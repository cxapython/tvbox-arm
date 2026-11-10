package com.yingxia.tv.runtime;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.Test;

/** State machine of {@link ScriptHttpTasks}: register, finish, fail, cancel, idempotency. */
public class ScriptHttpTaskTest {
  /** Queued executor: bodies only run when the test drains them, so states are deterministic. */
  private static final class Queued implements Executor {
    final List<Runnable> queue = new ArrayList<>();

    @Override
    public synchronized void execute(Runnable command) {
      queue.add(command);
    }

    synchronized Runnable take() {
      return queue.isEmpty() ? null : queue.remove(0);
    }

    void runNext() {
      Runnable r = take();
      assertNotNull("expected a scheduled task", r);
      r.run();
    }
  }

  @Test
  public void startRegistersPendingAndPollIsNonBlocking() {
    Queued executor = new Queued();
    ScriptHttpTasks tasks = new ScriptHttpTasks(executor);
    String id = tasks.start(cancel -> "answer");
    assertEquals(ScriptHttpTasks.State.PENDING, tasks.state(id));
    assertNull(tasks.poll(id));
    assertEquals(ScriptHttpTasks.State.PENDING, tasks.state(id));
  }

  @Test
  public void completionIsPolledExactlyOnce() {
    Queued executor = new Queued();
    ScriptHttpTasks tasks = new ScriptHttpTasks(executor);
    String id = tasks.start(cancel -> "answer");
    executor.runNext();
    assertEquals(ScriptHttpTasks.State.DONE, tasks.state(id));
    assertEquals("answer", tasks.poll(id));
    assertNull(tasks.poll(id));
    assertNull(tasks.state(id));
  }

  @Test
  public void failureBecomesFailedStateWithPayload() throws Exception {
    Queued executor = new Queued();
    ScriptHttpTasks tasks = new ScriptHttpTasks(executor);
    String id = tasks.start(cancel -> {
      throw new IOException("boom");
    });
    executor.runNext();
    assertEquals(ScriptHttpTasks.State.FAILED, tasks.state(id));
    JSONObject answer = new JSONObject(tasks.poll(id));
    assertEquals(0, answer.getInt("code"));
    assertEquals("", answer.getString("content"));
    assertTrue(answer.getString("failed").contains("boom"));
    assertNull(tasks.poll(id));
  }

  @Test
  public void cancelPendingStaysCancelledAfterBodyRuns() {
    Queued executor = new Queued();
    ScriptHttpTasks tasks = new ScriptHttpTasks(executor);
    String id = tasks.start(cancel -> "late");
    tasks.cancel(id);
    assertEquals(ScriptHttpTasks.State.CANCELLED, tasks.state(id));
    assertNull(tasks.poll(id));
    executor.runNext();
    assertEquals(ScriptHttpTasks.State.CANCELLED, tasks.state(id));
    assertNull(tasks.poll(id));
  }

  @Test
  public void cancelIsIdempotentForSameHandle() {
    Queued executor = new Queued();
    ScriptHttpTasks tasks = new ScriptHttpTasks(executor);
    String id = tasks.start(cancel -> "answer");
    tasks.cancel(id);
    tasks.cancel(id);
    assertEquals(ScriptHttpTasks.State.CANCELLED, tasks.state(id));
    tasks.cancel("does-not-exist");
  }

  @Test
  public void cancelAfterCompletionDropsUntakenAnswer() {
    Queued executor = new Queued();
    ScriptHttpTasks tasks = new ScriptHttpTasks(executor);
    String id = tasks.start(cancel -> "answer");
    executor.runNext();
    tasks.cancel(id);
    assertEquals(ScriptHttpTasks.State.CANCELLED, tasks.state(id));
    assertNull(tasks.poll(id));
  }

  @Test
  public void attachAfterCancelRunsActionImmediately() {
    Queued executor = new Queued();
    ScriptHttpTasks tasks = new ScriptHttpTasks(executor);
    AtomicBoolean interrupted = new AtomicBoolean();
    String id = tasks.start(cancel -> {
      cancel.attach(() -> interrupted.set(true));
      return "late";
    });
    tasks.cancel(id);
    executor.runNext();
    assertTrue(interrupted.get());
    assertEquals(ScriptHttpTasks.State.CANCELLED, tasks.state(id));
    assertNull(tasks.poll(id));
  }

  @Test
  public void cancelRunningTaskInterruptsAttachedTransport() throws Exception {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      ScriptHttpTasks tasks = new ScriptHttpTasks(pool);
      CountDownLatch attached = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      AtomicBoolean interrupted = new AtomicBoolean();
      String id = tasks.start(cancel -> {
        cancel.attach(() -> interrupted.set(true));
        attached.countDown();
        assertTrue(release.await(5, TimeUnit.SECONDS));
        return "late";
      });
      assertTrue(attached.await(5, TimeUnit.SECONDS));
      tasks.cancel(id);
      assertTrue(interrupted.get());
      assertEquals(ScriptHttpTasks.State.CANCELLED, tasks.state(id));
      release.countDown();
      assertNull(tasks.poll(id));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  public void cancelAllCancelsEveryPendingTask() {
    Queued executor = new Queued();
    ScriptHttpTasks tasks = new ScriptHttpTasks(executor);
    String first = tasks.start(cancel -> "one");
    String second = tasks.start(cancel -> "two");
    tasks.cancelAll();
    assertEquals(ScriptHttpTasks.State.CANCELLED, tasks.state(first));
    assertEquals(ScriptHttpTasks.State.CANCELLED, tasks.state(second));
    assertNull(tasks.poll(first));
    assertNull(tasks.poll(second));
  }
}
