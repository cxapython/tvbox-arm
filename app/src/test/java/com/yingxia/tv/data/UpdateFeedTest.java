package com.yingxia.tv.data;

import static org.junit.Assert.*;
import com.yingxia.tv.core.AppUpdate;
import com.yingxia.tv.core.Json;
import java.io.IOException;
import okhttp3.*;
import org.json.JSONObject;
import org.junit.Test;

public class UpdateFeedTest {
  private JSONObject metadata() {
    return Json.obj("schema", 1, "applicationId", "com.yingxia.tv", "abi", "armeabi-v7a",
        "versionCode", 6, "versionName", "1.8.15-android.6-tv", "minSdk", 21,
        "size", 7000000, "tag", "v1.8.15-android.6-tv", "sha256", "a".repeat(64),
        "certificateSha256", "b".repeat(64), "apkUrl",
        "https://github.com/cxapython/tvbox-arm/releases/download/v1.8.15-android.6-tv/yingxia-tv-arm32.apk");
  }

  private OkHttpClient client(int code, String body, Headers headers) {
    return new OkHttpClient.Builder().addInterceptor(chain -> {
      assertEquals(AppUpdate.MANIFEST_URL, chain.request().url().toString());
      assertEquals("github.com", chain.request().url().host());
      assertTrue(chain.request().url().isHttps());
      return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
          .code(code).message("fixture").headers(headers)
          .body(ResponseBody.create(MediaType.get("application/json"), body)).build();
    }).build();
  }
  private String error(int code, Headers headers) throws Exception {
    try { UpdateFeed.fetch(client(code, "{}", headers), "test"); fail("HTTP error accepted"); return ""; }
    catch (IOException expected) { return expected.getMessage(); }
  }

  @Test public void readsOfficialAssetWithoutCallingRestApi() throws Exception {
    AppUpdate update = UpdateFeed.fetch(client(200, metadata().toString(), new Headers.Builder().build()), "test");
    assertEquals(6, update.versionCode);
    assertEquals("v1.8.15-android.6-tv", update.tag);
  }
  @Test public void olderReleaseWithoutMetadataIsNotRateLimitOrLatest() throws Exception {
    assertNull(UpdateFeed.fetch(client(404, "Not Found", new Headers.Builder().build()), "test"));
  }
  @Test public void plainForbiddenIsNotMisreportedAsRateLimit() throws Exception {
    String message = error(403, new Headers.Builder().build());
    assertTrue(message.contains("HTTP 403"));
    assertTrue(message.contains("拒绝"));
    assertFalse(message.contains("限流"));
  }
  @Test public void genuineRateLimitsAreRecognized() throws Exception {
    assertTrue(error(429, new Headers.Builder().build()).contains("限流"));
    assertTrue(error(403, new Headers.Builder().add("X-RateLimit-Remaining", "0").build()).contains("限流"));
    assertTrue(error(403, new Headers.Builder().add("Retry-After", "60").build()).contains("限流"));
  }
  @Test public void otherHttpErrorsKeepTheirStatus() throws Exception {
    assertTrue(error(503, new Headers.Builder().build()).contains("HTTP 503"));
  }
  @Test public void oversizedMetadataIsRejected() throws Exception {
    try { UpdateFeed.fetch(client(200, " ".repeat(32769), new Headers.Builder().build()), "test"); fail(); }
    catch (IOException expected) { assertTrue(expected.getMessage().contains("大小限制")); }
  }
  @Test public void wrongRepositoryApkIsStillRejected() throws Exception {
    JSONObject value = metadata().put("apkUrl", "https://evil.example/app.apk");
    try { UpdateFeed.fetch(client(200, value.toString(), new Headers.Builder().build()), "test"); fail(); }
    catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("正式发布附件")); }
  }
  @Test public void taggedRedirectMustMatchManifest() throws Exception {
    OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
      Request redirected = chain.request().newBuilder().url(
          "https://github.com/cxapython/tvbox-arm/releases/download/vwrong/update.json").build();
      return new Response.Builder().request(redirected).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
          .body(ResponseBody.create(MediaType.get("application/json"), metadata().toString())).build();
    }).build();
    try { UpdateFeed.fetch(http, "test"); fail(); }
    catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("正式发布附件")); }
  }
  @Test public void signedCdnRedirectRetainsTaggedManifestCheck() throws Exception {
    for (String tag : new String[] {"v1.8.15-android.6-tv", "vwrong"}) {
      OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
        Request tagged = chain.request().newBuilder().url(
            "https://github.com/cxapython/tvbox-arm/releases/download/" + tag + "/update.json").build();
        Response redirect = new Response.Builder().request(tagged).protocol(Protocol.HTTP_1_1)
            .code(302).message("Found").build();
        Request cdn = chain.request().newBuilder().url("https://release-assets.githubusercontent.com/signed-update.json?token=fixture").build();
        return new Response.Builder().request(cdn).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .priorResponse(redirect)
            .body(ResponseBody.create(MediaType.get("application/octet-stream"), metadata().toString())).build();
      }).build();
      if (tag.equals("vwrong")) {
        try { UpdateFeed.fetch(http, "test"); fail(); }
        catch (IllegalArgumentException expected) { /* must inspect redirects, not just CDN URL */ }
      } else assertEquals(6, UpdateFeed.fetch(http, "test").versionCode);
    }
  }
  @Test public void httpDowngradeIsRejected() throws Exception {
    OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
      Request redirected = chain.request().newBuilder().url("http://github.com/asset").build();
      return new Response.Builder().request(redirected).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
          .body(ResponseBody.create(MediaType.get("application/json"), metadata().toString())).build();
    }).build();
    try { UpdateFeed.fetch(http, "test"); fail(); }
    catch (IOException expected) { assertTrue(expected.getMessage().contains("不安全")); }
  }
}
