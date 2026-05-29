package com.example

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class VoiceReelsViewModel : ViewModel() {

    private val _reels = MutableStateFlow<List<ReelItem>>(emptyList())
    val reels: StateFlow<List<ReelItem>> = _reels.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _isPlaying = MutableStateFlow(true)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isVoiceEnabled = MutableStateFlow(true)
    val isVoiceEnabled: StateFlow<Boolean> = _isVoiceEnabled.asStateFlow()

    private val _voiceStatus = MutableStateFlow("Listening...")
    val voiceStatus: StateFlow<String> = _voiceStatus.asStateFlow()

    private val _lastHeardSentence = MutableStateFlow<String?>(null)
    val lastHeardSentence: StateFlow<String?> = _lastHeardSentence.asStateFlow()

    private val _showHeartPop = MutableStateFlow(false)
    val showHeartPop: StateFlow<Boolean> = _showHeartPop.asStateFlow()

    private val _hudState = MutableStateFlow<String?>(null) // "PLAY" or "PAUSE"
    val hudState: StateFlow<String?> = _hudState.asStateFlow()

    private val _isSpeechEngineActive = MutableStateFlow(true)
    val isSpeechEngineActive: StateFlow<Boolean> = _isSpeechEngineActive.asStateFlow()

    init {
        loadInitialReels()
    }

    private fun loadInitialReels() {
        _reels.value = listOf(
            ReelItem(
                id = 1,
                creator = "alex_travels",
                avatarColor = 0xFF6366F1, // Indigo
                caption = "Waking up in the Swiss Alps was a dream come true. Look at that crisp mountain view! 🏔️✨ Absolutely pure magic.",
                hashtags = listOf("travel", "nature", "alps", "wanderlust"),
                musicTrack = "Original Sound - Alex Travels",
                likesCount = 1205421,
                commentsCount = 4521,
                bookmarksCount = 89045,
                sharesCount = 12400,
                type = ReelType.SWISS_ALPS
            ),
            ReelItem(
                id = 2,
                creator = "neon_mesh",
                avatarColor = 0xFFEC4899, // Pink
                caption = "Lost in the glowing grid of Neo Tokyo. The electric rain has its own lofi rhythm. 🌆⚡ Raincoat weather forever.",
                hashtags = listOf("cyberpunk", "tokyo", "scifi", "neon"),
                musicTrack = "Synthwave Cruise - Vector Grid",
                likesCount = 854120,
                commentsCount = 3120,
                bookmarksCount = 62450,
                sharesCount = 9812,
                type = ReelType.NEON_CYBER
            ),
            ReelItem(
                id = 3,
                creator = "pastries_by_m",
                avatarColor = 0xFFF43F5E, // Rose
                caption = "Baking fresh double-chocolate French macarons! Sweet, delicate, and totally worth the 4 attempts. 🍓🍩 Who wants one?",
                hashtags = listOf("baking", "pastry", "chocolate", "macarons"),
                musicTrack = "Cozy Cafe Accordion - Paris Cafe",
                likesCount = 741200,
                commentsCount = 8244,
                bookmarksCount = 124500,
                sharesCount = 34500,
                type = ReelType.WARM_BAKING
            ),
            ReelItem(
                id = 4,
                creator = "surf_jake",
                avatarColor = 0xFF14B8A6, // Teal
                caption = "Chasing heavy barrels in Fiji today! Standard paddle out turned into the wave of the season. 🌊🏄‍♂️ Epic swell energy.",
                hashtags = listOf("surfing", "ocean", "extreme", "fiji"),
                musicTrack = "Surf Rock Sunburst - The Barrels",
                likesCount = 925340,
                commentsCount = 2140,
                bookmarksCount = 47800,
                sharesCount = 8114,
                type = ReelType.WAVE_SURFING
            ),
            ReelItem(
                id = 5,
                creator = "coder_lucas",
                avatarColor = 0xFF10B981, // Emerald
                caption = "Coding an offline speech scrolling reel app in a single night. Fluid design system, responsive canvases, clean logic! 💻⚡ Let's compile.",
                hashtags = listOf("kotlin", "compose", "android", "build"),
                musicTrack = "Midnight Lo-fi Programming Beats",
                likesCount = 1530224,
                commentsCount = 12100,
                bookmarksCount = 189502,
                sharesCount = 74312,
                type = ReelType.CODER_BEATS
            )
        )
    }

    fun scrollNext() {
        val nextIdx = (_currentIndex.value + 1) % _reels.value.size
        _currentIndex.value = nextIdx
        // Auto play on transition to keep engagement
        _isPlaying.value = true
        triggerHudNotification("PLAY")
    }

    fun scrollPrev() {
        val prevIdx = if (_currentIndex.value - 1 < 0) _reels.value.size - 1 else _currentIndex.value - 1
        _currentIndex.value = prevIdx
        _isPlaying.value = true
        triggerHudNotification("PLAY")
    }

    fun setPlaying(playing: Boolean) {
        _isPlaying.value = playing
        triggerHudNotification(if (playing) "PLAY" else "PAUSE")
    }

    fun togglePlayPause() {
        setPlaying(!_isPlaying.value)
    }

    fun toggleLikeCurrent() {
        val currentIdx = _currentIndex.value
        _reels.update { list ->
            list.mapIndexed { index, item ->
                if (index == currentIdx) {
                    val newLiked = !item.isLiked
                    item.copy(
                        isLiked = newLiked,
                        likesCount = if (newLiked) item.likesCount + 1 else item.likesCount - 1
                    )
                } else item
            }
        }
        val currentIsLiked = _reels.value[currentIdx].isLiked
        if (currentIsLiked) {
            triggerHeartPopAnimation()
        }
    }

    fun toggleBookmarkCurrent() {
        val currentIdx = _currentIndex.value
        _reels.update { list ->
            list.mapIndexed { index, item ->
                if (index == currentIdx) {
                    val newBookmarked = !item.isBookmarked
                    item.copy(
                        isBookmarked = newBookmarked,
                        bookmarksCount = if (newBookmarked) item.bookmarksCount + 1 else item.bookmarksCount - 1
                    )
                } else item
            }
        }
    }

    fun toggleFollowCurrent() {
        val currentIdx = _currentIndex.value
        _reels.update { list ->
            list.mapIndexed { index, item ->
                if (index == currentIdx) {
                    val newFollowed = !item.isFollowed
                    item.copy(isFollowed = newFollowed)
                } else item
            }
        }
    }

    fun setVoiceStatus(status: String) {
        _voiceStatus.value = status
    }

    fun setSpeechEngineActive(active: Boolean) {
        _isSpeechEngineActive.value = active
    }

    fun toggleVoiceFeedback(sentence: String) {
        _lastHeardSentence.value = sentence
    }

    fun toggleVoiceEnabled() {
        _isVoiceEnabled.value = !_isVoiceEnabled.value
        if (_isVoiceEnabled.value) {
            _voiceStatus.value = "Listening..."
        } else {
            _voiceStatus.value = "Voice disabled"
            _lastHeardSentence.value = null
        }
    }

    fun handleVoiceInput(heardText: String) {
        if (!_isVoiceEnabled.value) return
        val raw = heardText.lowercase().trim()
        
        viewModelScope.launch {
            _lastHeardSentence.value = "\"$heardText\""
            delay(100)
            
            when {
                // NEXT COMMANDS
                raw.contains("next") || raw.contains("down") || raw.contains("skip") || raw.contains("forward") -> {
                    _voiceStatus.value = "Matched: NEXT"
                    delay(300)
                    scrollNext()
                    _voiceStatus.value = "Listening..."
                }
                // PREVIOUS COMMANDS
                raw.contains("prev") || raw.contains("previous") || raw.contains("back") || raw.contains("up") -> {
                    _voiceStatus.value = "Matched: PREV"
                    delay(300)
                    scrollPrev()
                    _voiceStatus.value = "Listening..."
                }
                // PAUSE COMMANDS
                raw.contains("pause") || raw.contains("stop") || raw.contains("wait") || raw.contains("hold") -> {
                    _voiceStatus.value = "Matched: PAUSE"
                    delay(300)
                    setPlaying(false)
                    _voiceStatus.value = "Listening..."
                }
                // PLAY COMMANDS
                raw.contains("play") || raw.contains("start") || raw.contains("resume") || raw.contains("go") -> {
                    _voiceStatus.value = "Matched: PLAY"
                    delay(300)
                    setPlaying(true)
                    _voiceStatus.value = "Listening..."
                }
                // LIKE COMMANDS
                raw.contains("like") || raw.contains("love") || raw.contains("heart") || raw.contains("favorite") -> {
                    _voiceStatus.value = "Matched: LIKE"
                    delay(300)
                    toggleLikeCurrent()
                    _voiceStatus.value = "Listening..."
                }
                // FALLBACK
                else -> {
                    // Soft highlight of unmatched speech
                    _voiceStatus.value = "Listening (No match)"
                    delay(1200)
                    if (_isVoiceEnabled.value) {
                        _voiceStatus.value = "Listening..."
                    }
                }
            }
        }
    }

    private fun triggerHeartPopAnimation() {
        viewModelScope.launch {
            _showHeartPop.value = true
            delay(800) // matches transition timeline
            _showHeartPop.value = false
        }
    }

    private fun triggerHudNotification(state: String) {
        viewModelScope.launch {
            _hudState.value = state
            delay(600)
            if (_hudState.value == state) {
                _hudState.value = null
            }
        }
    }
}
