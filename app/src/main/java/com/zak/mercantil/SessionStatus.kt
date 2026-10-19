package com.zak.mercantil

/** Only fixed workflow messages are retained; never bank labels, balances or credentials. */
object SessionStatus {
    var running = false
        private set
    var message = "Listo para configurar la prueba."
        private set
    private val events = ArrayDeque<String>()
    fun begin() { events.clear(); running = true; update("Prueba iniciada.") }
    fun update(text: String) {
        if (message == text) return
        message = text
        events.addLast(text)
        while (events.size > 30) events.removeFirst()
    }
    fun finish(text: String) { running = false; update(text) }
    fun history(): String = events.joinToString("\n\n")
}
