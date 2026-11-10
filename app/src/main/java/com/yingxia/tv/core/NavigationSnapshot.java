package com.yingxia.tv.core;

import org.json.JSONObject;

/** A restorable page: typed route arguments plus the data already loaded for it. */
public final class NavigationSnapshot {
  private final String route, title;
  private final JSONObject arguments;
  private Object data;
  private int scrollY;

  public NavigationSnapshot(String route, String title, JSONObject arguments) {
    this.route = route;
    this.title = title;
    this.arguments = Json.copy(arguments == null ? new JSONObject() : arguments);
  }

  public String route() {
    return route;
  }

  public String title() {
    return title;
  }

  public JSONObject arguments() {
    return arguments;
  }

  public Object data() {
    return data;
  }

  public void data(Object value) {
    data = value;
  }

  public int scrollY() {
    return scrollY;
  }

  public void scrollY(int value) {
    scrollY = Math.max(0, value);
  }
}
