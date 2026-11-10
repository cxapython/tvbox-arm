package com.yingxia.tv.core;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.*;
import org.json.*;
import org.junit.Test;

public class NativeMusicCatalogTest {
  @Test public void miguKeepsBothIdsAndDoesNotLoseDurationOrCover() throws Exception {
    JSONObject raw = Json.obj("contentId", "600902000006889366", "copyrightId", "60054701923", "name", "晴天",
        "singerList", new JSONArray().put(Json.obj("name", "周杰伦")), "album", "叶惠美", "duration", 270, "img3", "/data/oss/cover.webp");
    List<JSONObject> songs = NativeMusicCatalog.miguTracks(Json.obj("code", "000000", "songResultData", Json.obj("resultList",
        new JSONArray().put(new JSONArray().put(raw)).put(new JSONArray().put(Json.obj("name", "missing ids"))))));
    assertEquals(1, songs.size());
    assertEquals("600902000006889366_60054701923", songs.get(0).getString("songId"));
    assertEquals(270000, songs.get(0).getLong("duration"));
    assertEquals("https://d.musicapp.migu.cn/data/oss/cover.webp", songs.get(0).getString("poster"));
    assertThrows(IOException.class, () -> NativeMusicCatalog.miguTracks(Json.obj("code", "100001")));
  }

  @Test public void kuwoDecodesEntitiesAndPreservesRecordingMetadata() throws Exception {
    List<JSONObject> songs = NativeMusicCatalog.kuwoTracks(Json.obj("abslist", new JSONArray().put(Json.obj(
        "MUSICRID", "MUSIC_228908", "NAME", "<em>晴天</em>", "ARTIST", "周杰伦&amp;费玉清", "ALBUM", "叶惠美", "DURATION", "269", "web_albumpic_short", "120/s3s94/93/211513640.jpg"))));
    JSONObject song = songs.get(0);
    assertEquals("kuwo:228908", song.getString("id"));
    assertEquals("晴天", song.getString("name"));
    assertEquals("周杰伦 / 费玉清", song.getString("artist"));
    assertEquals(269000, song.getLong("duration"));
    assertEquals("https://img4.kuwo.cn/star/albumcover/500/s3s94/93/211513640.jpg", song.getString("poster"));
    assertThrows(IOException.class, () -> NativeMusicCatalog.kuwoTracks(Json.obj("TOTAL", 0)));
  }

  @Test public void qqUsesNativeMobileSearchAndMapsMidToPlaybackIdentity() throws Exception {
    NativeMusicCatalog catalog = new NativeMusicCatalog((url, method, query, headers, body) -> {
      assertEquals("POST", method);
      assertEquals("https://y.qq.com/", headers.get("Referer"));
      JSONObject req = body.getJSONObject("req");
      assertEquals("DoSearchForQQMusicMobile", req.getString("method"));
      assertEquals(2, req.getJSONObject("param").getInt("page_num"));
      assertTrue(req.getJSONObject("param").getString("searchid").matches("[0-9]{10}"));
      return Json.obj("req", Json.obj("code", 0, "data", Json.obj("body", Json.obj("item_song", new JSONArray().put(qqSong("mid1"))))));
    });
    JSONObject song = catalog.search("tencent", "Song", 2).get(0);
    assertEquals("tencent:mid1", song.getString("id"));
    assertEquals(245000, song.getLong("duration"));
    assertTrue(song.getString("poster").contains("album1.jpg"));
    assertTrue(song.getBoolean("music"));
  }

  private JSONObject qqSong(String id) {
    return Json.obj("mid", id, "title", "Song", "interval", 245, "album", Json.obj("mid", "album1", "name", "Album"),
        "singer", new JSONArray().put(Json.obj("name", "Singer")));
  }

  @Test public void qqCollectionsRouteChartAndPaginateAlbumsWithoutRepeatingForever() throws Exception {
    int[] calls = {0};
    NativeMusicCatalog catalog = new NativeMusicCatalog((url, method, query, headers, body) -> {
      calls[0]++;
      JSONObject req = body.getJSONObject("req");
      if (req.getString("method").equals("GetDetail")) {
        assertEquals(26, req.getJSONObject("param").getInt("topId"));
        return Json.obj("req", Json.obj("code", 0, "data", Json.obj("songInfoList", new JSONArray().put(qqSong("chart1")))));
      }
      return Json.obj("req", Json.obj("code", 0, "data", Json.obj("totalNum", 1000, "songList", new JSONArray().put(Json.obj("songInfo", qqSong("albumSong"))))));
    });
    assertEquals("chart1", catalog.tracks(Json.obj("collectionId", "toplist:26")).get(0).getString("songId"));
    assertEquals(1, catalog.tracks(Json.obj("collectionId", "album1", "kind", "album")).size());
    assertEquals(3, calls[0]);
    NativeMusicCatalog bad = new NativeMusicCatalog((url, method, query, headers, body) -> Json.obj("req", Json.obj("code", 2001)));
    assertThrows(IOException.class, () -> bad.search("tencent", "Song", 1));
  }

  @Test public void crossCatalogLyricsRejectDifferentArtistLiveVersionAndMissingDuration() {
    JSONObject original = Json.obj("name", "蓝莲花", "artist", "许巍", "duration", 270000);
    assertTrue(MusicRecordingMatch.same(original, Json.obj("name", "蓝莲花", "artist", "许巍", "duration", 270524)));
    assertFalse(MusicRecordingMatch.same(original, Json.obj("name", "蓝莲花 (Live)", "artist", "许巍", "duration", 270524)));
    assertFalse(MusicRecordingMatch.same(original, Json.obj("name", "蓝莲花", "artist", "翻唱歌手", "duration", 270524)));
    assertFalse(MusicRecordingMatch.same(original, Json.obj("name", "蓝莲花", "artist", "许巍")));
  }
}
