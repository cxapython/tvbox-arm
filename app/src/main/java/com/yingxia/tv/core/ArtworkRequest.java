package com.yingxia.tv.core;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** TVBox artwork transport suffixes, matching the original PosterResource parser. */
public final class ArtworkRequest {
  private static final Pattern SUFFIX =
      Pattern.compile("@(Headers|Cookie|Referer|User-Agent)=", Pattern.CASE_INSENSITIVE);
  public final String url;
  public final Map<String, String> headers;

  private ArtworkRequest(String url, Map<String, String> headers) {
    this.url = url;
    this.headers = Collections.unmodifiableMap(headers);
  }

  public static ArtworkRequest parse(String address, String origin) {
    String text = address == null ? "" : address.trim();
    Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    // Preserve the Android port's existing JSON suffix as well as the original named forms.
    int json = text.indexOf("@{");
    if (json >= 0) {
      headers.putAll(Json.headers(Json.object(text.substring(json + 1))));
      text = text.substring(0, json);
    }
    Matcher matcher = SUFFIX.matcher(text);
    List<Integer> starts = new ArrayList<>(), ends = new ArrayList<>();
    List<String> names = new ArrayList<>();
    while (matcher.find()) {
      starts.add(matcher.start()); ends.add(matcher.end()); names.add(matcher.group(1));
    }
    String endpoint = starts.isEmpty() ? text : text.substring(0, starts.get(0));
    for (int i = 0; i < starts.size(); i++) {
      String value = text.substring(ends.get(i), i + 1 < starts.size() ? starts.get(i + 1) : text.length());
      if (names.get(i).equalsIgnoreCase("Headers"))
        headers.putAll(Json.headers(Json.object(value)));
      else headers.put(names.get(i), value);
    }
    headers.entrySet().removeIf(entry -> !entry.getKey().matches("[A-Za-z0-9-]+")
        || entry.getValue().contains("\r") || entry.getValue().contains("\n"));
    String url = ConfigCodec.address(endpoint, origin);
    String host = URI.create(url).getHost();
    if (host != null && host.toLowerCase(java.util.Locale.ROOT).endsWith(".doubanio.com")
        && !headers.containsKey("Referer")) headers.put("Referer", "https://movie.douban.com/");
    return new ArtworkRequest(url, headers);
  }
}
