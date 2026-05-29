package com.example

import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.content.SharedPreferences

class VoiceReelsAccessibilityService : AccessibilityService() {

    companion object {
        var isServiceRunning = false
            private set
            
        var isVoiceControlActive = false
            set(value) {
                field = value
                instance?.toggleListeningState(value)
            }
            
        var isOverlayEnabled = false
            set(value) {
                field = value
                instance?.toggleOverlay(value)
            }
            
        private var instance: VoiceReelsAccessibilityService? = null
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private val handler = Handler(Looper.getMainLooper())

    // Overlay components
    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var panelView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null

    // Audio system volumes preservation
    private var originalSystemVolume = -1
    private var isQuietModeActive = false

    // SharedPreferences listener
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        if (key == "show_floating_overlay") {
            val show = sharedPreferences.getBoolean("show_floating_overlay", false)
            toggleOverlay(show)
        }
        if (key == "global_voice_control_enabled") {
            val active = sharedPreferences.getBoolean("global_voice_control_enabled", false)
            isVoiceControlActive = active
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceRunning = true
        
        // Sync setting with saved value
        val prefs = getSharedPreferences("voice_reels_prefs", Context.MODE_PRIVATE)
        isVoiceControlActive = prefs.getBoolean("global_voice_control_enabled", false)
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        
        Toast.makeText(this, "Voice Reels Assistant Connected", Toast.LENGTH_LONG).show()
        
        if (isVoiceControlActive) {
            startListening()
        }

        // Toggle state matching saved window overlay visibility
        val showOverlay = prefs.getBoolean("show_floating_overlay", false)
        toggleOverlay(showOverlay)
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

        // Apply silence filter configuration
        muteSystemSounds()

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
        restoreSystemSounds()
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

    private fun muteSystemSounds() {
        val prefs = getSharedPreferences("voice_reels_prefs", Context.MODE_PRIVATE)
        val shouldMute = prefs.getBoolean("mute_voice_beeps", true)
        if (!shouldMute) return

        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_MUTE, 0)
            } else {
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(AudioManager.STREAM_SYSTEM, true)
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(AudioManager.STREAM_NOTIFICATION, true)
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(AudioManager.STREAM_ALARM, true)
            }
            isQuietModeActive = true
        } catch (e: Exception) {
            // silent catch
        }
    }

    private fun restoreSystemSounds() {
        if (!isQuietModeActive) return
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_UNMUTE, 0)
            } else {
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(AudioManager.STREAM_SYSTEM, false)
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(AudioManager.STREAM_NOTIFICATION, false)
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(AudioManager.STREAM_ALARM, false)
            }
        } catch (e: Exception) {
            // silent catch
        }
        isQuietModeActive = false
    }

    fun toggleOverlay(show: Boolean) {
        handler.post {
            if (show) {
                showFloatingViews()
            } else {
                hideFloatingViews()
            }
        }
    }

    private var listeningSwitch: Switch? = null
    private var silenceSwitch: Switch? = null

    private fun createBubbleAndPanelViews() {
        val context = this
        val metrics = resources.displayMetrics
        val dpToPx = { dp: Int -> (dp * metrics.density).toInt() }

        if (bubbleView == null) {
            val container = FrameLayout(context)
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF0F172A.toInt()) // slate 900
                setStroke(dpToPx(2), 0xFF38BDF8.toInt()) // sky cyan outline
            }
            container.background = shape
            container.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))

            val bubbleText = TextView(context).apply {
                text = "🎙️"
                textSize = 20f
                gravity = Gravity.CENTER
            }
            container.addView(bubbleText)

            container.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f
                private var isDragging = false

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = bubbleParams?.x ?: 0
                            initialY = bubbleParams?.y ?: 0
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isDragging = false
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - initialTouchX).toInt()
                            val dy = (event.rawY - initialTouchY).toInt()
                            if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                                isDragging = true
                            }
                            bubbleParams?.x = initialX + dx
                            bubbleParams?.y = initialY + dy
                            
                            if (panelView?.parent != null && panelParams != null) {
                                panelParams?.x = bubbleParams?.x ?: 100
                                panelParams?.y = (bubbleParams?.y ?: 300) + v.height + dpToPx(8)
                                windowManager?.updateViewLayout(panelView, panelParams)
                            }
                            
                            windowManager?.updateViewLayout(container, bubbleParams)
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!isDragging) {
                                toggleControlPanel()
                            }
                            return true
                        }
                    }
                    return false
                }
            })

            bubbleView = container
        }

        if (panelView == null) {
            val rootLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(0xFF1E293B.toInt()) // slate 800
                    cornerRadius = dpToPx(16).toFloat()
                    setStroke(dpToPx(1), 0xFF38BDF8.toInt()) // sky cyan outline
                }
                background = bg
                setPadding(dpToPx(14), dpToPx(14), dpToPx(14), dpToPx(14))
                layoutParams = ViewGroup.LayoutParams(
                    dpToPx(210),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            // Header Layout
            val headerLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val titleText = TextView(context).apply {
                text = "Voice Controller"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val minimizeBtn = TextView(context).apply {
                text = "✖"
                setTextColor(0xFF94A3B8.toInt())
                textSize = 14f
                setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6))
                setOnClickListener {
                    toggleControlPanel()
                }
            }
            headerLayout.addView(titleText)
            headerLayout.addView(minimizeBtn)
            rootLayout.addView(headerLayout)

            // Divider 1
            val divider1 = View(context).apply {
                setBackgroundColor(0xFF334155.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dpToPx(1)
                ).apply {
                    setMargins(0, dpToPx(8), 0, dpToPx(8))
                }
            }
            rootLayout.addView(divider1)

            val prefs = getSharedPreferences("voice_reels_prefs", Context.MODE_PRIVATE)

            // Toggle 1: Background Voice Control
            val listeningRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val listeningLabel = TextView(context).apply {
                text = "Voice Control"
                setTextColor(0xFFE2E8F0.toInt())
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            listeningSwitch = Switch(context).apply {
                isChecked = isVoiceControlActive
                setOnCheckedChangeListener { _, isChecked ->
                    prefs.edit().putBoolean("global_voice_control_enabled", isChecked).apply()
                    isVoiceControlActive = isChecked
                }
            }
            listeningRow.addView(listeningLabel)
            listeningRow.addView(listeningSwitch)
            rootLayout.addView(listeningRow)

            // Spacer
            val spacerPref = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(1, dpToPx(6))
            }
            rootLayout.addView(spacerPref)

            // Toggle 2: Silence Beeps
            val silenceRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val silenceLabel = TextView(context).apply {
                text = "Silence Beeps"
                setTextColor(0xFFE2E8F0.toInt())
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            silenceSwitch = Switch(context).apply {
                isChecked = prefs.getBoolean("mute_voice_beeps", true)
                setOnCheckedChangeListener { _, isChecked ->
                    prefs.edit().putBoolean("mute_voice_beeps", isChecked).apply()
                    if (!isChecked) {
                        restoreSystemSounds()
                    }
                }
            }
            silenceRow.addView(silenceLabel)
            silenceRow.addView(silenceSwitch)
            rootLayout.addView(silenceRow)

            // Divider 2
            val divider2 = View(context).apply {
                setBackgroundColor(0xFF334155.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dpToPx(1)
                ).apply {
                    setMargins(0, dpToPx(8), 0, dpToPx(8))
                }
            }
            rootLayout.addView(divider2)

            // Actions head
            val actionLabel = TextView(context).apply {
                text = "Manual Triggers"
                setTextColor(0xFF94A3B8.toInt())
                textSize = 10f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            rootLayout.addView(actionLabel)

            // Action Buttons Row 1 (Prev, Next)
            val btnRow1 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(6)
                }
            }
            
            val createPillButton = { textStr: String, onClickAction: () -> Unit ->
                TextView(context).apply {
                    text = textStr
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 11f
                    gravity = Gravity.CENTER
                    val btnBg = GradientDrawable().apply {
                        setColor(0xFF334155.toInt())
                        cornerRadius = dpToPx(8).toFloat()
                    }
                    background = btnBg
                    setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
                    setOnClickListener { onClickAction() }
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        setMargins(dpToPx(3), 0, dpToPx(3), 0)
                    }
                }
            }

            val nextBtn = createPillButton("Next ⬇️") { swipeUp() }
            val prevBtn = createPillButton("Prev ⬆️") { swipeDown() }
            btnRow1.addView(prevBtn)
            btnRow1.addView(nextBtn)
            rootLayout.addView(btnRow1)

            // Action Buttons Row 2 (Pause, Like)
            val btnRow2 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(6)
                }
            }
            val pauseBtn = createPillButton("Pause ⏸️") { performTap() }
            val likeBtn = createPillButton("Like ❤️") { performDoubleTap() }
            btnRow2.addView(pauseBtn)
            btnRow2.addView(likeBtn)
            rootLayout.addView(btnRow2)

            panelView = rootLayout
        }
    }

    private fun showFloatingViews() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return
        }

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        if (bubbleParams == null) {
            bubbleParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 300
            }
        }

        createBubbleAndPanelViews()

        if (bubbleView != null && bubbleView?.parent != null) {
            try {
                wm.removeView(bubbleView)
            } catch (e: Exception) {}
        }

        try {
            wm.addView(bubbleView, bubbleParams)
        } catch (e: Exception) {}
    }

    private fun hideFloatingViews() {
        val wm = windowManager ?: getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (panelView != null && panelView?.parent != null) {
            try {
                wm.removeView(panelView)
            } catch (e: Exception) {}
        }
        if (bubbleView != null && bubbleView?.parent != null) {
            try {
                wm.removeView(bubbleView)
            } catch (e: Exception) {}
        }
    }

    private fun toggleControlPanel() {
        val wm = windowManager ?: getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        val dpToPx = { dp: Int -> (dp * metrics.density).toInt() }

        if (panelView != null && panelView?.parent != null) {
            try {
                wm.removeView(panelView)
            } catch (e: Exception) {}
        } else {
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            if (panelParams == null) {
                panelParams = WindowManager.LayoutParams(
                    dpToPx(210),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                }
            }

            panelParams?.x = bubbleParams?.x ?: 100
            panelParams?.y = (bubbleParams?.y ?: 300) + (bubbleView?.height ?: dpToPx(50)) + dpToPx(8)

            // Update UI toggles to current states
            listeningSwitch?.isChecked = isVoiceControlActive
            val prefs = getSharedPreferences("voice_reels_prefs", Context.MODE_PRIVATE)
            silenceSwitch?.isChecked = prefs.getBoolean("mute_voice_beeps", true)

            try {
                wm.addView(panelView, panelParams)
            } catch (e: Exception) {}
        }
    }

    override fun onDestroy() {
        isServiceRunning = false
        stopListening()
        hideFloatingViews()
        val prefs = getSharedPreferences("voice_reels_prefs", Context.MODE_PRIVATE)
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        instance = null
        super.onDestroy()
    }
}
