package br.ufmg.sistole.engine

import android.media.AudioManager
import android.media.ToneGenerator

/** Bipe por batimento e som de alarme. */
class Beeper {
    private val tone: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()

    fun beat() {
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 40) }
    }

    fun alarm() {
        runCatching { tone?.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 450) }
    }
}
