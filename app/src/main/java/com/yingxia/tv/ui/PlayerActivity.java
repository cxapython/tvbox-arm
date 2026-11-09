package com.yingxia.tv.ui;

import static com.yingxia.tv.ui.TvStyle.*;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.datasource.*;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.*;
import com.yingxia.tv.*;
import com.yingxia.tv.core.*;
import com.yingxia.tv.data.*;
import java.io.*;
import java.util.*;
import org.json.*;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlayerActivity extends Activity {
  private ExoPlayer player;
  private PlayerView view;
  private FrameLayout root;
  private JSONObject play;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private long resume = 0;
  private TextView title, subtitle;
  private boolean seekingNext = false, released = false;
  private boolean resumeWhenForeground;

  public static void open(Context context, JSONObject play) {
    try {
      File file = new File(context.getCacheDir(), "play-" + UUID.randomUUID() + ".json");
      try (FileOutputStream out = new FileOutputStream(file)) {
        out.write(play.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      context.startActivity(
          new Intent(context, PlayerActivity.class).putExtra("playFile", file.getAbsolutePath()));
    } catch (IOException e) {
      Toast.makeText(context, "无法准备播放", Toast.LENGTH_LONG).show();
    }
  }

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getWindow().getDecorView().setSystemUiVisibility(5894);
    try {
      File file = new File(getIntent().getStringExtra("playFile"));
      try (InputStream in = new FileInputStream(file);
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n);
        play = new JSONObject(out.toString("UTF-8"));
      }
      file.delete();
    } catch (Exception e) {
      finish();
      return;
    }
    resume = saved == null ? play.optLong("position") : saved.getLong("position");
    build();
    start();
  }

  private void build() {
    root = new FrameLayout(this);
    root.setBackgroundColor(Color.BLACK);
    setContentView(root);
    view = new PlayerView(this);
    view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
    view.setKeepContentOnPlayerReset(true);
    view.setControllerAutoShow(true);
    view.setControllerShowTimeoutMs(5000);
    view.setShowNextButton(true);
    view.setShowPreviousButton(true);
    view.setShowSubtitleButton(true);
    root.addView(view, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout chrome = vertical(this);
    pad(chrome, 30, 18);
    chrome.setBackground(
        new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {0xc0000000, 0x00000000}));
    title = text(this, play.optString("name"), 25, TEXT);
    subtitle = text(this, play.optString("episode"), 15, SECONDARY);
    chrome.addView(title);
    chrome.addView(subtitle);
    FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
    root.addView(chrome, hp);
    LinearLayout actions = horizontal(this);
    pad(actions, 24, 10);
    spaced(actions, button(this, "返回", this::finish), 8);
    spaced(actions, button(this, "选集", this::episodes), 8);
    spaced(actions, button(this, "音轨 / 字幕", this::tracks), 8);
    spaced(actions, button(this, "倍速", this::speed), 8);
    spaced(actions, button(this, "画面", this::aspect), 8);
    spaced(
        actions,
        button(
            this,
            "下载",
            () -> {
              OfflineService.add(this, play);
              Toast.makeText(this, "已加入下载", android.widget.Toast.LENGTH_SHORT).show();
            }),
        8);
    FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
    ap.bottomMargin = dp(this, 100);
    root.addView(actions, ap);
    view.setControllerVisibilityListener(
        (PlayerView.ControllerVisibilityListener)
            visibility -> {
              chrome.setVisibility(visibility);
              actions.setVisibility(visibility);
            });
    view.requestFocus();
    if (play.optBoolean("music")) {
      ImageView cover = new ImageView(this);
      cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
      FrameLayout.LayoutParams cp =
          new FrameLayout.LayoutParams(dp(this, 250), dp(this, 250), Gravity.CENTER);
      root.addView(cover, 1, cp);
      Artwork.load(cover, play.optString("poster"), 250, 250);
      app()
          .io
          .execute(
              () -> {
                try {
                  String lyrics = new MusicClient().lyrics(play);
                  runOnUiThread(
                      () ->
                          subtitle.setText(
                              play.optString("artist")
                                  + " · "
                                  + lyrics.replaceAll("\\[[^]]*]", "").trim().split("\n")[0]));
                } catch (Exception ignored) {
                }
              });
    }
  }

  private YingxiaApp app() {
    return YingxiaApp.get();
  }

  private void start() {
    if (player != null) {
      view.setPlayer(null);
      player.release();
    }
    released = false;
    DefaultHttpDataSource.Factory network =
        new DefaultHttpDataSource.Factory()
            .setUserAgent(Http.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12000)
            .setReadTimeoutMs(25000)
            .setDefaultRequestProperties(Json.headers(play.optJSONObject("header")));
    DataSource.Factory upstream = new DefaultDataSource.Factory(this, network);
    CacheDataSource.Factory cached =
        new CacheDataSource.Factory()
            .setCache(OfflineService.cache(this))
            .setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    DefaultLoadControl load =
        new DefaultLoadControl.Builder()
            .setBufferDurationsMs(10000, 40000, 1200, 2500)
            .setTargetBufferBytes(20 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build();
    player =
        new ExoPlayer.Builder(this)
            .setLoadControl(load)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(cached))
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000)
            .build();
    view.setPlayer(player);
    player.setWakeMode(C.WAKE_MODE_NETWORK);
    player.setAudioAttributes(
        new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(
                play.optBoolean("music") ? C.AUDIO_CONTENT_TYPE_MUSIC : C.AUDIO_CONTENT_TYPE_MOVIE)
            .build(),
        true);
    player.addListener(
        new Player.Listener() {
          @Override
          public void onPlayerError(PlaybackException e) {
            new AlertDialog.Builder(PlayerActivity.this)
                .setTitle("暂时无法播放")
                .setMessage(e.getErrorCodeName() + "\n请重试，或选择其他线路。")
                .setPositiveButton(
                    "重试",
                    (d, i) -> {
                      resume = player.getCurrentPosition();
                      start();
                    })
                .setNeutralButton("选集", (d, i) -> episodes())
                .setNegativeButton("返回", (d, i) -> finish())
                .show();
          }

          @Override
          public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_ENDED && !seekingNext) next();
          }
        });
    try {
      MediaItem.Builder item =
          new MediaItem.Builder()
              .setUri(play.getString("url"))
              .setMediaMetadata(
                  new MediaMetadata.Builder()
                      .setTitle(play.optString("name"))
                      .setSubtitle(play.optString("episode"))
                      .build());
      List<MediaItem.SubtitleConfiguration> subs = new ArrayList<>();
      for (JSONObject s : Json.objects(Json.array(play.opt("subtitles")))) {
        String url = s.optString("url");
        if (!url.isEmpty())
          subs.add(
              new MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
                  .setMimeType(
                      url.contains(".vtt")
                          ? MimeTypes.TEXT_VTT
                          : url.contains(".ass")
                              ? MimeTypes.TEXT_SSA
                              : MimeTypes.APPLICATION_SUBRIP)
                  .setLabel(s.optString("name", "字幕"))
                  .build());
      }
      item.setSubtitleConfigurations(subs);
      player.setMediaItem(item.build());
      long intro =
          play.optBoolean("live") || play.optBoolean("music")
              ? 0
              : app().repository.settings.getInt("intro", 0) * 1000L;
      player.seekTo(Math.max(resume, intro));
      player.prepare();
      player.play();
      handler.removeCallbacks(progress);
      handler.postDelayed(progress, 5000);
    } catch (Exception e) {
      Toast.makeText(this, Json.message(e), android.widget.Toast.LENGTH_LONG).show();
      finish();
    }
  }

  private final Runnable progress =
      new Runnable() {
        @Override
        public void run() {
          if (released || player == null) return;
          save();
          long duration = player.getDuration(),
              outro = app().repository.settings.getInt("outro", 0) * 1000L;
          if (!play.optBoolean("live")
              && !play.optBoolean("music")
              && outro > 0
              && duration > 0
              && player.getCurrentPosition() > duration - outro
              && !seekingNext) next();
          handler.postDelayed(this, 5000);
        }
      };

  private void save() {
    if (player != null && !play.optBoolean("live"))
      app()
          .repository
          .progress(play, player.getCurrentPosition(), Math.max(0, player.getDuration()));
  }

  private void episodes() {
    List<JSONObject> episodes = Json.objects(Json.array(play.opt("episodes")));
    if (episodes.isEmpty()) {
      Toast.makeText(this, "没有其他集数", android.widget.Toast.LENGTH_SHORT).show();
      return;
    }
    String[] labels = new String[episodes.size()];
    for (int i = 0; i < labels.length; i++)
      labels[i] = episodes.get(i).optString("flag") + " · " + episodes.get(i).optString("name");
    new AlertDialog.Builder(this)
        .setTitle("选集 / 切换线路")
        .setItems(labels, (d, i) -> switchTo(episodes.get(i)))
        .setNegativeButton("取消", null)
        .show();
  }

  private void next() {
    if (seekingNext) return;
    List<JSONObject> episodes = Json.objects(Json.array(play.opt("episodes")));
    JSONObject current = Json.child(play, "episodeInfo");
    for (int i = 0; i + 1 < episodes.size(); i++)
      if (episodes.get(i).optString("id").equals(current.optString("id"))
          && episodes.get(i + 1).optString("flag").equals(current.optString("flag"))) {
        switchTo(episodes.get(i + 1));
        return;
      }
  }

  private void switchTo(JSONObject episode) {
    if (seekingNext) return;
    save();
    seekingNext = true;
    if (player != null) player.pause();
    JSONObject site = Json.child(play, "site"), video = Json.child(play, "video");
    app()
        .io
        .execute(
            () -> {
              try {
                JSONObject result =
                    play.optBoolean("live") ? episode : app().engine.playback(site, video, episode);
                if (play.optBoolean("live")) result.put("episodes", play.opt("episodes"));
                runOnUiThread(
                    () -> {
                      if (isFinishing()) return;
                      play = result;
                      resume = 0;
                      seekingNext = false;
                      title.setText(play.optString("name"));
                      subtitle.setText(play.optString("episode"));
                      start();
                    });
              } catch (Exception e) {
                runOnUiThread(
                    () -> {
                      seekingNext = false;
                      Toast.makeText(this, Json.message(e), android.widget.Toast.LENGTH_LONG)
                          .show();
                      if (player != null) player.play();
                    });
              }
            });
  }

  private void speed() {
    float[] speeds = {.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f};
    String[] labels = new String[speeds.length];
    for (int i = 0; i < labels.length; i++) labels[i] = speeds[i] + "×";
    new AlertDialog.Builder(this)
        .setTitle("播放速度")
        .setItems(labels, (d, i) -> player.setPlaybackSpeed(speeds[i]))
        .show();
  }

  private void aspect() {
    new AlertDialog.Builder(this)
        .setTitle("画面比例")
        .setItems(
            new String[] {"适应屏幕", "填满屏幕", "拉伸"},
            (d, i) ->
                view.setResizeMode(
                    i == 0
                        ? AspectRatioFrameLayout.RESIZE_MODE_FIT
                        : i == 1
                            ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            : AspectRatioFrameLayout.RESIZE_MODE_FILL))
        .show();
  }

  private void tracks() {
    List<String> labels = new ArrayList<>();
    List<Runnable> actions = new ArrayList<>();
    labels.add("关闭字幕");
    actions.add(
        () ->
            player.setTrackSelectionParameters(
                player
                    .getTrackSelectionParameters()
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()));
    for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
      int type = group.getType();
      if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT) continue;
      for (int i = 0; i < group.length; i++) {
        final int index = i;
        if (!group.isTrackSupported(i)) continue;
        Format f = group.getTrackFormat(i);
        labels.add(
            (type == C.TRACK_TYPE_TEXT ? "字幕 · " : "音轨 · ")
                + (f.label != null ? f.label : f.language != null ? f.language : "" + (i + 1)));
        actions.add(
            () ->
                player.setTrackSelectionParameters(
                    player
                        .getTrackSelectionParameters()
                        .buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(
                            new TrackSelectionOverride(group.getMediaTrackGroup(), index))
                        .build()));
      }
    }
    new AlertDialog.Builder(this)
        .setTitle("音轨与字幕")
        .setItems(labels.toArray(new String[0]), (d, i) -> actions.get(i).run())
        .show();
  }

  @Override
  public boolean onKeyDown(int code, KeyEvent event) {
    if (code == KeyEvent.KEYCODE_MENU) {
      episodes();
      return true;
    }
    if (code == KeyEvent.KEYCODE_MEDIA_NEXT) {
      next();
      return true;
    }
    if ((code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)
        && !view.isControllerFullyVisible()) {
      player.seekTo(
          Math.max(
              0,
              player.getCurrentPosition()
                  + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 10000 : -10000)));
      view.showController();
      return true;
    }
    return super.onKeyDown(code, event);
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    if (player != null) state.putLong("position", player.getCurrentPosition());
  }

  @Override
  protected void onStart() {
    super.onStart();
    if (player != null && resumeWhenForeground) player.play();
    if (player != null) {
      handler.removeCallbacks(progress);
      handler.postDelayed(progress, 5000);
    }
  }

  @Override
  protected void onStop() {
    super.onStop();
    save();
    handler.removeCallbacks(progress);
    if (player != null) {
      resumeWhenForeground = player.getPlayWhenReady();
      player.pause();
    }
  }

  @Override
  protected void onDestroy() {
    released = true;
    handler.removeCallbacksAndMessages(null);
    if (player != null) {
      view.setPlayer(null);
      player.release();
      player = null;
    }
    super.onDestroy();
  }

  @Override
  public void onBackPressed() {
    if (view.isControllerFullyVisible()) {
      view.hideController();
      return;
    }
    super.onBackPressed();
  }
}
