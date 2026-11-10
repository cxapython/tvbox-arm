package com.yingxia.tv.core;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservatively removes short, foreign-family ad runs from finished HLS media playlists. */
public final class AdSkip {
  private static final double LONGEST_RUN = 60.0;
  private static final double LARGEST_SHARE = 0.10;
  private static final int MOST_RUNS = 4;
  private static final double SHORTEST_VIDEO = 180.0;
  private static final Pattern NUMBERS_OR_HASHES = Pattern.compile("[0-9a-fA-F]{6,}|[0-9]+");

  public static final class Result {
    public final String text;
    public final int runs;
    public final double seconds;
    public final String host;

    private Result(String text, int runs, double seconds, String host) {
      this.text = text;
      this.runs = runs;
      this.seconds = seconds;
      this.host = host;
    }
  }

  private static final class Segment {
    final List<String> tags;
    final String address;
    final URI url;
    final double duration;
    final boolean discontinuity;

    Segment(List<String> tags, String address, URI url, double duration, boolean discontinuity) {
      this.tags = tags;
      this.address = address;
      this.url = url;
      this.duration = duration;
      this.discontinuity = discontinuity;
    }
  }

  private static final class Run {
    final int start;
    final int end;

    Run(int start, int end) {
      this.start = start;
      this.end = end;
    }
  }

  private AdSkip() {}

  /** Returns the cleaned playlist, or null when the narrow ad rule does not apply. */
  public static Result clean(String text, String origin) {
    if (text == null || origin == null) return null;
    final URI base;
    try {
      base = URI.create(origin);
    } catch (IllegalArgumentException invalid) {
      return null;
    }
    if (!text.contains("#EXT-X-ENDLIST") || text.contains("#EXT-X-STREAM-INF")) return null;

    List<String> header = new ArrayList<>(), tail, pending = new ArrayList<>();
    List<Segment> segments = new ArrayList<>();
    double duration = 0.0;
    boolean discontinuity = false, started = false;
    for (String line : text.split("\\R", -1)) {
      String value = line.trim();
      if (value.isEmpty()) continue;
      if (value.equals("#EXT-X-DISCONTINUITY")) {
        discontinuity = true;
        continue;
      }
      if (value.startsWith("#")) {
        if (value.startsWith("#EXTINF:")) {
          started = true;
          duration = duration(value.substring(8));
        }
        if (started) pending.add(line);
        else header.add(line);
        continue;
      }
      if (!started) return null;
      final URI url;
      try {
        url = base.resolve(value);
      } catch (IllegalArgumentException invalid) {
        return null;
      }
      segments.add(new Segment(new ArrayList<>(pending), line, url, duration, discontinuity));
      pending.clear();
      discontinuity = false;
      duration = 0.0;
    }
    tail = pending;

    double total = duration(segments, 0, segments.size());
    if (segments.size() <= 1 || total < SHORTEST_VIDEO || !hasDiscontinuity(segments)) return null;

    Map<String, Double> time = new LinkedHashMap<>();
    for (Segment segment : segments) add(time, family(segment.url), segment.duration);
    String film = largest(time);
    if (film == null) return null;

    List<Run> runs = new ArrayList<>();
    int start = 0;
    for (int index = 1; index < segments.size(); index++) {
      if (segments.get(index).discontinuity) {
        runs.add(new Run(start, index));
        start = index;
      }
    }
    runs.add(new Run(start, segments.size()));
    if (runs.size() <= 1) return null;

    List<Run> removed = new ArrayList<>();
    for (Run run : runs) {
      double length = duration(segments, run.start, run.end);
      Map<String, Double> share = new LinkedHashMap<>();
      for (int index = run.start; index < run.end; index++) {
        Segment segment = segments.get(index);
        add(share, family(segment.url), segment.duration);
      }
      String own = largest(share);
      if (own != null && !own.equals(film) && length <= LONGEST_RUN) removed.add(run);
    }
    double seconds = 0.0;
    for (Run run : removed) seconds += duration(segments, run.start, run.end);
    if (removed.isEmpty() || removed.size() > MOST_RUNS || removed.size() >= runs.size()
        || seconds > total * LARGEST_SHARE) return null;

    for (Run run : removed) {
      for (int index = run.start; index < run.end; index++) {
        for (String tag : segments.get(index).tags) {
          if (!tag.startsWith("#EXTINF:")) return null;
        }
      }
    }

    Set<Integer> dropped = new HashSet<>();
    for (Run run : removed) {
      for (int index = run.start; index < run.end; index++) dropped.add(index);
    }
    List<String> lines = new ArrayList<>(header);
    boolean first = true;
    String host = "-";
    for (int index = 0; index < segments.size(); index++) {
      if (dropped.contains(index)) continue;
      Segment segment = segments.get(index);
      if (segment.discontinuity && !first) lines.add("#EXT-X-DISCONTINUITY");
      lines.addAll(segment.tags);
      lines.add(segment.address);
      first = false;
    }
    lines.addAll(tail);
    if (!removed.isEmpty()) {
      String value = segments.get(removed.get(0).start).url.getHost();
      host = value == null ? "-" : value;
    }
    return new Result(String.join("\n", lines) + "\n", removed.size(), seconds, host);
  }

  private static double duration(String extinf) {
    int comma = extinf.indexOf(',');
    String value = (comma < 0 ? extinf : extinf.substring(0, comma)).trim();
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException invalid) {
      return 0.0;
    }
  }

  private static double duration(List<Segment> segments, int start, int end) {
    double duration = 0.0;
    for (int index = start; index < end; index++) duration += segments.get(index).duration;
    return duration;
  }

  private static boolean hasDiscontinuity(List<Segment> segments) {
    for (Segment segment : segments) if (segment.discontinuity) return true;
    return false;
  }

  private static void add(Map<String, Double> durations, String family, double seconds) {
    durations.put(family, durations.getOrDefault(family, 0.0) + seconds);
  }

  private static String largest(Map<String, Double> durations) {
    String largest = null;
    double seconds = 0.0;
    for (Map.Entry<String, Double> entry : durations.entrySet()) {
      if (largest == null || entry.getValue() > seconds) {
        largest = entry.getKey();
        seconds = entry.getValue();
      }
    }
    return largest;
  }

  /** Host, folder, and file name with decimal numbers and hash-like runs replaced by '#'. */
  static String family(URI url) {
    String path = url.getPath();
    if (path == null) path = "";
    int slash = path.lastIndexOf('/');
    String directory = slash < 0 ? "" : path.substring(0, slash + 1);
    String name = slash < 0 ? path : path.substring(slash + 1);
    String host = url.getHost();
    return (host == null ? "" : host) + "|" + directory + "|"
        + NUMBERS_OR_HASHES.matcher(name).replaceAll("#");
  }
}
