package com.yingxia.tv.core;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class MusicResolverTest {
  private static MusicResolver.Provider provider(
      String name, List<String> calls, String result, String failure) {
    return new MusicResolver.Provider() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public JSONObject resolve(JSONObject song, String quality) throws Exception {
        calls.add(name + "@" + quality);
        if (failure != null) throw new IOException(failure);
        return Json.obj("url", result, "upstream", name);
      }
    };
  }

  @Test
  public void failureContinuesInOrderAndKeepsRequestedQuality() throws Exception {
    List<String> calls = new ArrayList<>();
    MusicResolver resolver =
        new MusicResolver(
            Arrays.asList(
                provider("migu", calls, null, "offline"),
                provider("lx", calls, "https://cdn.example/song.flac", null)));
    JSONObject song = Json.obj("songId", "id");
    JSONObject found = resolver.resolve(song, "FLAC");
    assertEquals("https://cdn.example/song.flac", found.getString("url"));
    assertEquals(Arrays.asList("migu@flac", "lx@flac"), calls);
  }

  @Test
  public void allFailuresAreAggregatedWithProviderNames() throws Exception {
    List<String> calls = new ArrayList<>();
    MusicResolver resolver =
        new MusicResolver(
            Arrays.asList(
                provider("vkeys", calls, null, "risk control"),
                provider("lx", calls, null, "not installed")));
    try {
      resolver.resolve(Json.obj(), "128k");
      fail();
    } catch (IOException error) {
      assertTrue(error.getMessage().contains("vkeys: risk control"));
      assertTrue(error.getMessage().contains("lx: not installed"));
      assertTrue(error.getMessage().contains("128"));
    }
    assertEquals(Arrays.asList("vkeys@128", "lx@128"), calls);
  }

  @Test
  public void emptyOrNonHttpAnswersCountAsFailureAndContinue() throws Exception {
    List<String> calls = new ArrayList<>();
    MusicResolver resolver =
        new MusicResolver(
            Arrays.asList(
                provider("bad", calls, "file:///private/song", null),
                provider("good", calls, "https://cdn.example/good.mp3", null)));
    assertEquals(
        "good", resolver.resolve(Json.obj(), "320").getString("upstream"));
  }

  @Test
  public void qualityMappingsMatchProviders() {
    assertEquals("999", MusicResolver.gdstudioBitrate("lossless"));
    assertEquals("320", MusicResolver.gdstudioBitrate("high"));
    assertEquals("128", MusicResolver.gdstudioBitrate("standard"));
    assertEquals("10", MusicResolver.vkeysCode("flac"));
    assertEquals("8", MusicResolver.vkeysCode("320"));
    assertEquals("6", MusicResolver.vkeysCode("128"));
    assertEquals(Arrays.asList("lossless", "exhigh"), MusicResolver.nianxinLevels("flac"));
    assertEquals(Arrays.asList("exhigh", "standard"), MusicResolver.nianxinLevels("320"));
    assertEquals(Arrays.asList("standard"), MusicResolver.nianxinLevels("128"));
  }

  @Test
  public void lxQualityHonorsDeclarations() {
    assertEquals("flac", MusicResolver.lxQuality(new JSONArray().put("320k").put("flac"), "flac"));
    assertEquals("320k", MusicResolver.lxQuality(new JSONArray().put("flac").put("320k"), "320"));
    assertEquals("128k", MusicResolver.lxQuality(new JSONArray().put("128k"), "320"));
    assertNull(MusicResolver.lxQuality(new JSONArray().put("320k"), "128"));
  }
}
