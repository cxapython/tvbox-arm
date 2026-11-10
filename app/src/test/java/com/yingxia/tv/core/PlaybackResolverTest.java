package com.yingxia.tv.core;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class PlaybackResolverTest {
  private static PlaybackResolver.ParseService service(String name, int type, String url) {
    return new PlaybackResolver.ParseService(name, type, url, Map.of(), List.of(), "");
  }

  private static PlaybackResolver.Plan plan(String address, String playURL,
      List<PlaybackResolver.ParseService> parses) {
    return new PlaybackResolver.Plan(
        List.of(new PlaybackResolver.Choice("第1集", address)), Map.of("X-Site", "site"), "flag",
        true, "click()", playURL, parses, List.of(), List.of());
  }

  @Test
  public void jsonPrefixUsesRawServiceConcatenationAndResponseHeaders() throws Exception {
    List<String> requested = new ArrayList<>();
    PlaybackResolver resolver = new PlaybackResolver((url, headers) -> {
      requested.add(url);
      assertEquals("site", headers.get("X-Site"));
      return "{\"data\":{\"url\":\"https://cdn.example/a.m3u8\","
          + "\"headers\":{\"Referer\":\"https://parse.example/\"},\"ua\":\"Parser UA\"}}";
    }, null);

    PlaybackResolver.Resolved found = resolver.resolve(
        plan("https://page.example/watch?a=1", "json:https://parse.example/?url=", List.of()));
    assertEquals("https://parse.example/?url=https://page.example/watch?a=1", requested.get(0));
    assertEquals("https://cdn.example/a.m3u8", found.url);
    assertEquals("https://parse.example/", found.headers.get("Referer"));
    assertEquals("Parser UA", found.headers.get("User-Agent"));
    assertFalse(found.headers.containsKey("X-Site"));
  }

  @Test
  public void superParseRacesAndOneFailureDoesNotStopOtherServices() throws Exception {
    List<PlaybackResolver.ParseService> services = List.of(
        service("super", 4, ""),
        service("denied", 1, "https://denied.example/?url="),
        service("winner", 1, "https://winner.example/?url="),
        service("slow", 1, "https://slow.example/?url="));
    PlaybackResolver resolver = new PlaybackResolver((url, headers) -> {
      if (url.startsWith("https://denied")) return "{\"url\":\"\",\"errMsg\":\"login required\"}";
      if (url.startsWith("https://winner")) {
        Thread.sleep(80);
        return "{\"url\":\"https://cdn.example/win.m3u8\"}";
      }
      Thread.sleep(2000);
      return "{\"url\":\"https://cdn.example/slow.m3u8\"}";
    }, null);

    long start = System.nanoTime();
    PlaybackResolver.Resolved found = resolver.resolve(
        plan("https://page.example/v", "parse:super", services));
    assertEquals("https://cdn.example/win.m3u8", found.url);
    assertTrue("failed services must not serialize the race",
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500);
  }

  @Test
  public void superParseStartsFourJSONAndTwoWebWorkers() throws Exception {
    List<PlaybackResolver.ParseService> services = new ArrayList<>();
    services.add(service("super", 4, ""));
    for (int i = 0; i < 5; i++) services.add(service("j" + i, 1, "https://j" + i + ".example/"));
    for (int i = 0; i < 2; i++) services.add(service("w" + i, 0, "https://w" + i + ".example/"));
    AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger(), started = new AtomicInteger();
    CountDownLatch release = new CountDownLatch(1);
    PlaybackResolver.Transport transport = (url, headers) -> {
      int now = active.incrementAndGet();
      maximum.accumulateAndGet(now, Math::max);
      started.incrementAndGet();
      try {
        assertTrue(release.await(3, TimeUnit.SECONDS));
        return "{\"url\":\"\"}";
      } finally {
        active.decrementAndGet();
      }
    };
    PlaybackResolver resolver = new PlaybackResolver(transport, request -> {
      started.incrementAndGet();
      assertTrue(release.await(3, TimeUnit.SECONDS));
      return null;
    });
    ExecutorService caller = Executors.newSingleThreadExecutor();
    try {
      Future<?> result = caller.submit(() -> {
        try {
          resolver.resolve(plan("https://page.example/v", "parse:super", services));
        } catch (Exception expected) {
          assertTrue(expected.getMessage().contains("各解析服务失败原因"));
        }
      });
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (started.get() < 6 && System.nanoTime() < deadline) Thread.sleep(10);
      assertEquals(6, started.get());
      assertEquals(4, maximum.get());
      release.countDown();
      result.get(3, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      caller.shutdownNow();
    }
  }

  @Test
  public void flagFiltersJSONAndWebIndependently() {
    PlaybackResolver.ParseService jsonFlag = new PlaybackResolver.ParseService(
        "json-flag", 1, "j1", Map.of(), List.of("flag"), "");
    PlaybackResolver.ParseService jsonOther = new PlaybackResolver.ParseService(
        "json-other", 1, "j2", Map.of(), List.of("other"), "");
    PlaybackResolver.ParseService webFlag = new PlaybackResolver.ParseService(
        "web-flag", 0, "w1", Map.of(), List.of("other"), "");
    PlaybackResolver.ParseService webOther = new PlaybackResolver.ParseService(
        "web-other", 0, "w2", Map.of(), List.of("flag"), "");
    List<PlaybackResolver.ParseService> all = List.of(jsonFlag, jsonOther, webFlag, webOther);
    assertEquals(List.of(jsonFlag), PlaybackResolver.parsers(all, 1, "flag"));
    assertEquals(List.of(webOther), PlaybackResolver.parsers(all, 0, "flag"));
  }

  @Test
  public void allProviderFailuresKeepServiceNamesAndRealReasons() throws Exception {
    List<PlaybackResolver.ParseService> services = List.of(
        service("super", 4, ""),
        service("login", 1, "https://login.example/"),
        service("broken", 1, "https://broken.example/"),
        service("empty", 1, "https://empty.example/"));
    PlaybackResolver resolver = new PlaybackResolver((url, headers) -> {
      if (url.startsWith("https://login")) return "{\"msg\":\"会员已过期\"}";
      if (url.startsWith("https://broken")) return "not-json";
      return "{\"url\":\"\"}";
    }, null);
    try {
      resolver.resolve(plan("https://page.example/v", "parse:super", services));
      fail();
    } catch (IOException error) {
      assertTrue(error.getMessage().contains("login: 会员已过期"));
      assertTrue(error.getMessage().contains("broken: 返回的不是有效 JSON"));
      assertTrue(error.getMessage().contains("empty: 未返回播放地址"));
    }
  }

  @Test
  public void magnetsAreRejectedOnDirectJSONAndSniffPaths() throws Exception {
    PlaybackResolver direct = new PlaybackResolver((url, headers) -> "", null);
    assertMagnet(direct, new PlaybackResolver.Plan(
        List.of(new PlaybackResolver.Choice("magnet", "magnet:?xt=urn:btih:abc")), Map.of(),
        "", false, "", "", List.of(), List.of(), List.of()));

    PlaybackResolver json = new PlaybackResolver(
        (url, headers) -> "{\"url\":\"magnet:?xt=urn:btih:abc\"}", null);
    assertMagnet(json, plan("https://page.example/v", "json:https://parse.example/", List.of()));

    PlaybackResolver sniff = new PlaybackResolver((url, headers) -> "",
        request -> new PlaybackResolver.Resolved("magnet:?xt=urn:btih:abc", Map.of()));
    assertMagnet(sniff, plan("https://page.example/v", "parse:web",
        List.of(service("web", 0, "https://parse.example/"))));
  }

  private static void assertMagnet(PlaybackResolver resolver, PlaybackResolver.Plan plan)
      throws Exception {
    try {
      resolver.resolve(plan);
      fail();
    } catch (IOException error) {
      assertTrue(error.getMessage(), error.getMessage().contains("磁链"));
    }
  }

  @Test
  public void loopbackM3U8ProxyUnwrapsAndOtherProxiesFailClearly() throws Exception {
    String wrapped = "http://127.0.0.1:9978/proxy?do=m3u8&url="
        + "https%3A%2F%2Fcdn.example%2Fa.m3u8%3Fx%3D1%25262";
    PlaybackResolver resolver = new PlaybackResolver((url, headers) -> "", null);
    PlaybackResolver.Resolved found = resolver.resolve(new PlaybackResolver.Plan(
        List.of(new PlaybackResolver.Choice("one", wrapped + "|Referer=https%3A%2F%2Fref.example%2F")),
        Map.of(), "", false, "", "", List.of(), List.of(), List.of()));
    assertEquals("https://cdn.example/a.m3u8?x=1%262", found.url);
    assertEquals("https://ref.example/", found.headers.get("Referer"));
    assertMagnet(resolver, new PlaybackResolver.Plan(
        List.of(new PlaybackResolver.Choice("magnet",
            "http://localhost/proxy?do=m3u8&url=magnet%3A%3Fxt%3Durn%3Abtih%3Aabc")),
        Map.of(), "", false, "", "", List.of(), List.of(), List.of()));

    assertTrue(PlaybackResolver.isPluginProxy("http://localhost:65535/proxy?do=m3u8&url=x"));
    assertFalse(PlaybackResolver.isPluginProxy("https://127.0.0.1:9978/proxy?do=m3u8&url=x"));
    assertFalse(PlaybackResolver.isPluginProxy("http://127.0.0.1.evil/proxy?do=m3u8&url=x"));
    assertFalse(PlaybackResolver.isPluginProxy("http://localhost:abc/proxy?do=m3u8&url=x"));
    try {
      resolver.resolve(new PlaybackResolver.Plan(
          List.of(new PlaybackResolver.Choice("one", "http://localhost/proxy?do=bili&aid=1&cid=2")),
          Map.of(), "", false, "", "", List.of(), List.of(), List.of()));
      fail();
    } catch (IOException error) {
      assertTrue(error.getMessage().contains("插件播放代理"));
    }
  }

  @Test
  public void siteResultUAAndPipeHeadersMergeInOrder() throws Exception {
    JSONObject site = Json.obj("header", Json.obj("X-Site", "yes", "User-Agent", "site"));
    JSONObject answer = Json.obj("url", "https://cdn.example/a.m3u8|X-Pipe=a%2Bb&Referer=https%3A%2F%2Fref.example%2F",
        "parse", 0, "header", "{\"X-Result\":\"yes\"}", "UA", "result UA");
    JSONObject episode = Json.obj("name", "第1集", "flag", "flag");
    PlaybackResolver.Plan plan = PlaybackResolver.plan(site, answer, episode);
    assertEquals("yes", plan.headers.get("X-Site"));
    assertEquals("yes", plan.headers.get("X-Result"));
    assertEquals("result UA", plan.headers.get("User-Agent"));
    PlaybackResolver.Resolved found = new PlaybackResolver((url, headers) -> "", null).resolve(plan);
    assertEquals("https://cdn.example/a.m3u8", found.url);
    assertEquals("a+b", found.headers.get("X-Pipe"));
    assertEquals("https://ref.example/", found.headers.get("Referer"));
  }

  @Test
  public void fongMiValuesStartAtPositionAndKeepQualityNames() {
    JSONObject url = new JSONObject();
    try {
      url.put("position", 1).put("values", new JSONArray()
          .put(Json.obj("n", "标清", "v", "https://cdn.example/sd.m3u8"))
          .put(Json.obj("n", "高清", "v", "https://cdn.example/hd.m3u8"))
          .put(Json.obj("n", "空", "v", "")));
    } catch (Exception error) {
      throw new AssertionError(error);
    }
    List<PlaybackResolver.Choice> choices = PlaybackResolver.choices(url, "", "第1集");
    assertEquals(2, choices.size());
    assertEquals("高清", choices.get(0).name);
    assertEquals("https://cdn.example/hd.m3u8", choices.get(0).address);
    assertEquals("标清", choices.get(1).name);
    assertNull(PlaybackResolver.providerError(Json.obj("url", url, "msg", "ignored")));
  }

  @Test
  public void resolvesTheSelectedFongMiQualityWithoutReorderingChoices() throws Exception {
    JSONObject site = new JSONObject();
    JSONObject answer = Json.obj("parse", 0, "url", Json.obj("position", 0,
        "values", new JSONArray()
            .put(Json.obj("n", "标清", "v", "https://cdn.example/sd.m3u8"))
            .put(Json.obj("n", "高清", "v", "https://cdn.example/hd.m3u8"))));
    PlaybackResolver.Plan plan = PlaybackResolver.plan(site, answer, Json.obj("name", "第1集"));
    PlaybackResolver resolver = new PlaybackResolver((url, headers) -> "", null);
    assertEquals("https://cdn.example/sd.m3u8", resolver.resolve(plan, 0).url);
    assertEquals("https://cdn.example/hd.m3u8", resolver.resolve(plan, 1).url);
    try {
      resolver.resolve(plan, 2);
      fail();
    } catch (IOException error) {
      assertTrue(error.getMessage().contains("清晰度已失效"));
    }
  }

  @Test
  public void playbackMetadataPassesThroughWithoutChangingShape() throws Exception {
    JSONArray subs = new JSONArray().put(Json.obj("name", "中文", "url", "https://sub.example/zh.srt"));
    JSONArray skips = new JSONArray().put(Json.obj("start", 1, "end", 2));
    JSONObject answer = Json.obj("danmaku", "https://danmaku.example/1", "subs", subs,
        "skips", skips, "decryptKey", "001122", "subt", subs);
    JSONObject play = new JSONObject();
    PlaybackResolver.passThrough(answer, play);
    assertEquals("https://danmaku.example/1", play.getString("danmaku"));
    assertSame(subs, play.get("subs"));
    assertSame(skips, play.get("skips"));
    assertEquals("001122", play.getString("decryptKey"));
    assertSame(subs, play.get("subtitles"));
    JSONObject standardSubs = new JSONObject();
    PlaybackResolver.passThrough(Json.obj("subs", subs), standardSubs);
    assertSame(subs, standardSubs.get("subtitles"));
  }

  @Test
  public void embeddedMacCMSMediaIsResolvedBeforeParsers() throws Exception {
    PlaybackResolver resolver = new PlaybackResolver((url, headers) ->
        "<script>var player_aaaa={\"url\":\"https:\\/\\/cdn.example\\/a.m3u8\",\"encrypt\":0};</script>",
        null);
    PlaybackResolver.Resolved found = resolver.resolve(
        plan("https://page.example/v", "", List.of(service("unused", 1, "https://unused.example/"))));
    assertEquals("https://cdn.example/a.m3u8", found.url);
  }
}
