package com.yingxia.tv.ui;

import static com.yingxia.tv.ui.TvStyle.*;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.datasource.*;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import com.yingxia.tv.YingxiaApp;
import com.yingxia.tv.core.*;
import com.yingxia.tv.data.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Android port of tvbox-silicon's MusicTVScreen / MusicTVSong / MusicTVBackdrop (030330f,
 * GPL-3.0-only). Music owns its layout, transport, lyrics and queue independently of video.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class MusicPlayerActivity extends Activity {
  private final Handler handler = new Handler(Looper.getMainLooper());
  private ExoPlayer player;
  private JSONObject play;
  private List<JSONObject> queue;
  private int retryIndex;
  private long retryPosition;
  private String retryQuality = MusicResolver.HIGH;
  private int index, generation, lyricIndex = Integer.MIN_VALUE;
  private String queueTitle = "", failure = "";
  private File requestFile;
  private boolean active, destroyed, busy, dragging, wantsPlayback = true, showsLyrics = true, recovered;
  private long lyricOffset, lastSave;
  private MusicLyrics lyrics;
  private FrameLayout root;
  private LinearLayout stage, songColumn, lyricColumn;
  private ImageView cover, backdrop;
  private TextView title, artist, album, status, elapsed, remaining, pause, quality, lyricToggle, favorite, queueButton;
  private SeekBar scrubber;
  private final List<TextView> lyricViews = new ArrayList<>();
  private int artSize;

  public static void open(Context context, JSONObject play) {
    open(context, play, Collections.singletonList(PlaybackHistory.entry(play, 0, 0, 0)), 0, "");
  }

  public static void open(Context context, JSONObject play, List<JSONObject> queue, int index, String source) {
    try {
      File file = new File(context.getCacheDir(), "music-play-" + UUID.randomUUID() + ".json");
      write(file, Json.obj("play", play, "queue", new JSONArray(queue), "index", index, "source", source));
      context.startActivity(new Intent(context, MusicPlayerActivity.class).putExtra("requestFile", file.getAbsolutePath()));
    } catch (Exception error) {
      Toast.makeText(context, "无法打开音乐播放器：" + Json.message(error), Toast.LENGTH_LONG).show();
    }
  }

  private static void write(File file, JSONObject value) throws IOException {
    android.util.AtomicFile atomic = new android.util.AtomicFile(file);
    FileOutputStream stream = null;
    try {
      stream = atomic.startWrite();
      stream.write(value.toString().getBytes(StandardCharsets.UTF_8));
      atomic.finishWrite(stream);
    } catch (IOException error) { atomic.failWrite(stream); throw error; }
  }

  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getWindow().getDecorView().setSystemUiVisibility(5894);
    setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
    try {
      requestFile = new File(getIntent().getStringExtra("requestFile"));
      JSONObject request;
      try (InputStream in = new android.util.AtomicFile(requestFile).openRead(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192]; int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        request = new JSONObject(out.toString("UTF-8"));
      }
      play = request.getJSONObject("play");
      queue = Json.objects(Json.array(request.opt("queue")));
      if (queue.isEmpty()) queue = Collections.singletonList(Json.copy(play));
      index = Math.max(0, Math.min(queue.size() - 1, request.optInt("index")));
      queueTitle = request.optString("source");
      if (saved != null) {
        wantsPlayback = saved.getBoolean("playing", true);
        showsLyrics = saved.getBoolean("lyrics", true);
        lyricOffset = saved.getLong("lyricOffset");
      }
      build();
      createPlayer();
      loadMedia(play.optLong("position"));
      showSong();
    } catch (Exception error) {
      Toast.makeText(this, "无法读取音乐：" + Json.message(error), Toast.LENGTH_LONG).show();
      finish();
    }
  }

  private YingxiaApp app() { return YingxiaApp.get(); }

  private void build() {
    root = new FrameLayout(this);
    root.setBackgroundColor(0xff202b3b);
    setContentView(root);
    backdrop = new ImageView(this);
    backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
    root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
    View shade = new View(this);
    shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x33202935, 0xcc090b16}));
    root.addView(shade, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout content = vertical(this);
    int height = (int) (getResources().getDisplayMetrics().heightPixels / getResources().getDisplayMetrics().density);
    pad(content, height < 450 ? 28 : 48, height < 450 ? 10 : 24);
    root.addView(content, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout header = horizontal(this);
    TextView back = control("‹ 返回", "返回歌曲列表", this::finish);
    back.setTag("music-back");
    header.addView(back, new LinearLayout.LayoutParams(dp(this, 92), dp(this, 44)));
    album = text(this, "", 15, 0xccffffff);
    album.setSingleLine(true);
    album.setGravity(Gravity.CENTER);
    header.addView(album, new LinearLayout.LayoutParams(0, -2, 1));
    TextView more = control("更多", "更多音乐操作", this::more);
    header.addView(more, new LinearLayout.LayoutParams(dp(this, 92), dp(this, 44)));
    content.addView(header);
    stage = horizontal(this);
    stage.setGravity(Gravity.CENTER);
    stage.setClipChildren(false);
    content.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
    songColumn = vertical(this);
    songColumn.setGravity(Gravity.CENTER);
    songColumn.setClipChildren(false);
    stage.addView(songColumn, new LinearLayout.LayoutParams(0, -1, 0.44f));
    cover = new ImageView(this);
    cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
    cover.setBackground(rounded(0xff293247, 9, this));
    cover.setClipToOutline(true);
    cover.setElevation(dp(this, 12));
    cover.setContentDescription("专辑封面");
    artSize = Math.min(320, Math.max(110, height - 218));
    songColumn.addView(cover, new LinearLayout.LayoutParams(dp(this, artSize), dp(this, artSize)));
    gap(songColumn, 12);
    title = text(this, "", height < 450 ? 20 : 27, Color.WHITE);
    title.setTypeface(null, Typeface.BOLD);
    title.setSingleLine(true);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    title.setTag("music-song-title");
    artist = text(this, "", height < 450 ? 14 : 19, 0xaaffffff);
    artist.setSingleLine(true);
    artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
    songColumn.addView(title, new LinearLayout.LayoutParams(dp(this, artSize), -2));
    songColumn.addView(artist, new LinearLayout.LayoutParams(dp(this, artSize), -2));
    lyricColumn = vertical(this);
    lyricColumn.setGravity(Gravity.CENTER_VERTICAL);
    pad(lyricColumn, 24, 0);
    stage.addView(lyricColumn, new LinearLayout.LayoutParams(0, -1, 0.56f));
    for (int i = 0; i < 5; i++) {
      TextView line = text(this, "", i == 2 ? (height < 450 ? 23 : 32) : (height < 450 ? 15 : 22), Color.WHITE);
      line.setGravity(Gravity.CENTER_VERTICAL);
      line.setTypeface(null, i == 2 ? Typeface.BOLD : Typeface.NORMAL);
      line.setMaxLines(i == 2 ? 3 : 1);
      line.setAlpha(i == 2 ? 1 : (i == 0 || i == 4 ? 0.24f : 0.48f));
      line.setTag("music-lyric-" + i);
      lyricViews.add(line);
      lyricColumn.addView(line, new LinearLayout.LayoutParams(-1, 0, i == 2 ? 2 : 1));
    }
    status = text(this, "正在准备音乐…", 11, 0xbfffffff);
    status.setSingleLine(true);
    status.setGravity(Gravity.CENTER);
    content.addView(status);
    LinearLayout timeline = horizontal(this);
    elapsed = text(this, "0:00", 12, 0xbfffffff);
    remaining = text(this, "−0:00", 12, 0xbfffffff);
    timeline.addView(elapsed, new LinearLayout.LayoutParams(dp(this, 48), -2));
    scrubber = new SeekBar(this);
    scrubber.setMax(1000);
    scrubber.setKeyProgressIncrement(25);
    scrubber.setProgressTintList(ColorStateList.valueOf(Color.WHITE));
    scrubber.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
    scrubber.setContentDescription("歌曲播放进度，左右键调整");
    scrubber.setTag("music-progress");
    timeline.addView(scrubber, new LinearLayout.LayoutParams(0, dp(this, 34), 1));
    remaining.setGravity(Gravity.END);
    timeline.addView(remaining, new LinearLayout.LayoutParams(dp(this, 48), -2));
    content.addView(timeline);
    scrubber.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
      @Override public void onStopTrackingTouch(SeekBar bar) { dragging = false; seekFraction(bar.getProgress()); }
      @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
        if (!fromUser || player == null) return;
        if (dragging) elapsed.setText(time(duration() * progress / 1000));
        else seekFraction(progress);
      }
    });
    LinearLayout controls = horizontal(this);
    controls.setGravity(Gravity.CENTER);
    spaced(controls, control("⏮", "上一首", this::previous), 8);
    pause = control("Ⅱ", "暂停", this::toggle);
    pause.setTextSize(23);
    pause.setTag("music-pause");
    spaced(controls, pause, 8);
    spaced(controls, control("⏭", "下一首", this::next), 16);
    lyricToggle = control("歌词", "显示或隐藏歌词", () -> { showsLyrics = !showsLyrics; layoutLyrics(); });
    lyricToggle.setTag("music-lyrics-toggle");
    spaced(controls, lyricToggle, 8);
    quality = control("音质", "音乐音质", this::chooseQuality);
    quality.setTag("music-quality");
    spaced(controls, quality, 8);
    queueButton = control("待播", "待播歌曲", this::showQueue);
    queueButton.setTag("music-queue");
    spaced(controls, queueButton, 8);
    favorite = control("♡", "收藏歌曲", () -> { app().repository.favorite(PlaybackHistory.entry(play, 0, 0, 0)); updateControls(); });
    favorite.setTag("music-favorite");
    spaced(controls, favorite, 0);
    content.addView(rail(this, controls));
    pause.requestFocus();
  }

  private TextView control(String label, String description, Runnable action) {
    TextView view = button(this, label, action);
    view.setTextSize(14);
    view.setMinHeight(dp(this, 38));
    view.setMinWidth(dp(this, 48));
    pad(view, 12, 6);
    view.setContentDescription(description);
    view.setBackground(rounded(0x19ffffff, 24, this));
    view.setOnFocusChangeListener((v, focused) -> {
      view.setTextColor(focused ? 0xff131727 : Color.WHITE);
      view.setBackground(rounded(focused ? Color.WHITE : 0x19ffffff, 24, this));
      view.animate().scaleX(focused ? 1.06f : 1).scaleY(focused ? 1.06f : 1).setDuration(130).start();
    });
    return view;
  }

  private void createPlayer() {
    DataSource.Factory network = () -> PlaybackHttp.factory(Json.headers(play.optJSONObject("header"))).createDataSource();
    CacheDataSource.Factory data = new CacheDataSource.Factory().setCache(OfflineService.cache(this))
        .setUpstreamDataSourceFactory(new DefaultDataSource.Factory(this, network))
        .setCacheWriteDataSinkFactory(null).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    player = new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(data))
        .setLoadControl(new DefaultLoadControl.Builder().setBufferDurationsMs(10000, 40000, 1200, 2500)
            .setTargetBufferBytes(8 * 1024 * 1024).build()).build();
    player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true);
    player.setHandleAudioBecomingNoisy(true);
    player.setWakeMode(C.WAKE_MODE_NETWORK);
    player.addListener(new Player.Listener() {
      @Override public void onIsPlayingChanged(boolean playing) {
        updateControls();
        cover.animate().scaleX(playing ? 1 : 0.92f).scaleY(playing ? 1 : 0.92f).setDuration(350).start();
      }
      @Override public void onPlayWhenReadyChanged(boolean ready, int reason) {
        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS || reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
          wantsPlayback = ready;
          updateControls();
        }
      }
      @Override public void onPlaybackStateChanged(int state) {
        updateControls();
        if (state == Player.STATE_ENDED && !busy && index + 1 < queue.size()) select(index + 1, 0, currentQuality());
      }
      @Override public void onPlayerError(PlaybackException error) {
        failure = play.optBoolean("fixedQuality") ? "播放失败，请稍后重试" : "播放失败，请重试或切换音质";
        retryIndex = index; retryPosition = player.getCurrentPosition(); retryQuality = currentQuality();
        if (!recovered && !play.optBoolean("complete") && error.errorCode >= PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            && error.errorCode < PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED) {
          recovered = true;
          select(index, Math.max(0, player.getCurrentPosition()), currentQuality());
        } else updateControls();
      }
    });
  }

  private String currentQuality() { return MusicResolver.normalizeQuality(play.optString("quality", MusicResolver.HIGH)); }

  private void loadMedia(long position) throws Exception {
    failure = "";
    MediaItem item = OfflineService.playbackItem(this, play).buildUpon().setMediaMetadata(new MediaMetadata.Builder()
        .setTitle(play.optString("name")).setArtist(play.optString("artist")).setAlbumTitle(play.optString("album")).build()).build();
    player.setMediaItem(item, Math.max(0, position));
    player.prepare();
    player.setPlayWhenReady(active && wantsPlayback);
    updateControls();
  }

  private void showSong() {
    final int token = generation;
    final JSONObject song = Json.copy(play);
    title.setText(song.optString("name"));
    artist.setText(song.optString("artist"));
    String collection = queueTitle.isEmpty() ? song.optString("album") : queueTitle;
    album.setText(collection.isEmpty() ? "正在播放 · " + MusicClient.platformName(song.optString("source")) : collection);
    lyrics = null;
    lyricIndex = Integer.MIN_VALUE;
    cover.setImageDrawable(monogram(this, song.optString("name", "♫")));
    backdrop.setImageDrawable(null);
    layoutLyrics();
    app().io.execute(() -> {
      try {
        JSONObject value = new MusicClient().lyricData(song);
        MusicLyrics parsed = MusicLyrics.parse(value.optString("lyric"), value.optString("translation"));
        runOnUiThread(() -> {
          if (destroyed || token != generation) return;
          lyrics = parsed;
          layoutLyrics();
          updateLyrics();
        });
      } catch (Exception error) {
        runOnUiThread(() -> { if (!destroyed && token == generation) { lyrics = null; layoutLyrics(); } });
      }
    });
    if (!song.optString("poster").isEmpty()) app().io.execute(() -> {
      try {
        Bitmap bitmap = Artwork.decode(app().http.get(song.optString("poster")).bytes, 600, 600);
        if (bitmap == null) return;
        Bitmap blurred = blur(bitmap);
        runOnUiThread(() -> {
          if (destroyed || token != generation) { bitmap.recycle(); blurred.recycle(); return; }
          cover.setImageBitmap(bitmap);
          ColorMatrix colors = new ColorMatrix(); colors.setSaturation(1.6f);
          backdrop.setColorFilter(new ColorMatrixColorFilter(colors));
          backdrop.setImageBitmap(blurred);
        });
      } catch (Exception ignored) { /* The monogram remains available offline. */ }
    });
  }

  /** Tiny, repeatedly smoothed cover, scaled across the TV: no full-screen blur buffers on ARM32. */
  private static Bitmap blur(Bitmap bitmap) {
    Bitmap small = Bitmap.createScaledBitmap(bitmap, 16, 16, true);
    int[] pixels = new int[256], next = new int[256];
    small.getPixels(pixels, 0, 16, 0, 0, 16, 16);
    for (int pass = 0; pass < 4; pass++) {
      for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
        int r = 0, g = 0, b = 0, n = 0;
        for (int yy = Math.max(0, y - 2); yy <= Math.min(15, y + 2); yy++)
          for (int xx = Math.max(0, x - 2); xx <= Math.min(15, x + 2); xx++) {
            int c = pixels[yy * 16 + xx]; r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++;
          }
        next[y * 16 + x] = Color.rgb(r / n, g / n, b / n);
      }
      int[] swap = pixels; pixels = next; next = swap;
    }
    if (small != bitmap) small.recycle();
    return Bitmap.createBitmap(pixels, 16, 16, Bitmap.Config.ARGB_8888);
  }

  private void layoutLyrics() {
    boolean visible = showsLyrics && lyrics != null && !lyrics.lines.isEmpty();
    lyricColumn.setVisibility(visible ? View.VISIBLE : View.GONE);
    songColumn.setLayoutParams(new LinearLayout.LayoutParams(0, -1, visible ? 0.44f : 1));
    int available = (int) (getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density) - 96;
    int textWidth = Math.min(artSize + 200, (int) (available * (visible ? 0.44f : 1)));
    title.setLayoutParams(new LinearLayout.LayoutParams(dp(this, textWidth), -2));
    artist.setLayoutParams(new LinearLayout.LayoutParams(dp(this, textWidth), -2));
    title.setGravity(visible ? Gravity.START : Gravity.CENTER);
    artist.setGravity(visible ? Gravity.START : Gravity.CENTER);
    lyricToggle.setText(showsLyrics ? "歌词 ✓" : "歌词");
    lyricIndex = Integer.MIN_VALUE;
  }

  private void updateLyrics() {
    if (lyrics == null || lyrics.lines.isEmpty() || !showsLyrics) return;
    int at = lyrics.synced ? lyrics.indexAt(Math.max(0, player.getCurrentPosition() + lyricOffset)) : 0;
    if (at == lyricIndex) return;
    lyricIndex = at;
    for (int i = 0; i < lyricViews.size(); i++) {
      int row = at + i - 2;
      String words = "";
      if (row >= 0 && row < lyrics.lines.size()) {
        MusicLyrics.Line line = lyrics.lines.get(row);
        words = line.text;
        if (i == 2 && line.translation != null && !line.translation.isEmpty()) words += "\n" + line.translation;
      } else if (i == 2 && at < 0) words = "♪";
      lyricViews.get(i).setText(words);
    }
  }

  private long duration() {
    return player != null && player.getDuration() > 0 ? player.getDuration() : Math.max(0, play.optLong("duration"));
  }

  private static String time(long value) { long seconds = Math.max(0, value) / 1000; return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60); }

  private void seekFraction(int progress) { if (player != null && !busy && duration() > 0) player.seekTo(duration() * progress / 1000); }

  private void toggle() {
    if (!failure.isEmpty()) { wantsPlayback = true; recovered = false; select(retryIndex, retryPosition, retryQuality); return; }
    wantsPlayback = !wantsPlayback;
    if (player.getPlaybackState() == Player.STATE_ENDED && wantsPlayback) player.seekTo(0);
    player.setPlayWhenReady(active && wantsPlayback && !busy);
    updateControls();
  }

  private void previous() {
    if (busy) return;
    if (player.getCurrentPosition() > 3000 || index == 0) { player.seekTo(0); return; }
    wantsPlayback = true; recovered = false;
    select(index - 1, 0, currentQuality());
  }

  private void next() {
    if (busy) return;
    if (index + 1 >= queue.size()) { Toast.makeText(this, "已是最后一首", Toast.LENGTH_SHORT).show(); return; }
    wantsPlayback = true; recovered = false;
    select(index + 1, 0, currentQuality());
  }

  private void select(int target, long position, String tier) {
    if (busy || target < 0 || target >= queue.size()) return;
    saveProgress();
    retryIndex = target; retryPosition = position; retryQuality = tier;
    busy = true;
    failure = "";
    player.pause();
    final int token = ++generation;
    JSONObject song = target == index ? Json.copy(play) : Json.copy(queue.get(target));
    updateControls();
    app().io.execute(() -> {
      try {
        JSONObject resolved = song.optBoolean("complete") ? song : new MusicClient().play(song, tier);
        resolved.put("position", position);
        runOnUiThread(() -> {
          if (destroyed || token != generation) return;
          try {
            boolean changed = target != index;
            play = resolved;
            index = target;
            busy = false;
            if (changed) { lyricOffset = 0; recovered = false; }
            loadMedia(position);
            showSong();
          } catch (Exception error) { failure = Json.message(error); updateControls(); }
        });
      } catch (Exception error) {
        runOnUiThread(() -> {
          if (destroyed || token != generation) return;
          busy = false;
          failure = play.optBoolean("fixedQuality") ? "音源暂时不可用，请稍后重试" : "音源暂时不可用，按重试或切换音质";
          updateControls();
        });
      }
    });
  }

  private void chooseQuality() {
    if (busy) return;
    if (play.optBoolean("fixedQuality")) { Toast.makeText(this, "使用站点提供的原始音质，实际音质见播放状态", Toast.LENGTH_SHORT).show(); return; }
    if (play.optBoolean("complete")) { Toast.makeText(this, "正在播放已下载音质", Toast.LENGTH_SHORT).show(); return; }
    new AlertDialog.Builder(this).setTitle("音乐音质")
        .setSingleChoiceItems(new String[] {"FLAC 无损", "320 kbps", "128 kbps"},
            currentQuality().equals(MusicResolver.FLAC) ? 0 : currentQuality().equals(MusicResolver.HIGH) ? 1 : 2,
            (dialog, selected) -> {
              dialog.dismiss();
              String tier = selected == 0 ? MusicResolver.FLAC : selected == 1 ? MusicResolver.HIGH : MusicResolver.STANDARD;
              app().repository.settings.edit().putString("musicQuality", tier).apply();
              recovered = false;
              select(index, player.getCurrentPosition(), tier);
            }).setNegativeButton("取消", null).show();
  }

  private void showQueue() {
    String[] names = new String[queue.size()];
    for (int i = 0; i < names.length; i++) names[i] = queue.get(i).optString("name") + " · " + queue.get(i).optString("artist");
    new AlertDialog.Builder(this).setTitle(queueTitle.isEmpty() ? "待播歌曲" : queueTitle)
        .setSingleChoiceItems(names, index, (dialog, selected) -> {
          dialog.dismiss(); wantsPlayback = true; recovered = false; select(selected, 0, currentQuality());
        }).setNegativeButton("返回", null).show();
  }

  private void more() {
    new AlertDialog.Builder(this).setTitle("音乐")
        .setItems(new String[] {"后退 15 秒", "前进 15 秒", "歌词提前 0.5 秒", "歌词延后 0.5 秒", "重置歌词同步", "下载当前歌曲"}, (dialog, selected) -> {
          if (selected < 2) player.seekTo(Math.max(0, Math.min(duration(), player.getCurrentPosition() + (selected == 0 ? -15000 : 15000))));
          else if (selected == 2) lyricOffset += 500;
          else if (selected == 3) lyricOffset -= 500;
          else if (selected == 4) lyricOffset = 0;
          else { OfflineService.add(this, play); Toast.makeText(this, "已加入下载", Toast.LENGTH_SHORT).show(); }
          lyricIndex = Integer.MIN_VALUE;
        }).setNegativeButton("返回", null).show();
  }

  private void updateControls() {
    if (play == null || pause == null || player == null) return;
    pause.setText(!failure.isEmpty() ? "重试" : wantsPlayback ? "Ⅱ" : "▶");
    pause.setContentDescription(!failure.isEmpty() ? "重试" : wantsPlayback ? "暂停" : "播放");
    String tier = currentQuality();
    quality.setText(play.optBoolean("fixedQuality") ? "原始音质" : tier.equals(MusicResolver.FLAC) ? "优先无损" : "优先 " + tier + "k");
    queueButton.setText("待播 " + (index + 1) + "/" + queue.size());
    favorite.setText(app().repository.contains("favorites", play.optString("id")) ? "♥" : "♡");
    String source;
    switch (play.optString("upstream")) {
      case "gdstudio": source = "GD 音乐台 · music.gdstudio.xyz"; break;
      case "xinghai": source = "星海音源 · yy.zddyr.top"; break;
      case "nianxin": source = "念心音源 · " + MusicClient.platformName(play.optString("source")); break;
      case "vkeys": source = "VKeys · QQ 音乐"; break;
      case "migu": source = "咪咕音乐"; break;
      default: source = MusicClient.platformName(play.optString("source"));
    }
    if (play.optBoolean("sourceFallback")) source += " · 已切换音源";
    Format format = player.getAudioFormat();
    String actual = MusicAudioQuality.describe(play.optBoolean("fixedQuality") ? "original" : tier, format == null ? null : format.sampleMimeType,
        format == null ? -1 : format.averageBitrate, format == null ? -1 : format.sampleRate);
    status.setText(!failure.isEmpty() ? failure : busy ? "正在准备音源…" : player.getPlaybackState() == Player.STATE_BUFFERING
        ? "正在缓冲…" : source + (actual.isEmpty() ? "" : "   ·   " + actual));
  }

  private final Runnable tick = new Runnable() {
    @Override public void run() {
      if (!active || destroyed || player == null) return;
      long position = player.getCurrentPosition(), duration = duration();
      if (!dragging) {
        scrubber.setProgress(duration <= 0 ? 0 : (int) Math.min(1000, position * 1000 / duration));
        elapsed.setText(time(position));
      }
      remaining.setText("−" + time(duration - position));
      updateLyrics();
      updateControls();
      if (SystemClock.elapsedRealtime() - lastSave > 5000) { saveProgress(); lastSave = SystemClock.elapsedRealtime(); }
      handler.postDelayed(this, 250);
    }
  };

  private void saveProgress() {
    if (player == null || play == null || busy) return;
    try { app().repository.progress(play, player.getCurrentPosition(), Math.max(0, player.getDuration())); }
    catch (RuntimeException error) { android.util.Log.w("Yingxia.Music", "Cannot save music progress", error); }
  }

  @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (player != null && event.getRepeatCount() == 0) {
      if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) != wantsPlayback) toggle();
        return true;
      }
      if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) { next(); return true; }
      if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) { previous(); return true; }
      if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
        player.seekTo(Math.max(0, Math.min(duration(), player.getCurrentPosition() + (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ? 15000 : -15000))));
        return true;
      }
    }
    return super.onKeyDown(keyCode, event);
  }

  @Override protected void onStart() {
    super.onStart(); active = true;
    if (player != null) { player.setPlayWhenReady(wantsPlayback && !busy); handler.post(tick); }
  }

  @Override protected void onStop() {
    active = false;
    saveProgress();
    handler.removeCallbacks(tick);
    if (player != null) player.pause();
    super.onStop();
  }

  @Override protected void onSaveInstanceState(Bundle saved) {
    if (player != null && play != null) {
      try {
        JSONObject current = Json.copy(play); current.put("position", player.getCurrentPosition());
        write(requestFile, Json.obj("play", current, "queue", new JSONArray(queue), "index", index, "source", queueTitle));
      } catch (Exception error) { android.util.Log.w("Yingxia.Music", "Cannot save player state", error); }
      saved.putBoolean("playing", wantsPlayback); saved.putBoolean("lyrics", showsLyrics); saved.putLong("lyricOffset", lyricOffset);
    }
    super.onSaveInstanceState(saved);
  }

  @Override protected void onDestroy() {
    destroyed = true; generation++;
    handler.removeCallbacksAndMessages(null);
    if (player != null) { player.release(); player = null; }
    if (isFinishing() && requestFile != null) new android.util.AtomicFile(requestFile).delete();
    super.onDestroy();
  }
}
