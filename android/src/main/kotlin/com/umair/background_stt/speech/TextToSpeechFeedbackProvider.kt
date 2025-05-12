package com.umair.background_stt.speech

import android.content.Context
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.umair.background_stt.R
import com.umair.background_stt.SpeechListenService
import com.umair.background_stt.adjustSound
import com.umair.background_stt.models.ConfirmIntent
import com.umair.background_stt.models.ConfirmationResult
import com.umair.background_stt.SpeechResultEvent
import org.greenrobot.eventbus.EventBus
import java.util.*


class TextToSpeechFeedbackProvider constructor(val context: Context) {

    private val TAG = "TextToSpeechFeedback"
    private var textToSpeech: TextToSpeech? = null
    private val handler = Handler(Looper.getMainLooper())
    private var confirmationIntent: ConfirmIntent? = null
    private var confirmationInProgress = false
    private var confirmationProvided = false
    private var waitingForVoiceInput = false
    private var voiceReplyProvided = false
    private var voiceConfirmationRequested = false
    private var maxTries = 20
    private var tries = 0
    private var voiceReplyCount = 0
    private var isListening = false

    private var soundPool = SoundPool(5, AudioManager.STREAM_NOTIFICATION, 0)
    private var soundId = soundPool.load(context, R.raw.bleep, 1)

    init {
        setUpTextToSpeech()
    }

