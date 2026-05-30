package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.MyApplicationTheme

private val Canvas = Color(0xFF020617)
private val Surface = Color(0xFF0F172A)
private val SurfaceAlt = Color(0xFF1E293B)
private val Accent = Color(0xFF38BDF8)
private val AccentBlue = Color(0xFF3B82F6)
private val Good = Color(0xFF34D399)
private val Bad = Color(0xFFF87171)
private val Muted = Color(0xFF94A3B8)

class MainActivity : ComponentActivity() {

    private val micGranted = mutableStateOf(false)

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        micGranted.value = granted
        if (!granted) {
            Toast.makeText(
                this,
                "Microphone permission is required for hands-free control.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        micGranted.value = hasMicPermission()

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Canvas
                ) { padding ->
                    ControlCenter(
                        modifier = Modifier.padding(padding),
                        micGranted = micGranted.value,
                        onRequestMic = { requestMic.launch(Manifest.permission.RECORD_AUDIO) },
                        onOpenAccessibility = { openAccessibilitySettings() },
                        onRequestOverlay = { requestOverlayPermission() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        micGranted.value = hasMicPermission()
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure {
                Toast.makeText(
                    this,
                    "Open Settings → Accessibility → Voice Reels manually.",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
    }
}

@Composable
private fun ControlCenter(
    modifier: Modifier = Modifier,
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRequestOverlay: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("voice_reels_prefs", Context.MODE_PRIVATE)
    }

    val serviceRunning by VoiceReelsAccessibilityService.isRunning.collectAsState()
    val listening by VoiceReelsAccessibilityService.isListening.collectAsState()
    val status by VoiceReelsAccessibilityService.status.collectAsState()
    val lastCommand by VoiceReelsAccessibilityService.lastCommand.collectAsState()
    val foregroundApp by VoiceReelsAccessibilityService.foregroundApp.collectAsState()

    var voiceControl by remember {
        mutableStateOf(prefs.getBoolean("global_voice_control_enabled", false))
    }
    var silenceBeeps by remember {
        mutableStateOf(prefs.getBoolean("mute_voice_beeps", true))
    }
    var overlay by remember {
        mutableStateOf(prefs.getBoolean("show_floating_overlay", false))
    }
    var duckAudio by remember {
        mutableStateOf(prefs.getBoolean("duck_media_audio", true))
    }
    var cooldownMs by remember {
        mutableStateOf(
            prefs.getInt(
                VoiceReelsAccessibilityService.PREF_COMMAND_COOLDOWN_MS,
                VoiceReelsAccessibilityService.DEFAULT_COMMAND_COOLDOWN_MS.toInt()
            )
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && overlay && !canDrawOverlays(context)) {
                overlay = false
                prefs.edit().putBoolean("show_floating_overlay", false).apply()
                VoiceReelsAccessibilityService.isOverlayEnabled = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            when (key) {
                "global_voice_control_enabled" ->
                    voiceControl = sharedPreferences.getBoolean(key, false)
                "show_floating_overlay" ->
                    overlay = sharedPreferences.getBoolean(key, false)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val ready = micGranted && serviceRunning

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Canvas)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Header(ready = ready, listening = listening && voiceControl)

        LiveStatusCard(
            voiceControl = voiceControl,
            listening = listening,
            statusText = status,
            lastCommand = lastCommand,
            foregroundApp = appLabel(foregroundApp)
        )

        SectionTitle("Setup")

        SetupStep(
            index = 1,
            title = "Microphone access",
            description = "Lets Voice Reels hear your spoken commands.",
            done = micGranted,
            actionLabel = if (micGranted) "Granted" else "Grant access",
            onAction = onRequestMic,
            enabled = !micGranted
        )

        SetupStep(
            index = 2,
            title = "Accessibility service",
            description = "Allows Voice Reels to scroll and tap inside other apps for you.",
            done = serviceRunning,
            actionLabel = if (serviceRunning) "Enabled" else "Open accessibility settings",
            onAction = onOpenAccessibility,
            enabled = true
        )

        SetupStep(
            index = 3,
            title = "Background voice control",
            description = "Start listening so your voice works over TikTok, Reels and Shorts.",
            done = voiceControl && ready,
            actionLabel = null,
            onAction = {},
            enabled = ready,
            trailing = {
                Switch(
                    checked = voiceControl,
                    enabled = ready,
                    onCheckedChange = { checked ->
                        voiceControl = checked
                        prefs.edit().putBoolean("global_voice_control_enabled", checked).apply()
                        VoiceReelsAccessibilityService.isVoiceControlActive = checked
                    },
                    colors = switchColors()
                )
            }
        )

        SectionTitle("Open a feed")
        SupportedApps()

        SectionTitle("Voice commands")
        CommandReference()

        SectionTitle("Listening & performance")
        CooldownSelector(
            cooldownMs = cooldownMs,
            onSelect = { value ->
                cooldownMs = value
                prefs.edit()
                    .putInt(VoiceReelsAccessibilityService.PREF_COMMAND_COOLDOWN_MS, value)
                    .apply()
            }
        )
        OptionToggle(
            title = "Lower video volume while listening",
            description = "Requests audio focus and temporarily lowers media volume (extra dip " +
                "while you speak) so loud reels do not drown out commands. Recommended.",
            checked = duckAudio,
            onCheckedChange = { checked ->
                duckAudio = checked
                prefs.edit().putBoolean("duck_media_audio", checked).apply()
            }
        )
        OptionToggle(
            title = "Silence recognition beeps",
            description = "Mute the system beeps that play when listening starts.",
            checked = silenceBeeps,
            onCheckedChange = { checked ->
                silenceBeeps = checked
                prefs.edit().putBoolean("mute_voice_beeps", checked).apply()
            }
        )
        OptionToggle(
            title = "Floating controls bubble",
            description = "Show a draggable on-top widget with manual buttons.",
            checked = overlay,
            onCheckedChange = { checked ->
                if (checked && !canDrawOverlays(context)) {
                    onRequestOverlay()
                } else {
                    overlay = checked
                    prefs.edit().putBoolean("show_floating_overlay", checked).apply()
                    VoiceReelsAccessibilityService.isOverlayEnabled = checked
                }
            }
        )

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Header(ready: Boolean, listening: Boolean) {
    Column {
        Text("Voice Reels", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Hands-free voice control for TikTok, Instagram Reels, Facebook Reels and YouTube Shorts.",
            color = Muted,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dot = when {
                listening -> Good
                ready -> Accent
                else -> Bad
            }
            Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    listening -> "Listening for commands"
                    ready -> "Ready — turn on background voice control"
                    else -> "Finish the setup steps below"
                },
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun LiveStatusCard(
    voiceControl: Boolean,
    listening: Boolean,
    statusText: String,
    lastCommand: String?,
    foregroundApp: String?
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Assistant status", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (voiceControl) statusText else "Off",
                    color = if (listening) Good else Muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                "Last command: ${lastCommand ?: "—"}",
                color = Color(0xFFE2E8F0),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text("Foreground app: ${foregroundApp ?: "—"}", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SetupStep(
    index: Int,
    title: String,
    description: String,
    done: Boolean,
    actionLabel: String?,
    onAction: () -> Unit,
    enabled: Boolean,
    trailing: (@Composable () -> Unit)? = null
) {
    Card {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (done) Good else Muted,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("$index. $title", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(description, color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
            }
            Spacer(Modifier.width(12.dp))
            when {
                trailing != null -> trailing()
                done -> Text(actionLabel ?: "Done", color = Good, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                actionLabel != null -> Button(
                    onClick = onAction,
                    enabled = enabled,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    )
                ) {
                    Text(actionLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SupportedApps() {
    val context = LocalContext.current
    val apps = listOf(
        AppEntry(
            "TikTok",
            VoiceReelsAccessibilityService.PKG_TIKTOK,
            "https://www.tiktok.com",
            VoiceReelsAccessibilityService.PKG_TIKTOK_ALT
        ),
        AppEntry("YouTube", VoiceReelsAccessibilityService.PKG_YOUTUBE, "https://www.youtube.com/shorts"),
        AppEntry("Instagram", VoiceReelsAccessibilityService.PKG_INSTAGRAM, "https://www.instagram.com/reels"),
        AppEntry("Facebook", VoiceReelsAccessibilityService.PKG_FACEBOOK, "https://www.facebook.com/reels")
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        apps.forEach { app ->
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceAlt)
                    .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                    .clickable { launchApp(context, app) }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(app.name, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun CommandReference() {
    val commands = listOf(
        "“Next”, “Down”, “Skip”" to "Scroll to the next video",
        "“Previous”, “Back”, “Up”" to "Scroll to the previous video",
        "“Play”, “Resume”" to "Resume — only if the video is paused",
        "“Pause”, “Stop”" to "Pause — only if the video is playing",
        "“Like”, “Love”, “Heart”" to "Like the current video"
    )
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            commands.forEach { (cmd, desc) ->
                Column {
                    Text(cmd, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(desc, color = Muted, fontSize = 12.sp)
                }
            }
            Text(
                "Tip: commands fire the instant a matching (or similar-sounding) word is heard.",
                color = Muted,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun CooldownSelector(cooldownMs: Int, onSelect: (Int) -> Unit) {
    val options = listOf(1000 to "1s", 2000 to "2s", 3000 to "3s")
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Repeat protection", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                "How long to wait before the same command can fire again. Prevents repeating an " +
                    "action when a word is heard several times.",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                options.forEach { (value, label) ->
                    val selected = cooldownMs == value
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) AccentBlue else SurfaceAlt)
                            .border(
                                1.dp,
                                if (selected) Accent else Color.White.copy(alpha = 0.06f),
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { onSelect(value) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(description, color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange, colors = switchColors())
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        color = Muted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Surface)
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(18.dp))
    ) { content() }
}

@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = Accent,
    checkedTrackColor = AccentBlue.copy(alpha = 0.5f)
)

private data class AppEntry(
    val name: String,
    val pkg: String,
    val launchUrl: String,
    val altPkg: String? = null
)

private fun launchApp(context: Context, app: AppEntry) {
    val pm = context.packageManager
    val installedPkg = listOfNotNull(app.pkg, app.altPkg)
        .firstOrNull { pkg -> pm.getLaunchIntentForPackage(pkg) != null }
    val deepLinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse(app.launchUrl)).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    runCatching {
        val launched = when {
            installedPkg != null -> {
                val appIntent = Intent(deepLinkIntent).setPackage(installedPkg)
                when {
                    appIntent.resolveActivity(pm) != null -> {
                        context.startActivity(appIntent)
                        true
                    }
                    deepLinkIntent.resolveActivity(pm) != null -> {
                        context.startActivity(deepLinkIntent)
                        true
                    }
                    else -> pm.getLaunchIntentForPackage(installedPkg)?.let { launchIntent ->
                        context.startActivity(launchIntent.apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                        true
                    } ?: false
                }
            }
            deepLinkIntent.resolveActivity(pm) != null -> {
                context.startActivity(deepLinkIntent)
                true
            }
            else -> false
        }
        if (!launched) {
            error("Unable to launch ${app.name}: app not available or cannot handle this content")
        }
    }.onFailure {
        Toast.makeText(context, "Could not open ${app.name}", Toast.LENGTH_SHORT).show()
    }
}

private fun appLabel(pkg: String?): String? = when (pkg) {
    null -> null
    VoiceReelsAccessibilityService.PKG_TIKTOK, VoiceReelsAccessibilityService.PKG_TIKTOK_ALT -> "TikTok"
    VoiceReelsAccessibilityService.PKG_YOUTUBE -> "YouTube"
    VoiceReelsAccessibilityService.PKG_INSTAGRAM -> "Instagram"
    VoiceReelsAccessibilityService.PKG_FACEBOOK -> "Facebook"
    else -> pkg
}

private fun canDrawOverlays(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
