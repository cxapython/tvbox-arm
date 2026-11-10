package com.yingxia.tv.core;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public class SourceSupportTest {
  private final SourceSupport support = new SourceSupport(Json.obj("classes", Json.obj(
      "AppgzGuard", Json.obj("script", "Guazi.js"),
      "LibvioGuard", Json.obj("script", "Libvio.js"))),
      Json.obj("scripts", Json.obj("Libvio.js", "HTTP 403 地区限制")));

  @Test public void onlyAdaptedJarSourcesAreOffered() {
    assertNull(support.reason(Json.obj("type", 3, "api", "csp_AppgzGuard")));
    assertNotNull(support.reason(Json.obj("type", 3, "api", "csp_NewCzGuard")));
    assertNotNull(support.reason(Json.obj("type", 3, "api", "csp_HmysGuard")));
    assertNotNull(support.reason(Json.obj("type", 3, "api", "csp_MyDriveGuard")));
  }

  @Test public void policyAlsoAppliesToDirectAssetPaths() {
    assertEquals("HTTP 403 地区限制", support.reason(Json.obj("type", 3, "api", "csp_LibvioGuard")));
    assertEquals("HTTP 403 地区限制", support.reason(Json.obj("type", 3, "api", "assets://sites/Libvio.js")));
  }

  @Test public void jsonXmlAndJavaScriptRemainAvailable() {
    for (int type : new int[] {0, 1, 4})
      assertNull(support.reason(Json.obj("type", type, "api", "https://example.test/api")));
    assertNull(support.reason(Json.obj("type", 3, "api", "./rule.js?version=1")));
    assertNull(support.reason(Json.obj("type", 3, "api", "csp_AppgzGuard", "ext", "{\"site\":\"a.js\"}")));
  }

  @Test public void unsupportedRuntimesAndLoadersStayHidden() {
    assertNotNull(support.reason(Json.obj("type", 3, "api", "https://example.test/rule.PY?v=2")));
    assertNotNull(support.reason(Json.obj("type", 2, "api", "https://example.test/api")));
    assertNotNull(support.reason(Json.obj("type", 3, "api", "csp_AppgzGuard", "ext", "./rule.js?v=1")));
    assertNotNull(support.reason(Json.obj("type", 3, "api", "csp_AppgzGuard", "ext", "./rule.py")));
  }
}
