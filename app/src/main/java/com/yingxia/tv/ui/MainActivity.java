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
public final class MainActivity extends Activity {
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
  private final Deque<Runnable> back = new ArrayDeque<>();
  private Runnable route;
  private final List<TextView> nav = new ArrayList<>();
  private boolean expanded = false;
  private HandoffServer handoff;
  private static final String[] TITLES = {
    "立即观看", "发现", "直播", "音乐", "漫画", "全局搜索", "来源", "云端文件", "播放历史", "收藏", "下载", "设置"
  };
  private static final String[] ICONS = {
    "▶", "◈", "◉", "♫", "▤", "⌕", "▦", "▱", "◷", "♡", "↓", "☷"
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
  }

  @Override
  protected void onPause() {
    super.onPause();
    if (handoff != null) handoff.close();
  }

  @Override
  protected void onDestroy() {
    generation++;
    if (current() == this) active.clear();
    super.onDestroy();
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
        discover("featured", 1);
        break;
      case 2:
        live();
        break;
      case 3:
        music("netease", "", 1);
        break;
      case 4:
        manga();
        break;
      case 5:
        searchPage("");
        break;
      case 6:
        sources();
        break;
      case 7:
        cloud();
        break;
      case 8:
        library("history", "播放历史");
        break;
      case 9:
        library("favorites", "收藏");
        break;
      case 10:
        downloads();
        break;
      default:
        settings();
    }
  }

  private void push(Runnable next) {
    if (route != null) back.push(route);
    next.run();
  }

  private int page(String title, Runnable again) {
    generation++;
    route = again;
    body.removeAllViews();
    scroll.scrollTo(0, 0);
    heading = text(this, title, 30, TEXT);
    heading.setTypeface(null, Typeface.BOLD);
    header = horizontal(this);
    header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
    body.addView(header);
    gap(body, 22);
    return generation;
  }

  private <T> void async(int token, Callable<T> work, Consumer<T> done) {
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
                  if (!isFinishing() && generation == token) error(Json.message(e));
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
        v -> push(() -> details(site, v)));
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
    int token = page("立即观看", this::home);
    List<JSONObject> sites = repo.sites();
    List<JSONObject> histories = Json.objects(repo.list("history"));
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
      sectionTitle("置顶来源");
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
                      "选择推荐来源",
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
      loading();
      async(
          token,
          () -> app.engine.call(chosen, new JSONObject()),
          answer -> {
            loaded();
            categoryChips(chosen, answer);
            recommendationRail(Catalog.videos(answer), chosen);
            if (Catalog.videos(answer).isEmpty())
              body.addView(
                  button(
                      this,
                      "打开 " + chosen.optString("name"),
                      () -> push(() -> site(chosen, "", 1, new JSONObject()))));
          });
    }
    if (repo.list("favorites").length() > 0) {
      sectionTitle("收藏");
      historyRail(Json.objects(repo.list("favorites")));
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
              () -> push(() -> details(site, video))),
          18);
    body.addView(rail(this, row));
  }

  private void sourceTiles(List<JSONObject> sites) {
    tiles(
        sites,
        s -> s.optString("name"),
        s -> s.optString("subscription") + " · " + runtimeName(s),
        s -> push(() -> site(s, "", 1, new JSONObject())),
        this::sourceMenu);
  }

  private String runtimeName(JSONObject s) {
    String api = s.optString("api");
    return s.optInt("type") == 0
        ? "XML"
        : s.optInt("type") == 1 || s.optInt("type") == 4
            ? "JSON"
            : api.startsWith("csp_") ? "JAR" : api.contains(".py") ? "Python" : "JavaScript";
  }

  private void sources() {
    page("来源", this::sources);
    List<JSONObject> sites = repo.sites();
    if (sites.isEmpty()) {
      empty("▦", "连接点播订阅", "在设置中添加 TVBox 订阅，来源会显示在这里。", "添加订阅", this::importDialog);
      return;
    }
    TextView info = text(this, sites.size() + " 个来源 · 长按确定键可置顶或隐藏", 14, SECONDARY);
    body.addView(info);
    gap(body, 18);
    sourceTiles(sites);
  }

  private void sourceMenu(JSONObject s) {
    boolean pin = repo.settings.getBoolean("pin." + s.optString("id"), false);
    new AlertDialog.Builder(this)
        .setTitle(s.optString("name"))
        .setItems(
            new String[] {pin ? "取消置顶" : "置顶来源", "隐藏来源"},
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
    int token = page(source.optString("name"), () -> site(source, category, pg, filters));
    LinearLayout row = actions();
    spaced(
        row,
        button(
            this,
            "⌕  搜索此来源",
            () ->
                input(
                    "搜索 " + source.optString("name"),
                    "关键词",
                    "",
                    q -> push(() -> sourceSearch(source, q, 1)))),
        10);
    spaced(row, button(this, "刷新", () -> site(source, category, pg, filters)), 10);
    loading();
    JSONObject params =
        category.isEmpty()
            ? new JSONObject()
            : Json.obj("t", category, "pg", "" + pg, "filters", filters.toString());
    async(
        token,
        () -> {
          JSONObject answer = app.engine.call(source, params);
          if (!category.isEmpty()) {
            JSONObject home = app.engine.call(source, new JSONObject());
            answer.put("class", home.opt("class")).put("filters", home.opt("filters"));
          }
          return answer;
        },
        answer -> {
          loaded();
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
                        } catch (Exception ignored) {
                        }
                        site(source, category, 1, next);
                      }),
                  8);
          }
          List<JSONObject> videos = Catalog.videos(answer);
          if (videos.isEmpty()) empty("▦", "暂无内容", "尝试其他分类或刷新此来源。", "重试", route);
          else videoGrid(videos, source);
          pagination(
              pg,
              answer.optInt("pagecount", videos.isEmpty() ? pg : pg + 1),
              p -> site(source, category, p, filters));
        });
  }

  private void sourceSearch(JSONObject site, String query, int pg) {
    int token = page(site.optString("name") + " · " + query, () -> sourceSearch(site, query, pg));
    loading();
    async(
        token,
        () -> app.engine.call(site, Json.obj("wd", query, "pg", "" + pg)),
        answer -> {
          loaded();
          List<JSONObject> videos = Catalog.videos(answer);
          videoGrid(videos, site);
          if (videos.isEmpty())
            empty(
                "⌕",
                "没有匹配的内容",
                "换一个关键词再试。",
                "重新搜索",
                () -> input("搜索", "关键词", query, q -> sourceSearch(site, q, 1)));
          pagination(
              pg,
              answer.optInt("pagecount", videos.isEmpty() ? pg : pg + 1),
              p -> sourceSearch(site, query, p));
        });
  }

  private void pagination(int pg, int total, Consumer<Integer> go) {
    LinearLayout row = actions();
    if (pg > 1) spaced(row, button(this, "上一页", () -> go.accept(pg - 1)), 10);
    spaced(row, text(this, "第 " + pg + " 页", 15, SECONDARY), 12);
    if (pg < total) spaced(row, button(this, "下一页", () -> go.accept(pg + 1)), 10);
  }

  private void details(JSONObject site, JSONObject listed) {
    int token = page("详情", () -> details(site, listed));
    loading();
    async(
        token,
        () -> {
          if (site.length() == 0 || listed.optString("vod_id").isEmpty()) return listed;
          JSONObject r = app.engine.call(site, Json.obj("ids", listed.optString("vod_id")));
          List<JSONObject> list = Catalog.videos(r);
          if (list.isEmpty()) throw new IOException("此来源没有返回影片详情");
          JSONObject v = list.get(0);
          if (v.optString("vod_pic").isEmpty()) v.put("vod_pic", listed.optString("vod_pic"));
          return v;
        },
        video -> {
          loaded();
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
              body.addView(button(this, "打开来源设置", () -> toast("请使用来源提供的登录入口")));
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
        });
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
          } catch (Exception ignored) {
          }
          PlayerActivity.open(this, play);
        });
  }

  private void resume(JSONObject saved) {
    if (saved.has("cloudServer")) {
      int token = generation;
      async(
          token,
          () -> app.cloud.stream(Json.child(saved, "cloudServer"), Json.child(saved, "cloudFile")),
          play -> {
            try {
              play.put("position", saved.optLong("position"));
            } catch (Exception ignored) {
            }
            PlayerActivity.open(this, play);
          });
    } else if (saved.has("site") && Json.child(saved, "site").length() > 0)
      async(generation, () -> app.engine.resume(saved), play -> PlayerActivity.open(this, play));
    else PlayerActivity.open(this, saved);
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
                if (item.has("url")) resume(item);
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
    page(title, () -> library(key, title));
    List<JSONObject> entries = Json.objects(repo.list(key));
    if (entries.isEmpty()) {
      empty(
          key.equals("favorites") ? "♡" : "◷",
          key.equals("favorites") ? "收藏喜欢的内容" : "还没有播放记录",
          key.equals("favorites") ? "在详情页收藏影片，在这里随时找到。" : "你观看的内容和进度会保存在这里。",
          "浏览来源",
          () -> select(6));
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
          if (s.has("url")) resume(s);
          else push(() -> details(Json.child(s, "site"), Json.child(s, "video")));
        });
  }

  private void libraryMenu(JSONObject item) {
    new AlertDialog.Builder(this)
        .setTitle(item.optString("name"))
        .setItems(
            new String[] {"打开", "从历史中移除", "收藏 / 取消收藏"},
            (d, i) -> {
              if (i == 0) resume(item);
              else if (i == 1) {
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
    page("直播", this::live);
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

  private List<JSONObject> loadChannels(JSONObject source) throws Exception {
    List<JSONObject> out = new ArrayList<>();
    Map<String, String> headers = Json.headers(source.optJSONObject("header"));
    if (source.has("ua")) headers.put("User-Agent", source.optString("ua"));
    for (JSONObject group : Json.objects(Json.array(source.opt("channels")))) {
      if (group.has("urls")) {
        JSONArray urls = Json.array(group.opt("urls"));
        if (urls.length() > 0)
          out.add(
              Json.obj(
                  "id",
                  group.optString("name") + urls.optString(0),
                  "name",
                  group.optString("name"),
                  "url",
                  urls.optString(0),
                  "group",
                  source.optString("name"),
                  "header",
                  new JSONObject(headers),
                  "live",
                  true));
      }
    }
    if (!out.isEmpty()) return out;
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
    out = PlaylistParser.parse(result.text(), result.url, headers);
    if (out.isEmpty()) throw new IOException("播放列表中没有找到可用频道");
    return out;
  }

  private void channels(JSONObject source, String selected) {
    int token = page(source.optString("name", "直播"), () -> channels(source, selected));
    loading();
    async(
        token,
        () -> loadChannels(source),
        items -> {
          loaded();
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
                    () -> channels(source, group)),
                10);
          List<JSONObject> shown = new ArrayList<>();
          for (JSONObject c : items)
            if (selected.isEmpty() || selected.equals(c.optString("group"))) shown.add(c);
          TextView count = text(this, shown.size() + " 个频道", 14, SECONDARY);
          body.addView(count);
          gap(body, 16);
          tiles(
              shown,
              c -> c.optString("name"),
              c -> c.optString("group"),
              channel -> {
                JSONObject play = Json.copy(channel);
                try {
                  play.put("episodes", new JSONArray(items))
                      .put("poster", channel.optString("logo"));
                } catch (Exception ignored) {
                }
                PlayerActivity.open(this, play);
              },
              c -> {
                repo.favorite(c);
                toast("已更新收藏");
              });
        });
  }

  private void searchPage(String initial) {
    int token = page("全局搜索", () -> searchPage(initial));
    LinearLayout entry = horizontal(this);
    EditText input = edit("搜索全部来源", initial);
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
    if (initial.isEmpty()) empty("⌕", "一次搜索，汇集所有来源", "输入影片、剧集或演员名称，搜索结果会陆续显示。", null, null);
    else performSearch(initial);
  }

  private void performSearch(String query) {
    if (query.isEmpty()) return;
    int token = ++generation;
    while (body.getChildCount() > 4) body.removeViewAt(4);
    List<JSONObject> sites = repo.sites();
    if (sites.isEmpty()) {
      empty("⌕", "先添加一个订阅", "添加点播来源后即可搜索。", "添加订阅", this::importDialog);
      return;
    }
    TextView status = text(this, "正在搜索 " + sites.size() + " 个来源…", 14, SECONDARY);
    body.addView(status);
    gap(body, 16);
    final int[] complete = {0}, failures = {0};
    for (JSONObject site : sites) {
      app.io.execute(
          () -> {
            try {
              List<JSONObject> videos =
                  Catalog.videos(app.engine.call(site, Json.obj("wd", query, "pg", "1")));
              runOnUiThread(
                  () -> {
                    if (generation != token) return;
                    complete[0]++;
                    status.setText(
                        "已搜索 "
                            + complete[0]
                            + " / "
                            + sites.size()
                            + " 个来源"
                            + (failures[0] > 0 ? " · " + failures[0] + " 个暂不可用" : ""));
                    if (!videos.isEmpty()) {
                      sectionTitle(site.optString("name"));
                      LinearLayout row = horizontal(this);
                      for (JSONObject video : videos.subList(0, Math.min(12, videos.size())))
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
                  });
            } catch (Throwable e) {
              runOnUiThread(
                  () -> {
                    if (generation != token) return;
                    complete[0]++;
                    failures[0]++;
                    status.setText(
                        "已搜索 "
                            + complete[0]
                            + " / "
                            + sites.size()
                            + " 个来源 · "
                            + failures[0]
                            + " 个暂不可用");
                  });
            }
          });
    }
  }

  private void discover(String feed, int pg) {
    int token = page("发现", () -> discover(feed, pg));
    LinearLayout filters = actions();
    String[][] options = {
      {"featured", "精选"},
      {"trending", "热门"},
      {"movies", "电影"},
      {"television", "剧集"},
      {"animation", "动漫"},
      {"chinese", "华语"},
      {"variety", "综艺"},
      {"upcoming", "即将上映"},
      {"topMovies", "高分电影"}
    };
    for (String[] option : options)
      spaced(
          filters,
          button(
              this, (option[0].equals(feed) ? "✓ " : "") + option[1], () -> discover(option[0], 1)),
          10);
    loading();
    async(
        token,
        () ->
            app.http.json(
                Http.query(
                    "https://yingxia-tmdb.getmegaportal.com/v1/catalog",
                    Http.params("feed", feed, "page", "" + pg, "language", "zh-CN"))),
        answer -> {
          loaded();
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
        });
  }

  private void discoverDetail(JSONObject item) {
    page(item.optString("name"), () -> discoverDetail(item));
    LinearLayout hero = horizontal(this);
    hero.setGravity(Gravity.TOP);
    ImageView image = new ImageView(this);
    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    hero.addView(image, new LinearLayout.LayoutParams(dp(this, 180), dp(this, 270)));
    Artwork.load(image, item.optString("poster"), 180, 270);
    LinearLayout info = vertical(this);
    pad(info, 28, 8);
    info.addView(text(this, item.optString("name"), 34, TEXT));
    gap(info, 12);
    info.addView(
        text(
            this,
            item.optString("year")
                + " · ★ "
                + String.format(Locale.ROOT, "%.1f", item.optDouble("rating")),
            17,
            SECONDARY));
    gap(info, 18);
    info.addView(text(this, item.optString("overview"), 16, SECONDARY));
    gap(info, 24);
    info.addView(button(this, "⌕  在全部来源中查找", () -> push(() -> searchPage(item.optString("name")))));
    hero.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
    body.addView(hero);
  }

  private void music(String provider, String query, int pg) {
    int token = page("音乐", () -> music(provider, query, pg));
    LinearLayout platforms = actions();
    String[][] options = {
      {"netease", "网易云音乐"}, {"tencent", "QQ 音乐"}, {"kuwo", "酷我音乐"}, {"kugou", "酷狗音乐"}
    };
    for (String[] p : options)
      spaced(
          platforms,
          button(this, (p[0].equals(provider) ? "✓ " : "") + p[1], () -> music(p[0], query, 1)),
          10);
    LinearLayout row = horizontal(this);
    EditText input = edit("搜索歌曲、歌手、专辑", query);
    row.addView(input, new LinearLayout.LayoutParams(0, dp(this, 52), 1));
    spaced(
        row,
        button(
            this,
            "搜索",
            () -> {
              hideKeyboard(input);
              music(provider, input.getText().toString().trim(), 1);
            }),
        12);
    body.addView(row);
    gap(body, 18);
    if (query.isEmpty()) {
      List<JSONObject> recent = new ArrayList<>();
      for (JSONObject s : Json.objects(repo.list("history")))
        if (s.optBoolean("music")) recent.add(s);
      if (!recent.isEmpty()) {
        sectionTitle("继续播放");
        tiles(
            recent,
            s -> s.optString("name"),
            s -> s.optString("artist"),
            s -> PlayerActivity.open(this, s),
            null);
      } else empty("♫", "让音乐充满客厅", "搜索你喜欢的歌曲，选择一个音源开始播放。", null, null);
      return;
    }
    loading();
    async(
        token,
        () -> new MusicClient().search(query, provider, pg),
        songs -> {
          loaded();
          if (songs.isEmpty()) {
            empty("♫", "没有找到歌曲", "换一个关键词或音乐平台。", null, null);
            return;
          }
          tiles(
              songs,
              s -> s.optString("name"),
              s -> s.optString("artist") + " · " + s.optString("album"),
              song -> {
                toast("正在准备音源…");
                async(
                    generation,
                    () -> new MusicClient().play(song),
                    play -> PlayerActivity.open(this, play));
              },
              song -> {
                repo.favorite(song);
                toast("已更新收藏");
              });
          pagination(pg, pg + 1, p -> music(provider, query, p));
        });
  }

  private void cloud() {
    page("云端文件", this::cloud);
    LinearLayout row = actions();
    spaced(row, button(this, "＋  添加服务器", this::serverDialog), 12);
    List<JSONObject> servers = Json.objects(repo.list("servers"));
    if (servers.isEmpty()) {
      empty(
          "▱",
          "你的媒体，触手可及",
          "连接 AList、OpenList、Emby、Jellyfin 或 WebDAV，直接浏览和播放服务器上的内容。",
          "添加服务器",
          this::serverDialog);
      return;
    }
    tiles(
        servers,
        s -> s.optString("name"),
        s -> s.optString("kind"),
        s -> push(() -> cloudFolder(s, s.optString("root", "/"))),
        s ->
            new AlertDialog.Builder(this)
                .setTitle(s.optString("name"))
                .setItems(
                    new String[] {"编辑", "移除"},
                    (d, i) -> {
                      if (i == 1) {
                        repo.remove("servers", s.optString("id"));
                        cloud();
                      } else serverDialog(s);
                    })
                .show());
  }

  private void serverDialog() {
    serverDialog(new JSONObject());
  }

  private void serverDialog(JSONObject old) {
    String[] kinds = {"alist", "emby", "webdav"},
        labels = {"AList / OpenList", "Emby / Jellyfin", "WebDAV"};
    LinearLayout form = vertical(this);
    pad(form, 24, 12);
    Spinner kind = new Spinner(this);
    kind.setAdapter(
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
    kind.setSelection(Math.max(0, Arrays.asList(kinds).indexOf(old.optString("kind", "alist"))));
    form.addView(kind);
    EditText name = edit("服务器名称", old.optString("name")),
        url = edit("服务器地址，例如 http://192.168.1.2:5244", old.optString("address")),
        user = edit("用户名（匿名访问可留空）", old.optString("username")),
        pass = edit("密码", old.optString("password")),
        path = edit("起始目录", old.optString("root", "/"));
    pass.setInputType(129);
    for (EditText f : List.of(name, url, user, pass, path)) form.addView(f);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("媒体服务器")
            .setView(form)
            .setNegativeButton("取消", null)
            .setPositiveButton("连接", null)
            .create();
    dialog.setOnShowListener(
        x ->
            dialog
                .getButton(-1)
                .setOnClickListener(
                    v -> {
                      try {
                        String address =
                            ConfigCodec.address(url.getText().toString(), null)
                                .replaceAll("/+$", "");
                        JSONObject server =
                            Json.obj(
                                "id",
                                old.optString("id", UUID.randomUUID().toString()),
                                "name",
                                name.getText().toString().isEmpty()
                                    ? Uri.parse(address).getHost()
                                    : name.getText().toString(),
                                "address",
                                address,
                                "kind",
                                kinds[kind.getSelectedItemPosition()],
                                "username",
                                user.getText().toString(),
                                "password",
                                pass.getText().toString(),
                                "root",
                                path.getText().toString().isEmpty()
                                    ? "/"
                                    : path.getText().toString());
                        toast("正在连接…");
                        async(
                            generation,
                            () -> {
                              app.cloud.list(server, server.optString("root"));
                              repo.upsert("servers", server, "id", false, 100);
                              return server;
                            },
                            s -> {
                              dialog.dismiss();
                              cloud();
                            });
                      } catch (Exception e) {
                        url.setError(Json.message(e));
                      }
                    }));
    dialog.show();
  }

  private void cloudFolder(JSONObject server, String path) {
    int token = page(server.optString("name"), () -> cloudFolder(server, path));
    body.addView(text(this, path, 14, SECONDARY));
    gap(body, 18);
    loading();
    async(
        token,
        () -> app.cloud.list(server, path),
        files -> {
          loaded();
          if (files.isEmpty()) {
            empty("▱", "这个目录是空的", "选择其他目录，或向服务器添加媒体文件。", null, null);
            return;
          }
          tiles(
              files,
              s -> (s.optBoolean("folder") ? "▱  " : "▶  ") + s.optString("name"),
              s ->
                  s.optBoolean("folder")
                      ? "文件夹"
                      : s.optLong("size") > 0
                          ? String.format(Locale.ROOT, "%.1f MB", s.optLong("size") / 1048576.0)
                          : "媒体文件",
              file -> {
                if (file.optBoolean("folder"))
                  push(() -> cloudFolder(server, file.optString("id")));
                else
                  async(
                      generation,
                      () -> app.cloud.stream(server, file),
                      play -> PlayerActivity.open(this, play));
              },
              null);
        });
  }

  private void manga() {
    page("漫画", this::manga);
    LinearLayout row = actions();
    spaced(
        row,
        button(
            this,
            "＋  添加漫画源",
            () ->
                input(
                    "添加漫画源",
                    "Venera JavaScript 源地址",
                    "",
                    url -> {
                      toast("正在安装漫画源…");
                      async(
                          generation,
                          () -> app.manga.install(ConfigCodec.address(url, null)),
                          info -> {
                            toast("已添加 " + info.optString("name"));
                            manga();
                          });
                    })),
        12);
    List<JSONObject> sources = Json.objects(repo.list("mangaSources"));
    if (sources.isEmpty()) {
      empty(
          "▤",
          "把喜欢的故事带到大屏",
          "添加 Venera 漫画源，浏览、搜索并阅读漫画。",
          "添加漫画源",
          () ->
              input(
                  "添加漫画源",
                  "JavaScript 源地址",
                  "",
                  url ->
                      async(
                          generation,
                          () -> app.manga.install(ConfigCodec.address(url, null)),
                          info -> manga())));
      return;
    }
    tiles(
        sources,
        s -> s.optString("name"),
        s -> "浏览漫画",
        s -> push(() -> mangaSource(s, 0, 1, "")),
        s ->
            new AlertDialog.Builder(this)
                .setTitle("移除 " + s.optString("name") + "？")
                .setPositiveButton(
                    "移除",
                    (d, i) -> {
                      repo.remove("mangaSources", s.optString("id"));
                      manga();
                    })
                .setNegativeButton("取消", null)
                .show());
  }

  private void mangaSource(JSONObject source, int explore, int pg, String query) {
    int token = page(source.optString("name"), () -> mangaSource(source, explore, pg, query));
    JSONObject info = Json.child(source, "info");
    LinearLayout row = actions();
    for (int i = 0; i < Json.array(info.opt("explore")).length(); i++) {
      final int index = i;
      JSONObject page = Json.array(info.opt("explore")).optJSONObject(i);
      if (page != null)
        spaced(
            row,
            button(this, page.optString("title"), () -> mangaSource(source, index, 1, "")),
            10);
    }
    spaced(
        row,
        button(
            this, "搜索", () -> input("搜索漫画", "关键词", query, q -> mangaSource(source, explore, 1, q))),
        10);
    loading();
    async(
        token,
        () -> {
          String method;
          JSONArray args = new JSONArray();
          if (query.isEmpty()) {
            method = "explore." + explore + ".load";
            args.put(pg);
          } else {
            method = "search.load";
            args.put(query).put(new JSONArray()).put(pg);
          }
          Object answer = app.manga.call(source.optString("id"), method, args);
          return answer instanceof JSONObject ? (JSONObject) answer : Json.obj("comics", answer);
        },
        answer -> {
          loaded();
          List<JSONObject> comics = Json.objects(Json.array(answer.opt("comics")));
          if (answer.has("data"))
            for (int i = 0; i < Json.array(answer.opt("data")).length(); i++) {
              Object part = Json.array(answer.opt("data")).opt(i);
              if (part instanceof JSONArray) comics.addAll(Json.objects((JSONArray) part));
              else if (part instanceof JSONObject)
                comics.addAll(Json.objects(Json.array(((JSONObject) part).opt("comics"))));
            }
          if (comics.isEmpty()) empty("▤", "暂无漫画", "选择其他分类或搜索。", null, null);
          else
            grid(
                comics,
                c -> c.optString("title"),
                c -> c.optString("subtitle", c.optString("subTitle")),
                c -> c.optString("cover"),
                c -> push(() -> mangaDetails(source, c)));
          pagination(pg, answer.optInt("maxPage", pg), p -> mangaSource(source, explore, p, query));
        });
  }

  private void mangaDetails(JSONObject source, JSONObject comic) {
    int token = page(comic.optString("title"), () -> mangaDetails(source, comic));
    loading();
    async(
        token,
        () -> app.manga.details(source.optString("id"), comic.optString("id")),
        details -> {
          loaded();
          TextView synopsis =
              text(this, Catalog.clean(details.optString("description")), 16, SECONDARY);
          body.addView(synopsis);
          gap(body, 22);
          JSONObject chapters = Json.child(details, "chapters");
          if (chapters.length() == 0) {
            body.addView(
                button(this, "开始阅读", () -> ReaderActivity.open(this, source, comic, null)));
            return;
          }
          for (String key : Json.keys(chapters)) {
            Object value = chapters.opt(key);
            if (value instanceof JSONObject) {
              sectionTitle(key);
              LinearLayout row = actions();
              for (String id : Json.keys((JSONObject) value))
                spaced(
                    row,
                    button(
                        this,
                        ((JSONObject) value).optString(id),
                        () -> ReaderActivity.open(this, source, comic, id)),
                    10);
            } else
              body.addView(
                  button(
                      this,
                      String.valueOf(value),
                      () -> ReaderActivity.open(this, source, comic, key)));
          }
        });
  }

  private void downloads() {
    page("下载", this::downloads);
    List<JSONObject> items = Json.objects(repo.list("downloads"));
    if (items.isEmpty()) {
      empty("↓", "随时离线观看", "在选集页长按确定键下载一集，或从播放器保存当前内容。", "浏览来源", () -> select(6));
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
    page("设置", this::settings);
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
                                  .setMessage("移除此订阅及其来源？")
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
    sectionTitle("资料与存储");
    LinearLayout storage = actions();
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
            "恢复隐藏来源",
            () -> {
              SharedPreferences.Editor edit = repo.settings.edit();
              for (String key : repo.settings.getAll().keySet())
                if (key.startsWith("hidden.")) edit.remove(key);
              edit.apply();
              toast("已恢复全部来源");
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
    body.addView(text(this, "所有来源，一方大屏。", 16, SECONDARY));
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
    ImageView qr = new ImageView(this);
    try {
      com.google.zxing.common.BitMatrix matrix =
          new com.google.zxing.MultiFormatWriter()
              .encode(address, com.google.zxing.BarcodeFormat.QR_CODE, 320, 320);
      android.graphics.Bitmap bitmap =
          android.graphics.Bitmap.createBitmap(320, 320, android.graphics.Bitmap.Config.RGB_565);
      for (int y = 0; y < 320; y++)
        for (int x = 0; x < 320; x++)
          bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
      qr.setImageBitmap(bitmap);
    } catch (Exception ignored) {
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
    Uri uri = intent.getData();
    if (uri != null && "yingxia".equals(uri.getScheme())) {
      String url = uri.getQueryParameter("url");
      if (url != null)
        input(
            "导入订阅",
            "订阅地址",
            url,
            address -> {
              async(
                  generation,
                  () -> {
                    Http.Result r = app.http.get(ConfigCodec.address(address, null));
                    repo.importConfig(r.bytes, r.url, null);
                    return true;
                  },
                  x -> select(0));
            });
    }
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

  @Override
  public void onBackPressed() {
    if (!back.isEmpty()) {
      Runnable go = back.pop();
      go.run();
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
