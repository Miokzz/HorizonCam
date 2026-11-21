package com.miokzz.horizoncam.photo

/** Pure safety policy: a request, a queried capability and a successful bind are distinct states. */
internal class HdrEligibility {
    data class Decision(val enabled: Boolean, val reason: String?)
    private val availability = mutableMapOf<String, Decision>()
    private val rejected = mutableMapOf<String, String>()

    fun record(key: String, supported: Boolean, reason: String?) {
        availability[key] = Decision(supported, reason)
    }

    fun reject(key: String, reason: String) { rejected[key] = reason }

    fun decide(key: String, requested: Boolean, physical: Boolean): Decision = when {
        !requested -> Decision(false, null)
        physical -> Decision(false, "HDR não é oferecido na seleção de uma câmera física.")
        rejected.containsKey(key) -> Decision(false, rejected.getValue(key))
        else -> availability[key] ?: Decision(false, "Consulte a disponibilidade de HDR para esta câmera.")
    }
}
