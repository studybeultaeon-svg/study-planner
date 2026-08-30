package com.phonelock.app.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File
import java.io.FileOutputStream

/**
 * "무전기" 음성 메시지 재생 — WAV 바이트 배열을 캐시 임시 파일에 써서 [MediaPlayer]로 재생한다.
 * 볼륨은 STREAM_MUSIC 위에 앱 설정 배율([volumePercent])만 곱한다 — 기기가 무음/저볼륨이면 그대로
 * 안 들리거나 작게 들림(특수 스트림으로 무음설정을 뚫지 않음).
 */
object VoicePlayer {
    fun play(context: Context, wavBytes: ByteArray, volumePercent: Int, onCompletion: () -> Unit = {}) {
        val tempFile = File.createTempFile("walkie_", ".wav", context.cacheDir)
        FileOutputStream(tempFile).use { it.write(wavBytes) }
        val player = MediaPlayer()
        runCatching {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            player.setDataSource(tempFile.absolutePath)
            val volume = volumePercent.coerceIn(0, 100) / 100f
            player.setVolume(volume, volume)
            player.setOnCompletionListener {
                it.release()
                tempFile.delete()
                onCompletion()
            }
            player.prepare()
            player.start()
        }.onFailure {
            player.release()
            tempFile.delete()
            onCompletion()
        }
    }
}
