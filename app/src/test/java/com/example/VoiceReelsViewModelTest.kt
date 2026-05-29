package com.example

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceReelsViewModelTest {

  private val testDispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `initial state loads reels and sensible defaults`() {
    val vm = VoiceReelsViewModel()
    assertEquals(5, vm.reels.value.size)
    assertEquals(0, vm.currentIndex.value)
    assertTrue(vm.isPlaying.value)
    assertTrue(vm.isVoiceEnabled.value)
  }

  @Test
  fun `scrollNext advances index and wraps around`() {
    val vm = VoiceReelsViewModel()
    val size = vm.reels.value.size

    vm.scrollNext()
    assertEquals(1, vm.currentIndex.value)

    // Calling scrollNext for the remaining items wraps back to the first reel.
    repeat(size - 1) { vm.scrollNext() }
    assertEquals(0, vm.currentIndex.value)
  }

  @Test
  fun `scrollPrev wraps to last item from first`() {
    val vm = VoiceReelsViewModel()
    val size = vm.reels.value.size

    vm.scrollPrev()
    assertEquals(size - 1, vm.currentIndex.value)
  }

  @Test
  fun `togglePlayPause flips playing state`() {
    val vm = VoiceReelsViewModel()
    val initial = vm.isPlaying.value

    vm.togglePlayPause()
    assertEquals(!initial, vm.isPlaying.value)
  }

  @Test
  fun `toggleLikeCurrent updates like flag and count`() {
    val vm = VoiceReelsViewModel()
    val before = vm.reels.value[0]
    assertFalse(before.isLiked)

    vm.toggleLikeCurrent()
    val liked = vm.reels.value[0]
    assertTrue(liked.isLiked)
    assertEquals(before.likesCount + 1, liked.likesCount)

    vm.toggleLikeCurrent()
    val unliked = vm.reels.value[0]
    assertFalse(unliked.isLiked)
    assertEquals(before.likesCount, unliked.likesCount)
  }

  @Test
  fun `toggleBookmarkCurrent updates bookmark flag and count`() {
    val vm = VoiceReelsViewModel()
    val before = vm.reels.value[0]

    vm.toggleBookmarkCurrent()
    val bookmarked = vm.reels.value[0]
    assertTrue(bookmarked.isBookmarked)
    assertEquals(before.bookmarksCount + 1, bookmarked.bookmarksCount)
  }

  @Test
  fun `voice command next scrolls to following reel`() = runTest(testDispatcher) {
    val vm = VoiceReelsViewModel()
    vm.handleVoiceInput("next please")
    advanceUntilIdle()
    assertEquals(1, vm.currentIndex.value)
  }

  @Test
  fun `voice command like toggles like on current reel`() = runTest(testDispatcher) {
    val vm = VoiceReelsViewModel()
    vm.handleVoiceInput("I love this")
    advanceUntilIdle()
    assertTrue(vm.reels.value[0].isLiked)
  }

  @Test
  fun `voice command pause stops playback`() = runTest(testDispatcher) {
    val vm = VoiceReelsViewModel()
    vm.handleVoiceInput("stop")
    advanceUntilIdle()
    assertFalse(vm.isPlaying.value)
  }

  @Test
  fun `disabled voice ignores commands`() = runTest(testDispatcher) {
    val vm = VoiceReelsViewModel()
    vm.toggleVoiceEnabled()
    assertFalse(vm.isVoiceEnabled.value)

    vm.handleVoiceInput("next")
    advanceUntilIdle()
    assertEquals(0, vm.currentIndex.value)
  }

  @Test
  fun `formatNumber renders compact thousands and millions`() {
    assertEquals("999", formatNumber(999))
    assertEquals("4.5K", formatNumber(4521))
    assertEquals("1.2M", formatNumber(1205421))
    assertEquals("1M", formatNumber(1000000))
    assertEquals("1K", formatNumber(1000))
  }
}
