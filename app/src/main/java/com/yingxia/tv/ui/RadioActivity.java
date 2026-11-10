package com.yingxia.tv.ui;

import static com.yingxia.tv.ui.TvStyle.*;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import com.yingxia.tv.YingxiaApp;
import com.yingxia.tv.core.ItaloRadio;
import com.yingxia.tv.data.PlaybackHttp;
import java.util.Locale;
import java.util.concurrent.Future;

/** Native, remote-friendly audio player: no video controls or seeking an endless radio stream. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class RadioActivity extends Activity {
  private final Handler handler = new Handler(Looper.getMainLooper());
  private ExoPlayer player;
  private LinearLayout root;
  private ImageView cover;
  private TextView songTitle, artist, status, timing, pauseButton, qualityButton;
  private ProgressBar progress;
  private Bars bars;
  private ItaloRadio song;
  private long fetchedAt;
  private int line, tried, generation;
  private boolean active, wantsPlayback = true;
  private String loadedCover = "";
  private Future<?> request;

  public static void open(Context context) { context.startActivity(new Intent(context, RadioActivity.class)); }

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getWindow().getDecorView().setSystemUiVisibility(5894);
    line = YingxiaApp.get().repository.settings.getInt("radio.italo.quality", 0) == 1 ? 1 : 0;
    build();
    player = new ExoPlayer.Builder(this)
        .setMediaSourceFactory(new DefaultMediaSourceFactory(PlaybackHttp.factory(ItaloRadio.HEADERS)))
        .build();
    player.setWakeMode(C.WAKE_MODE_NETWORK);
    player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true);
    player.addListener(new Player.Listener() {
      @Override public void onPlaybackStateChanged(int state) {
        handler.removeCallbacks(stalled);
        if (!active) return;
        if (state == Player.STATE_BUFFERING && wantsPlayback) {
          status.setText("正在连接电台…");
          handler.postDelayed(stalled, 15000);
        } else if (state == Player.STATE_READY) status.setText(wantsPlayback ? "● 正在直播" : "已暂停");
        else if (state == Player.STATE_ENDED && wantsPlayback) failover();
      }
      @Override public void onIsPlayingChanged(boolean playing) {
        bars.playing = playing;
        bars.invalidate();
        pauseButton.setText(playing ? "暂停" : "播放");
      }
      @Override public void onPlayerError(PlaybackException error) { if (active) failover(); }
    });
  }

  private void build() {
    root = vertical(this);
    root.setTag("radio-backdrop");
    pad(root, 44, 24);
    backdrop(0xff183153);
    setContentView(root);
    root.addView(text(this, "ItaloDance  /  RADIO", 19, TEXT));
    LinearLayout stage = horizontal(this);
    stage.setGravity(Gravity.CENTER_VERTICAL);
    root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
    int side = Math.min(250, Math.max(132, (int) (getResources().getDisplayMetrics().heightPixels
        / getResources().getDisplayMetrics().density) - 180));
    cover = new ImageView(this);
    cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
    cover.setBackground(monogram(this, "♫"));
    cover.setContentDescription("当前歌曲封面");
    stage.addView(cover, new LinearLayout.LayoutParams(dp(this, side), dp(this, side)));
    LinearLayout info = vertical(this);
    pad(info, 36, 10);
    stage.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
    songTitle = text(this, "ItaloDance", 30, TEXT);
    songTitle.setMaxLines(2);
    songTitle.setTag("radio-song-title");
    artist = text(this, "意大利舞曲 · 24 小时在线电台", 18, SECONDARY);
    info.addView(songTitle);
    info.addView(artist);
    gap(info, 18);
    progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progress.setMax(1000);
    progress.setFocusable(false);
    progress.setContentDescription("当前歌曲进度");
    info.addView(progress, new LinearLayout.LayoutParams(-1, dp(this, 7)));
    timing = text(this, "正在获取歌曲信息…", 13, SECONDARY);
    info.addView(timing);
    bars = new Bars(this);
    info.addView(bars, new LinearLayout.LayoutParams(dp(this, 130), dp(this, 48)));
    status = text(this, "正在连接电台…", 14, SECONDARY);
    root.addView(status);
    LinearLayout actions = horizontal(this);
    pauseButton = button(this, "暂停", this::togglePlayback);
    pauseButton.setTag("radio-pause");
    qualityButton = button(this, "音质：" + (line == 0 ? "高" : "低"), () -> {
      line = 1 - line;
      YingxiaApp.get().repository.settings.edit().putInt("radio.italo.quality", line).apply();
      tried = 0;
      wantsPlayback = true;
      tune();
    });
    qualityButton.setTag("radio-quality");
    spaced(actions, pauseButton, 12);
    spaced(actions, qualityButton, 12);
    spaced(actions, button(this, "重试", () -> { tried = 0; wantsPlayback = true; tune(); }), 12);
    spaced(actions, button(this, "返回", this::finish), 0);
    root.addView(actions);
    pauseButton.requestFocus();
  }

  private void togglePlayback() {
    wantsPlayback = !wantsPlayback;
    if (wantsPlayback) { tried = 0; tune(); }
    else {
      handler.removeCallbacks(stalled);
      player.pause();
      status.setText("已暂停");
      pauseButton.setText("播放");
    }
  }

  private void tune() {
    if (!active) return;
    handler.removeCallbacks(stalled);
    tried |= 1 << line;
    qualityButton.setText("音质：" + (line == 0 ? "高" : "低"));
    player.setMediaItem(new MediaItem.Builder().setUri(ItaloRadio.STREAMS[line])
        .setMimeType(MimeTypes.AUDIO_MPEG).build());
    player.prepare();
    player.setPlayWhenReady(wantsPlayback);
  }

  private final Runnable stalled = this::failover;

  private void failover() {
    handler.removeCallbacks(stalled);
    if (!active || !wantsPlayback) return;
    if ((tried & (1 << (1 - line))) == 0) {
      line = 1 - line;
      tune();
    } else {
      player.pause();
      wantsPlayback = false;
      status.setText("电台暂时无法连接，请重试或切换音质");
    }
  }

  private final Runnable poll = this::pollMetadata;

  private void pollMetadata() {
    if (!active) return;
    final int token = generation;
    request = YingxiaApp.get().io.submit(() -> {
      try {
        ItaloRadio current = ItaloRadio.parse(YingxiaApp.get().http.get(ItaloRadio.API, ItaloRadio.HEADERS).text());
        long receivedAt = SystemClock.elapsedRealtime();
        Bitmap image = null;
        if (!current.cover.isEmpty() && !current.cover.equals(loadedCover)) {
          try { image = Artwork.decode(YingxiaApp.get().http.get(current.cover, ItaloRadio.HEADERS).bytes, 400, 400); }
          catch (Exception ignored) { /* Metadata remains useful when its artwork is unavailable. */ }
        }
        final Bitmap bitmap = image;
        runOnUiThread(() -> {
          if (!active || token != generation) { if (bitmap != null) bitmap.recycle(); return; }
          song = current;
          fetchedAt = receivedAt;
          songTitle.setText(current.title.isEmpty() ? "ItaloDance" : current.title);
          artist.setText(current.artist.isEmpty() ? "ItaloDance · 正在直播" : current.artist);
          if (bitmap != null) {
            cover.setImageBitmap(bitmap);
            cover.setBackgroundColor(Color.TRANSPARENT);
            loadedCover = current.cover;
            backdrop(average(bitmap));
          }
          handler.postDelayed(poll, Math.max(5000, current.nextPoll - (SystemClock.elapsedRealtime() - receivedAt)));
        });
      } catch (Exception error) {
        runOnUiThread(() -> {
          if (!active || token != generation) return;
          if (song == null) timing.setText("直播中 · 歌曲信息稍后更新");
          handler.postDelayed(poll, ItaloRadio.FALLBACK_DELAY);
        });
      }
    });
  }

  private final Runnable tick = new Runnable() {
    @Override public void run() {
      if (!active) return;
      if (song != null && song.duration > 0) {
        long elapsed = song.progress(SystemClock.elapsedRealtime() - fetchedAt);
        progress.setProgress((int) (elapsed * 1000 / song.duration));
        timing.setText(time(elapsed) + "  /  " + time(song.duration));
      }
      bars.invalidate();
      handler.postDelayed(this, 200);
    }
  };

  private static String time(long ms) {
    return String.format(Locale.ROOT, "%d:%02d", ms / 60000, ms / 1000 % 60);
  }
  private static int average(Bitmap bitmap) {
    long red = 0, green = 0, blue = 0;
    for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
      int pixel = bitmap.getPixel(x * bitmap.getWidth() / 8, y * bitmap.getHeight() / 8);
      red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel);
    }
    return Color.rgb((int) (red / 64), (int) (green / 64), (int) (blue / 64));
  }
  private void backdrop(int color) {
    int top = Color.rgb(Color.red(color) * 4 / 5, Color.green(color) * 4 / 5, Color.blue(color) * 4 / 5);
    int bottom = Color.rgb(Color.red(color) / 4, Color.green(color) / 4, Color.blue(color) / 4);
    root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {top, bottom}));
  }

  @Override protected void onStart() {
    super.onStart();
    active = true;
    generation++;
    tried = 0;
    tune();
    poll.run();
    tick.run();
  }
  @Override protected void onStop() {
    active = false;
    generation++;
    handler.removeCallbacksAndMessages(null);
    if (request != null) request.cancel(true);
    player.pause();
    super.onStop();
  }
  @Override protected void onDestroy() {
    player.release();
    super.onDestroy();
  }
  @Override public boolean onKeyDown(int key, KeyEvent event) {
    if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { togglePlayback(); return true; }
    return super.onKeyDown(key, event);
  }

  private static final class Bars extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    boolean playing;
    Bars(Context context) { super(context); setFocusable(false); }
    @Override protected void onDraw(Canvas canvas) {
      paint.setColor(0xbbffffff);
      float unit = getWidth() / 15f;
      for (int i = 0; i < 7; i++) {
        double phase = SystemClock.elapsedRealtime() / (240.0 + i * 53) + i;
        float level = playing ? (float) (.25 + .65 * (Math.sin(phase) + 1) / 2) : .15f;
        canvas.drawRoundRect(i * 2 * unit, getHeight() * (1 - level), (i * 2 + 1) * unit,
            getHeight(), unit / 2, unit / 2, paint);
      }
    }
  }
}
