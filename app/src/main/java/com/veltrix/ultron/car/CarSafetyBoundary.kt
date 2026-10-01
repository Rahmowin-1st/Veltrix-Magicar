package com.veltrix.ultron.car

/**
 * Hard boundary between infotainment/UI automation and vehicle actuation.
 *
 * Veltrix Magicar may operate the Android head-unit UI, media and apps. It must
 * never attempt to actuate steering, braking, throttle, transmission, ignition,
 * airbags or driver-assistance systems.
 */
object CarSafetyBoundary {
    private val blocked = listOf(
        Regex("""\b(brake|braking|parking brake)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(steer|steering|steering wheel control)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(throttle|accelerator|gas pedal)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(gear|transmission|shift into|drive gear|reverse gear)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(ignition|start engine|stop engine)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(airbag|abs|traction control|stability control)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(adas|autopilot|lane keep|lane keeping|adaptive cruise|cruise control)\b""", RegexOption.IGNORE_CASE)
    )

    fun blocksObjective(objective: String): Boolean {
        val clean = objective.trim()
        if (clean.isEmpty()) return false
        return blocked.any { it.containsMatchIn(clean) }
    }

    const val MESSAGE =
        "Veltrix Magicar controls infotainment and Android UI only; vehicle driving or safety-critical actuation is not allowed."
}
