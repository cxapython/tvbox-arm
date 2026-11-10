package com.yingxia.tv.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/** TMDB discovery metadata and detail merging, independent of network and Android UI. */
public final class DiscoverCatalog {
  public static final String DEFAULT_FEED = "trending";
  public static final String DETAILS_ENDPOINT = "https://yingxia-tmdb.getmegaportal.com/v1/details";
  public static final String DETAILS_VERSION = "4";

  private static final String[][] FEEDS = {
    {"trending", "热门"},
    {"movies", "电影"},
    {"television", "剧集"},
    {"onTheAir", "正在播出"},
    {"nowPlaying", "正在上映"},
    {"upcoming", "即将上映"},
    {"topMovies", "高分电影"},
    {"topTelevision", "高分剧集"},
    {"animation", "动漫"},
    {"chinese", "华语"},
    {"korean", "韩剧"},
    {"variety", "综艺"},
    {"family", "家庭"},
    {"kids", "儿童"},
    {"netflix", "Netflix"},
    {"disney", "Disney+"},
    {"appleTV", "Apple TV+"},
    {"hbo", "HBO"},
    {"warner", "华纳"},
    {"universal", "环球"},
    {"paramount", "派拉蒙"},
    {"sony", "索尼"}
  };

  private DiscoverCatalog() {}

  /** Fresh feed/name objects in the original catalog order, safe for a UI to decorate. */
  public static List<JSONObject> feeds() {
    List<JSONObject> out = new ArrayList<>();
    for (String[] feed : FEEDS) out.add(Json.obj("feed", feed[0], "name", feed[1]));
    return out;
  }

  public static String language(Locale locale) {
    return locale == null ? "en-US" : mapLanguage(locale.toLanguageTag());
  }

  /** A nonblank configured value wins; a blank or absent value uses the device locale. */
  public static String language(String configured, Locale fallback) {
    return configured == null || configured.trim().isEmpty()
        ? language(fallback)
        : mapLanguage(configured);
  }

  private static String mapLanguage(String value) {
    String language = value.trim().replace('_', '-').toLowerCase(Locale.ROOT);
    if (language.equals("简体")
        || language.equals("zh-hans")
        || language.startsWith("zh-hans-")
        || language.equals("zh-cn")) return "zh-CN";
    if (language.equals("繁体")
        || language.equals("zh-hant")
        || language.startsWith("zh-hant-")
        || language.equals("zh-tw")
        || language.equals("zh-hk")
        || language.equals("tw")
        || language.equals("hk")) return "zh-TW";
    if (language.equals("ja") || language.startsWith("ja-")) return "ja-JP";
    return "en-US";
  }

  public static Map<String, String> detailsParameters(String type, String id, String language) {
    Map<String, String> out = new LinkedHashMap<>();
    out.put("v", DETAILS_VERSION);
    out.put("type", type);
    out.put("id", id);
    out.put("language", language);
    return out;
  }

  /** Details override listed fields; fields absent from details are retained from the list item. */
  public static JSONObject mergeDetails(JSONObject listed, JSONObject details) {
    JSONObject merged = Json.copy(listed);
    for (String key : Json.keys(details))
      try {
        merged.put(key, details.opt(key));
      } catch (JSONException broken) {
        throw new IllegalStateException("无法合并影片详情", broken);
      }
    return merged;
  }
}
