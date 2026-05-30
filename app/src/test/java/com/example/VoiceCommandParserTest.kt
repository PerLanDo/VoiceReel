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
    listOf("previous", "go back", "prev", "scroll up", "last one").forEach {
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
  fun `play pause synonyms map to PLAY_PAUSE`() {
    listOf("pause", "play", "stop", "resume", "wait", "hold on").forEach {
      assertEquals("'$it'", VoiceCommand.PLAY_PAUSE, VoiceCommandParser.parse(it))
    }
  }

  @Test
  fun `unrelated speech maps to NONE`() {
    listOf("hello there", "what time is it", "", "   ").forEach {
      assertEquals("'$it'", VoiceCommand.NONE, VoiceCommandParser.parse(it))
    }
    assertEquals(VoiceCommand.NONE, VoiceCommandParser.parse(null))
  }

  @Test
  fun `parsing is case insensitive`() {
    assertEquals(VoiceCommand.NEXT, VoiceCommandParser.parse("NEXT"))
    assertEquals(VoiceCommand.LIKE, VoiceCommandParser.parse("LiKe"))
  }
}
