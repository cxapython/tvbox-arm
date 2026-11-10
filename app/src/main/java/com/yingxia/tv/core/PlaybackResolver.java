package com.yingxia.tv.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The complete TVBox playback routing chain, with injectable network and sniffing boundaries. */
public final class PlaybackResolver {
  public interface Transport {
    String get(String url, Map<String, String> headers) throws Exception;
  }

  public interface Sniffer {
    Resolved sniff(SniffRequest request) throws Exception;
  }

  public static final class SniffRequest {
    public final String url;
    public final Map<String, String> headers;
    public final String click;
    public final boolean detect;
    public final List<String> ads;
    public final List<SniffRules.Rule> rules;

    SniffRequest(String url, Map<String, String> headers, String click, boolean detect,
        List<String> ads, List<SniffRules.Rule> rules) {
      this.url = url;
      this.headers = immutable(headers);
      this.click = click;
      this.detect = detect;
      this.ads = List.copyOf(ads);
      this.rules = List.copyOf(rules);
    }
  }

  public static final class ParseService {
    public final String name;
    public final int type;
    public final String url;
    public final Map<String, String> headers;
    public final List<String> flags;
    public final String click;

    public ParseService(String name, int type, String url, Map<String, String> headers,
        List<String> flags, String click) {
      this.name = name == null || name.isEmpty() ? url : name;
      this.type = type;
      this.url = url;
      this.headers = immutable(headers);
      this.flags = List.copyOf(flags == null ? List.of() : flags);
      this.click = click == null ? "" : click;
    }

    static ParseService from(JSONObject value) {
      JSONObject ext = value.optJSONObject("ext");
      Map<String, String> headers = ext == null ? Map.of()
          : Json.headers(ext.optJSONObject("header"));
      return new ParseService(value.optString("name", value.optString("url")),
          value.optInt("type", -1), value.optString("url"), headers,
          SniffRules.strings(ext == null ? null : ext.opt("flag")), value.optString("click"));
    }

    static List<ParseService> list(JSONArray values) {
      List<ParseService> out = new ArrayList<>();
      for (JSONObject value : Json.objects(values)) {
        ParseService item = from(value);
        if (item.type >= 0) out.add(item);
      }
      return out;
    }
  }

  public static final class Choice {
    public final String name;
    public final String address;

    public Choice(String name, String address) {
      this.name = name;
      this.address = address;
    }
  }

  public static final class Resolved {
    public final String url;
    public final Map<String, String> headers;

    public Resolved(String url, Map<String, String> headers) {
      this.url = url;
      this.headers = immutable(headers);
    }
  }

  public static final class Plan {
    public final List<Choice> choices;
    public final Map<String, String> headers;
    public final String flag;
    public final boolean requiresWebDetection;
    public final String click;
    public final String webPlayURL;
    public final List<ParseService> parses;
    public final List<String> ads;
    public final List<SniffRules.Rule> rules;

    public Plan(List<Choice> choices, Map<String, String> headers, String flag,
        boolean requiresWebDetection, String click, String webPlayURL, List<ParseService> parses,
        List<String> ads, List<SniffRules.Rule> rules) {
      this.choices = List.copyOf(choices);
      this.headers = immutable(headers);
      this.flag = flag == null ? "" : flag;
      this.requiresWebDetection = requiresWebDetection;
      this.click = click == null ? "" : click;
      this.webPlayURL = webPlayURL == null ? "" : webPlayURL;
      this.parses = List.copyOf(parses == null ? List.of() : parses);
      this.ads = List.copyOf(ads == null ? List.of() : ads);
      this.rules = List.copyOf(rules == null ? List.of() : rules);
    }
  }

  private static final class Failure {
    final String service;
    final String reason;

    Failure(String service, Throwable error) {
      this.service = service;
      this.reason = Json.message(error);
    }

    @Override public String toString() {
      return service + ": " + reason;
    }
  }

  private static final class Attempt {
    final ParseService service;
    final boolean web;
    final Callable<Resolved> operation;

    Attempt(ParseService service, boolean web, Callable<Resolved> operation) {
      this.service = service;
      this.web = web;
      this.operation = operation;
    }
  }

  private static final class Outcome {
    final Resolved found;
    final Throwable error;

    Outcome(Resolved found, Throwable error) {
      this.found = found;
      this.error = error;
    }
  }

