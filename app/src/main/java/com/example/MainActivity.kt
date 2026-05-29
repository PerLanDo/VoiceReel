package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

class MainActivity : ComponentActivity() {

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private lateinit var mainViewModel: VoiceReelsViewModel

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            setupAndStartListening()
        } else {
            mainViewModel.setVoiceStatus("Voice simulation active (permission denied)")
            Toast.makeText(this, "Microphone permission is required for full hands-free scrolling.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            MyApplicationTheme {
                val viewModel: VoiceReelsViewModel = viewModel()
                mainViewModel = viewModel
                
                // Track dynamic speech recognizer engine check
                val context = LocalContext.current
                LaunchedEffect(Unit) {
                    val available = SpeechRecognizer.isRecognitionAvailable(context)
                    viewModel.setSpeechEngineActive(available)
                    
                    // Trigger initial permission check
                    checkPermissionsAndListen()
                }

                // Restart or stop speech listening depending on state toggle in UI
                val voiceEnabled by viewModel.isVoiceEnabled.collectAsState()
                LaunchedEffect(voiceEnabled) {
                    if (voiceEnabled) {
                        checkPermissionsAndListen()
                    } else {
                        stopSpeechListening()
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color(0xFF020617) // Deep Slate 950 Classic Dark Design theme
                ) { innerPadding ->
                    VoiceReelsApp(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding),
                        onRequestPermission = { checkPermissionsAndListen(forceRequest = true) }
                    )
                }
            }
        }
    }

    private fun checkPermissionsAndListen(forceRequest: Boolean = false) {
        val permissionState = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        if (permissionState == PackageManager.PERMISSION_GRANTED) {
            setupAndStartListening()
        } else if (forceRequest) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            mainViewModel.setVoiceStatus("Microphone access ready")
        }
    }

    private fun setupAndStartListening() {
        if (!mainViewModel.isVoiceEnabled.value) return
        
        // CRITICAL CONCURRENCY PROTECTION:
        // Do not listen locally in MainActivity if the Background Accessibility Service is active and listening!
        if (VoiceReelsAccessibilityService.isServiceRunning && VoiceReelsAccessibilityService.isVoiceControlActive) {
            mainViewModel.setVoiceStatus("System Assistant Active")
            stopSpeechListening()
            return
        }

        try {
            // Ensure action runs on the main thread
            if (Looper.myLooper() != Looper.getMainLooper()) {
                Handler(Looper.getMainLooper()).post { setupAndStartListening() }
                return
            }

            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                return
            }

            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(createSpeechListener())
                }
            }

            if (recognizerIntent == null) {
                recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
            }

            speechRecognizer?.startListening(recognizerIntent)
            mainViewModel.setVoiceStatus("Listening...")
        } catch (e: Exception) {
            mainViewModel.setSpeechEngineActive(false)
            mainViewModel.setVoiceStatus("Voice simulator active")
        }
    }

    private fun stopSpeechListening() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { stopSpeechListening() }
            return
        }
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            // silent close
        }
    }

    private fun createSpeechListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                mainViewModel.setVoiceStatus("Listening...")
            }

            override fun onBeginningOfSpeech() {
                mainViewModel.setVoiceStatus("Hearing...")
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                mainViewModel.setVoiceStatus("Processing...")
            }

            override fun onError(error: Int) {
                val explanation = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio read error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client issue"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "No micro permission"
                    SpeechRecognizer.ERROR_NETWORK -> "Network issue"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timed out"
                    SpeechRecognizer.ERROR_NO_MATCH -> "Listening..."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Mic busy"
                    SpeechRecognizer.ERROR_SERVER -> "Voice server issue"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening..."
                    else -> "Restarting mic capture"
                }
                mainViewModel.setVoiceStatus(explanation)

                // Use gentle sleep/retry delays to avoid burning main thread / binder limits
                val delayTime = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 3000L else 2000L

                // Restart auto-listen loop on silence / mismatch to remain fully hands-free
                lifecycleScope.launch {
                    delay(delayTime)
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && mainViewModel.isVoiceEnabled.value) {
                         setupAndStartListening()
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    mainViewModel.handleVoiceInput(matches[0])
                }

                // Restart continuous listening
                lifecycleScope.launch {
                    delay(1200)
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && mainViewModel.isVoiceEnabled.value) {
                        setupAndStartListening()
                    }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    mainViewModel.toggleVoiceFeedback(matches[0])
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    override fun onResume() {
        super.onResume()
        if (::mainViewModel.isInitialized && mainViewModel.isVoiceEnabled.value) {
            checkPermissionsAndListen()
        }
        val prefs = getSharedPreferences("voice_reels_prefs", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val hasPerm = Settings.canDrawOverlays(this)
            val showOverlay = prefs.getBoolean("show_floating_overlay", false)
            if (showOverlay && !hasPerm) {
                prefs.edit().putBoolean("show_floating_overlay", false).apply()
                VoiceReelsAccessibilityService.isOverlayEnabled = false
            }
        }
    }

    override fun onPause() {
        stopSpeechListening()
        super.onPause()
    }

    override fun onDestroy() {
        stopSpeechListening()
        super.onDestroy()
    }
}

