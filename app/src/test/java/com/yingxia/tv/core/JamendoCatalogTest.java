package com.yingxia.tv.core;

import static org.junit.Assert.*;
import org.json.*;
import org.junit.Test;

public class JamendoCatalogTest {
  @Test public void parsesItaloDanceTrackAndEscapesHtml() throws Exception {
    JSONObject song = JamendoCatalog.parseTrack(new JSONObject()
        .put("id", "1446381").put("name", "Ora Esatta &amp; Remix").put("artist_name", "Giry")
        .put("album_name", "DREAMER").put("duration", 245)
        .put("album_image", "https://usercontent.jamendo.com/cover.jpg")
        .put("audio", "https://prod-1.storage.jamendo.com/?trackid=1446381"));
    assertEquals("jamendo:1446381", song.getString("id"));
    assertEquals("Ora Esatta & Remix", song.getString("name"));
    assertEquals(245000, song.getLong("duration"));
    assertTrue(song.getBoolean("fixedQuality"));
  }

  @Test public void rejectsNonJamendoAudioUrls() throws Exception {
    assertNull(JamendoCatalog.parseTrack(new JSONObject().put("id", "1").put("name", "x")
        .put("audio", "https://attacker.example/song.mp3")));
    assertFalse(JamendoCatalog.isMediaUrl("https://jamendo.com.attacker.example/song.mp3"));
    assertFalse(JamendoCatalog.isMediaUrl("http://prod-1.storage.jamendo.com/song.mp3"));
  }

  @Test public void resolvesFreshAudioUrl() throws Exception {
    JamendoCatalog catalog = new JamendoCatalog(url -> new JSONObject()
        .put("headers", new JSONObject().put("code", 0))
        .put("results", new JSONArray().put(new JSONObject().put("id", "7")
            .put("name", "Dance").put("audio", "https://prod-1.storage.jamendo.com/a.mp3"))).toString());
    JSONObject resolved = catalog.resolve(Json.obj("songId", "7"), "flac");
    assertEquals("https://prod-1.storage.jamendo.com/a.mp3", resolved.getString("url"));
    assertEquals("jamendo", resolved.getString("upstream"));
  }
}