  private final Transport transport;
  private final Sniffer sniffer;

  public PlaybackResolver(Transport transport, Sniffer sniffer) {
    this.transport = transport;
    this.sniffer = sniffer;
  }

  /** Builds the routing plan and merges site/result headers in TVBox order. */
  public static Plan plan(JSONObject site, JSONObject answer, JSONObject episode) throws IOException {
    String provider = providerError(answer);
    if (provider != null) throw new IOException(provider);
    Map<String, String> headers = new LinkedHashMap<>(Json.headers(site.optJSONObject("header")));
    headers.putAll(resultHeaders(answer));
    String prefix = answer.optString("playUrl");
    String click = site.optString("click").trim();
    if (click.isEmpty()) click = answer.optString("click").trim();
    boolean prefixed = prefix.startsWith("json:") || prefix.startsWith("parse:");
    List<Choice> choices = choices(answer.opt("url"), prefixed ? "" : prefix,
        episode.optString("name"));
    if (choices.isEmpty()) throw new IOException("源没有返回播放地址");
    List<SniffRules.Rule> rules = new ArrayList<>();
    for (JSONObject rule : Json.objects(Json.array(site.opt("rules"))))
      rules.add(SniffRules.Rule.from(rule));
    return new Plan(choices, headers, episode.optString("flag"),
        answer.optInt("parse") != 0 || answer.optInt("jx") != 0 || !prefix.isEmpty(), click,
        prefixed ? prefix : "", ParseService.list(Json.array(site.opt("parses"))),
        SniffRules.strings(site.opt("ads")), rules);
  }

  /** FongMi Url values, ordinary strings, and TVBox's alternating name/address arrays. */
  public static List<Choice> choices(Object value, String prefix, String fallbackName) {
    List<Choice> out = new ArrayList<>();
    if (value instanceof String && !((String) value).trim().isEmpty()) {
      out.add(new Choice(fallbackName, prefix + value));
      return out;
    }
    if (value instanceof JSONObject) {
      JSONObject object = (JSONObject) value;
      JSONArray values = object.optJSONArray("values");
      if (values == null) return out;
      int position = object.optInt("position", 0);
      List<Integer> order = new ArrayList<>();
      if (position >= 0 && position < values.length()) order.add(position);
      for (int i = 0; i < values.length(); i++) if (i != position) order.add(i);
      for (int index : order) {
        JSONObject item = values.optJSONObject(index);
        if (item == null || item.optString("v").trim().isEmpty()) continue;
        String name = item.optString("n");
        out.add(new Choice(name.isEmpty() ? fallbackName : name, prefix + item.optString("v")));
      }
      return out;
    }
    if (!(value instanceof JSONArray)) return out;
    JSONArray values = (JSONArray) value;
    for (int i = 0; i + 1 < values.length(); i += 2) {
      String name = values.optString(i), address = values.optString(i + 1);
      if (address.trim().isEmpty()) continue;
      out.add(new Choice(name.isEmpty() ? fallbackName : name, prefix + address));
    }
    return out;
  }

  public Resolved resolve(Plan plan) throws Exception {
    return resolve(plan, 0);
  }

