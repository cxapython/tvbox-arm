package com.yingxia.tv.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;

/** Accumulated global-search answers retained when details are opened and closed. */
public final class SearchSnapshot {
  public static final class Result {
    public final JSONObject site;
    public final List<JSONObject> videos;

    Result(JSONObject site, List<JSONObject> videos) {
      this.site = site;
      this.videos = videos;
    }
  }

  private final String query;
  private final int total;
  private final List<Result> results = new ArrayList<>();
  private int completed, failures;

  public SearchSnapshot(String query, int total, int initialFailures) {
    this.query = query;
    this.total = total;
    failures = initialFailures;
  }

  public void success(JSONObject site, List<JSONObject> videos) {
    completed++;
    if (!videos.isEmpty()) results.add(new Result(site, videos));
  }

  public void failure() {
    completed++;
    failures++;
  }

  public String query() {
    return query;
  }

  public int completed() {
    return completed;
  }

  public int total() {
    return total;
  }

  public int failures() {
    return failures;
  }

  public List<Result> results() {
    return Collections.unmodifiableList(results);
  }

  public String status() {
    return "已搜索 "
        + completed
        + " / "
        + total
        + " 个点播源"
        + (failures > 0 ? " · " + failures + " 个暂不可用" : "");
  }
}
