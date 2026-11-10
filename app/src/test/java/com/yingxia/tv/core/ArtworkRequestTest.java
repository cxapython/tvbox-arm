package com.yingxia.tv.core;

import static org.junit.Assert.*;
import org.junit.Test;

public final class ArtworkRequestTest {
  @Test public void guaziAgentIsAHeaderNotPartOfThePath() {
    ArtworkRequest request = ArtworkRequest.parse(
        "https://img.example/poster.jpg@User-Agent=Dalvik/2.1.0", null);
    assertEquals("https://img.example/poster.jpg", request.url);
    assertEquals("Dalvik/2.1.0", request.headers.get("User-Agent"));
  }

  @Test public void originalPosterResourceCasesPreserveAtSignsAndResolveRelativePaths() {
    ArtworkRequest request = ArtworkRequest.parse(
        "/art/@2x.jpg@Referer=https://origin.example/@User-Agent=Example Agent@Cookie=id=1",
        "https://example.com/config.json");
    assertEquals("https://example.com/art/@2x.jpg", request.url);
    assertEquals("https://origin.example/", request.headers.get("Referer"));
    assertEquals("Example Agent", request.headers.get("User-Agent"));
    assertEquals("id=1", request.headers.get("Cookie"));
    assertEquals("https://example.com/image@2x.jpg",
        ArtworkRequest.parse("/image@2x.jpg", "https://example.com/config.json").url);
  }

  @Test public void bothJsonSuffixesAndDoubanRefererAreSupported() {
    for (String suffix : new String[] {"@Headers=", "@"}) {
      ArtworkRequest request = ArtworkRequest.parse(
          "https://img3.doubanio.com/p.jpg" + suffix
              + "{\"referer\":\"https://custom.example/\",\"User-Agent\":\"Custom\"}", null);
      assertEquals("https://custom.example/", request.headers.get("Referer"));
      assertEquals("Custom", request.headers.get("User-Agent"));
    }
    assertEquals("https://movie.douban.com/",
        ArtworkRequest.parse("https://img3.doubanio.com/p.jpg", null).headers.get("Referer"));
  }

  @Test public void invalidHeadersCannotInjectAdditionalRequestLines() {
    ArtworkRequest request = ArtworkRequest.parse(
        "https://img.example/p.jpg@Headers={\"Bad Key\":\"x\",\"Cookie\":\"x\\r\\nY: z\",\"Referer\":\"ok\"}", null);
    assertEquals(1, request.headers.size());
    assertEquals("ok", request.headers.get("Referer"));
  }
}
