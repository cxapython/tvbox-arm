package com.yingxia.tv.core;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

public class DiscoverCatalogTest {
  @Test
  public void supportedFeedsStartWithTheWorkingDefault() {
    List<JSONObject> feeds = DiscoverCatalog.feeds();
    assertEquals(22, feeds.size());
    String[] expected = {
      "trending", "movies", "television", "onTheAir", "nowPlaying", "upcoming",
      "topMovies", "topTelevision", "animation", "chinese", "korean", "variety",
      "family", "kids", "netflix", "disney", "appleTV", "hbo", "warner", "universal",
      "paramount", "sony"
    };
    for (int index = 0; index < expected.length; index++) {
      assertEquals(expected[index], feeds.get(index).optString("feed"));
      assertFalse(feeds.get(index).optString("name").isEmpty());
    }
    assertEquals("热门", feeds.get(0).optString("name"));
    assertEquals(DiscoverCatalog.DEFAULT_FEED, feeds.get(0).optString("feed"));
    assertEquals("trending", DiscoverCatalog.DEFAULT_FEED);
    assertTrue(feeds.stream().noneMatch(feed -> "featured".equals(feed.optString("feed"))));
  }

  @Test
  public void languageHonorsScriptsRegionsAndConfiguredPriority() {
    assertEquals("zh-CN", DiscoverCatalog.language(Locale.SIMPLIFIED_CHINESE));
    assertEquals("zh-CN", DiscoverCatalog.language(Locale.forLanguageTag("zh-Hans")));
    assertEquals("zh-TW", DiscoverCatalog.language(Locale.TRADITIONAL_CHINESE));
    assertEquals("zh-TW", DiscoverCatalog.language(Locale.forLanguageTag("zh-Hant")));
    assertEquals("zh-TW", DiscoverCatalog.language(Locale.forLanguageTag("zh-HK")));
    assertEquals("ja-JP", DiscoverCatalog.language(Locale.JAPAN));
    assertEquals("en-US", DiscoverCatalog.language(Locale.US));

    assertEquals("zh-CN", DiscoverCatalog.language("简体", Locale.US));
    assertEquals("zh-TW", DiscoverCatalog.language("繁体", Locale.US));
    assertEquals("zh-TW", DiscoverCatalog.language("TW", Locale.US));
    assertEquals("zh-TW", DiscoverCatalog.language("zh_HK", Locale.US));
    assertEquals("ja-JP", DiscoverCatalog.language("ja", Locale.CHINA));
    assertEquals("en-US", DiscoverCatalog.language("en", Locale.CHINA));
    assertEquals("zh-CN", DiscoverCatalog.language("  ", Locale.CHINA));
  }

  @Test
  public void detailsParametersContainVersionTypeIdAndLanguage() {
    Map<String, String> parameters = DiscoverCatalog.detailsParameters("movie", "42", "zh-CN");
    assertEquals("https://yingxia-tmdb.getmegaportal.com/v1/details",
        DiscoverCatalog.DETAILS_ENDPOINT);
    assertEquals("4", parameters.get("v"));
    assertEquals("movie", parameters.get("type"));
    assertEquals("42", parameters.get("id"));
    assertEquals("zh-CN", parameters.get("language"));
  }

  @Test
  public void mergePrefersRichDetailsAndRetainsListedFieldsWithoutMutatingInputs() {
    JSONObject listed = Json.obj(
        "id", "42", "name", "列表标题", "poster", "listed.jpg", "year", "2024");
    JSONObject details = Json.obj("name", "详情标题", "overview", "完整简介", "rating", 8.5);
    String listedBefore = listed.toString(), detailsBefore = details.toString();

    JSONObject merged = DiscoverCatalog.mergeDetails(listed, details);
    assertNotSame(listed, merged);
    assertNotSame(details, merged);
    assertEquals("42", merged.optString("id"));
    assertEquals("listed.jpg", merged.optString("poster"));
    assertEquals("2024", merged.optString("year"));
    assertEquals("详情标题", merged.optString("name"));
    assertEquals("完整简介", merged.optString("overview"));
    assertEquals(8.5, merged.optDouble("rating"), 0);
    assertEquals(listedBefore, listed.toString());
    assertEquals(detailsBefore, details.toString());
  }
}
