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
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode


class BackgroundSttPlugin : FlutterPlugin, ActivityAware, PluginRegistry.RequestPermissionsResultListener {
    private lateinit var channel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private var eventSink: EventChannel.EventSink? = null
    private var currentActivity: Activity? = null
    private var isStarted = false

    companion object {
        private const val TAG = "BackgroundSttPlugin"
        private const val REQUEST_RECORD_PERMISSIONS = 1212
    }

    init {
        EventBus.getDefault().register(this)
    }

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "background_stt")
        eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "background_stt_stream")
        setUpPluginMethods(flutterPluginBinding.applicationContext, flutterPluginBinding.binaryMessenger)
    }

    fun isMyServiceRunning(serviceClass: Class<*>, context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun setUpPluginMethods(context: Context, messenger: BinaryMessenger) {
        notifyIfPermissionsGranted(context)

        channel.setMethodCallHandler { call, result ->
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

        eventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                eventSink = events
            }

            override fun onCancel(arguments: Any?) {
                // Do nothing
            }
        })
    }

    // Remove this entire block as these methods are now instance methods

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        EventBus.getDefault().unregister(this)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onSpeechResultEvent(event: SpeechResultEvent) {
        eventSink?.success(event.toString())
    }

    override fun onAttachedToActivity(activityPluginBinding: ActivityPluginBinding) {
        currentActivity = activityPluginBinding.activity
        activityPluginBinding.addRequestPermissionsResultListener(this)
        requestRecordPermission()

        if (isStarted && !areRecordPermissionsGranted()) {
            currentActivity?.let { initSpeechService(it) }
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {
        // Do nothing
    }

    override fun onReattachedToActivityForConfigChanges(activityPluginBinding: ActivityPluginBinding) {
        currentActivity = activityPluginBinding.activity
        activityPluginBinding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivity() {
        currentActivity = null
        SpeechListenService.stopSpeechListener()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        return if (requestCode == REQUEST_RECORD_PERMISSIONS && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            doIfPermissionsGranted()
            true
        } else {
            false
        }
    }

    private fun requestRecordPermission() {
        if (!areRecordPermissionsGranted()) {
            Log.i(TAG, "requestRecordPermission: Requesting record audio permissions..")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                currentActivity?.let {
                    ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_PERMISSIONS)
                } ?: Log.e(TAG, "requestRecordPermission: Unable to request storage permissions.")
            } else {
                doIfPermissionsGranted()
            }
        } else {
            doIfPermissionsGranted()
        }
    }

    private fun areRecordPermissionsGranted(): Boolean {
        return currentActivity?.let {
            ContextCompat.checkSelfPermission(it, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        } ?: false
    }

    private fun initSpeechService(context: Context) {
        Speech.init(context, context.packageName, 2000L, 1000L)
        Logger.setLogLevel(Logger.LogLevel.DEBUG)
        try {
            context.startService(Intent(context, SpeechListenService::class.java))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun notifyIfPermissionsGranted(context: Context) {
        if (permissionsGranted(context)) {
            doIfPermissionsGranted()
        }
    }

    private fun permissionsGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun doIfPermissionsGranted() {
        channel.invokeMethod("recordPermissionsGranted", "")
    }

    private fun getStringValueById(id: String, call: MethodCall): String {
        return call.argument<String>(id) ?: ""
    }

    private fun getBoolValueById(id: String, call: MethodCall): Boolean {
        return call.argument<Boolean>(id) ?: false
    }
}
