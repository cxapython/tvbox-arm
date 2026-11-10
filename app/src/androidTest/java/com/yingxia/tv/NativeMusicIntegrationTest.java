package com.yingxia.tv;

import android.app.Instrumentation;
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

/** Opt-in native QQ/Kuwo/Migu catalog + primary/fallback stream verification. */
@SuppressWarnings("deprecation")
public final class NativeMusicIntegrationTest extends ActivityInstrumentationTestCase2<MainActivity> {
  public NativeMusicIntegrationTest() { super(MainActivity.class); }

  public void testNativeMusicPlatforms() throws Exception {
    MainActivity main = getActivity();
    JSONArray results = new JSONArray();
    List<String> failures = new ArrayList<>();
    for (String source : new String[] {"tencent", "kuwo", "migu"}) {
      JSONObject sample = Json.obj("source", source);
      results.put(sample);
      Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(MusicPlayerActivity.class.getName(), null, false);
      MusicPlayerActivity activity = null;
      try {
        MusicClient client = new MusicClient();
        List<JSONObject> songs = client.search("蓝莲花 许巍", source, 1);
        assertFalse("Empty " + source + " search", songs.isEmpty());
        JSONObject song = songs.get(0);
        for (JSONObject candidate : songs)
          if (candidate.optString("name").equals("蓝莲花") && candidate.optString("artist").equals("许巍")) { song = candidate; break; }
        sample.put("song", song.optString("name")).put("songId", song.optString("songId")).put("expectedDurationMs", song.optLong("duration"));
        JSONObject play = client.play(song, MusicResolver.FLAC);
        sample.put("resolvedSource", play.optString("source")).put("sourceFallback", play.optBoolean("sourceFallback")).put("upstream", play.optString("upstream")).put("reportedQuality", play.optString("actualQuality"));
        getInstrumentation().runOnMainSync(() -> PlayerActivity.open(main, play));
        activity = (MusicPlayerActivity) monitor.waitForActivityWithTimeout(10000);
        assertNotNull(activity);
        final MusicPlayerActivity target = activity;
        Field field = MusicPlayerActivity.class.getDeclaredField("player"); field.setAccessible(true);
        waitDecoded(target, field, 4000, sample);
        assertTrue("Duration mismatch / preview: " + sample, Math.abs(sample.getLong("durationMs") - song.optLong("duration")) < 12000);
        getInstrumentation().runOnMainSync(() -> {
          try { ((ExoPlayer) field.get(target)).seekTo(120000); }
          catch (Exception error) { throw new RuntimeException(error); }
        });
        waitDecoded(target, field, 123000, sample);
        getInstrumentation().runOnMainSync(target::finish);
        getInstrumentation().waitForIdleSync();
        activity = null;
        if (source.equals("tencent")) {
          List<JSONObject> sections = client.browse(source);
          assertFalse(sections.isEmpty()); sample.put("shelves", sections.size());
          JSONObject chart = sections.get(0).getJSONArray("items").getJSONObject(0);
          assertFalse(client.tracks(chart).isEmpty());
          for (String kind : new String[] {"album", "playlist"}) {
            List<JSONObject> collections = client.collections("许巍", source, kind, 1);
            assertFalse(kind + " search empty", collections.isEmpty());
            List<JSONObject> tracks = client.tracks(collections.get(0));
            assertFalse(kind + " tracks empty", tracks.isEmpty()); sample.put(kind + "Tracks", tracks.size());
          }
        }
        sample.put("passed", true);
      } catch (Throwable error) {
        sample.put("error", Json.message(error));
        failures.add(source + ": " + Json.message(error));
      } finally {
        if (activity != null) { MusicPlayerActivity target = activity; getInstrumentation().runOnMainSync(target::finish); }
        getInstrumentation().removeMonitor(monitor);
        try (OutputStream out = new FileOutputStream(new File(main.getFilesDir(), "native-music-live-report.json"))) {
          out.write(results.toString(2).getBytes(StandardCharsets.UTF_8));
        }
      }
    }
    assertTrue(String.join("; ", failures), failures.isEmpty());
  }

  public void testCompanionCatalogFallback() throws Exception {
    MainActivity main = getActivity();
    MusicClient client = new MusicClient();
    JSONObject song = null;
    for (JSONObject candidate : client.search("蓝莲花 许巍", "tencent", 1))
      if (candidate.optString("name").equals("蓝莲花") && candidate.optString("artist").equals("许巍")) { song = candidate; break; }
    assertNotNull(song);
    android.content.SharedPreferences settings = YingxiaApp.get().repository.settings;
    long original = settings.getLong("music.vkeys.retryAfter", 0);
    try {
      settings.edit().putLong("music.vkeys.retryAfter", System.currentTimeMillis() + 600000).commit();
      JSONObject play = client.play(song, MusicResolver.FLAC);
      assertTrue(play.optBoolean("sourceFallback"));
      assertFalse(play.optString("source").equals("tencent"));
      assertEquals(song.optString("id"), play.optString("id"));
      assertEquals(song.optString("songId"), play.optString("catalogSongId"));
      JSONObject report = Json.obj("passed", true, "catalogSource", play.optString("catalogSource"),
          "resolvedSource", play.optString("source"), "upstream", play.optString("upstream"), "identityPreserved", true);
      try (OutputStream out = new FileOutputStream(new File(main.getFilesDir(), "music-companion-report.json"))) {
        out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
      }
    } finally { settings.edit().putLong("music.vkeys.retryAfter", original).commit(); }
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
              .put("measuredBitrate", player.getAudioFormat() == null ? -1 : player.getAudioFormat().averageBitrate);
          ready[0] = player.isPlaying() && frames > 0 && player.getCurrentPosition() >= position && player.getDuration() > 150000;
        } catch (Exception error) { throw new RuntimeException(error); }
      });
      Thread.sleep(200);
    }
    assertTrue("Audio did not decode: " + sample, ready[0]);
  }
}
