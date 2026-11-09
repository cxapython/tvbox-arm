package com.github.catvod.net;

/** Spider requests use the app's connection pool and normal Android TLS validation. */
public final class OkHttp {
  public static okhttp3.Dns dns() {
    return okhttp3.Dns.SYSTEM;
  }

  public static okhttp3.OkHttpClient client() {
    return com.yingxia.tv.YingxiaApp.get().http.client;
  }
}
