package com.yingxia.tv.core;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class ConfigDirectoryTest {
  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  public void urlsAndStoreHouseBecomeSelectableEntries() throws Exception {
    String json =
        "{"
            + "\"urls\":[{\"name\":\"甲\",\"url\":\"a.json\"},"
            + "{\"name\":\"重复\",\"url\":\"https://example.com/root/dir/a.json\"},"
            + "{\"name\":\"坏地址\",\"url\":\"javascript:alert(1)\"}],"
            + "\"storeHouse\":[{\"sourceName\":\"乙\",\"sourceUrl\":\"../b.json\"}]}";
    List<JSONObject> entries = ConfigCodec.directory(utf8(json), "https://example.com/root/dir/config.json");
    assertEquals(2, entries.size());
    assertEquals("甲", entries.get(0).optString("name"));
    assertEquals("https://example.com/root/dir/a.json", entries.get(0).optString("url"));
    assertEquals("乙", entries.get(1).optString("name"));
    assertEquals("https://example.com/root/b.json", entries.get(1).optString("url"));
  }

  @Test
  public void ordinarySubscriptionIsNotADirectory() throws Exception {
    JSONObject subscription = Json.obj("sites", new org.json.JSONArray());
    assertTrue(ConfigCodec.directory(subscription, "https://example.com/config").isEmpty());
    assertTrue(
        ConfigCodec.directory(utf8("{\"urls\":[]}"), "https://example.com/config").isEmpty());
  }

  @Test
  public void unterminatedBlockCommentAfterDocumentIsIgnored() throws Exception {
    JSONObject decoded = ConfigCodec.decode(utf8("{\"name\":\"ok\"}/* unfinished"));
    assertEquals("ok", decoded.optString("name"));
    assertEquals(
        "/* kept */",
        ConfigCodec.decode(utf8("{\"name\":\"/* kept */\"}/* unfinished")).optString("name"));
  }
}
