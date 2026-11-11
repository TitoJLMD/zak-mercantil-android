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
        // Dimensiones base de la captura (1080 x 2400) donde X=1003.5, Y=1592.6
        private const val REF_W = 1080f
        private const val REF_H = 2400f
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

    private fun collectBankRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                val r = try { w.root } catch (_: Exception) { null } ?: continue
                if (r.packageName?.toString() == BANK_PACKAGE) roots.add(r) else recycle(r)
            }
        } catch (_: Exception) { }
        if (roots.isEmpty()) {
            val active = try { rootInActiveWindow } catch (_: Exception) { null }
            if (active != null) {
                if (active.packageName?.toString() == BANK_PACKAGE) roots.add(active) else recycle(active)
            }
        }
        return roots
    }

    private fun inspect() {
        val selected = config ?: return
        if (SystemClock.uptimeMillis() >= deadline) {
            finish("Terminó el tiempo de prueba. Revisa el último paso en ZAK Mercantil.")
            return
        }
        val roots = collectBankRoots()
        if (roots.isEmpty()) return
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        try {
            val pending = java.util.ArrayDeque<AccessibilityNodeInfo>()
            roots.forEach { pending.add(it) }
            while (pending.isNotEmpty() && nodes.size < 1200) {
                val n = pending.removeFirst()
                nodes.add(n)
                for (i in 0 until n.childCount) n.getChild(i)?.let { pending.add(it) }
            }
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
            val declarationChecked = readable.any { isSwitchOrCheck(it) && it.isChecked }

            val snapshot = BankSnapshot(labels, editable.singleOrNull()?.text?.toString(), pageLabels, declarationChecked)
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

            val maxAttempts = if (decision.action in setOf(Action.ACCEPT_DECLARATION, Action.CONTINUE_DECLARATION)) 8 else 3
            if ((attempts[decision.action] ?: 0) >= maxAttempts) {
                finish("Este paso no cambió de pantalla tras varios intentos. Revisa Mercantil.")
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
                if (decision.action == Action.CONTINUE_REVIEW) {
                    progress = progress.copy(reviewAccepted = true)
                }
                if (decision.action == Action.ACCEPT_DECLARATION) {
                    progress = progress.copy(declarationToggled = true)
                }
                if (decision.action == Action.CONTINUE_DECLARATION && !declarationChecked) {
                    // Si pulsó Continuar y aún no avanza, vuelve a accionar el switch con la siguiente coordenada/gesto
                    progress = progress.copy(declarationToggled = false)
                }
                attempts[decision.action] = (attempts[decision.action] ?: 0) + 1
                scrollAttempts.remove(decision.action)
                stalledSince = 0L
            } else if (performed == Outcome.SCROLLED) {
                scrollAttempts[decision.action] = (scrollAttempts[decision.action] ?: 0) + 1
                stalledSince = 0L
            } else if (decision.action == Action.CONTINUE_DECLARATION && !declarationChecked) {
                progress = progress.copy(declarationToggled = false)
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
            roots.forEach { r -> if (nodes.none { it === r }) recycle(r) }
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
        Action.CONTINUE_REVIEW -> clickLabel(nodes, "Aceptar", "Continuar", "Confirmar")
        Action.ACCEPT_DECLARATION -> toggleDeclarationByCoords(nodes)
        Action.CONTINUE_DECLARATION -> clickContinueByCoords(nodes)
        else -> Outcome.NONE
    }

    private fun isSwitchOrCheck(node: AccessibilityNodeInfo): Boolean {
        if (node.isCheckable) return true
        val cls = node.className?.toString().orEmpty()
        return cls.contains("Switch", ignoreCase = true) ||
            cls.contains("CheckBox", ignoreCase = true) ||
            cls.contains("ToggleButton", ignoreCase = true) ||
            cls.contains("CompoundButton", ignoreCase = true)
    }

    private fun scaleX(refX: Float): Float {
        val w = resources.displayMetrics.widthPixels.toFloat().takeIf { it > 0f } ?: REF_W
        return refX * (w / REF_W)
    }

    private fun scaleY(refY: Float): Float {
        val h = resources.displayMetrics.heightPixels.toFloat().takeIf { it > 0f } ?: REF_H
        return refY * (h / REF_H)
    }

    /**
     * Usa las coordenadas medidas en el dispositivo (base 1080x2400):
     * Switch Declaración Jurada: X = 825..1003.5, Y = 1580..1592.6
     */
    private fun toggleDeclarationByCoords(nodes: List<AccessibilityNodeInfo>): Outcome {
        val tryIndex = attempts[Action.ACCEPT_DECLARATION] ?: 0

        // Si el árbol de accesibilidad expone un Switch nativo y aún no se ha intentado, probarlo
        val allSwitches = nodes.filter { it.isVisibleToUser && it.isEnabled && isSwitchOrCheck(it) }
        for (sw in allSwitches) {
            if (sw.isChecked) return Outcome.ACTED
        }

        return when (tryIndex % 4) {
            0 -> {
                // Intento 1: Tap exacto en el centro-derecha del switch (X=910, Y=1585)
                if (tapPoint(scaleX(910f), scaleY(1585f))) Outcome.ACTED else Outcome.NONE
            }
            1 -> {
                // Intento 2: El mismo trazo (swipe) de tu captura: desde X=830, Y=1580 hasta X=1003.5, Y=1592.6
                if (swipeGesture(scaleX(830f), scaleY(1580f), scaleX(1003.5f), scaleY(1592.6f), 190L))
                    Outcome.ACTED else Outcome.NONE
            }
            2 -> {
                // Intento 3: Tap en la posición exacta del puntero de tu captura (X=955, Y=1592.6)
                if (tapPoint(scaleX(955f), scaleY(1592.6f))) Outcome.ACTED else Outcome.NONE
            }
            else -> {
                // Intento 4: Tap sobre la bolita izquierda del switch cuando está apagado (X=845, Y=1582)
                if (tapPoint(scaleX(845f), scaleY(1582f))) Outcome.ACTED else Outcome.NONE
            }
        }
    }

    /**
     * Pulsa el botón "Continuar" en Declaración Jurada (centro exacto en 1080x2400: X=540, Y=1800)
     */
    private fun clickContinueByCoords(nodes: List<AccessibilityNodeInfo>): Outcome {
        val tryIndex = attempts[Action.CONTINUE_DECLARATION] ?: 0
        val target = nodes.firstOrNull { n ->
            n.isVisibleToUser && listOf(n.text, n.contentDescription).any {
                it != null && normalized(it.toString()) in setOf("continuar", "aceptar", "confirmar")
            }
        }
        if (target != null && tryIndex % 2 == 1) {
            if (clickAncestor(target)) return Outcome.ACTED
            if (tapBounds(target)) return Outcome.ACTED
        }
        // Toque directo en el centro del botón azul Continuar (X=540, Y=1800)
        if (tapPoint(scaleX(540f), scaleY(1800f))) return Outcome.ACTED
        if (target != null && clickAncestor(target)) return Outcome.ACTED
        return Outcome.NONE
    }

    private fun tapBounds(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        return tapPoint(bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun tapPoint(x: Float, y: Float): Boolean {
        if (gestureInFlight) return false
        if (x <= 0f || y <= 0f) return false
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        gestureInFlight = true
        val accepted = dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 95)).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { gestureInFlight = false; schedule(220) }
                override fun onCancelled(gestureDescription: GestureDescription?) { gestureInFlight = false; schedule(280) }
            },
            handler
        )
        if (!accepted) gestureInFlight = false
        return accepted
    }

    private fun swipeGesture(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean {
        if (gestureInFlight) return false
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        gestureInFlight = true
        val accepted = dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { gestureInFlight = false; schedule(220) }
                override fun onCancelled(gestureDescription: GestureDescription?) { gestureInFlight = false; schedule(280) }
            },
            handler
        )
        if (!accepted) gestureInFlight = false
        return accepted
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
                if (tapBounds(node)) return Outcome.ACTED
            }
        }
        if (disabled) return Outcome.NONE
        if (activeAction == Action.OPEN_MENU) return Outcome.NONE
        return reveal(nodes, hidden, progress.dropdown != null)
    }

    private fun reveal(nodes: List<AccessibilityNodeInfo>, target: AccessibilityNodeInfo?, popup: Boolean): Outcome {
        if (gestureInFlight) return Outcome.NONE
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
        }.map { node -> node to Rect().also { n.getBoundsInScreen(it) } }
            .filter { (_, r) -> r.width() > window.width() / 2 && r.height() > 100 }
        val ordered = if (popup) candidates.sortedByDescending { it.second.top }
            else candidates.sortedByDescending { it.second.width().toLong() * it.second.height() }
        for ((node, _) in ordered) {
            if (node.performAction(if (backwards) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                return Outcome.SCROLLED
        }
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