  /** Resolves one FongMi quality choice without discarding the remaining choice identities. */
  public Resolved resolve(Plan plan, int choiceIndex) throws Exception {
    if (plan.choices.isEmpty()) throw new IOException("源没有返回播放地址");
    if (choiceIndex < 0 || choiceIndex >= plan.choices.size())
      throw new IOException("选择的清晰度已失效，请重新解析");
    Choice choice = plan.choices.get(choiceIndex);
    if (!plan.requiresWebDetection || isDirectMedia(choice.address))
      return finish(new Resolved(choice.address, plan.headers));
    String playURL = plan.webPlayURL;
    if (playURL.startsWith("json:")) {
      String service = playURL.substring(5);
      ParseService item = new ParseService(service, 1, service, plan.headers, List.of(), "");
      List<Failure> failures = new ArrayList<>();
      try {
        return finish(askJSON(item, choice.address, plan.headers));
      } catch (Exception error) {
        if (isTerminal(error)) throw error;
        failures.add(new Failure(item.name, error));
        throw detectionError(failures);
      }
    }
    if (playURL.startsWith("parse:")) {
      String name = playURL.substring(6);
      ParseService selected = null;
      for (ParseService item : plan.parses) if (item.name.equals(name)) { selected = item; break; }
      if (selected == null) throw new IOException("未找到名为 " + name + " 的解析服务");
      List<Failure> failures = new ArrayList<>();
      switch (selected.type) {
        case 0:
          try {
            Map<String, String> headers = selected.headers.isEmpty() ? plan.headers : selected.headers;
            return finish(sniff(selected, selected.url + choice.address, headers, plan.click, plan));
          } catch (Exception error) {
            if (isTerminal(error)) throw error;
            failures.add(new Failure(selected.name, error));
          }
          break;
        case 1:
          try {
            Map<String, String> headers = selected.headers.isEmpty() ? plan.headers : selected.headers;
            return finish(askJSON(selected, choice.address, headers));
          } catch (Exception error) {
            if (isTerminal(error)) throw error;
            failures.add(new Failure(selected.name, error));
          }
          break;
        case 4: {
          Resolved found = superParse(plan, choice.address, failures);
          if (found != null) return finish(found);
          break;
        }
        default:
          failures.add(new Failure(selected.name,
              new IOException("不支持 type=" + selected.type + " 解析服务")));
      }
      throw detectionError(failures);
    }

    List<Failure> failures = new ArrayList<>();
    try {
      String html = transport.get(choice.address, plan.headers);
      String embedded = embeddedMedia(html, choice.address);
      if (embedded != null) return finish(new Resolved(embedded, plan.headers));
    } catch (Exception error) {
      if (isTerminal(error)) throw error;
      failures.add(new Failure("网页读取", error));
    }
    List<Attempt> attempts = new ArrayList<>();
    for (ParseService item : plan.parses) {
      String service = item.url.toLowerCase(java.util.Locale.ROOT);
      if (item.type != 1 || !service.startsWith("http") || service.contains("127.0.0.1")
          || service.contains("localhost")) continue;
      attempts.add(new Attempt(item, false, () -> askJSON(item, choice.address, item.headers)));
    }
    if (attempts.size() > 24) attempts = new ArrayList<>(attempts.subList(0, 24));
    Resolved found = race(attempts, List.of(), 6, 0, failures,
        System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8));
    if (found != null) return finish(found);
    try {
      return finish(sniff(null, choice.address, plan.headers, plan.click, plan));
    } catch (Exception error) {
      if (isTerminal(error)) throw error;
      failures.add(new Failure("网页嗅探", error));
      throw detectionError(failures);
    }
  }

  private Resolved superParse(Plan plan, String page, List<Failure> failures) throws Exception {
    List<Attempt> json = new ArrayList<>(), web = new ArrayList<>();
    for (ParseService item : parsers(plan.parses, 1, plan.flag))
      json.add(new Attempt(item, false, () -> askJSON(item, page, item.headers)));
    for (ParseService item : parsers(plan.parses, 0, plan.flag)) {
      if (sniffer == null) {
        failures.add(new Failure(item.name, new IOException("此设备未提供网页嗅探器")));
        continue;
      }
      web.add(new Attempt(item, true,
          () -> sniff(item, item.url + page, item.headers, item.click, plan)));
    }
    return race(json, web, 4, 2, failures, 0);
  }

  /** Independently filter each parser type; an empty flagged subset falls back to that whole type. */
  public static List<ParseService> parsers(List<ParseService> items, int type, String flag) {
    List<ParseService> typed = new ArrayList<>(), flagged = new ArrayList<>();
    for (ParseService item : items) if (item.type == type) {
      typed.add(item);
      if (item.flags.contains(flag)) flagged.add(item);
    }
    return flagged.isEmpty() ? typed : flagged;
  }

  private Resolved race(List<Attempt> json, List<Attempt> web, int jsonWidth, int webWidth,
      List<Failure> failures, long deadlineNanos) throws Exception {
    int total = json.size() + web.size();
    if (total == 0) return null;
    ExecutorService executor = Executors.newFixedThreadPool(Math.min(total, jsonWidth + webWidth));
    CompletionService<Outcome> completed = new ExecutorCompletionService<>(executor);
    Map<Future<Outcome>, Attempt> running = new LinkedHashMap<>();
    int nextJSON = 0, nextWeb = 0;
    try {
      while (nextJSON < json.size() && nextJSON < jsonWidth) {
        Attempt item = json.get(nextJSON++);
        running.put(completed.submit(() -> run(item.operation)), item);
      }
      while (nextWeb < web.size() && nextWeb < webWidth) {
        Attempt item = web.get(nextWeb++);
        running.put(completed.submit(() -> run(item.operation)), item);
      }
      while (!running.isEmpty()) {
        Future<Outcome> future;
        if (deadlineNanos == 0) {
          future = completed.take();
        } else {
          long remaining = deadlineNanos - System.nanoTime();
          future = remaining <= 0 ? null
              : completed.poll(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
          if (future == null) {
            for (Map.Entry<Future<Outcome>, Attempt> entry : running.entrySet()) {
              entry.getKey().cancel(true);
              failures.add(new Failure(entry.getValue().service.name,
                  new IOException("普通网页 JSON 快路径 8 秒超时")));
            }
            for (int i = nextJSON; i < json.size(); i++)
              failures.add(new Failure(json.get(i).service.name,
                  new IOException("普通网页 JSON 快路径 8 秒超时")));
            for (int i = nextWeb; i < web.size(); i++)
              failures.add(new Failure(web.get(i).service.name,
                  new IOException("普通网页 JSON 快路径 8 秒超时")));
            return null;
          }
        }
        Attempt item = running.remove(future);
        Outcome outcome;
        try {
          outcome = future.get();
        } catch (java.util.concurrent.ExecutionException error) {
          outcome = new Outcome(null, error.getCause());
        }
        if (outcome.found != null) {
          for (Future<Outcome> other : running.keySet()) other.cancel(true);
          return outcome.found;
        }
        failures.add(new Failure(item.service.name, outcome.error));
        if (item.web && nextWeb < web.size()) {
          Attempt next = web.get(nextWeb++);
          running.put(completed.submit(() -> run(next.operation)), next);
        } else if (!item.web && nextJSON < json.size()) {
          Attempt next = json.get(nextJSON++);
          running.put(completed.submit(() -> run(next.operation)), next);
        }
      }
      return null;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      for (Future<Outcome> other : running.keySet()) other.cancel(true);
      throw interrupted;
    } finally {
      executor.shutdownNow();
    }
  }

  private static Outcome run(Callable<Resolved> operation) {
    try {
      Resolved found = operation.call();
      return found == null ? new Outcome(null, new IOException("未返回可播放地址"))
          : new Outcome(found, null);
    } catch (Exception error) {
      return new Outcome(null, error);
    }
  }

  private Resolved askJSON(ParseService service, String page, Map<String, String> headers)
      throws Exception {
    String body = transport.get(service.url + page, headers);
    JSONObject root;
    try {
      root = new JSONObject(body);
    } catch (JSONException invalid) {
      throw new IOException("返回的不是有效 JSON", invalid);
    }
    JSONObject payload = root.optJSONObject("data") == null ? root : root.optJSONObject("data");
    String provider = providerError(payload);
    if (provider != null) throw new IOException(provider);
    String address = payload.optString("url").trim();
    if (address.isEmpty()) throw new IOException("未返回播放地址");
    Map<String, String> foundHeaders = new LinkedHashMap<>();
    JSONObject header = payload.optJSONObject("header");
    if (header == null) header = payload.optJSONObject("headers");
    if (header != null) foundHeaders.putAll(Json.headers(header));
    else {
      String text = payload.optString("header", "");
      if (text.isEmpty()) text = payload.optString("headers", "");
      if (!text.isEmpty()) foundHeaders.putAll(Json.headers(Json.object(text)));
    }
    String agent = firstText(payload, "user-agent", "User-Agent", "ua");
    if (!agent.isEmpty()) foundHeaders.put("User-Agent", agent);
    if (address.toLowerCase(java.util.Locale.ROOT).startsWith("magnet:"))
      return new Resolved(address, foundHeaders);
    if (!address.toLowerCase(java.util.Locale.ROOT).startsWith("http") || address.equals(page))
      throw new IOException("返回的播放地址无效");
    if (!isDirectMedia(address) && !address.contains(".m3u8") && !address.contains(".mp4"))
      throw new IOException("返回的不是支持的媒体地址");
    return new Resolved(address, foundHeaders);
  }

  private Resolved sniff(ParseService service, String target, Map<String, String> headers,
      String click, Plan plan) throws Exception {
    if (sniffer == null) throw new IOException("此设备未提供网页嗅探器");
    boolean detect = !target.contains("player/?url=");
    Resolved found = sniffer.sniff(new SniffRequest(target, headers, click, detect, plan.ads, plan.rules));
    if (found == null) throw new IOException("15 秒内未发现可播放地址");
    return found;
  }

  private static final class TerminalException extends IOException {
    TerminalException(String message) {
      super(message);
    }
  }

  private static Resolved finish(Resolved found) throws IOException {
    String address = unwrapPluginProxy(found.url);
    rejectMagnet(address);
    if (isPluginProxy(address))
      throw new TerminalException("此源需要不受支持的插件播放代理");
    Map<String, String> headers = new LinkedHashMap<>(found.headers);
    String[] parts = address.split("\\|", 2);
    if (parts.length == 2) {
      for (String pair : parts[1].split("&")) {
        String[] item = pair.split("=", 2);
        if (item.length == 2) headers.put(item[0], percentDecode(item[1]));
      }
    }
    return new Resolved(parts[0], headers);
  }

  public static void rejectMagnet(String address) throws IOException {
    if (address != null && address.toLowerCase(java.util.Locale.ROOT).startsWith("magnet:"))
      throw new TerminalException("此源返回了磁链，不支持种子播放");
  }

  /** Strictly only http loopback /proxy URLs belong to a plugin; lookalikes are ordinary URLs. */
  public static boolean isPluginProxy(String address) {
    int marker = address == null ? -1 : address.indexOf("/proxy?");
    if (marker < 0) return false;
    String authority = address.substring(0, marker);
    for (String host : new String[] {"http://127.0.0.1", "http://localhost"}) {
      if (authority.equals(host)) return true;
      if (authority.startsWith(host + ":")) {
        try {
          int port = Integer.parseInt(authority.substring(host.length() + 1));
          if (port >= -1 && port <= 65535) return true;
        } catch (NumberFormatException ignored) {
          return false;
        }
      }
    }
    return false;
  }

  public static Map<String, String> pluginProxyQuery(String address) {
    Map<String, String> out = new LinkedHashMap<>();
    if (!isPluginProxy(address)) return out;
    String query = address.substring(address.indexOf("/proxy?") + 7);
    for (String pair : query.split("&", -1)) {
      String[] item = pair.split("=", 2);
      out.putIfAbsent(percentDecode(item[0]), item.length == 2 ? percentDecode(item[1]) : "");
    }
    return out;
  }

  public static String unwrapPluginProxy(String address) {
    Map<String, String> query = pluginProxyQuery(address);
    String inner = query.get("url");
    return "m3u8".equals(query.get("do")) && inner != null && !inner.isEmpty() ? inner : address;
  }

  private static IOException detectionError(List<Failure> failures) {
    StringBuilder message = new StringBuilder("此线路需要网页解析，但未找到可播放地址");
    if (!failures.isEmpty()) {
      message.append("；各解析服务失败原因：");
      for (int i = 0; i < failures.size(); i++) {
        if (i > 0) message.append("；");
        message.append(failures.get(i));
      }
    }
    return new IOException(message.toString());
  }

  private static boolean isTerminal(Throwable error) {
    return error instanceof TerminalException;
  }

  /** Provider login/authorization messages are useful only when no playable URL accompanied them. */
  public static String providerError(JSONObject result) {
    Object url = result.opt("url");
    if (url instanceof String && !((String) url).trim().isEmpty()) return null;
    if (url instanceof JSONArray && ((JSONArray) url).length() > 0) return null;
    if (url instanceof JSONObject && !choices(url, "", "").isEmpty()) return null;
    for (String key : new String[] {"errMsg", "msg", "message"}) {
      Object value = result.opt(key);
      if (value instanceof String && !((String) value).trim().isEmpty())
        return ((String) value).trim();
    }
    return null;
  }

  /** Copy every playback-only data field without interpreting or dropping its original shape. */
  public static void passThrough(JSONObject answer, JSONObject play) {
    for (String key : new String[] {"danmaku", "subs", "skips", "decryptKey"})
      if (answer.has(key)) {
        try {
          play.put(key, answer.opt(key));
        } catch (JSONException impossible) {
          throw new IllegalStateException(impossible);
        }
      }
    if (answer.has("subt") || answer.has("subs")) {
      try {
        play.put("subtitles", answer.has("subt") ? answer.opt("subt") : answer.opt("subs"));
      } catch (JSONException impossible) {
        throw new IllegalStateException(impossible);
      }
    }
  }

  private static Map<String, String> resultHeaders(JSONObject answer) {
    Map<String, String> headers = new LinkedHashMap<>();
    JSONObject fields = answer.optJSONObject("header");
    if (fields != null) headers.putAll(Json.headers(fields));
    else {
      Object text = answer.opt("header");
      if (text instanceof String) headers.putAll(Json.headers(Json.object((String) text)));
    }
    String agent = firstText(answer, "UA", "ua");
    if (!agent.isEmpty()) headers.put("User-Agent", agent);
    String referer = firstText(answer, "referer");
    if (!referer.isEmpty()) headers.put("Referer", referer);
    return headers;
  }

  private static String firstText(JSONObject object, String... keys) {
    for (String key : keys) {
      Object value = object.opt(key);
      if (value instanceof String && !((String) value).isEmpty()) return (String) value;
    }
    return "";
  }

  public static boolean isDirectMedia(String address) {
    if (address == null) return false;
    String clean = address.split("\\|", 2)[0];
    try {
      String path = new URI(clean).getPath();
      if (path == null) return false;
      int dot = path.lastIndexOf('.');
      if (dot < 0) return false;
      String extension = path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
      return List.of("mp4", "m4v", "m3u8", "mp3", "m4a", "aac").contains(extension);
    } catch (Exception invalid) {
      return false;
    }
  }

  private static final Pattern PLAYER_DATA = Pattern.compile(
      "player_[A-Za-z0-9_]+\\s*=\\s*(\\{.*?\\})\\s*[;<]", Pattern.DOTALL);
  private static final Pattern MEDIA_ADDRESS = Pattern.compile(
      "https?:(?:\\\\?/){2}[^\"'\\s<>()]+?\\.(?:m3u8|mp4)(?:\\?[^\"'\\s<>()]*)?",
      Pattern.CASE_INSENSITIVE);

  /** Extract MacCMS player data or an inline media URL; malformed candidates are simply not media. */
  public static String embeddedMedia(String html, String base) {
    if (html == null) return null;
    Matcher player = PLAYER_DATA.matcher(html);
    if (player.find()) {
      try {
        JSONObject object = new JSONObject(player.group(1));
        String address = object.optString("url");
        int encrypt = object.optInt("encrypt", 0);
        if (encrypt == 1) address = percentDecode(address);
        else if (encrypt == 2) {
          try {
            address = percentDecode(new String(Base64.getDecoder().decode(address), StandardCharsets.UTF_8));
          } catch (IllegalArgumentException ignored) {
            // Keep the undecoded candidate; validation below will reject it if it is not a URL.
          }
        }
        String resolved = resolve(address, base);
        if (resolved != null && isDirectMedia(resolved)) return resolved;
      } catch (JSONException ignored) {
        // A player assignment which is not JSON cannot yield embedded media.
      }
    }
    Matcher media = MEDIA_ADDRESS.matcher(html);
    return media.find() ? resolve(media.group().replace("\\/", "/"), base) : null;
  }

  private static String resolve(String address, String base) {
    try {
      URI parent = new URI(base), resolved = parent.resolve(address);
      String scheme = resolved.getScheme();
      return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
          ? resolved.toString() : null;
    } catch (Exception invalid) {
      return null;
    }
  }

  /** Percent decoding which preserves '+', and preserves the whole token when any escape is bare. */
  private static String percentDecode(String text) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int cursor = 0;
    while (cursor < text.length()) {
      char c = text.charAt(cursor);
      if (c != '%') {
        int next = text.indexOf('%', cursor);
        if (next < 0) next = text.length();
        byte[] plain = text.substring(cursor, next).getBytes(StandardCharsets.UTF_8);
        out.write(plain, 0, plain.length);
        cursor = next;
        continue;
      }
      if (cursor + 2 >= text.length()) return text;
      int high = Character.digit(text.charAt(cursor + 1), 16);
      int low = Character.digit(text.charAt(cursor + 2), 16);
      if (high < 0 || low < 0) return text;
      out.write((high << 4) | low);
      cursor += 3;
    }
    return new String(out.toByteArray(), StandardCharsets.UTF_8);
  }

  private static Map<String, String> immutable(Map<String, String> values) {
    return Collections.unmodifiableMap(new LinkedHashMap<>(values == null ? Map.of() : values));
  }
}
