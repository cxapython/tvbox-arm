package com.github.catvod;

public final class Runtime {
  public static android.content.Context context() {
    return com.yingxia.tv.YingxiaApp.get();
  }

  public static android.app.Activity activity() {
    return com.yingxia.tv.ui.MainActivity.current();
  }
}
