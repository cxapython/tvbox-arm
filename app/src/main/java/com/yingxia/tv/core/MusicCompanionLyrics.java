package com.yingxia.tv.core;

import java.util.List;
import org.json.JSONObject;

/** Lyrics never change the playing audio; accept only the same title, artist, version and duration. */
public final class MusicCompanionLyrics {
  private MusicCompanionLyrics() {}
  public interface Catalog {
    List<JSONObject> search(String query) throws Exception;
    JSONObject lyrics(JSONObject song) throws Exception;
  }
  public static JSONObject find(JSONObject song, List<Catalog> catalogs) {
    String query = song.optString("name") + " " + song.optString("artist");
    for (Catalog catalog : catalogs) {
      try {
        for (JSONObject candidate : catalog.search(query)) {
          if (!MusicRecordingMatch.same(song, candidate)) continue;
          JSONObject lyrics = catalog.lyrics(candidate);
          if (lyrics != null && !lyrics.optString("lyric").trim().isEmpty()) return lyrics;
        }
      } catch (Exception unavailable) {
        if (Thread.currentThread().isInterrupted()) return empty();
        // One catalog's failure must not prevent trying the other one.
      }
    }
    return empty();
  }
  public static JSONObject empty() { return Json.obj("lyric", "", "translation", ""); }
}
