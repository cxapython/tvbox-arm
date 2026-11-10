package com.yingxia.tv.core;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONObject;
import org.junit.Test;

public class CategoryFilterTest {
  @Test
  public void sortedJSONOmitsEmptyChoicesAndNoChoiceHasNoParameters() throws Exception {
    JSONObject choices =
        new JSONObject().put("year", "2026").put("area", "").put("class", "喜剧");

    JSONObject parameters = Catalog.filterParameters(Json.obj("type", 1), choices);
    assertNotNull(parameters);
    assertEquals("{\"class\":\"喜剧\",\"year\":\"2026\"}", parameters.getString("f"));
    assertEquals(1, parameters.length());

    assertNull(Catalog.filterParameters(Json.obj("type", 1), new JSONObject()));
    assertNull(Catalog.filterParameters(Json.obj("type", 1), Json.obj("area", "")));
    assertNull(Catalog.filterParameters(Json.obj("type", 1), null));
  }

  @Test
  public void typeFourUsesBase64Ext() throws Exception {
    JSONObject choices = new JSONObject().put("sort", "hot").put("area", "大陆");
    JSONObject parameters = Catalog.filterParameters(Json.obj("type", 4), choices);

    String json =
        new String(
            Base64.getDecoder().decode(parameters.getString("ext")), StandardCharsets.UTF_8);
    assertEquals("{\"area\":\"大陆\",\"sort\":\"hot\"}", json);
    assertFalse(parameters.has("f"));
    assertFalse(parameters.has("filters"));
  }

  @Test
  public void typeFourFilterExtIsNotOverwrittenBySiteExtension() throws Exception {
    JSONObject site = Json.obj("type", 4, "ext", "https://config.example/spider.js");
    java.util.Map<String, String> parameters = Catalog.apiParameters(site,
        Json.obj("t", "movies", "ext", "ZmlsdGVyKys="));
    assertEquals("ZmlsdGVyKys=", parameters.get("ext"));
    assertEquals("https://config.example/spider.js", parameters.get("extend"));
    assertEquals("detail", parameters.get("ac"));
    assertEquals("true", parameters.get("filter"));
  }

  @Test
  public void searchesAlwaysDisableQuickMode() {
    java.util.Map<String, String> parameters =
        Catalog.apiParameters(Json.obj("type", 1), Json.obj("wd", "仁医", "pg", "1"));
    assertEquals("false", parameters.get("quick"));
    assertEquals("detail", parameters.get("ac"));
    assertEquals("仁医", parameters.get("wd"));
  }

  @Test
  public void cmsAndPluginHostsUseTheirOwnParameterNames() throws Exception {
    JSONObject choices = Json.obj("year", "2025");
    for (Object type : new Object[] {0, 1, "0", "1"}) {
      JSONObject parameters = Catalog.filterParameters(Json.obj("type", type), choices);
      assertEquals("{\"year\":\"2025\"}", parameters.getString("f"));
      assertEquals(1, parameters.length());
    }

    JSONObject plugin = Catalog.filterParameters(Json.obj("type", 3), choices);
    assertEquals("{\"year\":\"2025\"}", plugin.getString("filters"));
    assertEquals(1, plugin.length());
  }
}
