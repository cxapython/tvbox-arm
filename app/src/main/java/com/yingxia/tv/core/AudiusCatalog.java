package com.yingxia.tv.core;

import java.io.IOException;
import java.util.*;
import org.json.*;

/** Public Audius discovery catalog and short-lived stream resolver. */
public final class AudiusCatalog {
  public static final String API = "https://discoveryprovider.audius.co/v1";
  public static final String APP_NAME = "tvbox_arm";
  public static final int PAGE_SIZE = 20;

  public interface Transport {
    String get(String url) throws Exception;
  }

  private final Transport transport;

  public AudiusCatalog(Transport transport) { this.transport = transport; }

  public List<JSONObject> search(String query, int page) throws Exception {
    String value = query == null ? "" : query.trim();
    if (value.isEmpty()) throw new IOException("请输入歌曲、歌手或风格关键词");
    int selected = Math.max(1, page);
    String url = HttpUrlBuilder.query(API + "/tracks/search",
        "query", value,
        "limit", String.valueOf(PAGE_SIZE),
        "offset", String.valueOf((selected - 1) * PAGE_SIZE),
        "app_name", APP_NAME);
    JSONObject response = new JSONObject(transport.get(url));
    JSONArray data = response.optJSONArray("data");
    if (data == null) throw new IOException("Audius 返回格式无效");
    List<JSONObject> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < data.length(); i++) {
      JSONObject raw = data.optJSONObject(i);
      JSONObject song = parseTrack(raw);
      if (song != null && seen.add(song.optString("songId"))) result.add(song);
    }
    return result;
  }

  /** Re-fetches the track so the stream URL is renewed instead of reusing a search result. */
  public JSONObject resolve(JSONObject song, String quality) throws Exception {
    String id = song.optString("songId").trim();
    if (id.isEmpty() || id.length() > 80 || !id.matches("[A-Za-z0-9_-]+"))
      throw new IOException("Audius 歌曲编号无效");
    JSONObject response = new JSONObject(transport.get(HttpUrlBuilder.query(
        API + "/tracks/" + id, "app_name", APP_NAME)));
    JSONObject track = response.optJSONObject("data");
    JSONObject stream = track == null ? null : track.optJSONObject("stream");
    String url = stream == null ? "" : stream.optString("url");
    if (!isMediaUrl(url)) throw new IOException("Audius 未返回可播放地址");
    return Json.obj("url", url, "upstream", "audius", "fixedQuality", true,
        "actualQuality", "original");
  }

  public static JSONObject parseTrack(JSONObject raw) throws IOException {
    if (raw == null) return null;
    String id = raw.optString("id").trim();
    String title = raw.optString("title").trim();
    if (id.isEmpty() || title.isEmpty() || !raw.optBoolean("is_streamable", true)) return null;
    JSONObject user = raw.optJSONObject("user");
    String artist = user == null ? "" : user.optString("name").trim();
    JSONObject artwork = raw.optJSONObject("artwork");
    String poster = artwork == null ? "" : artwork.optString("480x480", artwork.optString("150x150"));
    return Json.obj("music", true, "source", "audius", "id", "audius:" + id,
        "songId", id, "name", title, "artist", artist, "album", "",
        "duration", Math.max(0, raw.optLong("duration")) * 1000L,
        "poster", poster, "genre", raw.optString("genre"), "fixedQuality", true);
  }

  public static boolean isMediaUrl(String url) {
    return url != null && (url.startsWith("https://") || url.startsWith("http://"));
  }

  /** Small URL builder kept local so catalog tests do not depend on Android networking classes. */
  private static final class HttpUrlBuilder {
    static String query(String base, String... values) {
      StringBuilder out = new StringBuilder(base);
      out.append(base.contains("?") ? '&' : '?');
      for (int i = 0; i + 1 < values.length; i += 2) {
        if (i > 0) out.append('&');
        out.append(encode(values[i])).append('=').append(encode(values[i + 1]));
      }
      return out.toString();
    }

    private static String encode(String value) {
      try { return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
      catch (java.io.UnsupportedEncodingException error) { throw new IllegalStateException(error); }
    }
  }
}
