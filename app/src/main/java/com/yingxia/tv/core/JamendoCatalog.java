package com.yingxia.tv.core;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.*;
import org.json.*;

/** Jamendo's public catalog and stream API. The client id is not a secret. */
public final class JamendoCatalog {
  public static final String API = "https://api.jamendo.com/v3.0";
  public static final String CLIENT_ID = "caa9215a";
  public static final int PAGE_SIZE = 20;

  public interface Transport {
    String get(String url) throws Exception;
  }

  private final Transport transport;

  public JamendoCatalog(Transport transport) { this.transport = transport; }

  public List<JSONObject> search(String query, int page) throws Exception {
    String value = query == null ? "" : query.trim();
    if (value.isEmpty()) throw new IOException("请输入歌曲、歌手或风格关键词");
    int selected = Math.max(1, page);
    String url = query(API + "/tracks/", "client_id", CLIENT_ID,
        "format", "json", "limit", String.valueOf(PAGE_SIZE),
        "offset", String.valueOf((selected - 1) * PAGE_SIZE),
        "search", value, "include", "musicinfo");
    return parseResults(transport.get(url));
  }

  /** Re-fetches the track to obtain a fresh signed audio URL. */
  public JSONObject resolve(JSONObject song, String quality) throws Exception {
    String id = song.optString("songId").trim();
    if (!id.matches("[0-9]+")) throw new IOException("Jamendo 歌曲编号无效");
    String url = query(API + "/tracks/", "client_id", CLIENT_ID,
        "format", "json", "ids", id, "include", "musicinfo");
    List<JSONObject> tracks = parseResults(transport.get(url));
    if (tracks.isEmpty()) throw new IOException("Jamendo 未找到歌曲");
    String stream = tracks.get(0).optString("streamUrl");
    if (!isMediaUrl(stream)) throw new IOException("Jamendo 未返回可播放地址");
    return Json.obj("url", stream, "upstream", "jamendo", "fixedQuality", true,
        "actualQuality", "original", "header", Json.obj("Referer", "https://www.jamendo.com/"));
  }

  public static List<JSONObject> parseResults(String body) throws IOException {
    try {
      JSONObject response = new JSONObject(body);
      JSONObject headers = response.optJSONObject("headers");
      if (headers != null && headers.optInt("code", 0) != 0)
        throw new IOException("Jamendo：" + headers.optString("error_message", "接口错误"));
      JSONArray rows = response.optJSONArray("results");
      if (rows == null) throw new IOException("Jamendo 返回格式无效");
      List<JSONObject> result = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      for (int i = 0; i < rows.length(); i++) {
        JSONObject song = parseTrack(rows.optJSONObject(i));
        if (song != null && seen.add(song.optString("songId"))) result.add(song);
      }
      return result;
    } catch (JSONException error) {
      throw new IOException("Jamendo 返回格式无效", error);
    }
  }

  public static JSONObject parseTrack(JSONObject raw) {
    if (raw == null) return null;
    String id = raw.optString("id").trim(), title = html(raw.optString("name")).trim();
    String stream = raw.optString("audio").trim();
    if (!id.matches("[0-9]+") || title.isEmpty() || !isMediaUrl(stream)) return null;
    String artist = html(raw.optString("artist_name")).trim();
    String album = html(raw.optString("album_name")).trim();
    String poster = raw.optString("album_image", raw.optString("image"));
    return Json.obj("music", true, "source", "jamendo", "id", "jamendo:" + id,
        "songId", id, "name", title, "artist", artist, "album", album,
        "duration", Math.max(0, raw.optLong("duration")) * 1000L,
        "poster", poster, "license", raw.optString("license_ccurl"),
        "streamUrl", stream, "fixedQuality", true);
  }

  public static boolean isMediaUrl(String url) {
    try {
      URI uri = URI.create(url == null ? "" : url);
      String host = uri.getHost();
      return "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null
          && host != null && (host.equalsIgnoreCase("jamendo.com")
              || host.toLowerCase(Locale.ROOT).endsWith(".jamendo.com"));
    } catch (IllegalArgumentException error) { return false; }
  }

  private static String query(String base, String... values) {
    StringBuilder result = new StringBuilder(base);
    result.append('?');
    for (int i = 0; i + 1 < values.length; i += 2) {
      if (i > 0) result.append('&');
      try {
        result.append(URLEncoder.encode(values[i], "UTF-8"));
        result.append('=').append(URLEncoder.encode(values[i + 1], "UTF-8"));
      } catch (java.io.UnsupportedEncodingException error) { throw new IllegalStateException(error); }
    }
    return result.toString();
  }

  private static String html(String value) {
    return value == null ? "" : value.replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
  }
}
