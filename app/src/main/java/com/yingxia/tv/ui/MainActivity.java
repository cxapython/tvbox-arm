package com.yingxia.tv.ui;

import static com.yingxia.tv.ui.TvStyle.*;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import com.yingxia.tv.*;
import com.yingxia.tv.core.*;
import com.yingxia.tv.data.*;
import java.io.*;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import org.json.*;

/** Android rendering of RootView, WatchNowTV, SiteView, LiveView and their shared TV design. */
public final class MainActivity extends Activity implements XiaoaiBridge.Target {
  private static WeakReference<MainActivity> active = new WeakReference<>(null);

  public static MainActivity current() {
    return active.get();
  }

  private YingxiaApp app;
  private Repository repo;
  private LinearLayout body, sidebar, header;
  private FrameLayout root, content;
  private ScrollView scroll;
  private TextView heading;
  private int generation = 0, section = 0;
  private final NavigationHistory back = new NavigationHistory();
  private NavigationSnapshot currentPage;
  private Runnable route, catalogMore;
  private boolean catalogLoading, restoringSnapshot;
  private TextView catalogFooter;
  private final List<TextView> nav = new ArrayList<>();
  private boolean expanded = false;
  private HandoffServer handoff;
  private static final class VoiceVideoSearch {
    int remaining;
    int score = -1;
    JSONObject site;
    JSONObject video;

