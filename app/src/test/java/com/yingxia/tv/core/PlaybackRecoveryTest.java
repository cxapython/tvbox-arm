package com.yingxia.tv.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlaybackRecoveryTest {
  @Test
  public void retriesAt1s3sAnd8sAndAlwaysRequestsFreshAddressBeforeParsing() {
    PlaybackRecovery recovery = new PlaybackRecovery();

    PlaybackRecovery.Decision first = recovery.afterFailure(true, 10);
    PlaybackRecovery.Decision second = recovery.afterFailure(true, 10);
    PlaybackRecovery.Decision third = recovery.afterFailure(true, 10);

    assertEquals(1_000L, first.delayMillis);
    assertEquals(3_000L, second.delayMillis);
    assertEquals(8_000L, third.delayMillis);
    assertTrue(first.requestAddressThenParse);
    assertTrue(second.requestAddressThenParse);
    assertTrue(third.requestAddressThenParse);
    assertEquals(3, recovery.attempts());
    assertNull(recovery.afterFailure(true, 10));
  }

  @Test
  public void sixtySecondsOfProgressInEitherDirectionResetsAttempts() {
    PlaybackRecovery recovery = new PlaybackRecovery();
    assertEquals(1_000L, recovery.afterFailure(true, 10).delayMillis);
    assertEquals(3_000L, recovery.afterFailure(true, 69.999).delayMillis);
    assertEquals(8_000L, recovery.afterFailure(true, 70).delayMillis);

    assertEquals(1_000L, recovery.afterFailure(true, 130).delayMillis);
    assertEquals(3_000L, recovery.afterFailure(true, 131).delayMillis);
    assertEquals(1_000L, recovery.afterFailure(true, 71).delayMillis);
    assertEquals(1, recovery.attempts());
  }

  @Test
  public void exhaustedSequenceCanStartAgainAfterRecovery() {
    PlaybackRecovery recovery = new PlaybackRecovery();
    recovery.afterFailure(true, 100);
    recovery.afterFailure(true, 100);
    recovery.afterFailure(true, 100);
    assertNull(recovery.afterFailure(true, 100));

    assertEquals(1_000L, recovery.afterFailure(true, 160).delayMillis);
    assertEquals(1, recovery.attempts());
  }

  @Test
  public void nonRetryableAndInvalidPositionsDoNotConsumeAttempts() {
    PlaybackRecovery recovery = new PlaybackRecovery();
    assertNull(recovery.afterFailure(false, 10));
    assertNull(recovery.afterFailure(true, -1));
    assertNull(recovery.afterFailure(true, Double.NaN));
    assertNull(recovery.afterFailure(true, Double.POSITIVE_INFINITY));
    assertEquals(0, recovery.attempts());
  }
}
