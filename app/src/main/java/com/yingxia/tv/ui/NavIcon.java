package com.yingxia.tv.ui;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Resolution-independent outlines matching the original sidebar's symbol vocabulary. */
final class NavIcon extends Drawable {
  private final int item;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private int color = TvStyle.TEXT;

  NavIcon(int item) {
    this.item = item;
  }

  void color(int value) {
    color = value;
    invalidateSelf();
  }

  @Override
  public void draw(Canvas c) {
    int save = c.save();
    Rect b = getBounds();
    c.translate(b.left, b.top);
    c.scale(b.width() / 24f, b.height() / 24f);
    paint.setColor(color);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(1.8f);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
    switch (item) {
      case 0:
        path(c, 8, 5, 19, 12, 8, 19, 8, 5);
        break;
      case 1:
        c.drawCircle(12, 12, 9, paint);
        path(c, 15.5f, 8.5f, 13.5f, 13.5f, 8.5f, 15.5f, 10.5f, 10.5f, 15.5f, 8.5f);
        break;
      case 2:
        c.drawRoundRect(3, 6, 21, 19, 3, 3, paint);
        path(c, 8, 2, 12, 6, 16, 2);
        break;
      case 3:
        path(c, 9, 17, 9, 5, 20, 3, 20, 15);
        c.drawOval(3, 16, 9, 21, paint);
        c.drawOval(14, 14, 20, 19, paint);
        break;
      case 4:
        path(c, 12, 6, 12, 21);
        path(c, 12, 6, 8, 4, 2, 4, 2, 18, 8, 18, 12, 21, 16, 18, 22, 18, 22, 4, 16, 4, 12, 6);
        break;
      case 5:
        c.drawCircle(10, 10, 7, paint);
        path(c, 15, 15, 22, 22);
        break;
      case 6:
        for (int y = 3; y <= 14; y += 11)
          for (int x = 3; x <= 14; x += 11) c.drawRoundRect(x, y, x + 7, y + 7, 1.5f, 1.5f, paint);
        break;
      case 7:
        Path cloud = new Path();
        cloud.moveTo(6, 19);
        cloud.cubicTo(-1, 19, 0, 9, 7, 10);
        cloud.cubicTo(7, 0, 21, 1, 20, 11);
        cloud.cubicTo(26, 12, 23, 19, 19, 19);
        cloud.close();
        c.drawPath(cloud, paint);
        break;
      case 8:
        c.drawArc(3, 3, 21, 21, -150, 300, false, paint);
        path(c, 2, 3, 2, 9, 8, 9);
        path(c, 12, 7, 12, 12, 16, 14);
        break;
      case 9:
        Path heart = new Path();
        heart.moveTo(12, 21);
        heart.cubicTo(8, 17, 1, 12, 2, 7);
        heart.cubicTo(3, 1, 10, 2, 12, 6);
        heart.cubicTo(14, 2, 21, 1, 22, 7);
        heart.cubicTo(23, 12, 16, 17, 12, 21);
        c.drawPath(heart, paint);
        break;
      case 10:
        path(c, 12, 2, 12, 16);
        path(c, 6, 10, 12, 16, 18, 10);
        path(c, 3, 17, 3, 22, 21, 22, 21, 17);
        break;
      case 11:
        for (int y = 5; y <= 19; y += 7) {
          path(c, 2, y, 22, y);
          float x = y == 12 ? 15 : 8;
          c.drawCircle(x, y, 2.5f, paint);
        }
        break;
    }
    c.restoreToCount(save);
  }

  private void path(Canvas c, float... coordinates) {
    Path p = new Path();
    p.moveTo(coordinates[0], coordinates[1]);
    for (int i = 2; i < coordinates.length; i += 2) p.lineTo(coordinates[i], coordinates[i + 1]);
    c.drawPath(p, paint);
  }

  @Override
  public void setAlpha(int alpha) {
    paint.setAlpha(alpha);
  }

  @Override
  public void setColorFilter(ColorFilter f) {
    paint.setColorFilter(f);
  }

  @Override
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }
}
