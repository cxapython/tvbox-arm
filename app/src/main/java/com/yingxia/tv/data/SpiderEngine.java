package com.yingxia.tv.data;

import android.content.*;
import com.github.catvod.crawler.Spider;
import com.yingxia.tv.*;
import com.yingxia.tv.core.*;
import com.yingxia.tv.runtime.*;
import dalvik.system.DexClassLoader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.json.*;
import org.xmlpull.v1.*;

public final class SpiderEngine {
  private final Context context;
  private final Http http;
  private final LiteFallback liteFallback = new LiteFallback();
  private final Map<String, Spider> jarSpiders = new java.util.concurrent.ConcurrentHashMap<>();
  private final Map<Session, Integer> activeSessions = new java.util.IdentityHashMap<>();
  private final Map<String, Session> sessions = new LinkedHashMap<>(4, .75f, true);

  private interface Session {
    JSONObject call(JSONObject params) throws Exception;

    default void close() {}
  }

  public SpiderEngine(Context context) {
    this.context = context;
    http = YingxiaApp.get().http;
  }

  public JSONObject call(JSONObject site, JSONObject params) throws Exception {
    Session session;
    synchronized (sessions) {
      String id = site.optString("id");
      session = sessions.get(id);
      if (session == null) {
        session = open(site);
        sessions.put(id, session);
      }
      activeSessions.put(session, activeSessions.getOrDefault(session, 0) + 1);
    }
    try {
      JSONObject result = session.call(params);
      if (result.has("error")) throw new IOException(result.optString("error"));
      return result;
    } finally {
      synchronized (sessions) {
        int refs = activeSessions.getOrDefault(session, 1) - 1;
        if (refs == 0) {
          activeSessions.remove(session);
          if (!sessions.containsValue(session)) session.close();
        } else activeSessions.put(session, refs);
        if (sessions.size() > 3) {
          Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator();
          while (it.hasNext() && sessions.size() > 3) {
            Session old = it.next().getValue();
            if (!activeSessions.containsKey(old)) {
              it.remove();
              old.close();
            }
          }
        }
      }
    }
  }

  public Object[] proxy(String key, Map<String, String> params) throws Exception {
    Spider spider = jarSpiders.get(key);
    if (spider == null) throw new IOException("Source proxy unavailable");
    return spider.proxy(params);
  }

  public void clear() {
    synchronized (sessions) {
      for (Session s : sessions.values()) if (!activeSessions.containsKey(s)) s.close();
      sessions.clear();
    }
  }

  private Session open(JSONObject raw) throws Exception {
    JSONObject site = Json.copy(raw);
    String api = site.optString("api"), origin = site.optString("origin");
    int type = site.optInt("type", 1);
    if ((type == 0 || type == 1 || type == 4) && !api.startsWith("csp_"))
      return params -> api(site, params);
    if (api.startsWith("csp_")) {
      // Use the exact lightweight ports shared with the Apple app where possible.
      try (InputStream in = context.getAssets().open("scripts/sites/index.json")) {
        JSONObject index = new JSONObject(new String(read(in, 200000), StandardCharsets.UTF_8));
        String key = api.substring(4);
        Object mapping = Json.child(index, "classes").opt(key);
        String mapped =
            mapping instanceof String
                ? (String) mapping
                : mapping instanceof JSONObject ? ((JSONObject) mapping).optString("script") : "";
        String extension = site.optString("ext").trim().toLowerCase(Locale.ROOT);
        String extensionPath = extension.split("\\?", 2)[0];
        boolean extIsScript = !extension.startsWith("{")
            && (extensionPath.endsWith(".js") || extensionPath.endsWith(".py"));
        if (!mapped.isEmpty() && !extIsScript) {
          JSONObject jarSite = Json.copy(site);
          site.put("api", mapped.startsWith("assets:") ? mapped : "assets://sites/" + mapped);
          Session lightweight;
          try {
            lightweight = script(site);
          } catch (Exception e) {
            if (jarSite.optString("jar", jarSite.optString("spider")).isEmpty()) throw e;
            return jar(jarSite);
          }
          if (jarSite.optString("jar", jarSite.optString("spider")).isEmpty()) return lightweight;
          return new Session() {
            private Session fallback;
            private final String fallbackKey = jarSite.optString("key", jarSite.optString("id"));

            public JSONObject call(JSONObject params) throws Exception {
              if (!liteFallback.prefersPort(fallbackKey)) {
                if (fallback == null) fallback = jar(jarSite);
                return fallback.call(params);
              }
              try {
                JSONObject answer = lightweight.call(params);
                if (answer.has("error")) throw new IOException(answer.optString("error"));
                String unusable = LiteSites.unusable(answer, params);
                if (unusable != null) throw new IOException(unusable);
                return answer;
              } catch (Exception original) {
                try {
                  if (fallback == null) fallback = jar(jarSite);
                  liteFallback.record(fallbackKey);
                  return fallback.call(params);
                } catch (Exception error) {
                  error.addSuppressed(original);
                  throw error;
                }
              }
            }

            public void close() {
              lightweight.close();
              if (fallback != null) fallback.close();
            }
          };
        }
      } catch (JSONException brokenIndex) {
        android.util.Log.w(
            "Yingxia.Spider", "Lite site index is invalid; using the JAR fallback", brokenIndex);
      }
      return jar(site);
    }
    if (api.toLowerCase(Locale.ROOT).contains(".py"))
      throw new IOException("此源需要 Python 运行时，请选用它的 JSON、JS 或 JAR 版本");
    site.put("api", ConfigCodec.address(api, origin));
    return script(site);
  }

