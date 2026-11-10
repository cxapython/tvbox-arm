package com.yingxia.tv.core;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.*;
import org.json.*;
import org.junit.Test;

public class GdMusicCatalogTest {
  private static JSONObject track(int id) {
    return Json.obj("id", id, "name", "Song " + id, "ar", new JSONArray().put(Json.obj("name", "Artist")),
        "al", Json.obj("name", "Album", "picUrl", "https://example.org/cover.jpg"), "dt", 245000);
  }

  @Test public void searchNormalizesArtistsAndSkipsBrokenRows() throws Exception {
    JSONArray input = new JSONArray().put(Json.obj("id", "1", "name", "A", "artist", new JSONArray().put("甲").put("乙")))
        .put(Json.obj("id", 2, "name", "B", "artist", "丙")).put(Json.obj("id", JSONObject.NULL, "name", "Broken"))
        .put(Json.obj("id", "3", "name", ""));
    List<JSONObject> result = GdMusicCatalog.parseSearch(input);
    assertEquals(2, result.size());
    assertEquals("甲 / 乙", result.get(0).getString("artist"));
    assertEquals("netease:2", result.get(1).getString("id"));
    assertTrue(result.get(0).getBoolean("music"));
    assertTrue(PlaybackHistory.resumable(result.get(0)));
    assertThrows(IOException.class, () -> GdMusicCatalog.parseSearch(Json.obj("error", "upstream unavailable")));
  }

  @Test public void enrichmentFailurePreservesSearchAndSuccessUsesMilliseconds() throws Exception {
    JSONArray search = new JSONArray().put(Json.obj("id", "1", "name", "Song", "artist", "Artist"));
    GdMusicCatalog failed = new GdMusicCatalog((url, query, timeout) -> {
      if (url.equals(GdMusicCatalog.API)) return search;
      throw new IOException("details offline");
    });
    assertEquals(1, failed.search("Song", 1).size());
    GdMusicCatalog ok = new GdMusicCatalog((url, query, timeout) -> url.equals(GdMusicCatalog.API)
        ? search : Json.obj("code", 200, "songs", new JSONArray().put(track(1))));
    JSONObject enriched = ok.search("Song", 1).get(0);
    assertEquals(245000, enriched.getLong("duration"));
    assertEquals("https://example.org/cover.jpg?param=300y300", enriched.getString("poster"));
  }

  @Test public void oldAndNewTrackShapesAgree() throws Exception {
    JSONObject old = Json.obj("id", "1", "name", "Song 1", "artists", track(1).getJSONArray("ar"),
        "album", track(1).getJSONObject("al"), "duration", 245000);
    List<JSONObject> songs = GdMusicCatalog.parseTracks(new JSONArray().put(old).put(track(1)));
    for (String key : new String[] {"id", "artist", "album", "duration", "poster", "lyricId"})
      assertEquals(songs.get(0).get(key), songs.get(1).get(key));
  }

  @Test public void playlistFillsMissingTracksInOriginalOrder() throws Exception {
    List<String> requests = new ArrayList<>();
    GdMusicCatalog client = new GdMusicCatalog((url, query, timeout) -> {
      requests.add(url);
      if (url.endsWith("playlist/detail")) return Json.obj("code", 200, "playlist", Json.obj(
          "tracks", new JSONArray().put(track(3)),
          "trackIds", new JSONArray().put(Json.obj("id", 1)).put(Json.obj("id", 3)).put(Json.obj("id", 2)).put(Json.obj("id", 1))));
      assertEquals("[1,2]", query.get("ids"));
      return Json.obj("code", 200, "songs", new JSONArray().put(track(2)).put(track(1)));
    });
    List<JSONObject> songs = client.tracks(Json.obj("collectionId", "100", "kind", "playlist"));
    assertEquals(Arrays.asList("1", "3", "2"), Arrays.asList(songs.get(0).getString("songId"), songs.get(1).getString("songId"), songs.get(2).getString("songId")));
    assertEquals(2, requests.size());
  }

