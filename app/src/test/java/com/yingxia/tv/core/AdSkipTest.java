package com.yingxia.tv.core;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.Test;

public class AdSkipTest {
  private static final String ORIGIN = "https://cdn.film.example/v/abc/index.m3u8";
  private static final String FILM = "https://cdn.film.example/v/abc/";
  private static final String AD = "https://ads.other.example/a/";

  private static List<String> run(String base, String prefix, int count) {
    return run(base, prefix, count, 10, false);
  }

  private static List<String> run(String base, String prefix, int count, boolean discontinuity) {
    return run(base, prefix, count, 10, discontinuity);
  }

  private static List<String> run(String base, String prefix, int count, double seconds,
      boolean discontinuity, String... extra) {
    List<String> lines = new ArrayList<>();
    if (discontinuity) lines.add("#EXT-X-DISCONTINUITY");
    lines.addAll(Arrays.asList(extra));
    for (int index = 0; index < count; index++) {
      lines.add("#EXTINF:" + seconds + ",");
      lines.add(base + prefix + String.format(Locale.ROOT, "%04d", index) + ".ts");
    }
    return lines;
  }

  @SafeVarargs
  private static String playlist(List<String>... parts) {
    return playlist(true, parts);
  }

  @SafeVarargs
  private static String playlist(boolean ended, List<String>... parts) {
    List<String> lines = new ArrayList<>(List.of("#EXTM3U", "#EXT-X-VERSION:3",
        "#EXT-X-TARGETDURATION:10", "#EXT-X-MEDIA-SEQUENCE:0"));
    for (List<String> part : parts) lines.addAll(part);
    if (ended) lines.add("#EXT-X-ENDLIST");
    return String.join("\n", lines);
  }

  @Test
  public void adRunFromAnotherHostAtStartGoesAway() {
    AdSkip.Result result = AdSkip.clean(playlist(
        run(AD, "ad", 2), run(FILM, "seg", 60, true)), ORIGIN);
    assertNotNull(result);
    assertEquals(1, result.runs);
    assertEquals(20.0, result.seconds, 0.0);
    assertEquals("ads.other.example", result.host);
    assertFalse(result.text.contains("ads.other.example"));
    assertEquals(60, result.text.split("seg", -1).length - 1);
    assertFalse(result.text.contains("#EXT-X-DISCONTINUITY"));
    assertTrue(result.text.contains("#EXT-X-ENDLIST"));
  }

  @Test
  public void middleAdRunGoesAndFilmKeepsItsJoin() {
    AdSkip.Result result = AdSkip.clean(playlist(run(FILM, "seg", 30),
        run(AD, "ad", 1, 15, true), run(FILM, "seg", 30, true)), ORIGIN);
    assertNotNull(result);
    assertEquals(1, result.runs);
    assertEquals(15.0, result.seconds, 0.0);
    assertEquals(1, result.text.split("#EXT-X-DISCONTINUITY", -1).length - 1);
    assertFalse(result.text.contains("/a/ad"));
  }

  @Test
  public void anotherFolderOnSameHostAndRelativeAddressesCount() {
    assertEquals(1, AdSkip.clean(playlist(run(FILM, "seg", 40),
        run("https://cdn.film.example/promo/", "p", 2, true),
        run(FILM, "seg", 20, true)), ORIGIN).runs);
    assertEquals(1, AdSkip.clean(playlist(run("", "seg", 50),
        run("../promo/", "p", 2, true), run("", "seg", 20, true)), ORIGIN).runs);
  }

  @Test
  public void playlistsOutsideTheNarrowRuleAreLeftAlone() {
    assertNull(AdSkip.clean(playlist(run(FILM, "seg", 40), run(AD, "ad", 2)), ORIGIN));
    assertNull(AdSkip.clean(playlist(false, run(AD, "ad", 2),
        run(FILM, "seg", 40, true)), ORIGIN));
    assertNull(AdSkip.clean("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nlow.m3u8\n"
        + "#EXT-X-ENDLIST\n", ORIGIN));
  }

  @Test
  public void longForeignRunAndShortVideoAreNotAds() {
    assertNull(AdSkip.clean(playlist(run(FILM, "seg", 100), run(AD, "ad", 70, true),
        run(FILM, "seg", 100, true)), ORIGIN));
    assertNull(AdSkip.clean(playlist(run(AD, "ad", 1), run(FILM, "seg", 10, true)), ORIGIN));
  }