@Composable
fun VoiceReelsApp(
    viewModel: VoiceReelsViewModel,
    modifier: Modifier = Modifier,
    onRequestPermission: () -> Unit
) {
    val context = LocalContext.current
    val reels by viewModel.reels.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val voiceStatus by viewModel.voiceStatus.collectAsState()
    val lastHeardText by viewModel.lastHeardSentence.collectAsState()
    val voiceEnabled by viewModel.isVoiceEnabled.collectAsState()
    val showHeartPop by viewModel.showHeartPop.collectAsState()
    val hudState by viewModel.hudState.collectAsState()

    val currentReel = reels.getOrNull(currentIndex)

    val hasMicPermission = remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    LaunchedEffect(currentIndex, voiceEnabled) {
        hasMicPermission.value = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    var activeTab by remember { mutableStateOf("home") }
    var isServiceEnabled by remember { mutableStateOf(VoiceReelsAccessibilityService.isServiceRunning) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isServiceEnabled = VoiceReelsAccessibilityService.isServiceRunning
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF020617)) // Slate 950 Canvas
    ) {
        // 1. Status Bar Simulation overlay
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "10:45",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.SignalCellular4Bar,
                    contentDescription = "Signal status",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Icon(
                    imageVector = Icons.Default.Wifi,
                    contentDescription = "Wifi connection",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Icon(
                    imageVector = Icons.Default.BatteryFull,
                    contentDescription = "Battery charge",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // 2. Screen Content block
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(Color(0xFF0F172A)) // Slate 900
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(32.dp))
        ) {
            if (activeTab == "explore") {
                GlobalAssistantScreen(
                    context = context,
                    isServiceEnabled = isServiceEnabled,
                    onActivateClick = {
                        try {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Please open Settings -> Accessibility manually", Toast.LENGTH_LONG).show()
                        }
                    }
                )
            } else if (currentReel != null) {
                var totalDragY by remember { mutableStateOf(0f) }
                
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(currentReel.id) {
                            detectDragGestures(
                                onDragStart = { totalDragY = 0f },
                                onDragEnd = {
                                    if (totalDragY < -100f) {
                                        viewModel.scrollNext()
                                    } else if (totalDragY > 100f) {
                                        viewModel.scrollPrev()
                                    }
                                },
                                onDragCancel = { totalDragY = 0f },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    totalDragY += dragAmount.y
                                }
                            )
                        }
                ) {
                    // Vertical sliding Transition animations
                    AnimatedContent(
                        targetState = currentReel,
                        transitionSpec = {
                            if (targetState.id > initialState.id) {
                                slideInVertically { height -> height } + fadeIn() togetherWith
                                        slideOutVertically { height -> -height } + fadeOut()
                            } else {
                                slideInVertically { height -> -height } + fadeIn() togetherWith
                                        slideOutVertically { height -> height } + fadeOut()
                            }
                        },
                        label = "reel_scroll_anim"
                    ) { activeReel ->
                        Box(modifier = Modifier.fillMaxSize()) {
                            // High FPS procedurally-simulated artistic feed animations
                            AnimatedReelCanvas(
                                type = activeReel.type,
                                isPlaying = isPlaying,
                                modifier = Modifier.fillMaxSize()
                            )

                            // Depth gradient shadows
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                Color.Black.copy(alpha = 0.35f),
                                                Color.Transparent,
                                                Color.Black.copy(alpha = 0.65f)
                                            )
                                        )
                                    )
                            )

                            // Top selection tabs overlay
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(24.dp)
                            ) {
                                Text(
                                    text = "Following",
                                    color = Color.White.copy(alpha = 0.55f),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "For You",
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(width = 16.dp, height = 2.dp)
                                            .background(Color.White)
                                    )
                                }
                            }

                            // Sidebar Buttons row (Right)
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(bottom = 120.dp, end = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(20.dp)
                            ) {
                                // Author avatar profile
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(Color(activeReel.avatarColor))
                                        .border(2.dp, Color.White, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = activeReel.creator.take(1).uppercase(),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp
                                    )
                                }

                                // Interactive Likes
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    IconButton(
                                        onClick = { viewModel.toggleLikeCurrent() },
                                        modifier = Modifier
                                            .testTag("like_button")
                                            .size(46.dp)
                                            .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Favorite,
                                            contentDescription = "Like button icon",
                                            tint = if (activeReel.isLiked) Color(0xFFF43F5E) else Color.White,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = formatNumber(activeReel.likesCount),
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                // Comments
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    IconButton(
                                        onClick = { 
                                            Toast.makeText(context, "Comments section (Simulated)", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier
                                            .testTag("comments_button")
                                            .size(46.dp)
                                            .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.ChatBubble,
                                            contentDescription = "Comment button icon",
                                            tint = Color.White,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = formatNumber(activeReel.commentsCount),
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                // Save/Bookmark
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    IconButton(
                                        onClick = { viewModel.toggleBookmarkCurrent() },
                                        modifier = Modifier
                                            .testTag("bookmark_button")
                                            .size(46.dp)
                                            .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Bookmark,
                                            contentDescription = "Bookmark button icon",
                                            tint = if (activeReel.isBookmarked) Color(0xFFFBBF24) else Color.White,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = formatNumber(activeReel.bookmarksCount),
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                // Share
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    IconButton(
                                        onClick = {
                                            Toast.makeText(context, "Share link generated!", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier
                                            .testTag("share_button")
                                            .size(46.dp)
                                            .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Share,
                                            contentDescription = "Share button icon",
                                            tint = Color.White,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = formatNumber(activeReel.sharesCount),
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // Left author metadata Overlay details
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(start = 16.dp, end = 80.dp, bottom = 24.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .background(Color(activeReel.avatarColor)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = activeReel.creator.take(1).uppercase(),
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Text(
                                        text = "@${activeReel.creator}",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    
                                    Button(
                                        onClick = { viewModel.toggleFollowCurrent() },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (activeReel.isFollowed) Color.White.copy(alpha = 0.2f) else Color.White,
                                            contentColor = if (activeReel.isFollowed) Color.White else Color.Black
                                        ),
                                        modifier = Modifier
                                            .height(26.dp)
                                            .testTag("follow_button"),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Text(
                                            text = if (activeReel.isFollowed) "Following" else "Follow",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(10.dp))
                                
                                Text(
                                    text = activeReel.caption,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    lineHeight = 18.sp
                                )
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(bottom = 8.dp)
                                ) {
                                    activeReel.hashtags.forEach { tag ->
                                        Text(
                                            text = "#$tag",
                                            color = Color(0xFF38BDF8),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }

                                // Spinning sound disc tag capsule
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier
                                        .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.MusicNote,
                                        contentDescription = "Audio track",
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = activeReel.musicTrack,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            // Infinite vinyl spinning record disc
                            var rotationAngle by remember { mutableStateOf(0f) }
                            LaunchedEffect(isPlaying) {
                                if (isPlaying) {
                                    while (true) {
                                        rotationAngle += 4f
                                        delay(16)
                                    }
                                }
                            }
                            
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 16.dp, bottom = 24.dp)
                                    .size(40.dp)
                                    .rotate(rotationAngle)
                                    .clip(CircleShape)
                                    .background(Color.Black)
                                    .border(2.dp, Color.White.copy(alpha = 0.5f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(Color(activeReel.avatarColor))
                                )
                            }
                        }
                    }

                    // 1. Double tap / VOICE LIKE Red heart pop-up graphics
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showHeartPop,
                        enter = scaleIn(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) + fadeIn(),
                        exit = scaleOut() + fadeOut(),
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = "Giant visual heart",
                            tint = Color(0xFFEC4899),
                            modifier = Modifier
                                .size(110.dp)
                                .shadow(8.dp, CircleShape)
                        )
                    }

                    // 2. Play / Pause dynamic HUD overlays
                    androidx.compose.animation.AnimatedVisibility(
                        visible = hudState != null,
                        enter = fadeIn(animationSpec = tween(150)) + scaleIn(),
                        exit = fadeOut(animationSpec = tween(200)) + scaleOut(),
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (hudState == "PLAY") Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                contentDescription = "HUD State icon representation",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }

        // 3. Bottom Voice Controls status band
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF1E293B).copy(alpha = 0.85f)) // Translucent slate card matching design instructions
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Breathing mic pulse effect
                        val breathingInfiniteSec = rememberInfiniteTransition(label = "mic_breathing_pulse")
                        val breathingScale by breathingInfiniteSec.animateFloat(
                            initialValue = 0.9f,
                            targetValue = 1.3f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1400, easing = EaseInOutBack),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "breathing_value"
                        )
                        
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(40.dp)
                        ) {
                            if (voiceEnabled && voiceStatus.contains("Listening")) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .scale(breathingScale)
                                        .clip(CircleShape)
                                        .background(Color(0xFF3B82F6).copy(alpha = 0.25f)) // Blue pulsing glow active
                                )
                            }
                            IconButton(
                                onClick = { viewModel.toggleVoiceEnabled() },
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(
                                        if (voiceEnabled) Color(0xFF3B82F6) else Color(0xFF475569),
                                        CircleShape
                                    )
                                    .testTag("microphone_toggle")
                            ) {
                                Icon(
                                    imageVector = if (voiceEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
                                    contentDescription = "Voice switch",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Speech feedback detail texts
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
                                        .clip(CircleShape)
                                        .background(if (voiceEnabled) Color(0xFF34D399) else Color(0xFFF87171))
                                )
                                Text(
                                    text = if (voiceEnabled) "Voice Active" else "Voice Control Staggered",
                                    color = if (voiceEnabled) Color(0xFF93C5FD) else Color(0xFF94A3B8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                            }
                            
                            Text(
                                text = lastHeardText ?: "\"Say 'Next' to scroll\"",
                                color = Color(0xFFE2E8F0),
                                fontSize = 13.sp,
                                fontStyle = FontStyle.Italic,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Listening State status tag details
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (voiceEnabled) voiceStatus else "Off",
                            color = if (voiceStatus.contains("Matched") || voiceStatus.contains("Hearing")) Color(0xFFFBBF24) else Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        
                        Box(
                            modifier = Modifier
                                .height(24.dp)
                                .width(1.dp)
                                .background(Color.White.copy(alpha = 0.15f))
                        )
                        
                        IconButton(
                            onClick = { viewModel.togglePlayPause() },
                            modifier = Modifier
                                .testTag("play_pause_button")
                                .size(34.dp)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = "Simulated status controller",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }

            // Developer simulation panel: ensures 100% testability offline / noisy environments
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Tap to simulate spoken phrase matching:",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
                
                if (!hasMicPermission.value && voiceEnabled) {
                    Text(
                        text = "Mic permission requested • tap here",
                        color = Color(0xFF38BDF8),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { onRequestPermission() }
                            .padding(vertical = 2.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(2.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    Pair("Play", "play"),
                    Pair("Pause", "pause"),
                    Pair("Next", "next"),
                    Pair("Prev", "back"),
                    Pair("Like", "like")
                ).forEach { (label, command) ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF334155).copy(alpha = 0.4f)) // Translucent dark style simulation nodes
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            .clickable {
                                viewModel.handleVoiceInput(command)
                            }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 4. Android Navigation Bar simulation block
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF020617))
                .border(width = (0.5).dp, color = Color.White.copy(alpha = 0.05f))
                .padding(bottom = 12.dp, top = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clickable { activeTab = "home" }
                    .alpha(if (activeTab == "home") 1f else 0.5f)
            ) {
                Icon(
                    imageVector = Icons.Filled.Home,
                    contentDescription = "Home simulation tab",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Home",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clickable { activeTab = "explore" }
                    .alpha(if (activeTab == "explore") 1f else 0.5f)
            ) {
                Icon(
                    imageVector = Icons.Filled.Explore,
                    contentDescription = "System voice assistant configuration",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Assistant",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .clickable {
                        Toast.makeText(context, "Record content!", Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Add simulator trigger",
                    tint = Color.Black,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clickable {
                        Toast.makeText(context, "Inbox placeholder screen", Toast.LENGTH_SHORT).show()
                    }
                    .alpha(0.5f)
            ) {
                Icon(
                    imageVector = Icons.Filled.Email,
                    contentDescription = "Inbox feedback tab",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Inbox",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clickable {
                        Toast.makeText(context, "User details card placeholder", Toast.LENGTH_SHORT).show()
                    }
                    .alpha(0.5f)
            ) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = "Simulated User profile",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Profile",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// Global wrap composable for Canvas
@Composable
fun AnimatedReelCanvas(type: ReelType, isPlaying: Boolean, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "animated_canvas_loops")
    val timeState = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(40000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase_offset"
    )
    val time = if (isPlaying) timeState.value else 0f

    Canvas(modifier = modifier.fillMaxSize()) {
        when (type) {
            ReelType.SWISS_ALPS -> drawAlpsCanvas(time)
            ReelType.NEON_CYBER -> drawCyberCanvas(time)
            ReelType.WARM_BAKING -> drawBakingCanvas(time)
            ReelType.WAVE_SURFING -> drawWaveCanvas(time)
            ReelType.CODER_BEATS -> drawCoderCanvas(time)
        }
    }
}

// Drawing routines updated to correct dot extension syntax (.dp) and proper Canvas types
fun DrawScope.drawAlpsCanvas(time: Float) {
    val h = size.height
    val w = size.width
    
    // Twilight gradient
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFF030712), Color(0xFF1E1B4B), Color(0xFF020617))
        )
    )
    
    // Remote simple stars
    val starsSeed = 15
    for (i in 0 until starsSeed) {
        val sx = (((i * 83) % 100) / 100f) * w
        val sy = (((i * 37) % 100) / 100f) * (h * 0.4f)
        val alphaVal = 0.3f + (sin((time * 0.08f + i).toDouble()).toFloat() * 0.3f)
        drawCircle(
            color = Color.White,
            radius = 1.5.dp.toPx(),
            center = Offset(sx, sy),
            alpha = alphaVal
        )
    }

    // Mountain Layer 1 Background
    val mt1 = Path().apply {
        moveTo(0f, h * 0.65f)
        lineTo(w * 0.3f, h * 0.4f)
        lineTo(w * 0.65f, h * 0.58f)
        lineTo(w * 0.85f, h * 0.45f)
        lineTo(w, h * 0.62f)
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }
    drawPath(path = mt1, color = Color(0xFF111827).copy(alpha = 0.9f))

    // Mountain Layer 2 Foreground Peak
    val mt2 = Path().apply {
        moveTo(0f, h * 0.82f)
        lineTo(w * 0.2f, h * 0.66f)
        lineTo(w * 0.5f, h * 0.52f)
        lineTo(w * 0.8f, h * 0.72f)
        lineTo(w, h * 0.68f)
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }
    drawPath(path = mt2, color = Color(0xFF0F172A))
    
    // Snowy peak cap
    val mtCap = Path().apply {
        moveTo(w * 0.35f, h * 0.61f)
        lineTo(w * 0.5f, h * 0.52f)
        lineTo(w * 0.62f, h * 0.6f)
        lineTo(w * 0.5f, h * 0.63f)
        close()
    }
    drawPath(path = mtCap, color = Color.White.copy(alpha = 0.15f))

    // Snowflake falling particles
    val numSnowflakes = 18
    for (i in 0 until numSnowflakes) {
        val xFraction = ((i * 19) % 100) / 100f
        val ySpeed = 0.06f + (i % 4) * 0.02f
        
        val sx = (xFraction * w + sin((time * 0.06f + i).toDouble()).toFloat() * 12f) % w
        val sy = (ySpeed * time * 240f + i * 72f) % h
        
        drawCircle(
            color = Color.White,
            radius = (2.dp.toPx() + (i % 3) * 1.5.dp.toPx()),
            center = Offset(sx, sy),
            alpha = 0.5f + (i % 3) * 0.15f
        )
    }
}

fun DrawScope.drawCyberCanvas(time: Float) {
    val h = size.height
    val w = size.width
    
    // Cyber magenta gradient
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFF020617), Color(0xFF1E1B4B), Color(0xFF311042))
        )
    )
    
    val horizonY = h * 0.45f
    
    // Grid perspective ray lines
    val rays = 10
    for (i in 0..rays) {
        val progress = i / rays.toFloat()
        val bottomX = w * progress
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF06B6D4).copy(alpha = 0.05f), Color(0xFF06B6D4).copy(alpha = 0.6f))
            ),
            start = Offset(w / 2f, horizonY),
            end = Offset(bottomX, h),
            strokeWidth = 1.5.dp.toPx()
        )
    }

    // Grid depth rings advancing forward
    val countGridLines = 6
    for (i in 0 until countGridLines) {
        val scaleShift = ((time * 0.05f + i / countGridLines.toFloat()) % 1.0f)
        val gridY = horizonY + (scaleShift * scaleShift) * (h - horizonY)
        drawLine(
            color = Color(0xFFEC4899).copy(alpha = 0.5f * scaleShift),
            start = Offset(0f, gridY),
            end = Offset(w, gridY),
            strokeWidth = 1.dp.toPx()
        )
    }

    // Scanning horizontal laser beam
    val laserY = horizonY + (sin((time * 0.05f).toDouble()).toFloat() * 0.5f + 0.5f) * (h - horizonY)
    drawLine(
        color = Color(0xFF06B6D4),
        start = Offset(0f, laserY),
        end = Offset(w, laserY),
        strokeWidth = 3.dp.toPx(),
        alpha = 0.8f
    )
    
    // Stylized perspective towers neon column
    val neonX = w * 0.15f
    val neonHeight = h * 0.12f
    drawRect(
        color = Color(0xFFEC4899).copy(alpha = 0.2f),
        topLeft = Offset(neonX - 10.dp.toPx(), horizonY - neonHeight),
        size = androidx.compose.ui.geometry.Size(20.dp.toPx(), neonHeight)
    )
}

