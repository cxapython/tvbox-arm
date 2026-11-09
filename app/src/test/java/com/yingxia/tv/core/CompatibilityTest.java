package com.yingxia.tv.core;

import static org.junit.Assert.*;

import com.yingxia.tv.runtime.Crypto;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.json.*;
import org.junit.Test;

public class CompatibilityTest {
  private static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  public void commentsTrailingCommasAndControlsAreAcceptedOnlyAtBoundary() throws Exception {
    String text =
        "\uFEFF{\n"
            + "// comment\n"
            + "\"sites\":[{\"key\":\"a\",\"type\":1,\"api\":\"https://example.com/a//x#hash\",\"name\":\"line\n"
            + "next\",},],/*a*/}";
    JSONObject o = ConfigCodec.decode(utf8(text));
    assertEquals("line\nnext", o.getJSONArray("sites").getJSONObject(0).getString("name"));
    assertEquals(
        "https://example.com/a//x#hash", o.getJSONArray("sites").getJSONObject(0).getString("api"));
  }

  @Test
  public void stringsContainingCommaCloseDoNotChange() throws Exception {
    assertEquals(",]}", ConfigCodec.decode(utf8("{\"x\":\",]}\",}")).getString("x"));
  }

  @Test
  public void escapedQuotesDoNotStartComments() throws Exception {
    assertEquals(
        "a\"//hello", ConfigCodec.decode(utf8("{\"x\":\"a\\\"//hello\", # a\n}")).getString("x"));
  }

  @Test
  public void catvodImageEnvelopeIsDecodedFromBinaryPrefix() throws Exception {
    byte[] body = utf8("{\"hello\":\"世界\"}");
    byte[] envelope =
        ("\u0000\u00ffPNGabcdefgh**" + Base64.getEncoder().encodeToString(body))
            .getBytes(StandardCharsets.ISO_8859_1);
    assertEquals("世界", ConfigCodec.decode(envelope).getString("hello"));
  }

