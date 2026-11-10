package com.yingxia.tv.core;

import java.net.URLDecoder;
import java.util.*;
import java.util.logging.*;
import java.util.regex.*;
import org.json.*;

public final class PlaylistParser {
  private static final Logger LOGGER = Logger.getLogger(PlaylistParser.class.getName());

  public static final class Result {
    private final List<JSONObject> channels;
    private final int invalidChannels, invalidHeaders;

    private Result(List<JSONObject> channels, int invalidChannels, int invalidHeaders) {
      this.channels = channels;
      this.invalidChannels = invalidChannels;
      this.invalidHeaders = invalidHeaders;
    }

    public List<JSONObject> getChannels() {
      return channels;
    }

    public int getInvalidChannels() {
      return invalidChannels;
    }

    public int getInvalidHeaders() {
      return invalidHeaders;
    }
  }

  public static List<JSONObject> parse(String text, String origin, Map<String, String> defaults) {
    return parseWithStats(text, origin, defaults).getChannels();
  }

  public static Result parseWithStats(String text, String origin, Map<String, String> defaults) {
    List<JSONObject> out = new ArrayList<>();
    int invalidChannels = 0, invalidHeaders = 0;
    Set<String> seen = new HashSet<>();
    boolean m3u = text.contains("#EXTM3U") || text.contains("#EXTINF:");
    String group = "", name = "", logo = "";
    Map<String, String> headers = new LinkedHashMap<>(defaults);
    for (String raw : text.replace("\uFEFF", "").split("\\r?\\n")) {
      String line = raw.trim();
      if (line.isEmpty()) continue;
      if (line.startsWith("#EXTINF:")) {
        name = line.substring(line.lastIndexOf(',') + 1).trim();
        group = attribute(line, "group-title", group);
        logo = attribute(line, "tvg-logo", "");
        headers = new LinkedHashMap<>(defaults);
      } else if (line.startsWith("#EXTGRP:")) group = line.substring(8).trim();
      else if (line.startsWith("#EXTVLCOPT:")) {
        String[] parts = line.substring(11).split("=", 2);
        if (parts.length == 2) {
          if (parts[0].equals("http-user-agent")) headers.put("User-Agent", parts[1]);
          if (parts[0].equals("http-referrer")) headers.put("Referer", parts[1]);
        }
      } else if (!line.startsWith("#")) {
        String url = line;
        if (!m3u) {
          String[] p = line.split(",", 2);
          if (p.length != 2) continue;
          if (p[1].trim().equals("#genre#")) {
            group = p[0];
            continue;
          }
          name = p[0].trim();
          url = p[1].trim();
          headers = new LinkedHashMap<>(defaults);
        }
        String[] address = url.split("\\|", 2);
        if (address.length == 2)
          for (String pair : address[1].split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2)
              try {
                headers.put(kv[0], URLDecoder.decode(kv[1], "UTF-8"));
              } catch (Exception invalid) {
                invalidHeaders++;
                LOGGER.log(Level.WARNING, "无法解码播放列表请求头: " + kv[0], invalid);
                headers.put(kv[0], kv[1]);
              }
          }
        try {
          String resolved = ConfigCodec.address(address[0], origin);
          String id = group + "|" + name + "|" + resolved;
          if (seen.add(id))
            out.add(
                Json.obj(
                    "id",
                    id,
                    "name",
                    name.isEmpty() ? resolved : name,
                    "group",
                    group,
                    "url",
                    resolved,
                    "logo",
                    logo,
                    "header",
                    new JSONObject(headers),
                    "live",
                    true));
        } catch (IllegalArgumentException invalid) {
          invalidChannels++;
          LOGGER.log(Level.WARNING, "忽略无效播放列表地址: " + address[0], invalid);
        }
        name = "";
        logo = "";
        headers = new LinkedHashMap<>(defaults);
      }
    }
    return new Result(out, invalidChannels, invalidHeaders);
  }

  private static String attribute(String s, String key, String fallback) {
    Matcher m = Pattern.compile(Pattern.quote(key) + "=\"([^\"]*)\"").matcher(s);
    return m.find() ? m.group(1) : fallback;
  }
}
