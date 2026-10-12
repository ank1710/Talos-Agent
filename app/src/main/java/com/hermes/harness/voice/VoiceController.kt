package com.hermes.harness.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Hermes' microphone and speaker: one [SpeechRecognizer] session at a time plus a
 * lazily created [TextToSpeech] engine.
 *
 * ### Main-thread requirement
 * Android documents [SpeechRecognizer] as "must be created and used on the main
 * thread": the factory call, `startListening`, `cancel`, `stopListening` and
 * `destroy` all touch a bound service and a [Looper]. Every one of those calls is
 * therefore marshalled onto the main thread through [mainHandler], so callers may
 * use this class from any thread and callbacks handed to [startListening] are
 * delivered on the main thread. Mutable state is either confined to the main
 * thread (the recognizer, the TTS engine) or marked `@Volatile` (the state flags)
 * so the `is*` accessors can be read from anywhere.
 *
 * ### Defensiveness
 * A device without a recognition service, a revoked `RECORD_AUDIO` permission, a
 * TTS engine that fails to initialise, a `SecurityException` thrown between the
 * permission check and `startListening`, and a callback that throws all degrade
 * into silence or a human-readable Portuguese error instead of a crash.
 * [shutdown] is idempotent and the controller stays usable afterwards: both
 * resources are simply recreated on the next call.
 *
 * @param context any context; only its application context is retained, so
 *   holding on to a `VoiceController` never leaks an `Activity`.
 */
class VoiceController(private val context: Context) {

    // Application context only: a recognizer/TTS engine that outlives an Activity
    // must not keep that Activity (and its views) reachable.
    private val appContext: Context = context.applicationContext ?: context

    /** Everything Android-side runs here; created eagerly so any thread may post. */
    private val mainHandler = Handler(Looper.getMainLooper())

    // --- Shared mutable state ------------------------------------------------
    // Written on the main thread, read from any thread.
    @Volatile
    private var listening: Boolean = false

    @Volatile
    private var speaking: Boolean = false

    @Volatile
    private var recognizer: SpeechRecognizer? = null

    @Volatile
    private var tts: TextToSpeech? = null

    /** False until [TextToSpeech.OnInitListener] reports success. */
    @Volatile
    private var ttsReady: Boolean = false

    /** Text that arrived before the engine was ready; spoken once it is. */
    @Volatile
    private var pendingSpeech: String? = null

    /**
     * Callbacks of the session that currently owns the recognizer. `null` means
     * "nobody wants results", which is how a cancelled or stopped session is
     * identified when a stale callback arrives late.
     */
    @Volatile
    private var callbacks: Callbacks? = null

    // =========================================================================
    // Public API - speech recognition
    // =========================================================================

    /** Answers whether on-device speech recognition is usable right now. */
    fun isRecognitionAvailable(): Boolean = try {
        SpeechRecognizer.isRecognitionAvailable(appContext)
    } catch (ignored: Exception) {
        // A missing or broken recognition service must never take the caller down.
        false
    }

    /**
     * Starts one listening session. Callbacks are delivered on the main thread.
     * [onPartial] receives interim transcripts, [onFinal] the settled transcript,
     * [onError] a human-readable Portuguese message.
     *
     * Any in-flight session is cancelled first, so calling this while already
     * listening restarts cleanly instead of throwing.
     */
    fun startListening(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val session = Callbacks(onPartial, onFinal, onError)
        runOnMain { beginSession(session) }
    }

    /** Cancels the current session without delivering a final result. */
    fun stopListening() {
        runOnMain { endSession() }
    }

    val isListening: Boolean
        get() = listening

    // =========================================================================
    // Public API - text to speech
    // =========================================================================

    /** Speaks [text], interrupting anything already being spoken. */
    fun speak(text: String) {
        val spoken = speakable(text)
        if (spoken.isEmpty()) return
        runOnMain { speakNow(spoken) }
    }

    fun stopSpeaking() {
        runOnMain {
            pendingSpeech = null
            speaking = false
            val engine = tts ?: return@runOnMain
            try {
                engine.stop()
            } catch (ignored: Exception) {
                // Nothing was being spoken; stop() is best effort.
            }
        }
    }

    val isSpeaking: Boolean
        get() = speaking || tts?.isSpeaking == true

    /**
     * Releases the recognizer and the TTS engine. Safe to call twice, and safe
     * from any thread; the controller may be used again afterwards.
     */
    fun shutdown() {
        runOnMain {
            // Mic first: leaving it open while tearing the engine down keeps the
            // recognition service bound longer than the caller expects.
            endSession()

            pendingSpeech = null
            speaking = false

            val engine = tts
            tts = null
            ttsReady = false
            if (engine != null) shutDownTts(engine)

            val rec = recognizer
            recognizer = null
            if (rec != null) {
                try {
                    rec.destroy()
                } catch (ignored: Throwable) {
                    // Teardown must stay idempotent even if the service already died.
                }
            }
        }
    }

