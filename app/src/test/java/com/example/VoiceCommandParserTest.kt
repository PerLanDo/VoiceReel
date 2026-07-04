package com.example

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandParserTest {

  @Test
  fun `next synonyms map to NEXT`() {
    listOf("next", "go to the next one", "down", "skip", "skip this", "forward").forEach {
      assertEquals("'$it'", VoiceCommand.NEXT, VoiceCommandParser.parse(it))
    }
  }

  @Test
  fun `previous synonyms map to PREVIOUS`() {
    listOf("previous", "go back", "prev", "scroll up", "last one", "rewind").forEach {
      assertEquals("'$it'", VoiceCommand.PREVIOUS, VoiceCommandParser.parse(it))
    }
  }

  @Test
  fun `like synonyms map to LIKE`() {
    listOf("like", "i love this", "heart it", "favorite", "favourite").forEach {
      assertEquals("'$it'", VoiceCommand.LIKE, VoiceCommandParser.parse(it))
    }
  }

  @Test
  fun `pause synonyms map to PAUSE`() {
    listOf("pause", "stop", "wait", "hold on", "freeze").forEach {
      assertEquals("'$it'", VoiceCommand.PAUSE, VoiceCommandParser.parse(it))
    }
  }

  @Test
  fun `play synonyms map to PLAY`() {
    listOf("play", "resume", "start", "continue", "unpause").forEach {
      assertEquals("'$it'", VoiceCommand.PLAY, VoiceCommandParser.parse(it))
    }
  }

  @Test
  fun `homophones are recognized`() {
    assertEquals(VoiceCommand.NEXT, VoiceCommandParser.parse("necks"))
    assertEquals(VoiceCommand.NEXT, VoiceCommandParser.parse("text"))
    assertEquals(VoiceCommand.LIKE, VoiceCommandParser.parse("lake"))
    assertEquals(VoiceCommand.LIKE, VoiceCommandParser.parse("bike"))
    assertEquals(VoiceCommand.PREVIOUS, VoiceCommandParser.parse("black"))
    assertEquals(VoiceCommand.PAUSE, VoiceCommandParser.parse("paws"))
  }

  @Test
  fun `fuzzy matching catches near-miss words`() {
    assertEquals(VoiceCommand.NEXT, VoiceCommandParser.parse("skipp"))
    assertEquals(VoiceCommand.LIKE, VoiceCommandParser.parse("lik"))
    assertEquals(VoiceCommand.PAUSE, VoiceCommandParser.parse("pawse"))
  }

  @Test
  fun `parses across multiple candidates`() {
    assertEquals(
      VoiceCommand.NEXT,
      VoiceCommandParser.parse(listOf("nonsense word", "go next now"))
    )
    assertEquals(
      VoiceCommand.LIKE,
      VoiceCommandParser.parse(listOf("blah", "lake"))
    )
  }

  @Test
  fun `unrelated speech maps to NONE`() {
    listOf("hello there", "what time is it", "weather today", "", "   ").forEach {
      assertEquals("'$it'", VoiceCommand.NONE, VoiceCommandParser.parse(it))
    }
    assertEquals(VoiceCommand.NONE, VoiceCommandParser.parse(null))
    assertEquals(VoiceCommand.NONE, VoiceCommandParser.parse(listOf<String?>(null, "")))
  }

  @Test
  fun `parsing is case insensitive`() {
    assertEquals(VoiceCommand.NEXT, VoiceCommandParser.parse("NEXT"))
    assertEquals(VoiceCommand.LIKE, VoiceCommandParser.parse("LiKe"))
  }

  @Test
  fun `words that merely contain a keyword do not falsely trigger`() {
    // Previously substring matching mapped these to commands.
    listOf(
      "background noise",     // contains "back"
      "in the background",    // contains "back"
      "download the file",    // contains "down"
      "that was playful",     // contains "play"
      "check the display",    // contains "play"
      "a stopwatch",          // contains "stop"
      "she started running",  // contains "start"
      "unlike anything else"  // contains "like"
    ).forEach {
      assertEquals("'$it'", VoiceCommand.NONE, VoiceCommandParser.parse(it))
    }
  }

  @Test
  fun `negated commands do not fire`() {
    listOf("don't skip", "dont skip", "do not go next", "never like this", "no previous").forEach {
      assertEquals("'$it'", VoiceCommand.NONE, VoiceCommandParser.parse(it))
    }
  }

  @Test
  fun `apostrophes are handled`() {
    // "don't" must not strand a bare "t"; and a real command after it still works.
    assertEquals(VoiceCommand.NEXT, VoiceCommandParser.parse("I don't like it, next"))
  }

  @Test
  fun `multi-word phrases resolve to intended command`() {
    assertEquals(VoiceCommand.LIKE, VoiceCommandParser.parse("thumbs up"))
    assertEquals(VoiceCommand.LIKE, VoiceCommandParser.parse("give it a thumbs up"))
    assertEquals(VoiceCommand.NEXT, VoiceCommandParser.parse("keep going"))
    assertEquals(VoiceCommand.NEXT, VoiceCommandParser.parse("move on"))
    assertEquals(VoiceCommand.PREVIOUS, VoiceCommandParser.parse("go back"))
  }

  @Test
  fun `plain up and down still map to previous and next`() {
    assertEquals(VoiceCommand.PREVIOUS, VoiceCommandParser.parse("scroll up"))
    assertEquals(VoiceCommand.NEXT, VoiceCommandParser.parse("scroll down"))
  }

  @Test
  fun `additional homophones are recognized`() {
    assertEquals(VoiceCommand.NEXT, VoiceCommandParser.parse("nest"))
    assertEquals(VoiceCommand.LIKE, VoiceCommandParser.parse("light"))
    assertEquals(VoiceCommand.PAUSE, VoiceCommandParser.parse("pose"))
    assertEquals(VoiceCommand.PLAY, VoiceCommandParser.parse("plate"))
  }
}
