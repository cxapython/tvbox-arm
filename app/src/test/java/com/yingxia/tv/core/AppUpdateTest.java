package com.yingxia.tv.core;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public class AppUpdateTest {
  private JSONObject manifest() {
    return Json.obj("schema", 1, "applicationId", "com.yingxia.tv", "abi", "armeabi-v7a",
        "versionCode", 5, "versionName", "1.8.15-android.5-tv", "minSdk", 21,
        "size", 7000000, "tag", "v1.8.15-android.5-tv", "sha256", "a".repeat(64),
        "certificateSha256", "b".repeat(64), "apkUrl",
        "https://github.com/cxapython/tvbox-arm/releases/download/v1.8.15-android.5-tv/yingxia-tv-arm32.apk");
  }

  @Test public void comparesVersionCodeNotMarketingVersion() {
    AppUpdate update = new AppUpdate(manifest());
    assertTrue(update.newerThan(4));
    assertFalse(update.newerThan(5));
    assertFalse(update.newerThan(10));
  }

  @Test public void rejectsWrongPackageAbiSizeDigestAndTag() throws Exception {
    for (Object[] pair : new Object[][] {{"applicationId", "evil.app"}, {"abi", "arm64-v8a"},
        {"schema", 2}, {"versionCode", 0}, {"minSdk", 19}, {"size", -1},
        {"size", AppUpdate.MAX_APK_BYTES + 1}, {"sha256", "bad"},
        {"certificateSha256", ""}, {"tag", "vwrong"}}) {
      try { new AppUpdate(manifest().put((String) pair[0], pair[1])); fail(pair[0].toString()); }
      catch (IllegalArgumentException expected) { /* rejected */ }
    }
  }

  @Test public void rejectsUntrustedUrlsAndEncodedTraversal() {
    String prefix = "https://github.com/cxapython/tvbox-arm/releases/download/";
    for (String url : new String[] {"http://github.com/cxapython/tvbox-arm/releases/download/v1/update.json",
        "https://github.com.evil/cxapython/tvbox-arm/releases/download/v1/update.json",
        "https://evil@github.com/cxapython/tvbox-arm/releases/download/v1/update.json",
        prefix + "v1/update.json?redirect=evil", prefix + "v1/%75pdate.json",
        "https://github.com/jo32/tvbox-silicon/releases/download/v1/update.json"}) {
      try { AppUpdate.requireAssetUrl(url, "v1", "update.json"); fail(url); }
      catch (IllegalArgumentException expected) { /* rejected */ }
    }
    AppUpdate.requireAssetUrl(prefix + "v1/update.json", "v1", "update.json");
  }
}
