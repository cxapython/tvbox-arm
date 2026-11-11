package com.yingxia.tv.ui;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads completed ASR queries emitted by the stock Xiaomi assistant. */
final class XiaoaiBridge {
  interface Target {
    void onVoiceText(String text);
  }

  private static final String TAG = "Yingxia.Xiaoai";
  private static final Pattern QUERY = Pattern.compile(
      "onConversationFinished Conversation\\(type=QUERY,.*?queryType=ASR, query=(.*?), requestId=");
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static Target target;
  private static Thread reader;
  private static Process process;

  private XiaoaiBridge() {}

  static synchronized void attach(Target next) {
    target = next;
    if (reader == null) start();
  }

  static synchronized void detach(Target current) {
    if (target != current) return;
    target = null;
    stop();
  }

  private static void start() {
    reader = new Thread(() -> {
      try {
        process = Runtime.getRuntime().exec(new String[] {
            "logcat", "-v", "raw", "-T", "1", "-e", "onConversationFinished"
        });
        try (BufferedReader input = new BufferedReader(
            new InputStreamReader(process.getInputStream(), "UTF-8"))) {
          for (String line; (line = input.readLine()) != null; ) {
            Matcher match = QUERY.matcher(line);
            if (!match.find()) continue;
            String text = match.group(1).trim();
            if (text.isEmpty()) continue;
            MAIN.post(() -> deliver(text));
          }
        }
      } catch (Exception error) {
        Log.w(TAG, "Xiaoai log reader stopped", error);
      }
    }, "yingxia-xiaoai");
    reader.setDaemon(true);
    reader.start();
  }

  private static synchronized void stop() {
    if (process != null) process.destroy();
    process = null;
    reader = null;
  }

  private static void deliver(String text) {
    Target next;
    synchronized (XiaoaiBridge.class) {
      next = target;
    }
    if (next == null) return;
    XiaoaiDismissService.dismiss();
    next.onVoiceText(text);
  }
}
