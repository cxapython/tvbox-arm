package com.yingxia.tv.data;

import androidx.media3.datasource.DefaultHttpDataSource;
import java.util.LinkedHashMap;
import java.util.Map;

/** Source headers must reach both HLS playlists and segments unchanged. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlaybackHttp {
  private PlaybackHttp() {}

  public static Map<String, String> headers(Map<String, String> source) {
    Map<String, String> result = new LinkedHashMap<>();
    result.put("User-Agent", Http.UA);
    source.forEach((key, value) ->
        result.put(key.equalsIgnoreCase("User-Agent") ? "User-Agent" : key, value));
    return result;
  }

  public static DefaultHttpDataSource.Factory factory(Map<String, String> source) {
    // setUserAgent() unconditionally overwrites even a source's per-request User-Agent.
    // Supply the fallback as a request property, just as the reference app does.
    return new DefaultHttpDataSource.Factory()
        .setDefaultRequestProperties(headers(source))
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(12000)
        .setReadTimeoutMs(25000);
  }
}
