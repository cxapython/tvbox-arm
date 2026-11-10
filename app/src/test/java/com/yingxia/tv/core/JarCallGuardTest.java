package com.yingxia.tv.core;

import static org.junit.Assert.*;

import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;

public class JarCallGuardTest {
  @Test
  public void deadlinesMatchPluginOperation() {
    assertEquals(90, JarCallGuard.timeoutSeconds(Json.obj("play", "https://example.com/v")));
    assertEquals(60, JarCallGuard.timeoutSeconds(Json.obj("wd", "query")));
    assertEquals(150, JarCallGuard.timeoutSeconds(Json.obj("ids", "1")));
    assertEquals(150, JarCallGuard.timeoutSeconds(new JSONObject()));
  }

  @Test
  public void concurrentCallIsRejectedWithoutWaiting() throws Exception {
    JarCallGuard guard = new JarCallGuard();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicReference<Throwable> backgroundFailure = new AtomicReference<>();
    Thread first =
        new Thread(
            () -> {
              try {
                guard.call(
                    5,
                    () -> {
                      entered.countDown();
                      assertTrue(release.await(5, TimeUnit.SECONDS));
                      return "done";
                    });
              } catch (Throwable error) {
                backgroundFailure.set(error);
              }
            });
    first.start();
    assertTrue(entered.await(5, TimeUnit.SECONDS));
    long before = System.nanoTime();
    try {
      guard.call(5, () -> "must not run");
      fail("second call should be rejected");
    } catch (JarCallGuard.BusyException expected) {
      assertTrue(JarCallGuard.isBusy(expected));
    }
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 1000);
    release.countDown();
    first.join(5000);
    assertFalse(first.isAlive());
    assertNull(backgroundFailure.get());
    assertEquals("next", guard.call(5, () -> "next"));
  }

  @Test
  public void timeoutReturnsButStuckBodyKeepsRejectingUntilItEnds() throws Exception {
    JarCallGuard guard = new JarCallGuard();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    Thread stuck =
        new Thread(
            () -> {
              try {
                guard.call(
                    1,
                    () -> {
                      entered.countDown();
                      // Simulate plugin native code which ignores Thread.interrupt().
                      boolean interrupted = false;
                      while (true) {
                        try {
                          if (release.await(10, TimeUnit.MILLISECONDS)) break;
                        } catch (InterruptedException ignored) {
                          interrupted = true;
                        }
                      }
                      if (interrupted) Thread.currentThread().interrupt();
                      return "late";
                    });
              } catch (Exception expected) {
              }
            });
    stuck.start();
    assertTrue(entered.await(5, TimeUnit.SECONDS));
    stuck.join(5000);
    assertFalse(stuck.isAlive());
    assertTrue(guard.isBusy());
    try {
      guard.call(1, () -> "must not run");
      fail("timed-out plugin is still occupied");
    } catch (JarCallGuard.BusyException expected) {
    }
    release.countDown();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (guard.isBusy() && System.nanoTime() < deadline) Thread.sleep(10);
    assertFalse(guard.isBusy());
    assertEquals("next", guard.call(1, () -> "next"));
  }

  @Test
  public void slowCallThrowsTimeoutAndFailureIsNotHidden() throws Exception {
    JarCallGuard guard = new JarCallGuard();
    try {
      guard.call(
          1,
          () -> {
            Thread.sleep(5000);
            return "late";
          });
      fail("slow call should time out");
    } catch (SocketTimeoutException expected) {
      assertTrue(expected.getMessage().contains("1 秒"));
    }
    // Cancellation interrupts the worker; admission is released by its finally block.
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (guard.isBusy() && System.nanoTime() < deadline) Thread.sleep(10);
    assertFalse("Interrupted worker did not release admission", guard.isBusy());
    try {
      guard.call(
          5,
          () -> {
            throw new java.io.IOException("provider failed");
          });
      fail("provider failure should propagate");
    } catch (Exception error) {
      assertEquals("provider failed", error.getMessage());
    }
  }
}