fun DrawScope.drawBakingCanvas(time: Float) {
    val h = size.height
    val w = size.width
    
    // Sweet dark plum gradient
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFF2E1022), Color(0xFF1F0C1B), Color(0xFF10020D))
        )
    )
    
    // Floating bubbles / treats
    val bCount = 10
    for (i in 0 until bCount) {
        val sizeF = 45.dp.toPx() + (i % 4) * 15.dp.toPx()
        val floatSpeed = 0.4f + (i % 3) * 0.15f
        
        val bx = (((i * 59) % 100) / 100f) * w
        val by = h - ((time * floatSpeed * 65f + i * 140f) % (h + 180.dp.toPx()))
        
        val tint = when (i % 3) {
            0 -> Color(0xFFF43F5E).copy(alpha = 0.22f) // Rose
            1 -> Color(0xFFFBBF24).copy(alpha = 0.18f) // Gold
            else -> Color(0xFFD946EF).copy(alpha = 0.2f) // Magenta
        }
        
        drawCircle(
            color = tint,
            radius = sizeF / 2f,
            center = Offset(bx, by)
        )
        
        drawCircle(
            color = Color(0xFF10020D),
            radius = sizeF / 5f,
            center = Offset(bx, by),
            style = Stroke(width = 1.5.dp.toPx())
        )
    }
}

