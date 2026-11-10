package com.yingxia.tv;

import android.app.Instrumentation;
import android.content.*;
import android.test.ActivityInstrumentationTestCase2;
import android.view.*;
import com.yingxia.tv.core.*;
import com.yingxia.tv.data.*;
import com.yingxia.tv.runtime.*;
import com.yingxia.tv.ui.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Real Android / JNI tests: no mocked QuickJS, storage, networking or MediaCodec. */
@SuppressWarnings("deprecation")
public final class RuntimeIntegrationTest extends ActivityInstrumentationTestCase2<MainActivity> {
  private Fixture server;
  private YingxiaApp app;
  private String backup;

  public RuntimeIntegrationTest() {
    super(MainActivity.class);
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    getActivity();
    app = YingxiaApp.get();
    backup = app.repository.export();
    server = new Fixture();
  }

  @Override
  protected void tearDown() throws Exception {
    app.engine.clear();
    app.repository.restore(backup);
    server.close();
    super.tearDown();
  }

  private JSONObject source(String key, int type, String api) {
    return Json.obj(
        "id",
        "test@" + key,
        "key",
        key,
        "name",
        key,
        "type",
        type,
        "api",
        server.base() + api,
        "origin",
        server.base() + "/config.json",
        "ext",
        "中文😀");
  }

  public void testPlaybackSourceHeadersReachTheServer() throws Exception {
    for (String key : new String[] {"User-Agent", "user-agent", "USER-AGENT"}) {
      androidx.media3.datasource.DataSource data = PlaybackHttp.factory(
          Map.of(key, "Lavf/57.83.100", "Referer", "http://WJiZxLXA2.com/")).createDataSource();
      JSONObject received = requestHeaders(data, Collections.emptyMap());
      assertEquals("Lavf/57.83.100", received.getString("user-agent"));
      assertEquals("http://WJiZxLXA2.com/", received.getString("referer"));
    }
    androidx.media3.datasource.DataSource data = PlaybackHttp.factory(Collections.emptyMap()).createDataSource();
    assertEquals(Http.UA, requestHeaders(data, Collections.emptyMap()).getString("user-agent"));
    // Offline requests supply source headers on each DataSpec, including HLS child requests.
    assertEquals("Lavf/57.83.100", requestHeaders(data,
        PlaybackHttp.headers(Map.of("user-agent", "Lavf/57.83.100"))).getString("user-agent"));
  }

  public void testUnsupportedSourcesAreHiddenWithoutDeletingUserData() throws Exception {
    JSONArray sites = new JSONArray()
        .put(Json.obj("key", "visible-guazi", "type", 3, "api", "csp_AppgzGuard"))
        .put(Json.obj("key", "deferred-cz", "type", 3, "api", "csp_NewCzGuard"))
        .put(Json.obj("key", "deferred-hm", "type", 3, "api", "csp_HmysGuard"))
        .put(Json.obj("key", "deferred-libvio", "type", 3, "api", "csp_LibvioGuard"))
        .put(Json.obj("key", "deferred-python", "type", 3, "api", "./source.py"))
        .put(Json.obj("key", "visible-api", "type", 1, "api", server.base() + "/api"));
    String origin = server.base() + "/visibility.json";
    app.repository.importConfig(Json.obj("sites", sites).toString().getBytes(StandardCharsets.UTF_8), origin, "visibility");
    Set<String> shown = new HashSet<>();
    for (JSONObject site : app.repository.sites())
      if (origin.equals(site.optString("origin"))) shown.add(site.optString("key"));
    assertEquals(Set.of("visible-guazi", "visible-api"), shown);
    JSONObject stored = null;
    for (JSONObject sub : app.repository.subscriptions())
      if (origin.equals(sub.optString("origin"))) stored = sub;
    assertNotNull(stored);
    assertEquals(6, stored.getJSONObject("config").getJSONArray("sites").length());
    JSONArray history = new JSONArray()
        .put(Json.obj("id", "hidden-history", "site", sites.getJSONObject(1)))
        .put(Json.obj("id", "visible-history", "site", sites.getJSONObject(0)));
    app.repository.save("history", history);
    assertEquals(1, app.repository.visibleLibrary("history").size());
    assertEquals("visible-history", app.repository.visibleLibrary("history").get(0).getString("id"));
    assertEquals(2, app.repository.list("history").length());
    Repository reloaded = new Repository(getActivity());
    assertEquals(1, reloaded.visibleLibrary("history").size());
    assertEquals(2, reloaded.list("history").length());
    assertTrue(reloaded.deferredSites().stream().anyMatch(s -> "deferred-cz".equals(s.optString("key"))));
  }

