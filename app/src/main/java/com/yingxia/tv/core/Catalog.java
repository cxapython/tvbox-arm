package com.yingxia.tv.core;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import org.json.*;

public final class Catalog {
  // HTMLText: plugins pass through site HTML, often entity-escaped twice ("&amp;nbsp;"), so decode
  // until stable (bounded) before stripping tags. Ported from the reference Catalog.swift.
  private static final Pattern ENTITY =
      Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});?");
  private static final Map<String, String> NAMED_ENTITIES = new HashMap<>();

  static {
    NAMED_ENTITIES.put("amp", "&");
    NAMED_ENTITIES.put("lt", "<");
    NAMED_ENTITIES.put("gt", ">");
    NAMED_ENTITIES.put("quot", "\"");
    NAMED_ENTITIES.put("apos", "'");
    NAMED_ENTITIES.put("nbsp", " ");
    NAMED_ENTITIES.put("ldquo", "“");
    NAMED_ENTITIES.put("rdquo", "”");
    NAMED_ENTITIES.put("lsquo", "‘");
    NAMED_ENTITIES.put("rsquo", "’");
    NAMED_ENTITIES.put("hellip", "…");
    NAMED_ENTITIES.put("mdash", "—");
    NAMED_ENTITIES.put("ndash", "–");
    NAMED_ENTITIES.put("middot", "·");
  }

  private static String decodeEntities(String text) {
    if (!text.contains("&")) return text;
    Matcher m = ENTITY.matcher(text);
    StringBuilder out = new StringBuilder();
    int cursor = 0;
    while (m.find()) {
      String body = m.group(1), replacement = null;
      if (body.startsWith("#x") || body.startsWith("#X")) {
        try {
          replacement = new String(Character.toChars(Integer.parseInt(body.substring(2), 16)));
        } catch (IllegalArgumentException ignored) {
        }
      } else if (body.startsWith("#")) {
        try {
          replacement = new String(Character.toChars(Integer.parseInt(body.substring(1))));
        } catch (IllegalArgumentException ignored) {
        }
      } else replacement = NAMED_ENTITIES.get(body.toLowerCase(Locale.ROOT));
      if (replacement == null) continue;
      out.append(text, cursor, m.start()).append(replacement);
      cursor = m.end();
    }
    return out.append(text.substring(cursor)).toString();
  }

  private static String decodeStable(String text) {
    for (int i = 0; i < 3; i++) {
      String decoded = decodeEntities(text);
      if (decoded.equals(text)) break;
      text = decoded;
    }
    return text;
  }

  /// A title or name: entities decoded, spaces tidied, and nothing else touched, so a name that
  /// reads like markup after decoding ("&lt;3") keeps its text.
  public static String name(String text) {
    return decodeStable(text == null ? "" : text)
        .replaceAll("[\\s\\u00A0\\u3000]+", " ")
        .trim();
  }

  public static String plain(String html) {
    return decodeStable(html == null ? "" : html)
        .replaceAll("(?i)<br\\s*/?>|</p>", "\n")
        .replaceAll("<[^>]+>", "")
        .replaceAll("[ \t\\u00A0\\u3000]+", " ")
        .replaceAll(" *\n\\s*", "\n")
        .trim();
  }

  /// A whole line that is only a 线路 label or a quality word; anchored at both ends so plot text
  /// never matches.
  private static final Pattern LAYOUT_LABEL =
      Pattern.compile(
          "^\\s*(?:(?:播放|高清|蓝光|超清|备用|高速)?\\s*线路[一二三四五六七八九十0-9]*[:：]?|超清|高清|蓝光|标清|流畅|4k|1080p|720p|2160p)\\s*$",
          Pattern.CASE_INSENSITIVE);

  /// A synopsis (`vod_content`): `plain`, minus the lines that hold nothing but a 线路 label or a
  /// quality word, which aggregator pages leave behind as layout.
  public static String synopsis(String html) {
    String text = plain(html);
    StringBuilder out = new StringBuilder();
    for (String line : text.split("\n", -1)) {
      if (line.length() <= 12 && LAYOUT_LABEL.matcher(line).find()) continue;
      if (out.length() > 0) out.append('\n');
      out.append(line);
    }
    return out.toString();
  }

  public static String clean(String s) {
    return synopsis(s);
  }

  /** Index/ranking sources name titles to search globally; they are not playback catalogs. */
  public static boolean isIndex(JSONObject source) {
    return source != null && source.optInt("indexs") == 1;
  }

  public static List<JSONObject> videos(JSONObject response) {
    List<JSONObject> out = new ArrayList<>();
    for (JSONObject v : Json.objects(Json.array(response.opt("list")))) {
      if (v.optString("vod_id").isEmpty()) continue;
      JSONObject item = Json.copy(v);
      try {
        item.put("vod_name", name(v.optString("vod_name", v.optString("vod_id"))));
        item.put("vod_content", synopsis(v.optString("vod_content")));
      } catch (JSONException broken) {
        throw new IllegalStateException("无法清洗影片字段", broken);
      }
      out.add(item);
    }
    return out;
  }

  public static List<JSONObject> episodes(JSONObject video) {
    // TVBox's two-dollar line separator; accept the legacy three-dollar form emitted by older
    // bundled spiders too. A single dollar inside a URL is never a line separator.
    String[] flags = video.optString("vod_play_from").split("\\$\\$(?:\\$)?", -1),
        groups = video.optString("vod_play_url").split("\\$\\$(?:\\$)?", -1);
    List<List<JSONObject>> byGroup = new ArrayList<>();
    for (int g = 0; g < groups.length; g++) {
      String flag = g < flags.length ? flags[g] : "";
      List<JSONObject> line = new ArrayList<>();
      String[] items = groups[g].split("#", -1);
      for (int i = 0; i < items.length; i++) {
        String item = items[i];
        if (item.isEmpty()) continue;
        // Split on the first '$' like Swift's split(separator:maxSplits:omittingEmptySubsequences:):
        // a '$' inside the URL is kept, and an empty half leaves the other as a "播放" address.
        int at = item.indexOf('$');
        String head = at < 0 ? item : item.substring(0, at),
            tail = at < 0 ? null : item.substring(at + 1),
            label,
            url;
        if (at >= 0 && !head.isEmpty() && !tail.isEmpty()) {
          label = name(head);
          url = tail;
        } else if (!head.isEmpty()) {
          label = "播放";
          url = head;
        } else if (tail != null && !tail.isEmpty()) {
          label = "播放";
          url = tail;
        } else {
          label = "播放";
          url = item;
        }
        line.add(Json.obj("id", g + "-" + i, "name", label, "url", url, "flag", flag));
      }
      byGroup.add(line);
    }
    // Collection APIs often list a web player group before the m3u8 group of the same episodes, so
    // groups of direct media come first and are what playback starts with.
    List<Integer> order = new ArrayList<>();
    for (int g = 0; g < byGroup.size(); g++) order.add(g);
    order.sort(
        (a, b) -> {
          boolean pa =
                  !byGroup.get(a).isEmpty() && direct(byGroup.get(a).get(0).optString("url")),
              pb = !byGroup.get(b).isEmpty() && direct(byGroup.get(b).get(0).optString("url"));
          return pa != pb ? (pa ? -1 : 1) : Integer.compare(a, b);
        });
    List<JSONObject> out = new ArrayList<>();
    for (int g : order) out.addAll(byGroup.get(g));
    Map<String, String> labels = episodeLabels(out);
    for (JSONObject e : out) {
      String shown = labels.get(e.optString("id"));
      if (shown != null)
        try {
          e.put("name", shown);
        } catch (JSONException broken) {
          throw new IllegalStateException("无法压缩剧集标签", broken);
        }
    }
    return out;
  }

  /// The label of every episode on a line that gets labels, by episode id; lines that keep their
  /// names are left out. Ported from the reference EpisodeLabels.swift (#318).
  public static Map<String, String> episodeLabels(List<JSONObject> episodes) {
    Map<String, List<JSONObject>> lines = new LinkedHashMap<>();
    for (JSONObject e : episodes)
      lines.computeIfAbsent(e.optString("flag"), k -> new ArrayList<>()).add(e);
    Map<String, String> result = new HashMap<>();
    for (List<JSONObject> line : lines.values()) {
      List<String> names = new ArrayList<>();
      for (JSONObject e : line) names.add(e.optString("name"));
      List<String> shown = lineLabels(names);
      if (shown == null) continue;
      for (int i = 0; i < line.size(); i++) result.put(line.get(i).optString("id"), shown.get(i));
    }
    return result;
  }

  private static final Pattern SIZE_TAG =
      Pattern.compile("^\\s*\\[([\\d.]+\\s*[kmgt]?b)\\]\\s*", Pattern.CASE_INSENSITIVE);
  private static final Pattern FILE_EXTENSION =
      Pattern.compile("\\.(?:mkv|mp4|m4v|ts|avi|rmvb|flv|mov|wmv|iso|webm|m2ts)$",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern SHARE_TAG = Pattern.compile("\\s*【[^【】]*】\\s*$");
  private static final Pattern SEASON_EPISODE =
      Pattern.compile("(?<![a-z0-9])s(\\d{1,2})[ ._-]?ep?(\\d{1,4})(?!\\d)",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern CHINESE_EPISODE =
      Pattern.compile("第\\s*([0-9]{1,4}|[零〇一二两三四五六七八九十百]+)\\s*[集话話回]");
  private static final Pattern EPISODE_MARK =
      Pattern.compile("(?<![a-z0-9])ep?[ ._-]?(\\d{1,4})(?![\\dp])", Pattern.CASE_INSENSITIVE);
  private static final Pattern EPISODE_RANGE = Pattern.compile("^(\\d{1,3})-(\\d{1,3})(?!\\d)");
  private static final Pattern TRAILING_NUMBER = Pattern.compile("(?:^|[ ._-])(\\d{1,3})$");
  private static final Pattern VERSION_NUMBER = Pattern.compile("[0-9]\\.[0-9]$");
  private static final Pattern DATE_STAMP =
      Pattern.compile("(?:19|20)[0-9]{2}[._-][0-9]{1,2}[._-][0-9]{1,2}$");
  private static final Pattern QUALITY_TAG =
      Pattern.compile(
          "^(?:2160p|1080p|720p|480p|4k|8k|hdr\\d*|dv|dovi|hevc|h26[45]|x26[45]|60fps|国语|粤语|英语|中字|双语)$",
          Pattern.CASE_INSENSITIVE);

  private static String[] groups(Pattern p, String text) {
    Matcher m = p.matcher(text);
    if (!m.find()) return null;
    String[] out = new String[m.groupCount()];
    for (int i = 0; i < out.length; i++) out[i] = m.group(i + 1) == null ? "" : m.group(i + 1);
    return out;
  }

  /// The name without its size tag ("[1.18GB] "), the share's tag after it ("【太阳鱼】") and file
  /// extension.
  static String episodeNameClean(String name) {
    return FILE_EXTENSION
        .matcher(SHARE_TAG.matcher(SIZE_TAG.matcher(name).replaceAll("")).replaceAll(""))
        .replaceAll("")
        .trim();
  }

  private static boolean isFileName(String name) {
    return groups(FILE_EXTENSION, SHARE_TAG.matcher(name).replaceAll("")) != null
        || episodeNameClean(name).length() > 12;
  }

  private static boolean enough(int count, int total) {
    return count * 10 >= total * 6;
  }

  private static final class Mark {
    final Integer season;
    final int episode;

    Mark(Integer season, int episode) {
      this.season = season;
      this.episode = episode;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Mark
          && Objects.equals(season, ((Mark) o).season)
          && episode == ((Mark) o).episode;
    }

    @Override
    public int hashCode() {
      return Objects.hash(season, episode);
    }
  }

  private static Mark marked(String name) {
    String[] parts = groups(SEASON_EPISODE, name);
    if (parts != null)
      try {
        return new Mark(Integer.valueOf(parts[0]), Integer.parseInt(parts[1]));
      } catch (NumberFormatException ignored) {
      }
    parts = groups(CHINESE_EPISODE, name);
    if (parts != null) {
      Integer episode = chineseNumber(parts[0]);
      if (episode != null) return new Mark(null, episode);
    }
    parts = groups(EPISODE_MARK, name);
    if (parts != null)
      try {
        return new Mark(null, Integer.parseInt(parts[0]));
      } catch (NumberFormatException ignored) {
      }
    return null;
  }

  private static Integer chineseNumber(String text) {
    try {
      return Integer.valueOf(text);
    } catch (NumberFormatException ignored) {
    }
    Map<Character, Integer> digits = new HashMap<>();
    String d = "零〇一二两三四五六七八九";
    int[] v = {0, 0, 1, 2, 2, 3, 4, 5, 6, 7, 8, 9};
    for (int i = 0; i < d.length(); i++) digits.put(d.charAt(i), v[i]);
    int total = 0, digit = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      Integer value = digits.get(c);
      if (value != null) digit = value;
      else {
        total += Math.max(1, digit) * (c == '百' ? 100 : 10);
        digit = 0;
      }
    }
    return total + digit > 0 ? total + digit : null;
  }

  /// The number one name carries itself: "Show.4K.01" gives 1, while a year, a date and a codec's
  /// version are not trusted.
  private static Integer trailingEpisode(String name) {
    if (groups(VERSION_NUMBER, name) != null || groups(DATE_STAMP, name) != null) return null;
    String[] parts = groups(TRAILING_NUMBER, name);
    if (parts == null) return null;
    try {
      int episode = Integer.parseInt(parts[0]);
      return episode > 0 ? episode : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /// Text and digit runs in turn, always starting and ending with text (possibly empty).
  private static List<String> pieces(String text) {
    List<String> parts = new ArrayList<>();
    parts.add("");
    boolean digits = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      boolean isDigit = c < 128 && Character.isDigit(c);
      if (isDigit != digits) {
        parts.add("");
        digits = isDigit;
      }
      parts.set(parts.size() - 1, parts.get(parts.size() - 1) + c);
    }
    if (digits) parts.add("");
    return parts;
  }

  /// For each naming pattern on the line, the number in the one place its names differ, by name
  /// index. Years and dates (2025, 20251220) are not episodes.
  static Map<Integer, Integer> byDifference(List<String> names) {
    List<List<String>> split = new ArrayList<>();
    List<List<String>> skeletons = new ArrayList<>();
    for (String name : names) {
      List<String> parts = pieces(name), skeleton = new ArrayList<>();
      split.add(parts);
      for (int i = 0; i < parts.size(); i += 2) skeleton.add(parts.get(i));
      skeletons.add(skeleton);
    }
    Map<List<String>, Integer> counts = new HashMap<>(), first = new HashMap<>();
    for (int i = 0; i < skeletons.size(); i++) {
      List<String> skeleton = skeletons.get(i);
      counts.merge(skeleton, 1, Integer::sum);
      first.putIfAbsent(skeleton, i);
    }
    List<List<String>> patterns = new ArrayList<>();
    for (Map.Entry<List<String>, Integer> e : counts.entrySet())
      if (e.getValue() >= 2) patterns.add(e.getKey());
    patterns.sort(
        (a, b) ->
            !counts.get(a).equals(counts.get(b))
                ? counts.get(b) - counts.get(a)
                : first.get(a) - first.get(b));
    Map<Integer, Integer> result = new HashMap<>();
    for (List<String> pattern : patterns) {
      List<Integer> group = new ArrayList<>();
      for (int i = 0; i < skeletons.size(); i++)
        if (skeletons.get(i).equals(pattern) && !result.containsKey(i)) group.add(i);
      if (group.size() < 2) continue;
      List<Integer> varying = new ArrayList<>();
      for (int slot = 0; slot < split.get(group.get(0)).size() / 2; slot++) {
        Set<String> seen = new HashSet<>();
        for (int index : group) seen.add(split.get(index).get(2 * slot + 1));
        if (seen.size() > 1) varying.add(slot);
      }
      if (varying.size() != 1) continue;
      int slot = varying.get(0);
      List<String> prefix = split.get(group.get(0)).subList(0, 2 * slot + 1);
      Map<Integer, String> values = new HashMap<>();
      for (int i = 0; i < split.size(); i++) {
        List<String> parts = split.get(i);
        if (!result.containsKey(i)
            && parts.size() > 2 * slot + 1
            && parts.subList(0, 2 * slot + 1).equals(prefix)) values.put(i, parts.get(2 * slot + 1));
      }
      boolean bad = false;
      for (String value : values.values())
        if (value.length() > 4
            || (value.length() == 4 && (value.startsWith("19") || value.startsWith("20")))) bad = true;
      if (bad) continue;
      for (Map.Entry<Integer, String> e : values.entrySet()) {
        try {
          result.put(e.getKey(), Integer.valueOf(e.getValue()));
        } catch (NumberFormatException ignored) {
        }
      }
    }
    return result;
  }

  private static List<String> words(String name) {
    List<String> out = new ArrayList<>();
    for (String w : name.split("[ ._\\-\\[\\]()]+")) if (!w.isEmpty()) out.add(w);
    return out;
  }

  /// For each name, its first quality word (4K, 2160p, 国语…) that some of the other names lack.
  private static List<String> tags(List<String> names) {
    List<List<String>> split = new ArrayList<>();
    Map<String, Integer> counts = new HashMap<>();
    for (String name : names) {
      List<String> words = words(name);
      split.add(words);
      for (String word : new HashSet<>(words)) counts.merge(word, 1, Integer::sum);
    }
    List<String> out = new ArrayList<>();
    for (List<String> words : split) {
      String tag = null;
      for (String word : words)
        if (counts.getOrDefault(word, 0) < names.size()
            && groups(QUALITY_TAG, word) != null) {
          tag = word;
          break;
        }
      out.add(tag);
    }
    return out;
  }

  /// A magnet line of packs, "01-04.2160p….mkv": 第1–4集 · 2160p.
  private static List<String> ranges(List<String> cleaned) {
    Map<Integer, int[]> found = new HashMap<>();
    for (int i = 0; i < cleaned.size(); i++) {
      String[] parts = groups(EPISODE_RANGE, cleaned.get(i));
      if (parts == null) continue;
      try {
        found.put(i, new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])});
      } catch (NumberFormatException ignored) {
      }
    }
    if (!enough(found.size(), cleaned.size())) return null;
    List<String> tags = tags(cleaned);
    List<String> shown = new ArrayList<>();
    for (int i = 0; i < cleaned.size(); i++) {
      int[] range = found.get(i);
      if (range == null) {
        shown.add(cleaned.get(i));
        continue;
      }
      String label = "第" + range[0] + "–" + range[1] + "集";
      shown.add(tags.get(i) != null ? label + " · " + tags.get(i) : label);
    }
    return new HashSet<>(shown).size() == shown.size() ? shown : null;
  }

  /// One line's labels, in order, or null when the line keeps its names. Names without a number
  /// (花絮, 预告) keep theirs, without the size tag and the file extension.
  public static List<String> lineLabels(List<String> names) {
    if (names.isEmpty()) return null;
    int fileNames = 0;
    for (String name : names) if (isFileName(name)) fileNames++;
    if (fileNames * 2 < names.size()) return null;
    List<String> cleaned = new ArrayList<>();
    for (String name : names) cleaned.add(episodeNameClean(name));
    Map<Integer, Mark> found = new HashMap<>();
    for (int i = 0; i < cleaned.size(); i++) {
      Mark mark = marked(cleaned.get(i));
      if (mark != null) found.put(i, mark);
    }
    if (!enough(found.size(), names.size())) {
      Map<Integer, Mark> merged = new HashMap<>();
      for (Map.Entry<Integer, Integer> e : byDifference(cleaned).entrySet())
        merged.put(e.getKey(), new Mark(null, e.getValue()));
      merged.putAll(found);
      if (!enough(merged.size(), names.size())) {
        List<String> shown = ranges(cleaned);
        if (shown != null) return shown;
        if (names.size() != 1) return null;
        Integer episode = trailingEpisode(cleaned.get(0));
        return episode == null
            ? null
            : new ArrayList<>(Collections.singletonList("第" + episode + "集"));
      }
      found = merged;
    }
    // A season only when the line holds more than one.
    Set<Integer> seasons = new HashSet<>();
    for (Mark mark : found.values()) if (mark.season != null) seasons.add(mark.season);
    Map<Integer, Mark> keys = new HashMap<>();
    for (Map.Entry<Integer, Mark> e : found.entrySet())
      keys.put(
          e.getKey(),
          new Mark(seasons.size() > 1 ? e.getValue().season : null, e.getValue().episode));
    Map<Mark, List<Integer>> twins = new HashMap<>();
    for (int i = 0; i < names.size(); i++)
      if (keys.containsKey(i))
        twins.computeIfAbsent(keys.get(i), k -> new ArrayList<>()).add(i);
    List<String> shown = new ArrayList<>();
    for (int i = 0; i < names.size(); i++) {
      Mark key = keys.get(i);
      if (key == null) {
        shown.add(cleaned.get(i));
        continue;
      }
      String label = (key.season != null ? "S" + key.season + " " : "") + "第" + key.episode + "集";
      // Two files of one episode (a 4K and a 1080p copy): the tag that tells them apart, else their
      // order.
      List<Integer> group = twins.get(key);
      if (group != null && group.size() > 1) {
        int place = group.indexOf(i);
        List<String> copies = new ArrayList<>();
        for (int index : group) copies.add(cleaned.get(index));
        List<String> tags = tags(copies);
        if (new HashSet<>(tags).size() == tags.size()) {
          if (tags.get(place) != null) label += " · " + tags.get(place);
        } else label += " (" + (place + 1) + ")";
      }
      shown.add(label);
    }
    return new HashSet<>(shown).size() == shown.size() ? shown : null;
  }

  /// A type-1 home answer's ids of entries with no `vod_pic`; the posters then come from one
  /// `ac=detail` call for those ids (network IO is the caller's).
  public static List<String> idsMissingPosters(JSONArray list) {
    List<String> out = new ArrayList<>();
    for (JSONObject entry : Json.objects(list)) {
      if (!entry.optString("vod_pic").trim().isEmpty()) continue;
      String id = entry.optString("vod_id");
      if (!id.isEmpty()) out.add(id);
    }
    return out;
  }

  /// `list` with each entry's missing `vod_pic` taken from the detail entry with the same `vod_id`.
  public static JSONArray fillingPosters(JSONArray list, JSONArray details) {
    Map<String, Object> posters = new HashMap<>();
    for (JSONObject entry : Json.objects(details)) {
      String id = entry.optString("vod_id");
      Object pic = entry.opt("vod_pic");
      if (!id.isEmpty() && pic != null && !(pic instanceof String && ((String) pic).isEmpty()))
        posters.put(id, pic);
    }
    JSONArray out = new JSONArray();
    for (JSONObject entry : Json.objects(list)) {
      JSONObject fields = entry;
      Object pic = posters.get(entry.optString("vod_id"));
      if (entry.optString("vod_pic").trim().isEmpty()
          && !entry.optString("vod_id").isEmpty()
          && pic != null) {
        fields = Json.copy(entry);
        try {
          fields.put("vod_pic", pic);
        } catch (JSONException broken) {
          throw new IllegalStateException("无法回填影片海报", broken);
        }
      }
      out.put(fields);
    }
    return out;
  }

  /// A category's filter choices as the source reads them, leaving out empty ("全部") values: base64
  /// JSON in `ext` for an HTTP spider (type 4), JSON in `f` for a CMS API (type 0/1), and JSON in
  /// `filters` for a plugin host, which hands the spider its `extend` map. Null when nothing is
  /// chosen.
  public static JSONObject filterParameters(JSONObject site, JSONObject extend) {
    Map<String, String> choices = new TreeMap<>();
    if (extend != null)
      for (String key : Json.keys(extend)) {
        String value = extend.optString(key);
        if (!value.isEmpty()) choices.put(key, value);
      }
    if (choices.isEmpty()) return null;
    StringBuilder json = new StringBuilder("{");
    for (Map.Entry<String, String> e : choices.entrySet()) {
      if (json.length() > 1) json.append(',');
      json.append(JSONObject.quote(e.getKey()))
          .append(':')
          .append(JSONObject.quote(e.getValue()));
    }
    String text = json.append('}').toString();
    int type = -1;
    Object raw = site == null ? null : site.opt("type");
    if (raw instanceof Number) type = ((Number) raw).intValue();
    else if (raw != null)
      try {
        type = Integer.parseInt(String.valueOf(raw).trim());
      } catch (NumberFormatException ignored) {
      }
    if (type == 4)
      return Json.obj(
          "ext",
          java.util.Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)));
    if (type == 0 || type == 1) return Json.obj("f", text);
    return Json.obj("filters", text);
  }

  /** CMS request parameters, keeping a type-4 filter `ext` distinct from the site's `extend`. */
  public static Map<String, String> apiParameters(JSONObject site, JSONObject params) {
    Map<String, String> query = new LinkedHashMap<>();
    for (String key : Json.keys(params)) query.put(key, params.optString(key));
    if (params.has("ids") || params.has("wd") || params.has("t")) query.put("ac", "detail");
    if (params.has("wd")) query.put("quick", "false");
    if (site.optInt("type") == 4) {
      query.put("filter", "true");
      if (site.has("ext")) query.put("extend", site.optString("ext"));
    }
    return query;
  }

  public static boolean direct(String url) {
    return url.matches(
            "(?i).*\\.(m3u8|mp4|mkv|webm|ts|m4v|mp3|flac|m4a|aac|wav|ogg|mpd)(?:[?#].*)?$")
        || url.startsWith("content:")
        || url.startsWith("file:");
  }

  public static Map<String, String> posterHeaders(JSONObject video) {
    String[] p = video.optString("vod_pic").split("@", 2);
    Map<String, String> h = new LinkedHashMap<>();
    if (p.length == 2) h.putAll(Json.headers(Json.object(p[1])));
    return h;
  }
}