    // =========================================================================
    // Recognition internals - main thread only
    // =========================================================================

    private fun beginSession(session: Callbacks) {
        // Drop the previous session's callbacks *before* cancelling: the terminal
        // event cancel() may emit then finds nobody to report to.
        callbacks = null
        listening = false
        safeCancel(recognizer)

        if (!isRecognitionAvailable()) {
            reportError(session, MESSAGE_UNAVAILABLE)
            return
        }
        if (!hasRecordPermission()) {
            reportError(session, MESSAGE_NO_PERMISSION)
            return
        }
        val engine = ensureRecognizer()
        if (engine == null) {
            reportError(session, MESSAGE_UNAVAILABLE)
            return
        }

        callbacks = session
        try {
            engine.startListening(buildRecognizerIntent())
            listening = true
        } catch (denied: SecurityException) {
            // RECORD_AUDIO can be revoked between the check above and this call.
            callbacks = null
            listening = false
            reportError(session, MESSAGE_NO_PERMISSION)
        } catch (ignored: Exception) {
            // IllegalStateException and friends: recover instead of crashing.
            callbacks = null
            listening = false
            reportError(session, MESSAGE_START_FAILED)
        }
    }

    /** Ends the session silently: no final result, no error callback. */
    private fun endSession() {
        callbacks = null
        listening = false
        safeCancel(recognizer)
    }

    private fun ensureRecognizer(): SpeechRecognizer? {
        recognizer?.let { return it }
        return try {
            val created = SpeechRecognizer.createSpeechRecognizer(appContext)
            created.setRecognitionListener(recognitionListener)
            recognizer = created
            created
        } catch (ignored: Exception) {
            // No service, no permission, no recognizer: report and move on.
            null
        }
    }

