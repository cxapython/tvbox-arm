package com.yingxia.tv.data;

import android.util.Log;
import com.yingxia.tv.*;
import com.yingxia.tv.core.*;
import com.yingxia.tv.runtime.ScriptRuntime;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Catalog search plus ordered Migu/Kuwo/QQ/NetEase/LX playback resolvers. */
public final class MusicClient {
  private final YingxiaApp app = YingxiaApp.get();
  private final Http http = app.http;

  public final GdMusicCatalog gd = new GdMusicCatalog(this::musicJson);
  public final NativeMusicCatalog nativeCatalog = new NativeMusicCatalog(this::catalogJson);
  public final MusifyCatalog musify = new MusifyCatalog(this::musifyText);

  private String musifyText(String url) throws Exception {
    Http.Result response = http.request(url, "GET",
        Http.params("User-Agent", MusifyCatalog.USER_AGENT, "Referer", MusifyCatalog.BASE + "/",
            "X-Requested-With", "fetch", "Cache-Control", "no-cache"),
        null, 3_000_000, true, 15000);
    if (response.status < 200 || response.status >= 300)
      throw new IOException(MusifyCatalog.errorMessage(response.status, response.text()));
    return response.text();
  }

  private JSONObject catalogJson(String url, String method, Map<String, String> query,
      Map<String, String> extraHeaders, JSONObject body) throws Exception {
    Map<String, String> headers = Http.params("User-Agent", Http.UA, "Cache-Control", "no-cache");
    headers.putAll(extraHeaders);
    if (body != null) headers.put("Content-Type", "application/json");
    Http.Result response = http.request(Http.query(url, query), method, headers,
        body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8), 2_000_000, true, 6000);
    Http.check(response);
    return response.json();
  }

  public static boolean hasCollections(String source) { return source.equals("netease") || source.equals("tencent") || source.equals("qq"); }

  public static String platformName(String source) {
    switch (source) {
      case "netease": return "GD 音乐台 · 网易云";
      case "tencent": case "qq": return "QQ 音乐";
      case "kuwo": return "酷我音乐";
      case "migu": return "咪咕音乐";
      case "kugou": return "酷狗音乐";
      case "musify": return "Musify";
      default: return "音乐";
    }
  }

  public List<JSONObject> browse(String source) throws Exception {
    return source.equals("netease") ? gd.browse() : nativeCatalog.browse();
  }

  public List<JSONObject> collections(String query, String source, String kind, int page) throws Exception {
    return source.equals("netease") ? gd.searchCollections(query, kind, page) : nativeCatalog.searchCollections(query, kind, page);
  }

  public List<JSONObject> tracks(JSONObject collection) throws Exception {
    return collection.optString("source", "netease").equals("netease") ? gd.tracks(collection) : nativeCatalog.tracks(collection);
  }


  private static synchronized void admitGd() throws Exception {
    admitService("gd", 50, "GD");
  }

  private static synchronized void admitService(String service, int limit, String label) throws Exception {
    android.content.SharedPreferences settings = YingxiaApp.get().repository.settings;
    String key = "music." + service + ".requests";
    JSONArray recent = MusicRequestBudget.admit(Json.array(settings.getString(key, "[]")), System.currentTimeMillis(), limit, label);
    settings.edit().putString(key, recent.toString()).apply();
  }

  private Object musicJson(String url, Map<String, String> query, int timeout) throws Exception {
    if (url.equals(GdMusicCatalog.API)) admitGd();
    Http.Result result = http.request(Http.query(url, query), "GET",
        Http.params("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Safari/537.36",
            "Referer", url.equals(GdMusicCatalog.API) ? "https://music.gdstudio.xyz/" : "https://music.163.com/",
            "Cache-Control", "no-cache"), null, 2_000_000, true, timeout);
    Http.check(result);
    try { return new JSONTokener(result.text()).nextValue(); }
    catch (JSONException error) { throw new IOException("音乐接口返回格式无效", error); }
  }

  public List<JSONObject> search(String query, String source, int page) throws Exception {
    if (source.equals("musify")) return Json.objects(musify.searchPage(query, page).getJSONArray("items"));
    if (source.equals("netease")) return gd.search(query, page);
    if (Arrays.asList("tencent", "qq", "migu", "kuwo").contains(source)) return nativeCatalog.search(source, query, page);
    String url =
        Http.query(
            "https://music-api.gdstudio.xyz/api.php",
            Http.params(
                "types", "search", "source", source, "name", query, "count", "20", "pages",
                "" + page));
    admitGd();
    JSONArray songs = new JSONArray(http.get(url).text());
    List<JSONObject> out = new ArrayList<>();
    for (JSONObject song : Json.objects(songs))
      out.add(
          Json.obj(
              "music", true,
              "id",
              source + ":" + song.optString("id"),
              "songId",
              song.optString("id"),
              "source",
              source,
              "name",
              song.optString("name"),
              "artist",
              song.opt("artist") instanceof JSONArray
                  ? String.join(" / ", strings(Json.array(song.opt("artist"))))
                  : song.optString("artist"),
              "album",
              song.optString("album"),
              "picId",
              song.optString("pic_id"),
              "lyricId",
              song.optString("lyric_id")));
    return out;
  }

  /** Uses the saved tier on renewal, and 320 kbps for a new song. */
  public JSONObject play(JSONObject song) throws Exception {
    return play(song, song.optString("quality", MusicResolver.HIGH));
  }

  public JSONObject play(JSONObject song, int quality) throws Exception {
    return play(song, MusicResolver.normalizeQuality(quality));
  }

  public JSONObject play(JSONObject song, String quality) throws Exception {
    String normalized = MusicResolver.normalizeQuality(quality);
    if (song.optString("source").equals("musify")) return playDirect(song, normalized);
    try {
      return playDirect(song, normalized);
    } catch (Exception first) {
      // TVCore MusicResolution's companion catalogs: only the same recording can replace a
      // failing channel. Keep the original library identity while renewing the actual source ID.
      long deadline = System.nanoTime() + 30_000_000_000L;
      List<String> failures = new ArrayList<>();
      failures.add(Json.message(first));
      for (String platform : Arrays.asList("migu", "kuwo", "netease", "tencent")) {
        if (platform.equals(song.optString("source")) || System.nanoTime() >= deadline) continue;
        try {
          List<JSONObject> matches = search(song.optString("name") + " " + song.optString("artist"), platform, 1);
          for (JSONObject candidate : matches) {
            if (!sameRecording(song, candidate)) continue;
            JSONObject resolved = playDirect(candidate, normalized);
            resolved.put("id", song.optString("id"))
                .put("catalogSource", song.optString("catalogSource", song.optString("source")))
                .put("catalogSongId", song.optString("catalogSongId", song.optString("songId")))
                .put("sourceFallback", true);
            return resolved;
          }
        } catch (Exception error) { failures.add(platformName(platform) + ": " + Json.message(error)); }
      }
      throw new IOException(String.join("；", failures));
    }
  }

  private JSONObject playDirect(JSONObject song, String quality) throws Exception {
    JSONObject stream = new MusicResolver(providers(song.optString("source"))).resolve(song, quality);
    JSONObject play = Json.copy(song);
    for (String key : Json.keys(stream)) play.put(key, stream.opt(key));
    play.put("quality", quality).put("music", true).put("episode", song.optString("artist"));
    loadCover(song, play);
    return play;
  }

  public String lyrics(JSONObject song) throws Exception {
    return lyricData(song).optString("lyric");
  }

  public JSONObject lyricData(JSONObject song) throws Exception {
    String source = song.optString("source");
    if (source.equals("musify")) return Json.obj("lyric", "", "translation", "");
    String catalog = song.optString("catalogSource");
    if (catalog.equals("tencent") || catalog.equals("qq")) return nativeCatalog.lyrics(song.optString("catalogSongId"));
    if (catalog.equals("netease")) return gd.lyric(song.optString("catalogSongId"));
    if (source.equals("netease")) return gd.lyric(song.optString("songId"));
    if (source.equals("tencent") || source.equals("qq")) return nativeCatalog.lyrics(song.optString("songId"));
    if (source.equals("migu") || source.equals("kuwo")) {
      // TVCore loads lyrics from a matching NetEase/QQ recording. Never use just the title:
      // artist and duration must agree, otherwise show no lyrics rather than another version.
      for (JSONObject match : gd.search(song.optString("name") + " " + song.optString("artist"), 1))
        if (sameRecording(song, match)) return gd.lyric(match.optString("songId"));
      return Json.obj("lyric", "", "translation", "");
    }
    Object result = musicJson(GdMusicCatalog.API, Http.params("types", "lyric", "source",
        song.optString("source"), "id", song.optString("lyricId", song.optString("songId"))), 6000);
    if (!(result instanceof JSONObject)) throw new IOException("歌词返回格式无效");
    JSONObject json = (JSONObject) result;
    return Json.obj("lyric", json.optString("lyric"), "translation", json.optString("tlyric"));
  }

  private static boolean sameRecording(JSONObject a, JSONObject b) {
    return MusicRecordingMatch.same(a, b);
  }

  private interface Call {
    JSONObject run(JSONObject song, String quality) throws Exception;
  }

  private static MusicResolver.Provider provider(String name, Call call) {
    return new MusicResolver.Provider() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public JSONObject resolve(JSONObject song, String quality) throws Exception {
        return call.run(song, quality);
      }
    };
  }

  private List<MusicResolver.Provider> providers(String platform) {
    if (platform.equals("musify")) return Collections.singletonList(provider("Musify", musify::resolve));
    List<MusicResolver.Provider> out = new ArrayList<>();
    switch (sourceKey(platform)) {
      case "mg":
        out.add(provider("migu", this::migu));
        break;
      case "kw":
        out.add(provider("nianxin/kuwo", this::nianxin));
        out.add(provider("xinghai/kuwo", this::xinghai));
        break;
      case "tx":
        out.add(provider("vkeys/qq", this::vkeys));
        break;
      case "kg":
        out.add(provider("gdstudio/kugou", this::gdstudio));
        break;
      default:
        out.add(provider("gdstudio/netease", this::gdstudio));
        out.add(provider("nianxin/netease", this::nianxin));
        out.add(provider("xinghai/netease", this::xinghai));
        break;
    }
    out.add(provider("lx", this::lx));
    return out;
  }

  private static String sourceKey(String platform) {
    switch (platform == null ? "" : platform.toLowerCase(Locale.ROOT)) {
      case "migu":
      case "mg":
        return "mg";
      case "kuwo":
      case "kw":
        return "kw";
      case "qq":
      case "tencent":
      case "tx":
        return "tx";
      case "kugou":
      case "kg":
        return "kg";
      default:
        return "wy";
    }
  }

  private static String lxSourceKey(String platform) {
    return sourceKey(platform);
  }

  private static JSONObject stream(String url, String upstream, String quality) throws Exception {
    if (url == null || url.trim().isEmpty()) throw new IOException("未返回播放地址");
    return Json.obj("url", url.trim(), "upstream", upstream, "actualQuality", quality);
  }

  private JSONObject gdstudio(JSONObject song, String quality) throws Exception {
    if (song.optString("source").equals("netease")) return gd.resolve(song.optString("songId"), quality);
    Object result = musicJson(GdMusicCatalog.API, Http.params("types", "url", "source", song.optString("source"),
        "id", song.optString("songId"), "br", MusicResolver.gdstudioBitrate(quality)), 8000);
    if (!(result instanceof JSONObject)) throw new IOException("GD 返回格式无效");
    return GdMusicCatalog.parseStream((JSONObject) result);
  }

  private JSONObject providerJson(String url) throws Exception {
    Http.Result response = http.request(url, "GET", Http.params("Cache-Control", "no-cache"), null, 2_000_000, true, 8000);
    Http.check(response);
    return response.json();
  }

  private JSONObject vkeys(JSONObject song, String quality) throws Exception {
    long now = System.currentTimeMillis();
    if (app.repository.settings.getLong("music.vkeys.retryAfter", 0) > now)
      throw new IOException("QQ 音源上游风控，暂时冷却");
    JSONObject response =
        providerJson(
            Http.query(
                "https://api.vkeys.cn/music/tencent/song/link",
                Http.params(
                    "mid", song.optString("songId"), "quality", MusicResolver.vkeysCode(quality))));
    if (response.optInt("code", -1) != 0) {
      String message = response.optString("message", "无可用资源");
      if (message.contains("风控") || message.contains("風控"))
        app.repository.settings.edit().putLong("music.vkeys.retryAfter", now + 600_000).apply();
      throw new IOException("VKeys: " + message);
    }
    return stream(
        Json.child(response, "data").optString("url"),
        "vkeys",
        Json.child(response, "data").optString("kbps", quality));
  }

  private JSONObject nianxin(JSONObject song, String quality) throws Exception {
    String key = sourceKey(song.optString("source"));
    String base = "https://mcp.nianxinxz.com/share/ceshi/" + (key.equals("kw") ? "kw.php" : "wy.php");
    List<String> failures = new ArrayList<>();
    for (String level : MusicResolver.nianxinLevels(quality)) {
      try {
        JSONObject response =
            providerJson(Http.query(base, Http.params("id", song.optString("songId"), "level", level)));
        if (response.optInt("code", -1) != 200)
          throw new IOException("Nianxin code " + response.optString("code"));
        JSONObject data = Json.child(response, "data");
        String url = data.optString("url", response.optString("url"));
        return stream(url, "nianxin", level);
      } catch (Exception error) {
        failures.add(level + ": " + Json.message(error));
      }
    }
    throw new IOException("Nianxin 各音质均失败: " + String.join("；", failures));
  }

  private JSONObject xinghai(JSONObject song, String quality) throws Exception {
    admitService("xinghai", 30, "星海");
    boolean kuwo = sourceKey(song.optString("source")).equals("kw");
    String tier = MusicResolver.normalizeQuality(quality);
    String level = kuwo ? (tier.equals(MusicResolver.FLAC) ? "flac" : tier + "k")
        : tier.equals(MusicResolver.FLAC) ? "lossless" : tier.equals(MusicResolver.HIGH) ? "exhigh" : "standard";
    JSONObject response = catalogJson("https://yy.zddyr.top/lx/api/", "GET",
        Http.params("source", kuwo ? "kw" : "netease", "name", "", "songmid", song.optString("songId"), "quality", level),
        Collections.emptyMap(), null);
    if (response.optInt("code") != 200) throw new IOException("星海暂无可用音源");
    return stream(Json.text(response, "url", Json.child(response, "data").optString("url")), "xinghai", response.optString("quality", level));
  }

  private static final class MiguTier {
    final String name, path, extension;

    MiguTier(String name, String path, String extension) {
      this.name = name;
      this.path = path;
      this.extension = extension;
    }
  }

  private static final MiguTier[] MIGU_TIERS = {
    new MiguTier("SQ", "歌曲下载/flac", ".flac"),
    new MiguTier("HQ", "标清高清/MP3_320_16_Stero", ".mp3"),
    new MiguTier("PQ", "标清高清/MP3_128_16_Stero", ".mp3")
  };

  private JSONObject migu(JSONObject song, String quality) throws Exception {
    String[] ids = song.optString("songId").split("_", 2);
    String content = ids[0], copyright = ids.length > 1 ? ids[1] : ids[0];
    if (content.isEmpty() || copyright.isEmpty()) throw new IOException("Migu 歌曲 ID 无效");
    String address =
        Http.query(
            "https://c.musicapp.migu.cn/strategy/listen-url/h5/v2.4",
            Http.params(
                "contentId", content,
                "copyrightId", copyright,
                "resourceType", "2",
                "netType", "01",
                "toneFlag", "PQ",
                "scene", "",
                "lowerQualityContentId", content));
    Http.Result response =
        http.request(
            address,
            "GET",
            Http.params(
                "birth", "h5page",
                "channel", "014X031",
                "Referer", "https://y.migu.cn/",
                "location-data", "30.6698676660,104.1229614820"),
            null,
            2_000_000,
            true,
            8000);
    Http.check(response);
    String base = miguAddress(miguJson(response.bytes));
    for (MiguTier tier : miguTiers(quality)) {
      String candidate = miguRetone(base, tier);
      if (candidate != null && miguExists(candidate)) return stream(candidate, "migu", tier.name);
    }
    return stream(base, "migu", "PQ");
  }

  private static List<MiguTier> miguTiers(String quality) {
    switch (MusicResolver.normalizeQuality(quality)) {
      case MusicResolver.FLAC:
        return Arrays.asList(MIGU_TIERS[0], MIGU_TIERS[1]);
      case MusicResolver.HIGH:
        return Collections.singletonList(MIGU_TIERS[1]);
      default:
        return Collections.emptyList();
    }
  }

  private static JSONObject miguJson(byte[] bytes) throws Exception {
    if (bytes.length <= 4 || (bytes[0] & 0xff) != 0xab || (bytes[1] & 0xff) != 0xcd || bytes[2] != 1)
      throw new IOException("Migu 返回格式无效");
    byte[] key = "Jk8qzuePiJ1qE3mDYhLQ3T73DtDoAhLP".getBytes(StandardCharsets.US_ASCII);
    int offset = bytes[3] & 0xff;
    byte[] plain = new byte[bytes.length - 4];
    for (int index = 0; index < plain.length; index++)
      plain[index] = (byte) ((bytes[index + 4] + offset - key[index % key.length] + 512) % 256);
    return new JSONObject(new String(plain, StandardCharsets.UTF_8));
  }

  private static String miguAddress(JSONObject response) throws IOException {
    if (!response.optString("code").equals("000000")) throw new IOException("Migu 无可用版权音源");
    JSONObject data = Json.child(response, "data");
    String url = data.optString("url", data.optString("playUrl"));
    if (url.startsWith("//")) url = "https:" + url;
    if (url.isEmpty()) throw new IOException("Migu 未返回播放地址");
    return url.replace("+", "%2B");
  }

  private boolean miguExists(String url) {
    try {
      Http.Result response =
          http.request(url, "HEAD", Collections.emptyMap(), null, 1024, true, 4000);
      return response.status >= 200 && response.status < 400;
    } catch (Exception error) {
      Log.d("Yingxia.Music", "Migu quality probe failed: " + Json.message(error));
      return false;
    }
  }

  private static String miguRetone(String url, MiguTier target) throws IOException {
    MiguTier source = MIGU_TIERS[2];
    String bare = url.split("\\?", 2)[0];
    String sourcePath = encodePath(source.path), targetPath = encodePath(target.path);
    if (!bare.contains(sourcePath) || !bare.endsWith(source.extension)) return null;
    String replaced = bare.replace(sourcePath, targetPath);
    return replaced.substring(0, replaced.length() - source.extension.length()) + target.extension;
  }

  private static String encodePath(String path) throws IOException {
    String[] parts = path.split("/");
    List<String> encoded = new ArrayList<>();
    for (String part : parts) encoded.add(URLEncoder.encode(part, "UTF-8").replace("+", "%20"));
    return String.join("/", encoded);
  }

  /** Runs only scripts already installed in repository.musicSources; nothing is imported implicitly. */
  private JSONObject lx(JSONObject song, String quality) throws Exception {
    List<JSONObject> installed = Json.objects(app.repository.list("musicSources"));
    int attempted = 0;
    for (JSONObject source : installed) {
      if (!source.optBoolean("enabled", true)) continue;
      String script = source.optString("script", source.optString("code"));
      String origin = source.optString("origin", source.optString("url"));
      if (script.isEmpty() && origin.startsWith("http")) {
        try {
          script = http.get(origin).text();
        } catch (Exception error) {
          Log.w("Yingxia.Music", "Cannot reload installed LX source: " + source.optString("id"));
          continue;
        }
      }
      if (script.isEmpty()) continue;
      String id = source.optString("id", ConfigCodec.hash(script));
      String name = source.optString("name", "LX Music Source");
      try (ScriptRuntime runtime =
          new ScriptRuntime(app, "music-lx:" + id, origin.isEmpty() ? "https://localhost/" : origin)) {
        runtime.supply("lx://script", script);
        JSONObject info = source.optJSONObject("info");
        if (info == null)
          info =
              Json.obj(
                  "name", name,
                  "description", source.optString("description"),
                  "version", source.optString("version"),
                  "author", source.optString("author"),
                  "homepage", source.optString("homepage"));
        runtime.supply("lx://info", info.toString());
        runtime.load("assets://lx/lx.js");
        JSONObject started = runtime.object("__lx.load()", 20);
        if (started.has("error")) throw new IOException("LX 脚本启动失败");
        String key = lxSourceKey(song.optString("source"));
        JSONArray declared = Json.array(Json.child(started, "sources").opt(key));
        String tier = MusicResolver.lxQuality(declared, quality);
        if (tier == null) continue;
        attempted++;
        JSONObject input =
            Json.obj("source", key, "quality", tier, "musicInfo", lxMusicInfo(song, key));
        JSONObject answer = runtime.object("__lx.musicUrl(" + input + ")", 8);
        if (answer.has("error")) throw new IOException("LX 脚本未返回播放地址");
        return stream(answer.optString("url"), "lx:" + name, tier);
      } catch (Exception error) {
        // Third-party script errors can contain API keys, so neither logs nor UI echoes them.
        Log.w("Yingxia.Music", "Installed LX source failed: " + id);
      }
    }
    if (attempted == 0) throw new IOException("没有声明支持该平台与音质的已安装 LX 脚本");
    throw new IOException("已安装 LX 脚本均未返回可用播放地址");
  }

  private static JSONObject lxMusicInfo(JSONObject song, String key) throws Exception {
    String songId = song.optString("songId");
    JSONObject info =
        Json.obj(
            "source", key,
            "songmid", songId,
            "name", song.optString("name"),
            "singer", song.optString("artist"),
            "albumName", song.optString("album"),
            "img", song.optString("poster"),
            "interval", "",
            "types", new JSONArray(),
            "_types", new JSONObject(),
            "typeUrl", new JSONObject());
    if (key.equals("mg")) {
      String[] ids = songId.split("_", 2);
      if (ids.length == 2) {
        info.put("songmid", ids[1]).put("copyrightId", ids[1]).put("contentId", ids[0]);
      } else info.put("copyrightId", songId).put("contentId", songId);
    } else if (key.equals("tx")) info.put("strMediaMid", songId);
    else if (key.equals("kw")) info.put("rid", songId);
    return info;
  }

  private void loadCover(JSONObject song, JSONObject play) throws Exception {
    // Keeping the old value (or the empty placeholder) leaves a usable playable object on failure.
    play.put("poster", song.optString("poster", ""));
    if (!play.optString("poster").isEmpty()) return;
    try {
      if (song.optString("source").equals("netease")) {
        JSONObject detail = gd.metadata(song.optString("songId"));
        if (detail != null) play.put("poster", detail.optString("poster")).put("duration", detail.optLong("duration"));
        return;
      }
      String picId = song.optString("picId");
      if (picId.isEmpty()) return;
      Object result = musicJson(GdMusicCatalog.API, Http.params("types", "pic", "source",
          song.optString("source"), "id", picId, "size", "500"), 6000);
      if (result instanceof JSONObject) {
        String url = ((JSONObject) result).optString("url");
        if (!url.isEmpty()) play.put("poster", url);
      }
    } catch (Exception error) {
      Log.w("Yingxia.Music", "Cannot load music cover; using placeholder", error);
    }
  }

  private static List<String> strings(JSONArray array) {
    List<String> out = new ArrayList<>();
    for (int index = 0; index < array.length(); index++) out.add(array.optString(index));
    return out;
  }
}
