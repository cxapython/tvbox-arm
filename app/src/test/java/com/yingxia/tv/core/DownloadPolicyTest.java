package com.yingxia.tv.core;

import static org.junit.Assert.*;

import org.junit.Test;

public final class DownloadPolicyTest {
  @Test
  public void simultaneousMatchesOriginalDefaultAndBounds() {
    assertEquals(2, DownloadPolicy.simultaneous(0));
    assertEquals(2, DownloadPolicy.simultaneous(-3));
    assertEquals(1, DownloadPolicy.simultaneous(1));
    assertEquals(2, DownloadPolicy.simultaneous(2));
    assertEquals(4, DownloadPolicy.simultaneous(4));
    assertEquals(4, DownloadPolicy.simultaneous(99));
    assertEquals(1, DownloadPolicy.clampSimultaneous(0));
    assertEquals(4, DownloadPolicy.clampSimultaneous(5));
  }

  @Test
  public void onlyExpiredStatusesTriggerAtMostTwoRenewals() {
    for (int status : new int[] {401, 403, 404, 410}) {
      assertTrue(DownloadPolicy.isExpiredStatus(status));
      assertTrue(DownloadPolicy.shouldRenew(status, 0, true));
      assertTrue(DownloadPolicy.shouldRenew(status, 1, true));
      assertFalse(DownloadPolicy.shouldRenew(status, 2, true));
      assertFalse(DownloadPolicy.shouldRenew(status, 0, false));
    }
    for (int status : new int[] {0, 400, 402, 405, 416, 429, 500, 503}) {
      assertFalse(DownloadPolicy.isExpiredStatus(status));
      assertFalse(DownloadPolicy.shouldRenew(status, 0, true));
    }
    assertFalse(DownloadPolicy.shouldRenew(403, -1, true));
  }

  @Test
  public void renewalRequiresAReResolvableSource() {
    assertFalse(DownloadPolicy.canResolve(Json.obj("cloudServer", Json.obj("id", "s"))));
    assertTrue(DownloadPolicy.canResolve(Json.obj("music", true)));
    assertTrue(DownloadPolicy.canResolve(Json.obj("site", Json.obj("id", "site"))));
    assertFalse(DownloadPolicy.canResolve(Json.obj("site", Json.obj())));
    assertFalse(DownloadPolicy.canResolve(Json.obj("url", "https://example.com/video.mp4")));
    assertFalse(DownloadPolicy.canResolve(null));
  }

  @Test
  public void adaptiveSuffixesIgnoreCaseAndQuery() {
    assertEquals(DownloadPolicy.MediaKind.HLS, DownloadPolicy.mediaKind("https://a/v/index.M3U8?sig=x"));
    assertEquals(DownloadPolicy.MediaKind.DASH, DownloadPolicy.mediaKind("https://a/v/manifest.mpd#t=1"));
    assertEquals(DownloadPolicy.MediaKind.PROGRESSIVE, DownloadPolicy.mediaKind("https://a/v/movie.mp4?x=.m3u8"));
    assertEquals(DownloadPolicy.MediaKind.PROGRESSIVE, DownloadPolicy.mediaKind("https://a/v/watch"));
    assertEquals(DownloadPolicy.MediaKind.PROGRESSIVE, DownloadPolicy.mediaKind(null));
    assertEquals(
        DownloadPolicy.MediaKind.HLS,
        DownloadPolicy.mediaKindForItem(
            Json.obj("url", "https://a/v/watch?sig=x", "mimeType", "application/vnd.apple.mpegurl")));
    assertEquals(
        DownloadPolicy.MediaKind.DASH,
        DownloadPolicy.mediaKindForItem(Json.obj("url", "https://a/v/watch", "type", "dash")));
  }
}
