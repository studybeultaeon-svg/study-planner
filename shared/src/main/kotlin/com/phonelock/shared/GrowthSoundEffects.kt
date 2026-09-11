package com.phonelock.shared

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * "식물" 탭 경험치 적용/레벨업 효과음(108차 후속) — 프로젝트에 번들 오디오 에셋 파이프라인이 없어서
 * (무전기 기능도 파일을 배포하지 않고 런타임에 WAV를 직접 만들거나 재생한다, [[VoicePlayer.kt]] 참고)
 * 16bit mono PCM 샘플을 순수 Kotlin으로 직접 합성한다. 플랫폼별 재생기(desktop
 * `monitor/GrowthSoundPlayer.kt`, android `service/GrowthSoundPlayer.kt`)가 이 샘플을 그대로 재생한다.
 */
object GrowthSoundEffects {
    const val SAMPLE_RATE = 44100

    /** 경험치가 주입되는 순간의 짧은 "틱" 효과음 — 음 높이가 빠르게 올라가는 단일 블립.
     *  진폭은 0.5였다가 "소리가 너무 크다"는 피드백으로 0.2로 낮췄다. */
    fun expTickSamples(): ShortArray = toneSweep(startHz = 520.0, endHz = 880.0, durationMs = 90, amplitude = 0.2)

    /** 레벨업 순간의 3음 상승 아르페지오(도-미-솔 느낌) — 각 음이 살짝 겹치며 밝게 울린다.
     *  진폭은 0.55였다가 "소리가 너무 크다"는 피드백으로 0.24로 낮췄다. */
    fun levelUpSamples(): ShortArray {
        val notes = listOf(523.25, 659.25, 783.99, 1046.50) // C5, E5, G5, C6
        val noteDurationMs = 110
        val overlapMs = 35
        val stepSamples = ((noteDurationMs - overlapMs) * SAMPLE_RATE / 1000.0).toInt()
        val toneSamples = notes.map { tone(it, noteDurationMs, amplitude = 0.24) }
        val totalLength = stepSamples * (notes.size - 1) + toneSamples.last().size
        val out = DoubleArray(totalLength)
        toneSamples.forEachIndexed { i, samples ->
            val offset = stepSamples * i
            for (j in samples.indices) {
                val idx = offset + j
                if (idx < out.size) out[idx] += samples[j]
            }
        }
        return out.map { it.coerceIn(-1.0, 1.0) }.let { doublesToPcm(it) }
    }

    private fun tone(hz: Double, durationMs: Int, amplitude: Double): DoubleArray {
        val n = (durationMs * SAMPLE_RATE / 1000.0).toInt()
        val out = DoubleArray(n)
        for (i in 0 until n) {
            val t = i / SAMPLE_RATE.toDouble()
            val envelope = attackDecayEnvelope(i, n)
            out[i] = sin(2.0 * PI * hz * t) * amplitude * envelope
        }
        return out
    }

    private fun toneSweep(startHz: Double, endHz: Double, durationMs: Int, amplitude: Double): ShortArray {
        val n = (durationMs * SAMPLE_RATE / 1000.0).toInt()
        val out = DoubleArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val frac = i / n.toDouble()
            val hz = startHz + (endHz - startHz) * frac
            phase += 2.0 * PI * hz / SAMPLE_RATE
            val envelope = attackDecayEnvelope(i, n)
            out[i] = sin(phase) * amplitude * envelope
        }
        return doublesToPcm(out)
    }

    /** 빠른 어택 + 부드러운 감쇠 — 클릭음 없이 자연스럽게 시작/끝나도록. */
    private fun attackDecayEnvelope(index: Int, total: Int): Double {
        val attackLen = min(total / 10, (SAMPLE_RATE * 0.005).toInt().coerceAtLeast(1))
        return when {
            index < attackLen -> index / attackLen.toDouble()
            else -> {
                val decayFrac = (index - attackLen) / (total - attackLen).toDouble()
                (1.0 - decayFrac).coerceIn(0.0, 1.0)
            }
        }
    }

    private fun doublesToPcm(samples: DoubleArray): ShortArray =
        ShortArray(samples.size) { i -> (samples[i].coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort() }

    private fun doublesToPcm(samples: List<Double>): ShortArray =
        ShortArray(samples.size) { i -> (samples[i].coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort() }
}
