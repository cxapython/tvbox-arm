package com.yingxia.tv.core;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.json.JSONArray;
import org.json.JSONObject;

/** TVBox's sniff decision table, kept independent of any WebView implementation. */
public final class SniffRules {
  public enum Decision { ALLOW, BLOCK, FOLLOW, VIDEO }

  public static final class Rule {
    public final List<String> hosts;
    public final List<String> regex;
    public final List<String> script;
    public final List<String> exclude;
    public final List<String> click;

    public Rule(List<String> hosts, List<String> regex, List<String> script,
        List<String> exclude, List<String> click) {
      this.hosts = immutable(hosts);
      this.regex = immutable(regex);
      this.script = immutable(script);
      this.exclude = immutable(exclude);
      this.click = immutable(click);
    }

    public static Rule from(JSONObject value) {
      return new Rule(strings(value.opt("hosts")), strings(value.opt("regex")),
          strings(value.opt("script")), strings(value.opt("exclude")), strings(value.opt("click")));
    }
  }

  private static final Pattern PLAYER = Pattern.compile("player.*https?://");
  private static final Pattern SNIFFER = Pattern.compile(
      "https?://[^\\s]{12,}\\.(?:m3u8|mp4|mkv|flv|mp3|m4a|aac|mpd)"
          + "|https?://.*?video/tos[^\\s]*|rtmp:[^\\s]+");

  private SniffRules() {}

  public static List<String> strings(Object value) {
    if (value instanceof String) {
      return ((String) value).isEmpty() ? List.of() : List.of((String) value);
    }
    if (!(value instanceof JSONArray)) return List.of();
    List<String> out = new ArrayList<>();
    JSONArray array = (JSONArray) value;
    for (int i = 0; i < array.length(); i++) {
      Object item = array.opt(i);
      if (item instanceof String && !((String) item).isEmpty()) out.add((String) item);
    }
    return out;
  }

  public static Decision decision(String url, String pageURL, boolean detect, int followedCount,
      List<String> ads, List<Rule> rules) {
    URI parsed = uri(url);
    String scheme = parsed == null ? "" : string(parsed.getScheme());
    if (scheme.equalsIgnoreCase("about") || scheme.equalsIgnoreCase("data")) return Decision.ALLOW;
    String host = parsed == null ? "" : string(parsed.getHost());
    if (host.isEmpty() || (!isCloudflareResource(parsed) && isAd(host, ads))) return Decision.BLOCK;
    if (detect && followedCount < 5 && !url.equals(pageURL) && PLAYER.matcher(url).find())
      return Decision.FOLLOW;
    return isVideo(url, pageURL, detect, rules) ? Decision.VIDEO : Decision.ALLOW;
  }

  public static boolean isVideo(String url, String pageURL, boolean detect, List<Rule> rules) {
    if (!detect && url.equals(pageURL)) return false;
    Rule rule = matchingRule(url, rules);
    if (rule != null) {
      for (String pattern : rule.exclude) if (matches(url, pattern)) return false;
      for (String pattern : rule.regex) if (matches(url, pattern)) return true;
    }
    if (url.contains("url=http") || url.contains("v=http") || pathContainsHTML(url)) return false;
    return SNIFFER.matcher(url).find();
  }

  public static List<String> pageScripts(String pageURL, String click, List<Rule> rules) {
    Rule rule = matchingRule(pageURL, rules);
    List<String> out = rule == null ? new ArrayList<>() : new ArrayList<>(rule.script);
    String extra = click == null ? "" : click.trim();
    if (!extra.isEmpty() && !out.contains(extra)) out.add(0, extra);
    return out;
  }

  public static List<String> pageClicks(String pageURL, List<Rule> rules) {
    Rule rule = matchingRule(pageURL, rules);
    return rule == null ? List.of() : rule.click;
  }

  private static boolean isCloudflareResource(URI url) {
    if (url == null) return false;
    return "challenges.cloudflare.com".equalsIgnoreCase(url.getHost())
        || string(url.getPath()).contains("/cdn-cgi/challenge-platform/");
  }

  private static boolean isAd(String host, List<String> ads) {
    for (String pattern : ads) if (matches(host, pattern)) return true;
    return false;
  }

  private static Rule matchingRule(String url, List<Rule> rules) {
    URI parsed = uri(url);
    if (parsed == null) return null;
    String nestedHost = "";
    String query = parsed.getRawQuery();
    if (query != null) {
      for (String pair : query.split("&")) {
        String[] item = pair.split("=", 2);
        if (item.length == 2 && item[0].equals("url")) {
          try {
            URI nested = uri(URLDecoder.decode(item[1], StandardCharsets.UTF_8.name()));
            if (nested != null) nestedHost = string(nested.getHost());
          } catch (Exception ignored) {
            // A malformed nested address simply has no nested host, as in the Swift implementation.
          }
          break;
        }
      }
    }
    String hosts = string(parsed.getHost());
    if (!nestedHost.isEmpty()) hosts += (hosts.isEmpty() ? "" : ",") + nestedHost;
    if (hosts.isEmpty()) return null;
    for (Rule rule : rules)
      for (String pattern : rule.hosts) if (matches(hosts, pattern)) return rule;
    return null;
  }

  private static boolean matches(String text, String pattern) {
    if (pattern == null || pattern.isEmpty()) return false;
    if (text.contains(pattern)) return true;
    try {
      return Pattern.compile(pattern).matcher(text).find();
    } catch (PatternSyntaxException invalid) {
      return false;
    }
  }

  private static boolean pathContainsHTML(String url) {
    URI parsed = uri(url);
    return string(parsed == null ? url : parsed.getPath()).contains(".html");
  }

  private static URI uri(String value) {
    try {
      return value == null ? null : new URI(value);
    } catch (Exception invalid) {
      return null;
    }
  }

  private static String string(String value) {
    return value == null ? "" : value;
  }

  private static List<String> immutable(List<String> values) {
    return Collections.unmodifiableList(new ArrayList<>(values == null ? List.of() : values));
  }
}
