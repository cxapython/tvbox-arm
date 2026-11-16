package com.yingxia.tv.core;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/** One in-flight resolution, no media buffering; expires conservatively for signed stream URLs. */
public final class NextEpisodePrefetch<T> {
  private static final long MAX_AGE = 5 * 60_000L;
  private String key;
  private long startedAt;
  private FutureTask<T> task;

  public synchronized void begin(String wanted, long now, Callable<T> resolve, Executor executor) {
    if (task != null && wanted.equals(key) && now >= startedAt && now - startedAt < MAX_AGE) return;
    clear();
    key = wanted; startedAt = now; task = new FutureTask<>(resolve);
    executor.execute(task);
  }
  /** Taking the pending future lets a real Next reuse and wait for the same request, off the UI. */
  public synchronized Future<T> take(String wanted, long now) {
    if (task == null || !wanted.equals(key) || now < startedAt || now - startedAt >= MAX_AGE) {
      clear(); return null;
    }
    Future<T> result = task;
    task = null; key = null;
    return result;
  }
  public synchronized void clear() {
    if (task != null) task.cancel(true);
    task = null; key = null;
  }
}
