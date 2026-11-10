package com.yingxia.tv.core;

import static org.junit.Assert.*;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class CatalogPagesTest {
  private static JSONObject answer(String... ids) {
    JSONArray list = new JSONArray();
    for (String id : ids) list.put(Json.obj("vod_id", id, "vod_name", "影片" + id));
    return Json.obj("list", list, "pagecount", 3);
  }

  @Test
  public void pagesAccumulateAndStopAtTheDeclaredEnd() {
    CatalogPages pages = new CatalogPages();
    List<JSONObject> first = pages.append(answer("1", "2"), 1);
    List<JSONObject> second = pages.append(answer("3"), 2);
    assertEquals(2, first.size());
    assertEquals(1, second.size());
    assertEquals(3, pages.videos().size());
    assertEquals(3, pages.nextPage());
    assertTrue(pages.hasMore());
    pages.append(answer("4"), 3);
    assertEquals(4, pages.videos().size());
    assertFalse(pages.hasMore());
    assertSame(first.get(0), pages.videos().get(0));
  }

  @Test
  public void aSourceIgnoringPageNumbersCannotLoopDuplicateVideosForever() {
    CatalogPages pages = new CatalogPages();
    pages.append(answer("1", "2"), 1);
    List<JSONObject> repeated = pages.append(answer("1", "2"), 2);
    assertTrue(repeated.isEmpty());
    assertEquals(2, pages.videos().size());
    assertFalse(pages.hasMore());
  }

  @Test
  public void indexSourcesRouteTitlesToGlobalSearch() {
    assertTrue(Catalog.isIndex(Json.obj("indexs", 1)));
    assertFalse(Catalog.isIndex(Json.obj("indexs", 0)));
    assertFalse(Catalog.isIndex(new JSONObject()));
  }

  @Test
  public void emptyPageDoesNotInventAnotherPage() {
    CatalogPages pages = new CatalogPages();
    pages.append(Json.obj("list", new JSONArray()), 1);
    assertEquals(1, pages.totalPages());
    assertFalse(pages.hasMore());
  }
}
