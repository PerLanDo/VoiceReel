package com.example

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandParserTest {

  @Test
  fun `next synonyms map to NEXT`() {
    listOf(
      "next", "go to the next one", "down", "skip", "skip this", "forward", "advance", "downward"
    ).forEach {
      assertEquals("'$it'", VoiceCommand.NEXT, VoiceCommandParser.parse(it))
    }
  }

  @Test
  fun `previous synonyms map to PREVIOUS`() {
    listOf(
      "previous", "go back", "prev", "scroll up", "last one", "rewind", "before", "upward"
    ).forEach {
      assertEquals("'$it'", VoiceCommand.PREVIOUS, VoiceCommandParser.parse(it))
    }
  }

  @Test
  fun `like synonyms map to LIKE`() {
    listOf("like", "i love this", "heart it", "favorite", "favourite", "fav this").forEach {
      assertEquals("'$it'", VoiceCommand.LIKE, VoiceCommandParser.parse(it))
    }
  }

  @Test
  fun `pause synonyms map to PAUSE`() {
    listOf("pause", "stop", "wait", "hold on", "freeze", "halt").forEach {
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
  fun `keyword substrings inside other words do not fire`() {
    // "up" inside "stupid"/"supper", "back" inside "background"/"backflip": the old
    // substring matcher fired PREVIOUS on these; the word-based matcher must not.
    listOf(
      "that is stupid",
      "the supper was good",
      "in the background",
      "what a backflip"
    ).forEach {
      assertEquals("'$it'", VoiceCommand.NONE, VoiceCommandParser.parse(it))
    }
  }

  @Test
  fun `negated commands are suppressed`() {
    assertEquals(VoiceCommand.NONE, VoiceCommandParser.parse("i don't like this"))
    assertEquals(VoiceCommand.NONE, VoiceCommandParser.parse("do not skip"))
    assertEquals(VoiceCommand.NONE, VoiceCommandParser.parse("never pause"))
    assertEquals(VoiceCommand.NONE, VoiceCommandParser.parse("no next"))
  }

  @Test
  fun `a real command after a negated one still fires`() {
    // "like" is negated, but "next" that follows is a genuine command.
    assertEquals(VoiceCommand.NEXT, VoiceCommandParser.parse("don't like it, skip"))
  }

  @Test
  fun `earliest spoken command wins`() {
    assertEquals(VoiceCommand.LIKE, VoiceCommandParser.parse("like then next"))
    assertEquals(VoiceCommand.NEXT, VoiceCommandParser.parse("next then like"))
  }

  @Test
  fun `fuzzy first-letter guard rejects common look-alikes`() {
    // These are within edit-distance 1 of a keyword but differ in the first letter,
    // so the first-letter guard must reject them rather than fire a command.
    listOf("atop" /* stop */, "clay" /* play */, "gown" /* down */, "town" /* down */).forEach {
      assertEquals("'$it'", VoiceCommand.NONE, VoiceCommandParser.parse(it))
    }
  }
}