  @Test
  public void removingTooMuchLeavesWholePlaylist() {
    assertNotNull(AdSkip.clean(playlist(run(FILM, "seg", 20), run(AD, "ad", 2, true),
        run(FILM, "seg", 20, true), run(AD, "ad", 3, true),
        run(FILM, "seg", 20, true)), ORIGIN));
    assertNull(AdSkip.clean(playlist(run(FILM, "seg", 12), run(AD, "ad", 3, true),
        run(FILM, "seg", 12, true), run(AD, "ad", 3, true)), ORIGIN));
  }

  @Test
  public void keyMapAndByteRangeMakeRemovedRunsConservative() {
    List<String> tags = List.of(
        "#EXT-X-KEY:METHOD=AES-128,URI=\"https://ads.other.example/k\"",
        "#EXT-X-MAP:URI=\"init.mp4\"", "#EXT-X-BYTERANGE:100@0");
    for (String tag : tags) {
      assertNull(tag, AdSkip.clean(playlist(run(FILM, "seg", 50),
          run(AD, "ad", 1, 10, true, tag), run(FILM, "seg", 20, true)), ORIGIN));
    }
  }

  @Test
  public void hashNamedSegmentsOfOneFolderAreOneFamily() {
    List<String> first = hashed(30, false), second = hashed(30, true);
    AdSkip.Result result = AdSkip.clean(playlist(first, run(AD, "ad", 1, true), second), ORIGIN);
    assertNotNull(result);
    assertEquals(1, result.runs);
  }

  private static List<String> hashed(int count, boolean discontinuity) {
    List<String> lines = new ArrayList<>();
    if (discontinuity) lines.add("#EXT-X-DISCONTINUITY");
    for (int index = 0; index < count; index++) {
      lines.add("#EXTINF:10,");
      lines.add(FILM + Integer.toHexString(index * 7919 + 1048576) + ".ts");
    }
    return lines;
  }

  @Test
  public void cleanedPlaylistKeepsHeaderOrderAndRelativeAddresses() {
    AdSkip.Result result = AdSkip.clean(playlist(run("../promo/", "ad", 1),
        run("", "seg", 30, true)), ORIGIN);
    assertNotNull(result);
    String[] lines = result.text.split("\n");
    assertArrayEquals(new String[] {"#EXTM3U", "#EXT-X-VERSION:3",
        "#EXT-X-TARGETDURATION:10", "#EXT-X-MEDIA-SEQUENCE:0"}, Arrays.copyOf(lines, 4));
    List<String> segments = new ArrayList<>();
    for (String line : lines) if (line.endsWith(".ts")) segments.add(line);
    List<String> sorted = new ArrayList<>(segments);
    sorted.sort(null);
    assertEquals(sorted, segments);
    assertEquals(30, segments.size());
    assertEquals("seg0000.ts", segments.get(0));
  }

  @Test
  public void durationShareAndVideoLengthLimitsAreInclusive() {
    assertNotNull("exactly 60 seconds", AdSkip.clean(playlist(run(FILM, "seg", 30),
        run(AD, "ad", 6, true), run(FILM, "seg", 30, true)), ORIGIN));
    assertNull("over 60 seconds", AdSkip.clean(playlist(run(FILM, "seg", 30),
        run(AD, "ad", 7, true), run(FILM, "seg", 30, true)), ORIGIN));
    assertNotNull("exactly ten percent", AdSkip.clean(playlist(run(AD, "ad", 2),
        run(FILM, "seg", 18, true)), ORIGIN));
    assertNotNull("exactly 180 seconds", AdSkip.clean(playlist(run(AD, "ad", 1),
        run(FILM, "seg", 17, true)), ORIGIN));
  }

  @Test
  public void atMostFourRunsMayBeRemoved() {
    AdSkip.Result four = AdSkip.clean(playlist(run(FILM, "seg", 18), run(AD, "ad", 1, true),
        run(FILM, "seg", 6, true), run(AD, "ad", 1, true), run(FILM, "seg", 6, true),
        run(AD, "ad", 1, true), run(FILM, "seg", 6, true), run(AD, "ad", 1, true),
        run(FILM, "seg", 18, true)), ORIGIN);
    assertNotNull(four);
    assertEquals(4, four.runs);
    assertNull(AdSkip.clean(playlist(run(FILM, "seg", 20), run(AD, "ad", 1, true),
        run(FILM, "seg", 20, true), run(AD, "ad", 1, true), run(FILM, "seg", 20, true),
        run(AD, "ad", 1, true), run(FILM, "seg", 20, true), run(AD, "ad", 1, true),
        run(FILM, "seg", 20, true), run(AD, "ad", 1, true), run(FILM, "seg", 20, true)), ORIGIN));
  }
}
