package com.yingxia.tv.core;

import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

/**
 * How each source has been answering lately (a port of TVCore's SourceHealth). Two failures in a
 * row pause a source's searches for 30 minutes; each further failure doubles the pause, up to 6
 * hours. A success clears the record. The clock is injectable so tests need not wait.
 */
public final class SourceHealth {
  /** Milliseconds, injectable for tests. */
  public interface Clock {
    long now();
  }

  /** Two failures in a row start a cooldown of 30 minutes. */
  static final long FIRST_COOLDOWN = 30 * 60 * 1000L;
  /** Each further failure doubles the cooldown, up to 6 hours. */
  static final long LONGEST_COOLDOWN = 6 * 3600 * 1000L;

  private static final class Record {
    int failures;
    long cooldownUntil; // epoch millis; 0 means not in cooldown
  }

  private static SourceHealth shared;

  /** The process-wide tracker, on the system clock. */
  public static synchronized SourceHealth shared() {
    if (shared == null) shared = new SourceHealth(System::currentTimeMillis);
    return shared;
  }

  private final Clock clock;
  private final Map<String, Record> records = new HashMap<>();

  public SourceHealth(Clock clock) {
    this.clock = clock;
  }

  /** A source is identified by its subscription origin and original key. */
  public static String key(String origin, String sourceKey) {
    return origin + "|" + sourceKey;
  }

  /** A merged site's stable identity uses the original key, not a collision-renamed display key. */
  public static String key(JSONObject site) {
    return key(site.optString("origin"), site.optString("sourceKey", site.optString("key")));
  }

  /** Whether search leaves this source out for now. */
  public synchronized boolean shouldSkip(String key) {
    Record record = records.get(key);
    return record != null && record.cooldownUntil > clock.now();
  }

  /** Records one failed answer from a source. */
  public synchronized void recordFailure(String key) {
    Record record = records.computeIfAbsent(key, k -> new Record());
    record.failures++;
    if (record.failures >= 2) {
      int doublings = record.failures - 2;
      long delay =
          doublings > 8 ? LONGEST_COOLDOWN : Math.min(LONGEST_COOLDOWN, FIRST_COOLDOWN << doublings);
      record.cooldownUntil = clock.now() + delay;
    }
  }

  /** Records one good answer from a source: the failure streak and cooldown end. */
  public synchronized void recordSuccess(String key) {
    Record record = records.computeIfAbsent(key, k -> new Record());
    record.failures = 0;
    record.cooldownUntil = 0;
  }
}
