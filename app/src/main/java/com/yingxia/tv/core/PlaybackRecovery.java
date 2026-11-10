package com.yingxia.tv.core;

/**
 * Retry state for a failed on-demand playback. Every retry asks the source for a fresh address
 * before parsing it again; this class deliberately leaves SOURCE/NETWORK classification to the
 * caller.
 */
public final class PlaybackRecovery {
  private static final long[] DELAYS_MILLIS = {1_000L, 3_000L, 8_000L};
  private static final double RECOVERED_SECONDS = 60.0;

  /** What the player should do after one retryable failure. */
  public static final class Decision {
    public final long delayMillis;
    public final boolean requestAddressThenParse;

    private Decision(long delayMillis) {
      this.delayMillis = delayMillis;
      this.requestAddressThenParse = true;
    }
  }

  private int attempts;
  private Double reopenedAt;

  /** Number of retries used since playback last progressed at least 60 seconds. */
  public int attempts() {
    return attempts;
  }

  /**
   * Returns the next delayed request-then-parse action, or null for a non-retryable failure or
   * after all three attempts. A failure 60 seconds away from the last reopening starts at 1s again.
   */
  public Decision afterFailure(boolean retryable, double positionSeconds) {
    if (!retryable || !Double.isFinite(positionSeconds) || positionSeconds < 0) return null;
    if (reopenedAt != null && Math.abs(positionSeconds - reopenedAt) >= RECOVERED_SECONDS) {
      attempts = 0;
    }
    if (attempts >= DELAYS_MILLIS.length) return null;
    reopenedAt = positionSeconds;
    return new Decision(DELAYS_MILLIS[attempts++]);
  }
}
