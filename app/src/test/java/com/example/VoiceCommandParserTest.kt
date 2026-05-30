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
}
