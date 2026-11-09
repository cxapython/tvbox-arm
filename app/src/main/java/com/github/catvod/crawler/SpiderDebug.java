package com.github.catvod.crawler;

public final class SpiderDebug {
  public static void log(String text) {
    android.util.Log.d("Yingxia.Plugin", "Plugin log (" + text.length() + " characters)");
  }

  public static void log(Throwable error) {
    android.util.Log.d("Yingxia.Plugin", error.getClass().getSimpleName());
  }
}
