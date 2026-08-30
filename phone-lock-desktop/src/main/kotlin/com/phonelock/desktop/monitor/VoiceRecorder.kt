package com.phonelock.desktop.monitor

import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * "무전기" 음성 메시지 녹음 — JDK 표준 `javax.sound.sampled`(`TargetDataLine`)로 원시 PCM을 캡처해
 * 표준 WAV로 저장한다(안드로이드와 동일 스펙: 8kHz mono 16-bit PCM, 최대 [MAX_DURATION_MS] — 새
 * 코덱 의존성 없이 양 플랫폼이 서로 재생할 수 있게 하기 위함).
 */
object VoiceRecorder {
    const val SAMPLE_RATE = 8000
    const val MAX_DURATION_MS = 10_000L

    /** 녹음 1회 세션 — [start]는 [stop] 호출 또는 [MAX_DURATION_MS] 도달 시 반환된다(블로킹, 별도 스레드에서 호출할 것). */
    class Session {
        @Volatile private var recording = false
        private val pcmBuffer = ByteArrayOutputStream()

        fun start(onDurationTick: (Long) -> Unit) {
            val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)
            if (!AudioSystem.isLineSupported(info)) return
            val line = AudioSystem.getLine(info) as TargetDataLine
            runCatching {
                line.open(format)
                line.start()
                recording = true
                val buffer = ByteArray(2048)
                val startedAt = System.currentTimeMillis()
                while (recording) {
                    val read = line.read(buffer, 0, buffer.size)
                    if (read > 0) pcmBuffer.write(buffer, 0, read)
                    val elapsed = System.currentTimeMillis() - startedAt
                    onDurationTick(elapsed)
                    if (elapsed >= MAX_DURATION_MS) recording = false
                }
            }
            line.stop()
            line.close()
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
