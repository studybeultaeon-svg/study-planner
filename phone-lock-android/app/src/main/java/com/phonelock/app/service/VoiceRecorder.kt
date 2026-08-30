package com.phonelock.app.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "무전기" 음성 메시지 녹음 — [AudioRecord]로 원시 PCM을 캡처해 표준 WAV로 감싼다. 데스크탑도 별도
 * 코덱 라이브러리 없이 그대로 재생할 수 있어야 해서, MediaRecorder의 기본 출력(3GP/AMR)이 아니라
 * 이 방식을 쓴다(양 플랫폼 공통 스펙: 8kHz mono 16-bit PCM, 최대 [MAX_DURATION_MS]).
 */
object VoiceRecorder {
    const val SAMPLE_RATE = 8000
    const val MAX_DURATION_MS = 10_000L
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 녹음 1회 세션 — [start]는 [stop] 호출 또는 [MAX_DURATION_MS] 도달 시 반환되는 suspend 함수. */
    class Session(private val context: Context) {
        @Volatile private var recording = false
        private val pcmBuffer = ByteArrayOutputStream()

        suspend fun start(onDurationTick: (Long) -> Unit) = withContext(Dispatchers.IO) {
            if (!hasPermission(context)) return@withContext
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minBufferSize <= 0) return@withContext
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, minBufferSize * 2
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return@withContext }
            recording = true
            record.startRecording()
            val chunk = ByteArray(minBufferSize)
            val startedAt = System.currentTimeMillis()
            while (recording) {
                val read = record.read(chunk, 0, chunk.size)
                if (read > 0) pcmBuffer.write(chunk, 0, read)
                val elapsed = System.currentTimeMillis() - startedAt
                onDurationTick(elapsed)
                if (elapsed >= MAX_DURATION_MS) recording = false
            }
            record.stop()
            record.release()
        }

        fun stop() { recording = false }

        /** WAV(44바이트 헤더 + PCM) 바이트 배열 — [start]가 끝난 뒤 호출. */
        fun toWavBytes(): ByteArray {
            val pcm = pcmBuffer.toByteArray()
            return writeWavHeader(pcm.size, SAMPLE_RATE, 1, 16) + pcm
        }

        val durationMs: Long get() = (pcmBuffer.size().toLong() * 1000L) / (SAMPLE_RATE * 2)
    }

    private fun writeWavHeader(dataSize: Int, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray())
        header.putInt(dataSize)
        return header.array()
    }
}
