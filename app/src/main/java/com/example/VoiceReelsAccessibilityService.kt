package com.example

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * The heart of Voice Reels.
 *
 * Listens for voice commands in the background and translates them into swipe / tap gestures (and,
 * where possible, real "like" button clicks) on whatever short-video app is in the foreground —
 * TikTok, Instagram Reels, Facebook Reels or YouTube Shorts.
 *
 * To stay responsive while a video is playing it ducks the foreground app's audio while listening,
 * prefers fast on-device recognition, and acts on *partial* results. To avoid accidentally
 * repeating an action when a word is heard several times, each command is rate-limited by a
 * configurable cooldown.
 */
class VoiceReelsAccessibilityService : AccessibilityService() {

    companion object {
        const val PKG_TIKTOK = "com.zhiliaoapp.musically"
        const val PKG_TIKTOK_ALT = "com.ss.android.ugc.aweme"
        const val PKG_YOUTUBE = "com.google.android.youtube"
        const val PKG_INSTAGRAM = "com.instagram.android"
        const val PKG_FACEBOOK = "com.facebook.katana"

        val SUPPORTED_PACKAGES = setOf(
            PKG_TIKTOK, PKG_TIKTOK_ALT, PKG_YOUTUBE, PKG_INSTAGRAM, PKG_FACEBOOK
        )

        const val PREF_COMMAND_COOLDOWN_MS = "command_cooldown_ms"

        // Default time the *same* command is suppressed after firing, so repeating a word
        // (e.g. "next … next … next") only acts once. User-adjustable in the UI.
        const val DEFAULT_COMMAND_COOLDOWN_MS = 2000L

        // A *different* command can follow much sooner, so "next" then "like" still feels snappy.
        private const val DIFFERENT_COMMAND_COOLDOWN_MS = 500L

        // Restart cadence — kept short so listening feels continuous and snappy.
        private const val RESTART_AFTER_RESULT_MS = 200L
        private const val RESTART_AFTER_ERROR_MS = 300L
        private const val RESTART_AFTER_BUSY_MS = 1000L

        private var instance: VoiceReelsAccessibilityService? = null

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _isListening = MutableStateFlow(false)
        val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

        private val _status = MutableStateFlow("Service not running")
        val status: StateFlow<String> = _status.asStateFlow()

        private val _lastCommand = MutableStateFlow<String?>(null)
        val lastCommand: StateFlow<String?> = _lastCommand.asStateFlow()

        private val _foregroundApp = MutableStateFlow<String?>(null)
        val foregroundApp: StateFlow<String?> = _foregroundApp.asStateFlow()

        var isVoiceControlActive: Boolean = false
            set(value) {
                field = value
                instance?.toggleListeningState(value)
            }

        var isOverlayEnabled: Boolean = false
            set(value) {
                field = value
                instance?.toggleOverlay(value)
            }
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private val handler = Handler(Looper.getMainLooper())

    private var currentPackage: String? = null

    // Per-utterance / dedupe bookkeeping.
    private var utteranceConsumed = false
    private var lastFiredCommand = VoiceCommand.NONE
    private var lastFiredAt = 0L

    // Audio ducking.
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var isQuietModeActive = false

    // Overlay components
    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var panelView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var listeningSwitch: Switch? = null
    private var silenceSwitch: Switch? = null
    private var overlaySwitch: Switch? = null

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            when (key) {
                "global_voice_control_enabled" -> {
                    val enabled = sharedPreferences.getBoolean(key, false)
                    if (isVoiceControlActive != enabled) isVoiceControlActive = enabled
                    handler.post { listeningSwitch?.isChecked = enabled }
                }
                "show_floating_overlay" -> {
                    val enabled = sharedPreferences.getBoolean(key, false)
                    if (isOverlayEnabled != enabled) isOverlayEnabled = enabled
                    handler.post { overlaySwitch?.isChecked = enabled }
                }
            }
        }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isRunning.value = true
        _status.value = "Ready"

