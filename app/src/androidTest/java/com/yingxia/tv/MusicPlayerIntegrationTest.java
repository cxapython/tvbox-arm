package com.yingxia.tv;

import android.app.Instrumentation;
import android.test.ActivityInstrumentationTestCase2;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.media3.exoplayer.ExoPlayer;
import com.yingxia.tv.core.*;
import com.yingxia.tv.data.MusicClient;
import com.yingxia.tv.ui.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Real queue transport, paused quality changes, library controls and lifecycle release. */
@SuppressWarnings("deprecation")
public final class MusicPlayerIntegrationTest extends ActivityInstrumentationTestCase2<MainActivity> {
  public MusicPlayerIntegrationTest() { super(MainActivity.class); }

  public void testQueueQualityAndRelease() throws Exception {
    MainActivity main = getActivity();
    MusicClient client = new MusicClient();
    JSONObject first = client.gd.metadata("168091");
    List<JSONObject> songs = client.search("曾经的你 许巍", "netease", 1);
    JSONObject second = null;
    for (JSONObject song : songs) if (song.optString("name").equals("曾经的你") && song.optString("artist").equals("许巍")) { second = song; break; }
    assertNotNull(second);
    List<JSONObject> queue = Arrays.asList(first, second);
    JSONObject play = client.play(first, MusicResolver.HIGH), report = new JSONObject();
    Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(MusicPlayerActivity.class.getName(), null, false);
    MusicPlayerActivity activity = null;
    android.content.SharedPreferences settings = YingxiaApp.get().repository.settings;
    String originalQuality = settings.getString("musicQuality", MusicResolver.HIGH);
    try {
      getInstrumentation().runOnMainSync(() -> MusicPlayerActivity.open(main, play, queue, 0, "许巍 · 验证歌单"));
      activity = (MusicPlayerActivity) monitor.waitForActivityWithTimeout(10000);
      assertNotNull(activity);
      MusicPlayerActivity target = activity;
      Field playerField = field("player"), indexField = field("index"), playField = field("play"), busyField = field("busy");
      await(() -> {
        ExoPlayer player = (ExoPlayer) playerField.get(target);
        return player != null && player.isPlaying() && player.getCurrentPosition() > 2000;
      });
      getInstrumentation().runOnMainSync(() -> target.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT)));
      await(() -> indexField.getInt(target) == 1 && !busyField.getBoolean(target) && ((ExoPlayer) playerField.get(target)).isPlaying());
      getInstrumentation().runOnMainSync(() -> {
        try { ((ExoPlayer) playerField.get(target)).seekTo(0); }
        catch (Exception error) { throw new RuntimeException(error); }
        target.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS));
      });
      await(() -> indexField.getInt(target) == 0 && !busyField.getBoolean(target) && ((ExoPlayer) playerField.get(target)).isPlaying());
      report.put("nextPrevious", true);
      getInstrumentation().runOnMainSync(() -> {
        target.getWindow().getDecorView().findViewWithTag("music-pause").performClick();
        try { ((ExoPlayer) playerField.get(target)).seekTo(60000); }
        catch (Exception error) { throw new RuntimeException(error); }
        target.getWindow().getDecorView().findViewWithTag("music-quality").performClick();
      });
      getInstrumentation().waitForIdleSync();
      clickText("FLAC 无损");
      await(() -> !busyField.getBoolean(target) && ((JSONObject) playField.get(target)).optString("quality").equals("flac")
          && ((ExoPlayer) playerField.get(target)).getAudioFormat() != null);
      getInstrumentation().runOnMainSync(() -> {
        try {
          ExoPlayer player = (ExoPlayer) playerField.get(target);
          assertFalse("Quality switch resumed paused playback", player.getPlayWhenReady());
          assertTrue(Math.abs(player.getCurrentPosition() - 60000) < 1000);
          assertEquals("audio/flac", player.getAudioFormat().sampleMimeType);
        } catch (Exception error) { throw new RuntimeException(error); }
      });
      report.put("pausedQualitySwitchPreservedPosition", true);
      boolean originalFavorite = YingxiaApp.get().repository.contains("favorites", first.optString("id"));
      getInstrumentation().runOnMainSync(() -> target.getWindow().getDecorView().findViewWithTag("music-favorite").performClick());
      assertEquals(!originalFavorite, YingxiaApp.get().repository.contains("favorites", first.optString("id")));
      getInstrumentation().runOnMainSync(() -> target.getWindow().getDecorView().findViewWithTag("music-favorite").performClick());
      report.put("favoriteToggle", true);
      getInstrumentation().runOnMainSync(target::finish);
      await(() -> playerField.get(target) == null);
      activity = null;
      report.put("releasedAfterExit", true).put("passed", true);
    } finally {
      if (activity != null) { MusicPlayerActivity target = activity; getInstrumentation().runOnMainSync(target::finish); }
      getInstrumentation().removeMonitor(monitor);
      settings.edit().putString("musicQuality", originalQuality).apply();
      try (OutputStream out = new FileOutputStream(new File(main.getFilesDir(), "music-player-controls-report.json"))) {
        out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
      }
    }
  }

  private Field field(String name) throws Exception { Field field = MusicPlayerActivity.class.getDeclaredField(name); field.setAccessible(true); return field; }
  private interface Check { boolean ready() throws Exception; }
  private void await(Check check) throws Exception {
    long until = System.currentTimeMillis() + 45000;
    boolean[] ready = {false};
    while (!ready[0] && System.currentTimeMillis() < until) {
      getInstrumentation().runOnMainSync(() -> {
        try { ready[0] = check.ready(); }
        catch (Exception error) { throw new RuntimeException(error); }
      });
      Thread.sleep(150);
    }
    assertTrue("Music player state timed out", ready[0]);
  }
  private void clickText(String text) throws Exception {
    long until = System.currentTimeMillis() + 5000;
    while (System.currentTimeMillis() < until) {
      AccessibilityNodeInfo root = getInstrumentation().getUiAutomation().getRootInActiveWindow();
      if (root != null) for (AccessibilityNodeInfo found : root.findAccessibilityNodeInfosByText(text)) {
        if (!text.contentEquals(found.getText())) continue;
        if (found.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
        // Android's CheckedTextView delegates clicks to ListView but may report clickable=false.
        android.graphics.Rect bounds = new android.graphics.Rect(); found.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) continue;
        long now = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now, 0, bounds.centerX(), bounds.centerY(), 0);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 80, 1, bounds.centerX(), bounds.centerY(), 0);
        getInstrumentation().sendPointerSync(down); getInstrumentation().sendPointerSync(up);
        down.recycle(); up.recycle();
        return;
      }
      Thread.sleep(100);
    }
    android.graphics.Bitmap bitmap = getInstrumentation().getUiAutomation().takeScreenshot();
    if (bitmap != null) try (OutputStream out = new FileOutputStream(new File(getActivity().getFilesDir(), "music-control-failure.png"))) {
      bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
    }
    fail("No visible control: " + text);
  }
}
