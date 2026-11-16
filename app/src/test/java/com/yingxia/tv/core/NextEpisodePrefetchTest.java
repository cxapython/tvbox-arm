package com.yingxia.tv.core;
import static org.junit.Assert.*;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
public class NextEpisodePrefetchTest {
  @Test public void nextReusesTheOneResolutionAndConsumesItOnce() throws Exception {
    NextEpisodePrefetch<String> cache = new NextEpisodePrefetch<>(); AtomicInteger calls = new AtomicInteger();
    cache.begin("two", 1000, () -> "url" + calls.incrementAndGet(), Runnable::run);
    cache.begin("two", 2000, () -> "unwanted", Runnable::run);
    assertEquals("url1", cache.take("two", 3000).get());
    assertEquals(1, calls.get()); assertNull(cache.take("two", 4000));
  }
  @Test public void expiredWrongQualityAndCancelledRequestsAreNotReused() {
    NextEpisodePrefetch<String> cache = new NextEpisodePrefetch<>();
    cache.begin("two-q0", 1000, () -> "url", r -> {});
    assertNull(cache.take("two-q1", 2000));
    cache.begin("two-q0", 1000, () -> "url", Runnable::run);
    assertNull(cache.take("two-q0", 301000));
    cache.begin("two-q0", 1000, () -> "url", Runnable::run); cache.clear();
    assertNull(cache.take("two-q0", 2000));
  }
  @Test public void inFlightFutureIsSharedNotRequestedTwice() {
    NextEpisodePrefetch<String> cache = new NextEpisodePrefetch<>();
    cache.begin("two", 1000, () -> "url", r -> {});
    Future<String> inFlight = cache.take("two", 2000);
    assertNotNull(inFlight); assertFalse(inFlight.isDone()); inFlight.cancel(true);
  }
}