fun DrawScope.drawWaveCanvas(time: Float) {
    val h = size.height
    val w = size.width
    
    // Deep Ocean gradient
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFF042F2E), Color(0xFF114F4A), Color(0xFF020617))
        )
    )
    
    // Wave sin curves
    val colors = listOf(
        Color(0xFF0D9488).copy(alpha = 0.35f),
        Color(0xFF14B8A6).copy(alpha = 0.45f),
        Color(0xFF0F766E).copy(alpha = 0.6f)
    )
    
    for (wave in 0..2) {
        val p = Path()
        p.moveTo(0f, h)
        val basicHeight = 50.dp.toPx() - wave * 10.dp.toPx()
        val freq = 0.006f + wave * 0.003f
        val velocity = 0.06f + wave * 0.04f
        
        for (x in 0..w.toInt() step 6) {
            val y = (h * 0.62f) + (wave * 34.dp.toPx()) + sin((x * freq + time * velocity).toDouble()).toFloat() * basicHeight
            p.lineTo(x.toFloat(), y)
        }
        p.lineTo(w, h)
        p.close()
        
        drawPath(path = p, color = colors[wave])
    }
}

fun DrawScope.drawCoderCanvas(time: Float) {
    val h = size.height
    val w = size.width
    
    // Green matrix terminals
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFF022C22), Color(0xFF021F1A), Color(0xFF020617))
        )
    )
    
    val columns = 14
    for (i in 0 until columns) {
        val cx = (((i * 71) % 100) / 100f) * w
        val yVelocity = 0.7f + (i % 3) * 0.4f
        val length = 120.dp.toPx() + (i % 4) * 50.dp.toPx()
        val headY = (time * yVelocity * 90f + i * 130f) % (h + length)
        
        drawLine(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color(0xFF10B981).copy(alpha = 0.7f), Color.White),
                startY = headY - length,
                endY = headY
            ),
            start = Offset(cx, headY - length),
            end = Offset(cx, headY),
            strokeWidth = 2.5.dp.toPx()
        )
    }
}

