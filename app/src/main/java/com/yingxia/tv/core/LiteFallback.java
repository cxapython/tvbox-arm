package com.yingxia.tv.core;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Remembers sources whose lite port recently fell back, with a 30-minute cooldown. */
public final class LiteFallback {
  private static final long COOLDOWN_MILLIS = 30 * 60 * 1000L;

  private final LongSupplier clock;
  private final Map<String, Long> fellBack = new HashMap<>();

  public LiteFallback() {
    this(System::currentTimeMillis);
  }

  /** Millisecond clock, injectable for tests. */
  LiteFallback(LongSupplier clock) {
    this.clock = clock;
  }

  public synchronized boolean prefersPort(String key) {
    Long since = fellBack.get(key);
    if (since == null) return true;
    if (clock.getAsLong() - since < COOLDOWN_MILLIS) return false;
    fellBack.remove(key);
    return true;
  }

  public synchronized void record(String key) {
    fellBack.put(key, clock.getAsLong());
  }
}
