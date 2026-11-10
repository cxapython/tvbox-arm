package com.yingxia.tv.core;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Account-free, deep-copied library snapshots suitable for sharing. */
public final class LibraryBackup {
  private static final Set<String> ACCOUNT_FIELDS =
      new HashSet<>(
          Arrays.asList(
              "username",
              "password",
              "token",
              "accesstoken",
              "refreshtoken",
              "idtoken",
              "authorization",
              "cookie",
              "setcookie",
              "apikey",
              "secret",
              "session",
              "sessionid",
              "credentials",
              "folderpassword"));

  private LibraryBackup() {}

  /**
   * Keeps cloud locations needed to resolve public/folder-password protected history, while removing
   * account credentials. The input is never mutated.
   */
  public static JSONObject withoutAccounts(JSONObject state) {
    try {
      JSONObject library = new JSONObject(state.toString());
      library.remove("servers");
      for (String section : Arrays.asList("history", "favorites", "downloads")) {
        JSONArray items = library.optJSONArray(section);
        if (items == null) continue;
        for (int index = 0; index < items.length(); index++) {
          JSONObject item = items.optJSONObject(index);
          if (item == null) continue;
          JSONObject server = item.optJSONObject("cloudServer");
          if (server != null) item.put("cloudServer", withoutCredentials(server));
          item.remove("header");
          item.remove("downloadCacheKey");
          if (item.has("cloudFile")) item.remove("url");
        }
      }
      return library;
    } catch (JSONException error) {
      throw new IllegalArgumentException("无法生成脱敏备份", error);
    }
  }

  private static JSONObject withoutCredentials(JSONObject server) throws JSONException {
    JSONObject result = new JSONObject(server.toString());
    for (String key : Json.keys(result)) {
      if (ACCOUNT_FIELDS.contains(normalize(key))) result.remove(key);
    }
    return result;
  }

  private static String normalize(String key) {
    return key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
  }
}
