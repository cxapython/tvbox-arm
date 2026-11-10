package com.yingxia.tv.core;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class NavigationHistoryTest {
  @Test
  public void backRestoresTheSameLoadedSnapshotWithoutAReplayCallback() {
    NavigationSnapshot page = new NavigationSnapshot(
        "site", "示例", Json.obj("source", Json.obj("key", "a"), "page", 1));
    JSONObject loaded = Json.obj("list", new org.json.JSONArray().put(Json.obj("vod_id", "1")));
    page.data(loaded);
    page.scrollY(240);
    NavigationHistory history = new NavigationHistory();
    history.push(page);

    NavigationSnapshot restored = history.pop();
    assertSame(page, restored);
    assertSame(loaded, restored.data());
    assertEquals("site", restored.route());
    assertEquals(240, restored.scrollY());
    assertTrue(history.isEmpty());
    assertNull(history.pop());
  }

  @Test
  public void argumentsAreSnapshottedWhenThePageIsCreated() throws Exception {
    JSONObject arguments = Json.obj("page", 1);
    NavigationSnapshot page = new NavigationSnapshot("discover", "发现", arguments);
    arguments.put("page", 2);
    assertEquals(1, page.arguments().optInt("page"));
  }
}
