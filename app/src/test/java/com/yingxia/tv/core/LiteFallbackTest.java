package com.yingxia.tv.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LiteFallbackTest {
  private static final long MINUTE = 60_000L;

  private static final class ManualClock {
    long now;
  }

  @Test
  public void cooldownStillAppliesAt2959() {
    ManualClock clock = new ManualClock();
    LiteFallback fallback = new LiteFallback(() -> clock.now);
    fallback.record("source");
    clock.now = 29 * MINUTE + 59_000L;
    assertFalse(fallback.prefersPort("source"));
  }

  @Test
  public void cooldownExpiresAtExactly3000() {
    ManualClock clock = new ManualClock();
    LiteFallback fallback = new LiteFallback(() -> clock.now);
    fallback.record("source");
    clock.now = 30 * MINUTE;
    assertTrue(fallback.prefersPort("source"));
    assertTrue(fallback.prefersPort("source"));
  }

  @Test
  public void cooldownIsIsolatedPerSource() {
    LiteFallback fallback = new LiteFallback(() -> 0L);
    fallback.record("a");
    assertFalse(fallback.prefersPort("a"));
    assertTrue(fallback.prefersPort("b"));
  }

  @Test
  public void expiredSourceCanRetryAndFallBackAgain() {
    ManualClock clock = new ManualClock();
    LiteFallback fallback = new LiteFallback(() -> clock.now);
    fallback.record("source");
    assertFalse(fallback.prefersPort("source"));
    clock.now = 30 * MINUTE;
    assertTrue(fallback.prefersPort("source"));
    fallback.record("source");
    assertFalse(fallback.prefersPort("source"));
    clock.now += 30 * MINUTE;
    assertTrue(fallback.prefersPort("source"));
  }
}
