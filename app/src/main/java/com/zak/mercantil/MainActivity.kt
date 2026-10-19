package com.zak.mercantil

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var amount: EditText
    private lateinit var currency: Spinner
    private lateinit var serviceState: TextView
    private lateinit var status: TextView
    private lateinit var history: TextView
    private lateinit var start: Button
    private val poll = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 800) }
    }
    private val navy = Color.rgb(16, 47, 67)
    private val teal = Color.rgb(0, 126, 145)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Launcher intents are intentionally ignored. Only a button press starts a run.
        val prefs = getSharedPreferences("trial_config", MODE_PRIVATE)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
        }
        content.addView(text("ZAK Mercantil", 28, true))
        content.addView(text("PRUEBA DE RECORRIDO · 0.2", 12).apply { setTextColor(teal) })
        content.addView(card().apply {
            addView(text("Hasta la revisión de la compra", 18, true))
            addView(text("El asistente se detiene en “Verifica tu operación”. Tú revisas los datos y decides cómo continuar en Mercantil.", 14))
        })
        content.addView(card().apply {
            addView(text("Monto a comprar", 15, true))
            amount = EditText(this@MainActivity).apply {
                setText(prefs.getString("amount", "20"))
                hint = "Ejemplo: 20 o 20,50"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                filters = arrayOf(InputFilter.LengthFilter(12))
                textSize = 26f
                setSingleLine(true)
                contentDescription = "Monto a comprar"
            }
            addView(amount)
            addView(text("Moneda", 15, true))
            currency = Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                    Currency.entries.map { it.label })
                setSelection(if (prefs.getString("currency", "USD") == "EUR") 1 else 0)
                contentDescription = "Moneda de la compra"
            }
            addView(currency)
        })
        content.addView(card().apply {
            addView(text("Cuentas por defecto", 16, true))
            addView(text("Se conservan las cuentas que Mercantil tenga seleccionadas. Si están vacías, elígelas en el banco.", 14))
            addView(text("Origen: Ahorros personales\nMotivo: Ahorros", 14))
        })
        content.addView(card().apply {
            serviceState = text("", 15, true)
            addView(serviceState)
            addView(button("1 · Activar accesibilidad", false) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
            addView(text("Activa “ZAK Mercantil Prueba”. Después abre Mercantil e inicia sesión directamente en el banco.", 14))
            addView(button("2 · Abrir Mercantil", false) { openBank() })
        })
        start = button("3 · Probar recorrido", true) { begin() }
        content.addView(start)
        content.addView(button("Detener prueba", false) {
            TrialService.instance?.stop("Prueba detenida por ti.")
            refresh()
        }.apply { setTextColor(Color.rgb(174, 40, 50)) })
        content.addView(card().apply {
            status = text("", 16, true)
            history = text("", 13)
            addView(status)
            addView(history)
        })
        content.addView(text("Asistente independiente · No es una aplicación de Mercantil Banco.", 12).apply {
            gravity = Gravity.CENTER
        })
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() { super.onResume(); ui.post(poll) }
    override fun onPause() { ui.removeCallbacks(poll); super.onPause() }

    private fun begin() {
        val value = Amounts.parse(amount.text.toString())
        if (value == null) {
            amount.error = "Escribe un monto mayor que cero, con hasta dos decimales."
            return
        }
        val chosenCurrency = Currency.entries[currency.selectedItemPosition]
        getSharedPreferences("trial_config", MODE_PRIVATE).edit()
            .putString("amount", Amounts.forInput(value)).putString("currency", chosenCurrency.code).apply()
        val service = TrialService.instance
        if (service == null) {
            Toast.makeText(this, "Activa el servicio de accesibilidad y vuelve a intentarlo.", Toast.LENGTH_LONG).show()
            return
        }
        service.begin(TrialConfig(value, chosenCurrency))
        refresh()
    }

    private fun openBank() {
        val launch = packageManager.getLaunchIntentForPackage(TrialService.BANK_PACKAGE)
        if (launch == null) {
            Toast.makeText(this, "No se encontró Mercantil Móvil Personas instalado.", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(launch)
    }

    private fun refresh() {
        serviceState.text = if (TrialService.instance != null) "● Servicio conectado" else "○ Servicio sin conectar"
        serviceState.setTextColor(if (TrialService.instance != null) teal else navy)
        status.text = SessionStatus.message
        history.text = SessionStatus.history()
        start.isEnabled = !SessionStatus.running
        amount.isEnabled = !SessionStatus.running
        currency.isEnabled = !SessionStatus.running
    }

    private fun text(value: String, size: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(navy)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(5), 0, dp(5))
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(16).toFloat() }
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(4) }
    }
    private fun button(label: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f
        setTextColor(if (primary) Color.WHITE else teal)
        background = GradientDrawable().apply {
            setColor(if (primary) teal else Color.WHITE); cornerRadius = dp(12).toFloat()
            if (!primary) setStroke(dp(1), Color.rgb(210, 226, 231))
        }
        layoutParams = LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(10) }
        setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
