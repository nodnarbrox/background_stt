package com.umair.background_stt

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.umair.background_stt.speech.Logger
import com.umair.background_stt.speech.Speech
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry


class BackgroundSttPlugin : FlutterPlugin, ActivityAware, PluginRegistry.RequestPermissionsResultListener {

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        setUpPluginMethods(flutterPluginBinding.applicationContext, flutterPluginBinding.binaryMessenger)
    }

    companion object {
        private val TAG = "BackgroundSttPlugin"
        private var REQUEST_RECORD_PERMISSIONS = 1212
        private var channel: MethodChannel? = null
        private var event_channel: EventChannel? = null
        var eventSink: EventChannel.EventSink? = null
        private var currentActivity: Activity? = null
        private var isStarted = false

        @JvmStatic
        var binaryMessenger: BinaryMessenger? = null

        @JvmStatic
        fun registerWith(registrar: PluginRegistry.Registrar) {
            val instance = BackgroundSttPlugin()
            registrar.addRequestPermissionsResultListener(instance)
            requestRecordPermission()
            setUpPluginMethods(registrar.activity(), registrar.messenger())
        }

        @JvmStatic
        fun registerWith(messenger: BinaryMessenger, context: Context) {
            val instance = BackgroundSttPlugin()
            requestRecordPermission()
            setUpPluginMethods(context, messenger)
        }

        @JvmStatic
        fun isMyServiceRunning(serviceClass: Class<*>, context: Context): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            for (service in manager.getRunningServices(Int.MAX_VALUE)) {
                if (serviceClass.name == service.service.className) {
                    return true
                }
            }
            return false
        }

        @JvmStatic
        private fun setUpPluginMethods(context: Context, messenger: BinaryMessenger) {

            channel = MethodChannel(messenger, "background_stt")
            notifyIfPermissionsGranted(context)

            channel?.setMethodCallHandler { call, result ->
                when (call.method) {
                    "startService" -> {
                        context.let {
                            initSpeechService(it)
                            isStarted = true
                            result.success("Started Speech listener service.")
                        }
                    }
                    "confirmIntent" -> {
                        val confirmationText = getStringValueById("confirmationText", call)
                        val positiveCommand = getStringValueById("positiveCommand", call)
                        val negativeCommand = getStringValueById("negativeCommand", call)
                        val voiceInputMessage = getStringValueById("voiceInputMessage", call)
                        val voiceInput = getBoolValueById("voiceInput", call)

                        if (confirmationText.isNotEmpty() && positiveCommand.isNotEmpty() && negativeCommand.isNotEmpty()) {
                            SpeechListenService.doOnIntentConfirmation(confirmationText, positiveCommand, negativeCommand, voiceInputMessage, voiceInput)
                            result.success("Requested confirmation for: $confirmationText\n " +
                                    "Positive Reply: $positiveCommand\n " +
                                    "Negative Reply: $negativeCommand\n " +
                                    "Voice Input Message: $voiceInputMessage\n " +
                                    "Voice Input: $voiceInput")
                        }
                    }
                    "stopService" -> {
                        eventSink?.endOfStream()
                        eventSink = null
                        isStarted = false
                        context.let {
                            try {
                                context.stopService(Intent(context, SpeechListenService::class.java))
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            result.success("Stopped Speech listener service.")
                        }
                    }
                    "cancelConfirmation" -> {
                        SpeechListenService.cancelConfirmation()
                        result.success("Confirmation cancelled.")
                    }
                    "resumeListening" -> {
                        SpeechListenService.isListening(true)
                        result.success("Speech listener resumed.")
                    }
                    "pauseListening" -> {
                        SpeechListenService.isListening(false)
                        result.success("Speech listener paused.")
                    }
                    "speak" -> {
                        val speechText = getStringValueById("speechText", call)
                        val queue = getBoolValueById("queue", call)
                        SpeechListenService.speak(speechText, queue)
                    }
                    "setSpeaker" -> {
                        val pitch = call.argument<String>("pitch")?.toFloat()
                        val rate = call.argument<String>("rate")?.toFloat()

                        if (pitch != null && rate != null) {
                            SpeechListenService.setSpeaker(pitch, rate)
                        }
                    }
                    else -> result.notImplemented()
                }
            }

            event_channel = EventChannel(messenger, "background_stt_stream")
            event_channel?.setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    eventSink = events
                }

                override fun onCancel(arguments: Any?) {

                }
            })
        }

        @JvmStatic
        private fun notifyIfPermissionsGranted(context: Context) {
            if (permissionsGranted(context)) {
                doIfPermissionsGranted()
            }
        }

        @JvmStatic
        fun permissionsGranted(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }

        @JvmStatic
        private fun doIfPermissionsGranted() {
            channel?.let {
                it.invokeMethod("recordPermissionsGranted", "")
            }
            //currentActivity?.enableAutoStart()

        }

        @JvmStatic
        private fun requestRecordPermission() {
            if (!areRecordPermissionsGranted()) {
                Log.i(TAG, "requestRecordPermission: Requesting record audio permissions..")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    currentActivity?.let {
                        ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_PERMISSIONS)
                    }
                            ?: Log.e(TAG, "requestRecordPermission: Unable to request storage permissions.")
                } else {
                    doIfPermissionsGranted()
                }
            } else {
                doIfPermissionsGranted()
            }
        }

        @JvmStatic
        private fun areRecordPermissionsGranted(): Boolean {
            currentActivity?.let {
                return ContextCompat.checkSelfPermission(it, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            }
            return false
        }

        @JvmStatic
        private fun initSpeechService(context: Context) {
            Speech.init(context, context.packageName, 2000L, 1000L)
            Logger.setLogLevel(Logger.LogLevel.DEBUG)
            try {
                context.startService(Intent(context, SpeechListenService::class.java))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        currentActivity = null
        channel?.setMethodCallHandler(null)
        event_channel?.setStreamHandler(null)
    }

    override fun onAttachedToActivity(activityPluginBinding: ActivityPluginBinding) {
        currentActivity = activityPluginBinding.activity
        activityPluginBinding.addRequestPermissionsResultListener(this)
        requestRecordPermission()

        //Only Auto-Run if service is started and not stopped explicitly
        if (isStarted) {
            if (!areRecordPermissionsGranted()) {
                currentActivity?.let {
                    initSpeechService(it)
                }
            }
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {

    }

    override fun onReattachedToActivityForConfigChanges(activityPluginBinding: ActivityPluginBinding) {
        currentActivity = activityPluginBinding.activity
        activityPluginBinding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivity() {
        currentActivity = null
        SpeechListenService.stopSpeechListener()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>?, grantResults: IntArray?): Boolean {
        if (requestCode == REQUEST_RECORD_PERMISSIONS && grantResults?.isNotEmpty()!! && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            doIfPermissionsGranted()
            return true
        }
        return false
    }
}