  public void testDefaultSubscriptionSeedsOnlyANewLibrary() throws Exception {
    File directory = new File(getActivity().getCacheDir(), "fresh-library-" + UUID.randomUUID());
    assertTrue(directory.mkdirs());
    Context isolated = new ContextWrapper(getActivity()) {
      @Override public File getFilesDir() { return directory; }
    };
    try {
      Repository fresh = new Repository(isolated);
      assertEquals(1, fresh.subscriptions().size());
      assertEquals(Repository.DEFAULT_SUBSCRIPTION, fresh.subscriptions().get(0).getString("origin"));
      Set<String> keys = new HashSet<>();
      for (JSONObject site : fresh.sites()) keys.add(site.optString("key"));
      assertTrue(keys.containsAll(Set.of("光影", "瓜子", "比特")));
      assertFalse(keys.contains("厂长"));
      assertFalse(keys.contains("海绵"));
      assertFalse(keys.contains("立播"));
      fresh.remove("subscriptions", fresh.subscriptions().get(0).getString("id"));
      assertTrue(new Repository(isolated).subscriptions().isEmpty());
      fresh.importConfig(server.config().getBytes(StandardCharsets.UTF_8), server.base() + "/own.json", "own");
      Repository reopened = new Repository(isolated);
      assertEquals(1, reopened.subscriptions().size());
      assertEquals("own", reopened.subscriptions().get(0).getString("name"));
    } finally {
      File[] files = directory.listFiles();
      if (files != null) for (File item : files) assertTrue(item.delete());
      assertTrue(directory.delete());
    }
  }

  private JSONObject requestHeaders(androidx.media3.datasource.DataSource data,
      Map<String, String> headers) throws Exception {
    try {
      data.open(new androidx.media3.datasource.DataSpec.Builder()
          .setUri(server.base() + "/headers").setHttpRequestHeaders(headers).build());
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[1024];
      int count;
      while ((count = data.read(buffer, 0, buffer.length)) != -1) out.write(buffer, 0, count);
      return new JSONObject(out.toString("UTF-8"));
    } finally { data.close(); }
  }

  public void testQuickJSUsesOriginalPreludeAndCrossThreadCalls() throws Exception {
    try (ScriptRuntime runtime =
        new ScriptRuntime(getActivity(), "integration", server.base() + "/config.json")) {
      runtime.initSpider(source("js", 3, "/spider.js"));
      JSONObject result = runtime.spider(new JSONObject());
      JSONObject checks = result.getJSONObject("checks");
      assertEquals("中文😀", checks.getString("roundtrip"));
      assertEquals("中文😀", checks.getString("storage"));
      assertEquals("https://example.com/1", checks.getString("link"));
      assertTrue(checks.getBoolean("timer"));
      assertEquals("你好", checks.getString("gbk"));
      ExecutorService worker = Executors.newSingleThreadExecutor();
      try {
        assertEquals(
            "中文😀",
            worker
                .submit(
                    () ->
                        runtime
                            .spider(new JSONObject())
                            .getJSONObject("checks")
                            .getString("roundtrip"))
                .get(10, TimeUnit.SECONDS));
      } finally {
        worker.shutdownNow();
      }
    }
  }

  public void testImportMergeRollbackAndDurableReload() throws Exception {
    app.repository.importConfig(
        server.config().getBytes(StandardCharsets.UTF_8), server.base() + "/config.json", "测试订阅");
    int before = app.repository.sites().size();
    assertTrue(before >= 2);
    try {
      app.repository.importConfig(
          "<html>error</html>".getBytes(StandardCharsets.UTF_8),
          server.base() + "/config.json",
          "测试订阅");
      fail("invalid refresh accepted");
    } catch (Exception expected) {
    }
    assertEquals(before, app.repository.sites().size());
    Repository reopened = new Repository(getActivity());
    assertEquals(before, reopened.sites().size());
    assertTrue(reopened.lives().size() > 0);
  }