  private Session script(JSONObject site) throws Exception {
    ScriptRuntime runtime =
        new ScriptRuntime(context, site.optString("id"), site.optString("origin"));
    try {
      runtime.initSpider(site);
    } catch (Exception e) {
      runtime.close();
      throw e;
    }
    return new Session() {
      public JSONObject call(JSONObject p) throws Exception {
        return runtime.spider(p);
      }

      public void close() {
        runtime.close();
      }
    };
  }

  private JSONObject api(JSONObject site, JSONObject p) throws Exception {
    String api = ConfigCodec.address(site.optString("api"), site.optString("origin"));
    Map<String, String> query = Catalog.apiParameters(site, p);
    Http.Result r = http.get(Http.query(api, query), Json.headers(site.optJSONObject("header")));
    JSONObject answer = r.text().trim().startsWith("<") ? xml(r.text()) : new JSONObject(r.text());
    // A plain type-1 home response often omits posters; one detail request fills all of them.
    if (p.length() == 0 && site.optInt("type") == 1) {
      JSONArray list = Json.array(answer.opt("list"));
      List<String> missing = Catalog.idsMissingPosters(list);
      if (!missing.isEmpty()) {
        try {
          JSONObject details = api(site, Json.obj("ids", String.join(",", missing)));
          answer.put("list", Catalog.fillingPosters(list, Json.array(details.opt("list"))));
        } catch (Exception detailFailure) {
          // Posters are optional: retain the usable home answer, but make the degradation visible.
          android.util.Log.w(
              "Yingxia.Spider", "Type-1 poster backfill failed; keeping home list", detailFailure);
        }
      }
    }
    return answer;
  }

  private JSONObject xml(String text) throws Exception {
    XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
    parser.setInput(new StringReader(text));
    JSONObject out = Json.obj("class", new JSONArray(), "list", new JSONArray());
    JSONObject video = null;
    String tag = "", flag = "";
    StringBuilder value = new StringBuilder();
    List<String> flags = new ArrayList<>(), plays = new ArrayList<>();
    for (int event = parser.getEventType();
        event != XmlPullParser.END_DOCUMENT;
        event = parser.next()) {
      if (event == XmlPullParser.START_TAG) {
        tag = parser.getName();
        value.setLength(0);
        if (tag.equals("list")) {
          out.put("page", parser.getAttributeValue(null, "page"))
              .put("pagecount", parser.getAttributeValue(null, "pagecount"));
        }
        if (tag.equals("video")) {
          video = new JSONObject();
          flags.clear();
          plays.clear();
        }
        if (tag.equals("dd")) flag = parser.getAttributeValue(null, "flag");
        if (tag.equals("ty")) flag = parser.getAttributeValue(null, "id");
      } else if (event == XmlPullParser.TEXT) value.append(parser.getText());
      else if (event == XmlPullParser.END_TAG) {
        String name = parser.getName(), s = value.toString();
        if (name.equals("ty"))
          out.getJSONArray("class").put(Json.obj("type_id", flag, "type_name", s));
        if (video != null) {
          Map<String, String> names =
              Http.params(
                  "id",
                  "vod_id",
                  "name",
                  "vod_name",
                  "pic",
                  "vod_pic",
                  "note",
                  "vod_remarks",
                  "des",
                  "vod_content",
                  "year",
                  "vod_year",
                  "actor",
                  "vod_actor",
                  "director",
                  "vod_director",
                  "type",
                  "type_name");
          if (names.containsKey(name)) video.put(names.get(name), s);
          if (name.equals("dd")) {
            flags.add(flag == null ? "" : flag);
            plays.add(s);
          }
          if (name.equals("video")) {
            video
                .put("vod_play_from", String.join("$$", flags))
                .put("vod_play_url", String.join("$$", plays));
            out.getJSONArray("list").put(video);
            video = null;
          }
        }
        value.setLength(0);
      }
    }
    return out;
  }

