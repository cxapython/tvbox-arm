package com.yingxia.tv.core;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Per-spider admission control and deadlines; no caller waits for an earlier plugin call. */
public final class JarCallGuard {
  public static final class BusyException extends IOException {
    public BusyException() {
      super("插件运行时忙，请稍后重试");
    }
  }

  private static final ExecutorService WORKERS =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "jar-call");
            thread.setDaemon(true);
            return thread;
          });

  private final ExecutorService workers;
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicBoolean started = new AtomicBoolean();

  public JarCallGuard() {
    this(WORKERS);
  }

  /** Injectable for host-side tests. */
  public JarCallGuard(ExecutorService workers) {
    this.workers = workers;
  }

  /** Seconds allowed for the three plugin request families. */
  public static int timeoutSeconds(JSONObject params) {
    if (params.has("play")) return 90;
    if (params.has("wd")) return 60;
    return 150;
  }

  public <T> T call(JSONObject params, Callable<T> body) throws Exception {
    return call(timeoutSeconds(params), body);
  }

  /** Timeout is in seconds; the worker releases admission only when plugin code actually returns. */
  public <T> T call(long timeout, Callable<T> body) throws Exception {
    if (!running.compareAndSet(false, true)) throw new BusyException();
    started.set(false);
    Future<T> future;
    try {
      future =
          workers.submit(
              () -> {
                started.set(true);
                try {
                  return body.call();
                } finally {
                  running.set(false);
                }
              });
    } catch (RuntimeException rejected) {
      running.set(false);
      throw rejected;
    }
    try {
      return future.get(timeout, TimeUnit.SECONDS);
    } catch (TimeoutException expired) {
      future.cancel(true);
      // A queued task cancelled before entering its body has no finally block to release admission.
      if (!started.get()) running.set(false);
      throw new SocketTimeoutException("插件调用超过 " + timeout + " 秒，已停止等待");
    } catch (InterruptedException interrupted) {
      future.cancel(true);
      if (!started.get()) running.set(false);
      Thread.currentThread().interrupt();
      throw interrupted;
    } catch (ExecutionException failed) {
      Throwable cause = failed.getCause();
      if (cause instanceof Exception) throw (Exception) cause;
      if (cause instanceof Error) throw (Error) cause;
      throw failed;
    }
  }

  public boolean isBusy() {
    return running.get();
  }

  public static boolean isBusy(Throwable error) {
    while (error != null) {
      if (error instanceof BusyException) return true;
      error = error.getCause();
    }
    return false;
  }
}
