package com.yingxia.tv.core;

import java.util.ArrayDeque;
import java.util.Deque;

/** Back navigation stores page snapshots, never closures that repeat network requests. */
public final class NavigationHistory {
  private final Deque<NavigationSnapshot> pages = new ArrayDeque<>();

  public void push(NavigationSnapshot page) {
    if (page != null) pages.push(page);
  }

  public NavigationSnapshot pop() {
    return pages.isEmpty() ? null : pages.pop();
  }

  public boolean isEmpty() {
    return pages.isEmpty();
  }

  public void clear() {
    pages.clear();
  }

  public int size() {
    return pages.size();
  }
}
