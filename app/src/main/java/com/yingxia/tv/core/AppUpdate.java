package com.yingxia.tv.core;

import java.net.URI;
import java.util.Locale;
import org.json.JSONObject;

/** Strict, numeric update contract for this Android port, never for the Apple upstream. */
public final class AppUpdate {
  public static final String REPOSITORY = "cxapython/tvbox-arm";
  public static final String MANIFEST_URL = "https://github.com/" + REPOSITORY + "/releases/latest/download/update.json";
  public static final String APK_NAME = "yingxia-tv-arm32.apk";
  public static final long MAX_APK_BYTES = 100L * 1024 * 1024;
  public final int versionCode, minSdk;
  public final long size;
  public final String versionName, tag, apkUrl, sha256, certificateSha256, notes;
  private final JSONObject json;

  public AppUpdate(JSONObject value) {
    json = Json.copy(value);
    versionCode = value.optInt("versionCode", -1);
    minSdk = value.optInt("minSdk", -1);
    size = value.optLong("size", -1);
    versionName = value.optString("versionName");
    tag = value.optString("tag");
    notes = value.optString("notes").substring(0, Math.min(4000, value.optString("notes").length()));
    apkUrl = value.optString("apkUrl");
    sha256 = value.optString("sha256").toLowerCase(Locale.ROOT);
    certificateSha256 = value.optString("certificateSha256").toLowerCase(Locale.ROOT);
    if (value.optInt("schema") != 1 || !"com.yingxia.tv".equals(value.optString("applicationId"))
        || !"armeabi-v7a".equals(value.optString("abi")) || versionCode < 1 || minSdk < 21
        || size <= 0 || size > MAX_APK_BYTES || versionName.isEmpty() || versionName.length() > 100
        || !tag.equals("v" + versionName) || !digest(sha256) || !digest(certificateSha256))
      throw new IllegalArgumentException("更新描述不完整或不适用于此电视版");
    requireAssetUrl(apkUrl, tag, APK_NAME);
  }

  public boolean newerThan(int installedCode) { return versionCode > installedCode; }
  public JSONObject json() { return Json.copy(json); }

  public static boolean digest(String value) { return value.matches("[0-9a-f]{64}"); }

  /** Do not allow metadata or APK URLs from a different repository, branch, scheme or path. */
  public static void requireAssetUrl(String url, String tag, String filename) {
    try {
      URI uri = new URI(url);
      String path = "/" + REPOSITORY + "/releases/download/" + tag + "/" + filename;
      if (!tag.matches("v[0-9A-Za-z._-]+") || !"https".equals(uri.getScheme())
          || !"github.com".equals(uri.getHost()) || uri.getPort() != -1
          || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
          || !path.equals(uri.getRawPath())) throw new IllegalArgumentException();
    } catch (Exception invalid) {
      throw new IllegalArgumentException("更新地址不是本项目的正式发布附件");
    }
  }
}
