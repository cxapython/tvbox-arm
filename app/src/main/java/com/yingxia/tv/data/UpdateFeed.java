package com.yingxia.tv.data;

import com.yingxia.tv.core.AppUpdate;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import okhttp3.CacheControl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;

/** Read the official release asset directly, without consuming GitHub REST API quota. */
public final class UpdateFeed {
  private static final int MAX_METADATA_BYTES = 32 * 1024;

  private UpdateFeed() {}

  public static AppUpdate fetch(OkHttpClient http, String version) throws Exception {
    Request request = new Request.Builder().url(AppUpdate.MANIFEST_URL)
        .header("Accept", "application/json").header("User-Agent", "Yingxia-TV/" + version)
        .cacheControl(new CacheControl.Builder().noCache().build()).build();
    try (Response response = http.newCall(request).execute()) {
      // Older releases have no update.json. This is not an API or network failure.
      if (response.code() == 404) return null;
      if (!response.isSuccessful()) {
        boolean limited = response.code() == 429 || (response.code() == 403
            && ("0".equals(response.header("X-RateLimit-Remaining"))
                || response.header("Retry-After") != null));
        if (limited) throw new IOException("更新下载服务暂时限流（HTTP " + response.code() + "），请稍后重试");
        if (response.code() == 403)
          throw new IOException("GitHub 拒绝更新请求（HTTP 403），请检查网络或代理后重试");
        throw new IOException("更新服务返回 HTTP " + response.code());
      }
      if (!response.request().url().isHttps() || response.body() == null)
        throw new IOException("更新连接不安全或内容为空");
      try (InputStream in = response.body().byteStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192]; int count;
        while ((count = in.read(buffer)) != -1) {
          if (out.size() + count > MAX_METADATA_BYTES) throw new IOException("更新描述超过大小限制");
          out.write(buffer, 0, count);
        }
        AppUpdate update = new AppUpdate(new JSONObject(out.toString("UTF-8")));
        // If GitHub exposed the tagged asset URL in its redirect chain, its tag must agree.
        // The final URL may be GitHub's signed release-assets CDN URL.
        for (Response hop = response; hop != null; hop = hop.priorResponse()) {
          okhttp3.HttpUrl url = hop.request().url();
          if ("github.com".equals(url.host())
              && url.encodedPath().startsWith("/" + AppUpdate.REPOSITORY + "/releases/download/"))
            AppUpdate.requireAssetUrl(url.toString(), update.tag, "update.json");
        }
        return update;
      }
    }
  }
}
