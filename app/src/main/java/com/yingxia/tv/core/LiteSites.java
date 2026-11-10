package com.yingxia.tv.core;

import org.json.JSONArray;
import org.json.JSONObject;

/** Decides whether a lite-site answer is unusable and should fall back to the JAR spider. */
public final class LiteSites {
  private static final String[] PROVIDER_ERROR_KEYS = {"errMsg", "msg", "message"};

  private LiteSites() {}

  /** Returns the fallback reason, or null when the answer is usable. */
  public static String unusable(JSONObject answer, JSONObject params) {
    if (params.has("play")) {
      String error = providerError(answer);
      if (error != null) return error;
      Object url = answer.opt("url");
      boolean emptyString = url instanceof String && ((String) url).isEmpty();
      boolean emptyArray = url instanceof JSONArray && ((JSONArray) url).length() == 0;
      return url == null || emptyString || emptyArray ? "no playback URL" : null;
    }
    if (params.has("ids")) return empty(answer, "list") ? "no details" : null;
    if (params.has("wd") || params.has("filters")) return null;
    if (params.has("t")) {
      boolean firstPage = params.optString("pg", "1").equals("1");
      return !params.optString("t").isEmpty() && firstPage && empty(answer, "list")
          ? "no videos"
          : null;
    }
    return empty(answer, "class") && empty(answer, "list") ? "no categories" : null;
  }

  private static String providerError(JSONObject answer) {
    Object url = answer.opt("url");
    boolean noStringURL = !(url instanceof String) || ((String) url).trim().isEmpty();
    boolean noArrayURL = !(url instanceof JSONArray) || ((JSONArray) url).length() == 0;
    if (!noStringURL || !noArrayURL) return null;
    for (String key : PROVIDER_ERROR_KEYS) {
      Object value = answer.opt(key);
      if (!(value instanceof String)) continue;
      String message = ((String) value).trim();
      if (!message.isEmpty()) return message;
    }
    return null;
  }

  private static boolean empty(JSONObject answer, String key) {
    Object value = answer.opt(key);
    return !(value instanceof JSONArray) || ((JSONArray) value).length() == 0;
  }
}
