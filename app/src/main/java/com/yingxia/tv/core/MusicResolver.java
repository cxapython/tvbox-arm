package com.yingxia.tv.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Injectable, ordered music-provider fallback with quality mapping and an aggregate failure. */
public final class MusicResolver {
  public static final String FLAC = "flac";
  public static final String HIGH = "320";
  public static final String STANDARD = "128";

  public interface Provider {
    String name();

    JSONObject resolve(JSONObject song, String quality) throws Exception;
  }

  private final List<Provider> providers;

  public MusicResolver(List<Provider> providers) {
    this.providers = new ArrayList<>(providers);
  }

  public JSONObject resolve(JSONObject song, String quality) throws Exception {
    String normalized = normalizeQuality(quality);
    List<String> failures = new ArrayList<>();
    for (Provider provider : providers) {
      try {
        JSONObject stream = provider.resolve(song, normalized);
        String url = stream == null ? "" : stream.optString("url");
        if (!url.startsWith("http://") && !url.startsWith("https://"))
          throw new IOException("未返回有效播放地址");
        return stream;
      } catch (Exception error) {
        String message = Json.message(error).replaceAll("[\\r\\n]+", " ").trim();
        if (message.length() > 240) message = message.substring(0, 240);
        failures.add(provider.name() + ": " + (message.isEmpty() ? "未知错误" : message));
      }
    }
    if (failures.isEmpty()) failures.add("没有可用于该平台的音源");
    throw new IOException("所有音源均不可用（" + normalized + "）：" + String.join("；", failures));
  }

  public static String normalizeQuality(String quality) {
    String value = quality == null ? HIGH : quality.trim().toLowerCase(Locale.ROOT);
    if (value.equals("flac") || value.equals("lossless") || value.equals("999") || value.equals("sq"))
      return FLAC;
    if (value.equals("128") || value.equals("128k") || value.equals("standard") || value.equals("pq"))
      return STANDARD;
    return HIGH;
  }

  public static String normalizeQuality(int quality) {
    return quality == 999 ? FLAC : normalizeQuality(String.valueOf(quality));
  }

  public static String gdstudioBitrate(String quality) {
    return normalizeQuality(quality).equals(FLAC) ? "999" : normalizeQuality(quality);
  }

  public static String vkeysCode(String quality) {
    switch (normalizeQuality(quality)) {
      case FLAC:
        return "10";
      case STANDARD:
        return "6";
      default:
        return "8";
    }
  }

  public static List<String> nianxinLevels(String quality) {
    switch (normalizeQuality(quality)) {
      case FLAC:
        return Arrays.asList("lossless", "exhigh");
      case STANDARD:
        return Collections.singletonList("standard");
      default:
        return Arrays.asList("exhigh", "standard");
    }
  }

  /** Selects a declared LX tier, or null when the source cannot serve the requested lossy tier. */
  public static String lxQuality(JSONArray declared, String quality) {
    List<String> values = new ArrayList<>();
    if (declared != null)
      for (int index = 0; index < declared.length(); index++) values.add(declared.optString(index));
    switch (normalizeQuality(quality)) {
      case FLAC:
        for (String candidate : Arrays.asList("flac", "flac24bit", "320k", "128k"))
          if (values.contains(candidate)) return candidate;
        return "320k";
      case STANDARD:
        return values.isEmpty() || values.contains("128k") ? "128k" : null;
      default:
        if (values.isEmpty() || values.contains("320k")) return "320k";
        return values.contains("128k") ? "128k" : null;
    }
  }
}
