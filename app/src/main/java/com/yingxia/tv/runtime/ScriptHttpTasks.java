package com.yingxia.tv.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/**
 * Requests started with {@code httpStart}, until their answers are taken. The network body runs on
 * a background executor; {@code httpPoll} only takes a finished answer, and {@code httpCancel} sets
 * the cancel flag and invokes any best-effort cancellation the body attached (for example an okhttp
 * {@code Call.cancel()}). Mirrors the Swift PendingRequests/ScriptHTTP.start semantics: a finished
 * answer may be polled exactly once, and cancelling a finished-but-untaken request drops its answer.
 */
public final class ScriptHttpTasks {
  public enum State {
    PENDING,
    DONE,
    FAILED,
    CANCELLED
  }

  /** The network work. Runs off the script thread; returning or throwing finishes the task. */
  public interface Body {
    String run(CancelHandle cancel) throws Exception;
  }

  /** Given to the body so it can register best-effort cancellation of the underlying transport. */
  public static final class CancelHandle {
    private final Entry entry;

    private CancelHandle(Entry entry) {
      this.entry = entry;
    }

    /** Attaches a cancel action. Runs it immediately when the task was already cancelled. */
    public void attach(Runnable action) {
      boolean runNow = false;
      synchronized (entry) {
        if (entry.state == State.PENDING) entry.cancelAction = action;
        else runNow = entry.state == State.CANCELLED;
      }
      if (runNow) action.run();
    }

    public boolean isCancelled() {
      synchronized (entry) {
        return entry.state == State.CANCELLED;
      }
    }
  }

  private static final class Entry {
    State state = State.PENDING;
    String answer;
    Runnable cancelAction;
  }

  private static final class SharedExecutor implements Executor {
    private final java.util.concurrent.ExecutorService delegate =
        Executors.newCachedThreadPool(
            runnable -> {
              Thread thread = new Thread(runnable, "script-http");
              thread.setDaemon(true);
              return thread;
            });

    @Override
    public void execute(Runnable command) {
      delegate.execute(command);
    }
  }

  private static final Executor SHARED = new SharedExecutor();

  public static Executor defaultExecutor() {
    return SHARED;
  }

  private final Executor executor;
  private final Map<String, Entry> tasks = new ConcurrentHashMap<>();
  private final AtomicInteger next = new AtomicInteger(1);

  public ScriptHttpTasks(Executor executor) {
    this.executor = executor;
  }

  /** Registers the task, schedules the body, and returns the handle without waiting. */
  public String start(Body body) {
    String id = String.valueOf(next.getAndIncrement());
    Entry entry = new Entry();
    tasks.put(id, entry);
    executor.execute(() -> run(entry, body));
    return id;
  }

  private void run(Entry entry, Body body) {
    String answer;
    boolean failed;
    try {
      answer = body.run(new CancelHandle(entry));
      failed = false;
    } catch (Exception e) {
      answer = failure(e);
      failed = true;
    }
    synchronized (entry) {
      if (entry.state != State.PENDING) return; // cancelled meanwhile: the answer is dropped
      entry.answer = answer;
      entry.state = failed ? State.FAILED : State.DONE;
      entry.cancelAction = null;
    }
  }

  /** Non-blocking take: the finished answer, or null while pending, cancelled, or unknown. */
  public String poll(String id) {
    Entry entry = tasks.get(id);
    if (entry == null) return null;
    synchronized (entry) {
      if (entry.state == State.PENDING || entry.state == State.CANCELLED) return null;
      tasks.remove(id);
      return entry.answer;
    }
  }

  /** Cancels the task: marks it, drops any untaken answer, and interrupts the transport. Idempotent. */
  public void cancel(String id) {
    Entry entry = tasks.get(id);
    if (entry == null) return;
    Runnable action;
    synchronized (entry) {
      if (entry.state == State.CANCELLED) return;
      entry.state = State.CANCELLED;
      entry.answer = null;
      action = entry.cancelAction;
      entry.cancelAction = null;
    }
    if (action != null) action.run();
  }

  public void cancelAll() {
    for (String id : tasks.keySet()) cancel(id);
  }

  /** Current state for inspection; null when the handle is unknown or already taken. */
  public State state(String id) {
    Entry entry = tasks.get(id);
    if (entry == null) return null;
    synchronized (entry) {
      return entry.state;
    }
  }

  private static String failure(Exception e) {
    String message = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
    return "{\"code\":0,\"content\":\"\",\"failed\":"
        + JSONObject.quote("Network request failed: " + message)
        + "}";
  }
}
