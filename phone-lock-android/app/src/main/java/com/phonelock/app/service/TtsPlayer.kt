package com.phonelock.app.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 무전기 텍스트 메시지(TTS) 재생 — [VoicePlayer]의 텍스트 버전. 매 재생마다 새 [TextToSpeech] 인스턴스를
 * 만들고 끝나면 shutdown한다(상시 유지할 필요가 없을 만큼 드물게 쓰이는 기능이라 [VoicePlayer]와 같은
 * 단순한 1회용 패턴을 그대로 따름).
 */
object TtsPlayer {
    fun speak(context: Context, text: String, volumePercent: Int, onCompletion: () -> Unit = {}) {
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
