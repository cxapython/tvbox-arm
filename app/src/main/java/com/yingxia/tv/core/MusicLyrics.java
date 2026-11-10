package com.yingxia.tv.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure Java LRC parser with repeated timestamps, millisecond offset and optional translation. */
public final class MusicLyrics {
  private static final Pattern STAMP =
      Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
  private static final Pattern TAG = Pattern.compile("^\\[([a-zA-Z#]+):([^]]*)]\\s*$");

  public static final class Line {
    public final long timeMillis;
    public final String text;
    public final String translation;

    Line(long timeMillis, String text, String translation) {
      this.timeMillis = timeMillis;
      this.text = text;
      this.translation = translation;
    }

    public Line withTranslation(String value) {
      return new Line(timeMillis, text, value);
    }
  }

  public final List<Line> lines;
  public final boolean synced;

  private MusicLyrics(List<Line> lines, boolean synced) {
    this.lines = Collections.unmodifiableList(lines);
    this.synced = synced;
  }

  /** Last line whose timestamp is at or before the position, or -1 before the first line. */
  public int indexAt(long positionMillis) {
    if (!synced || lines.isEmpty() || positionMillis < lines.get(0).timeMillis) return -1;
    int low = 0, high = lines.size() - 1;
    while (low < high) {
      int middle = (low + high + 1) >>> 1;
      if (lines.get(middle).timeMillis <= positionMillis) low = middle;
      else high = middle - 1;
    }
    return low;
  }

  public static MusicLyrics parse(String lrc) {
    return parse(lrc, null);
  }

  /** Returns null for empty lyrics; unstamped text is returned as unsynced lines. */
  public static MusicLyrics parse(String lrc, String translation) {
    Parsed original = lines(lrc == null ? "" : lrc);
    if (original.timed.isEmpty()) {
      List<Line> plain = new ArrayList<>();
      for (String text : original.plain) if (!text.isEmpty()) plain.add(new Line(0, text, null));
      return plain.isEmpty() ? null : new MusicLyrics(plain, false);
    }
    List<Timed> ordered = new ArrayList<>(original.timed);
    Collections.sort(ordered, Comparator.comparingLong(item -> item.timeMillis));
    List<Line> result = new ArrayList<>();
    boolean words = false;
    String previousText = null;
    for (Timed item : ordered) {
      if (!item.text.isEmpty()) words = true;
      // Consecutive empty timestamps add no useful karaoke transition.
      if (item.text.isEmpty() && previousText != null && previousText.isEmpty()) continue;
      result.add(new Line(Math.max(0, item.timeMillis - original.offsetMillis), item.text, null));
      previousText = item.text;
    }
    if (!words) return null;
    if (translation != null && !translation.trim().isEmpty()) {
      Parsed translated = lines(translation);
      List<Timed> usable = new ArrayList<>();
      for (Timed item : translated.timed)
        if (!item.text.isEmpty() && !item.text.equals("//")) usable.add(item);
      for (int index = 0; index < result.size(); index++) {
        Line line = result.get(index);
        if (line.text.isEmpty()) continue;
        long originalTime = line.timeMillis + original.offsetMillis;
        Timed nearest = null;
        long distance = Long.MAX_VALUE;
        for (Timed candidate : usable) {
          long candidateTime = candidate.timeMillis - translated.offsetMillis;
          long candidateDistance = Math.abs(candidateTime - originalTime);
          if (candidateDistance < distance) {
            nearest = candidate;
            distance = candidateDistance;
          }
        }
        if (nearest != null && distance < 350 && !nearest.text.equals(line.text))
          result.set(index, line.withTranslation(nearest.text));
      }
    }
    return new MusicLyrics(result, true);
  }

  private static final class Timed {
    final long timeMillis;
    final String text;

    Timed(long timeMillis, String text) {
      this.timeMillis = timeMillis;
      this.text = text;
    }
  }

  private static final class Parsed {
    final List<Timed> timed = new ArrayList<>();
    final List<String> plain = new ArrayList<>();
    long offsetMillis;
  }

  private static Parsed lines(String lrc) {
    Parsed parsed = new Parsed();
    String normalized = lrc.replace("\r\n", "\n").replace('\r', '\n');
    for (String raw : normalized.split("\n", -1)) {
      String line = raw.trim();
      Matcher tag = TAG.matcher(line);
      if (tag.matches()) {
        if (tag.group(1).equalsIgnoreCase("offset")) {
          try {
            parsed.offsetMillis = Long.parseLong(tag.group(2).trim());
          } catch (NumberFormatException ignored) {
            // An invalid metadata tag has no timing effect.
          }
        }
        continue;
      }
      Matcher stamp = STAMP.matcher(line);
      List<Long> times = new ArrayList<>();
      int end = 0;
      while (stamp.find()) {
        if (stamp.start() != end) break;
        long minutes = Long.parseLong(stamp.group(1));
        long seconds = Long.parseLong(stamp.group(2));
        long milliseconds = 0;
        String fraction = stamp.group(3);
        if (fraction != null) {
          milliseconds = Long.parseLong(fraction);
          if (fraction.length() == 1) milliseconds *= 100;
          else if (fraction.length() == 2) milliseconds *= 10;
        }
        times.add((minutes * 60 + seconds) * 1000 + milliseconds);
        end = stamp.end();
      }
      String text = line.substring(end).trim();
      if (times.isEmpty()) parsed.plain.add(text);
      else for (long time : times) parsed.timed.add(new Timed(time, text));
    }
    return parsed;
  }
}
