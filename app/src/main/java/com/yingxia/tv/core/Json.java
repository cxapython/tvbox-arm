package com.yingxia.tv.core;

import java.util.*;
import org.json.*;

public final class Json {
  private Json() {}

  public static JSONObject obj(Object... pairs) {
    JSONObject o = new JSONObject();
    try {
      for (int i = 0; i + 1 < pairs.length; i += 2)
        o.put(String.valueOf(pairs[i]), pairs[i + 1] == null ? JSONObject.NULL : pairs[i + 1]);
    } catch (JSONException e) {
      throw new IllegalArgumentException(e);
    }
    return o;
  }

  public static JSONObject object(String s) {
    try {
      return new JSONObject(s);
    } catch (Exception e) {
      return new JSONObject();
    }
  }

  public static JSONArray array(Object v) {
    if (v instanceof JSONArray) return (JSONArray) v;
    if (v instanceof Collection) return new JSONArray((Collection<?>) v);
    if (v instanceof String)
      try {
        return new JSONArray((String) v);
      } catch (Exception ignored) {
      }
    return new JSONArray();
  }

  public static JSONObject child(JSONObject o, String k) {
    JSONObject v = o.optJSONObject(k);
    return v == null ? new JSONObject() : v;
  }

  public static List<JSONObject> objects(JSONArray a) {
    List<JSONObject> out = new ArrayList<>();
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.optJSONObject(i);
      if (o != null) out.add(o);
    }
    return out;
  }

  public static List<String> keys(JSONObject o) {
    List<String> out = new ArrayList<>();
    o.keys().forEachRemaining(out::add);
    return out;
  }

  public static Map<String, String> headers(JSONObject o) {
    Map<String, String> m = new LinkedHashMap<>();
    if (o != null) for (String k : keys(o)) if (!o.isNull(k)) m.put(k, o.optString(k));
    return m;
  }

  public static String text(JSONObject o, String k, String fallback) {
    return o.isNull(k) ? fallback : o.optString(k, fallback);
  }

  public static JSONObject copy(JSONObject o) {
    return object(o.toString());
  }

  public static String message(Throwable t) {
    while (t.getCause() != null
        && (t instanceof java.lang.reflect.InvocationTargetException
            || t instanceof java.util.concurrent.ExecutionException)) t = t.getCause();
    String m = t.getMessage();
    return m == null ? t.getClass().getSimpleName() : m;
  }
}
