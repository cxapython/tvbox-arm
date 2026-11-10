package com.yingxia.tv.core;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public final class SubscriptionLinkTest {
  @Test
  public void parsesOriginalImportHostAndName() throws Exception {
    JSONObject link = SubscriptionLink.parse(
        "yingxia://import?url=https%3A%2F%2Fexample.com%2Fa.json%3Fx%3D1%26y%3D2&name=My%20NAS");
    assertEquals("https://example.com/a.json?x=1&y=2", link.getString("url"));
    assertEquals("My NAS", link.getString("name"));
  }

  @Test
  public void rejectsLookalikeHostsPathsAndMissingUrls() {
    String[] invalid = {
      "yingxia://import.evil.example?url=https%3A%2F%2Fexample.com",
      "yingxia://other?url=https%3A%2F%2Fexample.com",
      "yingxia:/import?url=https%3A%2F%2Fexample.com",
      "https://import?url=https%3A%2F%2Fexample.com",
      "yingxia://import?name=empty"
    };
    for (String value : invalid) {
      try {
        SubscriptionLink.parse(value);
        fail("accepted " + value);
      } catch (Exception expected) {
        assertTrue(expected.getMessage().length() > 0);
      }
    }
  }
}
