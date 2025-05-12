package com.umair.background_stt.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.umair.background_stt.adjustSound
import java.util.*

class TextToSpeechFeedbackProvider(context: Context, private val listener: TtsProgressListener) :
    UtteranceProgressListener() {

    companion object {
        private const val TAG = "TextToSpeechFeedback"
    }

    private var textToSpeech: TextToSpeech? = null
    private var isInitialized = false
    private var context: Context
    private var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private val handler = Handler()

    init {
        this.context = context
        this.audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        initializeTts()
    }

    private fun initializeTts() {
        Log.i(TAG, "Setting up the connection to TTS engine...")
        textToSpeech = TextToSpeech(context) { status ->
            isInitialized = status == TextToSpeech.SUCCESS
            if (isInitialized) {
                textToSpeech?.setOnUtteranceProgressListener(this)
                textToSpeech?.language = Locale.US
                Log.i(TAG, "Text-to-Speech Initialized with enhanced volume settings.")
            } else {
                Log.e(TAG, "Failed to initialize Text-to-Speech engine!")
            }
        }
    }

    /**
     * Speak the provided text with TTS
     *
     * @param text The text to speak
     * @param forceMode Whether to force speaking even if busy
     * @param queue Whether to queue the speech or interrupt current speech
     */
    fun speak(text: String, forceMode: Boolean = true, queue: Boolean = false) {
        Log.d(TAG, "speak: Starting TTS with text: '$text', forceMode: $forceMode, queue: $queue")
        
        if (!isInitialized) {
            Log.e(TAG, "Cannot speak! TTS not initialized")
            listener.onFailure("TTS not initialized")
            return
        }

        try {
            // Try to enhance audio for speaking
            safelyAdjustAudio()
            
            // Call TextToSpeech with parameters
            callTextToSpeech(text, queue)
        } catch (e: Exception) {
            Log.e(TAG, "Error while speaking: ${e.message}")
            listener.onFailure("Error while speaking: ${e.message}")
        }
    }

    /**
     * Safely adjust audio settings with exception handling
     */
    private fun safelyAdjustAudio() {
        try {
            // Request audio focus
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(audioAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { }
                    .build()
                
                audioManager.requestAudioFocus(audioFocusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not request audio focus: ${e.message}")
        }
    }

    /**
     * Call TextToSpeech with the specified parameters
     */
    private fun callTextToSpeech(text: String, queue: Boolean) {
        Log.d(TAG, "callTextToSpeech: Speaking text: '$text', queue: $queue")
        
        val queueMode = if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        val utteranceId = UUID.randomUUID().toString()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            textToSpeech?.speak(text, queueMode, null, utteranceId)
        } else {
            @Suppress("DEPRECATION")
            val params = HashMap<String, String>().apply {
                put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            textToSpeech?.speak(text, queueMode, params)
        }
    }

    /**
     * Release TTS resources
     */
    fun shutdown() {
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            
            // Release audio focus
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down TTS: ${e.message}")
        }
    }

    /**
     * Handle TTS start event
     */
    override fun onStart(utteranceId: String?) {
        Log.d(TAG, "TTS onStart: Starting speech for utterance: $utteranceId")
        handler.post { listener.onStart(utteranceId ?: "") }
    }

    /**
     * Handle TTS done event - IMMEDIATELY resume speech recognition!
     */
    override fun onDone(utteranceId: String?) {
        Log.d(TAG, "TTS onDone: Speech completed for utterance: $utteranceId")
        
        // Call our callback without any delay!
        doOnSpeechComplete(utteranceId ?: "")
    }

    /**
     * Handle speech completion and immediately resume speech recognition
     */
    private fun doOnSpeechComplete(utteranceId: String) {
        Log.d(TAG, "doOnSpeechComplete: Speech completed, immediately resuming speech recognition")
        
        // Release audio focus
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not abandon audio focus: ${e.message}")
        }
        
        // Resume speech recognition immediately
        handler.post { 
            try {
                resumeSpeechService()
                listener.onDone(utteranceId)
            } catch (e: Exception) {
                Log.e(TAG, "Error resuming speech service: ${e.message}")
                // Even if resuming fails, still notify the listener that TTS is done
                listener.onDone(utteranceId)
            }
        }
    }

    /**
     * Resume speech recognition service
     */
    fun resumeSpeechService() {
        try {
            // Safely try to adjust audio, but don't crash the app if it fails
            try {
                adjustSound(context, 10, true)
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException when adjusting audio: ${e.message}")
                // Continue even if we can't adjust audio
            } catch (e: Exception) {
                Log.w(TAG, "Exception when adjusting audio: ${e.message}")
                // Continue even if we can't adjust audio
            }
            
            listener.onResumeSpeechService()
        } catch (e: Exception) {
            Log.e(TAG, "Error in resumeSpeechService: ${e.message}")
        }
    }

    /**
     * Handle TTS error event
     */
    override fun onError(utteranceId: String?) {
        Log.e(TAG, "TTS onError: Error speaking utterance: $utteranceId")
        handler.post { listener.onError(utteranceId ?: "") }
        
        // Even on error, try to resume speech recognition
        doOnSpeechComplete(utteranceId ?: "")
    }

    /**
     * Handle audio range event (required override)
     */
    @Suppress("DEPRECATION")
    override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
        // Not used in our implementation
    }

    /**
     * Change the pitch of the TTS voice
     *
     * @param pitch The pitch level (1.0 is normal)
     */
    fun setPitch(pitch: Float) {
        textToSpeech?.setPitch(pitch)
    }

    /**
     * Change the speech rate of the TTS voice
     *
     * @param rate The speech rate (1.0 is normal)
     */
    fun setSpeechRate(rate: Float) {
        textToSpeech?.setSpeechRate(rate)
    }
}
