package com.example

import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

class VoiceReelsAccessibilityService : AccessibilityService() {

    companion object {
        var isServiceRunning = false
            private set
            
        var isVoiceControlActive = false
            set(value) {
                field = value
                instance?.toggleListeningState(value)
            }
            
        private var instance: VoiceReelsAccessibilityService? = null
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceRunning = true
        
        // Sync setting with saved value
        val prefs = getSharedPreferences("voice_reels_prefs", Context.MODE_PRIVATE)
        isVoiceControlActive = prefs.getBoolean("global_voice_control_enabled", false)
        
        Toast.makeText(this, "Voice Reels Assistant Connected", Toast.LENGTH_LONG).show()
        
        if (isVoiceControlActive) {
            startListening()
        }
    }

    fun toggleListeningState(active: Boolean) {
        handler.post {
            if (active) {
                startListening()
            } else {
                stopListening()
            }
        }
    }

    private fun startListening() {
        if (!isServiceRunning || !isVoiceControlActive) return
        
        // Ensure execution is strictly scheduled on the main/UI thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { startListening() }
            return
        }

        // Verify microphone permission is granted before utilizing SpeechRecognizer
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            return
        }

        try {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(createSpeechListener())
                }
            }

            if (recognizerIntent == null) {
                recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
            }

            speechRecognizer?.startListening(recognizerIntent)
        } catch (e: Exception) {
            // silent catch on emulator or if offline
        }
    }

    private fun stopListening() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { stopListening() }
            return
        }
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            // silent catch
        }
    }

    private fun createSpeechListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                // Throttle retries on busy or soft recognition errors to prevent rapid recursion loops
                val delayTime = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 3000L else 1800L
                if (isVoiceControlActive && isServiceRunning) {
                    handler.postDelayed({
                        if (isVoiceControlActive && isServiceRunning) {
                            startListening()
                        }
                    }, delayTime)
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    handleVoiceCommand(matches[0])
                }

                if (isVoiceControlActive && isServiceRunning) {
                    handler.postDelayed({
                        if (isVoiceControlActive && isServiceRunning) {
                            startListening()
                        }
                    }, 1200) // healthy pause before restarting mic capture to let media flow smoothly
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    private fun handleVoiceCommand(heard: String) {
        val command = heard.lowercase().trim()
        
        when {
            // MATCH SCROLL UP / GO TO NEXT VIDEO (Swipe Up Gesture)
            command.contains("next") || command.contains("down") || command.contains("skip") || command.contains("forward") -> {
                showMatchToast("Next Video ⬇️")
                swipeUp()
            }
            // MATCH SCROLL DOWN / GO TO PREVIOUS VIDEO (Swipe Down Gesture)
            command.contains("prev") || command.contains("previous") || command.contains("back") || command.contains("up") -> {
                showMatchToast("Previous Video ⬆️")
                swipeDown()
            }
            // MATCH PLAY / PAUSE SINGLE TAP ON SCREEN
            command.contains("pause") || command.contains("stop") || command.contains("wait") || command.contains("play") || command.contains("resume") -> {
                showMatchToast("Play / Pause ⏸️🎬")
                performTap()
            }
            // MATCH DOUBLE TAP ON SCREEN (LIKE FEEDBACK EVENT)
            command.contains("like") || command.contains("love") || command.contains("heart") || command.contains("favorite") -> {
                showMatchToast("Liked! ❤️")
                performDoubleTap()
            }
        }
    }

    private fun showMatchToast(message: String) {
        Toast.makeText(applicationContext, "Voice Reels: $message", Toast.LENGTH_SHORT).show()
    }

    private fun swipeUp() {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()

        val startX = width / 2
        val startY = height * 0.75f
        val endX = width / 2
        val endY = height * 0.20f

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val strokeDescription = GestureDescription.StrokeDescription(path, 0, 300)
        val gestureBuilder = GestureDescription.Builder().apply {
            addStroke(strokeDescription)
        }

        dispatchGesture(gestureBuilder.build(), null, null)
    }

    private fun swipeDown() {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()

        val startX = width / 2
        val startY = height * 0.25f
        val endX = width / 2
        val endY = height * 0.80f

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val strokeDescription = GestureDescription.StrokeDescription(path, 0, 300)
        val gestureBuilder = GestureDescription.Builder().apply {
            addStroke(strokeDescription)
        }

        dispatchGesture(gestureBuilder.build(), null, null)
    }

    private fun performTap() {
        val displayMetrics = resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2f
        val centerY = displayMetrics.heightPixels / 2f

        val path = Path().apply {
            moveTo(centerX, centerY)
        }

        val strokeDescription = GestureDescription.StrokeDescription(path, 0, 80)
        val gestureBuilder = GestureDescription.Builder().apply {
            addStroke(strokeDescription)
        }

        dispatchGesture(gestureBuilder.build(), null, null)
    }

    private fun performDoubleTap() {
        val displayMetrics = resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2f
        val centerY = displayMetrics.heightPixels / 2f

        // Gesture tap 1
        val path1 = Path().apply { moveTo(centerX, centerY) }
        val gestureBuilder1 = GestureDescription.Builder().apply {
            addStroke(GestureDescription.StrokeDescription(path1, 0, 50))
        }

        dispatchGesture(gestureBuilder1.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                // Gesture tap 2 scheduled swift overlay matching double touch
                handler.postDelayed({
                    val path2 = Path().apply { moveTo(centerX, centerY) }
                    val gestureBuilder2 = GestureDescription.Builder().apply {
                        addStroke(GestureDescription.StrokeDescription(path2, 0, 50))
                    }
                    dispatchGesture(gestureBuilder2.build(), null, null)
                }, 150)
            }
        }, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        isServiceRunning = false
        stopListening()
        instance = null
        super.onDestroy()
    }
}