    VoiceVideoSearch(int remaining) { this.remaining = remaining; }
  }
  private static final String[] TITLES = {
    "现在观看", "发现", "直播", "音乐", "全局搜索", "点播源", "播放历史", "收藏", "下载", "设置"
  };
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    active = new WeakReference<>(this);
    app = YingxiaApp.get();
    repo = app.repository;
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    build();
    select(0);
    handleLink(getIntent());
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    handleLink(intent);
  }

  @Override
  protected void onResume() {
    super.onResume();
    active = new WeakReference<>(this);
    if (handoff == null) handoff = new HandoffServer(this);
    handoff.start();
    XiaoaiBridge.attach(this);
  }

  @Override
  protected void onPause() {
    super.onPause();
    if (handoff != null) handoff.close();
    XiaoaiBridge.detach(this);
  }

  @Override
  protected void onDestroy() {
    generation++;
    XiaoaiBridge.detach(this);
    if (current() == this) active.clear();
    super.onDestroy();
  }

  @Override public void onVoiceText(String text) {
    runOnUiThread(() -> {
      String query = voiceQuery(text);
      if (query.isEmpty() || query.equals("映匣")) return;
      boolean music = isMusicVoice(text);
      boolean autoplay = hasVoiceWord(text, "播放", "看", "观看", "听");
      if (music) {
        handleMusicVoice(query, autoplay, null);
      } else if (autoplay) {
        voiceSearchVideo(query);
      } else {
        select(4);
        searchPage(query);
      }
    });
  }

  private void build() {
    root = new FrameLayout(this);
    root.setBackgroundColor(BACKGROUND);
    root.setClipChildren(false);
    setContentView(root);
    content = new FrameLayout(this);
    FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-1, -1);
    cp.leftMargin = dp(this, 72);
    root.addView(content, cp);
    scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    scroll.setVerticalScrollBarEnabled(false);
    scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
    scroll
        .getViewTreeObserver()
        .addOnScrollChangedListener(this::maybeLoadMore);
    content.addView(scroll);
    body = vertical(this);
    body.setClipChildren(false);
    body.setClipToPadding(false);
    pad(body, 30, 28);
    scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
    sidebar = vertical(this);
    pad(sidebar, 10, 12);
    sidebar.setBackground(rounded(0xf20c1120, 22, this));
    sidebar.setElevation(dp(this, 20));
    FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(this, 64), -1);
    sp.setMargins(dp(this, 6), dp(this, 8), 0, dp(this, 8));
    root.addView(sidebar, sp);
    ImageView logo = new ImageView(this);
    logo.setImageResource(com.yingxia.tv.R.drawable.app_icon);
    logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(this, 36), dp(this, 36));
    lp.gravity = Gravity.CENTER_HORIZONTAL;
    lp.bottomMargin = dp(this, 12);
    sidebar.addView(logo, lp);
    ScrollView sideScroll = new ScrollView(this);
    sideScroll.setVerticalScrollBarEnabled(false);
    LinearLayout links = vertical(this);
    sideScroll.addView(links);
    sidebar.addView(sideScroll, new LinearLayout.LayoutParams(-1, 0, 1));
    for (int i = 0; i < TITLES.length; i++) {
      final int index = i;
      TextView v = text(this, "", 15, TEXT);
      NavIcon icon = new NavIcon(i);
      icon.setBounds(0, 0, dp(this, 20), dp(this, 20));
      v.setCompoundDrawables(icon, null, null, null);
      v.setCompoundDrawablePadding(dp(this, 14));
      v.setGravity(Gravity.CENTER_VERTICAL);
      pad(v, 12, 7);
      v.setMinHeight(dp(this, 36));
      v.setFocusable(true);
      v.setClickable(true);
      v.setSingleLine(true);
      v.setContentDescription(TITLES[i]);
      v.setOnClickListener(x -> select(index));
      v.setOnFocusChangeListener(
          (x, focus) -> {
            if (focus) expandSidebar(true);
            v.setBackground(
                rounded(
                    focus ? Color.WHITE : index == section ? 0xff2d3549 : Color.TRANSPARENT,
                    22,
                    this));
            v.setTextColor(focus ? BACKGROUND : TEXT);
            icon.color(focus ? BACKGROUND : TEXT);
          });
      LinearLayout.LayoutParams item = new LinearLayout.LayoutParams(-1, -2);
      item.bottomMargin = dp(this, 3);
      links.addView(v, item);
      nav.add(v);
    }
    root.getViewTreeObserver()
        .addOnGlobalFocusChangeListener(
            (old, next) -> {
              if (next != null && !isInside(next, sidebar) && expanded) expandSidebar(false);
            });
  }

  private boolean isInside(View v, View parent) {
    for (View p = v; p != null; ) {
      if (p == parent) return true;
      android.view.ViewParent next = p.getParent();
      p = next instanceof View ? (View) next : null;
    }
    return false;
  }

  private void expandSidebar(boolean show) {
    if (expanded == show) return;
    expanded = show;
    sidebar.getLayoutParams().width = dp(this, show ? 192 : 64);
    sidebar.requestLayout();
    for (int i = 0; i < nav.size(); i++) nav.get(i).setText(show ? TITLES[i] : "");
  }

  private void select(int index) {
    section = index;
    back.clear();
    for (int i = 0; i < nav.size(); i++) {
      nav.get(i).setBackground(rounded(i == index ? 0xff2d3549 : Color.TRANSPARENT, 22, this));
    }
    showSection();
  }

  private void showSection() {
    switch (section) {
      case 0:
        home();
        break;
      case 1:
        discover(DiscoverCatalog.DEFAULT_FEED, 1);
        break;
      case 2:
        live();
        break;
      case 3:
        music("netease", "", 1);
        break;
      case 4:
        searchPage("");
        break;
      case 5:
        sources();
        break;
      case 6:
        library("history", "播放历史");
        break;
      case 7:
        library("favorites", "收藏");
        break;
      case 8:
        downloads();
        break;
      default:
        settings();
    }
  }

  private void push(Runnable next) {
    if (currentPage != null) {
      currentPage.scrollY(scroll.getScrollY());
      back.push(currentPage);
    }
    next.run();
  }

  private int page(String routeName, String title, JSONObject arguments, Runnable again) {
    generation++;
    route = again;
    currentPage = new NavigationSnapshot(routeName, title, arguments);
    catalogMore = null;
    catalogLoading = false;
    catalogFooter = null;
    body.removeAllViews();
    scroll.scrollTo(0, 0);
    heading = text(this, title, 30, TEXT);
    heading.setTypeface(null, Typeface.BOLD);
    header = horizontal(this);
    if (section != 0 || !back.isEmpty()) {
      TextView up = button(this, "‹ 返回", this::navigateUp);
      up.setTag("page-back");
      up.setContentDescription("返回上一页");
      spaced(header, up, 12);
    }
    header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
    body.addView(header);
    gap(body, 22);
    return generation;
  }

  /** On-screen navigation leaves the page directly; remote Back may first focus the sidebar. */
  private void navigateUp() {
    NavigationSnapshot previous = back.pop();
    if (previous != null) restore(previous);
    else select(0);
  }

  private void loaded(Object data) {
    if (currentPage != null) currentPage.data(data);
  }

  private <T> void async(int token, Callable<T> work, Consumer<T> done) {
    async(token, work, done, this::error);
  }

  private <T> void async(
      int token, Callable<T> work, Consumer<T> done, Consumer<String> failed) {
    app.io.execute(
        () -> {
          try {
            T value = work.call();
            runOnUiThread(
                () -> {
                  if (!isFinishing() && generation == token) done.accept(value);
                });
          } catch (Throwable e) {
            if (e instanceof ThreadDeath) throw (ThreadDeath) e;
            runOnUiThread(
                () -> {
                  if (!isFinishing() && generation == token) failed.accept(Json.message(e));
                });
          }
        });
  }

  private void loading() {
    TextView t = text(this, "正在加载…", 16, SECONDARY);
    t.setTag("loading");
    body.addView(t);
  }

  private void loaded() {
    View v = body.findViewWithTag("loading");
    if (v != null) body.removeView(v);
  }

  private void error(String message) {
    loaded();
    LinearLayout box = vertical(this);
    pad(box, 24, 22);
    box.setBackground(rounded(SURFACE, 16, this));
    box.addView(text(this, "暂时无法加载", 22, TEXT));
    gap(box, 10);
    TextView detail = text(this, message, 15, SECONDARY);
    detail.setTextIsSelectable(true);
    box.addView(detail);
    gap(box, 18);
    LinearLayout actions = horizontal(this);
    spaced(actions, button(this, "重试", () -> route.run()), 12);
    if (!back.isEmpty()) spaced(actions, button(this, "返回", this::onBackPressed), 12);
    box.addView(actions);
    body.addView(box);
  }

  private void empty(String symbol, String title, String message, String action, Runnable go) {
    LinearLayout box = vertical(this);
    box.setGravity(Gravity.CENTER);
    pad(box, 32, 60);
    TextView icon = text(this, symbol, 56, SECONDARY);
    icon.setGravity(Gravity.CENTER);
    box.addView(icon, new LinearLayout.LayoutParams(-2, -2));
    gap(box, 18);
    TextView name = text(this, title, 26, TEXT);
    name.setGravity(Gravity.CENTER);
    box.addView(name, new LinearLayout.LayoutParams(-2, -2));
    gap(box, 12);
    TextView detail = text(this, message, 16, SECONDARY);
    detail.setGravity(Gravity.CENTER);
    detail.setMaxWidth(dp(this, 560));
    box.addView(detail, new LinearLayout.LayoutParams(-2, -2));
    gap(box, 24);
    if (go != null) box.addView(button(this, action, go), new LinearLayout.LayoutParams(-2, -2));
    body.addView(box, new LinearLayout.LayoutParams(-1, -2));
  }

  private void sectionTitle(String title) {
    gap(body, 22);
    TextView h = text(this, title, 21, TEXT);
    h.setTypeface(null, Typeface.BOLD);
    body.addView(h);
    gap(body, 10);
  }

  private LinearLayout actions() {
    LinearLayout l = horizontal(this);
    pad(l, 2, 5);
    body.addView(rail(this, l));
    gap(body, 8);
    return l;
  }

  private void videoGrid(List<JSONObject> videos, JSONObject site) {
    grid(
        videos,
        v -> Catalog.clean(v.optString("vod_name")),
        v -> v.optString("vod_remarks"),
        v -> v.optString("vod_pic"),
        v -> {
          if (Catalog.isIndex(site)) push(() -> searchPage(Catalog.clean(v.optString("vod_name"))));
          else push(() -> details(site, v));
        });
  }

  private void grid(
      List<JSONObject> values,
      java.util.function.Function<JSONObject, String> title,
      java.util.function.Function<JSONObject, String> subtitle,
      java.util.function.Function<JSONObject, String> poster,
      Consumer<JSONObject> click) {
    int available =
        (int)
                (getResources().getDisplayMetrics().widthPixels
                    / getResources().getDisplayMetrics().density)
            - 140;
    int cols = Math.max(3, Math.min(6, available / 158));
    int width = (available - (cols - 1) * 18) / cols;
    LinearLayout row = null;
    for (int i = 0; i < values.size(); i++) {
      if (i % cols == 0) {
        row = horizontal(this);
        row.setGravity(Gravity.TOP);
        row.setClipChildren(false);
        body.addView(row);
        gap(body, 16);
      }
      JSONObject item = values.get(i);
      View card =
          card(
              this,
              title.apply(item),
              subtitle.apply(item),
              poster.apply(item),
              width,
              (int) (width * 1.5),
              () -> click.accept(item));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.rightMargin = dp(this, 16);
      row.addView(card, lp);
    }
  }

  private void tiles(
      List<JSONObject> items,
      java.util.function.Function<JSONObject, String> title,
      java.util.function.Function<JSONObject, String> subtitle,
      Consumer<JSONObject> click,
      Consumer<JSONObject> longClick) {
    LinearLayout row = null;
    int available =
        (int)
                (getResources().getDisplayMetrics().widthPixels
                    / getResources().getDisplayMetrics().density)
            - 140;
    int cols = Math.max(2, available / 280);
    int width = (available - 16 * (cols - 1)) / cols;
    for (int i = 0; i < items.size(); i++) {
      if (i % cols == 0) {
        row = horizontal(this);
        row.setGravity(Gravity.TOP);
        body.addView(row);
        gap(body, 14);
      }
      JSONObject item = items.get(i);
      LinearLayout tile = horizontal(this);
      pad(tile, 16, 18);
      tile.setBackground(rounded(SURFACE, 16, this));
      ImageView art = new ImageView(this);
      art.setImageDrawable(monogram(this, title.apply(item)));
      art.setClipToOutline(true);
      tile.addView(art, new LinearLayout.LayoutParams(dp(this, 50), dp(this, 50)));
      LinearLayout label = vertical(this);
      pad(label, 14, 0);
      TextView name = text(this, title.apply(item), 18, TEXT);
      name.setSingleLine(true);
      label.addView(name);
      TextView sub = text(this, subtitle.apply(item), 12, SECONDARY);
      sub.setSingleLine(true);
      label.addView(sub);
      tile.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
      tile.setFocusable(true);
      tile.setClickable(true);
      tile.setContentDescription(title.apply(item));
      tile.setOnClickListener(v -> click.accept(item));
      if (longClick != null)
        tile.setOnLongClickListener(
            v -> {
              longClick.accept(item);
              return true;
            });
      tile.setOnFocusChangeListener(
          (v, f) -> {
            tile.setBackground(f ? border(0xff333d55, this) : rounded(SURFACE, 16, this));
            tile.animate().scaleX(f ? 1.02f : 1).scaleY(f ? 1.02f : 1).setDuration(150).start();
          });
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(this, width), dp(this, 94));
      lp.rightMargin = dp(this, 16);
      row.addView(tile, lp);
    }
  }

  private void home() {
    home(null);
  }

  private void home(JSONObject cached) {
    int token = page("home", "现在观看", new JSONObject(), this::home);
    if (cached == null) loaded(new JSONObject());
    List<JSONObject> sites = repo.sites();
    List<JSONObject> histories = repo.visibleLibrary("history");
    if (!histories.isEmpty()) {
      JSONObject item = histories.get(0);
      if (!item.optBoolean("completed")) {
        homeHero(item);
      }
    }
    if (sites.isEmpty() && repo.lives().isEmpty()) {
      empty("▶", "连接你的影音世界", "添加 TVBox 订阅，汇集点播、直播和你喜爱的内容。", "添加订阅", this::importDialog);
      return;
    }
    if (!histories.isEmpty()) {
      sectionTitle("继续观看");
      historyRail(histories.subList(0, Math.min(12, histories.size())));
    }
    List<JSONObject> pinned = new ArrayList<>();
    for (JSONObject s : sites)
      if (repo.settings.getBoolean("pin." + s.optString("id"), false)) pinned.add(s);
    if (!pinned.isEmpty()) {
      sectionTitle("置顶点播源");
      sourceTiles(pinned);
    }
    if (!sites.isEmpty()) {
      String selected = repo.settings.getString("recommendSource", sites.get(0).optString("id"));
      JSONObject chosen =
          sites.stream()
              .filter(s -> s.optString("id").equals(selected))
              .findFirst()
              .orElse(sites.get(0));
      sectionTitle("为你推荐");
      LinearLayout row = actions();
      spaced(
          row,
          button(
              this,
              "⌄  " + chosen.optString("name"),
              () ->
                  choose(
                      "选择推荐点播源",
                      sites,
                      s -> s.optString("name"),
                      s -> {
                        repo.settings
                            .edit()
                            .putString("recommendSource", s.optString("id"))
                            .apply();
                        home();
                      })),
          12);
      spaced(row, button(this, "刷新", this::home), 12);
      if (cached != null) showRecommendation(chosen, cached);
      else {
        loading();
        async(
            token,
            () -> app.engine.call(chosen, new JSONObject()),
            answer -> {
              loaded();
              loaded(answer);
              showRecommendation(chosen, answer);
            });
      }
    }
    if (!repo.visibleLibrary("favorites").isEmpty()) {
      sectionTitle("收藏");
      historyRail(repo.visibleLibrary("favorites"));
    }
    if (!repo.lives().isEmpty()) {
      sectionTitle("直播");
      tiles(
          repo.lives(),
          s -> s.optString("name"),
          s -> "直播频道",
          s -> push(() -> channels(s, "")),
          null);
    }
  }

  private void showRecommendation(JSONObject chosen, JSONObject answer) {
    categoryChips(chosen, answer);
    recommendationRail(Catalog.videos(answer), chosen);
    if (Catalog.videos(answer).isEmpty())
      body.addView(
          button(
              this,
              "打开 " + chosen.optString("name"),
              () -> push(() -> site(chosen, "", 1, new JSONObject()))));
  }

  private void homeHero(JSONObject item) {
    FrameLayout hero = new FrameLayout(this);
    hero.setClipChildren(false);
    String poster = item.optString("poster");
    if (!poster.isEmpty()) {
      ImageView cover = new ImageView(this);
      cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
      cover.setBackground(rounded(SURFACE, 12, this));
      cover.setClipToOutline(true);
      FrameLayout.LayoutParams art =
          new FrameLayout.LayoutParams(
              dp(this, 180), dp(this, 270), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
      art.rightMargin = dp(this, 24);
      hero.addView(cover, art);
      Artwork.load(cover, poster, 180, 270);
    }
    LinearLayout info = vertical(this);
    pad(info, 4, 22);
    info.addView(text(this, "接下来播放", 16, SECONDARY));
    gap(info, 12);
    TextView title = text(this, item.optString("name"), 42, TEXT);
    title.setTypeface(null, Typeface.BOLD);
    title.setMaxLines(2);
    info.addView(title);
    gap(info, 10);
    info.addView(
        text(
            this,
            item.optString("episode")
                + "  ·  "
                + Json.child(item, "site").optString("name")
                + "  ·  "
                + formatTime(item.optLong("position")),
            16,
            SECONDARY));
    long duration = item.optLong("duration");
    if (duration > 0) {
      gap(info, 16);
      ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
      progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
      progress.setMax(1000);
      progress.setProgress((int) Math.min(1000, item.optLong("position") * 1000 / duration));
      info.addView(progress, new LinearLayout.LayoutParams(dp(this, 280), dp(this, 5)));
    }
    gap(info, 22);
    LinearLayout row = horizontal(this);
    spaced(row, button(this, "▶  继续播放", () -> resume(item)), 14);
    if (Json.child(item, "site").length() > 0)
      spaced(
          row,
          button(
              this,
              "查看详情",
              () -> push(() -> details(Json.child(item, "site"), Json.child(item, "video")))),
          14);
    info.addView(row);
    FrameLayout.LayoutParams copy =
        new FrameLayout.LayoutParams(-1, -2, Gravity.LEFT | Gravity.BOTTOM);
    copy.rightMargin = dp(this, poster.isEmpty() ? 0 : 235);
    hero.addView(info, copy);
    body.addView(hero, new LinearLayout.LayoutParams(-1, dp(this, 320)));
  }

  private void recommendationRail(List<JSONObject> videos, JSONObject site) {
    LinearLayout row = horizontal(this);
    row.setClipChildren(false);
    for (JSONObject video : videos.subList(0, Math.min(20, videos.size())))
      spaced(
          row,
          card(
              this,
              video.optString("vod_name"),
              video.optString("vod_remarks"),
              video.optString("vod_pic"),
              138,
              207,
              () -> {
                if (Catalog.isIndex(site))
                  push(() -> searchPage(Catalog.clean(video.optString("vod_name"))));
                else push(() -> details(site, video));
              }),
          18);
    body.addView(rail(this, row));
  }

  private void sourceTiles(List<JSONObject> sites) {
    tiles(
        sites,
        s -> s.optString("name"),
        s -> s.optString("subscription"),
        s -> push(() -> site(s, "", 1, new JSONObject())),
        this::sourceMenu);
  }

  private void sources() {
    page("sources", "点播源", new JSONObject(), this::sources);
    List<JSONObject> sites = repo.sites();
    if (sites.isEmpty()) {
      empty("▦", "连接点播订阅", "在设置中添加 TVBox 订阅，点播源会显示在这里。", "添加订阅", this::importDialog);
      return;
    }
    TextView info = text(this, sites.size() + " 个点播源 · 长按确定键可置顶或隐藏", 14, SECONDARY);
    body.addView(info);
    gap(body, 18);
    sourceTiles(sites);
  }

  private void sourceMenu(JSONObject s) {
    boolean pin = repo.settings.getBoolean("pin." + s.optString("id"), false);
    new AlertDialog.Builder(this)
        .setTitle(s.optString("name"))
        .setItems(
            new String[] {pin ? "取消置顶" : "置顶点播源", "隐藏点播源"},
            (d, i) -> {
              repo.settings
                  .edit()
                  .putBoolean(
                      (i == 0 ? "pin." : "hidden.") + s.optString("id"), i == 0 ? !pin : true)
                  .apply();
              route.run();
            })
        .show();
  }

  private void categoryChips(JSONObject site, JSONObject home) {
    List<JSONObject> categories = Json.objects(Json.array(home.opt("class")));
    if (categories.isEmpty()) return;
    LinearLayout chips = actions();
    for (JSONObject c : categories)
      spaced(
          chips,
          button(
              this,
              c.optString("type_name"),
              () -> push(() -> site(site, c.optString("type_id"), 1, new JSONObject()))),
          10);
  }

  private void site(JSONObject source, String category, int pg, JSONObject filters) {
    site(source, category, pg, filters, null);
  }

  private void site(
      JSONObject source, String category, int pg, JSONObject filters, CatalogPages cached) {
    int token =
        page(
            "site",
            source.optString("name"),
            Json.obj("source", source, "category", category, "page", pg, "filters", filters),
            () -> site(source, category, pg, filters));
    LinearLayout row = actions();
    spaced(
        row,
        button(
            this,
            "⌕  搜索此点播源",
            () ->
                input(
                    "搜索 " + source.optString("name"),
                    "关键词",
                    "",
                    q -> push(() -> sourceSearch(source, q, 1)))),
        10);
    spaced(row, button(this, "刷新", () -> site(source, category, pg, filters)), 10);
    if (cached != null) {
      siteFilters(source, category, filters, cached.firstAnswer());
      videoGrid(cached.videos(), source);
      loaded(cached);
      installCatalogMore(
          cached,
          category.isEmpty()
              ? null
              : () -> requestSitePage(
                  generation, source, category, cached.nextPage(), filters, cached, false));
      return;
    }
    CatalogPages pages = new CatalogPages();
    loading();
    requestSitePage(token, source, category, pg, filters, pages, true);
  }

  private void requestSitePage(
      int token,
      JSONObject source,
      String category,
      int pg,
      JSONObject filters,
      CatalogPages pages,
      boolean initial) {
    if (catalogLoading) return;
    catalogLoading = true;
    catalogMore = null;
    if (!initial && catalogFooter != null) catalogFooter.setText("正在加载更多…");
    JSONObject params =
        category.isEmpty() ? new JSONObject() : Json.obj("t", category, "pg", "" + pg);
    JSONObject filterParameters = Catalog.filterParameters(source, filters);
    if (filterParameters != null)
      for (String key : Json.keys(filterParameters))
        try {
          params.put(key, filterParameters.get(key));
        } catch (JSONException broken) {
          throw new IllegalStateException("无法设置分类筛选", broken);
        }
    async(
        token,
        () -> {
          JSONObject answer = app.engine.call(source, params);
          if (!category.isEmpty() && initial) {
            JSONObject home = app.engine.call(source, new JSONObject());
            answer.put("class", home.opt("class")).put("filters", home.opt("filters"));
          }
          return answer;
        },
        answer -> {
          loaded();
          catalogLoading = false;
          List<JSONObject> added = pages.append(answer, pg);
          if (initial) siteFilters(source, category, filters, answer);
          if (initial && pages.videos().isEmpty())
            empty("▦", "暂无内容", "尝试其他分类或刷新此点播源。", "重试", route);
          else videoGrid(added, source);
          loaded(pages);
          installCatalogMore(
              pages,
              category.isEmpty()
                  ? null
                  : () -> requestSitePage(
                      generation, source, category, pages.nextPage(), filters, pages, false));
          scroll.post(this::maybeLoadMore);
        },
        message -> {
          catalogLoading = false;
          if (initial) error(message);
          else
            installCatalogRetry(
                pages,
                message,
                () -> requestSitePage(
                    generation, source, category, pg, filters, pages, false));
        });
  }

  private void siteFilters(
      JSONObject source, String category, JSONObject filters, JSONObject answer) {
    categoryChips(source, answer);
    JSONObject filterMap =
        answer.opt("filters") instanceof String
            ? Json.object(answer.optString("filters"))
            : Json.child(answer, "filters");
    for (JSONObject filter : Json.objects(Json.array(filterMap.opt(category)))) {
      LinearLayout chips = actions();
      String key = filter.optString("key");
      spaced(chips, text(this, filter.optString("name"), 14, SECONDARY), 12);
      for (JSONObject choice : Json.objects(Json.array(filter.opt("value"))))
        spaced(
            chips,
            button(
                this,
                (filters.optString(key).equals(choice.optString("v")) ? "✓ " : "")
                    + choice.optString("n"),
                () -> {
                  JSONObject next = Json.copy(filters);
                  try {
                    next.put(key, choice.optString("v"));
                  } catch (JSONException broken) {
                    throw new IllegalStateException("无法设置分类筛选", broken);
                  }
                  site(source, category, 1, next);
                }),
            8);
    }
  }

  private void sourceSearch(JSONObject site, String query, int pg) {
    sourceSearch(site, query, pg, null);
  }

  private void sourceSearch(JSONObject site, String query, int pg, CatalogPages cached) {
    int token =
        page(
            "sourceSearch",
            site.optString("name") + " · " + query,
            Json.obj("site", site, "query", query, "page", pg),
            () -> sourceSearch(site, query, pg));
    if (cached != null) {
      videoGrid(cached.videos(), site);
      loaded(cached);
      installCatalogMore(cached, () -> requestSourceSearchPage(
          generation, site, query, cached.nextPage(), cached, false));
      return;
    }
    CatalogPages pages = new CatalogPages();
    loading();
    requestSourceSearchPage(token, site, query, pg, pages, true);
  }

  private void requestSourceSearchPage(
      int token, JSONObject site, String query, int pg, CatalogPages pages, boolean initial) {
    if (catalogLoading) return;
    catalogLoading = true;
    catalogMore = null;
    if (!initial && catalogFooter != null) catalogFooter.setText("正在加载更多…");
    async(
        token,
        () -> app.engine.call(site, Json.obj("wd", query, "pg", "" + pg)),
        answer -> {
          loaded();
          catalogLoading = false;
          List<JSONObject> added = pages.append(answer, pg);
          videoGrid(added, site);
          if (initial && pages.videos().isEmpty())
            empty(
                "⌕",
                "没有匹配的内容",
                "换一个关键词再试。",
                "重新搜索",
                () -> input("搜索", "关键词", query, q -> sourceSearch(site, q, 1)));
          loaded(pages);
          installCatalogMore(
              pages,
              () -> requestSourceSearchPage(
                  generation, site, query, pages.nextPage(), pages, false));
          scroll.post(this::maybeLoadMore);
        },
        message -> {
          catalogLoading = false;
          if (initial) error(message);
          else
            installCatalogRetry(
                pages,
                message,
                () -> requestSourceSearchPage(generation, site, query, pg, pages, false));
        });
  }

  private void installCatalogMore(CatalogPages pages, Runnable next) {
    if (catalogFooter != null) body.removeView(catalogFooter);
    catalogFooter = null;
    catalogMore = pages.hasMore() ? next : null;
    if (pages.page() == 0 || (pages.hasMore() && next == null)) return;
    catalogFooter =
        button(
            this,
            pages.hasMore() && next != null
                ? "加载更多 · 第 " + pages.page() + " / " + pages.totalPages() + " 页"
                : "已加载全部 " + pages.videos().size() + " 项",
            () -> {
              if (catalogMore != null) catalogMore.run();
            });
    body.addView(catalogFooter);
  }

  private void installCatalogRetry(CatalogPages pages, String message, Runnable retry) {
    if (catalogFooter != null) body.removeView(catalogFooter);
    catalogMore = retry;
    catalogFooter = button(this, "加载失败：" + message + " · 点击重试", retry);
    body.addView(catalogFooter);
  }

  private void maybeLoadMore() {
    if (restoringSnapshot || catalogMore == null || catalogLoading || scroll.getChildCount() == 0)
      return;
    View child = scroll.getChildAt(0);
    if (child.getBottom() <= scroll.getScrollY() + scroll.getHeight() + dp(this, 360))
      catalogMore.run();
  }

  private void pagination(int pg, int total, Consumer<Integer> go) {
    LinearLayout row = actions();
    if (pg > 1) spaced(row, button(this, "上一页", () -> go.accept(pg - 1)), 10);
    spaced(row, text(this, "第 " + pg + " 页", 15, SECONDARY), 12);
    if (pg < total) spaced(row, button(this, "下一页", () -> go.accept(pg + 1)), 10);
  }

  private void details(JSONObject site, JSONObject listed) {
    details(site, listed, null);
  }

  private void details(JSONObject site, JSONObject listed, JSONObject cached) {
    int token =
        page(
            "details",
            "详情",
            Json.obj("site", site, "listed", listed),
            () -> details(site, listed));
    if (cached != null) {
      loaded(cached);
      renderDetails(site, listed, cached);
      return;
    }
    loading();
    async(
        token,
        () -> {
          if (site.length() == 0 || listed.optString("vod_id").isEmpty()) return listed;
          JSONObject r = app.engine.call(site, Json.obj("ids", listed.optString("vod_id")));
          List<JSONObject> list = Catalog.videos(r);
          if (list.isEmpty()) throw new IOException("此点播源没有返回影片详情");
          JSONObject v = list.get(0);
          if (v.optString("vod_pic").isEmpty()) v.put("vod_pic", listed.optString("vod_pic"));
          return v;
        },
        video -> {
          loaded();
          loaded(video);
          renderDetails(site, listed, video);
        });
  }

  private void renderDetails(JSONObject site, JSONObject listed, JSONObject video) {
          if (video.optString("vod_tag").equals("folder")) {
            site(site, video.optString("vod_id"), 1, new JSONObject());
            return;
          }
          LinearLayout hero = horizontal(this);
          hero.setGravity(Gravity.TOP);
          ImageView poster = new ImageView(this);
          poster.setBackground(monogram(this, video.optString("vod_name")));
          poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
          poster.setClipToOutline(true);
          poster.setBackground(rounded(SURFACE, 14, this));
          hero.addView(poster, new LinearLayout.LayoutParams(dp(this, 175), dp(this, 262)));
          Artwork.load(poster, video.optString("vod_pic"), 175, 262);
          LinearLayout info = vertical(this);
          pad(info, 26, 4);
          TextView name = text(this, video.optString("vod_name"), 34, TEXT);
          name.setTypeface(null, Typeface.BOLD);
          info.addView(name);
          gap(info, 12);
          info.addView(
              text(
                  this,
                  join(video, "vod_year", "type_name", "vod_area", "vod_lang", "vod_remarks"),
                  15,
                  SECONDARY));
          gap(info, 12);
          TextView synopsis =
              text(this, Catalog.clean(video.optString("vod_content")), 16, SECONDARY);
          synopsis.setMaxLines(5);
          info.addView(synopsis);
          gap(info, 12);
          info.addView(text(this, join(video, "vod_director", "vod_actor"), 13, SECONDARY));
          gap(info, 20);
          List<JSONObject> episodes = Catalog.episodes(video);
          JSONObject favorite =
              Json.obj(
                  "id",
                  site.optString("id") + "@" + video.optString("vod_id"),
                  "name",
                  video.optString("vod_name"),
                  "poster",
                  video.optString("vod_pic"),
                  "site",
                  site,
                  "video",
                  video,
                  "kind",
                  "video");
          LinearLayout buttons = horizontal(this);
          if (!episodes.isEmpty())
            spaced(buttons, button(this, "▶  立即播放", () -> play(site, video, episodes.get(0))), 12);
          TextView favoriteButton =
              button(
                  this,
                  repo.contains("favorites", favorite.optString("id")) ? "♥  已收藏" : "♡  收藏",
                  () -> {
                    boolean saved = repo.favorite(favorite);
                    toast(saved ? "已加入收藏" : "已取消收藏");
                    details(site, video);
                  });
          spaced(buttons, favoriteButton, 12);
          info.addView(buttons);
          hero.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
          body.addView(hero);
          gap(body, 24);
          if (episodes.isEmpty()) {
            if (!video.optString("action").isEmpty())
              body.addView(button(this, "打开点播源设置", () -> toast("请使用点播源提供的登录入口")));
            else body.addView(text(this, "此内容暂未提供可播放的集数", 16, SECONDARY));
            return;
          }
          LinkedHashMap<String, List<JSONObject>> lines = new LinkedHashMap<>();
          for (JSONObject e : episodes)
            lines.computeIfAbsent(e.optString("flag"), k -> new ArrayList<>()).add(e);
          LinearLayout lineChips = actions();
          FrameLayout episodeContainer = new FrameLayout(this);
          body.addView(episodeContainer);
          for (Map.Entry<String, List<JSONObject>> entry : lines.entrySet())
            spaced(
                lineChips,
                button(
                    this,
                    entry.getKey().isEmpty() ? "播放线路" : entry.getKey(),
                    () -> episodeButtons(episodeContainer, site, video, entry.getValue())),
                10);
          episodeButtons(episodeContainer, site, video, lines.values().iterator().next());
  }

  private void episodeButtons(
      FrameLayout container, JSONObject site, JSONObject video, List<JSONObject> episodes) {
    container.removeAllViews();
    LinearLayout list = vertical(this);
    container.addView(list);
    LinearLayout row = null;
    int cols =
        Math.max(
            3,
            ((int)
                        (getResources().getDisplayMetrics().widthPixels
                            / getResources().getDisplayMetrics().density)
                    - 140)
                / 140);
    for (int i = 0; i < episodes.size(); i++) {
      if (i % cols == 0) {
        row = horizontal(this);
        list.addView(row);
        gap(list, 12);
      }
      JSONObject episode = episodes.get(i);
      TextView b = button(this, episode.optString("name"), () -> play(site, video, episode));
      b.setOnLongClickListener(
          v -> {
            downloadEpisode(site, video, episode);
            return true;
          });
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(this, 125), -2);
      lp.rightMargin = dp(this, 10);
      row.addView(b, lp);
    }
  }

  private void play(JSONObject site, JSONObject video, JSONObject episode) {
    int token = generation;
    toast("正在准备播放…");
    async(
        token,
        () -> app.engine.playback(site, video, episode),
        play -> {
          JSONObject history = repo.history(play.optString("historyId", play.optString("id")));
          JSONObject previous = Json.child(history, "episodeInfo");
          boolean sameEpisode =
              previous.optString("name").equals(episode.optString("name"))
                  && previous.optString("flag").equals(episode.optString("flag"));
          try {
            play.put(
                "position",
                !sameEpisode || history.optBoolean("completed") ? 0 : history.optLong("position"));
          } catch (JSONException error) {
            android.util.Log.e("Yingxia.Main", "Cannot restore episode progress", error);
            toast("无法恢复播放进度，未开始播放");
            return;
          }
          PlayerActivity.open(this, play);
        });
  }

  private static boolean resumable(JSONObject item) {
    return PlaybackHistory.resumable(item);
  }

  private void resume(JSONObject saved) {
    if (saved.has("cloudServer")) {
      toast("网盘功能已移除，此旧记录可在管理中删除");
      return;
    } else if (saved.optBoolean("music")) {
      async(
          generation,
          () -> {
            JSONObject play = new MusicClient().play(saved);
            play.put("position", saved.optBoolean("completed") ? 0 : saved.optLong("position"));
            return play;
          },
          play -> PlayerActivity.open(this, play));
    } else if (Json.child(saved, "site").length() > 0) {
      async(generation, () -> app.engine.resume(saved), play -> PlayerActivity.open(this, play));
    } else {
      android.util.Log.w("Yingxia.Main", "History entry has no resolvable catalog identity");
      toast("此记录缺少点播源信息，无法重新解析播放地址");
    }
  }

  private void historyRail(List<JSONObject> entries) {
    LinearLayout row = horizontal(this);
    row.setClipChildren(false);
    for (JSONObject item : entries) {
      View v =
          card(
              this,
              item.optString("name"),
              item.optString("episode"),
              item.optString("poster"),
              138,
              207,
              () -> {
                if (item.has("cloudServer") || resumable(item)) resume(item);
                else push(() -> details(Json.child(item, "site"), Json.child(item, "video")));
              });
      v.setOnLongClickListener(
          x -> {
            libraryMenu(item);
            return true;
          });
      spaced(row, v, 18);
    }
    body.addView(rail(this, row));
  }

  private void library(String key, String title) {
    page("library", title, Json.obj("key", key, "title", title), () -> library(key, title));
    List<JSONObject> entries = repo.visibleLibrary(key);
    if (entries.isEmpty()) {
      empty(
          key.equals("favorites") ? "♡" : "◷",
          key.equals("favorites") ? "收藏喜欢的内容" : "还没有播放记录",
          key.equals("favorites") ? "在详情页收藏影片，在这里随时找到。" : "你观看的内容和进度会保存在这里。",
          "浏览点播源",
          () -> select(5));
      return;
    }
    LinearLayout row = actions();
    spaced(
        row,
        button(
            this,
            "管理",
            () ->
                choose(
                    "选择要移除的内容",
                    entries,
                    s -> s.optString("name"),
                    s -> {
                      repo.remove(key, s.optString("id"));
                      library(key, title);
                    })),
        12);
    grid(
        entries,
        s -> s.optString("name"),
        s -> s.optString("episode"),
        s -> s.optString("poster"),
        s -> {
          if (s.has("cloudServer") || resumable(s)) resume(s);
          else push(() -> details(Json.child(s, "site"), Json.child(s, "video")));
        });
  }

  private void libraryMenu(JSONObject item) {
    new AlertDialog.Builder(this)
        .setTitle(item.optString("name"))
        .setItems(
            new String[] {"打开", "从历史中移除", "收藏 / 取消收藏"},
            (d, i) -> {
              if (i == 0) {
                if (item.has("cloudServer") || resumable(item)) resume(item);
                else push(() -> details(Json.child(item, "site"), Json.child(item, "video")));
              } else if (i == 1) {
                repo.remove("history", item.optString("id"));
                route.run();
              } else {
                repo.favorite(item);
                route.run();
              }
            })
        .show();
  }

  private void live() {
    page("live", "直播", new JSONObject(), this::live);
    sectionTitle("内置电台");
    body.addView(button(this, "♫ ItaloDance · 意大利舞曲电台", () -> RadioActivity.open(this)));
    gap(body, 18);
    sectionTitle("电视直播");
    List<JSONObject> sources = repo.lives();
    if (sources.isEmpty()) {
      empty("◉", "连接直播订阅", "添加包含直播的 TVBox 订阅，或导入 M3U / TXT 播放列表。", "添加订阅", this::importDialog);
      return;
    }
    tiles(
        sources,
        s -> s.optString("name", "直播"),
        s -> "打开频道列表",
        s -> push(() -> channels(s, "")),
        null);
  }

  private static final class ChannelLoad {
    final List<JSONObject> items;
    final int invalidChannels, invalidHeaders;

    ChannelLoad(List<JSONObject> items, int invalidChannels, int invalidHeaders) {
      this.items = items;
      this.invalidChannels = invalidChannels;
      this.invalidHeaders = invalidHeaders;
    }
  }

  private ChannelLoad loadChannels(JSONObject source) throws Exception {
    List<JSONObject> inline = LiveChannels.fromInline(source);
    if (!inline.isEmpty()) return new ChannelLoad(inline, 0, 0);
    Map<String, String> headers = Json.headers(source.optJSONObject("header"));
    if (source.has("ua")) headers.put("User-Agent", source.optString("ua"));
    String url = source.optString("url");
    if (url.startsWith("proxy://")) {
      Uri parsed = Uri.parse(url);
      String ext = parsed.getQueryParameter("ext");
      if (ext != null)
        url =
            new String(
                android.util.Base64.decode(ext, android.util.Base64.DEFAULT),
                StandardCharsets.UTF_8);
    }
    Http.Result result =
        app.http.get(ConfigCodec.address(url, source.optString("origin")), headers);
    PlaylistParser.Result parsed = PlaylistParser.parseWithStats(result.text(), result.url, headers);
    if (parsed.getChannels().isEmpty())
      throw new IOException(
          "播放列表中没有找到可用频道"
              + (parsed.getInvalidChannels() > 0
                  ? "，" + parsed.getInvalidChannels() + " 个频道地址无效"
                  : ""));
    return new ChannelLoad(
        parsed.getChannels(), parsed.getInvalidChannels(), parsed.getInvalidHeaders());
  }

  private void channels(JSONObject source, String selected) {
    channels(source, selected, null);
  }

  private void channels(JSONObject source, String selected, ChannelLoad cached) {
    int token =
        page(
            "channels",
            source.optString("name", "直播"),
            Json.obj("source", source, "selected", selected),
            () -> channels(source, selected));
    if (cached != null) {
      loaded(cached);
      renderChannels(source, selected, cached);
      return;
    }
    loading();
    async(
        token,
        () -> loadChannels(source),
        loadedChannels -> {
          loaded();
          loaded(loadedChannels);
          renderChannels(source, selected, loadedChannels);
        });
  }

  private void renderChannels(JSONObject source, String selected, ChannelLoad load) {
    List<JSONObject> items = load.items;
    LinearLayout filters = actions();
    LinkedHashSet<String> groups = new LinkedHashSet<>();
    groups.add("");
    for (JSONObject c : items) groups.add(c.optString("group"));
    for (String group : groups)
      spaced(
          filters,
          button(
              this,
              (group.equals(selected) ? "✓ " : "") + (group.isEmpty() ? "全部" : group),
              () -> channels(source, group, load)),
          10);
    List<JSONObject> shown = new ArrayList<>();
    for (JSONObject c : items)
      if (selected.isEmpty() || selected.equals(c.optString("group"))) shown.add(c);
    String invalid =
        (load.invalidChannels > 0 ? " · " + load.invalidChannels + " 个频道地址无效" : "")
            + (load.invalidHeaders > 0 ? " · " + load.invalidHeaders + " 个请求头已回退" : "");
    TextView count = text(this, shown.size() + " 个频道" + invalid, 14, SECONDARY);
    body.addView(count);
    gap(body, 16);
    tiles(
        shown,
        c -> c.optString("name"),
        c -> c.optString("group"),
        channel -> {
          JSONObject play = Json.copy(channel);
          try {
            Object lines = channel.has("episodes") ? channel.opt("episodes") : new JSONArray(items);
            play.put("episodes", lines).put("poster", channel.optString("logo"));
          } catch (JSONException broken) {
            android.util.Log.e("Yingxia.Main", "Cannot prepare live channels", broken);
            toast("无法准备频道线路");
            return;
          }
          PlayerActivity.open(this, play);
        },
        c -> {
          repo.favorite(c);
          toast("已更新收藏");
        });
  }

  private void searchPage(String initial) {
    searchPage(initial, null);
  }

  private void searchPage(String initial, SearchSnapshot cached) {
    int token =
        page(
            "search",
            "全局搜索",
            Json.obj("initial", initial),
            () -> searchPage(initial));
    LinearLayout entry = horizontal(this);
    EditText input = edit("搜索全部点播源", initial);
    entry.addView(input, new LinearLayout.LayoutParams(0, dp(this, 52), 1));
    spaced(
        entry,
        button(
            this,
            "搜索",
            () -> {
              hideKeyboard(input);
              performSearch(input.getText().toString().trim());
            }),
        12);
    body.addView(entry);
    input.setOnEditorActionListener(
        (v, a, event) -> {
          if (a == EditorInfo.IME_ACTION_SEARCH) {
            performSearch(input.getText().toString().trim());
            return true;
          }
          return false;
        });
    gap(body, 18);
    if (cached != null) {
      loaded(cached);
      renderSearchSnapshot(cached);
    } else if (initial.isEmpty())
      empty("⌕", "一次搜索，汇集所有点播源", "输入影片、剧集或演员名称，搜索结果会陆续显示。", null, null);
    else performSearch(initial);
  }

  private void renderSearchSnapshot(SearchSnapshot search) {
    body.addView(text(this, search.status(), 14, SECONDARY));
    gap(body, 16);
    for (SearchSnapshot.Result result : search.results())
      appendSearchResult(result.site, result.videos);
  }

  private void appendSearchResult(JSONObject site, List<JSONObject> shown) {
    if (!repo.isSourceVisible(site)) return;
    sectionTitle(site.optString("name"));
    LinearLayout row = horizontal(this);
    for (JSONObject video : shown.subList(0, Math.min(12, shown.size())))
      spaced(
          row,
          card(
              this,
              video.optString("vod_name"),
              video.optString("vod_remarks"),
              video.optString("vod_pic"),
              130,
              195,
              () -> push(() -> details(site, video))),
          16);
    body.addView(rail(this, row));
  }

  private void performSearch(String query) {
    if (query.isEmpty()) return;
    int token = ++generation;
    while (body.getChildCount() > 4) body.removeViewAt(4);
    List<JSONObject> sites = repo.sites();
    if (sites.isEmpty()) {
      empty("⌕", "先添加一个订阅", "添加点播源后即可搜索。", "添加订阅", this::importDialog);
      return;
    }
    // Sources in a failure cooldown stay out of this search; a retry asks them again.
    SourceHealth health = SourceHealth.shared();
    List<JSONObject> asked = new ArrayList<>();
    int paused = 0;
    for (JSONObject site : sites)
      if (health.shouldSkip(SourceHealth.key(site)))
        paused++;
      else asked.add(site);
    TextView status =
        text(
            this,
            "正在搜索 " + asked.size() + " 个点播源…" + (paused > 0 ? " · " + paused + " 个冷却中" : ""),
            14,
            SECONDARY);
    body.addView(status);
    gap(body, 16);
    SearchSnapshot search = new SearchSnapshot(query, asked.size(), paused);
    currentPage = new NavigationSnapshot("search", "全局搜索", Json.obj("initial", query));
    route = () -> searchPage(query);
    loaded(search);
    for (JSONObject site : asked) {
      String healthKey = SourceHealth.key(site);
      app.io.execute(
          () -> {
            try {
              List<JSONObject> videos =
                  Catalog.videos(app.engine.call(site, Json.obj("wd", query, "pg", "1")));
              health.recordSuccess(healthKey);
              // The titles that are what was searched for lead; the rest follow, none hidden.
              videos = TitleMatch.ordered(videos, query, v -> v.optString("vod_name"));
              final List<JSONObject> shown = videos;
              runOnUiThread(
                  () -> {
                    if (generation != token) return;
                    search.success(site, shown);
                    status.setText(search.status());
                    if (!shown.isEmpty()) appendSearchResult(site, shown);
                  });
            } catch (Exception e) {
              // A busy local runtime is backpressure, not evidence that the remote source failed.
              if (!JarCallGuard.isBusy(e)) health.recordFailure(healthKey);
              runOnUiThread(
                  () -> {
                    if (generation != token) return;
                    search.failure();
                    status.setText(search.status());
                  });
            }
          });
    }
  }

  private String discoverLanguage() {
    String configured = repo.settings.getString("appLanguage", "");
    return configured.equals("auto")
        ? DiscoverCatalog.language(Locale.getDefault())
        : DiscoverCatalog.language(configured, Locale.getDefault());
  }

  private String discoverLanguageLabel(String language) {
    switch (language) {
      case "zh-CN": return "简体中文";
      case "zh-TW": return "繁体中文";
      case "ja-JP": return "日本語";
      default: return "English";
    }
  }

  private void discover(String feed, int pg) {
    discover(feed, pg, null);
  }

  private void discover(String feed, int pg, JSONObject cached) {
    int token =
        page(
            "discover",
            "发现",
            Json.obj("feed", feed, "page", pg),
            () -> discover(feed, pg));
    LinearLayout filters = actions();
    for (JSONObject option : DiscoverCatalog.feeds())
      spaced(
          filters,
          button(
              this,
              (option.optString("feed").equals(feed) ? "✓ " : "") + option.optString("name"),
              () -> discover(option.optString("feed"), 1)),
          10);
    if (cached != null) {
      loaded(cached);
      renderDiscover(feed, pg, cached);
      return;
    }
    loading();
    async(
        token,
        () ->
            app.http.json(
                Http.query(
                    "https://yingxia-tmdb.getmegaportal.com/v1/catalog",
                    Http.params("feed", feed, "page", "" + pg, "language", discoverLanguage()))),
        answer -> {
          loaded();
          loaded(answer);
          renderDiscover(feed, pg, answer);
        });
  }

  private void renderDiscover(String feed, int pg, JSONObject answer) {
    List<JSONObject> items = Json.objects(Json.array(answer.opt("items")));
    if (items.isEmpty()) empty("◈", "暂时没有内容", "稍后刷新再试。", "刷新", route);
    else
      grid(
          items,
          s -> s.optString("name"),
          s ->
              s.optString("year")
                  + (s.optDouble("rating", 0) > 0
                      ? "  ★ " + String.format(Locale.ROOT, "%.1f", s.optDouble("rating"))
                      : ""),
          s -> s.optString("poster"),
          s -> push(() -> discoverDetail(s)));
    pagination(pg, answer.optInt("totalPages", pg), p -> discover(feed, p));
  }

  private void discoverDetail(JSONObject item) {
    discoverDetail(item, null);
  }

  private void discoverDetail(JSONObject item, JSONObject cached) {
    int token =
        page(
            "discoverDetail",
            item.optString("name"),
            Json.obj("item", item),
            () -> discoverDetail(item));
    if (cached != null) {
      loaded(cached);
      renderDiscoverDetail(cached);
      return;
    }
    loading();
    async(
        token,
        () -> {
          JSONObject details =
              app.http.json(
                  Http.query(
                      DiscoverCatalog.DETAILS_ENDPOINT,
                      DiscoverCatalog.detailsParameters(
                          item.optString("mediaType", "movie"),
                          item.optString("id"),
                          discoverLanguage())));
          return DiscoverCatalog.mergeDetails(item, details);
        },
        details -> {
          loaded();
          loaded(details);
          renderDiscoverDetail(details);
        });
  }

  private void renderDiscoverDetail(JSONObject item) {
    LinearLayout hero = horizontal(this);
    hero.setGravity(Gravity.TOP);
    ImageView image = new ImageView(this);
    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    hero.addView(image, new LinearLayout.LayoutParams(dp(this, 180), dp(this, 270)));
    Artwork.load(image, item.optString("poster"), 180, 270);
    LinearLayout info = vertical(this);
    pad(info, 28, 8);
    info.addView(text(this, item.optString("name"), 34, TEXT));
    if (!item.optString("tagline").isEmpty()) {
      gap(info, 8);
      info.addView(text(this, item.optString("tagline"), 17, SECONDARY));
    }
    gap(info, 12);
    List<String> facts = new ArrayList<>();
    if (!item.optString("year").isEmpty()) facts.add(item.optString("year"));
    if (item.optDouble("rating", 0) > 0)
      facts.add(
          "★ "
              + String.format(Locale.ROOT, "%.1f", item.optDouble("rating"))
              + (item.optInt("voteCount") > 0 ? "（" + item.optInt("voteCount") + " 人评分）" : ""));
    if (item.optDouble("runtime", 0) > 0)
      facts.add(String.format(Locale.ROOT, "%.0f 分钟", item.optDouble("runtime")));
    if (!item.optString("status").isEmpty()) facts.add(item.optString("status"));
    info.addView(text(this, String.join(" · ", facts), 17, SECONDARY));
    String genres = names(item.opt("genres"), "");
    String countries = names(item.opt("countries"), "");
    if (!genres.isEmpty() || !countries.isEmpty()) {
      gap(info, 10);
      List<String> classification = new ArrayList<>();
      if (!genres.isEmpty()) classification.add(genres);
      if (!countries.isEmpty()) classification.add(countries);
      info.addView(text(this, String.join(" · ", classification), 15, SECONDARY));
    }
    gap(info, 18);
    info.addView(text(this, item.optString("overview"), 16, SECONDARY));
    gap(info, 24);
    info.addView(button(this, "⌕  在全部点播源中查找", () -> push(() -> searchPage(item.optString("name")))));
    hero.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
    body.addView(hero);
    String cast = names(item.opt("cast"), "name");
    if (!cast.isEmpty()) {
      sectionTitle("演员");
      body.addView(text(this, cast, 15, SECONDARY));
    }
    String studios = names(item.opt("studios"), "name");
    if (!studios.isEmpty()) {
      sectionTitle("制作与播出");
      body.addView(text(this, studios, 15, SECONDARY));
    }
    List<JSONObject> seasons = Json.objects(Json.array(item.opt("seasons")));
    if (!seasons.isEmpty()) {
      sectionTitle("季");
      body.addView(text(this, names(item.opt("seasons"), "name"), 15, SECONDARY));
    }
    List<JSONObject> recommendations = Json.objects(Json.array(item.opt("recommendations")));
    if (!recommendations.isEmpty()) {
      sectionTitle("你可能喜欢");
      grid(
          recommendations,
          s -> s.optString("name"),
          s -> s.optString("year"),
          s -> s.optString("poster"),
          s -> push(() -> discoverDetail(s)));
    }
  }

  private static String names(Object value, String key) {
    List<String> out = new ArrayList<>();
    JSONArray array = Json.array(value);
    for (int index = 0; index < array.length(); index++) {
      Object item = array.opt(index);
      String name = item instanceof JSONObject ? ((JSONObject) item).optString(key) : String.valueOf(item);
      if (!name.trim().isEmpty() && !name.equals("null")) out.add(name.trim());
    }
    return String.join(" / ", out);
  }

  private void music(String provider, String query, int pg) {
    music(provider, query, pg, "song", null);
  }

  private void music(String provider, String query, int pg, String kind, JSONObject cached) {
    int token = page("music", "音乐", Json.obj("provider", provider, "query", query, "page", pg, "kind", kind),
        () -> music(provider, query, pg, kind, null));
    spaced(header, button(this, "♫ ItaloDance 电台", () -> RadioActivity.open(this)), 0);
    LinearLayout platforms = actions();
    String[][] options = {
      {"netease", "GD 音乐台 · 网易云"}, {"tencent", "QQ 音乐"}, {"kuwo", "酷我音乐"},
      {"migu", "咪咕音乐"}, {"kugou", "酷狗音乐"}, {"musify", "Musify"},
      {"audius", "Audius · Hardstyle / ItaloDance"}, {"jamendo", "Jamendo · 独立电子音乐"}
    };
    for (String[] p : options) {
      TextView platform = button(this, (p[0].equals(provider) ? "✓ " : "") + p[1], () -> music(p[0], query, 1));
      spaced(platforms, platform, 10);
      if (p[0].equals(provider)) platform.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
        @Override public void onGlobalLayout() {
          platform.getViewTreeObserver().removeOnGlobalLayoutListener(this);
          if (token == generation) {
            HorizontalScrollView rail = (HorizontalScrollView) platforms.getParent();
            rail.scrollTo(Math.max(0, platform.getRight() - rail.getWidth() + dp(MainActivity.this, 2)), 0);
          }
        }
      });
    }
    LinearLayout controls = actions();
    String quality = MusicResolver.normalizeQuality(repo.settings.getString("musicQuality", MusicResolver.HIGH));
    String qualityLabel = quality.equals(MusicResolver.FLAC) ? "FLAC 无损" : quality + " kbps";
    if (!provider.equals("musify") && !provider.equals("audius") && !provider.equals("jamendo")) spaced(controls, button(this, "优先音质：" + qualityLabel, () -> new AlertDialog.Builder(this)
        .setTitle("音乐音质")
        .setItems(new String[] {"FLAC 无损", "320 kbps", "128 kbps"}, (dialog, selected) -> {
          repo.settings.edit().putString("musicQuality", selected == 0 ? MusicResolver.FLAC : selected == 1 ? MusicResolver.HIGH : MusicResolver.STANDARD).apply();
          music(provider, query, pg, kind, (JSONObject) currentPage.data());
        }).setNegativeButton("取消", null).show()), 10);
    if (MusicClient.hasCollections(provider)) {
      spaced(controls, button(this, "榜单 / 推荐", () -> music(provider, "", 1)), 10);
      String[][] kinds = {{"song", "歌曲"}, {"playlist", "歌单"}, {"album", "专辑"}};
      for (String[] item : kinds)
        spaced(controls, button(this, (kind.equals(item[0]) ? "✓ " : "") + item[1],
            () -> music(provider, query, 1, item[0], null)), 10);
      body.addView(text(this, provider.equals("netease") ? "音源：GD 音乐台 · music.gdstudio.xyz · 仅限个人非商业使用" : "QQ 音乐 · 榜单、歌单和专辑", 12, SECONDARY));
      gap(body, 12);
    }
    if (provider.equals("musify")) {
      body.addView(text(this, "Musify · 站点原始音质 · 点按播放，长按收藏或下载", 12, SECONDARY));
      gap(body, 12);
    }
    if (provider.equals("audius")) {
      body.addView(text(this, "Audius · 海外公开电子音乐流 · 原始音质 · 需要电视可访问海外网络", 12, SECONDARY));
      gap(body, 12);
    }
    if (provider.equals("jamendo")) {
      body.addView(text(this, "Jamendo · 独立音乐与 Creative Commons 曲目 · 原始音质", 12, SECONDARY));
      gap(body, 12);
    }
    LinearLayout row = horizontal(this);
    EditText input = edit(provider.equals("musify") ? "搜索歌曲、歌手，或粘贴 Musify 歌手链接" : "搜索歌曲、歌手、歌单或专辑", query);
    row.addView(input, new LinearLayout.LayoutParams(0, dp(this, 52), 1));
    Runnable search = () -> {
      hideKeyboard(input);
      String nextQuery = input.getText().toString().trim();
      if (nextQuery.equals(query)) music(provider, nextQuery, 1, kind, null);
      else push(() -> music(provider, nextQuery, 1, kind, null));
    };
    input.setOnEditorActionListener((v, action, event) -> {
      if (action != EditorInfo.IME_ACTION_SEARCH) return false;
      search.run();
      return true;
    });
    spaced(row, button(this, "搜索", search), 12);
    body.addView(row);
    gap(body, 18);
    if (query.isEmpty()) {
      List<JSONObject> recent = new ArrayList<>();
      for (JSONObject song : Json.objects(repo.list("history"))) if (song.optBoolean("music")) recent.add(song);
      if (!recent.isEmpty()) {
        sectionTitle("继续播放");
        tiles(recent, song -> song.optString("name"), song -> song.optString("artist"), this::resume, null);
      }
      if (!MusicClient.hasCollections(provider)) {
        if (recent.isEmpty()) empty("♫", "让音乐充满客厅", "搜索你喜欢的歌曲，选择一个音源开始播放。", null, null);
        return;
      }
    }
    Consumer<JSONObject> render = result -> {
      loaded();
      loaded(result);
      if (query.isEmpty()) {
        List<JSONObject> sections = Json.objects(Json.array(result.opt("sections")));
        if (sections.isEmpty()) body.addView(text(this, "暂无推荐，可搜索歌曲、歌单或专辑。", 16, SECONDARY));
        for (JSONObject section : sections) {
          sectionTitle(section.optString("name"));
          musicCollections(Json.objects(Json.array(section.opt("items"))));
        }
      } else {
        List<JSONObject> items = Json.objects(Json.array(result.opt("items")));
        if (items.isEmpty()) body.addView(text(this, "没有更多结果，试试其他关键词。", 16, SECONDARY));
        if (kind.equals("song") || !MusicClient.hasCollections(provider)) musicSongs(items);
        else musicCollections(items);
        pagination(pg, result.optBoolean("hasNext", items.size() == GdMusicCatalog.PAGE_SIZE) ? pg + 1 : pg,
            next -> music(provider, query, next, kind, null));
      }
    };
    if (cached != null) { render.accept(cached); return; }
    loading();
    async(token, () -> {
      MusicClient client = new MusicClient();
      if (query.isEmpty()) return Json.obj("sections", new JSONArray(client.browse(provider)));
      if (provider.equals("musify")) return client.musify.searchPage(query, pg);
      List<JSONObject> items = MusicClient.hasCollections(provider) && !kind.equals("song")
          ? client.collections(query, provider, kind, pg) : client.search(query, provider, pg);
      return Json.obj("items", new JSONArray(items));
    }, render, message -> {
      loaded();
      body.addView(text(this, message, 16, SECONDARY));
      body.addView(button(this, "重试", () -> music(provider, query, pg, kind, null)));
    });
  }

  private void musicCollections(List<JSONObject> collections) {
    grid(collections, item -> item.optString("name"),
        item -> item.optString("subtitle") + (item.optInt("trackCount") > 0 ? " · " + item.optInt("trackCount") + " 首" : ""),
        item -> item.optString("poster"), item -> push(() -> musicCollection(item, 1, null)));
  }

  private void musicCollection(JSONObject collection, int pg, List<JSONObject> cached) {
    int token = page("musicCollection", collection.optString("name"), Json.obj("collection", collection, "page", pg),
        () -> musicCollection(collection, pg, null));
    body.addView(text(this, MusicClient.platformName(collection.optString("source", "netease")) + " · 长按歌曲收藏或下载", 14, SECONDARY));
    Consumer<List<JSONObject>> render = songs -> {
      loaded();
      loaded(songs);
      if (songs.isEmpty()) {
        empty("♫", "暂无可用歌曲", "该歌单或专辑暂未返回歌曲。", null, null);
        return;
      }
      int pages = (songs.size() + GdMusicCatalog.PAGE_SIZE - 1) / GdMusicCatalog.PAGE_SIZE;
      int selected = Math.max(1, Math.min(pg, pages));
      sectionTitle(songs.size() + " 首歌曲");
      musicSongs(songs.subList((selected - 1) * GdMusicCatalog.PAGE_SIZE, Math.min(songs.size(), selected * GdMusicCatalog.PAGE_SIZE)), songs, collection.optString("name"));
      pagination(selected, pages, next -> musicCollection(collection, next, songs));
    };
    if (cached != null) { render.accept(cached); return; }
    loading();
    async(token, () -> new MusicClient().tracks(collection), render);
  }

  private void musicSongs(List<JSONObject> songs) {
    musicSongs(songs, null, "");
  }

  private void musicSongs(List<JSONObject> songs, List<JSONObject> queue, String source) {
    tiles(songs, song -> song.optString("name"),
        song -> song.optString("artist") + (song.optString("album").isEmpty() ? "" : " · " + song.optString("album")) +
            (song.optLong("duration") > 0 ? " · " + formatTime(song.optLong("duration")) : ""),
        song -> {
          toast("正在准备音源…");
          async(generation, () -> new MusicClient().play(song, repo.settings.getString("musicQuality", MusicResolver.HIGH)),
              play -> {
                if (queue == null) MusicPlayerActivity.open(this, play);
                else MusicPlayerActivity.open(this, play, queue, queue.indexOf(song), source);
              });
        }, song -> new AlertDialog.Builder(this).setTitle(song.optString("name"))
            .setItems(new String[] {repo.contains("favorites", song.optString("id")) ? "取消收藏" : "收藏歌曲", "下载歌曲"},
                (dialog, selected) -> {
                  if (selected == 0) toast(repo.favorite(song) ? "已收藏歌曲" : "已取消收藏");
                  else {
                    toast("正在准备下载…");
                    async(generation, () -> new MusicClient().play(song, repo.settings.getString("musicQuality", MusicResolver.HIGH)),
                        play -> OfflineService.add(this, play));
                  }
                }).setNegativeButton("返回", null).show());
  }

  private void downloads() {
    page("downloads", "下载", new JSONObject(), this::downloads);
    List<JSONObject> items = Json.objects(repo.list("downloads"));
    if (items.isEmpty()) {
      empty("↓", "随时离线观看", "在选集页长按确定键下载一集，或从播放器保存当前内容。", "浏览点播源", () -> select(5));
      return;
    }
    tiles(
        items,
        s -> s.optString("name"),
        s -> OfflineService.status(this, s),
        s -> {
          if (s.optBoolean("complete")) PlayerActivity.open(this, s);
          else toast(OfflineService.status(this, s));
        },
        s ->
            new AlertDialog.Builder(this)
                .setTitle("删除下载？")
                .setPositiveButton(
                    "删除",
                    (d, i) -> {
                      OfflineService.remove(this, s);
                      downloads();
                    })
                .setNegativeButton("取消", null)
                .show());
  }

  private void downloadEpisode(JSONObject site, JSONObject video, JSONObject episode) {
    toast("正在准备下载…");
    async(
        generation,
        () -> app.engine.playback(site, video, episode),
        play -> {
          OfflineService.add(this, play);
          toast("已加入下载队列");
        });
  }

  private void settings() {
    page("settings", "设置", new JSONObject(), this::settings);
    sectionTitle("订阅");
    LinearLayout row = actions();
    spaced(row, button(this, "＋  添加订阅", this::importDialog), 10);
    spaced(
        row,
        button(
            this,
            "从文件导入",
            () -> {
              Intent i =
                  new Intent(Intent.ACTION_OPEN_DOCUMENT)
                      .setType("*/*")
                      .addCategory(Intent.CATEGORY_OPENABLE);
              startActivityForResult(i, 10);
            }),
        10);
    spaced(row, button(this, "手机扫码输入", this::remoteDialog), 10);
    List<JSONObject> subscriptions = repo.subscriptions();
    for (JSONObject sub : subscriptions) {
      LinearLayout item = horizontal(this);
      LinearLayout label = vertical(this);
      label.addView(text(this, sub.optString("name"), 18, TEXT));
      TextView address = text(this, sub.optString("origin"), 12, SECONDARY);
      address.setSingleLine(true);
      label.addView(address);
      item.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
      spaced(item, button(this, "刷新", () -> refresh(sub)), 8);
      spaced(
          item,
          button(
              this,
              "管理",
              () ->
                  new AlertDialog.Builder(this)
                      .setTitle(sub.optString("name"))
                      .setItems(
                          new String[] {"重命名", "移除订阅"},
                          (d, n) -> {
                            if (n == 0)
                              input(
                                  "订阅名称",
                                  "名称",
                                  sub.optString("name"),
                                  name -> {
                                    try {
                                      sub.put("name", name);
                                    } catch (Exception ignored) {
                                    }
                                    repo.upsert("subscriptions", sub, "id", false, 100);
                                    settings();
                                  });
                            else
                              new AlertDialog.Builder(this)
                                  .setMessage("移除此订阅及其点播源？")
                                  .setPositiveButton(
                                      "移除",
                                      (a, b) -> {
                                        repo.remove("subscriptions", sub.optString("id"));
                                        app.engine.clear();
                                        settings();
                                      })
                                  .setNegativeButton("取消", null)
                                  .show();
                          })
                      .show()),
          8);
      body.addView(item);
      gap(body, 14);
    }
    sectionTitle("播放");
    LinearLayout playback = actions();
    spaced(
        playback,
        button(
            this,
            "片头跳过：" + repo.settings.getInt("intro", 0) + " 秒",
            () ->
                input(
                    "自动跳过片头",
                    "秒数",
                    "" + repo.settings.getInt("intro", 0),
                    s -> {
                      try {
                        repo.settings
                            .edit()
                            .putInt("intro", Math.max(0, Integer.parseInt(s)))
                            .apply();
                        settings();
                      } catch (Exception e) {
                        toast("请输入秒数");
                      }
                    })),
        10);
    spaced(
        playback,
        button(
            this,
            "片尾跳过：" + repo.settings.getInt("outro", 0) + " 秒",
            () ->
                input(
                    "自动跳过片尾",
                    "秒数",
                    "" + repo.settings.getInt("outro", 0),
                    s -> {
                      try {
                        repo.settings
                            .edit()
                            .putInt("outro", Math.max(0, Integer.parseInt(s)))
                            .apply();
                        settings();
                      } catch (Exception e) {
                        toast("请输入秒数");
                      }
                    })),
        10);
    sectionTitle("发现");
    LinearLayout discovery = actions();
    String language = discoverLanguage();
    spaced(
        discovery,
        button(
            this,
            "发现语言：" + discoverLanguageLabel(language),
            () ->
                choose(
                    "发现语言",
                    List.of(
                        Json.obj("id", "auto", "name", "跟随系统"),
                        Json.obj("id", "zh-CN", "name", "简体中文"),
                        Json.obj("id", "zh-TW", "name", "繁体中文"),
                        Json.obj("id", "en-US", "name", "English"),
                        Json.obj("id", "ja-JP", "name", "日本語")),
                    value -> value.optString("name"),
                    value -> {
                      repo.settings.edit().putString("appLanguage", value.optString("id")).apply();
                      settings();
                    })),
        10);
    sectionTitle("资料与存储");
    LinearLayout storage = actions();
    spaced(
        storage,
        button(
            this,
            "同时下载：" + OfflineService.parallelDownloads() + " 个",
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("同时下载数")
                    .setItems(
                        new String[] {"1 个", "2 个", "3 个", "4 个"},
                        (dialog, selected) -> {
                          OfflineService.setParallelDownloads(selected + 1);
                          settings();
                        })
                    .setNegativeButton("取消", null)
                    .show()),
        10);
    spaced(
        storage,
        button(
            this,
            "导出备份",
            () ->
                startActivityForResult(
                    new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .setType("application/json")
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .putExtra(Intent.EXTRA_TITLE, "yingxia-backup.json"),
                    11)),
        10);
    spaced(
        storage,
        button(
            this,
            "恢复备份",
            () ->
                startActivityForResult(
                    new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .setType("application/json")
                        .addCategory(Intent.CATEGORY_OPENABLE),
                    12)),
        10);
    spaced(
        storage,
        button(
            this,
            "恢复隐藏点播源",
            () -> {
              SharedPreferences.Editor edit = repo.settings.edit();
              for (String key : repo.settings.getAll().keySet())
                if (key.startsWith("hidden.")) edit.remove(key);
              edit.apply();
              toast("已恢复手动隐藏的点播源");
            }),
        10);
    spaced(
        storage,
        button(
            this,
            "清除图片缓存",
            () -> {
              Artwork.clear();
              toast("已清除");
            }),
        10);
    sectionTitle("关于映匣");
    body.addView(
        text(this, "映匣  " + BuildConfig.VERSION_NAME + "  ·  Android TV / ARM32", 18, TEXT));
    gap(body, 8);
    body.addView(text(this, "所有点播源，一方大屏。", 16, SECONDARY));
    gap(body, 12);
    body.addView(text(this, "基于 tvbox-silicon · GPL-3.0\n原版图标、脚本与视觉规范沿用映匣项目。", 14, SECONDARY));
    gap(body, 18);
    body.addView(
        button(
            this,
            "开源许可",
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("开源许可")
                    .setMessage(
                        "Yingxia — GPL-3.0-only\n"
                            + "QuickJS-ng — MIT\n"
                            + "AndroidX Media3 — Apache-2.0\n"
                            + "OkHttp — Apache-2.0\n"
                            + "jsoup — MIT\n"
                            + "ZXing — Apache-2.0\n"
                            + "Venera 脚本 API — GPL-3.0\n"
                            + "详见项目 THIRD_PARTY_NOTICES.md 与 LICENSE。")
                    .setPositiveButton("好", null)
                    .show()));
  }

  private void importDialog() {
    LinearLayout form = vertical(this);
    pad(form, 24, 12);
    EditText url = edit("TVBox / M3U / TXT 地址", ""), name = edit("订阅名称（可选）", "");
    form.addView(url);
    form.addView(name);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("添加订阅")
            .setView(form)
            .setPositiveButton("添加", null)
            .setNegativeButton("取消", null)
            .create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(-1)
                .setOnClickListener(
                    v -> {
                      try {
                        String address = ConfigCodec.address(url.getText().toString(), null);
                        dialog.getButton(-1).setEnabled(false);
                        toast("正在读取订阅…");
                        app.io.execute(
                            () -> {
                              try {
                                Http.Result r =
                                    app.http.get(
                                        address, Http.params("User-Agent", "okhttp/3.12.13"));
                                List<JSONObject> directory = ConfigCodec.directory(r.bytes, r.url);
                                if (!directory.isEmpty()) {
                                  runOnUiThread(
                                      () -> {
                                        dialog.dismiss();
                                        chooseDirectory(directory);
                                      });
                                  return;
                                }
                                repo.importConfig(r.bytes, r.url, name.getText().toString());
                                app.engine.clear();
                                runOnUiThread(
                                    () -> {
                                      dialog.dismiss();
                                      select(0);
                                      toast("订阅已添加");
                                    });
                              } catch (Exception e) {
                                runOnUiThread(
                                    () -> {
                                      dialog.getButton(-1).setEnabled(true);
                                      url.setError(Json.message(e));
                                    });
                              }
                            });
                      } catch (Exception e) {
                        url.setError(Json.message(e));
                      }
                    }));
    dialog.show();
  }

  /** A multi-subscription directory is a choice list, not an import error. */
  private void chooseDirectory(List<JSONObject> entries) {
    String[] labels = new String[entries.size()];
    for (int i = 0; i < entries.size(); i++) {
      String name = entries.get(i).optString("name");
      labels[i] = name.trim().isEmpty() ? entries.get(i).optString("url") : name;
    }
    new AlertDialog.Builder(this)
        .setTitle("选择订阅")
        .setItems(
            labels,
            (dialog, which) -> {
              JSONObject entry = entries.get(which);
              toast("正在读取订阅…");
              async(
                  generation,
                  () -> {
                    Http.Result r =
                        app.http.get(
                            entry.optString("url"),
                            Http.params("User-Agent", "okhttp/3.12.13"));
                    repo.importConfig(r.bytes, r.url, entry.optString("name"));
                    app.engine.clear();
                    return true;
                  },
                  imported -> {
                    select(0);
                    toast("订阅已添加");
                  });
            })
        .show();
  }

  private void refresh(JSONObject sub) {
    toast("正在刷新订阅…");
    async(
        generation,
        () -> {
          String origin = sub.optString("origin");
          if (origin.contains("/imported/")) throw new IOException("本地文件请重新导入");
          Http.Result r = app.http.get(origin, Http.params("User-Agent", "okhttp/3.12.13"));
          repo.importConfig(r.bytes, r.url, sub.optString("name"));
          app.engine.clear();
          return true;
        },
        x -> {
          toast("刷新成功");
          settings();
        });
  }

  private void remoteDialog() {
    if (handoff == null || handoff.url().isEmpty()) {
      toast("请先连接局域网");
      return;
    }
    String address = handoff.url();
    LinearLayout box = vertical(this);
    pad(box, 32, 18);
    box.setGravity(Gravity.CENTER);
    View qr;
    try {
      com.google.zxing.common.BitMatrix matrix =
          new com.google.zxing.MultiFormatWriter()
              .encode(address, com.google.zxing.BarcodeFormat.QR_CODE, 320, 320);
      android.graphics.Bitmap bitmap =
          android.graphics.Bitmap.createBitmap(320, 320, android.graphics.Bitmap.Config.RGB_565);
      for (int y = 0; y < 320; y++)
        for (int x = 0; x < 320; x++)
          bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
      ImageView image = new ImageView(this);
      image.setImageBitmap(bitmap);
      qr = image;
    } catch (Exception failure) {
      android.util.Log.e("Yingxia.Main", "Cannot create subscription QR code", failure);
      qr = text(this, "二维码生成失败，请使用下方地址手动输入。", 16, SECONDARY);
    }
    box.addView(qr, new LinearLayout.LayoutParams(dp(this, 220), dp(this, 220)));
    gap(box, 16);
    box.addView(text(this, address, 16, TEXT));
    gap(box, 10);
    box.addView(text(this, "手机与电视连接同一网络，扫码后输入订阅地址。", 14, SECONDARY));
    new AlertDialog.Builder(this)
        .setTitle("手机输入")
        .setView(box)
        .setPositiveButton("完成", (d, i) -> select(0))
        .show();
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    int token = generation;
    async(
        token,
        () -> {
          if (request == 11) {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
              if (out == null) throw new IOException("无法写入文件");
              out.write(repo.exportWithoutAccounts().getBytes(StandardCharsets.UTF_8));
            }
            return "备份已导出";
          }
          byte[] bytes;
          try (InputStream in = getContentResolver().openInputStream(uri);
              ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IOException("无法读取文件");
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) != -1) {
              if (out.size() + n > 20_000_000) throw new IOException("文件超过 20 MB");
              out.write(b, 0, n);
            }
            bytes = out.toByteArray();
          }
          if (request == 12) repo.restore(new String(bytes, StandardCharsets.UTF_8));
          else {
            String origin =
                handoff == null ? "http://127.0.0.1/imported/" : handoff.localBase() + "/imported/";
            repo.importConfig(
                bytes, origin + ConfigCodec.hash(uri.toString()) + "/config.json", "本地订阅");
          }
          return request == 12 ? "备份已恢复" : "订阅已导入";
        },
        message -> {
          toast(message);
          settings();
        });
  }

  public void subscriptionImported() {
    toast("手机已添加订阅");
    select(0);
  }

  private void handleLink(Intent intent) {
    if (BuildConfig.DEBUG && intent.hasExtra("qaFixture")) {
      String fixture = intent.getStringExtra("qaFixture");
      int destination = intent.getIntExtra("qaSection", 0);
      async(
          generation,
          () -> {
            Http.Result response = app.http.get(fixture);
            repo.importConfig(response.bytes, response.url, "本地界面验证");
            app.engine.clear();
            return true;
          },
          ignored -> {
            select(Math.max(0, Math.min(TITLES.length - 1, destination)));
            String name = intent.getStringExtra("qaTitle");
            if (name != null && !repo.sites().isEmpty())
              push(() -> sourceSearch(repo.sites().get(0), name, 1));
          });
      return;
    }
    if (Intent.ACTION_SEARCH.equals(intent.getAction())
        || "com.yingxia.tv.action.SEARCH_MUSIC".equals(intent.getAction())) {
      String query = intent.getStringExtra(SearchManager.QUERY);
      if (query == null) query = intent.getStringExtra("query");
      handleMusicVoice(query, intent.getBooleanExtra("autoplay", false), intent.getStringExtra("source"));
      return;
    }
    Uri uri = intent.getData();
    if (uri == null || !"yingxia".equalsIgnoreCase(uri.getScheme())) return;
    if ("music".equalsIgnoreCase(uri.getHost()) && "/search".equalsIgnoreCase(uri.getPath())) {
      handleMusicVoice(uri.getQueryParameter("query"), isTrue(uri.getQueryParameter("autoplay")),
          uri.getQueryParameter("source"));
      return;
    }
    final JSONObject link;
    try {
      link = SubscriptionLink.parse(uri.toString());
    } catch (Exception error) {
      android.util.Log.w("Yingxia.Main", "Rejected subscription deep link", error);
      toast(Json.message(error));
      return;
    }
    input(
        "导入订阅",
        "订阅地址",
        link.optString("url"),
        address -> {
          async(
              generation,
              () -> {
                Http.Result r = app.http.get(ConfigCodec.address(address, null));
                repo.importConfig(r.bytes, r.url, link.optString("name", null));
                app.engine.clear();
                return true;
              },
              x -> select(0));
        });
  }

  private void handleMusicVoice(String query, boolean autoplay, String source) {
    String value = query == null ? "" : query.trim();
    if (value.isEmpty()) {
      select(3);
      toast("没有收到歌曲名称");
      return;
    }
    String provider = musicProvider(source);
    if (autoplay) {
      select(3);
      voiceSearchMusic(provider, value);
    } else {
      select(3);
      music(provider, value, 1);
    }
  }

  private void voiceSearchVideo(String query) {
    int token = page("voiceVideo", "语音播放", Json.obj("query", query),
        () -> voiceSearchVideo(query));
    body.addView(text(this, "正在搜索并准备播放：" + query, 16, SECONDARY));
    List<JSONObject> sites = new ArrayList<>();
    for (JSONObject site : repo.sites())
      if (repo.isSourceVisible(site) && !Catalog.isIndex(site)) sites.add(site);
    if (sites.isEmpty()) {
      toast("没有可搜索的影视源");
      searchPage(query);
      return;
    }
    loading();
    VoiceVideoSearch state = new VoiceVideoSearch(sites.size());
    for (JSONObject site : sites) {
      app.io.execute(() -> {
        JSONObject best = null;
        int bestScore = -1;
        try {
          List<JSONObject> videos = Catalog.videos(app.engine.call(site, Json.obj("wd", query, "pg", "1")));
          for (JSONObject video : TitleMatch.ordered(videos, query, v -> v.optString("vod_name"))) {
            int score = voiceVideoScore(query, video.optString("vod_name"));
            if (score > bestScore) {
              best = video;
              bestScore = score;
            }
          }
        } catch (Exception error) {
          android.util.Log.d("Yingxia.Voice", "voice video search failed", error);
        }
        boolean done;
        synchronized (state) {
          if (best != null && bestScore > state.score) {
            state.score = bestScore;
            state.site = site;
            state.video = best;
          }
          state.remaining--;
          done = state.remaining == 0;
        }
        if (done) runOnUiThread(() -> finishVoiceVideo(token, query, state));
      });
    }
  }

  private void finishVoiceVideo(int token, String query, VoiceVideoSearch state) {
    if (generation != token || isFinishing()) return;
    if (state.site == null || state.video == null) {
      toast("没有找到可播放的影视内容");
      searchPage(query);
      return;
    }
    loaded();
    body.addView(text(this, "已找到：" + state.video.optString("vod_name") + "，正在准备播放…", 16, SECONDARY));
    JSONObject site = state.site;
    JSONObject listed = state.video;
    async(token, () -> {
      JSONObject answer = app.engine.call(site, Json.obj("ids", listed.optString("vod_id")));
      List<JSONObject> details = Catalog.videos(answer);
      JSONObject video = details.isEmpty() ? listed : details.get(0);
      List<JSONObject> episodes = Catalog.episodes(video);
      if (episodes.isEmpty()) throw new IOException("这个影视条目没有可播放集数");
      return app.engine.playback(site, video, episodes.get(0));
    }, play -> PlayerActivity.open(this, play), message -> {
      toast(message);
      details(site, listed);
    });
  }

  private static int voiceVideoScore(String query, String title) {
    String q = voiceNormalize(query), t = voiceNormalize(title);
    if (q.isEmpty() || t.isEmpty()) return 0;
    if (q.equals(t)) return 1000;
    if (t.contains(q)) return 800;
    if (q.contains(t)) return 700;
    return 100;
  }

  private static boolean isMusicVoice(String text) {
    return hasVoiceWord(text, "歌曲", "音乐", "听歌", "歌手", "专辑", "歌词");
  }

  private static boolean hasVoiceWord(String text, String... words) {
    if (text == null) return false;
    for (String word : words) if (text.contains(word)) return true;
    return false;
  }

  private static String voiceQuery(String text) {
    String value = text == null ? "" : text.trim();
    String[] prefixes = {
        "小爱同学", "小爱", "播放一下", "搜索一下", "搜一下", "查找一下", "打开一下",
        "请你", "请", "帮我", "给我", "我要", "播放", "搜索", "搜", "查找", "找一下",
        "找", "打开", "观看", "看一下", "看", "听一下", "听"
    };
    boolean changed;
    do {
      changed = false;
      value = value.replaceFirst("^[，,。.!！、:：\\s]+", "");
      for (String prefix : prefixes) {
        if (value.startsWith(prefix)) {
          value = value.substring(prefix.length());
          changed = true;
          break;
        }
      }
    } while (changed);
    for (String kind : new String[] {"歌曲", "音乐", "电影", "电视剧", "电视", "影片", "连续剧"}) {
      if (value.startsWith(kind)) value = value.substring(kind.length());
    }
    return value.replaceFirst("^[，,。.!！、:：\\s]+", "").trim();
  }

  private static String voiceNormalize(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT)
        .replaceAll("[\\p{P}\\p{S}\\s]+", "");
  }

  private void voiceSearchMusic(String provider, String query) {
    int token = page("voiceMusic", "语音播放", Json.obj("provider", provider, "query", query),
        () -> voiceSearchMusic(provider, query));
    body.addView(text(this, "正在搜索并准备播放：" + query, 16, SECONDARY));
    loading();
    async(token, () -> {
      List<JSONObject> songs = new MusicClient().search(query, provider, 1);
      if (songs.isEmpty()) throw new IOException("没有找到匹配的歌曲");
      return new MusicClient().play(songs.get(0), repo.settings.getString("musicQuality", MusicResolver.HIGH));
    }, play -> MusicPlayerActivity.open(this, play), message -> {
      toast(message);
      music(provider, query, 1);
    });
  }

  private static boolean isTrue(String value) {
    return "1".equals(value) || "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value)
        || "on".equalsIgnoreCase(value);
  }

  private static String musicProvider(String value) {
    if (value == null) return "netease";
    String provider = value.trim().toLowerCase(Locale.ROOT);
    return Arrays.asList("netease", "tencent", "qq", "kuwo", "migu", "kugou", "musify", "audius", "jamendo").contains(provider)
        ? provider : "netease";
  }

  private EditText edit(String hint, String value) {
    EditText e = new EditText(this);
    e.setTextColor(TEXT);
    e.setHintTextColor(SECONDARY);
    e.setTextSize(16);
    e.setSingleLine(true);
    e.setHint(hint);
    e.setText(value);
    e.setSelectAllOnFocus(true);
    e.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
    e.setMinHeight(dp(this, 48));
    e.setBackground(rounded(SURFACE, 10, this));
    pad(e, 16, 8);
    return e;
  }

  private void input(String title, String hint, String initial, Consumer<String> done) {
    EditText e = edit(hint, initial);
    LinearLayout form = vertical(this);
    pad(form, 24, 18);
    form.addView(e);
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setView(form)
        .setNegativeButton("取消", null)
        .setPositiveButton("确定", (d, i) -> done.accept(e.getText().toString().trim()))
        .show();
  }

  private void choose(
      String title,
      List<JSONObject> values,
      java.util.function.Function<JSONObject, String> label,
      Consumer<JSONObject> selected) {
    String[] labels = new String[values.size()];
    for (int i = 0; i < labels.length; i++) labels[i] = label.apply(values.get(i));
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setItems(labels, (d, i) -> selected.accept(values.get(i)))
        .setNegativeButton("取消", null)
        .show();
  }

  private void hideKeyboard(View input) {
    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
        .hideSoftInputFromWindow(input.getWindowToken(), 0);
  }

  private void toast(String text) {
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
  }

  private static String join(JSONObject o, String... keys) {
    List<String> values = new ArrayList<>();
    for (String k : keys) if (!o.optString(k).isEmpty()) values.add(Catalog.clean(o.optString(k)));
    return String.join(" · ", values);
  }

  private static String formatTime(long ms) {
    return String.format(Locale.ROOT, "%d:%02d", ms / 60000, (ms / 1000) % 60);
  }

  private static boolean needsSnapshotData(String route) {
    return List.of(
            "site", "sourceSearch", "details", "channels", "discover", "discoverDetail")
        .contains(route);
  }

  private void restore(NavigationSnapshot snapshot) {
    if (snapshot.data() == null && needsSnapshotData(snapshot.route())) {
      page(
          "incomplete",
          snapshot.title(),
          snapshot.arguments(),
          () -> restore(snapshot));
      empty(
          "◷",
          "页面尚未加载完成",
          "没有可恢复的页面数据。可明确重新加载，或继续返回。",
          "重新加载",
          () -> openSnapshot(snapshot));
      return;
    }
    restoringSnapshot = true;
    openSnapshot(snapshot);
    int y = snapshot.scrollY();
    scroll.post(
        () -> {
          scroll.scrollTo(0, y);
          restoringSnapshot = false;
        });
  }

  @SuppressWarnings("unchecked")
  private void openSnapshot(NavigationSnapshot snapshot) {
    JSONObject a = snapshot.arguments();
    Object data = snapshot.data();
    switch (snapshot.route()) {
      case "home":
        home((JSONObject) data);
        break;
      case "sources":
        sources();
        break;
      case "site":
        site(
            Json.child(a, "source"),
            a.optString("category"),
            a.optInt("page", 1),
            Json.child(a, "filters"),
            (CatalogPages) data);
        break;
      case "sourceSearch":
        sourceSearch(
            Json.child(a, "site"), a.optString("query"), a.optInt("page", 1), (CatalogPages) data);
        break;
      case "details":
        details(Json.child(a, "site"), Json.child(a, "listed"), (JSONObject) data);
        break;
      case "library":
        library(a.optString("key"), a.optString("title"));
        break;
      case "live":
        live();
        break;
      case "channels":
        channels(Json.child(a, "source"), a.optString("selected"), (ChannelLoad) data);
        break;
      case "search":
        searchPage(a.optString("initial"), (SearchSnapshot) data);
        break;
      case "discover":
        discover(a.optString("feed"), a.optInt("page", 1), (JSONObject) data);
        break;
      case "discoverDetail":
        discoverDetail(Json.child(a, "item"), (JSONObject) data);
        break;
      case "music":
        music(a.optString("provider"), a.optString("query"), a.optInt("page", 1), a.optString("kind", "song"), (JSONObject) data);
        break;
      case "musicCollection":
        musicCollection(Json.child(a, "collection"), a.optInt("page", 1), (List<JSONObject>) data);
        break;
      case "downloads":
        downloads();
        break;
      case "settings":
        settings();
        break;
      default:
        throw new IllegalArgumentException("未知页面快照: " + snapshot.route());
    }
  }

  @Override
  public void onBackPressed() {
    NavigationSnapshot previous = back.pop();
    if (previous != null) {
      restore(previous);
      return;
    }
    if (!expanded) {
      nav.get(section).requestFocus();
      return;
    }
    if (section != 0) {
      select(0);
      return;
    }
    new AlertDialog.Builder(this)
        .setMessage("退出映匣？")
        .setPositiveButton("退出", (d, i) -> finish())
        .setNegativeButton("取消", null)
        .show();
  }
}
