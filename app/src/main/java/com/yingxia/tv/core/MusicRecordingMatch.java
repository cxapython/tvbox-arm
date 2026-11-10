package com.yingxia.tv.core;

import java.util.Locale;
import org.json.JSONObject;

/** Conservative recording match for lyrics across catalogs; live/remix names remain distinct. */
public final class MusicRecordingMatch {
  private MusicRecordingMatch() {}
  private static String normalized(String value) {
    return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
  }
  public static boolean same(JSONObject a, JSONObject b) {
    String name = normalized(a.optString("name")), artist = normalized(a.optString("artist"));
    long duration = a.optLong("duration"), other = b.optLong("duration");
    return !name.isEmpty() && !artist.isEmpty() && duration > 0 && other > 0
        && name.equals(normalized(b.optString("name"))) && artist.equals(normalized(b.optString("artist")))
        && Math.abs(duration - other) <= 3000;
  }
}
