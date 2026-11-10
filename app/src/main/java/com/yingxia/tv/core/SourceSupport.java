package com.yingxia.tv.core;

import java.util.Locale;
import org.json.JSONObject;

/** Presentation policy: preserve unsupported entries in backups, but do not offer them as sources. */
public final class SourceSupport {
  private final JSONObject classes;
  private final JSONObject deferred;

  public SourceSupport(JSONObject index, JSONObject policy) {
    classes = Json.child(index, "classes");
    deferred = Json.child(policy, "scripts");
  }

  /** Null means the runtime can offer this source; this is not a promise of upstream uptime. */
  public String reason(JSONObject site) {
    String api = site.optString("api").trim();
    if (api.isEmpty()) return "缺少源地址";
    if (path(api).endsWith(".py")) return "尚未提供 Python 运行时";
    int type = site.optInt("type", 1);
    if (api.startsWith("csp_")) {
      Object entry = classes.opt(api.substring(4));
      String script = entry instanceof String ? (String) entry
          : entry instanceof JSONObject ? ((JSONObject) entry).optString("script") : "";
      if (script.isEmpty()) return "尚无 JavaScript 适配，JAR 源留待二期";
      Object ext = site.opt("ext");
      if (ext instanceof String && !((String) ext).trim().startsWith("{")) {
        String extension = path(((String) ext).trim());
        if (extension.endsWith(".js") || extension.endsWith(".py"))
          return "此源仍需 JAR 脚本加载器，留待二期";
      }
      return deferredReason(script);
    }
    if (type == 0 || type == 1 || type == 4) return null;
    if (type != 3) return "尚未适配的源类型";
    if (path(api).endsWith(".jar")) return "此地址不是可执行的 JavaScript 源";
    return deferredReason(api);
  }

  private String deferredReason(String script) {
    String name = path(script);
    name = name.substring(name.lastIndexOf('/') + 1);
    for (String key : Json.keys(deferred))
      if (key.equalsIgnoreCase(name)) return deferred.optString(key);
    return null;
  }

  private static String path(String value) {
    return value.split("[?#]", 2)[0].toLowerCase(Locale.ROOT);
  }
}
