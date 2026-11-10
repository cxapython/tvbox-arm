package com.yingxia.tv.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import org.jsoup.parser.Parser;

/** TVCore QQMusicCatalog, MusicCollectionCatalogs, MusicBrowse and MusicMiguKuwo,
 * translated from tvbox-silicon 030330f (GPL-3.0-only). */
public final class NativeMusicCatalog {
  public interface Transport {
    JSONObject request(String url, String method, Map<String, String> query, Map<String, String> headers, JSONObject body) throws Exception;
  }
  private final Transport transport;
  private static final String QQ = "https://u.y.qq.com/cgi-bin/musicu.fcg";
  private static final String MIGU_DEVICE = "963B7AA0D21511ED807EE5846EC87D20";
  private static final String MIGU_SIGNING = "6cdc72a439cef99a3418d2a78aa28c73yyapp2d16148780a1dcc7408e06336b98cfd50";

  public NativeMusicCatalog(Transport transport) { this.transport = transport; }

  public static Map<String, String> params(String... pairs) {
    Map<String, String> values = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) values.put(pairs[i], pairs[i + 1]);
    return values;
  }

  private static String text(JSONObject json, String... keys) {
    for (String key : keys) if (!json.isNull(key) && !json.optString(key).isEmpty()) return json.optString(key);
    return "";
  }

  private static String plain(String text) {
    return Parser.unescapeEntities(text.replaceAll("<[^>]+>", ""), false).trim();
  }

  private static String artists(JSONArray array) {
    List<String> names = new ArrayList<>();
    for (JSONObject item : Json.objects(array)) {
      String name = plain(text(item, "name"));
      if (!name.isEmpty()) names.add(name);
    }
    return String.join(" / ", names);
  }

  private static JSONObject song(String source, String id, String name, String artist, String album, long duration, String poster) {
    return Json.obj("id", source + ":" + id, "songId", id, "source", source, "music", true,
        "name", plain(name), "artist", artist, "album", plain(album), "duration", duration, "poster", poster, "lyricId", id);
  }

  private JSONObject qq(String module, String method, JSONObject param) throws Exception {
    JSONObject json = transport.request(QQ, "POST", params(), params("Referer", "https://y.qq.com/"),
        Json.obj("comm", Json.obj("ct", 11, "cv", "1003006", "uin", "0"), "req", Json.obj("module", module, "method", method, "param", param)));
    JSONObject req = Json.child(json, "req");
    int code = req.optInt("code", json.optInt("code", -1));
    if (code != 0) throw new IOException("QQ 音乐 " + method + " 返回 code " + code);
    return Json.child(req, "data");
  }

  private JSONObject qqSearch(String query, int page, int type) throws Exception {
    return qq("music.search.SearchCgiService", "DoSearchForQQMusicMobile", Json.obj("query", query, "num_per_page", 20,
        "page_num", Math.max(1, page), "search_type", type, "searchid", String.valueOf(1_000_000_000L + new Random().nextInt(Integer.MAX_VALUE))));
  }

  public List<JSONObject> search(String source, String query, int page) throws Exception {
    switch (source) {
      case "tencent":
      case "qq": {
        JSONObject body = Json.child(qqSearch(query, page, 0), "body");
        JSONArray rows = body.optJSONArray("item_song");
        return qqTracks(rows == null ? Json.array(Json.child(body, "song").opt("list")) : rows);
      }
      case "migu": {
        String time = String.valueOf(System.currentTimeMillis());
        JSONObject response = transport.request("https://jadeite.migu.cn/music_search/v3/search/searchAll", "GET",
            params("isCorrect", "0", "isCopyright", "1", "searchSwitch", "{\"song\":1,\"album\":0,\"singer\":0,\"tagSong\":1,\"mvSong\":0,\"bestShow\":1,\"songlist\":0,\"lyricSong\":0}",
                "pageSize", "20", "text", query, "pageNo", String.valueOf(Math.max(1, page)), "sort", "0", "sid", "USS"),
            params("uiVersion", "A_music_3.6.1", "deviceId", MIGU_DEVICE, "timestamp", time, "sign", miguSignature(query, time), "channel", "0146921",
                "User-Agent", "Mozilla/5.0 (Linux; U; Android 11.0.0; zh-cn; MI 11 Build/OPR1.170623.032) AppleWebKit/534.30 (KHTML, like Gecko) Version/4.0 Mobile Safari/534.30"), null);
        return miguTracks(response);
      }
      case "kuwo":
        return kuwoTracks(transport.request("https://search.kuwo.cn/r.s", "GET",
            params("client", "kt", "all", query, "pn", String.valueOf(Math.max(0, page - 1)), "rn", "20", "uid", "2574109560",
                "ver", "kwplayer_ar_8.5.4.2", "vipver", "1", "ft", "music", "cluster", "0", "strategy", "2012", "encoding", "utf8", "rformat", "json", "vermerge", "1", "mobi", "1"), params(), null));
      default: throw new IOException("不支持的音乐平台：" + source);
    }
  }

  public static String miguSignature(String query, String time) throws Exception {
    byte[] digest = MessageDigest.getInstance("MD5").digest((query + MIGU_SIGNING + MIGU_DEVICE + time).getBytes(StandardCharsets.UTF_8));
    StringBuilder result = new StringBuilder();
    for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 255));
    return result.toString();
  }

  public static List<JSONObject> miguTracks(JSONObject response) throws IOException {
    if (!response.optString("code").equals("000000")) throw new IOException("咪咕搜索返回 code " + response.optString("code"));
    List<JSONObject> result = new ArrayList<>();
    JSONArray groups = Json.array(Json.child(response, "songResultData").opt("resultList"));
    for (int i = 0; i < groups.length(); i++) for (JSONObject raw : Json.objects(Json.array(groups.opt(i)))) {
      String content = text(raw, "contentId"), copyright = text(raw, "copyrightId"), title = text(raw, "name", "songName");
      if (content.isEmpty() || copyright.isEmpty() || title.isEmpty()) continue;
      String picture = text(raw, "img3", "img2", "img1");
      if (!picture.isEmpty() && !picture.startsWith("http")) picture = "https://d.musicapp.migu.cn" + picture;
      result.add(song("migu", content + "_" + copyright, title, artists(Json.array(raw.opt("singerList"))), text(raw, "album"),
          Math.round(raw.optDouble("duration", 0) * 1000), picture));
    }
    return result;
  }

  public static List<JSONObject> kuwoTracks(JSONObject response) throws IOException {
    JSONArray rows = response.optJSONArray("abslist");
    if (rows == null) throw new IOException("酷我搜索未返回歌曲列表");
    List<JSONObject> result = new ArrayList<>();
    for (JSONObject raw : Json.objects(rows)) {
      String id = text(raw, "MUSICRID").replace("MUSIC_", ""), title = text(raw, "NAME");
      if (id.isEmpty() || title.isEmpty()) continue;
      String picture = text(raw, "web_albumpic_short");
      int slash = picture.indexOf('/');
      picture = slash < 0 ? "" : "https://img4.kuwo.cn/star/albumcover/500" + picture.substring(slash);
      List<String> singers = new ArrayList<>();
      for (String singer : plain(text(raw, "ARTIST")).split("&")) if (!singer.trim().isEmpty()) singers.add(singer.trim());
      result.add(song("kuwo", id, title, String.join(" / ", singers), text(raw, "ALBUM"),
          Math.round(raw.optDouble("DURATION", 0) * 1000), picture));
    }
    return result;
  }

  public static List<JSONObject> qqTracks(JSONArray rows) {
    List<JSONObject> result = new ArrayList<>();
    for (JSONObject raw : Json.objects(rows)) {
      String id = text(raw, "mid"), title = text(raw, "title", "name");
      if (id.isEmpty() || title.isEmpty()) continue;
      JSONObject album = Json.child(raw, "album");
      result.add(song("tencent", id, title, artists(Json.array(raw.opt("singer"))), text(album, "name"),
          Math.round(raw.optDouble("interval", 0) * 1000), qqCover(text(album, "mid"))));
    }
    return result;
  }

  private static String qqCover(String mid) { return mid.isEmpty() ? "" : "https://y.gtimg.cn/music/photo_new/T002R300x300M000" + mid + ".jpg"; }

  private static JSONObject collection(String id, String kind, String title, String subtitle, String poster, int count) {
    return Json.obj("id", "tencent:" + kind + ":" + id, "collectionId", id, "source", "tencent", "kind", kind,
        "name", plain(title), "subtitle", plain(subtitle), "poster", poster, "trackCount", count);
  }

  public List<JSONObject> searchCollections(String query, String kind, int page) throws Exception {
    return qqCollections(Json.child(qqSearch(query, page, kind.equals("album") ? 2 : 3), "body"), kind);
  }

  public static List<JSONObject> qqCollections(JSONObject body, String kind) {
    List<JSONObject> result = new ArrayList<>();
    boolean album = kind.equals("album");
    for (JSONObject raw : Json.objects(Json.array(body.opt(album ? "item_album" : "item_songlist")))) {
      String id = album ? text(raw, "albummid", "albumMID") : text(raw, "dissid");
      String title = album ? text(raw, "name", "albumName") : text(raw, "dissname");
      if (id.isEmpty() || title.isEmpty()) continue;
      String singers = artists(Json.array(raw.opt("singer_list")));
      result.add(collection(id, kind, title, album ? (singers.isEmpty() ? text(raw, "singer") : singers) : text(raw, "nickname"),
          album ? qqCover(id) : text(raw, "logo", "imgurl"), album ? raw.optInt("song_num") : raw.optInt("songnum", raw.optInt("song_count"))));
    }
    return result;
  }

  public List<JSONObject> tracks(JSONObject collection) throws Exception {
    String id = collection.optString("collectionId");
    if (id.startsWith("toplist:")) {
      int chart = Integer.parseInt(id.substring("toplist:".length()));
      return qqTracks(Json.array(qq("musicToplist.ToplistInfoServer", "GetDetail", Json.obj("topId", chart, "offset", 0, "num", 500, "period", "")).opt("songInfoList")));
    }
    if (collection.optString("kind").equals("album")) {
      List<JSONObject> result = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      while (result.size() < 500) {
        JSONObject data = qq("music.musichallAlbum.AlbumSongList", "GetAlbumSongList", Json.obj("albumMid", id, "begin", result.size(), "num", 100, "order", 2));
        JSONArray rows = new JSONArray();
        for (JSONObject row : Json.objects(Json.array(data.opt("songList")))) rows.put(Json.child(row, "songInfo"));
        List<JSONObject> page = qqTracks(rows);
        int before = result.size();
        for (JSONObject song : page) if (result.size() < 500 && seen.add(song.optString("id"))) result.add(song);
        if (before == result.size() || result.size() >= data.optInt("totalNum", result.size())) break;
      }
      return result;
    }
    return qqTracks(Json.array(qq("music.srfDissInfo.aiDissInfo", "uniform_get_Dissinfo", Json.obj("disstid", Long.parseLong(id),
        "userinfo", 1, "tag", 1, "is_pc", 1, "orderlist", 1, "song_begin", 0, "song_num", 500, "onlysonglist", 0)).opt("songlist")));
  }

  public List<JSONObject> browse() throws Exception {
    List<JSONObject> result = new ArrayList<>(), more = new ArrayList<>();
    int answered = 0;
    try {
      JSONObject charts = qq("musicToplist.ToplistInfoServer", "GetAll", Json.obj());
      answered++;
      List<JSONObject> top = new ArrayList<>();
      for (JSONObject group : Json.objects(Json.array(charts.opt("group")))) {
        for (JSONObject chart : Json.objects(Json.array(group.opt("toplist")))) {
          String id = text(chart, "topId"), title = text(chart, "title");
          if (id.isEmpty() || title.isEmpty()) continue;
          (group.optInt("groupId", -1) == 0 ? top : more).add(collection("toplist:" + id, "playlist", title, "",
              text(chart, "mbFrontPicUrl", "frontPicUrl", "headPicUrl"), chart.optInt("totalNum")));
        }
      }
      section(result, "巅峰榜", top);
    } catch (Exception ignored) { /* Preserve the other shelves. */ }
    try {
      JSONObject lists = qq("playlist.PlayListPlazaServer", "get_playlist_by_tag", Json.obj("id", 10000000, "sin", 0, "size", 30, "order", 5, "cur_page", 1));
      answered++;
      List<JSONObject> items = new ArrayList<>();
      for (JSONObject raw : Json.objects(Json.array(lists.opt("v_playlist")))) {
        String id = text(raw, "tid"), title = text(raw, "title");
        if (id.isEmpty() || id.equals("0") || title.isEmpty()) continue;
        items.add(collection(id, "playlist", title, text(Json.child(raw, "creator_info"), "nick"),
            text(raw, "cover_url_medium", "cover_url_big", "cover_url_small"), Json.array(raw.opt("song_ids")).length()));
      }
      section(result, "推荐歌单", items);
    } catch (Exception ignored) { /* Preserve the other shelves. */ }
    try {
      JSONObject albums = qq("newalbum.NewAlbumServer", "get_new_album_info", Json.obj("area", 1, "sin", 0, "num", 30));
      answered++;
      List<JSONObject> items = new ArrayList<>();
      for (JSONObject raw : Json.objects(Json.array(albums.opt("albums")))) {
        String mid = text(raw, "mid"), name = text(raw, "name");
        if (!mid.isEmpty() && !name.isEmpty()) items.add(collection(mid, "album", name, artists(Json.array(raw.opt("singers"))), qqCover(mid), 0));
      }
      section(result, "新碟上架", items);
    } catch (Exception ignored) { /* Preserve the other shelves. */ }
    section(result, "更多榜单", more);
    if (answered == 0) throw new IOException("QQ 音乐推荐暂时不可用，可以先搜索歌曲");
    return result;
  }

  private static void section(List<JSONObject> result, String name, List<JSONObject> rows) {
    if (!rows.isEmpty()) result.add(Json.obj("name", name, "items", new JSONArray(rows.subList(0, Math.min(60, rows.size())))));
  }

  public JSONObject lyrics(String mid) throws Exception {
    JSONObject response = transport.request("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg", "GET",
        params("songmid", mid, "format", "json", "nobase64", "1"), params("Referer", "https://y.qq.com/"), null);
    if (response.optInt("retcode", response.optInt("code", 0)) != 0) return Json.obj("lyric", "");
    return Json.obj("lyric", Parser.unescapeEntities(text(response, "lyric"), false),
        "translation", Parser.unescapeEntities(text(response, "trans"), false));
  }
}
