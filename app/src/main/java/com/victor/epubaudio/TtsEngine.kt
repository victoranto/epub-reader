package com.victor.epubaudio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Envoltorio sencillo sobre el TextToSpeech de Android.
 * - speak(): lectura en voz alta, con callbacks por trozo.
 * - synthesizeToFile(): genera un WAV para exportar.
 */
class TtsEngine(private val context: Context) {

    private var tts: TextToSpeech? = null
    private val pendingFiles = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /** Se llama cuando empieza / termina un trozo hablado (no los de exportación). */
    var onSpeakStart: ((String) -> Unit)? = null
    var onSpeakDone: ((String) -> Unit)? = null

    val maxInput: Int get() = minOf(TextToSpeech.getMaxSpeechInputLength(), 3000)

    suspend fun init(): Boolean = suspendCancellableCoroutine { cont ->
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (ok) {
                tts = engine
                engine?.setOnUtteranceProgressListener(listener)
            }
            if (cont.isActive) cont.resume(ok)
        }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            if (!pendingFiles.containsKey(utteranceId)) onSpeakStart?.invoke(utteranceId)
        }

        override fun onDone(utteranceId: String) {
            val d = pendingFiles.remove(utteranceId)
            if (d != null) d.complete(true) else onSpeakDone?.invoke(utteranceId)
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) {
            pendingFiles.remove(utteranceId)?.complete(false)
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            pendingFiles.remove(utteranceId)?.complete(false)
        }
    }

    fun setLanguage(tag: String?) {
        val locale = tag?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
        val res = tts?.setLanguage(locale)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.setLanguage(Locale.getDefault())
        }
    }

    fun setRate(rate: Float) {
        tts?.setSpeechRate(rate)
    }

    fun speak(text: String, utteranceId: String, flush: Boolean) {
        tts?.speak(
            text,
            if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
            null,
            utteranceId
        )
    }

    fun stop() {
        tts?.stop()
    }

    /** Genera un WAV con el texto. Devuelve true si salió bien. */
    suspend fun synthesizeToFile(text: String, file: File, utteranceId: String): Boolean {
        val engine = tts ?: return false
        val done = CompletableDeferred<Boolean>()
        pendingFiles[utteranceId] = done
        val res = engine.synthesizeToFile(text, Bundle(), file, utteranceId)
        if (res != TextToSpeech.SUCCESS) {
            pendingFiles.remove(utteranceId)
            return false
        }
        return done.await()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