  @Test
  public void catvodEncryptedConfigurationRoundTrip() throws Exception {
    String key = "secret0000000000",
        iv = "1234567890123000",
        plain = "{\"sites\":[{\"key\":\"a\",\"api\":\"a\",\"type\":1}]}";
    Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
    c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(utf8(key), "AES"), new IvParameterSpec(utf8(iv)));
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    out.write(utf8("$#SeCrEt#$"));
    out.write(c.doFinal(utf8(plain)));
    out.write(utf8("1234567890123"));
    assertEquals(
        1,
        ConfigCodec.decode(utf8(ConfigCodec.hex(out.toByteArray())))
            .getJSONArray("sites")
            .length());
  }

  @Test
  public void htmlAndEmptyConfigurationAreRejected() throws Exception {
    for (String s :
        List.of("<!doctype html><html>error</html>", "{\"sites\":[]}", "{broken json}")) {
      try {
        ConfigCodec.subscription(utf8(s), "https://example.com/config");
        fail(s);
      } catch (Exception expected) {
      }
    }
  }

  @Test
  public void directoryIsNotMistakenForSubscription() throws Exception {
    try {
      ConfigCodec.subscription(
          utf8("{\"urls\":[{\"url\":\"https://example.com/a\"}]}"), "https://example.com");
      fail();
    } catch (Exception e) {
      assertTrue(e.getMessage().contains("目录"));
    }
  }

  @Test
  public void rawPlaylistCanBeImportedAsSubscription() throws Exception {
    JSONObject config =
        ConfigCodec.subscription(
            utf8("#EXTM3U\n#EXTINF:-1,频道\nhttps://example.com/live.m3u8"),
            "https://example.com/list.m3u");
    assertEquals(
        "https://example.com/list.m3u",
        config.getJSONArray("lives").getJSONObject(0).getString("url"));
  }

  @Test
  public void addressPreservesEscapesAndResolvesRelativePaths() {
    assertEquals(
        "https://example.com/a/x%26y",
        ConfigCodec.address("../x%26y", "https://example.com/a/b/config.json"));
    assertEquals("http://192.168.1.8:8080/a", ConfigCodec.address("192.168.1.8:8080/a", null));
    assertEquals("https://example.com", ConfigCodec.address("https：／／example.com", null));
  }

  @Test(expected = IllegalArgumentException.class)
  public void unsupportedURLSchemeIsRejected() {
    ConfigCodec.address("javascript:alert(1)", null);
  }

  @Test
  public void m3uGroupsHeadersRelativeAddressesAndDeduplication() {
    String text =
        "#EXTM3U\n"
            + "#EXTINF:-1 tvg-logo=\"cover.png\" group-title=\"新闻\",频道一\n"
            + "#EXTVLCOPT:http-user-agent=Custom\n"
            + "stream.m3u8|Referer=https%3A%2F%2Fexample.com%2F\n"
            + "#EXTINF:-1 group-title=\"新闻\",频道一\n"
            + "stream.m3u8\n"
            + "#EXTINF:-1,频道二\n"
            + "https://example.com/2";
    List<JSONObject> out =
        PlaylistParser.parse(text, "https://example.com/a/list.m3u", Map.of("X-Test", "1"));
    assertEquals(2, out.size());
    assertEquals("https://example.com/a/stream.m3u8", out.get(0).optString("url"));
    assertEquals("Custom", Json.child(out.get(0), "header").optString("User-Agent"));
    assertEquals("https://example.com/", Json.child(out.get(0), "header").optString("Referer"));
    assertFalse(Json.child(out.get(1), "header").has("User-Agent"));
  }

  @Test
  public void txtPlaylistGenreLinesAreNotChannels() {
    List<JSONObject> channels =
        PlaylistParser.parse(
            "央视,#genre#\n"
                + "CCTV1,https://example.com/1\n"
                + "CCTV2,javascript:test\n"
                + "卫视,#genre#\n"
                + "湖南,https://example.com/2",
            "https://example.com/list.txt",
            Map.of());
    assertEquals(2, channels.size());
    assertEquals("卫视", channels.get(1).optString("group"));
  }

  @Test
  public void episodesKeepLinesAndURLDollarCharacters() {
    JSONObject v =
        Json.obj(
            "vod_play_from",
            "线路一$$$线路二",
            "vod_play_url",
            "第1集$https://example.com/v?a=$token#第2集$https://example.com/2$$$正片$https://example.com/3");
    List<JSONObject> e = Catalog.episodes(v);
    assertEquals(3, e.size());
    assertEquals("https://example.com/v?a=$token", e.get(0).optString("url"));
    assertEquals("线路二", e.get(2).optString("flag"));
  }

  @Test
  public void nativeCryptoContractMatchesAppleHost() throws Exception {
    assertEquals(
        "900150983cd24fb0d6963f7d28e17f72",
        new JSONObject(
                Crypto.run(Json.obj("op", "hash", "alg", "md5", "dataText", "abc").toString()))
            .getString("hex"));
    JSONObject encrypted =
        new JSONObject(
            Crypto.run(
                Json.obj(
                        "op",
                        "aes",
                        "encrypt",
                        true,
                        "mode",
                        "CBC",
                        "padding",
                        true,
                        "keyText",
                        "1234567890123456",
                        "ivText",
                        "1234567890123456",
                        "dataText",
                        "中文😀")
                    .toString()));
    JSONObject plain =
        new JSONObject(
            Crypto.run(
                Json.obj(
                        "op",
                        "aes",
                        "encrypt",
                        false,
                        "mode",
                        "CBC",
                        "padding",
                        true,
                        "keyText",
                        "1234567890123456",
                        "ivText",
                        "1234567890123456",
                        "data",
                        encrypted.getString("data"),
                        "output",
                        "text")
                    .toString()));
    assertEquals("中文😀", plain.getString("text"));
  }
}
