package com.yingxia.tv.core;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class SearchRankingTest {
  private static final long MINUTE = 60_000L;
  private static final long HOUR = 3_600_000L;

  private static final class ManualClock implements SourceHealth.Clock {
    long now;

    @Override
    public long now() {
      return now;
    }
  }

  @Test
  public void oneFailureDoesNotPauseASource() {
    ManualClock clock = new ManualClock();
    SourceHealth health = new SourceHealth(clock);
    health.recordFailure("a|s1");
    assertFalse(health.shouldSkip("a|s1"));
  }

  @Test
  public void twoFailuresPauseForThirtyMinutes() {
    ManualClock clock = new ManualClock();
    SourceHealth health = new SourceHealth(clock);
    health.recordFailure("a|s1");
    health.recordFailure("a|s1");
    assertTrue(health.shouldSkip("a|s1"));
    clock.now += 30 * MINUTE;
    assertFalse(health.shouldSkip("a|s1"));
    clock.now -= 1;
    assertTrue(health.shouldSkip("a|s1"));
  }

  @Test
  public void eachFurtherFailureDoublesTheCooldownUpToSixHours() {
    ManualClock clock = new ManualClock();
    SourceHealth health = new SourceHealth(clock);
    String key = "a|s1";
    health.recordFailure(key); // streak of one: not paused yet
    long[] expected = {30 * MINUTE, HOUR, 2 * HOUR, 4 * HOUR, 6 * HOUR, 6 * HOUR};
    for (long cooldown : expected) {
      health.recordFailure(key); // streak grows; cooldown restarts at the new length
      assertTrue(health.shouldSkip(key));
      clock.now += cooldown - 1;
      assertTrue("cooldown of " + cooldown + "ms should still hold", health.shouldSkip(key));
      clock.now += 1;
      assertFalse("cooldown of " + cooldown + "ms should be over", health.shouldSkip(key));
    }
  }

  @Test
  public void aSuccessClearsTheStreakAndTheCooldown() {
    ManualClock clock = new ManualClock();
    SourceHealth health = new SourceHealth(clock);
    String key = "a|s1";
    health.recordFailure(key);
    health.recordFailure(key);
    assertTrue(health.shouldSkip(key));
    health.recordSuccess(key);
    assertFalse(health.shouldSkip(key));
    // After a success the source needs two fresh failures before it is paused again.
    health.recordFailure(key);
    assertFalse(health.shouldSkip(key));
  }

  @Test
  public void mergedSitesKeepHealthUnderTheirOriginalSourceKey() {
    org.json.JSONObject site =
        Json.obj("origin", "https://example.com/config", "key", "renamed@abc123", "sourceKey", "original");
    assertEquals("https://example.com/config|original", SourceHealth.key(site));
    assertEquals(
        "https://example.com/config|plain",
        SourceHealth.key(Json.obj("origin", "https://example.com/config", "key", "plain")));
  }

  @Test
  public void normalizeMakesCaseWidthAndScriptIrrelevant() {
    assertEquals(
        TitleMatch.normalize("Mission: Impossible"),
        TitleMatch.normalize("MISSION IMPOSSIBLE"));
    assertEquals(TitleMatch.normalize("機動搜查隊"), TitleMatch.normalize("机动搜查队"));
    assertEquals("abc剧", TitleMatch.normalize("ＡＢＣ　剧"));
  }

  @Test
  public void aTitleThatHoldsTheKeywordMatches() {
    assertTrue(TitleMatch.matches("机动搜查队404", "机动搜查队"));
    assertTrue(TitleMatch.matches("Mission: Impossible - Fallout", "mission impossible"));
    assertTrue(TitleMatch.matches("ＡＢＣ　剧", "abc剧"));
  }

  @Test
  public void traditionalAndSimplifiedAreTheSame() {
    assertTrue(TitleMatch.matches("機動搜查隊404", "机动搜查队"));
    assertTrue(TitleMatch.matches("机动搜查队", "機動搜查隊"));
  }

  @Test
  public void aSeasonOnTheKeywordIsOptional() {
    assertTrue(TitleMatch.matches("庆余年", "庆余年第二季"));
    assertTrue(TitleMatch.matches("Severance", "Severance S2"));
    assertFalse(TitleMatch.matches("完全不同", "庆余年第二季"));
  }

  @Test
  public void longKeywordsMayHaveLettersBetween() {
    assertTrue(TitleMatch.matches("机动 搜查 特别 队", "机动搜查队"));
    // Two characters must be next to each other: the loose rule is for three or more.
    assertFalse(TitleMatch.matches("仁者医心", "仁医"));
    assertTrue(TitleMatch.matches("仁医", "仁医"));
  }

  @Test
  public void unrelatedTitlesDoNotMatch() {
    assertFalse(TitleMatch.matches("后会友期", "后会无期"));
    assertFalse(TitleMatch.matches("Spider-Man", "batman"));
  }

  @Test
  public void anEmptyKeywordMatchesEverything() {
    assertTrue(TitleMatch.matches("anything", "  "));
    List<String> items = Arrays.asList("b", "a");
    assertEquals(items, TitleMatch.ordered(items, "", t -> t));
  }

  @Test
  public void matchesComeFirstInTheirOwnOrder() {
    List<String> titles = Arrays.asList("别的剧", "仁医 第二季", "无关", "仁医");
    assertEquals(
        Arrays.asList("仁医 第二季", "仁医", "别的剧", "无关"),
        TitleMatch.ordered(titles, "仁医", t -> t));
  }
}
