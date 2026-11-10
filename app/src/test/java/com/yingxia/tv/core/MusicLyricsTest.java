package com.yingxia.tv.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class MusicLyricsTest {
  @Test
  public void parsesMultipleTimestampsFractionsAndStableOrder() {
    MusicLyrics lyrics =
        MusicLyrics.parse("[00:02.50][00:01.250]first\n[00:03]second\n[00:04][00:04]repeat");
    assertNotNull(lyrics);
    assertTrue(lyrics.synced);
    assertEquals(5, lyrics.lines.size());
    assertEquals(1250, lyrics.lines.get(0).timeMillis);
    assertEquals("first", lyrics.lines.get(0).text);
    assertEquals(2500, lyrics.lines.get(1).timeMillis);
    assertEquals(0, lyrics.indexAt(1499));
    assertEquals(1, lyrics.indexAt(2500));
    assertEquals(4, lyrics.indexAt(9000));
    assertEquals(-1, lyrics.indexAt(100));
  }

  @Test
  public void positiveAndNegativeOffsetsAreAppliedAndClamped() {
    MusicLyrics shifted = MusicLyrics.parse("[offset:500]\n[00:01.00]later\n[00:00.25]zero");
    assertEquals(0, shifted.lines.get(0).timeMillis);
    assertEquals(500, shifted.lines.get(1).timeMillis);
    MusicLyrics earlier = MusicLyrics.parse("[offset:-200]\n[00:01.00]later");
    assertEquals(1200, earlier.lines.get(0).timeMillis);
  }

  @Test
  public void translationMatchesNearestTimestampWithin350Milliseconds() {
    MusicLyrics lyrics =
        MusicLyrics.parse(
            "[offset:100]\n[00:01.00]hello\n[00:03.00]world",
            "[offset:100]\n[00:01.20]你好\n[00:03.50]太远\n[00:03.00]世界");
    assertEquals("你好", lyrics.lines.get(0).translation);
    assertEquals("世界", lyrics.lines.get(1).translation);
  }

  @Test
  public void metadataAndUnsyncedTextAreHandled() {
    MusicLyrics plain = MusicLyrics.parse("[ti:title]\nfirst line\n\nsecond line");
    assertNotNull(plain);
    assertFalse(plain.synced);
    assertEquals(2, plain.lines.size());
    assertEquals(-1, plain.indexAt(1000));
    assertNull(MusicLyrics.parse("[ar:artist]\n[00:01]"));
  }
}
