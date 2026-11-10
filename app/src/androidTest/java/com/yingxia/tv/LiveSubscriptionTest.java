package com.yingxia.tv;

import android.app.Instrumentation;
import android.test.ActivityInstrumentationTestCase2;
import androidx.media3.exoplayer.ExoPlayer;
import com.yingxia.tv.core.*;
import com.yingxia.tv.data.*;
import com.yingxia.tv.ui.*;
import com.yingxia.tv.runtime.ScriptRuntime;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Opt-in live-provider smoke test; intentionally excluded from the deterministic fixture suite. */
@SuppressWarnings("deprecation")
public final class LiveSubscriptionTest extends ActivityInstrumentationTestCase2<MainActivity> {
  private static final String ORIGIN = "https://raw.githubusercontent.com/qist/tvbox/master/fty.json";
  private YingxiaApp app;
  private final JSONArray results = new JSONArray();
  private String reportFile = "fty-live-report.json";
  private long requiredPositionMs = 5000;
  private long minimumDurationMs = 0;
  private String searchTitle = "狂飙";
  private int episodeIndex = 0;
  private boolean exactTitle = true;

  public void testBukaGuangying() throws Exception { probeBuka("光影", "guangying"); }
  public void testBukaLibvio() throws Exception { probeBuka("立播", "libvio"); }
  public void testBukaBttwoo() throws Exception { probeBuka("比特", "bttwoo"); }

  private void probeBuka(String key, String report) throws Exception {
    reportFile = "buka-" + report + "-report.json";
    requiredPositionMs = 35000;
    minimumDurationMs = 600000;
    exactTitle = false;
    probe(new String[] {key}, 1);
  }

  public LiveSubscriptionTest() { super(MainActivity.class); }

  public void testFtyPlayback() throws Exception {
    probe(new String[] {"光影", "奶酪", "视界", "糯米"}, 2);
  }

  public void testGuaziPlayback() throws Exception {
    reportFile = "guazi-live-report.json";
    requiredPositionMs = 35000;
    minimumDurationMs = 600000;
    probe(new String[] {"瓜子"}, 1);
  }

  public void testGuaziMoviePlayback() throws Exception {
    reportFile = "guazi-movie-report.json";
    searchTitle = "流浪地球";
    requiredPositionMs = 35000;
    minimumDurationMs = 3600000;
    probe(new String[] {"瓜子"}, 1);
  }

  public void testGuaziNextEpisodePlayback() throws Exception {
    reportFile = "guazi-next-report.json";
    episodeIndex = 1;
    requiredPositionMs = 35000;
    minimumDurationMs = 600000;
    probe(new String[] {"瓜子"}, 1);
  }

  public void testGuaziCatalog() throws Exception {
    reportFile = "guazi-catalog-report.json";
    getActivity();
    app = YingxiaApp.get();
    JSONObject site = null;
    for (JSONObject candidate : app.repository.sites())
      if ("瓜子".equals(candidate.optString("key"))) { site = candidate; break; }
    assertNotNull("User's Guazi subscription missing", site);
    try {
      JSONObject home = app.engine.call(site, new JSONObject());
      record(Json.obj("stage", "home", "categories", home.opt("class"), "videos", Catalog.videos(home).size()));
      for (String category : new String[] {"2", "5"}) {
        JSONObject answer = app.engine.call(site, Json.obj("t", category, "pg", "1"));
        List<JSONObject> videos = Catalog.videos(answer);
        record(Json.obj("stage", "category", "id", category, "videos", videos.size()));
        for (int i = 0; i < Math.min(3, videos.size()); i++) inspectVideo(site, videos.get(i));
      }
      List<JSONObject> matches = Catalog.videos(app.engine.call(site, Json.obj("wd", "应召生涯", "pg", "1")));
      record(Json.obj("stage", "search", "query", "应召生涯", "matches", matches.size()));
      for (int i = 0; i < Math.min(5, matches.size()); i++) inspectVideo(site, matches.get(i));
    } finally { app.engine.clear(); }
  }

