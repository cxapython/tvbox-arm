package com.yingxia.tv.core;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class SearchSnapshotTest {
  @Test
  public void resultsAndFailuresSurviveAsDataForBackNavigation() {
    SearchSnapshot search = new SearchSnapshot("片名", 3, 1);
    search.success(Json.obj("id", "a"), List.of(Json.obj("vod_id", "1")));
    search.success(Json.obj("id", "b"), List.of());
    search.failure();
    assertEquals(3, search.completed());
    assertEquals(2, search.failures());
    assertEquals(1, search.results().size());
    assertEquals("已搜索 3 / 3 个点播源 · 2 个暂不可用", search.status());
  }
}
