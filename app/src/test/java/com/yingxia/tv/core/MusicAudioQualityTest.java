package com.yingxia.tv.core;

import static org.junit.Assert.*;
import org.junit.Test;

public class MusicAudioQualityTest {
  @Test public void originalQualityHasNoInventedTargetOrDowngrade() {
    assertEquals("实际 MP3 · 192 kbps · 44.1 kHz", MusicAudioQuality.describe("original", "audio/mpeg", 192000, 44100));
  }
  @Test public void requestedLosslessDoesNotMakeAnMp3Lossless() {
    String label = MusicAudioQuality.describe("flac", "audio/mpeg", 320000, 44100);
    assertTrue(label.contains("MP3 · 320 kbps"));
    assertTrue(label.contains("已降级"));
    assertFalse(label.contains("FLAC"));
  }
  @Test public void flacIsReportedFromCodecEvenWhenAHighTierWasRequested() {
    assertEquals("实际 FLAC · 44.1 kHz", MusicAudioQuality.describe("320", "audio/flac", 927000, 44100));
  }
  @Test public void unknownBitrateIsNeverSubstitutedWithTheRequestedTier() {
    assertEquals("实际 MP3 · 码率待确认 · 44.1 kHz", MusicAudioQuality.describe("320", "audio/mpeg", -1, 44100));
    assertTrue(MusicAudioQuality.describe("320", "audio/mpeg", 128000, 44100).contains("已降级"));
    assertEquals("实际音质待确认", MusicAudioQuality.describe("flac", null, -1, -1));
  }
}
