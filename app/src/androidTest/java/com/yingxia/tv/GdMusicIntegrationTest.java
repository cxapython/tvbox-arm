package com.yingxia.tv;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.test.ActivityInstrumentationTestCase2;
import androidx.media3.exoplayer.ExoPlayer;
import com.yingxia.tv.core.*;
import com.yingxia.tv.data.MusicClient;
import com.yingxia.tv.ui.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Opt-in public GD/NetEase integration: catalog UI, collections, three tiers and real decoding. */
@SuppressWarnings("deprecation")
public final class GdMusicIntegrationTest extends ActivityInstrumentationTestCase2<MainActivity> {
  public GdMusicIntegrationTest() { super(MainActivity.class); }

  public void testGdCatalogAndPlayback() throws Exception {
    MainActivity main = getActivity();
    JSONObject report = new JSONObject();
    Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(MusicPlayerActivity.class.getName(), null, false);
    MusicPlayerActivity playerActivity = null;
    try {
      Method music = MainActivity.class.getDeclaredMethod("music", String.class, String.class, int.class);
      music.setAccessible(true);
      getInstrumentation().runOnMainSync(() -> {
        try { music.invoke(main, "netease", "", 1); }
        catch (Exception error) { throw new RuntimeException(error); }
      });
      Field pageField = MainActivity.class.getDeclaredField("currentPage");
      pageField.setAccessible(true);
      Object[] loaded = {null};
      long until = System.currentTimeMillis() + 30000;
      while (loaded[0] == null && System.currentTimeMillis() < until) {
        getInstrumentation().runOnMainSync(() -> {
          try { loaded[0] = ((NavigationSnapshot) pageField.get(main)).data(); }
          catch (Exception error) { throw new RuntimeException(error); }
        });
        Thread.sleep(200);
      }
      assertNotNull("Music browse UI did not load", loaded[0]);
      JSONArray sections = ((JSONObject) loaded[0]).getJSONArray("sections");
      assertTrue(sections.length() > 0);
      report.put("shelves", sections.length());
      screenshot("gd-music-browse.png");
      MusicClient client = new MusicClient();
      JSONObject chart = sections.getJSONObject(0).getJSONArray("items").getJSONObject(0);
      List<JSONObject> chartTracks = client.gd.tracks(chart);
      assertFalse(chartTracks.isEmpty());
      report.put("chart", chart.getString("name")).put("chartTracks", chartTracks.size());
      for (String kind : new String[] {"album", "playlist"}) {
        List<JSONObject> collections = client.gd.searchCollections("许巍", kind, 1);
        assertFalse(kind + " search empty", collections.isEmpty());
        List<JSONObject> tracks = client.gd.tracks(collections.get(0));
        assertFalse(kind + " tracks empty", tracks.isEmpty());
        report.put(kind + "Tracks", tracks.size());
      }
      List<JSONObject> songs = client.search("蓝莲花 许巍", "netease", 1);
      assertFalse(songs.isEmpty());
      JSONObject song = songs.get(0);
      for (JSONObject candidate : songs)
        if (candidate.optString("name").equals("蓝莲花") && candidate.optString("artist").contains("许巍")) { song = candidate; break; }
      assertTrue("Song has no complete duration", song.optLong("duration") > 150000);
      assertFalse("Song has no cover", song.optString("poster").isEmpty());
      report.put("song", song.optString("name")).put("artist", song.optString("artist")).put("songId", song.optString("songId"))
          .put("expectedDurationMs", song.optLong("duration"));
      JSONObject lyric = client.lyricData(song);
      assertNotNull(MusicLyrics.parse(lyric.optString("lyric"), lyric.optString("translation")));
      JSONArray tiers = new JSONArray();
      report.put("tiers", tiers);
      Field playerField = MusicPlayerActivity.class.getDeclaredField("player");
      playerField.setAccessible(true);
      for (String quality : new String[] {MusicResolver.STANDARD, MusicResolver.HIGH, MusicResolver.FLAC}) {
        JSONObject stream = client.gd.resolve(song.optString("songId"), quality);
        JSONObject play = Json.copy(song);
        for (String key : Json.keys(stream)) play.put(key, stream.get(key));
        play.put("quality", quality).put("music", true).put("episode", song.optString("artist"));
        getInstrumentation().removeMonitor(monitor);
        monitor = getInstrumentation().addMonitor(MusicPlayerActivity.class.getName(), null, false);
        getInstrumentation().runOnMainSync(() -> PlayerActivity.open(main, play));
        playerActivity = (MusicPlayerActivity) monitor.waitForActivityWithTimeout(10000);
        assertNotNull(playerActivity);
        final MusicPlayerActivity target = playerActivity;
        JSONObject sample = Json.obj("requestedQuality", quality, "reportedBitrate", stream.optString("actualQuality"));
        tiers.put(sample);
        waitDecoded(target, playerField, 4000, sample);
        assertTrue("Preview or wrong duration: " + sample, Math.abs(sample.getLong("durationMs") - song.optLong("duration")) < 15000);
        getInstrumentation().runOnMainSync(() -> {
          try { ((ExoPlayer) playerField.get(target)).seekTo(120000); }
          catch (Exception error) { throw new RuntimeException(error); }
        });
        waitDecoded(target, playerField, 123000, sample);
        if (quality.equals(MusicResolver.HIGH)) {
          screenshot("gd-music-playing.png");
          getInstrumentation().runOnMainSync(() -> target.getWindow().getDecorView().findViewWithTag("music-pause").performClick());
          getInstrumentation().runOnMainSync(() -> {
            try { assertFalse(((ExoPlayer) playerField.get(target)).getPlayWhenReady()); }
            catch (Exception error) { throw new RuntimeException(error); }
          });
          getInstrumentation().runOnMainSync(() -> target.getWindow().getDecorView().findViewWithTag("music-lyrics-toggle").performClick());
          getInstrumentation().waitForIdleSync();
          Thread.sleep(400);
          Field lyricColumn = MusicPlayerActivity.class.getDeclaredField("lyricColumn");
          lyricColumn.setAccessible(true);
          getInstrumentation().runOnMainSync(() -> {
            try { assertEquals(android.view.View.GONE, ((android.view.View) lyricColumn.get(target)).getVisibility()); }
            catch (Exception error) { throw new RuntimeException(error); }
          });
          screenshot("gd-music-cover.png");
          getInstrumentation().runOnMainSync(() -> target.getWindow().getDecorView().findViewWithTag("music-pause").performClick());
          report.put("pauseResumeAndLyricsToggle", true);
        }
        getInstrumentation().runOnMainSync(target::finish);
        playerActivity = null;
        getInstrumentation().waitForIdleSync();
      }
      report.put("passed", true);
    } finally {
      if (playerActivity != null) {
        MusicPlayerActivity target = playerActivity;
        getInstrumentation().runOnMainSync(target::finish);
      }
      getInstrumentation().removeMonitor(monitor);
      try (OutputStream out = new FileOutputStream(new File(main.getFilesDir(), "gd-music-live-report.json"))) {
        out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
      }
    }
  }

