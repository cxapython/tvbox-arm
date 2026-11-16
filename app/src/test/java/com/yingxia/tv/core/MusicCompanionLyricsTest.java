package com.yingxia.tv.core;
import static org.junit.Assert.*;
import java.util.List;
import java.io.IOException;
import org.json.JSONObject;
import org.junit.Test;
public class MusicCompanionLyricsTest {
  private JSONObject song(String title, long duration) {
    return Json.obj("name", title, "artist", "Artist", "duration", duration);
  }
  private MusicCompanionLyrics.Catalog catalog(JSONObject candidate, String lyric) {
    return new MusicCompanionLyrics.Catalog() {
      public List<JSONObject> search(String query) { return List.of(candidate); }
      public JSONObject lyrics(JSONObject song) { return Json.obj("lyric", lyric); }
    };
  }
  @Test public void fallsBackAfterEmptyLyricsOrCatalogFailure() {
    JSONObject song = song("Song", 200000);
    MusicCompanionLyrics.Catalog unavailable = new MusicCompanionLyrics.Catalog() {
      public List<JSONObject> search(String query) throws Exception { throw new IOException("unavailable"); }
      public JSONObject lyrics(JSONObject song) { throw new AssertionError(); }
    };
    assertEquals("correct", MusicCompanionLyrics.find(song, List.of(unavailable,
        catalog(song, ""), catalog(song, "correct"))).optString("lyric"));
  }
  @Test public void rejectsRemixAndDifferentRecording() {
    JSONObject song = song("Song", 200000);
    assertEquals("", MusicCompanionLyrics.find(song, List.of(catalog(song("Song (Remix)", 200000), "wrong"),
        catalog(song("Song", 240000), "wrong"))).optString("lyric"));
  }
}