    private fun setUpTextToSpeech() {
        textToSpeech = TextToSpeech(context,
                TextToSpeech.OnInitListener { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        val ttsLang = textToSpeech?.setLanguage(Locale.US)

                        if (ttsLang == TextToSpeech.LANG_MISSING_DATA || ttsLang == TextToSpeech.LANG_NOT_SUPPORTED) {
                            Log.i(TAG, "The Language is not supported!")
                        } else {
                            // Set default speech parameters for better audibility
                            textToSpeech?.setPitch(1.0f)  // Normal pitch
                            textToSpeech?.setSpeechRate(0.9f)  // Slightly slower for clarity
                            
                            // Set max volume for TTS
                            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
                            
                            Log.i(TAG, "Text-to-Speech Initialized with enhanced volume settings.")
                        }
                    } else {
                        Log.e(TAG, "Text-to-Speech Initialization failed.")
                    }
                })

        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "TTS onDone: Speech completed for utterance: $utteranceId")
                SpeechListenService.isSpeaking = false
                // Immediately process speech completion without delay
                doOnSpeechComplete()
            }

            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS onError: Speech error for utterance: $utteranceId")
                SpeechListenService.isSpeaking = false
                // Immediately process speech completion without delay
                doOnSpeechComplete()
            }

            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "TTS onStart: Starting speech for utterance: $utteranceId")
                SpeechListenService.isSpeaking = true
                
                // Maximize volume when speaking
                for (i in 0..3) {
                    handler.postDelayed({
                        context.adjustSound(AudioManager.ADJUST_RAISE, forceAdjust = true)
                    }, i * 100L)
                }
            }
        });
    }

    fun disposeTextToSpeech() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }

    fun speak(text: String, forceMode: Boolean = false, queue: Boolean = true) {
        Log.d(TAG, "speak: Starting TTS with text: '$text', forceMode: $forceMode, queue: $queue")

        if (forceMode) {
            Speech.getInstance().stopListening()
            callTextToSpeech(text, queue)
        } else {
            if (!SpeechListenService.isSpeaking && !textToSpeech?.isSpeaking!!) {
                Speech.getInstance().stopListening()
                isListening = false
                callTextToSpeech(text, queue)
            } else {
                Log.d(TAG, "speak: Already speaking, skipping text: '$text'")
            }
        }
    }

    fun setSpeaker(pitch: Float, rate: Float) {
        Log.d(TAG, "setSpeaker: Setting pitch to $pitch and rate to $rate")
        textToSpeech?.setPitch(pitch)
        textToSpeech?.setSpeechRate(rate)
    }

    private fun callTextToSpeech(text: String, queue: Boolean) {
        Log.d(TAG, "callTextToSpeech: Speaking text: '$text', queue: $queue")
        
        // Ensure maximum volume before speaking
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
        
        if (queue) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                textToSpeech?.speak(text, TextToSpeech.QUEUE_ADD, null, text)
            } else {
                textToSpeech?.speak(text, TextToSpeech.QUEUE_ADD, null)
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, text)
            } else {
                textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null)
            }
        }
    }

    private fun doOnSpeechComplete() {
        // Immediately process when TTS is done without any delay
        if (!textToSpeech?.isSpeaking!!) {
            Log.d(TAG, "doOnSpeechComplete: Speech completed, immediately resuming speech recognition")
            if (!voiceReplyProvided) {
                waitingForVoiceInput = false
            }
            // Start listening immediately
            resumeSpeechService()
        } else {
            // In case TTS is somehow still speaking, check again after a very brief delay
            handler.postDelayed({
                doOnSpeechComplete()
            }, 50)
        }
    }

    fun resumeSpeechService() {
        Log.i(TAG, "Listening to voice commands..")
        isListening = true
        
        // Don't mute audio when resuming speech recognition
        // Just lower it slightly to improve recognition
        context.adjustSound(AudioManager.ADJUST_LOWER, forceAdjust = false)
        
        // Start listening immediately
        SpeechListenService.startListening()
    }

    fun isConfirmationInProgress(): Boolean {
        return confirmationInProgress
    }

    fun cancelConfirmation(now: Boolean = false) {
        Log.d(TAG, "cancelConfirmation: Cancelling confirmation, now: $now")
        if (now) {
            resetConfirmation()
            Log.i(TAG, "Confirmation cancelled.")
            
            // Don't mute audio, just lower it
            context.adjustSound(AudioManager.ADJUST_LOWER)
            
            SpeechListenService.startListening()
        } else {
            if (confirmationProvided) {
                handler.postDelayed({
                    confirmationInProgress = false
                }, 3000)
            }
        }
    }

    fun setConfirmationData(confirmationText: String, positiveCommand: String, negativeCommand: String, voiceInputMessage: String, voiceInput: Boolean) {
        tries = 0
        confirmationInProgress = true
        confirmationIntent = ConfirmIntent(confirmationText, positiveCommand, negativeCommand, voiceInputMessage, voiceInput)
        waitingForVoiceInput = voiceInput
        
        Log.d(TAG, "setConfirmationData: Set confirmation with text: '$confirmationText', positive: '$positiveCommand', negative: '$negativeCommand'")
    }

    fun doOnConfirmationProvided(text: String) {
        Log.d(TAG, "doOnConfirmationProvided: Received confirmation text: '$text'")
        if (text.isNotEmpty()) {
            if (confirmationInProgress) {
                confirmationIntent?.let { confirmationIntent ->
                    if (confirmationIntent.voiceInput && !voiceReplyProvided) {
                        doForVoiceInput(text)
                    } else {
                        doForConfirmation(text)
                    }
                }
            } else {
                Log.e(TAG, "doOnConfirmationProvided: No confirmation in progress!")
                sendConfirmation("", false, "")
            }
        }
    }

    private fun doForConfirmation(text: String) {
        if (isListening) {
            val reply = text.substringBefore(" ")
            Log.i(TAG, "doForConfirmation: Reply: \"$reply\"")
            if (reply.isNotEmpty()) {
                confirmationIntent?.let { confirmationIntent ->
                    if (confirmationIntent.confirmationIntent.isNotEmpty()) {
                        var isSuccess = false
                        if (confirmationIntent.positiveCommand.matches(Regex(reply)) || confirmationIntent.negativeCommand.matches(Regex(reply))) {
                            Log.i(TAG, "doForConfirmation: Confirmation provided: \"$reply\"")

                            speak("You said: $reply")

                            isSuccess = true

                            if (confirmationIntent.voiceMessage.isNotEmpty()) {
                                sendConfirmation(reply, isSuccess, confirmationIntent.voiceMessage)
                            } else {
                                sendConfirmation(reply, isSuccess, "")
                            }

                        } else {
                            if (tries < maxTries) {
                                Log.e(TAG, "doForConfirmation: No appropriate reply received! Try Count: $tries")
                                tries++
                            } else {
                                Log.i(TAG, "doForConfirmation: Confirmation failed.")
                                speak("Confirmation failed, Retry again.")
                                sendConfirmation(reply, isSuccess, "")
                            }
                        }
                    }
                }
            } else {
                Log.e(TAG, "doOnConfirmationProvided: No appropriate reply received!")
            }
        }
    }

    private fun doForVoiceInput(text: String) {
        Log.d(TAG, "doForVoiceInput: Processing voice input: '$text', voiceReplyProvided: $voiceReplyProvided, waitingForVoiceInput: $waitingForVoiceInput")
        if (!voiceReplyProvided) {
            if (!waitingForVoiceInput) {

                voiceReplyCount++

                Log.i(TAG, "doForVoiceInput: Voice Reply: \"$text\", Count: $voiceReplyCount")
                confirmationIntent?.voiceMessage = text

                handler.postDelayed({
                    if (!voiceReplyProvided) {
                        confirmationIntent?.voiceInputMessage?.let {
                            Log.i(TAG, "doForVoiceInput: Starting Confirmation.\n " +
                                    "Positive Reply: ${confirmationIntent?.positiveCommand}\n " +
                                    "Negative Reply: ${confirmationIntent?.negativeCommand}")

                            speak("You said: ${confirmationIntent?.voiceMessage} $it say: ${confirmationIntent?.positiveCommand} or ${confirmationIntent?.negativeCommand}")
                            voiceConfirmationRequested = true
                        }
                    }
                    voiceReplyProvided = true
                }, 4000)

            }
        }
    }

    private fun sendConfirmation(reply: String, isSuccess: Boolean, voiceInputMessage: String) {
        Log.d(TAG, "sendConfirmation: Sending confirmation, reply: '$reply', isSuccess: $isSuccess")
        confirmationIntent?.let {
            val result = ConfirmationResult(it.confirmationIntent, reply, voiceInputMessage, isSuccess).toString()
            EventBus.getDefault().post(SpeechResultEvent(result, false))
            Log.i(TAG, "sendConfirmation: Confirmation sent.")

            this.confirmationIntent = null
            tries = 0

            handler.postDelayed({
                resetConfirmation()
                Log.i(TAG, "sendConfirmation: Confirmation completed.")
            }, 3000)
        }
    }

    private fun resetConfirmation() {
        Log.d(TAG, "resetConfirmation: Resetting confirmation state")
        confirmationInProgress = false
        confirmationProvided = true
        waitingForVoiceInput = false
        voiceReplyProvided = false
        voiceConfirmationRequested = false
        voiceReplyCount = 0
        soundPool.release()
        SpeechListenService.isSpeaking = false
        isListening = false
    }

    private fun playSound() {
        soundPool.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f)
    }
}
