package com.yingxia.tv.core;

import java.io.IOException;
import java.util.*;
import org.json.*;

/** Java port of TVCore's NeteaseMusicCatalog, MusicBrowse and MusicNeteaseCollections.
 * Source: tvbox-silicon 030330f (GPL-3.0-only). GD supplies search/streams; NetEase supplies metadata.
 */
public final class GdMusicCatalog {
  public static final String API = "https://music-api.gdstudio.xyz/api.php";
  private static final String NETEASE = "https://music.163.com/api/";
  public static final int PAGE_SIZE = 20, TRACK_LIMIT = 500;

  public interface Transport {
    Object get(String url, Map<String, String> query, int timeout) throws Exception;
  }

  private final Transport transport;

  public GdMusicCatalog(Transport transport) { this.transport = transport; }

  private static Map<String, String> params(String... values) {
    Map<String, String> result = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i += 2) result.put(values[i], values[i + 1]);
    return result;
  }

  private JSONObject netease(String path, String... query) throws Exception {
    Object result = transport.get(NETEASE + path, params(query), 6000);
    if (!(result instanceof JSONObject)) throw new IOException("网易云返回格式无效");
    JSONObject json = (JSONObject) result;
    if (json.has("code") && json.optInt("code") != 200)
      throw new IOException("网易云返回 code " + json.optString("code"));
    return json;
  }

  public List<JSONObject> search(String query, int page) throws Exception {
    Object result = transport.get(API, params("types", "search", "source", "netease", "name", query,
        "count", "20", "pages", String.valueOf(Math.max(1, page))), 6000);
    List<JSONObject> songs = parseSearch(result);
    if (!songs.isEmpty()) {
      List<String> ids = new ArrayList<>();
      for (JSONObject song : songs) ids.add(song.optString("songId"));
      try {
        Map<String, JSONObject> details = index(detailTracks(ids));
        for (JSONObject song : songs) {
          JSONObject detail = details.get(song.optString("songId"));
          if (detail != null) {
            song.put("duration", detail.optLong("duration"));
            song.put("poster", detail.optString("poster"));
          }
        }
      } catch (Exception ignored) {
        // As in TVCore, optional enrichment must not discard a successful search.
      }
    }
    return songs;
  }

  public static List<JSONObject> parseSearch(Object response) throws IOException {
    if (!(response instanceof JSONArray)) {
      JSONObject error = response instanceof JSONObject ? (JSONObject) response : new JSONObject();
      throw new IOException("GD 搜索失败：" + Json.text(error, "error", Json.text(error, "msg", "返回格式无效")));
    }
    List<JSONObject> result = new ArrayList<>();
    for (JSONObject raw : Json.objects((JSONArray) response)) {
      if (value(raw, "id").isEmpty() || value(raw, "name").isEmpty()) continue;
      List<String> artists = new ArrayList<>();
      if (raw.opt("artist") instanceof JSONArray) {
        JSONArray names = raw.optJSONArray("artist");
        for (int i = 0; i < names.length(); i++) if (!names.isNull(i)) artists.add(names.optString(i));
      } else if (!value(raw, "artist").isEmpty()) artists.add(value(raw, "artist"));
      result.add(song(value(raw, "id"), value(raw, "name"), String.join(" / ", artists), value(raw, "album"))
          .putSafe("picId", value(raw, "pic_id")).putSafe("lyricId", value(raw, "lyric_id")).json);
    }
    return result;
  }

  // Build with Json.obj so Android's checked JSONException never hides malformed upstream rows.
  private static final class Song {
    final JSONObject json;
    Song(JSONObject json) { this.json = json; }
    Song putSafe(String key, Object value) {
      try { json.put(key, value); } catch (JSONException e) { throw new IllegalArgumentException(e); }
      return this;
    }
  }

  private static Song song(String id, String title, String artists, String album) {
    return new Song(Json.obj("id", "netease:" + id, "songId", id, "source", "netease", "music", true,
        "name", title, "artist", artists, "album", album, "lyricId", id));
  }

  private static String value(JSONObject json, String key) { return Json.text(json, key, ""); }

  public static List<JSONObject> parseTracks(JSONArray array) {
    List<JSONObject> result = new ArrayList<>();
    for (JSONObject raw : Json.objects(array)) {
      String id = value(raw, "id"), title = value(raw, "name");
      if (id.isEmpty() || title.isEmpty()) continue;
      JSONArray artists = raw.optJSONArray("ar");
      if (artists == null) artists = Json.array(raw.opt("artists"));
      List<String> names = new ArrayList<>();
      for (JSONObject artist : Json.objects(artists)) if (!value(artist, "name").isEmpty()) names.add(value(artist, "name"));
      JSONObject album = raw.optJSONObject("al");
      if (album == null) album = Json.child(raw, "album");
      result.add(song(id, title, String.join(" / ", names), value(album, "name"))
          .putSafe("duration", raw.optLong("dt", raw.optLong("duration")))
          .putSafe("poster", cover(value(album, "picUrl"))).json);
    }
    return result;
  }

  public JSONObject metadata(String songId) throws Exception {
    return index(detailTracks(Collections.singletonList(songId))).get(songId);
  }

  private List<JSONObject> detailTracks(List<String> ids) throws Exception {
    return parseTracks(Json.array(netease("song/detail/", "ids", "[" + String.join(",", ids) + "]").opt("songs")));
  }

  private static Map<String, JSONObject> index(List<JSONObject> songs) {
    Map<String, JSONObject> result = new LinkedHashMap<>();
    for (JSONObject song : songs) result.put(song.optString("songId"), song);
    return result;
  }

  private static String cover(String url) {
    if (url.isEmpty()) return "";
    return url.split("\\?", 2)[0] + "?param=300y300";
  }

  public static List<JSONObject> parseCollections(JSONArray array, String kind) {
    List<JSONObject> result = new ArrayList<>();
    boolean album = kind.equals("album");
    for (JSONObject raw : Json.objects(array)) {
      String id = value(raw, "id"), name = value(raw, "name");
      if (id.isEmpty() || name.isEmpty()) continue;
      result.add(Json.obj("id", "netease:" + kind + ":" + id, "collectionId", id, "source", "netease", "kind", kind,
          "name", name, "subtitle", value(Json.child(raw, album ? "artist" : "creator"), album ? "name" : "nickname"),
          "poster", cover(album ? value(raw, "picUrl") : Json.text(raw, "coverImgUrl", value(raw, "picUrl"))),
          "trackCount", raw.optInt(album ? "size" : "trackCount")));
    }
    return result;
  }

  public List<JSONObject> searchCollections(String query, String kind, int page) throws Exception {
    JSONObject result = netease("search/get/web", "s", query, "type", kind.equals("album") ? "10" : "1000",
        "limit", "20", "offset", String.valueOf((Math.max(1, page) - 1) * PAGE_SIZE));
    return parseCollections(Json.array(Json.child(result, "result").opt(kind.equals("album") ? "albums" : "playlists")), kind);
  }

  /** Every shelf fails independently, matching TVCore's optional browse requests. */
  public List<JSONObject> browse() throws Exception {
    List<JSONObject> sections = new ArrayList<>();
    List<JSONObject> more = new ArrayList<>();
    int answers = 0;
    try {
      JSONObject charts = netease("toplist");
      answers++;
      List<JSONObject> top = new ArrayList<>();
      for (JSONObject raw : Json.objects(Json.array(charts.opt("list")))) {
        List<JSONObject> items = parseCollections(new JSONArray().put(raw), "playlist");
        (value(raw, "ToplistType").isEmpty() ? more : top).addAll(items);
      }
      section(sections, "官方榜单", top);
    } catch (Exception ignored) { /* Other shelves can still be used. */ }
    try {
      JSONObject lists = netease("personalized/playlist", "limit", "30");
      answers++;
      section(sections, "推荐歌单", parseCollections(Json.array(lists.opt("result")), "playlist"));
    } catch (Exception ignored) { /* Other shelves can still be used. */ }
    try {
      JSONObject albums = netease("album/new", "area", "ALL", "offset", "0", "limit", "30", "total", "true");
      answers++;
      section(sections, "新碟上架", parseCollections(Json.array(albums.opt("albums")), "album"));
    } catch (Exception ignored) { /* Other shelves can still be used. */ }
    section(sections, "更多榜单", more);
    if (answers == 0) throw new IOException("音乐推荐暂时不可用，可以先搜索歌曲");
    return sections;
  }

  private static void section(List<JSONObject> sections, String title, List<JSONObject> items) {
    if (!items.isEmpty()) sections.add(Json.obj("name", title, "items", new JSONArray(items.subList(0, Math.min(60, items.size())))));
  }

  public List<JSONObject> tracks(JSONObject collection) throws Exception {
    String id = collection.optString("collectionId");
    if (!id.matches("[0-9]+")) throw new IOException("歌单或专辑 ID 无效");
    if (collection.optString("kind").equals("album")) {
      JSONObject result = netease("v1/album/" + id);
      JSONArray songs = result.optJSONArray("songs");
      return parseTracks(songs == null ? Json.array(Json.child(result, "album").opt("songs")) : songs);
    }
    JSONObject list = Json.child(netease("v6/playlist/detail", "id", id, "n", "1000"), "playlist");
    Map<String, JSONObject> songs = index(parseTracks(Json.array(list.opt("tracks"))));
    LinkedHashSet<String> order = new LinkedHashSet<>();
    for (JSONObject track : Json.objects(Json.array(list.opt("trackIds")))) {
      String trackId = value(track, "id");
      if (trackId.matches("[0-9]+")) order.add(trackId);
      if (order.size() == TRACK_LIMIT) break;
    }
    if (order.isEmpty()) order.addAll(songs.keySet());
    List<String> missing = new ArrayList<>();
    for (String trackId : order) if (!songs.containsKey(trackId)) missing.add(trackId);
    for (int start = 0; start < missing.size(); start += 200)
      songs.putAll(index(detailTracks(missing.subList(start, Math.min(missing.size(), start + 200)))));
    List<JSONObject> result = new ArrayList<>();
    for (String trackId : order) {
      if (songs.containsKey(trackId)) result.add(songs.get(trackId));
      if (result.size() == TRACK_LIMIT) break;
    }
    return result;
  }

  public JSONObject lyric(String songId) throws Exception {
    JSONObject response = netease("song/lyric", "id", songId, "lv", "1", "tv", "1");
    return Json.obj("lyric", value(Json.child(response, "lrc"), "lyric"),
        "translation", value(Json.child(response, "tlyric"), "lyric"));
  }

  public JSONObject resolve(String songId, String quality) throws Exception {
    Object result = transport.get(API, params("types", "url", "source", "netease", "id", songId,
        "br", MusicResolver.gdstudioBitrate(quality)), 8000);
    if (!(result instanceof JSONObject)) throw new IOException("GD 返回格式无效");
    return parseStream((JSONObject) result);
  }

  public static JSONObject parseStream(JSONObject response) throws IOException {
    String url = value(response, "url").trim();
    try {
      java.net.URI uri = new java.net.URI(url);
      if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null)
        throw new IllegalArgumentException();
    } catch (Exception error) {
      throw new IOException("GD：" + Json.text(response, "error", Json.text(response, "msg", "该歌曲暂无可用音源")));
    }
    return Json.obj("url", url, "upstream", "gdstudio", "actualQuality", value(response, "br"));
  }
}
