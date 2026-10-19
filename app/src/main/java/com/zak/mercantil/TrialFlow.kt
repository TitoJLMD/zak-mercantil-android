package com.zak.mercantil

import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale

fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
    .replace(Regex("\\s+"), " ").trim().trimEnd(':', '.')

enum class Currency(val code: String, val label: String, val option: String) {
    USD("USD", "Dólares (USD)", "Dólares"), EUR("EUR", "Euros (EUR)", "Euros")
}

object Amounts {
    fun parse(text: String): BigDecimal? {
        val value = text.trim()
        if (!Regex("^[0-9]{1,9}([.,][0-9]{1,2})?$").matches(value)) return null
        return value.replace(',', '.').toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
    }
    fun forInput(amount: BigDecimal): String = amount.stripTrailingZeros().toPlainString().replace('.', ',')
}

data class TrialConfig(val amount: BigDecimal, val currency: Currency) {
    init { require(Amounts.parse(Amounts.forInput(amount)) != null) }
}

enum class Dropdown { CURRENCY, ORIGIN, MOTIVE }

data class TrialProgress(
    val amountSubmitted: Boolean = false,
    val detailsSubmitted: Boolean = false,
    val originSelected: Boolean = false,
    val motiveSelected: Boolean = false,
    val dropdown: Dropdown? = null,
    val declarationToggled: Boolean = false
)

data class BankSnapshot(val labels: List<String>, val amountText: String? = null, val pageLabels: List<String> = labels) {
    private val values = labels.map(::normalized)
    private val pageValues = pageLabels.map(::normalized)
    fun has(text: String): Boolean = values.any { it.contains(normalized(text)) }
    fun exact(text: String): Boolean = values.any { it == normalized(text) }
    fun pageHas(text: String): Boolean = pageValues.any { it.contains(normalized(text)) }
}

enum class Action {
    WAIT, OPEN_MENU, OPEN_MARKET, OPEN_BUY, OPEN_CURRENCY, SELECT_CURRENCY,
    SET_AMOUNT, SUBMIT_AMOUNT, OPEN_ORIGIN, SELECT_ORIGIN, OPEN_MOTIVE,
    SELECT_MOTIVE, SCROLL_OPTIONS, SUBMIT_DETAILS, CONTINUE_REVIEW,
    ACCEPT_DECLARATION, CONTINUE_DECLARATION,
    STOP_REVIEW, STOP_ERROR, STOP_SUCCESS, STOP_DECLARATION
}

data class Decision(val action: Action, val message: String) {
    val terminal: Boolean get() = action in setOf(
        Action.STOP_REVIEW, Action.STOP_ERROR, Action.STOP_SUCCESS, Action.STOP_DECLARATION
    )
}

object InteractionTiming {
    const val POLL_MS = 220L
    const val EVENT_MS = 80L
    fun delay(action: Action, previous: Action?): Long = when {
        previous == null -> 0L
        action != previous -> 220L
        action in setOf(Action.SUBMIT_AMOUNT, Action.SUBMIT_DETAILS) -> 1_400L
        action == Action.SCROLL_OPTIONS -> 480L
        else -> 650L
    }
}

