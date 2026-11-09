package com.github.catvod;

public final class Proxy {
  public static String getUrl() {
    return getUrl(true);
  }

  public static String getUrl(boolean local) {
    return com.yingxia.tv.data.PluginProxy.url();
  }
}