    private fun buildRecognizerIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
    }

    private fun safeCancel(engine: SpeechRecognizer?) {
        if (engine == null) return
        try {
            engine.cancel()
        } catch (ignored: Exception) {
            // Already cancelled or already destroyed; nothing to do.
        }
    }

    private fun hasRecordPermission(): Boolean = try {
        appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    } catch (ignored: Exception) {
        false
    }

    /** First non-blank transcript, or the raw first entry, or `null`. */
    private fun firstTranscript(bundle: Bundle?): String? {
        val candidates = try {
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        } catch (ignored: Exception) {
            null
        } ?: return null
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.isNotBlank() } ?: candidates.first()
    }

    private fun reportError(session: Callbacks, message: String) {
        runOnMain { invokeSafely { session.onError(message) } }
    }

    /** Maps a `SpeechRecognizer.ERROR_*` code to a short Portuguese message. */
    private fun messageForError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> MESSAGE_NO_MATCH
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> MESSAGE_SPEECH_TIMEOUT
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> MESSAGE_NO_PERMISSION
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> MESSAGE_NO_NETWORK
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> MESSAGE_RECOGNIZER_BUSY
        SpeechRecognizer.ERROR_AUDIO -> MESSAGE_AUDIO
        SpeechRecognizer.ERROR_SERVER -> MESSAGE_SERVER
        SpeechRecognizer.ERROR_CLIENT -> MESSAGE_CLIENT
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> MESSAGE_TOO_MANY_REQUESTS
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> MESSAGE_SERVER
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> MESSAGE_LANGUAGE
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> MESSAGE_LANGUAGE
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> MESSAGE_SUPPORT_CHECK
        else -> MESSAGE_UNKNOWN
    }

    private val recognitionListener: RecognitionListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        /** The mic is closed now, but [onResults] may still arrive: never a final here. */
        override fun onEndOfSpeech() {
            listening = false
        }

        override fun onPartialResults(partialResults: Bundle?) {
            // No callbacks means the session was stopped or restarted: stale event.
            val session = callbacks ?: return
            val text = firstTranscript(partialResults)?.trim().orEmpty()
            if (text.isEmpty()) return
            runOnMain { invokeSafely { session.onPartial(text) } }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val session = callbacks ?: return // stopped/cancelled in the meantime
            callbacks = null
            val text = firstTranscript(results)?.trim().orEmpty()
            if (text.isEmpty()) {
                // An empty transcript is a failed turn, not a silent success.
                runOnMain { invokeSafely { session.onError(MESSAGE_EMPTY_TRANSCRIPT) } }
            } else {
                runOnMain { invokeSafely { session.onFinal(text) } }
            }
        }

        override fun onError(error: Int) {
            listening = false
            val session = callbacks ?: return // stale event from a cancelled session
            callbacks = null
            val message = messageForError(error)
            runOnMain { invokeSafely { session.onError(message) } }
        }
    }

    /** One listening session's user callbacks; a fresh instance per start. */
    private class Callbacks(
        val onPartial: (String) -> Unit,
        val onFinal: (String) -> Unit,
        val onError: (String) -> Unit,
    )

    // =========================================================================
    // TTS internals - main thread only
    // =========================================================================

    private val ttsInitListener: TextToSpeech.OnInitListener = object : TextToSpeech.OnInitListener {
        override fun onInit(status: Int) {
            handleTtsInit(status)
        }
    }

    private val progressListener: UtteranceProgressListener = object : UtteranceProgressListener() {

        override fun onStart(utteranceId: String?) {
            if (isOurUtterance(utteranceId)) speaking = true
        }

        override fun onDone(utteranceId: String?) {
            if (isOurUtterance(utteranceId)) speaking = false
        }

        // Deprecated since API 21 in favour of onError(String, int), yet still
        // abstract, so this override is mandatory: @Suppress silences the notice.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) {
            if (isOurUtterance(utteranceId)) speaking = false
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            if (isOurUtterance(utteranceId)) speaking = false
        }
    }

    /** Lazily creates the engine (main thread); safe to call repeatedly. */
    private fun ensureTts() {
        if (tts != null) return
        ttsReady = false
        try {
            // A null engine name means "the device default"; the 3-arg overload is
            // used so no engine-specific behaviour is assumed.
            tts = TextToSpeech(appContext, ttsInitListener, null)
        } catch (ignored: Exception) {
            // Device without a TTS engine: stay silent rather than crash.
            tts = null
            ttsReady = false
        }
    }

    private fun handleTtsInit(status: Int) {
        val engine = tts
        if (engine == null) {
            // shutdown() won the race while the engine was initialising.
            return
        }
        if (status != TextToSpeech.SUCCESS) {
            ttsReady = false
            speaking = false
            pendingSpeech = null
            shutDownTts(engine)
            tts = null
            return
        }

        try {
            val result = engine.setLanguage(Locale.getDefault())
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Device locale without a voice: fall back to a widely shipped one.
                engine.setLanguage(Locale.US)
            }
        } catch (ignored: Exception) {
            try {
                engine.setLanguage(Locale.US)
            } catch (ignored: Exception) {
                // No usable voice data at all: the engine will simply stay quiet.
            }
        }

        try {
            engine.setOnUtteranceProgressListener(progressListener)
        } catch (ignored: Exception) {
            // Without the listener isSpeaking falls back to the optimistic flag.
        }

        ttsReady = true
        val queued = pendingSpeech
        pendingSpeech = null
        if (queued != null) speakNow(queued)
    }

    /** Must run on the main thread. */
    private fun speakNow(spoken: String) {
        val engine = tts
        if (engine == null || !ttsReady) {
            // Not ready yet: remember the latest request and flush it on init.
            pendingSpeech = spoken
            ensureTts()
            return
        }
        try {
            val result = engine.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
            speaking = result == TextToSpeech.SUCCESS
        } catch (ignored: Exception) {
            speaking = false
        }
    }

    private fun isOurUtterance(utteranceId: String?): Boolean =
        utteranceId == null || utteranceId == UTTERANCE_ID

    private fun shutDownTts(engine: TextToSpeech) {
        try {
            engine.stop()
        } catch (ignored: Throwable) {
            // Best effort: teardown must survive any engine misbehaviour.
        }
        try {
            engine.shutdown()
        } catch (ignored: Throwable) {
            // Same: shutdown() may be called twice.
        }
    }

    // =========================================================================
    // Text hygiene
    // =========================================================================

    /**
     * Rewrites [text] into something a synthesiser can read aloud.
     *
     * Assistant answers arrive as Markdown, and TTS reads Markdown literally: it
     * announces "asterisk asterisk" for `**bold**`, spells URLs out character by
     * character, and drones through indented code. This drops the syntax — and
     * fenced code blocks entirely, which are unlistenable — so only the words a
     * person would actually say survive.
     */
    private fun speakable(text: String): String {
        if (text.isBlank()) return ""
        var out = text

        // 1. Fenced code blocks: replaced by a short spoken note, never read out.
        out = FENCED_CODE.replace(out, " $SPOKEN_CODE_NOTE ")
        // 2. Images and links: keep the visible label, discard the target URL.
        out = IMAGE.replace(out, "\$1")
        out = LINK.replace(out, "\$1")
        // 3. Bare URLs and e-mail addresses would be spelled out letter by letter.
        out = BARE_URL.replace(out, " ")
        // 4. Heading hashes, block quotes, bullets and horizontal rules.
        out = LINE_PREFIX.replace(out, "")
        // 5. Remaining inline markers: backticks, emphasis, hashes.
        out = MARKDOWN_MARKS.replace(out, "")
        // 6. Line breaks become sentence breaks so the voice pauses where the text did.
        out = LINE_BREAK.replace(out, ". ")
        out = WHITESPACE.replace(out, " ").trim()
        // 7. Collapse punctuation runs the previous steps may have created.
        out = REPEATED_PUNCTUATION.replace(out, "\$1")
        out = LEADING_JUNK.replace(out, "").trim()

        // 8. Long answers exceed the engine's single-utterance limit: cut on a word.
        val limit = speechLimit()
        if (out.length > limit) {
            val cut = out.substring(0, limit)
            out = cut.substringBeforeLast(' ', cut).trim()
        }
        return out
    }

    /** Conservative per-utterance budget; engines reject oversized input outright. */
    private fun speechLimit(): Int = try {
        (TextToSpeech.getMaxSpeechInputLength() - 64).coerceAtLeast(256)
    } catch (ignored: Exception) {
        3800
    }

    // =========================================================================
    // Threading helpers
    // =========================================================================

    /** Runs [block] on the main thread, inline when already there to keep ordering. */
    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }

    /** A throwing UI callback must not kill the recognizer's message pump. */
    private fun invokeSafely(block: () -> Unit) {
        try {
            block()
        } catch (ignored: Exception) {
            // Deliberately swallowed: the caller owns that callback's bugs.
        }
    }

    private companion object {

        /** Stable utterance id so progress callbacks can be attributed. */
        const val UTTERANCE_ID = "com.hermes.harness.voice.UTTERANCE"

        /** Spoken in place of a code block, which cannot be read aloud usefully. */
        const val SPOKEN_CODE_NOTE = "bloco de código omitido."

        const val MESSAGE_NO_MATCH = "Não entendi. Tente falar de novo."
        const val MESSAGE_SPEECH_TIMEOUT = "Não ouvi nada."
        const val MESSAGE_NO_PERMISSION = "Permissão de microfone negada."
        const val MESSAGE_NO_NETWORK = "Sem rede para o reconhecimento de voz."
        const val MESSAGE_RECOGNIZER_BUSY = "O reconhecedor está ocupado."
        const val MESSAGE_AUDIO = "Falha ao capturar o áudio do microfone."
        const val MESSAGE_SERVER = "O serviço de reconhecimento de voz falhou."
        const val MESSAGE_CLIENT = "O reconhecedor de voz parou inesperadamente."
        const val MESSAGE_TOO_MANY_REQUESTS = "Muitas solicitações de voz. Aguarde um instante."
        const val MESSAGE_LANGUAGE = "O idioma do aparelho não é suportado pelo reconhecedor."
        const val MESSAGE_UNAVAILABLE = "Reconhecimento de voz indisponível neste aparelho."
        const val MESSAGE_START_FAILED = "Não foi possível iniciar o reconhecimento de voz."
        const val MESSAGE_EMPTY_TRANSCRIPT = "Não captei nenhuma fala. Tente de novo."
        const val MESSAGE_SUPPORT_CHECK = "Não foi possível verificar o suporte de voz do aparelho."
        const val MESSAGE_UNKNOWN = "Não foi possível reconhecer a fala. Tente de novo."

        /** Fenced code blocks, ``` or ~~~, including the fence lines. */
        private val FENCED_CODE = Regex("""(?s)```.*?```|~~~.*?~~~""")

        /** Markdown image; group 1 is the alt text worth speaking. */
        private val IMAGE = Regex("""!\[([^\]]*)\]\([^)]*\)""")

        /** Markdown link; group 1 is the label, the URL is dropped. */
        private val LINK = Regex("""\[([^\]]*)\]\([^)]*\)""")

        /** Bare URLs and e-mail addresses. */
        private val BARE_URL = Regex("""(?i)\b(?:https?://|www\.)\S+|\b[\w.+-]+@[\w-]+\.[\w.-]+""")

        /** Heading hashes, block quotes, bullets, numbered items, horizontal rules. */
        private val LINE_PREFIX = Regex(
            """(?m)^[ \t]{0,3}(?:#{1,6}[ \t]*|>[ \t]?|[-*+][ \t]+|\d{1,3}[.)][ \t]+|[-*_]{3,}[ \t]*)"""
        )

        /** Inline code and emphasis markers that would be read as punctuation. */
        private val MARKDOWN_MARKS = Regex("""[`*_~#]+""")

        private val LINE_BREAK = Regex("""\s*\n+\s*""")

        private val WHITESPACE = Regex("""\s+""")

        private val REPEATED_PUNCTUATION = Regex("""([.!?,;:])(?:\s*[.!?,;:])+""")

        private val LEADING_JUNK = Regex("""^[\s.,;:•·|>*-]+""")
    }
}
