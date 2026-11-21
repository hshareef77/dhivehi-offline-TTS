package com.hshareef77.dhivehiofflinetts

import com.blindtechnexus.freeeloquencetts.EvvNative

/**
 * Eloquence preset indices from the original Eci.kt/EvvEngine.kt.
 * Voices are copied from read-only presets into scratch, then current.
 */
internal object NativeVoices {
    val names = listOf("Reed", "Shelley", "Bobby", "Rocko", "Glen",
        "Sandy", "Grandma", "Grandpa", "Luna")
    private const val SCRATCH = 9
    private const val CURRENT = 0

    fun apply(handle: Long, chosen: Int, speed: Float, pitch: Float = 1f) {
        if (handle == 0L) return
        val preset = if (chosen == 8) 7 else chosen.coerceIn(0, 7) + 1
        EvvNative.copyVoice(handle, preset, SCRATCH)
        if (chosen == 8) {
            val shape = mapOf(0 to 1, 2 to 92, 3 to 70, 1 to 12,
                4 to 3, 5 to 38, 7 to 92)
            for ((parameter, value) in shape)
                EvvNative.setVoiceParam(handle, SCRATCH, parameter, value)
        }
        EvvNative.copyVoice(handle, SCRATCH, CURRENT)
        EvvNative.setVoiceParam(handle, CURRENT, 6,
            (speed * 50f).toInt().coerceIn(15, 250))
        // Baseline depends on the selected preset; user-controlled pitch
        // scales around neutral rather than resetting every voice to 50.
        if (pitch != 1f) {
            EvvNative.setVoiceParam(handle, CURRENT, 2,
                (50f * pitch).toInt().coerceIn(0, 100))
        }
    }
}
