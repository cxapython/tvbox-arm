package com.yingxia.tv.core;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/** Accumulated catalog data shared by infinite scrolling and back-stack restoration. */
public final class CatalogPages {
  private final List<JSONObject> answers = new ArrayList<>();
  private final List<JSONObject> videos = new ArrayList<>();
  private final Set<String> seen = new HashSet<>();
  private int page, totalPages;

  public List<JSONObject> append(JSONObject answer, int requestedPage) {
    List<JSONObject> added = new ArrayList<>();
    for (JSONObject video : Catalog.videos(answer))
      if (seen.add(video.optString("vod_id"))) added.add(video);
    answers.add(answer);
    videos.addAll(added);
    page = requestedPage;
    totalPages =
        added.isEmpty()
            ? page
            : Math.max(totalPages, answer.optInt("pagecount", page + 1));
    return added;
  }

  public List<JSONObject> videos() {
    return videos;
  }

  public JSONObject firstAnswer() {
    return answers.isEmpty() ? new JSONObject() : answers.get(0);
  }

  public int page() {
    return page;
  }

  public int nextPage() {
    return page + 1;
  }

  public int totalPages() {
    return totalPages;
  }

  public boolean hasMore() {
    return page > 0 && page < totalPages;
  }
}
