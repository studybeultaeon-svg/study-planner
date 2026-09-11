package com.phonelock.app.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.phonelock.shared.GrowthSoundEffects

/**
 * "식물" 탭 경험치 적용/레벨업 효과음 재생(108차 후속, 데스크탑판과 대칭) — [GrowthSoundEffects]가
 * 합성한 16bit mono PCM을 [AudioTrack]에 STATIC 모드로 바로 실어 재생한다. 프로젝트에 번들 오디오
 * 에셋 파이프라인이 없어서(무전기 기능도 런타임에 WAV를 만들거나 임시파일로 재생한다,
 * [VoicePlayer.kt] 참고) 짧은 효과음은 파일 없이 직접 합성해 재생하는 쪽을 택했다. STREAM_MUSIC이라
 * 기기 볼륨/무음 설정을 그대로 존중한다(뚫지 않음).
 */
object GrowthSoundPlayer {
    fun playExpTick() = play(GrowthSoundEffects.expTickSamples())

    fun playLevelUp() = play(GrowthSoundEffects.levelUpSamples())

    private fun play(samples: ShortArray) {
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(GrowthSoundEffects.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(samples, 0, samples.size)
            track.setNotificationMarkerPosition(samples.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) {
                    t.release()
                }
                override fun onPeriodicNotification(t: AudioTrack) {}
            })
            track.play()
        }
    }
}
