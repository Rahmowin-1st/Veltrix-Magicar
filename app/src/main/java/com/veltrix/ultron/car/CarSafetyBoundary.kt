package com.veltrix.ultron.car

/**
 * Hard boundary between infotainment/UI automation and vehicle actuation.
 *
 * Veltrix Magicar may operate the Android head-unit UI, media and apps. It must
 * never attempt to actuate steering, braking, throttle, transmission, ignition,
 * airbags or driver-assistance systems.
 */
object CarSafetyBoundary {
    private val directActuation = listOf(
        Regex("""\b(start|stop|kill|restart)\s+(the\s+)?engine\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(shift|switch|change|put)\s+(the\s+car\s+)?(into\s+)?(park|drive|reverse|neutral|gear)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(steer|turn)\s+(the\s+car\s+)?(left|right)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(apply|release|engage|disable)\s+(the\s+)?(parking\s+)?brake\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(press|apply|increase|decrease|set)\s+(the\s+)?(throttle|accelerator|gas pedal)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(enable|disable|override|change|set)\s+(the\s+)?(airbag|abs|traction control|stability control|adas|autopilot|lane keep|lane keeping|adaptive cruise|cruise control)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(turn|switch)\s+(the\s+)?ignition\s+(on|off)\b""", RegexOption.IGNORE_CASE)
    )

    fun blocksObjective(objective: String): Boolean {
        val clean = objective.trim()
        if (clean.isEmpty()) return false
        return directActuation.any { it.containsMatchIn(clean) }
    }

    const val MESSAGE =
        "Veltrix Magicar controls infotainment and Android UI only; vehicle driving or safety-critical actuation is not allowed."
}