  private void waitDecoded(MusicPlayerActivity target, Field field, long position, JSONObject sample) throws Exception {
    long until = System.currentTimeMillis() + 45000;
    boolean[] ready = {false};
    while (!ready[0] && System.currentTimeMillis() < until) {
      getInstrumentation().runOnMainSync(() -> {
        try {
          ExoPlayer player = (ExoPlayer) field.get(target);
          if (player == null) return;
          int frames = player.getAudioDecoderCounters() == null ? 0 : player.getAudioDecoderCounters().renderedOutputBufferCount;
          sample.put("positionMs", player.getCurrentPosition()).put("durationMs", player.getDuration()).put("decodedBuffers", frames)
              .put("mime", player.getAudioFormat() == null ? "" : player.getAudioFormat().sampleMimeType)
              .put("measuredBitrate", player.getAudioFormat() == null ? -1 : player.getAudioFormat().averageBitrate)
              .put("error", player.getPlayerError() == null ? "" : player.getPlayerError().getErrorCodeName());
          ready[0] = player.isPlaying() && frames > 0 && player.getCurrentPosition() >= position && player.getDuration() > 150000;
        } catch (Exception error) { throw new RuntimeException(error); }
      });
      Thread.sleep(200);
    }
    assertTrue("GD audio did not decode: " + sample, ready[0]);
  }

  private void screenshot(String name) throws Exception {
    Bitmap bitmap = getInstrumentation().getUiAutomation().takeScreenshot();
    assertNotNull(bitmap);
    try (OutputStream out = new FileOutputStream(new File(getActivity().getFilesDir(), name))) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
    } finally { bitmap.recycle(); }
  }
}