  @Test public void largePlaylistBatchesAndLimitsRequests() throws Exception {
    int[] details = {0};
    GdMusicCatalog client = new GdMusicCatalog((url, query, timeout) -> {
      if (url.endsWith("playlist/detail")) {
        JSONArray ids = new JSONArray();
        for (int i = 1; i <= 700; i++) ids.put(Json.obj("id", i));
        return Json.obj("playlist", Json.obj("trackIds", ids));
      }
      details[0]++;
      JSONArray ids = new JSONArray(query.get("ids")), songs = new JSONArray();
      assertTrue(ids.length() <= 200);
      for (int i = 0; i < ids.length(); i++) songs.put(track(ids.getInt(i)));
      return Json.obj("songs", songs);
    });
    List<JSONObject> songs = client.tracks(Json.obj("collectionId", "1", "kind", "playlist"));
    assertEquals(500, songs.size());
    assertEquals(3, details[0]);
    assertEquals("500", songs.get(499).getString("songId"));
  }

  @Test public void browseKeepsWorkingShelvesAndCollectionSearchUsesPaging() throws Exception {
    GdMusicCatalog client = new GdMusicCatalog((url, query, timeout) -> {
      if (url.endsWith("toplist")) return Json.obj("list", new JSONArray()
          .put(Json.obj("id", 1, "name", "Hot", "ToplistType", "H"))
          .put(Json.obj("id", 2, "name", "Rock")));
      if (url.endsWith("search/get/web")) {
        assertEquals("10", query.get("type"));
        assertEquals("40", query.get("offset"));
        return Json.obj("result", Json.obj("albums", new JSONArray().put(Json.obj("id", 3, "name", "Album"))));
      }
      throw new IOException("shelf offline");
    });
    assertEquals(2, client.browse().size());
    assertEquals("album", client.searchCollections("A", "album", 3).get(0).getString("kind"));
    GdMusicCatalog offline = new GdMusicCatalog((url, query, timeout) -> { throw new IOException(); });
    assertThrows(IOException.class, offline::browse);
  }

  @Test public void streamValidationKeepsActualBitrateAndLyricsTranslation() throws Exception {
    assertEquals("944", GdMusicCatalog.parseStream(Json.obj("url", "https://example.org/a.flac", "br", 944)).getString("actualQuality"));
    for (String url : new String[] {"", "file:///tmp/a", "https:///a", "https://"})
      assertThrows(IOException.class, () -> GdMusicCatalog.parseStream(Json.obj("url", url)));
    GdMusicCatalog client = new GdMusicCatalog((url, query, timeout) -> {
      if (url.equals(GdMusicCatalog.API)) {
        assertEquals("999", query.get("br"));
        assertEquals(8000, timeout);
        return Json.obj("url", "https://example.org/a.flac", "br", 944);
      }
      return Json.obj("lrc", Json.obj("lyric", "[00:01]hello"), "tlyric", Json.obj("lyric", "[00:01]你好"));
    });
    client.resolve("1", MusicResolver.FLAC);
    JSONObject lyric = client.lyric("1");
    assertEquals("你好", MusicLyrics.parse(lyric.getString("lyric"), lyric.getString("translation")).lines.get(0).translation);
  }

  @Test public void budgetPersistsAcrossOperationsAndExpiresAtWindowBoundary() throws Exception {
    JSONArray requests = new JSONArray();
    long start = 1000000;
    for (int i = 0; i < 50; i++) requests = MusicRequestBudget.admit(new JSONArray(requests.toString()), start + i * 1000);
    final JSONArray full = requests;
    assertThrows(IOException.class, () -> MusicRequestBudget.admit(full, start + 60000));
    assertEquals(50, MusicRequestBudget.admit(full, start + 300000).length());
    assertEquals(1, MusicRequestBudget.admit(full, start + 600000).length());
  }
}
