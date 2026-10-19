package com.zak.mercantil

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class TrialService : AccessibilityService() {
    private enum class Outcome { NONE, ACTED, SCROLLED }
    companion object {
        const val BANK_PACKAGE = "com.mercantilbanco.mercantilmovil"
        var instance: TrialService? = null
            private set
    }
    private val handler = Handler(Looper.getMainLooper())
    private var config: TrialConfig? = null
    private var deadline = 0L
    private var lastActionAt = 0L
    private val attempts = mutableMapOf<Action, Int>()
    private var stalledSince = 0L
    private var lastAction: Action? = null
    private var activeAction = Action.WAIT
    private var nextTickAt = 0L
    private var gestureInFlight = false
    private val scrollAttempts = mutableMapOf<Action, Int>()
    private var progress = TrialProgress()
    private var menuHintShown = false
    private val tick = object : Runnable {
        override fun run() { nextTickAt = 0L; inspect(); schedule(InteractionTiming.POLL_MS) }
    }

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() == BANK_PACKAGE) schedule(InteractionTiming.EVENT_MS)
    }

    private fun schedule(delay: Long) {
        if (!SessionStatus.running) return
        val target = SystemClock.uptimeMillis() + delay
        if (nextTickAt != 0L && nextTickAt <= target) return
        handler.removeCallbacks(tick)
        nextTickAt = target
        handler.postDelayed(tick, delay)
    }
    override fun onInterrupt() { stop("Accesibilidad interrumpida. Prueba detenida.") }
    override fun onUnbind(intent: Intent?): Boolean {
        stop("Servicio desconectado.")
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        stop("Servicio desconectado.")
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Only the local Activity invokes this method; no exported run command exists. */
    fun begin(value: TrialConfig) {
        if (SessionStatus.running) return
        val launch = packageManager.getLaunchIntentForPackage(BANK_PACKAGE)
        if (launch == null) { SessionStatus.finish("Instala Mercantil Móvil Personas para probar."); return }
        config = value
        attempts.clear(); scrollAttempts.clear(); stalledSince = 0L; lastActionAt = 0L
        lastAction = null; nextTickAt = 0L; gestureInFlight = false
        progress = TrialProgress(); menuHintShown = false
        deadline = SystemClock.uptimeMillis() + 180_000L
        SessionStatus.begin()
        try {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            handler.removeCallbacks(tick)
            schedule(220)
        } catch (_: Exception) { stop("No pude abrir Mercantil. Ábrelo y vuelve a iniciar la prueba.") }
    }

    fun stop(reason: String) {
        handler.removeCallbacks(tick)
        nextTickAt = 0L
        config = null
        if (SessionStatus.running) SessionStatus.finish(reason)
    }

    private fun finish(reason: String) {
        stop(reason)
        Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
        try {
            (getSystemService(VIBRATOR_SERVICE) as? Vibrator)?.vibrate(VibrationEffect.createOneShot(180, 80))
        } catch (_: Exception) { }
    }

    private fun inspect() {
        val selected = config ?: return
        if (SystemClock.uptimeMillis() >= deadline) {
            finish("Terminó el tiempo de prueba. Revisa el último paso en ZAK Mercantil.")
            return
        }
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        try {
            // Never act on another app, on a system authentication prompt, or on the keyboard.
            if (root.packageName?.toString() != BANK_PACKAGE) return
            val pending = java.util.ArrayDeque<AccessibilityNodeInfo>()
            pending.add(root)
            while (pending.isNotEmpty() && nodes.size < 900) {
                val n = pending.removeFirst()
                nodes.add(n)
                for (i in 0 until n.childCount) n.getChild(i)?.let { pending.add(it) }
            }
            // Retain all references for cleanup even when the size guard stops traversal.
            while (pending.isNotEmpty()) nodes.add(pending.removeFirst())
            val readable = nodes.filter { !it.isPassword }
            val visible = readable.filter { it.isVisibleToUser }
            val labels = visible.flatMap { listOfNotNull(it.text?.toString(), it.contentDescription?.toString()) }
                .filter { it.isNotBlank() }.distinct()
            val pageLabels = readable.flatMap { listOfNotNull(it.text?.toString(), it.contentDescription?.toString()) }
                .filter { it.isNotBlank() }.distinct()
            val fields = visible.filter { it.isEnabled && (it.isEditable || it.className?.toString()?.endsWith("EditText") == true) }
            val numeric = fields.filter { it.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER }
            val editable = numeric.ifEmpty { fields }
            val snapshot = BankSnapshot(labels, editable.singleOrNull()?.text?.toString(), pageLabels)
            val decision = TrialFlow.decide(snapshot, selected, progress)
            SessionStatus.update(decision.message)
            if (decision.terminal) { finish(decision.message); return }
            if (decision.action == Action.WAIT) return
            if (gestureInFlight) return
            if (visible.any { it.className?.toString()?.endsWith("ProgressBar") == true }) return
            if (SystemClock.uptimeMillis() - lastActionAt < InteractionTiming.delay(decision.action, lastAction)) return
            lastActionAt = SystemClock.uptimeMillis()
            lastAction = decision.action
            activeAction = decision.action
            if ((attempts[decision.action] ?: 0) >= 3) {
                finish("Este paso no cambió de pantalla. Completa el paso en Mercantil y vuelve a iniciar la prueba.")
                return
            }
            if ((scrollAttempts[decision.action] ?: 0) >= 10) {
                finish("No pude mostrar el control después de desplazar la pantalla. Revisa el último paso en ZAK Mercantil.")
                return
            }
            val performed = perform(decision.action, readable, editable, selected)
            if (performed == Outcome.ACTED) {
                if (decision.action == Action.SUBMIT_AMOUNT) progress = progress.copy(amountSubmitted = true)
                if (decision.action == Action.SUBMIT_DETAILS) progress = progress.copy(detailsSubmitted = true)
                if (decision.action == Action.OPEN_ORIGIN) progress = progress.copy(dropdown = Dropdown.ORIGIN)
                if (decision.action == Action.OPEN_MOTIVE) progress = progress.copy(dropdown = Dropdown.MOTIVE)
                if (decision.action == Action.OPEN_CURRENCY) progress = progress.copy(dropdown = Dropdown.CURRENCY)
                if (decision.action in setOf(Action.OPEN_ORIGIN, Action.OPEN_MOTIVE, Action.OPEN_CURRENCY))
                    scrollAttempts.remove(Action.SCROLL_OPTIONS)
                if (decision.action == Action.SELECT_ORIGIN) progress = progress.copy(dropdown = null, originSelected = true)
                if (decision.action == Action.SELECT_MOTIVE) progress = progress.copy(dropdown = null, motiveSelected = true)
                if (decision.action == Action.SELECT_CURRENCY) progress = progress.copy(dropdown = null)
                attempts[decision.action] = (attempts[decision.action] ?: 0) + 1
                scrollAttempts.remove(decision.action)
                stalledSince = 0L
            } else if (performed == Outcome.SCROLLED) {
                scrollAttempts[decision.action] = (scrollAttempts[decision.action] ?: 0) + 1
                stalledSince = 0L
            } else if (decision.action == Action.OPEN_MENU && !menuHintShown) {
                menuHintShown = true
                Toast.makeText(this, "Toca la flecha amarilla del menú inferior de Mercantil para continuar la prueba.", Toast.LENGTH_LONG).show()
            } else if (decision.action != Action.OPEN_MENU) {
                if (stalledSince == 0L) stalledSince = SystemClock.uptimeMillis()
                if (SystemClock.uptimeMillis() - stalledSince > 20_000)
                    finish("No pude accionar el control de esta pantalla. Revisa el último paso en ZAK Mercantil.")
            }
        } catch (_: Exception) {
            finish("La pantalla cambió durante la prueba. Revisa Mercantil antes de volver a iniciar.")
        } finally {
            if (nodes.none { it === root }) recycle(root)
            nodes.forEach(::recycle)
        }
    }

    private fun perform(action: Action, nodes: List<AccessibilityNodeInfo>,
                        editable: List<AccessibilityNodeInfo>, selected: TrialConfig): Outcome = when (action) {
        Action.OPEN_MENU -> clickLabel(nodes, "Menú", "Abrir menú", "Mostrar menú", "Más", "Expandir", "Opciones")
        Action.OPEN_MARKET -> clickLabel(nodes, "Mercado de divisas")
        Action.OPEN_BUY -> clickLabel(nodes, "Comprar divisas")
        Action.OPEN_CURRENCY -> clickLabel(nodes, "Selecciona el tipo de moneda", "Selecciona tipo de moneda",
            "Dólares (USD)", "Euros (EUR)", "Dólares", "Euros", "Tipo de moneda")
        Action.SELECT_CURRENCY -> clickLabel(nodes, selected.currency.option, selected.currency.label)
        Action.SET_AMOUNT -> {
            val field = editable.singleOrNull()
            if (field == null || field.isPassword || !field.isEnabled) Outcome.NONE
            else if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, Amounts.forInput(selected.amount))
            })) Outcome.ACTED else Outcome.NONE
        }
        Action.SUBMIT_AMOUNT, Action.SUBMIT_DETAILS -> clickLabel(nodes, "Comprar")
        Action.OPEN_ORIGIN -> clickLabel(nodes, "Selecciona el origen de los fondos", "Origen de los fondos",
            "Actividad comercial de empresa", "Ahorro con aporte del padre")
        Action.SELECT_ORIGIN -> clickLabel(nodes, "Ahorros personales")
        Action.OPEN_MOTIVE -> clickLabel(nodes, "Selecciona el motivo de la compra", "Motivo de la compra", "Remesas")
        Action.SELECT_MOTIVE -> clickLabel(nodes, "Ahorros")
        Action.SCROLL_OPTIONS -> {
            val target = when (progress.dropdown) {
                Dropdown.ORIGIN -> "Ahorros personales"
                Dropdown.MOTIVE -> "Ahorros"
                Dropdown.CURRENCY -> selected.currency.option
                null -> ""
            }
            reveal(nodes, nodes.firstOrNull { node -> listOf(node.text, node.contentDescription)
                .any { it != null && normalized(it.toString()) == normalized(target) } }, true)
        }
      Action.CONTINUE_REVIEW -> clickLabel(nodes, "Aceptar")
      Action.ACCEPT_DECLARATION -> toggleLabel(nodes, "Acepto la Declaración Jurada")
      Action.CONTINUE_DECLARATION -> clickLabel(nodes, "Continuar")
       // Explicit allowlist: no click exists for Aceptar, Continuar or the sworn declaration.
       // Explicit allowlist: cada acción nueva está declarada arriba.
         else -> Outcome.NONE        else -> Outcome.NONE
    }

    private fun clickLabel(nodes: List<AccessibilityNodeInfo>, vararg names: String): Outcome {
        var hidden: AccessibilityNodeInfo? = null
        var disabled = false
        for (name in names) {
            val expected = normalized(name)
            for (node in nodes) {
                val fieldLabel = expected in setOf("origen de los fondos", "motivo de la compra", "tipo de moneda")
                if (listOf(node.text, node.contentDescription).none {
                        if (it == null) false else {
                            val label = normalized(it.toString())
                            label == expected || (fieldLabel && label.length < 180 &&
                                (label.startsWith("$expected ") || label.startsWith("$expected:")))
                        }
                    }) continue
                if (!node.isVisibleToUser) { if (hidden == null) hidden = node; continue }
                if (!node.isEnabled) { disabled = true; continue }
                if (clickAncestor(node)) return Outcome.ACTED
            }
        }
        if (disabled) return Outcome.NONE
        if (activeAction == Action.OPEN_MENU) return Outcome.NONE
        return reveal(nodes, hidden, progress.dropdown != null)
        private fun toggleLabel(nodes: List<AccessibilityNodeInfo>, name: String): Outcome {
    val expected = normalized(name)
    val label = nodes.firstOrNull { n ->
        listOf(n.text, n.contentDescription).any { it != null && normalized(it.toString()) == expected }
    } ?: return Outcome.NONE
    // El switch puede ser el nodo, un hermano de la fila, o el ancestro clickable.
    val row = label.parent
    val candidates = buildList {
        add(label)
        row?.let { add(it) }
        if (row != null) for (i in 0 until row.childCount) row.getChild(i)?.let { add(it) }
    }
    candidates.firstOrNull { it.isCheckable }?.let { sw ->
        if (sw.isChecked) return Outcome.ACTED          // ya marcado: no lo apaga
        if (sw.isClickable && sw.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return Outcome.ACTED
    }
    return if (clickAncestor(label)) Outcome.ACTED else Outcome.NONE
}
    }

    private fun reveal(nodes: List<AccessibilityNodeInfo>, target: AccessibilityNodeInfo?, popup: Boolean): Outcome {
        if (gestureInFlight) return Outcome.NONE
        // First ask the actual target's parent hierarchy to bring it into view.
        if (target != null && (scrollAttempts[activeAction] ?: 0) == 0 &&
            target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)) return Outcome.SCROLLED
        val root = nodes.firstOrNull() ?: return Outcome.NONE
        val window = Rect().also { root.getBoundsInScreen(it) }
        if (window.width() <= 0 || window.height() <= 0) return Outcome.NONE
        val targetBounds = target?.let { n -> Rect().also { n.getBoundsInScreen(it) } }
        val backwards = targetBounds != null && targetBounds.height() > 0 && targetBounds.bottom < window.top + window.height() / 6
        val candidates = nodes.filter { node ->
            node.isVisibleToUser && node.isEnabled && node.isScrollable &&
                node.className?.toString()?.contains("HorizontalScrollView") != true
        }.map { node -> node to Rect().also { node.getBoundsInScreen(it) } }
            .filter { (_, r) -> r.width() > window.width() / 2 && r.height() > 100 }
        val ordered = if (popup) candidates.sortedByDescending { it.second.top }
            else candidates.sortedByDescending { it.second.width().toLong() * it.second.height() }
        for ((node, _) in ordered) {
            if (node.performAction(if (backwards) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                return Outcome.SCROLLED
        }
        // Some bank controls expose no scroll action. Use only a swipe in the bank's active window.
        val live = rootInActiveWindow ?: return Outcome.NONE
        val stillBank = try { live.packageName?.toString() == BANK_PACKAGE } finally { recycle(live) }
        if (!stillBank) return Outcome.NONE
        val area = ordered.firstOrNull()?.second?.let { Rect(it) } ?: Rect(window).apply {
            top = window.top + (window.height() * if (popup) 0.68f else 0.20f).toInt()
            bottom = window.top + (window.height() * 0.90f).toInt()
        }
        if (!area.intersect(window) || area.height() < 100) return Outcome.NONE
        val lower = area.top + area.height() * 0.82f
        val upper = area.top + area.height() * 0.25f
        val path = Path().apply {
            moveTo(area.exactCenterX(), if (backwards) upper else lower)
            lineTo(area.exactCenterX(), if (backwards) lower else upper)
        }
        gestureInFlight = true
        val accepted = dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 230)).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { gestureInFlight = false; schedule(100) }
                override fun onCancelled(gestureDescription: GestureDescription?) { gestureInFlight = false; schedule(250) }
            }, handler)
        if (!accepted) gestureInFlight = false
        return if (accepted) Outcome.SCROLLED else Outcome.NONE
    }

    private fun clickAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        try {
            repeat(5) {
                val target = current ?: return false
                if (!target.isEnabled || !target.isVisibleToUser) return false
                if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                val parent = target.parent
                if (target !== node) recycle(target)
                current = parent
            }
            return false
        } finally { current?.takeIf { it !== node }?.let(::recycle) }
    }

    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo) { try { node.recycle() } catch (_: Exception) { } }
}
