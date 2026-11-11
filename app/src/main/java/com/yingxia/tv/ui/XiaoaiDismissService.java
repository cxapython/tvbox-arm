package com.yingxia.tv.ui;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

/** Optionally closes the stock Xiaoai result card after the bridge consumes its query. */
public final class XiaoaiDismissService extends AccessibilityService {
  private static volatile XiaoaiDismissService instance;

  static void dismiss() {
    XiaoaiDismissService service = instance;
    if (service == null) return;
    for (AccessibilityWindowInfo window : service.getWindows()) {
      AccessibilityNodeInfo root = window.getRoot();
      if (root == null) continue;
      CharSequence packageName = root.getPackageName();
      root.recycle();
      if ("com.xiaomi.voicecontrol".contentEquals(packageName)) {
        service.performGlobalAction(GLOBAL_ACTION_BACK);
        return;
      }
    }
  }

  @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

  @Override public void onInterrupt() {}

  @Override protected void onServiceConnected() {
    super.onServiceConnected();
    instance = this;
  }

  @Override public boolean onUnbind(Intent intent) {
    instance = null;
    return super.onUnbind(intent);
  }

  @Override public void onDestroy() {
    if (instance == this) instance = null;
    super.onDestroy();
  }
}