  public void testJSONHomeCategorySearchDetailAndPlayback() throws Exception {
    JSONObject site = source("api", 1, "/api");
    JSONObject home = app.engine.call(site, new JSONObject());
    assertEquals("电影", home.getJSONArray("class").getJSONObject(0).getString("type_name"));
    assertEquals(8, Catalog.videos(home).size());
    assertTrue(
        app.engine.call(site, Json.obj("wd", "山海", "pg", "1")).getJSONArray("list").length() > 0);
    JSONObject v = Catalog.videos(app.engine.call(site, Json.obj("ids", "1"))).get(0);
    assertEquals(2, Catalog.episodes(v).size());
    JSONObject play = app.engine.playback(site, v, Catalog.episodes(v).get(0));
    assertEquals(server.base() + "/playback.mp4", play.getString("url"));
    assertEquals("山海之间", play.getString("name"));
  }

  public void testDexPluginSDKAndLoopbackProxy() throws Exception {
    JSONObject site = source("fixture-jar", 3, "/unused");
    site.put("api", "csp_Fixture")
        .put("jar", server.base() + "/spider-fixture.jar")
        .put("ext", server.base() + "/api");
    JSONObject result = app.engine.call(site, new JSONObject());
    assertEquals(8, new JSONObject(result.getString("api")).getJSONArray("list").length());
    assertEquals(42, result.getJSONObject("script").getInt("sum"));
    assertEquals("中文😀", result.getJSONObject("script").getString("emoji"));
    assertEquals("proxy-中文😀", app.http.get(result.getString("proxy")).text());
    app.engine.clear();
  }

  public void testHistoryUsesVideoIdentityAndResumeRefreshesEpisodeURL() throws Exception {
    JSONObject site = source("history", 1, "/api");
    JSONObject video = server.video(1);
    List<JSONObject> episodes = Catalog.episodes(video);
    JSONObject first = app.engine.playback(site, video, episodes.get(0));
    app.repository.progress(first, 4000, 20000);
    JSONObject second = app.engine.playback(site, video, episodes.get(1));
    app.repository.progress(second, 6000, 20000);
    assertEquals(first.getString("historyId"), second.getString("historyId"));
    JSONObject saved = app.repository.history(second.getString("historyId"));
    assertEquals("第2集", saved.getString("episode"));
    saved.put("url", "https://expired.invalid/old.mp4");
    saved.getJSONObject("episodeInfo").put("url", "https://expired.invalid/episode.mp4");
    JSONObject resolved = app.engine.resume(saved);
    assertEquals(server.base() + "/playback.mp4", resolved.getString("url"));
    assertEquals(6000, resolved.getLong("position"));
    app.repository.upsert(
        "servers", Json.obj("id", "private", "password", "do-not-export"), "id", false, 100);
    assertFalse(app.repository.exportWithoutAccounts().contains("do-not-export"));
  }

  public void testDownloadCompletesAndReadsBackWithoutNetwork() throws Exception {
    String id = "integration-offline";
    JSONObject item = Json.obj("id", id, "name", "离线测试", "url", server.base() + "/playback.mp4");
    getInstrumentation().runOnMainSync(() -> OfflineService.add(getActivity(), item));
    androidx.media3.exoplayer.offline.DownloadManager manager =
        OfflineService.manager(getActivity());
    long until = System.currentTimeMillis() + 20000;
    androidx.media3.exoplayer.offline.Download result = null;
    try {
      do {
        result = manager.getDownloadIndex().getDownload(id);
        if (result != null
            && (result.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED
                || result.state == androidx.media3.exoplayer.offline.Download.STATE_FAILED)) break;
        Thread.sleep(200);
      } while (System.currentTimeMillis() < until);
      assertNotNull("Download was never registered", result);
      assertEquals(
          "Download did not complete",
          androidx.media3.exoplayer.offline.Download.STATE_COMPLETED,
          result.state);
      androidx.media3.datasource.cache.CacheDataSource cached =
          new androidx.media3.datasource.cache.CacheDataSource.Factory()
              .setCache(OfflineService.cache(getActivity()))
              .setUpstreamDataSourceFactory(null)
              .createDataSource();
      try (InputStream expected =
          getInstrumentation().getContext().getAssets().open("playback.mp4")) {
        androidx.media3.common.MediaItem media =
            OfflineService.playbackItem(getActivity(), Json.copy(item).put("complete", true));
        assertNotNull(media.localConfiguration);
        assertEquals(id, media.localConfiguration.customCacheKey);
        cached.open(
            new androidx.media3.datasource.DataSpec.Builder()
                .setUri(media.localConfiguration.uri)
                .setKey(media.localConfiguration.customCacheKey)
                .build());
        ByteArrayOutputStream actual = new ByteArrayOutputStream();
        byte[] bytes = new byte[8192];
        int count;
        while ((count = cached.read(bytes, 0, bytes.length)) != -1) actual.write(bytes, 0, count);
        assertTrue(Arrays.equals(readAll(expected), actual.toByteArray()));
      } finally {
        cached.close();
      }
    } finally {
      getInstrumentation().runOnMainSync(() -> OfflineService.remove(getActivity(), item));
    }
  }

