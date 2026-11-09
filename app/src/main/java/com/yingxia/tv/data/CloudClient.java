package com.yingxia.tv.data;

import com.yingxia.tv.*;
import com.yingxia.tv.core.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import org.jsoup.*;
import org.jsoup.nodes.*;
import org.jsoup.parser.Parser;

/** The same AList/OpenList, Emby/Jellyfin and WebDAV folder contracts as TVCore. */
public final class CloudClient {
  private final Http http;
  private final Map<String, JSONObject> auth = new HashMap<>();

  public CloudClient(android.content.Context context) {
    http = YingxiaApp.get().http;
  }

  private String base(JSONObject s) {
    return s.optString("address").replaceAll("/+$", "");
  }

  private synchronized JSONObject credentials(JSONObject s) throws Exception {
    String key = s.optString("id");
    if (auth.containsKey(key)) return auth.get(key);
    String kind = s.optString("kind");
    JSONObject c = new JSONObject();
    if (kind.equals("emby")) {
      JSONObject r =
          http.post(
              base(s) + "/Users/AuthenticateByName",
              Json.obj("Username", s.optString("username"), "Pw", s.optString("password")),
              Http.params(
                  "X-Emby-Authorization",
                  "MediaBrowser Client=\"Yingxia\", Device=\"Android TV\", DeviceId=\""
                      + key
                      + "\", Version=\"1.8.15\""));
      c =
          Json.obj(
              "token", r.getString("AccessToken"), "user", r.getJSONObject("User").getString("Id"));
    } else if (kind.equals("alist") && !s.optString("username").isEmpty()) {
      JSONObject r =
          http.post(
              base(s) + "/api/auth/login",
              Json.obj("username", s.optString("username"), "password", s.optString("password")),
              Collections.emptyMap());
      check(r);
      c.put("token", r.getJSONObject("data").getString("token"));
    }
    auth.put(key, c);
    return c;
  }

  private Map<String, String> headers(JSONObject s) throws Exception {
    String kind = s.optString("kind");
    if (kind.equals("webdav"))
      return Http.params(
          "Authorization",
          "Basic "
              + Base64.getEncoder()
                  .encodeToString(
                      (s.optString("username") + ":" + s.optString("password"))
                          .getBytes(StandardCharsets.UTF_8)));
    JSONObject c = credentials(s);
    return c.optString("token").isEmpty()
        ? new HashMap<>()
        : Http.params(kind.equals("emby") ? "X-Emby-Token" : "Authorization", c.optString("token"));
  }

  private void check(JSONObject r) throws Exception {
    if (r.optInt("code", 200) != 200)
      throw new java.io.IOException(r.optString("message", "服务器返回错误"));
  }

  public List<JSONObject> list(JSONObject s, String path) throws Exception {
    List<JSONObject> out = new ArrayList<>();
    String kind = s.optString("kind", "alist");
    try {
      if (kind.equals("alist")) {
        JSONObject r =
            http.post(
                base(s) + "/api/fs/list",
                Json.obj(
                    "path",
                    path,
                    "password",
                    s.optString("folderPassword"),
                    "page",
                    1,
                    "per_page",
                    0,
                    "refresh",
                    false),
                headers(s));
        check(r);
        for (JSONObject f : Json.objects(Json.array(Json.child(r, "data").opt("content"))))
          out.add(
              Json.obj(
                  "id",
                  (path.equals("/") ? "" : path) + "/" + f.optString("name"),
                  "name",
                  f.optString("name"),
                  "folder",
                  f.optBoolean("is_dir"),
                  "size",
                  f.optLong("size"),
                  "poster",
                  f.optString("thumb")));
      } else if (kind.equals("emby")) {
        JSONObject c = credentials(s);
        String endpoint =
            base(s)
                + "/Users/"
                + c.optString("user")
                + (path.equals("/")
                    ? "/Views"
                    : "/Items?ParentId="
                        + java.net.URLEncoder.encode(path, "UTF-8")
                        + "&Fields=Path&SortBy=SortName");
        JSONObject r = http.get(endpoint, headers(s)).json();
        for (JSONObject f : Json.objects(Json.array(r.opt("Items"))))
          out.add(
              Json.obj(
                  "id",
                  f.optString("Id"),
                  "name",
                  f.optString("Name"),
                  "folder",
                  f.optBoolean("IsFolder"),
                  "poster",
                  base(s) + "/Items/" + f.optString("Id") + "/Images/Primary?maxHeight=300"));
      } else if (kind.equals("webdav")) {
        Map<String, String> h = headers(s);
        h.put("Depth", "1");
        h.put("Content-Type", "application/xml");
        String url = base(s) + (path.startsWith("/") ? path : "/" + path);
        String body =
            "<?xml version=\"1.0\"?><d:propfind"
                + " xmlns:d=\"DAV:\"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>";
        Http.Result r =
            http.request(
                url, "PROPFIND", h, body.getBytes(StandardCharsets.UTF_8), 4_000_000, true, 20000);
        Http.check(r);
        Document xml = Jsoup.parse(r.text(), "", Parser.xmlParser());
        for (Element response : xml.select("response, D|response, d|response")) {
          String href = tag(response, "href"), name = tag(response, "displayname");
          String id = new java.net.URI(href).getPath();
          String current = new java.net.URI(url).getPath();
          if (id.replaceAll("/+$", "").equals(current.replaceAll("/+$", ""))) continue;
          if (name.isEmpty()) {
            String[] parts = id.replaceAll("/+$", "").split("/");
            name = parts[parts.length - 1];
          }
          boolean folder =
              !response.getElementsByTag("d:collection").isEmpty()
                  || !response.getElementsByTag("D:collection").isEmpty()
                  || !response.getElementsByTag("collection").isEmpty();
          out.add(Json.obj("id", id, "name", name, "folder", folder));
        }
      }
    } catch (Exception e) {
      auth.remove(s.optString("id"));
      throw e;
    }
    out.sort(
        Comparator.comparing((JSONObject f) -> !f.optBoolean("folder"))
            .thenComparing(f -> f.optString("name")));
    return out;
  }

  private static String tag(Element parent, String tag) {
    for (Element e : parent.getAllElements())
      if (e.tagName().equalsIgnoreCase(tag)
          || e.tagName().toLowerCase(Locale.ROOT).endsWith(":" + tag)) return e.text();
    return "";
  }

  public JSONObject stream(JSONObject s, JSONObject file) throws Exception {
    String url;
    Map<String, String> h = headers(s);
    String kind = s.optString("kind", "alist");
    if (kind.equals("alist")) {
      JSONObject r =
          http.post(
              base(s) + "/api/fs/get",
              Json.obj("path", file.optString("id"), "password", s.optString("folderPassword")),
              h);
      check(r);
      url = r.getJSONObject("data").getString("raw_url");
      h = Json.headers(Json.child(Json.child(r, "data"), "header"));
    } else if (kind.equals("emby"))
      url = base(s) + "/Videos/" + file.optString("id") + "/stream?static=true";
    else url = base(s) + new java.net.URI(null, null, file.optString("id"), null).toASCIIString();
    return Json.obj(
        "id",
        "cloud:" + s.optString("id") + ":" + file.optString("id"),
        "name",
        file.optString("name"),
        "url",
        url,
        "header",
        new JSONObject(h),
        "poster",
        file.optString("poster"),
        "cloudServer",
        s,
        "cloudFile",
        file);
  }
}
