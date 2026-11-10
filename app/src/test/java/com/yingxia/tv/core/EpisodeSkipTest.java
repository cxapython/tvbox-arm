package com.yingxia.tv.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class EpisodeSkipTest {
  private static JSONObject segment(Object start, Object end, String type) {
    try {
      return new JSONObject().put("start", start).put("end", end).put("type", type);
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  @Test
  public void parsesArrayStringAndReasonableFieldForms() throws Exception {
    JSONArray skips = new JSONArray()
        .put(segment(5, 90, "intro"))
        .put(segment("1700.5", "1800", "outro"));
    List<EpisodeSkip.Segment> parsed = EpisodeSkip.parse(skips);
    assertEquals(2, parsed.size());
    assertEquals(EpisodeSkip.Kind.INTRO, parsed.get(0).kind);
    assertEquals(5, parsed.get(0).start, 0);
    assertEquals(1700.5, parsed.get(1).start, 0);

    assertEquals(2, EpisodeSkip.parse(skips.toString()).size());
    assertEquals(2, EpisodeSkip.parse(new JSONObject().put("skips", skips)).size());
    JSONObject kindAlias = segment(1, 2, "intro");
    kindAlias.remove("type");
    kindAlias.put("kind", "INTRO");
    assertEquals(EpisodeSkip.Kind.INTRO, EpisodeSkip.parse(new JSONArray().put(kindAlias)).get(0).kind);
  }

  @Test
  public void malformedSegmentsAndContainersAreIgnored() {
    JSONArray skips = new JSONArray()
        .put(segment(-1, 2, "intro"))
        .put(segment(2, 2, "intro"))
        .put(segment(3, 2, "intro"))
        .put(segment(1, "NaN", "intro"))
        .put(segment(1, 2, "other"))
        .put(segment(1, 2, null))
        .put("not an object");
    assertTrue(EpisodeSkip.parse(skips).isEmpty());
    assertTrue(EpisodeSkip.parse("not json").isEmpty());
    assertTrue(EpisodeSkip.parse(null).isEmpty());
  }

  @Test
  public void introTriggersOnlyOnceEvenAfterRewinding() {
    EpisodeSkip.Pass pass = new EpisodeSkip.Pass(EpisodeSkip.parse(
        new JSONArray().put(segment(5, 90, "intro"))));

    assertEquals(90, pass.target(10, 1800, true), 0);
    assertNull(pass.target(11, 1800, true));
    assertNull(pass.target(6, 1800, true));

    EpisodeSkip.Pass nextPlayback = new EpisodeSkip.Pass(EpisodeSkip.parse(
        new JSONArray().put(segment(5, 90, "intro"))));
    assertEquals(90, nextPlayback.target(6, 1800, true), 0);
  }

  @Test
  public void outroReturnsNearDurationForNextAndDoesNotLoop() {
    EpisodeSkip.Pass pass = new EpisodeSkip.Pass(EpisodeSkip.parse(
        new JSONArray().put(segment(1700, 1800, "outro"))));

    assertEquals(1799.95, pass.target(1750, 1800, true), 0.000001);
    assertNull(pass.target(1751, 1800, true));
  }

  @Test
  public void pausedInvalidIntervalsLiveAndUnknownDurationsAreSafe() {
    EpisodeSkip.Pass pass = new EpisodeSkip.Pass(EpisodeSkip.parse(
        new JSONArray().put(segment(5, 90, "intro"))));
    assertNull(pass.target(10, 1800, false));
    assertEquals(90, pass.target(10, 1800, true), 0);

    EpisodeSkip.Pass invalid = new EpisodeSkip.Pass(EpisodeSkip.parse(
        new JSONArray().put(segment(5, 90, "intro"))));
    assertNull(invalid.target(10, 80, true));
    assertNull(invalid.target(10, 0, true));
    assertNull(invalid.target(10, -1, true));
    assertNull(invalid.target(10, Double.NaN, true));
    assertNull(invalid.target(10, Double.POSITIVE_INFINITY, true));
    assertNull(invalid.target(Double.NaN, 1800, true));
    assertNull(invalid.target(-1, 1800, true));
    assertNull(invalid.target(1801, 1800, true));
  }
}
