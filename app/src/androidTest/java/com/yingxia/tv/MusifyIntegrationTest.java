package com.yingxia.tv;

import android.app.Instrumentation;
import android.test.ActivityInstrumentationTestCase2;
import androidx.media3.common.Player;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import com.yingxia.tv.core.*;
import com.yingxia.tv.data.*;
import com.yingxia.tv.ui.*;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Opt-in real catalog, decoding, seeking, download and cache-only playback verification. */
@SuppressWarnings("deprecation")
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class MusifyIntegrationTest extends ActivityInstrumentationTestCase2<MainActivity> {
  public MusifyIntegrationTest() { super(MainActivity.class); }

  public void testVisibleReturnAndSearchToPlayer() throws Exception {
    MainActivity main = getActivity();
    Method select = MainActivity.class.getDeclaredMethod("select", int.class);
    Method music = MainActivity.class.getDeclaredMethod("music", String.class, String.class, int.class);
    Field current = MainActivity.class.getDeclaredField("currentPage");
    select.setAccessible(true); music.setAccessible(true); current.setAccessible(true);
    getInstrumentation().runOnMainSync(() -> {
      try { select.invoke(main, 3); music.invoke(main, "musify", "", 1); }
      catch (Exception error) { throw new RuntimeException(error); }
      android.widget.EditText input = input(main.getWindow().getDecorView());
      assertNotNull(input);
      input.setText("Rockstar Hands Up Mix");
      named(main.getWindow().getDecorView(), "搜索").performClick();
    });
    long until = System.currentTimeMillis() + 30000;
    boolean[] loaded = {false};
    while (!loaded[0] && System.currentTimeMillis() < until) {
      getInstrumentation().runOnMainSync(() -> loaded[0] = named(main.getWindow().getDecorView(), "Rockstar (Hands Up Mix)") != null);
      Thread.sleep(150);
    }
    assertTrue("Search result not rendered", loaded[0]);
    Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(MusicPlayerActivity.class.getName(), null, false);
    MusicPlayerActivity activity = null;
    JSONObject report = new JSONObject();
    try {
      getInstrumentation().runOnMainSync(() -> {
        android.view.View row = named(main.getWindow().getDecorView(), "Rockstar (Hands Up Mix)");
        while (!row.isClickable()) row = (android.view.View) row.getParent();
        row.performClick();
      });
      activity = (MusicPlayerActivity) monitor.waitForActivityWithTimeout(30000);
      assertNotNull("Tapping a song did not open the music player", activity);
      MusicPlayerActivity target = activity;
      Field playerField = MusicPlayerActivity.class.getDeclaredField("player"); playerField.setAccessible(true);
      waitDecoded((ExoPlayer) playerField.get(target), 4000, report);
      getInstrumentation().runOnMainSync(() -> {
        android.widget.TextView back = target.getWindow().getDecorView().findViewWithTag("music-back");
        android.graphics.Rect bounds = new android.graphics.Rect();
        assertTrue(back.getGlobalVisibleRect(bounds));
        assertTrue(back.getText().toString().contains("返回"));
        assertTrue(back.isFocusable());
      });
      android.graphics.Bitmap[] screenshot = {null};
      getInstrumentation().runOnMainSync(() -> {
        android.view.View decor = target.getWindow().getDecorView();
        screenshot[0] = android.graphics.Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        decor.draw(new android.graphics.Canvas(screenshot[0]));
      });
      try (OutputStream out = new FileOutputStream(new File(main.getFilesDir(), "music-return-player.png"))) {
        screenshot[0].compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
      }
      screenshot[0].recycle();
      getInstrumentation().runOnMainSync(() -> target.getWindow().getDecorView().findViewWithTag("music-back").performClick());
      getInstrumentation().waitForIdleSync();
      until = System.currentTimeMillis() + 5000;
      while (!target.isDestroyed() && System.currentTimeMillis() < until) Thread.sleep(100);
      assertTrue(target.isDestroyed());
      assertNull(playerField.get(target));
      activity = null;
      getInstrumentation().runOnMainSync(() -> {
        try {
          NavigationSnapshot page = (NavigationSnapshot) current.get(main);
          assertEquals("Rockstar Hands Up Mix", page.arguments().optString("query"));
          assertNotNull(named(main.getWindow().getDecorView(), "Rockstar (Hands Up Mix)"));
          main.getWindow().getDecorView().findViewWithTag("page-back").performClick();
          page = (NavigationSnapshot) current.get(main);
          assertEquals("music", page.route());
          assertEquals("", page.arguments().optString("query"));
          assertEquals("musify", page.arguments().optString("provider"));
          main.getWindow().getDecorView().findViewWithTag("page-back").performClick();
          assertEquals("home", ((NavigationSnapshot) current.get(main)).route());
        } catch (Exception error) { throw new RuntimeException(error); }
      });
      report.put("songTapOpensPlayer", true).put("visiblePlayerBack", true).put("searchPreserved", true)
          .put("returnToMusicThenHome", true).put("playerReleased", true).put("passed", true);
    } finally {
      if (activity != null) { MusicPlayerActivity target = activity; getInstrumentation().runOnMainSync(target::finish); }
      getInstrumentation().removeMonitor(monitor);
      try (OutputStream out = new FileOutputStream(new File(main.getFilesDir(), "music-return-report.json"))) {
        out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
      }
    }
  }

  private android.widget.EditText input(android.view.View view) {
    if (view instanceof android.widget.EditText) return (android.widget.EditText) view;
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) { android.widget.EditText found = input(group.getChildAt(i)); if (found != null) return found; }
    }
    return null;
  }

  private android.view.View named(android.view.View view, String text) {
    if (view instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView) view).getText())) return view;
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) { android.view.View found = named(group.getChildAt(i), text); if (found != null) return found; }
    }
    return null;
  }

  public void testSearchPlayDownloadAndOffline() throws Exception {
    MainActivity main = getActivity();
    MusicClient client = new MusicClient();
    JSONObject report = new JSONObject();
    Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(MusicPlayerActivity.class.getName(), null, false);
    MusicPlayerActivity activity = null;
    ExoPlayer[] offline = {null};
    JSONObject downloadItem = null;
    boolean preexisting = false;
    try {
      JSONObject page = client.musify.searchPage("Rockstar Hands Up Mix", 1);
      JSONArray songs = page.getJSONArray("items");
      assertTrue(songs.length() > 0);
      JSONObject song = null;
      for (JSONObject candidate : Json.objects(songs))
        if (candidate.optString("songId").equals("4422849")) song = candidate;
      assertNotNull("Exact Empyre One mix missing", song);
      report.put("searchRows", songs.length()).put("hasNext", page.getBoolean("hasNext"));
      JSONObject next = client.musify.searchPage("Rockstar Hands Up Mix", 2);
      assertTrue(next.getJSONArray("items").length() > 0);
      assertFalse(songs.getJSONObject(0).getString("id").equals(next.getJSONArray("items").getJSONObject(0).getString("id")));
      String artist = MusifyCatalog.BASE + "/en/artist/empyre-one-164584";
      JSONObject artistFirst = client.musify.searchPage(artist, 1), artistNext = client.musify.searchPage(artist, 2);
      assertTrue(artistFirst.getBoolean("hasNext"));
      assertTrue(artistNext.getJSONArray("items").length() > 0);
      assertFalse(artistFirst.getJSONArray("items").getJSONObject(0).getString("id").equals(
          artistNext.getJSONArray("items").getJSONObject(0).getString("id")));
      report.put("artistRows", artistFirst.getJSONArray("items").length()).put("artistPaginationPassed", true);
      JSONObject play = client.play(song, MusicResolver.FLAC);
      assertEquals("musify", play.getString("source"));
      assertTrue(play.getBoolean("fixedQuality"));
      assertFalse(play.optBoolean("sourceFallback"));
      assertTrue(client.lyricData(song).getString("lyric").isEmpty());
      getInstrumentation().runOnMainSync(() -> MusicPlayerActivity.open(main, play));
      activity = (MusicPlayerActivity) monitor.waitForActivityWithTimeout(10000);
      assertNotNull(activity);
      Field field = MusicPlayerActivity.class.getDeclaredField("player");
      field.setAccessible(true);
      ExoPlayer live = (ExoPlayer) field.get(activity);
      waitDecoded(live, 4000, report);
      assertTrue(Math.abs(report.getLong("durationMs") - 313600) < 2000);
      getInstrumentation().runOnMainSync(() -> live.seekTo(120000));
      waitDecoded(live, 123000, report);
      report.put("streamSeekPassed", true);
      MusicPlayerActivity close = activity;
      getInstrumentation().runOnMainSync(close::finish);
      getInstrumentation().waitForIdleSync();
      activity = null;

      downloadItem = play;
      preexisting = YingxiaApp.get().repository.contains("downloads", play.getString("id"));
      getInstrumentation().runOnMainSync(() -> OfflineService.add(main, play));
      Download[] saved = {null};
      long deadline = System.currentTimeMillis() + 360000;
      long nextProgress = 0;
      do {
        getInstrumentation().runOnMainSync(() -> {
          try { saved[0] = OfflineService.manager(main).getDownloadIndex().getDownload(play.optString("id")); }
          catch (IOException error) { throw new RuntimeException(error); }
        });
        if (saved[0] != null && System.currentTimeMillis() >= nextProgress) {
          report.put("downloadBytes", saved[0].getBytesDownloaded()).put("downloadState", saved[0].state);
          android.util.Log.i("MusifyTest", "Download bytes=" + saved[0].getBytesDownloaded() + " state=" + saved[0].state);
          nextProgress = System.currentTimeMillis() + 20000;
        }
        if (saved[0] != null && (saved[0].state == Download.STATE_COMPLETED || saved[0].state == Download.STATE_FAILED)) break;
        Thread.sleep(300);
      } while (System.currentTimeMillis() < deadline);
      assertNotNull("Download did not start", saved[0]);
      assertEquals("Download did not complete", Download.STATE_COMPLETED, saved[0].state);
      assertTrue(saved[0].getBytesDownloaded() > 10_000_000);
      report.put("downloadBytes", saved[0].getBytesDownloaded()).put("downloadState", saved[0].state);
      getInstrumentation().runOnMainSync(() -> {
        // No upstream factory: this player can only read the downloaded cache.
        CacheDataSource.Factory cacheOnly = new CacheDataSource.Factory().setCache(OfflineService.cache(main))
            .setUpstreamDataSourceFactory(null).setCacheWriteDataSinkFactory(null);
        offline[0] = new ExoPlayer.Builder(main).setMediaSourceFactory(new DefaultMediaSourceFactory(cacheOnly)).build();
        offline[0].setMediaItem(saved[0].request.toMediaItem());
        offline[0].prepare();
        offline[0].play();
      });
      JSONObject offlineReport = new JSONObject();
      waitDecoded(offline[0], 3000, offlineReport);
      getInstrumentation().runOnMainSync(() -> offline[0].seekTo(300000));
      waitDecoded(offline[0], 303000, offlineReport);
      report.put("offline", offlineReport).put("offlineWithoutNetworkPassed", true).put("passed", true);
    } catch (Throwable error) {
      report.put("error", Json.message(error));
      throw error;
    } finally {
      if (activity != null) { MusicPlayerActivity close = activity; getInstrumentation().runOnMainSync(close::finish); }
      if (offline[0] != null) getInstrumentation().runOnMainSync(offline[0]::release);
      if (downloadItem != null && !preexisting) {
        JSONObject item = downloadItem;
        getInstrumentation().runOnMainSync(() -> OfflineService.remove(main, item));
      }
      getInstrumentation().removeMonitor(monitor);
      try (OutputStream out = new FileOutputStream(new File(main.getFilesDir(), "musify-live-report.json"))) {
        out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
      }
    }
  }

  private void waitDecoded(ExoPlayer player, long position, JSONObject result) throws Exception {
    long deadline = System.currentTimeMillis() + 45000;
    boolean[] ready = {false};
    while (!ready[0] && System.currentTimeMillis() < deadline) {
      getInstrumentation().runOnMainSync(() -> {
        try {
          assertNull("Decoder failure: " + player.getPlayerError(), player.getPlayerError());
          int buffers = player.getAudioDecoderCounters() == null ? 0 : player.getAudioDecoderCounters().renderedOutputBufferCount;
          result.put("positionMs", player.getCurrentPosition()).put("durationMs", player.getDuration()).put("decodedBuffers", buffers)
              .put("mime", player.getAudioFormat() == null ? "" : player.getAudioFormat().sampleMimeType)
              .put("bitrate", player.getAudioFormat() == null ? -1 : player.getAudioFormat().averageBitrate);
          ready[0] = player.getPlaybackState() == Player.STATE_READY && player.isPlaying() && buffers > 0 && player.getCurrentPosition() >= position;
        } catch (JSONException error) { throw new RuntimeException(error); }
      });
      Thread.sleep(200);
    }
    assertTrue("Audio failed to decode: " + result, ready[0]);
  }
}
