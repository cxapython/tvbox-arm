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
import java.lang.ref.WeakReference;
import java.util.*;
import org.json.*;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlayerActivity extends Activity {
  private static WeakReference<PlayerActivity> active = new WeakReference<>(null);

  public static PlayerActivity current() {
    return active.get();
  }

  private ExoPlayer player;
  private PlayerView view;
  private FrameLayout root;
  private JSONObject play;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private long resume = 0;
  private TextView title, subtitle;
  private MusicLyrics lyrics;
  private boolean seekingNext = false, released = false;
  private boolean resumeWhenForeground;
  private boolean recoveryScheduled, progressErrorShown;
  private int operationGeneration;
  private Runnable retry;
  private PlaybackRecovery recovery = new PlaybackRecovery();
  private EpisodeSkip.Pass skipPass = new EpisodeSkip.Pass(java.util.Collections.emptyList());

  public static void open(Context context, JSONObject play) {
    if (play.optBoolean("music")) { MusicPlayerActivity.open(context, play); return; }
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
    active = new WeakReference<>(this);
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
      android.util.Log.e("Yingxia.Player", "Cannot read playback request", e);
      Toast.makeText(this, "无法读取播放信息：" + Json.message(e), Toast.LENGTH_LONG).show();
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
    spaced(actions, button(this, "清晰度", this::qualities), 8);
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
      cover.setBackground(monogram(this, play.optString("name", "♫")));
      subtitle.setMaxLines(8);
      FrameLayout.LayoutParams cp =
          new FrameLayout.LayoutParams(dp(this, 250), dp(this, 250), Gravity.CENTER);
      root.addView(cover, 1, cp);
      Artwork.load(cover, play.optString("poster"), 250, 250);
      app()
          .io
          .execute(
              () -> {
                try {
                  MusicLyrics parsed = MusicLyrics.parse(new MusicClient().lyrics(play));
                  runOnUiThread(
                      () -> {
                        if (isFinishing()) return;
                        lyrics = parsed;
                        if (lyrics == null)
                          subtitle.setText(play.optString("artist") + " · 暂无歌词");
                        else updateLyrics();
                      });
                } catch (Exception error) {
                  android.util.Log.w("Yingxia.Player", "Cannot load lyrics", error);
                  runOnUiThread(
                      () -> {
                        if (!isFinishing()) subtitle.setText(play.optString("artist") + " · 歌词加载失败");
                      });
                }
              });
    }
  }

  private void updateLyrics() {
    if (!play.optBoolean("music") || lyrics == null || subtitle == null) return;
    String artist = play.optString("artist");
    if (!lyrics.synced) {
      List<String> all = new ArrayList<>();
      for (MusicLyrics.Line line : lyrics.lines) {
        all.add(line.text);
        if (line.translation != null && !line.translation.isEmpty()) all.add(line.translation);
      }
      subtitle.setText(artist + "\n" + String.join("\n", all));
      return;
    }
    int index = lyrics.indexAt(player == null ? resume : player.getCurrentPosition());
    if (index < 0) {
      subtitle.setText(artist + " · ♪");
      return;
    }
    MusicLyrics.Line line = lyrics.lines.get(index);
    subtitle.setText(
        artist
            + "\n"
            + line.text
            + (line.translation == null || line.translation.isEmpty()
                ? ""
                : "\n" + line.translation));
  }

  private YingxiaApp app() {
    return YingxiaApp.get();
  }

  private void start() {
    released = false;
    recovery = new PlaybackRecovery();
    operationGeneration++;
    if (player == null) createPlayer();
    loadCurrent();
  }

  /** The ExoPlayer lives for the whole activity; episodes, qualities and refreshed URLs replace only its item. */
  private void createPlayer() {
    DataSource.Factory network =
        () -> PlaybackHttp.factory(Json.headers(play.optJSONObject("header"))).createDataSource();
    DataSource.Factory upstream =
        () ->
            new PlaybackDataSource(
                new DefaultDataSource.Factory(this, network).createDataSource(),
                app().repository.settings.getBoolean("player.skipAds", true));
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
          public void onPlayerError(PlaybackException error) {
            long position = player == null ? resume : Math.max(0, player.getCurrentPosition());
            scheduleRecovery(error, position);
          }

          @Override
          public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_ENDED && !seekingNext) next();
          }
        });
  }

  private void loadCurrent() {
    try {
      MediaItem.Builder item =
          OfflineService.playbackItem(this, play).buildUpon()
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
      skipPass =
          new EpisodeSkip.Pass(
              play.optBoolean("live") || play.optBoolean("music")
                  ? java.util.Collections.emptyList()
                  : EpisodeSkip.parse(play.opt("skips")));
      player.setMediaItem(item.build());
      long intro =
          play.optBoolean("live") || play.optBoolean("music")
              ? 0
              : app().repository.settings.getInt("intro", 0) * 1000L;
      player.seekTo(Math.max(resume, intro));
      player.prepare();
      player.play();
      handler.removeCallbacks(progress);
      handler.removeCallbacks(sourceSkip);
      handler.postDelayed(progress, 5000);
      handler.postDelayed(sourceSkip, 250);
    } catch (Exception e) {
      android.util.Log.e("Yingxia.Player", "Cannot load media item", e);
      Toast.makeText(this, "无法加载播放地址：" + Json.message(e), Toast.LENGTH_LONG).show();
      finish();
    }
  }

  private static boolean retryable(PlaybackException error) {
    int code = error.errorCode;
    return code >= PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        && code < PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED;
  }

  /** Every automatic attempt refreshes the catalog address through SpiderEngine.resume first. */
  private void scheduleRecovery(Exception failure, long position) {
    if (released || recoveryScheduled || seekingNext || isFinishing()) return;
    boolean canRetry = !(failure instanceof PlaybackException) || retryable((PlaybackException) failure);
    PlaybackRecovery.Decision decision = recovery.afterFailure(canRetry, position / 1000.0);
    if (decision == null) {
      showPlaybackError(failure, position);
      return;
    }
    recoveryScheduled = true;
    Toast.makeText(
            this,
            "播放中断，" + decision.delayMillis / 1000
                + (play.optBoolean("music") || Json.child(play, "site").length() > 0
                    ? " 秒后自动刷新地址并重试（" : " 秒后自动重试（")
                + recovery.attempts() + "/3）",
            Toast.LENGTH_SHORT)
        .show();
    retry = () -> refreshAddress(position);
    handler.postDelayed(retry, decision.delayMillis);
  }

  private void refreshAddress(long position) {
    final int generation = operationGeneration;
    app()
        .io
        .execute(
            () -> {
              try {
                JSONObject saved = Json.copy(play);
                saved.put("position", position).put("completed", false);
                JSONObject refreshed;
                if (play.optBoolean("music")) {
                  refreshed = new MusicClient().play(saved);
                } else if (Json.child(play, "site").length() > 0) {
                  refreshed = app().engine.resume(saved);
                } else {
                  refreshed = saved;
                }
                runOnUiThread(
                    () -> {
                      if (generation != operationGeneration || isFinishing()) return;
                      play = refreshed;
                      resume = position;
                      recoveryScheduled = false;
                      loadCurrent();
                    });
              } catch (Exception error) {
                android.util.Log.w("Yingxia.Player", "Playback address refresh failed", error);
                runOnUiThread(
                    () -> {
                      if (generation != operationGeneration || isFinishing()) return;
                      recoveryScheduled = false;
                      scheduleRecovery(error, position);
                    });
              }
            });
  }

  private void showPlaybackError(Exception failure, long position) {
    String reason = failure instanceof PlaybackException
        ? ((PlaybackException) failure).getErrorCodeName() : Json.message(failure);
    new AlertDialog.Builder(this)
        .setTitle("暂时无法播放")
        .setMessage(reason + "\n请手动重试，或选择其他线路。")
        .setPositiveButton(
            "重试",
            (dialog, which) -> {
              recovery = new PlaybackRecovery();
              scheduleRecovery(failure, position);
            })
        .setNeutralButton("选集", (dialog, which) -> episodes())
        .setNegativeButton("返回", (dialog, which) -> finish())
        .show();
  }

  private void cancelRecovery() {
    if (retry != null) handler.removeCallbacks(retry);
    recoveryScheduled = false;
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

  private final Runnable sourceSkip =
      new Runnable() {
        @Override
        public void run() {
          if (released || player == null) return;
          long duration = player.getDuration();
          updateLyrics();
          Double target =
              skipPass.target(
                  player.getCurrentPosition() / 1000.0, duration / 1000.0, player.isPlaying());
          if (target != null) {
            if (duration > 0 && target >= duration / 1000.0 - 0.1) {
              if (!next()) player.seekTo((long) (target * 1000));
            } else {
              player.seekTo((long) (target * 1000));
            }
          }
          handler.postDelayed(this, 250);
        }
      };

  private void save() {
    if (player == null || play.optBoolean("live")) return;
    try {
      app().repository.progress(play, player.getCurrentPosition(), Math.max(0, player.getDuration()));
      progressErrorShown = false;
    } catch (RuntimeException error) {
      android.util.Log.e("Yingxia.Player", "Cannot persist playback progress", error);
      if (!progressErrorShown) {
        progressErrorShown = true;
        Toast.makeText(this, "播放进度保存失败：" + Json.message(error), Toast.LENGTH_LONG).show();
      }
    }
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

  private boolean next() {
    if (seekingNext) return true;
    JSONObject episode = EpisodePlaylist.next(play);
    if (episode == null) return false;
    switchTo(episode);
    return true;
  }

  private void switchTo(JSONObject episode) {
    if (seekingNext) return;
    save();
    cancelRecovery();
    seekingNext = true;
    final int generation = ++operationGeneration;
    final boolean wasPlaying = player == null || player.getPlayWhenReady();
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
                      if (generation != operationGeneration || isFinishing()) return;
                      play = result;
                      resume = 0;
                      recovery = new PlaybackRecovery();
                      title.setText(play.optString("name"));
                      subtitle.setText(play.optString("episode"));
                      loadCurrent();
                      seekingNext = false;
                    });
              } catch (Exception e) {
                android.util.Log.w("Yingxia.Player", "Cannot switch episode", e);
                runOnUiThread(
                    () -> {
                      if (generation != operationGeneration || isFinishing()) return;
                      seekingNext = false;
                      Toast.makeText(this, Json.message(e), Toast.LENGTH_LONG).show();
                      if (wasPlaying && player != null) player.play();
                    });
              }
            });
  }

  private void qualities() {
    List<JSONObject> qualities = Json.objects(Json.array(play.opt("qualities")));
    if (qualities.size() <= 1) {
      Toast.makeText(this, "此线路没有其他清晰度", Toast.LENGTH_SHORT).show();
      return;
    }
    String[] labels = new String[qualities.size()];
    int selected = play.optInt("quality", 0);
    for (int index = 0; index < labels.length; index++) {
      String name = qualities.get(index).optString("name", "清晰度 " + (index + 1));
      labels[index] = (index == selected ? "● " : "") + name;
    }
    new AlertDialog.Builder(this)
        .setTitle("选择清晰度")
        .setItems(labels, (dialog, index) -> chooseQuality(index))
        .setNegativeButton("取消", null)
        .show();
  }

  private void chooseQuality(int quality) {
    if (seekingNext || quality == play.optInt("quality", 0) || player == null) return;
    save();
    cancelRecovery();
    seekingNext = true;
    final int generation = ++operationGeneration;
    final long position = player.getCurrentPosition();
    final boolean wasPlaying = player.getPlayWhenReady();
    player.pause();
    JSONObject site = Json.child(play, "site"), video = Json.child(play, "video");
    JSONObject episode = Json.child(play, "episodeInfo");
    app()
        .io
        .execute(
            () -> {
              try {
                JSONObject result = app().engine.playback(site, video, episode, quality);
                runOnUiThread(
                    () -> {
                      if (generation != operationGeneration || isFinishing()) return;
                      play = result;
                      resume = position;
                      recovery = new PlaybackRecovery();
                      loadCurrent();
                      if (!wasPlaying) player.pause();
                      seekingNext = false;
                      Toast.makeText(this, "已切换清晰度", Toast.LENGTH_SHORT).show();
                    });
              } catch (Exception error) {
                android.util.Log.w("Yingxia.Player", "Cannot switch quality", error);
                runOnUiThread(
                    () -> {
                      if (generation != operationGeneration || isFinishing()) return;
                      seekingNext = false;
                      Toast.makeText(this, "清晰度切换失败：" + Json.message(error), Toast.LENGTH_LONG)
                          .show();
                      if (wasPlaying) player.play();
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
      handler.removeCallbacks(sourceSkip);
      handler.postDelayed(progress, 5000);
      handler.postDelayed(sourceSkip, 250);
    }
  }

  @Override
  protected void onStop() {
    super.onStop();
    save();
    handler.removeCallbacks(progress);
    handler.removeCallbacks(sourceSkip);
    if (player != null) {
      resumeWhenForeground = player.getPlayWhenReady();
      player.pause();
    }
  }

  @Override
  protected void onDestroy() {
    if (current() == this) active.clear();
    released = true;
    operationGeneration++;
    cancelRecovery();
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
