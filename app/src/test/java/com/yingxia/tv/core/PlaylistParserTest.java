package com.yingxia.tv.core;

import static org.junit.Assert.*;

import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

public class PlaylistParserTest {
  @Test
  public void invalidAddressesAreCountedWithoutDroppingValidChannels() {
    String text = "央视,#genre#\n"
        + "坏频道,javascript:test\n"
        + "CCTV1,https://example.com/1.m3u8\n"
        + "另一个坏频道,ftp://example.com/2.m3u8\n";
    PlaylistParser.Result result = PlaylistParser.parseWithStats(
        text, "https://example.com/list.txt", Map.of());

    assertEquals(2, result.getInvalidChannels());
    assertEquals(0, result.getInvalidHeaders());
    assertEquals(1, result.getChannels().size());
    assertEquals("CCTV1", result.getChannels().get(0).optString("name"));
    assertEquals("https://example.com/1.m3u8", result.getChannels().get(0).optString("url"));
    assertEquals(result.getChannels().toString(),
        PlaylistParser.parse(text, "https://example.com/list.txt", Map.of()).toString());
  }

  @Test
  public void malformedPercentEncodedHeaderFallsBackToRawValueAndIsCounted() {
    PlaylistParser.Result result = PlaylistParser.parseWithStats(
        "#EXTM3U\n"
            + "#EXTINF:-1,频道\n"
            + "https://example.com/live.m3u8|Referer=https%ZZraw&X-Good=hello%20world\n",
        "https://example.com/list.m3u",
        Map.of("X-Default", "default"));

    assertEquals(0, result.getInvalidChannels());
    assertEquals(1, result.getInvalidHeaders());
    assertEquals(1, result.getChannels().size());
    JSONObject header = Json.child(result.getChannels().get(0), "header");
    assertEquals("https%ZZraw", header.optString("Referer"));
    assertEquals("hello world", header.optString("X-Good"));
    assertEquals("default", header.optString("X-Default"));
  }
  @Test public void extinfHeadersApplyOnlyToThatEntryAndVlcoptOverrides() {
    String playlist = "#EXTM3U\n#EXTINF:-1 http-user-agent=\"Aptv-UA\" http-referer=\"https://origin/\",One\n"
        + "https://example.com/1.m3u8\n"
        + "#EXTINF:-1 http-user-agent=\"Entry-UA\",Two\n#EXTVLCOPT:http-user-agent=Override-UA\n"
        + "https://example.com/2.m3u8\n#EXTINF:-1,Three\nhttps://example.com/3.m3u8";
    java.util.List<JSONObject> channels = PlaylistParser.parse(playlist, "https://example.com/list", Map.of("User-Agent", "Default"));
    assertEquals("Aptv-UA", Json.child(channels.get(0), "header").optString("User-Agent"));
    assertEquals("https://origin/", Json.child(channels.get(0), "header").optString("Referer"));
    assertEquals("Override-UA", Json.child(channels.get(1), "header").optString("User-Agent"));
    assertEquals("Default", Json.child(channels.get(2), "header").optString("User-Agent"));
    assertFalse(Json.child(channels.get(2), "header").has("Referer"));
  }

}
