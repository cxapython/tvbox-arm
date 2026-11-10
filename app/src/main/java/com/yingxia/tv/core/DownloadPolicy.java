package com.yingxia.tv.core;

import java.util.Locale;
import org.json.JSONObject;

/** Download limits and renewal decisions shared with the original DownloadLibrary. */
public final class DownloadPolicy {
  public static final String SIMULTANEOUS_SETTING = "downloadsSimultaneous";
  public static final int DEFAULT_SIMULTANEOUS = 2;
  public static final int MIN_SIMULTANEOUS = 1;
  public static final int MAX_SIMULTANEOUS = 4;
  public static final int MAX_RENEWALS = 2;

  public enum MediaKind {
    PROGRESSIVE,
    HLS,
    DASH
  }

  private DownloadPolicy() {}

  /** A missing, zero or negative preference has the original default of two. */
  public static int simultaneous(int stored) {
    return stored > 0 ? clampSimultaneous(stored) : DEFAULT_SIMULTANEOUS;
  }

  public static int clampSimultaneous(int value) {
    return Math.max(MIN_SIMULTANEOUS, Math.min(MAX_SIMULTANEOUS, value));
  }

  public static boolean isExpiredStatus(int status) {
    return status == 401 || status == 403 || status == 404 || status == 410;
  }

  public static boolean canResolve(JSONObject item) {
    return item != null
        && (item.optBoolean("music")
            || item.optJSONObject("site") != null && item.optJSONObject("site").length() > 0);
  }

  public static boolean shouldRenew(int status, int renewals, boolean resolvable) {
    return resolvable && isExpiredStatus(status) && renewals >= 0 && renewals < MAX_RENEWALS;
  }

  public static MediaKind mediaKindForItem(JSONObject item) {
    String declared = item == null ? "" : item.optString("mimeType", item.optString("type"));
    String normalized = declared.toLowerCase(Locale.ROOT);
    if (normalized.contains("m3u8")
        || normalized.contains("mpegurl")
        || normalized.contains("hls")) return MediaKind.HLS;
    if (normalized.contains("mpd") || normalized.contains("dash")) return MediaKind.DASH;
    return mediaKind(item == null ? "" : item.optString("url"));
  }

  /** URL suffixes select Media3's adaptive helpers; query strings and case do not affect them. */
  public static MediaKind mediaKind(String value) {
    String path = value == null ? "" : value;
    int query = path.indexOf('?'), fragment = path.indexOf('#');
    int end = path.length();
    if (query >= 0) end = Math.min(end, query);
    if (fragment >= 0) end = Math.min(end, fragment);
    path = path.substring(0, end).toLowerCase(Locale.ROOT);
    if (path.endsWith(".m3u8")) return MediaKind.HLS;
    if (path.endsWith(".mpd")) return MediaKind.DASH;
    return MediaKind.PROGRESSIVE;
  }
}
