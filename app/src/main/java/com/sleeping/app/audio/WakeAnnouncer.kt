package com.sleeping.app.audio

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.util.Log
import java.util.Locale

/**
 * Pleos TextToSpeech SDK로 기상 안내 음성을 낸다.
 * ON_DEVICE 모드라 Pleos Playground 앱 등록(Client ID/Secret)이 필요 없다 (2026-11-14 결정).
 */
class WakeAnnouncer(context: Context) {

    private val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
    @Volatile private var ready = false
    @Volatile private var pending: String? = null

    fun initialize() {
        runCatching {
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    runCatching { tts.setLocale(Locale.KOREA) }
                    ready = true
                    pending?.let { speak(it) }
                    pending = null
                }

                override fun onFailure(errorType: ErrorType) {
                    Log.w(TAG, "TTS 초기화 실패: $errorType")
                }
            })
        }.onFailure { Log.w(TAG, "TTS 사용 불가: ${it.message}") }
    }

    fun speak(text: String) {
        if (!ready) {
            pending = text
            return
        }
        runCatching { tts.speak(text, true) }.onFailure { Log.w(TAG, "TTS 재생 실패: ${it.message}") }
    }

    fun release() {
        runCatching { tts.stop() }
        runCatching { tts.release() }
    }

    private companion object {
        const val TAG = "Sleeping"
    }
}
