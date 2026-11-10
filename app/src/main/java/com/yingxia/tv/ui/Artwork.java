package com.yingxia.tv.ui;

import android.graphics.*;
import android.os.*;
import android.util.LruCache;
import android.widget.ImageView;
import com.yingxia.tv.*;
import com.yingxia.tv.core.*;
import java.util.concurrent.*;

/** Small downsampled images keep 32-bit TVs comfortably below their heap limit. */
public final class Artwork {
  private static final LruCache<String, Bitmap> cache =
      new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
          return b.getAllocationByteCount();
        }
      };
  private static final ExecutorService pool =
      new ThreadPoolExecutor(
          2,
          2,
          30,
          TimeUnit.SECONDS,
          new LinkedBlockingQueue<>(150),
          r -> new Thread(r, "yingxia-art"),
          new ThreadPoolExecutor.DiscardPolicy());
  private static final Handler ui = new Handler(Looper.getMainLooper());

  public static void load(ImageView view, String address, int width, int height) {
    // The same anti-leech URL can legitimately change cookies/Referer between source sessions.
    String key = address + "@" + width;
    view.setTag(key);
    Bitmap hit = cache.get(key);
    if (hit != null) {
      view.setImageBitmap(hit);
      return;
    }
    int w = TvStyle.dp(view.getContext(), width), h = TvStyle.dp(view.getContext(), height);
    pool.execute(
        () -> {
          try {
            ArtworkRequest request = ArtworkRequest.parse(address, null);
            byte[] bytes =
                YingxiaApp.get()
                    .http
                    .get(
                        request.url, request.headers)
                    .bytes;
            Bitmap b = decode(bytes, w, h);
            if (b == null) return;
            cache.put(key, b);
            ui.post(
                () -> {
                  if (key.equals(view.getTag())) view.setImageBitmap(b);
                });
          } catch (Exception error) {
            android.util.Log.w("Yingxia.Artwork", "Cannot load artwork", error);
            ui.post(
                () -> {
                  if (key.equals(view.getTag()))
                    view.setImageResource(android.R.drawable.ic_menu_report_image);
                });
          }
        });
  }

  public static Bitmap decode(byte[] bytes, int w, int h) {
    BitmapFactory.Options o = new BitmapFactory.Options();
    o.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
    o.inSampleSize = 1;
    while (o.outWidth / (o.inSampleSize * 2) >= w && o.outHeight / (o.inSampleSize * 2) >= h)
      o.inSampleSize *= 2;
    o.inJustDecodeBounds = false;
    o.inPreferredConfig = Bitmap.Config.RGB_565;
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
  }

  public static void clear() {
    cache.evictAll();
  }
}
