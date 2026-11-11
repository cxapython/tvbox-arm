package com.yingxia.tv.core;

import static org.junit.Assert.*;
import java.io.IOException;
import org.json.*;
import org.junit.Test;

public class AudiusCatalogTest {
  @Test public void parsesHardstyleTrackMetadata() throws Exception {
    JSONObject song = AudiusCatalog.parseTrack(new JSONObject()
        .put("id", "bpJ6P").put("title", "Hardstyle set #1").put("duration", 3636)
        .put("is_streamable", true).put("genre", "Hardstyle")
        .put("user", new JSONObject().put("name", "Ducky"))
        .put("artwork", new JSONObject().put("480x480", "https://img.example/cover.jpg")));
    assertEquals("audius:bpJ6P", song.getString("id"));
    assertEquals("Ducky", song.getString("artist"));
    assertEquals(3636000, song.getLong("duration"));
    assertTrue(song.getBoolean("fixedQuality"));
  }

  @Test public void rejectsMalformedOrUnstreamableRows() throws Exception {
    assertNull(AudiusCatalog.parseTrack(new JSONObject().put("id", "x").put("title", "x")
        .put("is_streamable", false)));
    assertNull(AudiusCatalog.parseTrack(new JSONObject().put("id", "x")));
    assertFalse(AudiusCatalog.isMediaUrl("file:///tmp/song"));
  }

  @Test public void searchAndResolveUseFreshTrackDetails() throws Exception {
    AudiusCatalog catalog = new AudiusCatalog(url -> {
      if (url.contains("/tracks/search")) return new JSONObject().put("data", new JSONArray()
          .put(new JSONObject().put("id", "bpJ6P").put("title", "Hardstyle")
              .put("duration", 10).put("is_streamable", true))).toString();
      assertTrue(url.contains("/tracks/bpJ6P"));
      return new JSONObject().put("data", new JSONObject()
          .put("stream", new JSONObject().put("url", "https://cdn.example/a.mp3"))).toString();
    });
    JSONObject song = catalog.search("hardstyle", 1).get(0);
    assertEquals("https://cdn.example/a.mp3", catalog.resolve(song, "flac").getString("url"));
  }
}
