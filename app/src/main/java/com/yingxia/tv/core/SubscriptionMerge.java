package com.yingxia.tv.core;

import java.net.URI;
import java.util.*;
import org.json.*;

/**
 * Port of SubscriptionMerge.swift: several enabled subscriptions shown as one, in list order.
 * Every site keeps its own subscription's origin and spider (its own spider wins; the
 * subscription's spider only fills a blank). A site an earlier subscription already offers —
 * same type/api/jar-md5/ext fingerprint once addresses resolve against its own origin — appears
 * once; repeats inside one subscription stay. Keys are unique across subscriptions: a later clash
 * is renamed key@tag with the subscription's tag (first 6 hex of sha256(origin)) and keeps its
 * original key in sourceKey. Lives and parses join without repeats (lives by resolved URL, parses
 * by declared URL).
 */
public final class SubscriptionMerge {
  private SubscriptionMerge() {}

  /** Each subscription is a JSON snapshot: {id, origin, name, config}. */
  public static List<JSONObject> sites(List<JSONObject> subscriptions) {
    List<JSONObject> sites = new ArrayList<>();
    Set<String> keys = new HashSet<>(), offered = new HashSet<>();
    JSONArray parses = parses(subscriptions);
    for (JSONObject sub : subscriptions) {
      JSONObject config = Json.child(sub, "config");
      String origin = sub.optString("origin"), tag = tag(sub);
      Set<String> own = new HashSet<>();
      for (JSONObject raw : Json.objects(Json.array(config.opt("sites")))) {
        String key = raw.optString("key");
        if (key.isEmpty() || raw.optString("api").trim().isEmpty()) continue;
        String fingerprint = fingerprint(raw, origin, config);
        if (offered.contains(fingerprint)) continue;
        own.add(fingerprint);
        String merged = key;
        if (!keys.add(key)) {
          merged = key + "@" + tag;
          if (!keys.add(merged)) continue;
        }
        JSONObject site = Json.copy(raw);
        try {
          site.put("id", sub.optString("id") + "@" + merged)
              .put("origin", origin)
              .put("subscription", sub.optString("name"))
              .put("parses", new JSONArray(parses.toString()))
              .put("ads", config.has("ads") ? config.opt("ads") : new JSONArray())
              .put("rules", config.has("rules") ? config.opt("rules") : new JSONArray());
          if (!merged.equals(key)) site.put("key", merged).put("sourceKey", key);
          if (raw.optString("spider").trim().isEmpty())
            site.put("spider", config.optString("spider"));
        } catch (JSONException broken) {
          throw new IllegalStateException(broken);
        }
        sites.add(site);
      }
      offered.addAll(own);
    }
    return sites;
  }

  public static List<JSONObject> lives(List<JSONObject> subscriptions) {
    List<JSONObject> out = new ArrayList<>();
    Set<String> urls = new HashSet<>();
    for (JSONObject sub : subscriptions) {
      String origin = sub.optString("origin");
      for (JSONObject raw : Json.objects(Json.array(Json.child(sub, "config").opt("lives")))) {
        String resolved = resolve(raw.optString("url"), origin);
        if (!urls.add(resolved != null ? resolved : raw.optString("url"))) continue;
        JSONObject live = Json.copy(raw);
        try {
          live.put("origin", origin)
              .put("id", sub.optString("id") + "@" + raw.optString("name"));
        } catch (JSONException broken) {
          throw new IllegalStateException(broken);
        }
        out.add(live);
      }
    }
    return out;
  }

  /** Parse services joined without repeats, by declared URL; entries without a URL are dropped. */
  public static JSONArray parses(List<JSONObject> subscriptions) {
    JSONArray out = new JSONArray();
    Set<String> urls = new HashSet<>();
    for (JSONObject sub : subscriptions)
      for (JSONObject parse : Json.objects(Json.array(Json.child(sub, "config").opt("parses")))) {
        Object url = parse.opt("url");
        if (!(url instanceof String) || !urls.add((String) url)) continue;
        out.put(parse);
      }
    return out;
  }

  /** Two entries are one source when they run the same way once their addresses are resolved. */
  static String fingerprint(JSONObject raw, String origin, JSONObject config) {
    boolean jar = isJar(raw);
    String api = raw.optString("api");
    if (!jar) {
      String resolved = resolve(api, origin);
      if (resolved != null) api = resolved;
    }
    String archive = "";
    if (jar) {
      String own = raw.optString("jar").trim();
      if (!own.isEmpty()) {
        String checksum = declaredChecksum(own);
        String resolved = resolve(own.split(";md5;")[0], origin);
        archive = checksum != null ? checksum : resolved != null ? resolved : "";
      } else {
        String spider = config.optString("spider");
        String checksum = declaredChecksum(spider);
        String resolved = resolve(spider.split(";md5;")[0], origin);
        archive = checksum != null ? checksum : resolved != null ? resolved : "";
      }
    }
    return String.join(
        "\n", raw.optString("type"), api, archive, canonicalExtension(raw.opt("ext"), origin));
  }