  private static byte[] readAll(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] bytes = new byte[8192];
    int count;
    while ((count = in.read(bytes)) != -1) out.write(bytes, 0, count);
    return out.toByteArray();
  }

  public void testRealPlaybackAdvancesAndSavesHistory() throws Exception {
    File media = new File(getActivity().getFilesDir(), "integration-video.mp4");
    try (InputStream in = getInstrumentation().getContext().getAssets().open("playback.mp4");
        OutputStream out = new FileOutputStream(media)) {
      byte[] b = new byte[16384];
      int n;
      while ((n = in.read(b)) != -1) out.write(b, 0, n);
    }
    Instrumentation.ActivityMonitor monitor =
        getInstrumentation().addMonitor(PlayerActivity.class.getName(), null, false);
    JSONObject play =
        Json.obj(
            "id",
            "integration-playback",
            "name",
            "测试视频",
            "url",
            UriString(media),
            "position",
            2000);
    getInstrumentation().runOnMainSync(() -> PlayerActivity.open(getActivity(), play));
    PlayerActivity player = (PlayerActivity) monitor.waitForActivityWithTimeout(10000);
    assertNotNull("PlayerActivity did not start", player);
    java.lang.reflect.Field f = PlayerActivity.class.getDeclaredField("player");
    f.setAccessible(true);
    long until = System.currentTimeMillis() + 15000;
    final long[] position = {0};
    while (System.currentTimeMillis() < until && position[0] < 3500) {
      getInstrumentation()
          .runOnMainSync(
              () -> {
                androidx.media3.exoplayer.ExoPlayer p =
                    (androidx.media3.exoplayer.ExoPlayer) read(f, player);
                if (p != null) position[0] = p.getCurrentPosition();
              });
      Thread.sleep(200);
    }
    assertTrue("MediaCodec playback never advanced: " + position[0], position[0] >= 3500);
    getInstrumentation()
        .runOnMainSync(
            () -> {
              androidx.media3.exoplayer.ExoPlayer p =
                  (androidx.media3.exoplayer.ExoPlayer) read(f, player);
              assertNotNull(p.getVideoDecoderCounters());
              assertTrue(
                  "Decoder produced no video frames",
                  p.getVideoDecoderCounters().renderedOutputBufferCount > 0);
            });
    if (android.os.Build.VERSION.SDK_INT >= 26) {
      android.graphics.Bitmap frame =
          android.graphics.Bitmap.createBitmap(320, 180, android.graphics.Bitmap.Config.ARGB_8888);
      CountDownLatch copied = new CountDownLatch(1);
      int[] copyResult = {-1};
      getInstrumentation()
          .runOnMainSync(
              () -> {
                View surface =
                    ((androidx.media3.ui.PlayerView)
                            player
                                .findViewById(androidx.media3.ui.R.id.exo_content_frame)
                                .getParent())
                        .getVideoSurfaceView();
                android.view.PixelCopy.request(
                    (SurfaceView) surface,
                    frame,
                    result -> {
                      copyResult[0] = result;
                      copied.countDown();
                    },
                    new android.os.Handler(android.os.Looper.getMainLooper()));
              });
      assertTrue(copied.await(5, TimeUnit.SECONDS));
      assertEquals(android.view.PixelCopy.SUCCESS, copyResult[0]);
      try (OutputStream output =
          new FileOutputStream(new File(getActivity().getFilesDir(), "codec-frame.png"))) {
        frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
      }
      int colored = 0;
      for (int y = 0; y < frame.getHeight(); y += 4)
        for (int x = 0; x < frame.getWidth(); x += 4) {
          int color = frame.getPixel(x, y);
          if (android.graphics.Color.red(color)
                  + android.graphics.Color.green(color)
                  + android.graphics.Color.blue(color)
              > 100) colored++;
        }
      assertTrue("The rendered test pattern is black", colored > 100);
      frame.recycle();
    }
    getInstrumentation().runOnMainSync(player::finish);
    getInstrumentation().waitForIdleSync();
    long savedUntil = System.currentTimeMillis() + 5000;
    while (app.repository.history("integration-playback").optLong("position") < 3500
        && System.currentTimeMillis() < savedUntil) Thread.sleep(100);
    assertTrue(app.repository.history("integration-playback").optLong("position") >= 3500);
    getInstrumentation().removeMonitor(monitor);
    media.delete();
  }

  public void testSeekControlsUseVectorButtons() throws Exception {
    File media = new File(getActivity().getFilesDir(), "seek-controls-video.mp4");
    try (InputStream in = getInstrumentation().getContext().getAssets().open("playback.mp4");
        OutputStream out = new FileOutputStream(media)) {
      byte[] buffer = new byte[8192];
      for (int count; (count = in.read(buffer)) != -1; ) out.write(buffer, 0, count);
    }
    Instrumentation.ActivityMonitor monitor =
        getInstrumentation().addMonitor(PlayerActivity.class.getName(), null, false);
    PlayerActivity player = null;
    try {
      JSONObject play = Json.obj("id", "seek-controls", "name", "控制栏测试", "url", UriString(media));
      getInstrumentation().runOnMainSync(() -> PlayerActivity.open(getActivity(), play));
      player = (PlayerActivity) monitor.waitForActivityWithTimeout(10000);
      assertNotNull(player);
      getInstrumentation().waitForIdleSync();
      android.widget.ImageButton rewind =
          player.getWindow().getDecorView().findViewById(androidx.media3.ui.R.id.exo_rew);
      android.widget.ImageButton forward =
          player.getWindow().getDecorView().findViewById(androidx.media3.ui.R.id.exo_ffwd);
      assertNotNull(rewind);
      assertNotNull(forward);
      assertTrue(rewind.getDrawable() instanceof android.graphics.drawable.VectorDrawable);
      assertTrue(forward.getDrawable() instanceof android.graphics.drawable.VectorDrawable);
      assertTrue(rewind.getContentDescription().toString().contains("快退"));
      assertTrue(forward.getContentDescription().toString().contains("快进"));
    } finally {
      if (player != null) {
        PlayerActivity target = player;
        getInstrumentation().runOnMainSync(target::finish);
      }
      getInstrumentation().removeMonitor(monitor);
      media.delete();
    }
  }

  public void testDpadCanReachEverySectionWithoutCrashing() throws Exception {
    getInstrumentation()
        .runOnMainSync(
            () -> {
              View root = getActivity().getWindow().getDecorView();
              View v = find(root, "点播源");
              assertNotNull(v);
              v.requestFocus();
              v.performClick();
            });
    getInstrumentation().waitForIdleSync();
    for (String name : List.of("直播", "音乐", "收藏", "播放历史", "下载", "设置")) {
      getInstrumentation()
          .runOnMainSync(
              () -> {
                View v = find(getActivity().getWindow().getDecorView(), name);
                assertNotNull(name, v);
                v.requestFocus();
                v.performClick();
              });
      getInstrumentation().waitForIdleSync();
      getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
      getInstrumentation().waitForIdleSync();
    }
  }

  private static Object read(java.lang.reflect.Field f, Object o) {
    try {
      return f.get(o);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static String UriString(File f) {
    return android.net.Uri.fromFile(f).toString();
  }

  private static View find(View v, String description) {
    if (description.contentEquals(
        v.getContentDescription() == null ? "" : v.getContentDescription())) return v;
    if (v instanceof android.view.ViewGroup)
      for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) {
        View found = find(((android.view.ViewGroup) v).getChildAt(i), description);
        if (found != null) return found;
      }
    return null;
  }

  private final class Fixture implements AutoCloseable {
    final ServerSocket socket;
    final ExecutorService pool = Executors.newFixedThreadPool(3);
    volatile boolean running = true;

    Fixture() throws IOException {
      socket = new ServerSocket(0);
      pool.submit(
          () -> {
            while (running)
              try {
                Socket c = socket.accept();
                pool.submit(() -> serve(c));
              } catch (IOException | RejectedExecutionException ignored) {
              }
          });
    }

    String base() {
      return "http://127.0.0.1:" + socket.getLocalPort();
    }

    String config() {
      return Json.obj(
              "sites",
              new JSONArray()
                  .put(Json.obj("key", "api", "name", "示例影视", "api", base() + "/api", "type", 1))
                  .put(
                      Json.obj(
                          "key",
                          "script",
                          "name",
                          "脚本影视",
                          "api",
                          base() + "/spider.js",
                          "type",
                          3)),
              "lives",
              new JSONArray().put(Json.obj("name", "示例直播", "url", base() + "/live.m3u")))
          .toString();
    }

    JSONObject video(int id) {
      String[] names = {"山海之间", "霓虹小巷", "极光之夜", "一碗人间烟火", "灯塔守望者", "樱花来信", "云端旅人", "森林之声"};
      return Json.obj(
          "vod_id",
          "" + id,
          "vod_name",
          names[(id - 1) % names.length],
          "vod_pic",
          "",
          "vod_year",
          "2026",
          "vod_remarks",
          id == 2 ? "更新至12集" : "2026",
          "vod_content",
          "为本地兼容性验证制作的测试影片。",
          "vod_play_from",
          "线路一",
          "vod_play_url",
          "第1集$" + base() + "/playback.mp4#第2集$" + base() + "/playback.mp4");
    }

    void serve(Socket client) {
      try (Socket c = client) {
        c.setSoTimeout(5000);
        BufferedReader r =
            new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        String first = r.readLine();
        if (first == null) return;
        String path = first.split(" ")[1];
        JSONObject requestHeaders = new JSONObject();
        for (String line; (line = r.readLine()) != null && !line.isEmpty();) {
          int colon = line.indexOf(':');
          if (colon > 0) requestHeaders.put(line.substring(0, colon).toLowerCase(Locale.ROOT),
              line.substring(colon + 1).trim());
        }
        String body = "{}", type = "application/json";
        if (path.startsWith("/headers")) body = requestHeaders.toString();
        else if (path.startsWith("/config")) body = config();
        else if (path.startsWith("/api")) {
          JSONArray list = new JSONArray();
          if (path.contains("ids=")) list.put(video(1));
          else for (int i = 1; i <= 8; i++) list.put(video(i));
          body =
              Json.obj(
                      "class",
                      new JSONArray()
                          .put(Json.obj("type_id", "1", "type_name", "电影"))
                          .put(Json.obj("type_id", "2", "type_name", "剧集")),
                      "list",
                      list,
                      "page",
                      1,
                      "pagecount",
                      1)
                  .toString();
        } else if (path.startsWith("/spider.js")) {
          type = "text/javascript";
          body =
              "export default {init(ext){local.set('t','ext',ext)},async home(){await new"
                  + " Promise(r=>setTimeout(r,5));return"
                  + " {class:[],list:[],checks:{roundtrip:Buffer.from('中文😀').toString(),storage:local.get('t','ext'),timer:true,gbk:new"
                  + " TextDecoder('gbk').decode(new Uint8Array([196,227,186,195])),link:pd('<a"
                  + " href=\"/1\">x</a>','a&&href','https://example.com/list/')}}},category(){return"
                  + " {list:[]}},search(){return {list:[]}}};";

        }
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        if (path.startsWith("/spider-fixture.jar") || path.startsWith("/playback.mp4")) {
          String asset =
              path.startsWith("/spider-fixture.jar") ? "spider-fixture.jar" : "playback.mp4";
          try (InputStream assetStream =
              getInstrumentation().getContext().getAssets().open(asset)) {
            data = readAll(assetStream);
          }
          type = asset.endsWith("mp4") ? "video/mp4" : "application/java-archive";
        }
        c.getOutputStream()
            .write(
                ("HTTP/1.1 200 OK\r\nContent-Type: "
                        + type
                        + "\r\nContent-Length: "
                        + data.length
                        + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
        c.getOutputStream().write(data);
      } catch (Exception ignored) {
      }
    }

    public void close() throws IOException {
      running = false;
      socket.close();
      pool.shutdownNow();
    }
  }
}
