package com.yingxia.tv.ui;

import static com.yingxia.tv.ui.TvStyle.*;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.yingxia.tv.*;
import com.yingxia.tv.core.*;
import org.json.*;

/** One page in memory; remote left/right turn pages, back returns to chapters. */
public final class ReaderActivity extends Activity {
  private JSONObject source, comic;
  private String chapter;
  private JSONArray pages = new JSONArray();
  private int page = 0, request = 0;
  private ImageView image;
  private TextView status;

  public static void open(Context c, JSONObject source, JSONObject comic, String chapter) {
    c.startActivity(
        new Intent(c, ReaderActivity.class)
            .putExtra("source", source.toString())
            .putExtra("comic", comic.toString())
            .putExtra("chapter", chapter));
  }

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow().addFlags(128);
    getWindow().getDecorView().setSystemUiVisibility(5894);
    source = Json.object(getIntent().getStringExtra("source"));
    comic = Json.object(getIntent().getStringExtra("comic"));
    chapter = getIntent().getStringExtra("chapter");
    page =
        YingxiaApp.get()
            .repository
            .settings
            .getInt(
                "read." + source.optString("id") + "@" + comic.optString("id") + "@" + chapter, 0);
    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(Color.BLACK);
    setContentView(root);
    image = new ImageView(this);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    root.addView(image, new FrameLayout.LayoutParams(-1, -1));
    status = text(this, "正在加载章节…", 15, TEXT);
    pad(status, 20, 10);
    status.setBackgroundColor(0xaa050917);
    root.addView(
        status, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
    image.setFocusable(true);
    image.requestFocus();
    YingxiaApp.get()
        .io
        .execute(
            () -> {
              try {
                JSONArray data =
                    YingxiaApp.get()
                        .manga
                        .pages(source.optString("id"), comic.optString("id"), chapter);
                runOnUiThread(
                    () -> {
                      pages = data;
                      page = Math.min(page, pages.length() - 1);
                      load();
                    });
              } catch (Exception e) {
                runOnUiThread(() -> failure(Json.message(e)));
              }
            });
  }

  private void load() {
    int token = ++request;
    status.setText(
        comic.optString("title") + " · " + (page + 1) + " / " + pages.length() + "  正在加载…");
    String url = pages.optString(page);
    YingxiaApp.get()
        .repository
        .settings
        .edit()
        .putInt(
            "read." + source.optString("id") + "@" + comic.optString("id") + "@" + chapter, page)
        .apply();
    YingxiaApp.get()
        .io
        .execute(
            () -> {
              try {
                byte[] bytes =
                    YingxiaApp.get()
                        .manga
                        .image(source.optString("id"), url, comic.optString("id"), chapter);
                Bitmap bitmap =
                    Artwork.decode(
                        bytes,
                        getResources().getDisplayMetrics().widthPixels,
                        getResources().getDisplayMetrics().heightPixels);
                if (bitmap == null) throw new java.io.IOException("页面图片无法解码");
                runOnUiThread(
                    () -> {
                      if (token != request || isFinishing()) {
                        bitmap.recycle();
                        return;
                      }
                      image.setImageBitmap(bitmap);
                      status.setText(
                          comic.optString("title")
                              + " · "
                              + (page + 1)
                              + " / "
                              + pages.length()
                              + "  ← 上一页  /  下一页 →");
                    });
              } catch (Exception e) {
                runOnUiThread(
                    () -> {
                      if (token == request && !isFinishing()) failure(Json.message(e));
                    });
              }
            });
  }

  private void failure(String reason) {
    new AlertDialog.Builder(this)
        .setTitle("页面加载失败")
        .setMessage(reason)
        .setPositiveButton("重试", (d, i) -> load())
        .setNegativeButton("返回", (d, i) -> finish())
        .show();
  }

  @Override
  public boolean onKeyDown(int code, KeyEvent event) {
    if (pages.length() > 0) {
      if (code == KeyEvent.KEYCODE_DPAD_RIGHT
          || code == KeyEvent.KEYCODE_DPAD_DOWN
          || code == KeyEvent.KEYCODE_DPAD_CENTER) {
        if (page + 1 < pages.length()) {
          page++;
          load();
        }
        return true;
      }
      if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_UP) {
        if (page > 0) {
          page--;
          load();
        }
        return true;
      }
    }
    return super.onKeyDown(code, event);
  }

  @Override
  protected void onDestroy() {
    request++;
    image.setImageDrawable(null);
    super.onDestroy();
  }
}
