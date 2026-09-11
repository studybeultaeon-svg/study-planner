package com.phonelock.desktop.monitor

import com.phonelock.shared.GrowthSoundEffects
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/**
 * "식물" 탭 경험치 적용/레벨업 효과음 재생(108차 후속) — [GrowthSoundEffects]가 합성한 16bit mono PCM을
 * [javax.sound.sampled.SourceDataLine]에 직접 흘려보낸다([VoicePlayer.kt]와 달리 WAV 헤더가 필요 없는
 * raw 스트림이라 더 가볍다). 짧은 효과음이라 매번 새 스레드+라인을 열고 끝나면 바로 닫는다.
 */
object GrowthSoundPlayer {
    private val format = AudioFormat(GrowthSoundEffects.SAMPLE_RATE.toFloat(), 16, 1, true, false)

    fun playExpTick() = play(GrowthSoundEffects.expTickSamples())

    fun playLevelUp() = play(GrowthSoundEffects.levelUpSamples())

    private fun play(samples: ShortArray) {
        Thread {
            runCatching {
                val bytes = ByteArray(samples.size * 2)
                for (i in samples.indices) {
                    val s = samples[i].toInt()
                    bytes[i * 2] = (s and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                }
                val line = AudioSystem.getSourceDataLine(format)
                line.open(format)
                line.start()
                line.write(bytes, 0, bytes.size)
                line.drain()
                line.close()
            }
        }.start()
    }
}