  /** The MD5 an archive address declares after `;md5;`, when it is a well-formed one. */
  static String declaredChecksum(String address) {
    String[] fields = address.split(";md5;");
    if (fields.length < 2) return null;
    String checksum = fields[1].trim().toLowerCase(Locale.ROOT);
    for (int i = 0; i < checksum.length(); i++)
      if (Character.digit(checksum.charAt(i), 16) < 0) return null;
    return checksum.length() == 32 ? "md5:" + checksum : null;
  }

  /** Classified like the original: only a type 3 api that is no .js/.py script runs as a JAR. */
  private static boolean isJar(JSONObject raw) {
    String api = raw.optString("api").trim();
    if (api.isEmpty()) return false;
    int type = raw.optInt("type", -1);
    if (type == 1 || type == 4 || type != 3) return false;
    String path = api.toLowerCase(Locale.ROOT);
    if (path.endsWith(".js") || path.contains(".js?")) return false;
    if (path.contains(".py")
        || (api.startsWith("py_")
            && raw.optString("ext").toLowerCase(Locale.ROOT).contains(".py"))) return false;
    return true;
  }

  private static String tag(JSONObject sub) {
    return ConfigCodec.hash(sub.optString("origin")).substring(0, 6);
  }

  /** Absolute http(s) form of address against origin, or null when it cannot resolve to one. */
  private static String resolve(String address, String origin) {
    if (address == null || address.trim().isEmpty()) return null;
    try {
      URI uri = new URI(origin).resolve(address.trim().replace(" ", "%20"));
      String scheme = uri.getScheme();
      if (scheme == null
          || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
          || uri.getHost() == null) return null;
      return uri.toASCIIString();
    } catch (Exception e) {
      return null;
    }
  }

  /** ext with relative addresses resolved against its own subscription, JSON-encoded sorted. */
  private static String canonicalExtension(Object ext, String origin) {
    if (ext == null || JSONObject.NULL.equals(ext)) return "";
    try {
      StringBuilder b = new StringBuilder();
      writeCanonical(resolveExtension(ext, origin), b);
      return b.toString();
    } catch (IllegalArgumentException unresolvable) {
      // The original's try? maps an unresolvable ext to "unknown", not to a merge failure.
      return "";
    }
  }

  private static Object resolveExtension(Object value, String origin) {
    if (value instanceof String) {
      String text = (String) value;
      if (text.startsWith("./") || text.startsWith("../") || text.startsWith("//")) {
        String resolved = resolve(text, origin);
        if (resolved == null) throw new IllegalArgumentException("unresolvable ext");
        return resolved;
      }
      return value;
    }
    if (value instanceof JSONObject) {
      JSONObject object = (JSONObject) value, out = new JSONObject();
      for (String key : Json.keys(object))
        try {
          out.put(key, resolveExtension(object.opt(key), origin));
        } catch (JSONException broken) {
          throw new IllegalArgumentException(broken);
        }
      return out;
    }
    if (value instanceof JSONArray) {
      JSONArray array = (JSONArray) value, out = new JSONArray();
      for (int i = 0; i < array.length(); i++) out.put(resolveExtension(array.opt(i), origin));
      return out;
    }
    return value;
  }

  private static void writeCanonical(Object value, StringBuilder b) {
    if (value == null || JSONObject.NULL.equals(value)) {
      b.append("null");
    } else if (value instanceof JSONObject) {
      JSONObject object = (JSONObject) value;
      List<String> keys = Json.keys(object);
      Collections.sort(keys);
      b.append('{');
      for (int i = 0; i < keys.size(); i++) {
        if (i > 0) b.append(',');
        b.append(JSONObject.quote(keys.get(i))).append(':');
        writeCanonical(object.opt(keys.get(i)), b);
      }
      b.append('}');
    } else if (value instanceof JSONArray) {
      JSONArray array = (JSONArray) value;
      b.append('[');
      for (int i = 0; i < array.length(); i++) {
        if (i > 0) b.append(',');
        writeCanonical(array.opt(i), b);
      }
      b.append(']');
    } else if (value instanceof String) {
      b.append(JSONObject.quote((String) value));
    } else if (value instanceof Number || value instanceof Boolean) {
      b.append(value);
    } else {
      b.append(JSONObject.quote(String.valueOf(value)));
    }
  }
}
