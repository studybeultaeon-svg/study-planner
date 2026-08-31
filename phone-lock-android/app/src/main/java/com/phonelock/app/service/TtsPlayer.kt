package com.phonelock.app.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 무전기 텍스트 메시지(TTS) 재생 — [VoicePlayer]의 텍스트 버전. 매 재생마다 새 [TextToSpeech] 인스턴스를
 * 만들고 끝나면 shutdown한다(상시 유지할 필요가 없을 만큼 드물게 쓰이는 기능이라 [VoicePlayer]와 같은
 * 단순한 1회용 패턴을 그대로 따름).
 *
 * 남성 목소리(78차): 한국어는 기기/엔진에 따라 설치된 음성이 대부분 하나뿐이라 이름으로 성별별 Voice를
 * 골라 쓰는 방식은 기기마다 결과가 달라 신뢰할 수 없다 — 대신 어느 기기에서도 항상 같은 방향으로 동작하는
 * 피치(pitch) 하향 조정으로 "남성 톤"을 흉내낸다(실제 다른 배우의 목소리가 아니라 같은 음성의 톤 변화).
 */
object TtsPlayer {
    private const val MALE_PITCH = 0.78f

    fun speak(context: Context, text: String, volumePercent: Int, voiceGender: String = "FEMALE", onCompletion: () -> Unit = {}) {
        if (text.isBlank()) { onCompletion(); return }
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                tts?.shutdown()
                onCompletion()
                return@TextToSpeech
            }
            val engine = tts ?: return@TextToSpeech
            engine.language = Locale.KOREAN
            if (voiceGender == "MALE") engine.setPitch(MALE_PITCH)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    engine.shutdown()
                    onCompletion()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    engine.shutdown()
                    onCompletion()
                }
            })
            // 볼륨은 STREAM_MUSIC 위에 배율만 곱한다 — VoicePlayer와 동일 원칙(무음 설정을 뚫지 않음).
            val volume = volumePercent.coerceIn(0, 100) / 100f
            val params = android.os.Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
            }
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "walkie_tts")
        }
    }
}