        val prefs = prefs()
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)

        isVoiceControlActive = prefs.getBoolean("global_voice_control_enabled", false)
        toggleOverlay(prefs.getBoolean("show_floating_overlay", false))

        Toast.makeText(this, "Voice Reels assistant connected", Toast.LENGTH_SHORT).show()
    }

    // region Foreground app tracking ------------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg == packageName) return
            currentPackage = pkg
            _foregroundApp.value = pkg
        }
    }

    override fun onInterrupt() {}

    // endregion

    // region Speech recognition ----------------------------------------------------------------

    fun toggleListeningState(active: Boolean) {
        handler.post { if (active) startListening() else stopListening() }
    }

    private fun startListening() {
        if (!_isRunning.value || !isVoiceControlActive) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { startListening() }
            return
        }
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            _status.value = "Microphone permission needed"
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            _status.value = "No speech engine on this device"
            return
        }

        muteSystemSoundsIfRequested()
        acquireAudioDucking()

        try {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(createSpeechListener())
                }
            }
            if (recognizerIntent == null) {
                recognizerIntent = buildRecognizerIntent()
            }
            utteranceConsumed = false
            speechRecognizer?.startListening(recognizerIntent)
            _isListening.value = true
            _status.value = "Listening…"
        } catch (e: Exception) {
            _status.value = "Could not start microphone"
            scheduleRestart(RESTART_AFTER_ERROR_MS)
        }
    }

    private fun buildRecognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                450L
            )
        }

    private fun stopListening() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { stopListening() }
            return
        }
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            // ignore
        }
        speechRecognizer = null
        _isListening.value = false
        if (_isRunning.value) _status.value = "Paused"
        releaseAudioDucking()
        restoreSystemSounds()
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!isVoiceControlActive || !_isRunning.value) return
        handler.postDelayed({
            if (isVoiceControlActive && _isRunning.value) startListening()
        }, delayMs)
    }

    private fun createSpeechListener(): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            utteranceConsumed = false
            _status.value = "Listening…"
        }

        override fun onBeginningOfSpeech() { _status.value = "Hearing you…" }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { _status.value = "Processing…" }

        override fun onError(error: Int) {
            _isListening.value = false
            val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                RESTART_AFTER_BUSY_MS
            } else {
                RESTART_AFTER_ERROR_MS
            }
            scheduleRestart(delay)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            tryFireFrom(partialResults)
        }

        override fun onResults(results: Bundle?) {
            if (!tryFireFrom(results)) {
                val first = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!first.isNullOrBlank()) _lastCommand.value = "\"$first\""
            }
            _isListening.value = false
            scheduleRestart(RESTART_AFTER_RESULT_MS)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** Returns true if the speech contained a recognizable command (whether or not it acted). */
    private fun tryFireFrom(results: Bundle?): Boolean {
        if (utteranceConsumed) return false
        val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?: return false
        val command = VoiceCommandParser.parse(candidates)
        if (command == VoiceCommand.NONE) return false
        fireCommand(command, candidates.firstOrNull().orEmpty())
        return true
    }

    private fun fireCommand(command: VoiceCommand, heard: String) {
        val now = SystemClock.elapsedRealtime()
        val sameCommand = command == lastFiredCommand
        val cooldown = if (sameCommand) commandCooldownMs() else DIFFERENT_COMMAND_COOLDOWN_MS
        if (now - lastFiredAt < cooldown) {
            // Within the cooldown window: swallow the repeat so we don't act twice.
            utteranceConsumed = true
            return
        }

        utteranceConsumed = true
        val performed = perform(command, heard)
        if (performed) {
            lastFiredCommand = command
            lastFiredAt = now
        }
    }

    /** Performs the command; returns true if an actual gesture was dispatched. */
    private fun perform(command: VoiceCommand, heard: String): Boolean = when (command) {
        VoiceCommand.NEXT -> { announce("Next ⬇️", heard); swipeUp(); true }
        VoiceCommand.PREVIOUS -> { announce("Previous ⬆️", heard); swipeDown(); true }
        VoiceCommand.LIKE -> { announce("Like ❤️", heard); doLike(); true }
        VoiceCommand.PLAY ->
            if (isVideoPlaying()) {
                info("Already playing")
                false
            } else {
                announce("Play ▶️", heard); performTap(); true
            }
        VoiceCommand.PAUSE ->
            if (isVideoPlaying()) {
                announce("Pause ⏸️", heard); performTap(); true
            } else {
                info("Already paused")
                false
            }
        VoiceCommand.NONE -> false
    }

    /** Best-effort check of whether the foreground app is currently playing audio/video. */
    private fun isVideoPlaying(): Boolean = try {
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive
    } catch (e: Exception) {
        false
    }

    private fun announce(action: String, heard: String) {
        _lastCommand.value = if (heard.isBlank()) action else "$action  ·  \"$heard\""
        showMatchToast(action)
    }

    private fun info(message: String) {
        _lastCommand.value = message
    }

    // endregion

    // region Audio ducking ---------------------------------------------------------------------

    private fun acquireAudioDucking() {
        if (hasAudioFocus) return
        if (!prefs().getBoolean("duck_media_audio", true)) return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(false)
                .build()
            audioFocusRequest = request
            am.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
        hasAudioFocus = granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun releaseAudioDucking() {
        if (!hasAudioFocus) return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
        hasAudioFocus = false
    }

    // endregion

    // region Gestures --------------------------------------------------------------------------

    private fun screenWidth() = resources.displayMetrics.widthPixels.toFloat()
    private fun screenHeight() = resources.displayMetrics.heightPixels.toFloat()

    private fun dispatchSwipe(startYFraction: Float, endYFraction: Float, durationMs: Long = 220L) {
        val x = screenWidth() / 2f
        val path = Path().apply {
            moveTo(x, screenHeight() * startYFraction)
            lineTo(x, screenHeight() * endYFraction)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun swipeUp() = dispatchSwipe(0.80f, 0.22f)

    private fun swipeDown() = dispatchSwipe(0.25f, 0.83f)

    private fun performTap() {
        val path = Path().apply { moveTo(screenWidth() / 2f, screenHeight() / 2f) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun performDoubleTap() {
        val cx = screenWidth() / 2f
        val cy = screenHeight() / 2f
        val first = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(cx, cy) }, 0, 50))
            .build()
        dispatchGesture(first, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                handler.postDelayed({
                    val second = GestureDescription.Builder()
                        .addStroke(
                            GestureDescription.StrokeDescription(
                                Path().apply { moveTo(cx, cy) }, 0, 50
                            )
                        )
                        .build()
                    dispatchGesture(second, null, null)
                }, 120)
            }
        }, null)
    }

    private fun doLike() {
        if (currentPackage == PKG_YOUTUBE) {
            if (!clickLikeButton()) performDoubleTap()
        } else {
            performDoubleTap()
        }
    }

    private fun clickLikeButton(): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findLikeNode(root, 0) ?: return false
        var clickable: AccessibilityNodeInfo? = node
        var depth = 0
        while (clickable != null && !clickable.isClickable && depth < 6) {
            clickable = clickable.parent
            depth++
        }
        return clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun findLikeNode(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 40) return null
        val desc = node.contentDescription?.toString()?.lowercase()
        if (desc != null &&
            desc.contains("like") &&
            !desc.contains("dislike") &&
            !desc.contains("unlike")
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            findLikeNode(node.getChild(i), depth + 1)?.let { return it }
        }
        return null
    }

    private fun showMatchToast(message: String) {
        Toast.makeText(applicationContext, "Voice Reels: $message", Toast.LENGTH_SHORT).show()
    }

    // endregion

    // region System sound muting ---------------------------------------------------------------

    private fun muteSystemSoundsIfRequested() {
        if (isQuietModeActive) return
        if (!prefs().getBoolean("mute_voice_beeps", true)) return
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
            am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
            isQuietModeActive = true
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun restoreSystemSounds() {
        if (!isQuietModeActive) return
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
            am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
        } catch (e: Exception) {
            // ignore
        }
        isQuietModeActive = false
    }

    // endregion

    // region Floating overlay ------------------------------------------------------------------

    fun toggleOverlay(show: Boolean) {
        handler.post { if (show) showFloatingViews() else hideFloatingViews() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun createBubbleAndPanelViews() {
        val context = this

        if (bubbleView == null) {
            val container = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF0F172A.toInt())
                    setStroke(dp(2), 0xFF38BDF8.toInt())
                }
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            container.addView(TextView(context).apply {
                text = "🎙️"
                textSize = 20f
                gravity = Gravity.CENTER
            })
            container.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var touchX = 0f
                private var touchY = 0f
                private var dragging = false

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = bubbleParams?.x ?: 0
                            initialY = bubbleParams?.y ?: 0
                            touchX = event.rawX
                            touchY = event.rawY
                            dragging = false
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - touchX).toInt()
                            val dy = (event.rawY - touchY).toInt()
                            if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) dragging = true
                            bubbleParams?.x = initialX + dx
                            bubbleParams?.y = initialY + dy
                            if (panelView?.parent != null && panelParams != null) {
                                panelParams?.x = bubbleParams?.x ?: 100
                                panelParams?.y = (bubbleParams?.y ?: 300) + v.height + dp(8)
                                runCatching { windowManager?.updateViewLayout(panelView, panelParams) }
                            }
                            runCatching { windowManager?.updateViewLayout(v, bubbleParams) }
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!dragging) toggleControlPanel()
                            return true
                        }
                    }
                    return false
                }
            })
            bubbleView = container
        }

        if (panelView == null) {
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(0xFF1E293B.toInt())
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), 0xFF38BDF8.toInt())
                }
                setPadding(dp(14), dp(14), dp(14), dp(14))
                layoutParams = ViewGroup.LayoutParams(dp(210), ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(TextView(context).apply {
                text = "Voice Reels"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            header.addView(TextView(context).apply {
                text = "✖"
                setTextColor(0xFF94A3B8.toInt())
                textSize = 14f
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setOnClickListener { toggleControlPanel() }
            })
            root.addView(header)
            root.addView(divider())

            val prefs = prefs()

            val overlayVisible = prefs.getBoolean("show_floating_overlay", false)

            root.addView(switchRow("Voice control", isVoiceControlActive) { checked ->
                prefs.edit().putBoolean("global_voice_control_enabled", checked).apply()
                isVoiceControlActive = checked
            }.also { listeningSwitch = it.second }.first)

            root.addView(switchRow("Silence beeps", prefs.getBoolean("mute_voice_beeps", true)) { checked ->
                prefs.edit().putBoolean("mute_voice_beeps", checked).apply()
                if (!checked) restoreSystemSounds()
            }.also { silenceSwitch = it.second }.first)

            root.addView(switchRow("Floating bubble", overlayVisible) { checked ->
                prefs.edit().putBoolean("show_floating_overlay", checked).apply()
                isOverlayEnabled = checked
            }.also { overlaySwitch = it.second }.first)

            root.addView(divider())
            root.addView(TextView(context).apply {
                text = "Manual triggers"
                setTextColor(0xFF94A3B8.toInt())
                textSize = 10f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })

            root.addView(buttonRow("Prev ⬆️", { swipeDown() }, "Next ⬇️", { swipeUp() }))
            root.addView(buttonRow("Tap ⏯️", { performTap() }, "Like ❤️", { doLike() }))

            panelView = root
        }
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(0xFF334155.toInt())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            setMargins(0, dp(8), 0, dp(8))
        }
    }

    private fun switchRow(
        label: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): Pair<View, Switch> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(0xFFE2E8F0.toInt())
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val sw = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        }
        row.addView(sw)
        return row to sw
    }

    private fun buttonRow(
        leftText: String, leftAction: () -> Unit,
        rightText: String, rightAction: () -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        row.addView(pillButton(leftText, leftAction))
        row.addView(pillButton(rightText, rightAction))
        return row
    }

    private fun pillButton(text: String, action: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 11f
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(0xFF334155.toInt())
            cornerRadius = dp(8).toFloat()
        }
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(3), 0, dp(3), 0)
        }
    }

    private fun showFloatingViews() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return
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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 300
            }
        }

        createBubbleAndPanelViews()
        if (bubbleView?.parent != null) runCatching { wm.removeView(bubbleView) }
        runCatching { wm.addView(bubbleView, bubbleParams) }
    }

    private fun hideFloatingViews() {
        val wm = windowManager ?: getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (panelView?.parent != null) runCatching { wm.removeView(panelView) }
        if (bubbleView?.parent != null) runCatching { wm.removeView(bubbleView) }
    }

    private fun toggleControlPanel() {
        val wm = windowManager ?: getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (panelView?.parent != null) {
            runCatching { wm.removeView(panelView) }
            return
        }
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        if (panelParams == null) {
            panelParams = WindowManager.LayoutParams(
                dp(210),
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
        }
        panelParams?.x = bubbleParams?.x ?: 100
        panelParams?.y = (bubbleParams?.y ?: 300) + (bubbleView?.height ?: dp(50)) + dp(8)
        val prefs = prefs()
        listeningSwitch?.isChecked = isVoiceControlActive
        silenceSwitch?.isChecked = prefs.getBoolean("mute_voice_beeps", true)
        overlaySwitch?.isChecked = prefs.getBoolean("show_floating_overlay", false)
        runCatching { wm.addView(panelView, panelParams) }
    }

    // endregion

    private fun prefs(): SharedPreferences =
        getSharedPreferences("voice_reels_prefs", Context.MODE_PRIVATE)

    private fun commandCooldownMs(): Long =
        prefs().getInt(PREF_COMMAND_COOLDOWN_MS, DEFAULT_COMMAND_COOLDOWN_MS.toInt()).toLong()

    override fun onDestroy() {
        _isRunning.value = false
        _status.value = "Service not running"
        stopListening()
        hideFloatingViews()
        runCatching { prefs().unregisterOnSharedPreferenceChangeListener(preferenceListener) }
        instance = null
        super.onDestroy()
    }
}
