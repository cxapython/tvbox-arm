package com.yingxia.tv.core;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.*;
import org.json.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;

/** Musify's public song catalog and short-lived streaming URLs. No account is required. */
public final class MusifyCatalog {
  public static final String BASE = "https://musify.club";
  public static final String USER_AGENT =
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

  public interface Transport {
    String get(String url) throws Exception;
  }

  private final Transport transport;

  public MusifyCatalog(Transport transport) { this.transport = transport; }

  public JSONObject searchPage(String query, int page) throws Exception {
    String url = searchUrl(query, page);
    return parsePage(transport.get(url), url);
  }

  public static String searchUrl(String query, int page) throws IOException {
    String value = query.trim();
    int selected = Math.max(1, page);
    if (value.startsWith("https://") || value.startsWith("http://")) {
      try {
        URI uri = URI.create(value);
        String path = uri.getPath().replaceAll("/+$", "");
        if (!"musify.club".equalsIgnoreCase(uri.getHost()) || uri.getUserInfo() != null
            || !path.matches("/(?:[a-z]{2}/)?artist/[a-zA-Z0-9_-]+(?:/tracks)?"))
          throw new IllegalArgumentException();
        return BASE + path + (path.endsWith("/tracks") ? "" : "/tracks") + (selected > 1 ? "/page" + selected : "");
      } catch (IllegalArgumentException error) {
        throw new IOException("请粘贴 Musify 歌手页链接，或输入歌曲、歌手名称");
      }
    }
    return BASE + "/en/search?searchText=" + URLEncoder.encode(value, "UTF-8") + "&type=song&page=" + selected;
  }

  public static JSONObject parsePage(String html, String url) throws IOException {
    Document doc = Jsoup.parse(html, url);
    if (doc.select(".tracklist, [data-search-instant-form]").isEmpty())
      throw new IOException("Musify 未返回歌曲列表，请稍后重试");
    JSONArray items = new JSONArray();
    Set<String> seen = new HashSet<>();
    for (Element row : doc.select(".tracklist__row[data-song-id]")) {
      String id = row.attr("data-song-id"), name = row.attr("data-name");
      if (!id.matches("[0-9]+") || name.isEmpty() || !seen.add(id)) continue;
      Element duration = row.selectFirst("[data-duration]");
      Element art = row.selectFirst("[data-art]");
      Element link = row.selectFirst(".tracklist__title a[href]");
      items.put(Json.obj("music", true, "source", "musify", "id", "musify:" + id, "songId", id,
          "name", name, "artist", row.attr("data-artist"), "album", row.attr("data-album"),
          "duration", duration == null ? 0 : durationMillis(duration.attr("data-duration")),
          "poster", art == null ? "" : art.absUrl("data-art"),
          "pageUrl", link == null ? "" : link.absUrl("href"),
          "restricted", row.attr("data-copyrighted").equals("true"), "fixedQuality", true));
    }
    Element next = doc.selectFirst("a.pagination-next[href]");
    boolean hasNext = next != null && !next.attr("href").isEmpty() && !next.attr("href").equals("#")
        && !next.hasClass("pointer-events-none") && !next.hasAttr("disabled")
        && !next.attr("aria-disabled").equals("true");
    return Json.obj("items", items, "hasNext", hasNext);
  }

  private static long durationMillis(String value) {
    if (!value.matches("[0-9]+(?::[0-5][0-9]){1,2}")) return 0;
    try {
      long seconds = 0;
      for (String part : value.split(":")) seconds = Math.addExact(Math.multiplyExact(seconds, 60), Long.parseLong(part));
      return Math.multiplyExact(seconds, 1000);
    } catch (ArithmeticException | NumberFormatException error) { return 0; }
  }

  public JSONObject resolve(JSONObject song, String quality) throws Exception {
    String id = song.optString("songId");
    if (!id.matches("[0-9]+")) throw new IOException("Musify 歌曲编号无效，请重新搜索");
    JSONObject response;
    try { response = new JSONObject(transport.get(BASE + "/api/track/" + id + "/stream-url")); }
    catch (JSONException error) { throw new IOException("Musify 播放接口返回格式无效", error); }
    String url = response.optString("url");
    if (!isMediaUrl(url)) throw new IOException(response.has("error") ? errorMessage(0, response.toString()) : "Musify 未返回有效音频地址");
    return Json.obj("url", url, "upstream", "musify", "fixedQuality", true,
        "header", Json.obj("User-Agent", USER_AGENT, "Referer", BASE + "/"));
  }

  public static boolean isMediaUrl(String url) {
    try {
      URI uri = URI.create(url);
      String host = uri.getHost();
      return "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null && host != null
          && (host.equalsIgnoreCase("musify.club") || host.toLowerCase(Locale.ROOT).endsWith(".musify.club"));
    } catch (IllegalArgumentException error) { return false; }
  }

  public static String errorMessage(int status, String body) {
    String reason = Json.object(body).optString("error");
    if (reason.equals("copyrighted")) return "Musify：这首歌曲受站点访问限制，请选择其他歌曲";
    if (status == 429 || reason.equals("quota")) return "Musify：访问次数达到站点限制，请稍后再试";
    if (status == 401 || status == 403) return "Musify：这首歌曲或当前访问需要站点授权";
    return "Musify 暂时无法提供音频" + (status > 0 ? "（HTTP " + status + "）" : "");
  }
}
