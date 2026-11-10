package com.yingxia.tv.core;

import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/** ItaloSound station and server-clock song timing, ported from Mp4Hub's ARM32 TV radio. */
public final class ItaloRadio {
  public static final String API = "https://italosound.com/radio/currently-playing";
  public static final long FALLBACK_DELAY = 30000;
  public static final String[] STREAMS = {
      "http://icecast.italosound.com:8000/italosound",
      "http://icecast.italosound.com:8000/italosoundlow"
  };
  public static final Map<String, String> HEADERS = Map.of(
      "User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36",
      "Referer", "https://italosound.com/", "X-Requested-With", "XMLHttpRequest",
      "Cache-Control", "no-cache");

  public final String title, artist, cover;
  public final long elapsed, duration, nextPoll;

  private ItaloRadio(JSONObject json) throws JSONException {
    JSONObject song = json.getJSONObject("song");
    title = song.optString("title");
    artist = song.optString("artist");
    cover = Json.child(song, "albumcover").optString("style_url");
    duration = Math.max(0, song.optLong("duration"));
    long start = json.optLong("start_time"), server = json.optLong("servertime");
    elapsed = start > 0 && server >= start ? (server - start) * 1000 : 0;
    long remaining = start > 0 && server > 0 && duration > 0
        ? (start + duration / 1000 - server) * 1000 : 0;
    nextPoll = Math.max(5000, Math.min(180000, remaining > 0 ? remaining + 3000 : FALLBACK_DELAY));
  }

  public static ItaloRadio parse(String body) throws JSONException {
    return new ItaloRadio(new JSONObject(body));
  }

  public long progress(long sinceFetch) {
    return duration > 0 ? Math.min(duration, Math.max(0, elapsed + sinceFetch)) : 0;
  }
}
