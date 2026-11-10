package com.yingxia.tv.core;

import static org.junit.Assert.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class SubscriptionMergeTest {
  private static JSONObject subscription(String id, String origin, JSONObject config) {
    return Json.obj("id", id, "origin", origin, "name", id, "config", config);
  }

  private static JSONObject site(String key, String api) {
    return Json.obj("key", key, "name", key, "type", 1, "api", api);
  }

  private static JSONObject config(JSONObject... sites) {
    JSONArray list = new JSONArray();
    for (JSONObject site : sites) list.put(site);
    return Json.obj("sites", list);
  }

  @Test
  public void realFantaiyingFixtureKeepsItsSourcesSpiderAndLives() throws Exception {
    JSONObject config;
    try (InputStream in = getClass().getResourceAsStream("/fantaiying.json")) {
      assertNotNull("app/src/test/resources/fantaiying.json must be on the test classpath", in);
      config = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
    String origin = "https://example.com/fantaiying.json";
    JSONObject subscription = subscription("fixture", origin, config);

    List<JSONObject> sites = SubscriptionMerge.sites(List.of(subscription));
    assertEquals(config.getJSONArray("sites").length(), sites.size());
    assertEquals(config.getString("spider"), sites.get(0).getString("spider"));
    assertEquals(origin, sites.get(0).getString("origin"));
    assertEquals(
        config.getJSONArray("lives").length(), SubscriptionMerge.lives(List.of(subscription)).size());
  }

  @Test
  public void siteSpiderWinsAndSubscriptionSpiderOnlyFillsBlank() throws Exception {
    JSONObject own = site("own", "https://api.example/own");
    own.put("spider", "own.jar;md5;aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    JSONObject missing = site("missing", "https://api.example/missing");
    JSONObject blank = site("blank", "https://api.example/blank");
    blank.put("spider", "  ");
    JSONObject config = config(own, missing, blank);
    config.put("spider", "shared.jar;md5;bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

    List<JSONObject> sites =
        SubscriptionMerge.sites(
            List.of(subscription("one", "https://one.example/config.json", config)));
    assertEquals(own.getString("spider"), sites.get(0).getString("spider"));
    assertEquals(config.getString("spider"), sites.get(1).getString("spider"));
    assertEquals(config.getString("spider"), sites.get(2).getString("spider"));
  }

  @Test
  public void fingerprintDeduplicatesOnlyAcrossSubscriptions() throws Exception {
    JSONObject first = site("first", "https://api.example/vod");
    first.put("ext", new JSONObject().put("z", "last").put("a", "first"));
    JSONObject mirror = site("mirror", "https://api.example/vod");
    mirror.put("ext", new JSONObject().put("a", "first").put("z", "last"));
    List<JSONObject> subscriptions =
        List.of(
            subscription("one", "https://one.example/config.json", config(first)),
            subscription("two", "https://two.example/config.json", config(mirror)));

    List<JSONObject> merged = SubscriptionMerge.sites(subscriptions);
    assertEquals(1, merged.size());
    assertEquals("first", merged.get(0).getString("key"));

    JSONObject repeatA = site("repeat", "https://api.example/repeat");
    JSONObject repeatB = site("repeat", "https://api.example/repeat");
    String origin = "https://one.example/config.json";
    List<JSONObject> repeats =
        SubscriptionMerge.sites(
            List.of(subscription("one", origin, config(repeatA, repeatB))));
    assertEquals(2, repeats.size());
    assertEquals("repeat", repeats.get(0).getString("key"));
    assertEquals(
        "repeat@" + ConfigCodec.hash(origin).substring(0, 6), repeats.get(1).getString("key"));
    assertEquals("repeat", repeats.get(1).getString("sourceKey"));
  }

  @Test
  public void laterKeyClashUsesOriginHashAndKeepsSourceKey() throws Exception {
    String firstOrigin = "https://one.example/config.json";
    String secondOrigin = "https://two.example/config.json";
    List<JSONObject> merged =
        SubscriptionMerge.sites(
            List.of(
                subscription(
                    "first-subscription-id",
                    firstOrigin,
                    config(site("shared", "https://api.example/one"))),
                subscription(
                    "second-subscription-id",
                    secondOrigin,
                    config(site("shared", "https://api.example/two")))));

    assertEquals(2, merged.size());
    String tag = ConfigCodec.hash(secondOrigin).substring(0, 6);
    assertEquals("shared@" + tag, merged.get(1).getString("key"));
    assertEquals("shared", merged.get(1).getString("sourceKey"));
    assertEquals("second-subscription-id@shared@" + tag, merged.get(1).getString("id"));
    assertEquals(secondOrigin, merged.get(1).getString("origin"));
  }

  @Test
  public void eachSiteKeepsItsSubscriptionsSniffConfiguration() throws Exception {
    JSONObject config = config(site("one", "https://api.example/one"));
    config.put("ads", new JSONArray().put("ads.example"));
    config.put("rules", new JSONArray().put(Json.obj("hosts", new JSONArray().put("cdn.example"),
        "regex", new JSONArray().put("token=[0-9]+"))));
    JSONObject site = SubscriptionMerge.sites(List.of(
        subscription("one", "https://one.example/config.json", config))).get(0);
    PlaybackResolver.Plan plan = PlaybackResolver.plan(site,
        Json.obj("url", "https://cdn.example/a.m3u8", "parse", 0),
        Json.obj("name", "第1集", "flag", "flag"));
    assertEquals(List.of("ads.example"), plan.ads);
    assertEquals(1, plan.rules.size());
    assertEquals(List.of("cdn.example"), plan.rules.get(0).hosts);
  }

  @Test
  public void parsesAndLivesJoinInOrderWithoutRepeats() throws Exception {
    String origin = "https://example.com/a/config.json";
    JSONObject firstConfig = config(site("one", "https://api.example/one"));
    firstConfig.put(
        "parses",
        new JSONArray()
            .put(Json.obj("name", "first", "url", "https://parse.example/one"))
            .put(Json.obj("name", "shared", "url", "https://parse.example/shared")));
    firstConfig.put(
        "lives",
        new JSONArray()
            .put(Json.obj("name", "relative", "url", "live.m3u"))
            .put(Json.obj("name", "first-only", "url", "https://live.example/first.m3u")));

    JSONObject secondConfig = config(site("two", "https://api.example/two"));
    secondConfig.put(
        "parses",
        new JSONArray()
            .put(Json.obj("name", "duplicate", "url", "https://parse.example/shared"))
            .put(Json.obj("name", "second", "url", "https://parse.example/two")));
    secondConfig.put(
        "lives",
        new JSONArray()
            .put(
                Json.obj(
                    "name", "duplicate", "url", "https://example.com/a/live.m3u"))
            .put(Json.obj("name", "second-only", "url", "https://live.example/second.m3u")));
    List<JSONObject> subscriptions =
        List.of(
            subscription("one", origin, firstConfig), subscription("two", origin, secondConfig));

    JSONArray parses = SubscriptionMerge.parses(subscriptions);
    assertEquals(3, parses.length());
    assertEquals("first", parses.getJSONObject(0).getString("name"));
    assertEquals("shared", parses.getJSONObject(1).getString("name"));
    assertEquals("second", parses.getJSONObject(2).getString("name"));
    for (JSONObject site : SubscriptionMerge.sites(subscriptions))
      assertEquals(3, site.getJSONArray("parses").length());

    List<JSONObject> lives = SubscriptionMerge.lives(subscriptions);
    assertEquals(3, lives.size());
    assertEquals("relative", lives.get(0).getString("name"));
    assertEquals("first-only", lives.get(1).getString("name"));
    assertEquals("second-only", lives.get(2).getString("name"));
    assertEquals("one@relative", lives.get(0).getString("id"));
    assertEquals(origin, lives.get(0).getString("origin"));
  }
}
