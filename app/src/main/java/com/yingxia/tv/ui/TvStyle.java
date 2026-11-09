package com.yingxia.tv.ui;

import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

public final class TvStyle {
  public static final int BACKGROUND = Color.rgb(5, 9, 23),
      SURFACE = Color.rgb(26, 31, 45),
      TEXT = Color.rgb(245, 246, 250),
      SECONDARY = Color.rgb(166, 174, 192),
      CYAN = Color.rgb(77, 199, 255);

  public static int dp(Context c, float n) {
    return Math.round(n * c.getResources().getDisplayMetrics().density);
  }

  public static GradientDrawable rounded(int color, float radius, Context c) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(c, radius));
    return d;
  }

  public static GradientDrawable border(int color, Context c) {
    GradientDrawable d = rounded(color, 12, c);
    d.setStroke(dp(c, 2), Color.WHITE);
    return d;
  }

  public static TextView text(Context c, String value, float size, int color) {
    TextView t = new TextView(c);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setFontFeatureSettings("kern");
    return t;
  }

  public static LinearLayout vertical(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  public static LinearLayout horizontal(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  public static void pad(View v, int x, int y) {
    int dx = dp(v.getContext(), x), dy = dp(v.getContext(), y);
    v.setPadding(dx, dy, dx, dy);
  }

  public static TextView button(Context c, String name, Runnable action) {
    TextView t = text(c, name, 16, TEXT);
    t.setGravity(Gravity.CENTER);
    t.setMinHeight(dp(c, 44));
    pad(t, 18, 10);
    t.setSingleLine(true);
    t.setFocusable(true);
    t.setClickable(true);
    t.setContentDescription(name);
    t.setBackground(rounded(SURFACE, 24, c));
    t.setOnClickListener(v -> action.run());
    t.setOnFocusChangeListener(
        (v, focused) -> {
          t.setTextColor(focused ? BACKGROUND : TEXT);
          t.setBackground(rounded(focused ? Color.WHITE : SURFACE, 24, c));
          t.animate()
              .scaleX(focused ? 1.04f : 1)
              .scaleY(focused ? 1.04f : 1)
              .setDuration(140)
              .start();
        });
    return t;
  }

  public static void gap(LinearLayout parent, int h) {
    View v = new View(parent.getContext());
    parent.addView(v, new LinearLayout.LayoutParams(1, dp(parent.getContext(), h)));
  }

  public static void spaced(LinearLayout parent, View v, int right) {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
    lp.rightMargin = dp(parent.getContext(), right);
    parent.addView(v, lp);
  }

  public static HorizontalScrollView rail(Context c, LinearLayout content) {
    HorizontalScrollView scroll = new HorizontalScrollView(c);
    scroll.setHorizontalScrollBarEnabled(false);
    scroll.setClipChildren(false);
    scroll.setClipToPadding(false);
    scroll.addView(content);
    scroll.setFillViewport(false);
    return scroll;
  }

  public static View card(
      Context c,
      String title,
      String subtitle,
      String poster,
      int width,
      int height,
      Runnable action) {
    LinearLayout card = vertical(c);
    card.setFocusable(true);
    card.setClickable(true);
    card.setContentDescription(title);
    card.setClipChildren(false);
    pad(card, 4, 6);
    ImageView art = new ImageView(c);
    art.setScaleType(ImageView.ScaleType.CENTER_CROP);
    art.setBackground(rounded(SURFACE, 10, c));
    art.setClipToOutline(true);
    art.setImageDrawable(monogram(c, title));
    FrameLayout picture = new FrameLayout(c);
    picture.addView(art, new FrameLayout.LayoutParams(-1, -1));
    View outline = new View(c);
    GradientDrawable stroke = rounded(Color.TRANSPARENT, 10, c);
    stroke.setStroke(dp(c, 2), Color.WHITE);
    outline.setBackground(stroke);
    outline.setVisibility(View.INVISIBLE);
    picture.addView(outline, new FrameLayout.LayoutParams(-1, -1));
    card.addView(picture, new LinearLayout.LayoutParams(dp(c, width), dp(c, height)));
    if (poster != null && !poster.isEmpty()) Artwork.load(art, poster, width, height);
    TextView label = text(c, title, 15, TEXT);
    label.setSingleLine(true);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, width), -2);
    lp.topMargin = dp(c, 8);
    card.addView(label, lp);
    if (subtitle != null && !subtitle.isEmpty()) {
      TextView sub = text(c, subtitle, 12, SECONDARY);
      sub.setSingleLine(true);
      card.addView(sub, new LinearLayout.LayoutParams(dp(c, width), -2));
    }
    card.setOnClickListener(v -> action.run());
    card.setOnFocusChangeListener(
        (v, focus) -> {
          outline.setVisibility(focus ? View.VISIBLE : View.INVISIBLE);
          card.animate()
              .scaleX(focus ? 1.045f : 1)
              .scaleY(focus ? 1.045f : 1)
              .setDuration(150)
              .start();
          label.setTypeface(null, focus ? Typeface.BOLD : Typeface.NORMAL);
          if (focus) card.setElevation(dp(c, 8));
          else card.setElevation(0);
        });
    return card;
  }

  public static Drawable monogram(Context c, String text) {
    return new Drawable() {
      final Paint p = new Paint(3);

      @Override
      public void draw(Canvas canvas) {
        Rect r = getBounds();
        int[] palette = {0xff28465b, 0xff44496c, 0xff285a5d, 0xff725347, 0xff613e58};
        int color = palette[Math.floorMod(text.hashCode(), palette.length)];
        p.setShader(
            new LinearGradient(
                0, 0, r.width(), r.height(), color, 0xff151c2e, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(new RectF(r), dp(c, 10), dp(c, 10), p);
        p.setShader(null);
        p.setColor(0xcce2ebf5);
        p.setTextSize(Math.min(r.width(), r.height()) * .28f);
        p.setTextAlign(Paint.Align.CENTER);
        String name =
            text.isEmpty()
                ? "映"
                : text.substring(
                    0,
                    text.offsetByCodePoints(0, Math.min(2, text.codePointCount(0, text.length()))));
        canvas.drawText(name, r.centerX(), r.centerY() - (p.ascent() + p.descent()) / 2, p);
      }

      @Override
      public void setAlpha(int a) {}

      @Override
      public void setColorFilter(ColorFilter cf) {}

      @Override
      public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
      }
    };
  }
}