/** Plans the configured route from the observed bank screen and session progress. */
object TrialFlow {
    fun decide(s: BankSnapshot, config: TrialConfig, progress: TrialProgress = TrialProgress()): Decision {
        if (s.has("Tu compra fue exitosa"))
            return Decision(Action.STOP_SUCCESS, "Mercantil muestra una compra exitosa. Asistente detenido.")
        if (s.has("Algo ha salido mal") || s.has("Intenta realizar esta operación más tarde") ||
            s.has("servicio no disponible") || s.has("saldo insuficiente"))
            return Decision(Action.STOP_ERROR, "Mercantil muestra un error. Revisa el resultado en el banco antes de volver a intentar.")
        // La declaración jurada va antes que la revisión: puede repetir textos del formulario.
        if (s.pageHas("Declaración jurada")) {
            if (!progress.declarationToggled)
                return Decision(Action.ACCEPT_DECLARATION, "Marcando Acepto la Declaración Jurada.")
            return Decision(Action.CONTINUE_DECLARATION, "Continuando. Este paso ejecuta la compra.")
        }
        if (s.pageHas("Verifica tu operación"))
            return Decision(Action.CONTINUE_REVIEW, "Aceptando la revisión para abrir la declaración jurada.")

        when (progress.dropdown) {
            Dropdown.ORIGIN -> return if (s.exact("Ahorros personales"))
                Decision(Action.SELECT_ORIGIN, "Seleccionando Ahorros personales.")
            else Decision(Action.SCROLL_OPTIONS, "Buscando Ahorros personales en la lista.")
            Dropdown.MOTIVE -> return if (s.exact("Ahorros"))
                Decision(Action.SELECT_MOTIVE, "Seleccionando Ahorros.")
            else Decision(Action.SCROLL_OPTIONS, "Buscando Ahorros en la lista.")
            Dropdown.CURRENCY -> return if (s.exact(config.currency.option) || s.exact(config.currency.label))
                Decision(Action.SELECT_CURRENCY, "Seleccionando la moneda elegida.")
            else Decision(Action.SCROLL_OPTIONS, "Buscando la moneda elegida en la lista.")
            null -> Unit
        }

        // Sheets overlay the form; recognize their options before the underlying page.
        if (s.exact("Ahorros personales") && s.exact("Actividad comercial de empresa"))
            return Decision(Action.SELECT_ORIGIN, "Seleccionando Ahorros personales.")
        if (s.exact("Ahorros") && s.exact("Remesas"))
            return Decision(Action.SELECT_MOTIVE, "Seleccionando Ahorros.")
        if ((s.exact("Dólares") || s.exact("Dólares (USD)")) &&
            (s.exact("Euros") || s.exact("Euros (EUR)")))
            return Decision(Action.SELECT_CURRENCY, "Seleccionando la moneda elegida.")

        if (s.pageHas("Detalle del Producto") && (s.pageHas("Origen de los fondos") || s.pageHas("Motivo de la compra") || progress.amountSubmitted)) {
            if (!progress.amountSubmitted)
                return Decision(Action.STOP_ERROR, "Hay un formulario anterior abierto. Vuelve a Mercado de divisas y reinicia la prueba para aplicar tu monto y moneda.")
            if (!progress.originSelected && !s.has("Ahorros personales"))
                return Decision(Action.OPEN_ORIGIN, "Mostrando Origen de los fondos.")
            if (!progress.motiveSelected && !s.exact("Ahorros") && !s.has("Motivo de la compra: Ahorros") && !s.has("Motivo de la compra Ahorros"))
                return Decision(Action.OPEN_MOTIVE, "Mostrando Motivo de la compra.")
            return Decision(Action.SUBMIT_DETAILS, "Avanzando al resumen con las cuentas seleccionadas por Mercantil.")
        }
        if (s.has("Tipo de moneda") && s.has("Comprar")) {
            if (!s.has(config.currency.label) && !s.exact(config.currency.option))
                return Decision(Action.OPEN_CURRENCY, "Abriendo Tipo de moneda.")
            val currentAmount = s.amountText?.let(Amounts::parse)
            if (currentAmount == null || currentAmount.compareTo(config.amount) != 0)
                return Decision(Action.SET_AMOUNT, "Escribiendo el monto elegido.")
            return Decision(Action.SUBMIT_AMOUNT, "Abriendo el detalle de la operación.")
        }
        if (s.has("Mercado de divisas") && s.has("Vender divisas") && s.has("Comprar divisas"))
            return Decision(Action.OPEN_BUY, "Abriendo Comprar divisas.")
        if (s.exact("Mercado de divisas") && s.has("Transferencias"))
            return Decision(Action.OPEN_MARKET, "Abriendo Mercado de divisas.")
        if (s.has("Resumen financiero"))
            return Decision(Action.OPEN_MENU, "Abre el menú inferior de Mercantil si no avanza automáticamente.")
        return Decision(Action.WAIT, "Inicia sesión en Mercantil y abre Mercado de divisas. Esperando una pantalla reconocida.")
    }
}
