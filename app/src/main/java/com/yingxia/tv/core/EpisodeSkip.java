package com.yingxia.tv.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Source-provided opening and ending credits, and their once-per-playback skip state. */
public final class EpisodeSkip {
  public enum Kind {
    INTRO,
    OUTRO
  }

  /** A half-open interval [start, end), in seconds. */
  public static final class Segment {
    public final double start;
    public final double end;
    public final Kind kind;

    public Segment(double start, double end, Kind kind) {
      this.start = start;
      this.end = end;
      this.kind = kind;
    }
  }

  /** State for one playback pass; construct a new Pass when the episode or play result changes. */
  public static final class Pass {
    private final List<Segment> segments;
    private final boolean[] handled;

    public Pass(List<Segment> segments) {
      this.segments = segments == null
          ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(segments));
      handled = new boolean[this.segments.size()];
    }

    /**
     * Returns a seek target while playing inside an unhandled segment. Intros seek to their end;
     * outros seek to just before the duration so the caller can advance to the next episode.
     * Rewinding does not retrigger a handled segment. Live/unknown durations and invalid intervals
     * return null.
     */
    public Double target(double time, double duration, boolean playing) {
      if (!playing || !Double.isFinite(time) || !Double.isFinite(duration)
          || time < 0 || duration <= 0 || time > duration) return null;
      for (int i = 0; i < segments.size(); i++) {
        Segment segment = segments.get(i);
        if (handled[i] || !valid(segment, duration)
            || time < segment.start || time >= segment.end) continue;
        handled[i] = true;
        if (segment.kind == Kind.INTRO) return segment.end;
        return Math.max(time, Math.max(0, duration - 0.05));
      }
      return null;
    }

    private static boolean valid(Segment segment, double duration) {
      return segment != null && segment.kind != null
          && Double.isFinite(segment.start) && Double.isFinite(segment.end)
          && segment.start >= 0 && segment.end > segment.start && segment.end <= duration;
    }
  }

  private EpisodeSkip() {}

  /**
   * Parses a skips JSONArray, its string form, or a play result containing "skips". Numeric
   * strings are accepted; the segment kind may be in "type" (intro/outro) or its alias "kind".
   */
  public static List<Segment> parse(Object value) {
    if (value instanceof JSONObject) value = ((JSONObject) value).opt("skips");
    if (value instanceof String) {
      try {
        value = new JSONArray((String) value);
      } catch (JSONException invalid) {
        return Collections.emptyList();
      }
    }
    if (!(value instanceof JSONArray)) return Collections.emptyList();
    JSONArray array = (JSONArray) value;
    List<Segment> out = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      JSONObject item = array.optJSONObject(i);
      if (item == null) continue;
      double start = number(item.opt("start")), end = number(item.opt("end"));
      Kind kind = kind(item);
      if (!Double.isFinite(start) || !Double.isFinite(end) || start < 0 || end <= start
          || kind == null) continue;
      out.add(new Segment(start, end, kind));
    }
    return Collections.unmodifiableList(out);
  }

  private static double number(Object value) {
    if (value instanceof Number) return ((Number) value).doubleValue();
    if (value instanceof String) {
      try {
        return Double.parseDouble(((String) value).trim());
      } catch (NumberFormatException invalid) {
        return Double.NaN;
      }
    }
    return Double.NaN;
  }

  private static Kind kind(JSONObject item) {
    Object value = item.opt("type");
    if (!(value instanceof String)) value = item.opt("kind");
    if (!(value instanceof String)) return null;
    switch (((String) value).trim().toLowerCase(Locale.ROOT)) {
      case "intro":
        return Kind.INTRO;
      case "outro":
        return Kind.OUTRO;
      default:
        return null;
    }
  }
}