fun formatNumber(num: Int): String {
    fun trimZero(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString() else value.toString()

    return when {
        num >= 1_000_000 -> "${trimZero((num / 100_000) / 10f)}M"
        num >= 1_000 -> "${trimZero((num / 100) / 10f)}K"
        else -> num.toString()
    }
}

@Composable
fun GlobalAssistantScreen(
    context: android.content.Context,
    isServiceEnabled: Boolean,
    onActivateClick: () -> Unit
) {
    val prefs = remember { context.getSharedPreferences("voice_reels_prefs", android.content.Context.MODE_PRIVATE) }
    var globalVoiceEnabled by remember {
        mutableStateOf(prefs.getBoolean("global_voice_control_enabled", false))
    }
    var muteVoiceBeeps by remember {
        mutableStateOf(prefs.getBoolean("mute_voice_beeps", true))
    }
    var showFloatingOverlay by remember {
        mutableStateOf(prefs.getBoolean("show_floating_overlay", false))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "System-Wide Assistant",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 2.dp)
        )
        Text(
            text = "Control any shorts or reels app completely hands-free using simple spoken voice triggers.",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
            lineHeight = 17.sp
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isServiceEnabled) Color(0xFF065F46).copy(alpha = 0.8f) else Color(0xFF1E293B)
            ),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    1.dp,
                    if (isServiceEnabled) Color(0xFF34D399).copy(alpha = 0.3f) else Color.White.copy(alpha = 0.05f),
                    RoundedCornerShape(20.dp)
                )
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isServiceEnabled) Color(0xFF34D399) else Color(0xFFF87171))
                    )
                    Text(
                        text = if (isServiceEnabled) "ACCESSIBILITY: ACTIVE" else "ACCESSIBILITY: INACTIVE",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = if (isServiceEnabled) {
                        "Voice Reels is active. Enable 'System Background Listening' below, open TikTok or YouTube, and use your voice commands!"
                    } else {
                        "Android requires an Accessibility Service permission to perform gestures (swipes) on your behalf over other media apps system-wide."
                    },
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )

                Button(
                    onClick = onActivateClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isServiceEnabled) Color.White.copy(alpha = 0.2f) else Color.White,
                        contentColor = if (isServiceEnabled) Color.White else Color.Black
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    Text(
                        text = if (isServiceEnabled) "Configure Accessibility Settings" else "Enable Voice Assistant",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "System Background Listening",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "Let the voice recognition engine run in the background to capture swipe commands over other apps.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Switch(
                    checked = globalVoiceEnabled,
                    onCheckedChange = { checked ->
                        prefs.edit().putBoolean("global_voice_control_enabled", checked).apply()
                        globalVoiceEnabled = checked
                        VoiceReelsAccessibilityService.isVoiceControlActive = checked
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF38BDF8),
                        checkedTrackColor = Color(0xFF38BDF8).copy(alpha = 0.3f)
                    )
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Silence Voice Beeps",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "Mute annoying system beep and ding sounds that play whenever standard speech recognition activates.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Switch(
                    checked = muteVoiceBeeps,
                    onCheckedChange = { checked ->
                        prefs.edit().putBoolean("mute_voice_beeps", checked).apply()
                        muteVoiceBeeps = checked
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF38BDF8),
                        checkedTrackColor = Color(0xFF38BDF8).copy(alpha = 0.3f)
                    )
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Floating Controls Bubble",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "Show a drag-and-drop floating widget over other apps to configure listening or tap controls hands-free.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Switch(
                    checked = showFloatingOverlay,
                    onCheckedChange = { checked ->
                        if (checked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                            Toast.makeText(context, "Grant 'Display over other apps' permissions first!", Toast.LENGTH_LONG).show()
                            try {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                context.startActivity(intent)
                            }
                        } else {
                            prefs.edit().putBoolean("show_floating_overlay", checked).apply()
                            showFloatingOverlay = checked
                            VoiceReelsAccessibilityService.isOverlayEnabled = checked
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF38BDF8),
                        checkedTrackColor = Color(0xFF38BDF8).copy(alpha = 0.3f)
                    )
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Supported Hands-Free Feeds",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    val apps = listOf(
                        Triple("TikTok", "com.zhiliaoapp.musically", "https://www.tiktok.com"),
                        Triple("YouTube", "com.google.android.youtube", "https://www.youtube.com/shorts"),
                        Triple("Instagram", "com.instagram.android", "https://www.instagram.com/reels"),
                        Triple("Facebook", "com.facebook.katana", "https://www.facebook.com/reels")
                    )
                    apps.forEach { (appName, appPkg, appUrl) ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E293B))
                                .clickable {
                                    val pm = context.packageManager
                                    var intent = pm.getLaunchIntentForPackage(appPkg)
                                    if (intent == null && appName == "TikTok") {
                                        intent = pm.getLaunchIntentForPackage("com.ss.android.ugc.aweme")
                                    }
                                    try {
                                        if (intent != null) {
                                            context.startActivity(intent)
                                        } else {
                                            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(appUrl))
                                            webIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                            context.startActivity(webIntent)
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Could not open $appName", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = appName,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Universal Voice Commands",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                
                val commands = listOf(
                    Pair("🗣️ Next / Down / Skip", "Swipe to the next short or reel video"),
                    Pair("🗣️ Prev / Back / Up", "Swipe up to the previous video"),
                    Pair("🗣️ Pause / Play / Stop", "Tap on the screen center to toggle state"),
                    Pair("🗣️ Like / Love / Heart", "Inject double tap gesture to like post")
                )
                
                commands.forEach { (cmd, desc) ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = cmd,
                            color = Color(0xFF38BDF8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = desc,
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}
