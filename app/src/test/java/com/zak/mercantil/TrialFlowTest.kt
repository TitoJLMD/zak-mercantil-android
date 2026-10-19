package com.zak.mercantil

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class TrialFlowTest {
    private val usd = TrialConfig(BigDecimal("20"), Currency.USD)
    private val eur = TrialConfig(BigDecimal("20.50"), Currency.EUR)
    private val prepared = TrialProgress(amountSubmitted = true)
    private val amountLabels = listOf("Comprar divisas", "Ingresa los datos", "Tipo de moneda:", "Dólares (USD)", "Comprar USD.", "Comprar")
    private val details = listOf("Detalle del Producto", "Desde mi cuenta:", "A mi cuenta:", "Origen de los fondos:", "Motivo de la compra:", "Comprar")

    @Test fun fullVideoRouteStopsAtReview() {
        fun expect(action: Action, labels: List<String>, value: String? = null, progress: TrialProgress = TrialProgress()) {
            assertEquals(action, TrialFlow.decide(BankSnapshot(labels, value), usd, progress).action)
        }
        expect(Action.OPEN_MARKET, listOf("Resumen financiero", "Transferencias", "Mercado de divisas"))
        expect(Action.OPEN_BUY, listOf("Mercado de divisas", "Comprar divisas", "Vender divisas"))
        expect(Action.SET_AMOUNT, amountLabels, "0,00")
        expect(Action.SUBMIT_AMOUNT, amountLabels, "20,00")
        expect(Action.OPEN_ORIGIN, details + "Selecciona el origen de los fondos", progress = prepared)
        expect(Action.SELECT_ORIGIN, details + listOf("Ahorros personales", "Actividad comercial de empresa"), progress = prepared)
        expect(Action.OPEN_MOTIVE, details + "Ahorros personales", progress = prepared)
        expect(Action.SELECT_MOTIVE, details + listOf("Ahorros personales", "Ahorros", "Remesas"), progress = prepared)
        expect(Action.SUBMIT_DETAILS, details + listOf("Ahorros personales", "Ahorros"), progress = prepared)
        expect(Action.STOP_REVIEW, listOf("Verifica tu operación", "Aceptar", "Cancelar"), progress = TrialProgress(true, true))
    }
    @Test fun reviewAlwaysStopsEvenWhenFormLabelsRemain() {
        val result = TrialFlow.decide(BankSnapshot(amountLabels + details + "Verifica tu operación", "20"), usd, prepared)
        assertTrue(result.terminal)
        assertEquals(Action.STOP_REVIEW, result.action)
    }
    @Test fun declarationCannotBeAcceptedByThisPlanner() {
        val result = TrialFlow.decide(BankSnapshot(listOf("Declaración jurada", "Acepto la Declaración Jurada", "Continuar")), usd)
        assertTrue(result.terminal)
        assertEquals(Action.STOP_DECLARATION, result.action)
    }
    @Test fun bankErrorDoesNotCauseAutomaticRetry() {
        val result = TrialFlow.decide(BankSnapshot(amountLabels + listOf("Algo ha salido mal...", "Intenta realizar esta operación más tarde.")), usd)
        assertTrue(result.terminal)
        assertEquals(Action.STOP_ERROR, result.action)
    }
    @Test fun successfulReceiptStopsEvenWithZeroReference() {
        val result = TrialFlow.decide(BankSnapshot(listOf("¡Listo! Tu compra fue exitosa", "Nro. de referencia: 0")), usd)
        assertTrue(result.terminal)
        assertEquals(Action.STOP_SUCCESS, result.action)
    }
    @Test fun currencyIsChangedBeforeWritingAmount() {
        assertEquals(Action.OPEN_CURRENCY, TrialFlow.decide(BankSnapshot(amountLabels, "20,50"), eur).action)
        assertEquals(Action.SELECT_CURRENCY, TrialFlow.decide(BankSnapshot(listOf("Dólares", "Euros")), eur).action)
    }
    @Test fun requestedAmountMustBeObservedBeforeAdvancing() {
        assertEquals(Action.SET_AMOUNT, TrialFlow.decide(BankSnapshot(amountLabels, "2,00"), usd).action)
        assertEquals(Action.SUBMIT_AMOUNT, TrialFlow.decide(BankSnapshot(amountLabels, "20,00"), usd).action)
    }
    @Test fun doesNotGuessAnAmountWhenTheFieldIsUnavailable() {
        assertEquals(Action.SET_AMOUNT, TrialFlow.decide(BankSnapshot(amountLabels), usd).action)
    }
    @Test fun previousFormCannotReuseOldAmount() {
        val result = TrialFlow.decide(BankSnapshot(details + listOf("Ahorros personales", "Ahorros")), eur)
        assertEquals(Action.STOP_ERROR, result.action)
    }
    @Test fun loginIsLeftForTheUser() {
        assertEquals(Action.WAIT, TrialFlow.decide(BankSnapshot(listOf("Clave de internet", "Ingresar", "Identificación biométrica")), usd).action)
    }
    @Test fun groupedAccessibleMotiveIsRecognized() {
        val labels = details + listOf("Origen de los fondos: Ahorros personales", "Motivo de la compra: Ahorros")
        assertEquals(Action.SUBMIT_DETAILS, TrialFlow.decide(BankSnapshot(labels), usd, prepared).action)
    }
    @Test fun amountInputRejectsAmbiguousOrInvalidValues() {
        for (invalid in listOf("0", "-1", "NaN", "1e3", "1.000,50", "2,345", "", "20abc")) assertNull(Amounts.parse(invalid))
        assertEquals(BigDecimal("20.50"), Amounts.parse("20,50"))
        assertEquals(BigDecimal("20.50"), Amounts.parse("20.50"))
        assertEquals("20,5", Amounts.forInput(BigDecimal("20.50")))
    }

    @Test fun offscreenFieldsDoNotMakeTheDetailsPageUnrecognizable() {
        val snapshot = BankSnapshot(listOf("Detalle del Producto", "Desde mi cuenta:"), pageLabels = details)
        assertEquals(Action.OPEN_ORIGIN, TrialFlow.decide(snapshot, usd, prepared).action)
    }

    @Test fun scrollingPastTheSelectedOriginDoesNotReopenIt() {
        val snapshot = BankSnapshot(listOf("Motivo de la compra:", "Selecciona el motivo de la compra"), pageLabels = details)
        assertEquals(Action.OPEN_MOTIVE, TrialFlow.decide(snapshot, usd,
            prepared.copy(originSelected = true)).action)
    }

    @Test fun offscreenBuyButtonIsStillTheNextPreparationStep() {
        val snapshot = BankSnapshot(listOf("Origen de los fondos:"), pageLabels = details)
        assertEquals(Action.SUBMIT_DETAILS, TrialFlow.decide(snapshot, usd,
            prepared.copy(originSelected = true, motiveSelected = true)).action)
    }

    @Test fun originSheetScrollsInsteadOfReopeningTheField() {
        val snapshot = BankSnapshot(listOf("Actividad comercial de empresa", "Ahorro con aporte del padre"),
            pageLabels = details + listOf("Ahorros personales"))
        assertEquals(Action.SCROLL_OPTIONS, TrialFlow.decide(snapshot, usd,
            prepared.copy(dropdown = Dropdown.ORIGIN)).action)
    }

    @Test fun visibleOriginDoesNotRequireAnotherOptionToBeVisible() {
        assertEquals(Action.SELECT_ORIGIN, TrialFlow.decide(BankSnapshot(listOf("Ahorros personales")), usd,
            prepared.copy(dropdown = Dropdown.ORIGIN)).action)
    }

    @Test fun currencySheetKeepsSearchingForTheChosenCurrency() {
        val progress = TrialProgress(dropdown = Dropdown.CURRENCY)
        assertEquals(Action.SCROLL_OPTIONS, TrialFlow.decide(BankSnapshot(listOf("Dólares")), eur, progress).action)
        assertEquals(Action.SELECT_CURRENCY, TrialFlow.decide(BankSnapshot(listOf("Euros")), eur, progress).action)
    }

    @Test fun offscreenReviewHeaderStillStopsThePreparationAssistant() {
        val snapshot = BankSnapshot(listOf("Aceptar", "Cancelar"), pageLabels = listOf("Verifica tu operación", "Aceptar", "Cancelar"))
        assertEquals(Action.STOP_REVIEW, TrialFlow.decide(snapshot, usd,
            prepared.copy(detailsSubmitted = true, dropdown = Dropdown.ORIGIN)).action)
    }

    @Test fun declarationStopsEvenWhenScrolledAndASelectorWasPending() {
        val snapshot = BankSnapshot(listOf("Acepto la Declaración Jurada", "Continuar"),
            pageLabels = listOf("Declaración jurada", "Acepto la Declaración Jurada", "Continuar"))
        assertEquals(Action.STOP_DECLARATION, TrialFlow.decide(snapshot, usd,
            prepared.copy(dropdown = Dropdown.MOTIVE)).action)
    }

    @Test fun nextStepRespondsFasterWhileRepeatedSubmissionStillWaits() {
        assertTrue(InteractionTiming.delay(Action.OPEN_MOTIVE, Action.SELECT_ORIGIN) < 400L)
        assertTrue(InteractionTiming.delay(Action.SUBMIT_DETAILS, Action.SUBMIT_DETAILS) > 1_000L)
        assertTrue(InteractionTiming.delay(Action.SUBMIT_DETAILS, Action.SUBMIT_DETAILS) >
            InteractionTiming.delay(Action.OPEN_MOTIVE, Action.SELECT_ORIGIN))
    }
}
