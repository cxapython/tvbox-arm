package com.yingxia.tv.data;

import com.yingxia.tv.*;
import com.yingxia.tv.core.*;
import java.io.*;
import java.util.*;
import org.json.*;

/** Port of the GD Studio catalog/provider used by TVCore/MusicCatalog and MusicProviders. */
public final class MusicClient {
  private final Http http = YingxiaApp.get().http;

  public List<JSONObject> search(String query, String source, int page) throws Exception {
    String url =
        Http.query(
            "https://music-api.gdstudio.xyz/api.php",
            Http.params(
                "types", "search", "source", source, "name", query, "count", "20", "pages",
                "" + page));
    JSONArray songs = new JSONArray(http.get(url).text());
    List<JSONObject> out = new ArrayList<>();
    for (JSONObject song : Json.objects(songs))
      out.add(
          Json.obj(
              "id",
              source + ":" + song.optString("id"),
              "songId",
              song.optString("id"),
              "source",
              source,
              "name",
              song.optString("name"),
              "artist",
              song.opt("artist") instanceof JSONArray
                  ? String.join(" / ", strings(Json.array(song.opt("artist"))))
                  : song.optString("artist"),
              "album",
              song.optString("album"),
              "picId",
              song.optString("pic_id"),
              "lyricId",
              song.optString("lyric_id")));
    return out;
  }

  public JSONObject play(JSONObject song) throws Exception {
    JSONObject response =
        http.json(
            Http.query(
                "https://music-api.gdstudio.xyz/api.php",
                Http.params(
                    "types",
                    "url",
                    "source",
                    song.optString("source"),
                    "id",
                    song.optString("songId"),
                    "br",
                    "320")));
    String url = response.optString("url");
    if (url.isEmpty()) throw new IOException("这首歌暂时没有可播放的音源，请尝试其他平台");
    JSONObject play = Json.copy(song);
    play.put("url", url).put("music", true).put("episode", song.optString("artist"));
    try {
      JSONObject pic =
          http.json(
              Http.query(
                  "https://music-api.gdstudio.xyz/api.php",
                  Http.params(
                      "types",
                      "pic",
                      "source",
                      song.optString("source"),
                      "id",
                      song.optString("picId"),
                      "size",
                      "500")));
      play.put("poster", pic.optString("url"));
    } catch (Exception ignored) {
    }
    return play;
  }

  public String lyrics(JSONObject song) throws Exception {
    return http.json(
            Http.query(
                "https://music-api.gdstudio.xyz/api.php",
                Http.params(
                    "types",
                    "lyric",
                    "source",
                    song.optString("source"),
                    "id",
                    song.optString("lyricId", song.optString("songId")))))
        .optString("lyric");
  }

  private static List<String> strings(JSONArray a) {
    List<String> s = new ArrayList<>();
    for (int i = 0; i < a.length(); i++) s.add(a.optString(i));
    return s;
  }
}