  public void testGuaziProtocol() throws Exception {
    reportFile = "guazi-protocol-report.json";
    getActivity();
    app = YingxiaApp.get();
    JSONObject site = null;
    for (JSONObject candidate : app.repository.sites())
      if ("瓜子".equals(candidate.optString("key"))) { site = Json.copy(candidate); break; }
    assertNotNull(site);
    String source;
    try (InputStream in = getActivity().getAssets().open("scripts/sites/Guazi.js")) {
      source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    try (ScriptRuntime runtime = new ScriptRuntime(getActivity(), site.optString("id"), site.optString("origin"))) {
      runtime.supply("assets://sites/Guazi.js", source + "\nglobalThis.guaziProbe = {call, host: () => base};");
      site.put("api", "assets://sites/Guazi.js");
      runtime.initSpider(site);
      record(Json.obj("host", runtime.call("guaziProbe.host()")));
      for (String title : new String[] {"狂飙", "流浪地球"}) {
        List<JSONObject> matches = Catalog.videos(runtime.spider(Json.obj("wd", title)));
        JSONObject selected = null;
        for (JSONObject candidate : matches) if (title.equals(candidate.optString("vod_name"))) { selected = candidate; break; }
        if (selected == null) continue;
        String id = selected.optString("vod_id");
        JSONObject detail = Catalog.videos(runtime.spider(Json.obj("ids", id))).get(0);
        JSONObject row = Json.obj("title", title, "id", id, "detail", detail);
        record(row);
        row.put("resources", runtime.object("guaziProbe.call('/App/Resource/Vurl/show', "
            + Json.obj("vurl_cloud_id", "2", "vod_d_id", id) + ")"));
        JSONArray plays = new JSONArray();
        row.put("plays", plays);
        Set<String> flags = new HashSet<>();
        for (JSONObject episode : Catalog.episodes(detail)) {
          if (!flags.add(episode.optString("flag"))) continue;
          JSONObject answer = runtime.spider(Json.obj("play", episode.optString("url"), "flag", episode.optString("flag")));
          plays.put(Json.obj("episode", episode, "answer", answer));
        }
        flush();
      }
    }
  }

  private void inspectVideo(JSONObject site, JSONObject listed) throws Exception {
    JSONObject result = Json.obj("stage", "detail", "id", listed.optString("vod_id"), "title", listed.optString("vod_name"));
    record(result);
    try {
      ArtworkRequest image = ArtworkRequest.parse(listed.optString("vod_pic"), site.optString("origin"));
      Http.Result response = app.http.get(image.url, image.headers);
      android.graphics.Bitmap bitmap = Artwork.decode(response.bytes, 175, 262);
      result.put("posterStatus", response.status).put("posterDecoded", bitmap != null)
          .put("posterUrl", image.url).put("posterHeaderNames", new JSONArray(image.headers.keySet()));
      if (bitmap != null) bitmap.recycle();
    } catch (Exception error) { result.put("posterError", error.toString()); }
    try {
      List<JSONObject> details = Catalog.videos(app.engine.call(site, Json.obj("ids", listed.optString("vod_id"))));
      if (details.isEmpty()) throw new IOException("No detail returned");
      JSONObject video = details.get(0);
      result.put("detailTitle", video.optString("vod_name"))
          .put("episodes", Catalog.episodes(video).size())
          .put("flags", video.optString("vod_play_from"))
          .put("episodeTextLength", video.optString("vod_play_url").length());
    } catch (Exception error) { result.put("detailError", error.toString()); }
    flush();
  }

  private void probe(String[] keys, int requiredSources) throws Exception {
    getActivity();
    app = YingxiaApp.get();
    String backup = app.repository.export();
    try {
      Http.Result response = app.http.get(ORIGIN);
      app.repository.importConfig(response.bytes, response.url, "饭太硬 · 实测");
      record(Json.obj("stage", "import", "sites", app.repository.sites().size()));
      int passed = 0;
      for (String key : keys) {
        JSONObject site = null;
        for (JSONObject candidate : app.repository.sites())
          if (key.equals(candidate.optString("key"))) { site = candidate; break; }
        if (site == null) continue;
        JSONObject result = Json.obj("source", key, "stage", "home");
        record(result);
        try {
          JSONObject home = app.engine.call(site, new JSONObject());
          List<JSONObject> videos = Catalog.videos(home);
          result.put("homeVideos", videos.size()).put("categories", Json.array(home.opt("class")).length());
          JSONArray categories = Json.array(home.opt("class"));
          if (minimumDurationMs > 0 && categories.length() > 0) {
            JSONObject category = categories.getJSONObject(0);
            List<JSONObject> listed = Catalog.videos(app.engine.call(site,
                Json.obj("t", category.optString("type_id"), "pg", "1")));
            result.put("categoryName", category.optString("type_name")).put("categoryVideos", listed.size());
            if (videos.isEmpty()) videos = listed;
          }
          result.put("stage", "search"); flush();
          List<JSONObject> search = Catalog.videos(app.engine.call(site, Json.obj("wd", searchTitle, "pg", "1", "quick", false)));
          result.put("searchResults", search.size());
          if (!search.isEmpty()) videos = search;
          if (videos.isEmpty()) throw new IOException("片库和搜索均无影片");
          JSONObject selected = videos.get(0);
          for (JSONObject candidate : videos)
            if (searchTitle.equals(candidate.optString("vod_name"))) { selected = candidate; break; }
          if (minimumDurationMs > 0 && exactTitle) assertEquals(searchTitle, selected.optString("vod_name"));
          result.put("stage", "detail"); flush();
          List<JSONObject> details = Catalog.videos(app.engine.call(site, Json.obj("ids", selected.optString("vod_id"))));
          if (details.isEmpty()) throw new IOException("未返回详情");
          JSONObject video = details.get(0);
          List<JSONObject> episodes = Catalog.episodes(video);
          result.put("title", video.optString("vod_name")).put("episodes", episodes.size());
          if (episodes.isEmpty()) throw new IOException("没有可选剧集");
          result.put("stage", "resolve"); flush();
          JSONObject play = app.engine.playback(site, video, episodes.get(episodeIndex));
          result.put("episode", episodes.get(episodeIndex).optString("name"));
          result.put("mediaHost", android.net.Uri.parse(play.optString("url")).getHost());
          result.put("mediaUrl", play.optString("url"));
          result.put("stage", "decode"); flush();
          verifyPlayback(play, result);
          result.put("passed", true).put("stage", "complete");
          passed++;
        } catch (Throwable error) {
          if (error instanceof ThreadDeath) throw (ThreadDeath) error;
          result.put("passed", false).put("error", error.toString());
          android.util.Log.e("Yingxia.LiveTest", key + " " + result.optString("stage"), error);
        } finally { flush(); }
        if (passed >= requiredSources) break;
      }
      assertTrue("Not all required sources decoded video; see files/" + reportFile, passed >= requiredSources);
    } finally {
      app.engine.clear();
      app.repository.restore(backup);
    }
  }

  private void verifyPlayback(JSONObject play, JSONObject result) throws Exception {
    Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(PlayerActivity.class.getName(), null, false);
    PlayerActivity activity = null;
    try {
      getInstrumentation().runOnMainSync(() -> PlayerActivity.open(getActivity(), play));
      activity = (PlayerActivity) monitor.waitForActivityWithTimeout(10000);
      if (activity == null) throw new IOException("播放器未启动");
      final PlayerActivity target = activity;
      Field field = PlayerActivity.class.getDeclaredField("player");
      field.setAccessible(true);
      long[] position = {0}; int[] frames = {0}; String[] failure = {null};
      long started = System.currentTimeMillis(), until = started + 75000;
      long nextSample = started;
      JSONArray samples = new JSONArray();
      result.put("samples", samples);
      while (System.currentTimeMillis() < until) {
        final JSONObject sample = new JSONObject();
        getInstrumentation().runOnMainSync(() -> {
          try {
            ExoPlayer player = (ExoPlayer) field.get(target);
            if (player == null) return;
            position[0] = player.getCurrentPosition();
            long duration = player.getDuration();
            result.put("durationMs", duration);
            if (minimumDurationMs > 0 && duration > 0 && duration < minimumDurationMs)
              failure[0] = "正片时长异常：" + duration + " ms，不能把短提示片算作播放成功";
            if (player.getVideoDecoderCounters() != null) frames[0] = player.getVideoDecoderCounters().renderedOutputBufferCount;
            sample.put("elapsedMs", System.currentTimeMillis() - started)
                .put("positionMs", position[0]).put("frames", frames[0])
                .put("durationMs", duration)
                .put("state", player.getPlaybackState()).put("playing", player.isPlaying())
                .put("playWhenReady", player.getPlayWhenReady())
                .put("bufferedMs", player.getTotalBufferedDuration())
                .put("windowFocus", target.hasWindowFocus());
            if (player.getPlayerError() != null) {
              Throwable error = player.getPlayerError();
              StringBuilder reason = new StringBuilder(player.getPlayerError().getErrorCodeName());
              for (int depth = 0; error != null && depth < 6; depth++, error = error.getCause())
                reason.append(" | ").append(error.toString());
              failure[0] = reason.toString();
            }
          } catch (Exception e) { failure[0] = e.toString(); }
        });
        if (System.currentTimeMillis() >= nextSample) {
          samples.put(sample);
          nextSample = System.currentTimeMillis() + 5000;
          flush();
        }
        if (position[0] >= requiredPositionMs && frames[0] > 0 || failure[0] != null) break;
        Thread.sleep(250);
      }
      result.put("positionMs", position[0]).put("renderedFrames", frames[0]);
      if (failure[0] != null) throw new IOException(failure[0]);
      if (position[0] < requiredPositionMs || frames[0] == 0)
        throw new IOException("75 秒内未确认所需播放进度和视频输出帧");
      if (minimumDurationMs > 0) {
        assertTrue("尚未确认正片总时长", result.optLong("durationMs") >= minimumDurationMs);
        captureFrame(target, "start");
        final int beforeSeek = frames[0];
        getInstrumentation().runOnMainSync(() -> {
          try { ((ExoPlayer) field.get(target)).seekTo(120000); }
          catch (IllegalAccessException error) { throw new RuntimeException(error); }
        });
        long seekUntil = System.currentTimeMillis() + 45000;
        boolean[] advanced = {false};
        while (System.currentTimeMillis() < seekUntil && !advanced[0]) {
          getInstrumentation().runOnMainSync(() -> {
            try {
              ExoPlayer player = (ExoPlayer) field.get(target);
              int count = player.getVideoDecoderCounters().renderedOutputBufferCount;
              advanced[0] = player.isPlaying() && player.getCurrentPosition() >= 125000 && count > beforeSeek + 30;
              result.put("seekPositionMs", player.getCurrentPosition()).put("seekFrames", count);
            } catch (Exception error) { throw new RuntimeException(error); }
          });
          Thread.sleep(250);
        }
        assertTrue("跳转到两分钟后未恢复实际解码", advanced[0]);
        captureFrame(target, "seek");
      }
    } finally {
      if (activity != null) {
        final PlayerActivity target = activity;
        getInstrumentation().runOnMainSync(target::finish);
        // An idle main queue does not imply onStop/onDestroy has run. Wait before restoring
        // the library, otherwise the player's final progress write recreates test history.
        boolean[] destroyed = {false};
        long until = System.currentTimeMillis() + 10000;
        while (!destroyed[0] && System.currentTimeMillis() < until) {
          getInstrumentation().runOnMainSync(() -> destroyed[0] = target.isDestroyed());
          if (!destroyed[0]) Thread.sleep(100);
        }
        assertTrue("播放器未销毁，不能恢复测试前的历史", destroyed[0]);
      }
      getInstrumentation().removeMonitor(monitor);
    }
  }

  private void captureFrame(PlayerActivity activity, String phase) throws Exception {
    android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(960, 540, android.graphics.Bitmap.Config.ARGB_8888);
    java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
    int[] status = {-1};
    getInstrumentation().runOnMainSync(() -> {
      android.view.View surface = ((androidx.media3.ui.PlayerView)
          activity.findViewById(androidx.media3.ui.R.id.exo_content_frame).getParent()).getVideoSurfaceView();
      android.view.PixelCopy.request((android.view.SurfaceView) surface, bitmap,
          value -> { status[0] = value; done.countDown(); }, new android.os.Handler(android.os.Looper.getMainLooper()));
    });
    assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(android.view.PixelCopy.SUCCESS, status[0]);
    try (OutputStream out = new FileOutputStream(new File(getActivity().getFilesDir(), reportFile.replace(".json", "-" + phase + ".png")))) {
      bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
    } finally { bitmap.recycle(); }
  }

  private void record(JSONObject result) throws Exception { results.put(result); flush(); }
  private void flush() throws Exception {
    try (OutputStream out = new FileOutputStream(new File(getActivity().getFilesDir(), reportFile))) {
      out.write(results.toString(2).getBytes(StandardCharsets.UTF_8));
    }
  }
}
