package com.github.catvod.utils;

import android.app.*;
import android.os.*;
import com.yingxia.tv.core.Json;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Native TV dialog for new-SDK plugins. A pending sign-in can be cancelled with Back. */
public final class Dialog implements AutoCloseable {
  private volatile AlertDialog dialog;

  public interface Callback {
    JSONObject onAction(JSONObject event) throws Exception;
  }

  public JSONObject show(JSONObject options) throws Exception {
    return show(options, null);
  }

  public JSONObject show(JSONObject options, Callback callback) throws Exception {
    Activity activity = com.github.catvod.Runtime.activity();
    if (activity == null) throw new IllegalStateException("应用不在前台");
    if (Looper.myLooper() == Looper.getMainLooper())
      throw new IllegalStateException("Plugin dialog must run on a worker");
    java.util.concurrent.ArrayBlockingQueue<JSONObject> done =
        new java.util.concurrent.ArrayBlockingQueue<>(1);
    activity.runOnUiThread(
        () -> {
          AlertDialog.Builder builder =
              new AlertDialog.Builder(activity)
                  .setTitle(options.optString("title", "来源设置"))
                  .setMessage(options.optString("message", options.optString("content")))
                  .setPositiveButton(
                      options.optString("confirm", "确定"),
                      (d, i) -> done.offer(Json.obj("action", "confirm")))
                  .setNegativeButton("取消", (d, i) -> done.offer(Json.obj("action", "cancel")))
                  .setOnCancelListener(d -> done.offer(Json.obj("action", "cancel")));
          dialog = builder.show();
        });
    JSONObject result = done.poll(5, TimeUnit.MINUTES);
    close();
    if (result == null) throw new java.util.concurrent.TimeoutException("Dialog timed out");
    if (callback != null) {
      JSONObject update = callback.onAction(result);
      if (update != null) return update;
    }
    return result;
  }

  @Override
  public void close() {
    AlertDialog current = dialog;
    if (current != null) new Handler(Looper.getMainLooper()).post(current::dismiss);
    dialog = null;
  }
}
