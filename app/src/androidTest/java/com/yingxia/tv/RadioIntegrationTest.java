package com.yingxia.tv;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.test.ActivityInstrumentationTestCase2;
import android.widget.TextView;
import androidx.media3.exoplayer.ExoPlayer;
import com.yingxia.tv.core.*;
import com.yingxia.tv.ui.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Opt-in real ItaloSound stream, both qualities, metadata, controls and lifecycle. */
@SuppressWarnings("deprecation")
public final class RadioIntegrationTest extends ActivityInstrumentationTestCase2<MainActivity> {
  public RadioIntegrationTest() { super(MainActivity.class); }

  public void testItaloDanceBothQualitiesAndNowPlaying() throws Exception {
    getActivity();
    android.content.SharedPreferences settings = YingxiaApp.get().repository.settings;
    boolean hadQuality = settings.contains("radio.italo.quality");
    int oldQuality = settings.getInt("radio.italo.quality", 0);
    settings.edit().putInt("radio.italo.quality", 0).commit();
    Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(RadioActivity.class.getName(), null, false);
    RadioActivity radio = null;
    JSONObject report = new JSONObject();
    JSONArray lines = new JSONArray();
    report.put("lines", lines);
    try {
      getInstrumentation().runOnMainSync(() -> RadioActivity.open(getActivity()));
      radio = (RadioActivity) monitor.waitForActivityWithTimeout(10000);
      assertNotNull(radio);
      final RadioActivity target = radio;
      Field field = RadioActivity.class.getDeclaredField("player");
      field.setAccessible(true);
      for (int line = 0; line < 2; line++) {
        final int selected = line;
        if (line == 1) getInstrumentation().runOnMainSync(() -> target.getWindow().getDecorView()
            .findViewWithTag("radio-quality").performClick());
        long until = System.currentTimeMillis() + 45000;
        boolean[] decoded = {false};
        JSONObject sample = new JSONObject();
        while (System.currentTimeMillis() < until && !decoded[0]) {
          getInstrumentation().runOnMainSync(() -> {
            try {
              ExoPlayer player = (ExoPlayer) field.get(target);
              String uri = player.getCurrentMediaItem().localConfiguration.uri.toString();
              String mime = player.getAudioFormat() == null ? "" : player.getAudioFormat().sampleMimeType;
              int frames = player.getAudioDecoderCounters() == null ? 0 : player.getAudioDecoderCounters().renderedOutputBufferCount;
              sample.put("quality", selected == 0 ? "high" : "low").put("url", uri)
                  .put("positionMs", player.getCurrentPosition()).put("audioFrames", frames).put("mime", mime);
              decoded[0] = ItaloRadio.STREAMS[selected].equals(uri) && player.isPlaying()
                  && player.getCurrentPosition() > 5000 && frames > 0 && "audio/mpeg".equals(mime);
            } catch (Exception error) { throw new RuntimeException(error); }
          });
          Thread.sleep(200);
        }
        lines.put(sample);
        assertTrue("Radio line did not decode: " + sample, decoded[0]);
      }
      long until = System.currentTimeMillis() + 45000;
      boolean[] metadata = {false};
      while (System.currentTimeMillis() < until && !metadata[0]) {
        getInstrumentation().runOnMainSync(() -> {
          String title = ((TextView) target.getWindow().getDecorView().findViewWithTag("radio-song-title")).getText().toString();
          metadata[0] = !title.isEmpty() && !title.equals("ItaloDance");
          try { report.put("song", title); } catch (JSONException error) { throw new RuntimeException(error); }
        });
        Thread.sleep(250);
      }
      assertTrue("Now-playing metadata did not arrive", metadata[0]);
      Bitmap image = getInstrumentation().getUiAutomation().takeScreenshot();
      assertNotNull(image);
      try (OutputStream out = new FileOutputStream(new File(getActivity().getFilesDir(), "radio-playing.png"))) {
        image.compress(Bitmap.CompressFormat.PNG, 100, out);
      } finally { image.recycle(); }
      getInstrumentation().runOnMainSync(() -> target.getWindow().getDecorView().findViewWithTag("radio-pause").performClick());
      getInstrumentation().runOnMainSync(() -> {
        try { assertFalse(((ExoPlayer) field.get(target)).getPlayWhenReady()); }
        catch (IllegalAccessException error) { throw new RuntimeException(error); }
      });
      report.put("paused", true).put("passed", true);
    } finally {
      if (radio != null) {
        final RadioActivity target = radio;
        getInstrumentation().runOnMainSync(target::finish);
      }
      getInstrumentation().removeMonitor(monitor);
      if (hadQuality) settings.edit().putInt("radio.italo.quality", oldQuality).commit();
      else settings.edit().remove("radio.italo.quality").commit();
      try (OutputStream out = new FileOutputStream(new File(getActivity().getFilesDir(), "radio-live-report.json"))) {
        out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
      }
    }
  }
}
