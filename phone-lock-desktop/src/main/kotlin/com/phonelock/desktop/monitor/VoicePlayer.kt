package com.phonelock.desktop.monitor

import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.LineEvent

/**
 * "무전기" 음성 메시지 재생 — WAV 바이트 배열을 [javax.sound.sampled.Clip]으로 재생한다. 볼륨은
 * MASTER_GAIN 컨트롤로 앱 설정 배율만 적용 — OS/기기 볼륨이나 무음 설정은 그대로 존중된다(뚫지 않음).
 */
object VoicePlayer {
    fun play(wavBytes: ByteArray, volumePercent: Int, onCompletion: () -> Unit = {}) {
        runCatching {
            val stream = AudioSystem.getAudioInputStream(ByteArrayInputStream(wavBytes))
            val clip = AudioSystem.getClip()
            clip.open(stream)
            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                val gainControl = clip.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
                val pct = volumePercent.coerceIn(0, 100) / 100f
                // 0% -> 최소(사실상 무음), 100% -> 0dB(원본 볼륨) 사이 선형 근사.
                gainControl.value = if (pct <= 0f) gainControl.minimum
                    else (gainControl.minimum * (1f - pct)).coerceIn(gainControl.minimum, gainControl.maximum)
            }
            clip.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP) {
                    clip.close()
                    onCompletion()
                }
            }
            clip.start()
        }.onFailure { onCompletion() }
    }
}
