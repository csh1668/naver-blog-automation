package com.csh.blogwriter.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 음성 입력. 부분 인식 결과를 계속 흘려보내고 최종 결과를 마지막으로 낸 뒤 끝난다.
 * 값은 늘 지금까지 알아들은 전체 문장이므로 입력창에 **교체**해서 넣는다.
 * 기기에 인식 엔진이 없으면 [available] 이 false 고, 그때는 아무것도 내지 않고 바로 끝난다.
 */
class SpeechInput(private val context: Context) {

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun listen(): Flow<String> = callbackFlow {
        if (!available) { close(); return@callbackFlow }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle) { firstText(partialResults)?.let { trySend(it) } }
            override fun onResults(results: Bundle) { firstText(results)?.let { trySend(it) }; close() }
            override fun onError(error: Int) { close() }
            override fun onEndOfSpeech() = Unit
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
        )
        awaitClose { recognizer.destroy() }
    }

    private fun firstText(bundle: Bundle): String? =
        bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
}
