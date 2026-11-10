package com.yingxia.tv.core;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;
import org.junit.Test;

public class MusifyCatalogTest {
  private String fixture() throws Exception {
    try (InputStream in = getClass().getResourceAsStream("/musify-search.html")) {
      assertNotNull(in);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test public void realSearchRowsKeepVersionArtistDurationAndStableIdentity() throws Exception {
    JSONObject page = MusifyCatalog.parsePage(fixture(), MusifyCatalog.BASE + "/en/search");
    JSONArray songs = page.getJSONArray("items");
    assertEquals(2, songs.length());
    assertTrue(page.getBoolean("hasNext"));
    JSONObject song = songs.getJSONObject(0);
    assertEquals("musify:4422849", song.getString("id"));
    assertEquals("Rockstar (Hands Up Mix)", song.getString("name"));
    assertEquals("DJ Gollum, Nicco, Empyre One", song.getString("artist"));
    assertEquals(314000, song.getLong("duration"));
    assertTrue(song.getString("poster").startsWith("https://"));
    assertTrue(song.getString("pageUrl").endsWith("-4422849"));
    assertTrue(song.getBoolean("music"));
    assertTrue(song.getBoolean("fixedQuality"));
    assertEquals("Rockstar (Hands Up Radio Edit)", songs.getJSONObject(1).getString("name"));
  }

  @Test public void paginationUsesWebsiteNextLinkAndDistinguishesEmptyFromBlocked() throws Exception {
    String end = fixture().replace("class=\"pagination-item pagination-next\"", "class=\"pagination-item pagination-next pointer-events-none\"");
    assertFalse(MusifyCatalog.parsePage(end, MusifyCatalog.BASE).getBoolean("hasNext"));
    JSONObject empty = MusifyCatalog.parsePage("<form data-search-instant-form></form>", MusifyCatalog.BASE);
    assertEquals(0, empty.getJSONArray("items").length());
    assertFalse(empty.getBoolean("hasNext"));
    assertThrows(IOException.class, () -> MusifyCatalog.parsePage("<h1>403 Forbidden</h1>", MusifyCatalog.BASE));
  }

  @Test public void malformedAndRepeatedRowsDoNotProduceBrokenSongs() throws Exception {
    String row = "<div class='tracklist__row' data-song-id='7' data-name='A &amp; B (Live)' data-artist='X &amp; Y'>"
        + "<span data-duration='1:02:03'></span></div>";
    JSONArray items = MusifyCatalog.parsePage("<div class='tracklist'>" + row + row
        + row.replace("data-song-id='7'", "data-song-id='../bad'") + "</div>", MusifyCatalog.BASE).getJSONArray("items");
    assertEquals(1, items.length());
    assertEquals("A & B (Live)", items.getJSONObject(0).getString("name"));
    assertEquals(3723000, items.getJSONObject(0).getLong("duration"));
  }

  @Test public void searchEscapesKeywordsAndArtistPagesUsePathPagination() throws Exception {
    assertEquals(MusifyCatalog.BASE + "/en/search?searchText=A%26B+%E6%AD%8C&type=song&page=2",
        MusifyCatalog.searchUrl("A&B 歌", 2));
    String artist = MusifyCatalog.BASE + "/en/artist/empyre-one-164584";
    assertEquals(artist + "/tracks", MusifyCatalog.searchUrl(artist, 1));
    assertEquals(artist + "/tracks/page2", MusifyCatalog.searchUrl(artist + "/tracks/", 2));
    assertThrows(IOException.class, () -> MusifyCatalog.searchUrl("https://elsewhere.test/artist/a", 1));
    assertThrows(IOException.class, () -> MusifyCatalog.searchUrl(MusifyCatalog.BASE + "/login", 1));
  }

  @Test public void resolveRenewsPublicUrlAndNeverClaimsAnUnmeasuredBitrate() throws Exception {
    int[] calls = {0};
    MusifyCatalog catalog = new MusifyCatalog(url -> {
      assertEquals(MusifyCatalog.BASE + "/api/track/4422849/stream-url", url);
      return Json.obj("url", "https://41s.musify.club/get/song.mp3?expires=" + ++calls[0]).toString();
    });
    JSONObject song = Json.obj("songId", "4422849");
    JSONObject first = catalog.resolve(song, "flac"), second = catalog.resolve(song, "128");
    assertNotEquals(first.getString("url"), second.getString("url"));
    assertEquals("musify", first.getString("upstream"));
    assertFalse(first.has("actualQuality"));
    assertTrue(first.getBoolean("fixedQuality"));
    assertEquals(MusifyCatalog.USER_AGENT, first.getJSONObject("header").getString("User-Agent"));
    assertFalse(first.getJSONObject("header").has("Cookie"));
    assertThrows(IOException.class, () -> catalog.resolve(Json.obj("songId", "../x"), "320"));
  }

  @Test public void gatesAndInvalidResponsesFailWithoutAnAudioUrl() {
    JSONObject song = Json.obj("songId", "1");
    assertThrows(IOException.class, () -> new MusifyCatalog(url -> "<html>login</html>").resolve(song, "320"));
    IOException gate = assertThrows(IOException.class, () -> new MusifyCatalog(url -> "{\"error\":\"copyrighted\"}").resolve(song, "320"));
    assertTrue(gate.getMessage().contains("访问限制"));
    assertTrue(MusifyCatalog.errorMessage(429, "{}").contains("次数"));
    assertTrue(MusifyCatalog.errorMessage(401, "{}").contains("授权"));
    assertFalse(MusifyCatalog.isMediaUrl("https://musify.club.attacker.test/song.mp3"));
    assertFalse(MusifyCatalog.isMediaUrl("https://user:password@musify.club/song.mp3"));
    assertFalse(MusifyCatalog.isMediaUrl("file:///song.mp3"));
  }
}
