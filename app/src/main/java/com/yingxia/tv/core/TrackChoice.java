package com.yingxia.tv.core;

import java.util.List;
import java.util.Locale;

/** Track indices are item-local; remember labels/languages, never a previous item's index. */
public final class TrackChoice {
  public final String name, language;
  public TrackChoice(String name, String language) {
    this.name = name == null ? "" : name.trim();
    this.language = language == null || language.equalsIgnoreCase("und") ? "" : language.trim().toLowerCase(Locale.ROOT);
  }
  public int find(List<TrackChoice> candidates) {
    if (name.isEmpty() && language.isEmpty()) return -1;
    for (int i = 0; i < candidates.size(); i++) {
      TrackChoice candidate = candidates.get(i);
      if (name.equals(candidate.name) && language.equals(candidate.language)) return i;
    }
    if (!language.isEmpty()) for (int i = 0; i < candidates.size(); i++)
      if (language.equals(candidates.get(i).language)) return i;
    return -1;
  }
}