  private Session jar(JSONObject site) throws Exception {
    String address = site.optString("jar", site.optString("spider"));
    if (address.isEmpty()) throw new IOException("订阅未提供此源的 JAR 插件");
    String[] location = address.split(";md5;", 2);
    String url = ConfigCodec.address(location[0], site.optString("origin"));
    File root = new File(context.getCodeCacheDir(), "plugins/" + ConfigCodec.hash(url));
    if (!root.exists() && !root.mkdirs()) throw new IOException("无法创建插件目录");
    File archive = new File(root, "plugin.jar");
    if (!archive.exists()) {
      byte[] data = http.get(url).bytes;
      if (location.length == 2
          && !ConfigCodec.hex(MessageDigest.getInstance("MD5").digest(data))
              .equalsIgnoreCase(location[1])) throw new IOException("插件 MD5 校验失败");
      try (FileOutputStream out = new FileOutputStream(archive)) {
        out.write(data);
      }
      archive.setReadOnly();
    }
    byte[] pluginBytes = java.nio.file.Files.readAllBytes(archive.toPath());
    byte[] normalized = PluginLocator.normalizeZip(pluginBytes);
    File dexArchive = archive;
    if (normalized != pluginBytes) {
      dexArchive = new File(root, "plugin-normalized.jar");
      try (FileOutputStream out = new FileOutputStream(dexArchive)) {
        out.write(normalized);
      }
      dexArchive.setReadOnly();
    }
    boolean dex = false;
    try (ZipFile zip = new ZipFile(dexArchive)) {
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry e = entries.nextElement();
        String name = e.getName();
        if (name.matches("classes[0-9]*\\.dex")) dex = true;
        if ((name.startsWith("lib/armeabi-v7a/") || name.startsWith("lib/armeabi/"))
            && name.endsWith(".so")) {
          File lib = new File(root, new File(name).getName());
          if (!lib.exists())
            try (InputStream in = zip.getInputStream(e);
                OutputStream out = new FileOutputStream(lib)) {
              out.write(read(in, 20_000_000));
            }
          lib.setReadOnly();
        }
      }
    }
    if (!dex) throw new IOException("此 JAR 没有 Android DEX 字节码，请使用 Android 版插件");
    DexClassLoader loader =
        new DexClassLoader(
            dexArchive.getAbsolutePath(),
            root.getAbsolutePath(),
            root.getAbsolutePath(),
            context.getClassLoader());
    try {
      Class<?> init = loader.loadClass("com.github.catvod.spider.Init");
      init.getMethod("init", Context.class).invoke(null, context);
    } catch (ClassNotFoundException | NoSuchMethodException optionalHook) {
      android.util.Log.d("Yingxia.Spider", "Plugin has no optional Init.init hook");
    }
    String name = site.optString("api").substring(4);
    if (!name.contains(".")) name = "com.github.catvod.spider." + name;
    Class<?> spiderType;
    try {
      spiderType = loader.loadClass(name);
    } catch (ClassNotFoundException missing) {
      Set<String> candidates = PluginLocator.spiderClasses(normalized);
      if (candidates.size() != 1) {
        throw new IOException("插件中找不到 " + name + "，DEX 候选类=" + candidates, missing);
      }
      name = "com.github.catvod.spider." + candidates.iterator().next();
      spiderType = loader.loadClass(name);
    }
    Object object = spiderType.getDeclaredConstructor().newInstance();
    if (!(object instanceof Spider)) throw new IOException("插件没有实现 CatVod Spider 接口");
    Spider spider = (Spider) object;
    spider.siteKey = site.optString("id", site.optString("key"));
    spider.js.scope(spider.siteKey);
    if (android.os.Build.VERSION.SDK_INT >= 24)
      spider.net = new com.github.catvod.net.Net(null, spider.siteKey);
    spider.local = new com.github.catvod.utils.Local(spider.siteKey);
    spider.init(context, site.optString("ext"));
    jarSpiders.put(spider.siteKey, spider);
    JarCallGuard guard = new JarCallGuard();
    return new Session() {
      public JSONObject call(JSONObject p) throws Exception {
        return guard.call(
            p,
            () -> {
              String answer;
              if (p.has("play"))
                answer =
                    spider.playerContent(
                        p.optString("flag"), p.optString("play"), Collections.emptyList());
              else if (p.has("ids"))
                answer = spider.detailContent(Arrays.asList(p.optString("ids").split(",")));
              else if (p.has("wd"))
                answer = spider.searchContent(p.optString("wd"), false, p.optString("pg", "1"));
              else if (p.has("t"))
                answer =
                    spider.categoryContent(
                        p.optString("t"),
                        p.optString("pg", "1"),
                        true,
                        new HashMap<>(Json.headers(Json.object(p.optString("filters", "{}")))));
              else {
                JSONObject home = Json.object(spider.homeContent(true));
                JSONObject videos = Json.object(spider.homeVideoContent());
                if (videos.has("list")) home.put("list", videos.opt("list"));
                return home;
              }
              return new JSONObject(answer);
            });
      }

      public void close() {
        jarSpiders.remove(spider.siteKey, spider);
        try {
          spider.destroy();
        } finally {
          spider.closeHostResources();
        }
      }
    };
  }

  private static byte[] read(InputStream in, int max) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[16384];
    int n;
    while ((n = in.read(buf)) != -1) {
      if (out.size() + n > max) throw new IOException("插件资源过大");
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  public JSONObject playback(JSONObject site, JSONObject video, JSONObject episode)
      throws Exception {
    return playback(site, video, episode, 0);
  }

  public JSONObject playback(JSONObject site, JSONObject video, JSONObject episode, int quality)
      throws Exception {
    String url = episode.optString("url");
    JSONObject answer;
    if (site.length() == 0) {
      answer = Json.obj("url", url, "parse", 0);
    } else if (site.optInt("type") == 0 || site.optInt("type") == 1) {
      // CMS sources have no spider playerContent call: their site-level playUrl routes the page.
      answer = Json.obj("url", url, "parse", 0, "playUrl", site.optString("playUrl"));
    } else {
      answer = call(site, Json.obj("play", url, "flag", episode.optString("flag")));
    }
    PlaybackResolver.Plan plan = PlaybackResolver.plan(site, answer, episode);
    PlaybackResolver.Sniffer sniffer = PlaybackWebSniffer.foreground();
    PlaybackResolver.Resolved resolved =
        new PlaybackResolver((address, headers) -> http.get(address, headers).text(), sniffer)
            .resolve(plan, quality);
    url = resolved.url;
    JSONArray qualities = new JSONArray();
    for (int index = 0; index < plan.choices.size(); index++) {
      PlaybackResolver.Choice choice = plan.choices.get(index);
      qualities.put(Json.obj("name", choice.name, "index", index));
    }
    JSONObject headers = new JSONObject(resolved.headers);
    String id =
        site.optString("id") + "@" + video.optString("vod_id") + "@" + episode.optString("id");
    JSONObject play =
        Json.obj(
            "id",
            id,
            "historyId",
            site.optString("id") + "@" + video.optString("vod_id"),
            "name",
            video.optString("vod_name", episode.optString("name")),
            "episode",
            episode.optString("name"),
            "url",
            url,
            "qualities",
            qualities,
            "quality",
            quality,
            "header",
            headers,
            "poster",
            video.optString("vod_pic"),
            "site",
            site,
            "video",
            video,
            "episodeInfo",
            episode,
            "episodes",
            new JSONArray(Catalog.episodes(video)),
            "updated",
            System.currentTimeMillis());
    PlaybackResolver.passThrough(answer, play);
    return play;
  }

  public JSONObject resume(JSONObject saved) throws Exception {
    JSONObject oldSite = Json.child(saved, "site");
    JSONObject currentSite = YingxiaApp.get().repository.site(oldSite.optString("id"));
    if (currentSite.length() == 0) currentSite = oldSite;
    String videoId = Json.child(saved, "video").optString("vod_id");
    List<JSONObject> videos = Catalog.videos(call(currentSite, Json.obj("ids", videoId)));
    if (videos.isEmpty()) throw new IOException("来源没有返回此影片，请重新搜索");
    JSONObject video = videos.get(0), oldEpisode = Json.child(saved, "episodeInfo");
    for (JSONObject episode : Catalog.episodes(video)) {
      if (episode.optString("name").equals(oldEpisode.optString("name"))
          && episode.optString("flag").equals(oldEpisode.optString("flag"))) {
        JSONObject play = playback(currentSite, video, episode, saved.optInt("quality", 0));
        play.put("position", saved.optBoolean("completed") ? 0 : saved.optLong("position"));
        return play;
      }
    }
    throw new IOException("来源的集数已变化，请在详情页重新选集");
  }
}
