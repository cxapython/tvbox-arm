package com.yingxia.tv.core;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.json.*;

/** Port of SubscriptionDocument.swift. Compatibility is confined to the import boundary. */
public final class ConfigCodec {
  private static final int LIMIT = 20_000_000;

  public static String address(String input, String base) {
    String s = input.trim().replace('：', ':').replace('／', '/');
    if (base == null && s.matches("^[^\\s/?#:]+\\.[^\\s/?#:]+(:[0-9]+)?([/?#]\\S*)?$"))
      s = "http://" + s;
    if (base == null) s = s.replaceFirst("(?i)^(https?):/+", "$1://");
    try {
      URI uri =
          base == null
              ? new URI(s.replace(" ", "%20"))
              : new URI(base).resolve(s.replace(" ", "%20"));
      if (!List.of("http", "https", "assets").contains(uri.getScheme())
          || uri.getAuthority() == null) throw new Exception();
      return uri.toASCIIString();
    } catch (Exception e) {
      throw new IllegalArgumentException("请输入有效的 HTTP 或 HTTPS 地址");
    }
  }

  public static JSONObject decode(byte[] input) throws Exception {
    if (input.length > LIMIT) throw new IllegalArgumentException("订阅超过 20 MB，请使用较小的订阅");
    String text = new String(input, StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", "").trim();
    if (!(text.startsWith("{")
        || text.startsWith("[")
        || text.startsWith("/")
        || text.startsWith("#"))) {
      // Image envelopes can have a non-UTF8 prefix; the ASCII marker survives ISO-8859-1.
      Matcher m =
          Pattern.compile("[A-Za-z0-9]{8}\\*\\*")
              .matcher(new String(input, StandardCharsets.ISO_8859_1));
      if (m.find())
        text =
            new String(
                    Base64.getMimeDecoder()
                        .decode(Arrays.copyOfRange(input, m.end(), input.length)),
                    StandardCharsets.UTF_8)
                .replaceFirst("^\\uFEFF", "")
                .trim();
    }
    if (text.toLowerCase(Locale.ROOT).startsWith("<!doctype")
        || text.toLowerCase(Locale.ROOT).startsWith("<html"))
      throw new IllegalArgumentException("订阅服务器返回了网页，请确认配置地址");
    if (text.startsWith("2423")) text = decrypt(text);
    return new JSONObject(normalize(text));
  }

  static String decrypt(String text) throws Exception {
    byte[] bytes = unhex(text.replaceAll("\\s", ""));
    int end = bytes.length - 13, keyEnd = -1;
    for (int i = 2; i + 1 < end; i++)
      if (bytes[i] == '#' && bytes[i + 1] == '$') {
        keyEnd = i;
        break;
      }
    if (keyEnd < 0 || keyEnd - 2 > 16) throw new IllegalArgumentException("加密订阅格式不正确");
    byte[] key = pad(new String(bytes, 2, keyEnd - 2, StandardCharsets.UTF_8)),
        iv = pad(new String(bytes, end, 13, StandardCharsets.UTF_8));
    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
    return new String(
            cipher.doFinal(Arrays.copyOfRange(bytes, keyEnd + 2, end)), StandardCharsets.UTF_8)
        .replaceFirst("^\\uFEFF", "");
  }

  private static byte[] pad(String s) {
    return (s.toLowerCase(Locale.ROOT) + "0000000000000000")
        .substring(0, 16)
        .getBytes(StandardCharsets.UTF_8);
  }

  public static String normalize(String s) {
    StringBuilder b = new StringBuilder();
    boolean quoted = false, escape = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i), n = i + 1 < s.length() ? s.charAt(i + 1) : 0;
      if (quoted) {
        if (escape) {
          b.append(c);
          escape = false;
          continue;
        }
        if (c == '\\') {
          b.append(c);
          escape = true;
          continue;
        }
        if (c == '"') quoted = false;
        if (c < 32) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
        else b.append(c);
      } else if (c == '"') {
        quoted = true;
        b.append(c);
      } else if (c == '#' || (c == '/' && n == '/')) {
        while (i < s.length() && s.charAt(i) != '\n' && s.charAt(i) != '\r') i++;
        b.append('\n');
      } else if (c == '/' && n == '*') {
        int end = s.indexOf("*/", i + 2);
        if (end < 0) throw new IllegalArgumentException("订阅的注释没有结束");
        b.append(' ');
        i = end + 1;
      } else b.append(c);
    }
    StringBuilder out = new StringBuilder();
    quoted = false;
    escape = false;
    for (int i = 0; i < b.length(); i++) {
      char c = b.charAt(i);
      if (quoted) {
        if (escape) escape = false;
        else if (c == '\\') escape = true;
        else if (c == '"') quoted = false;
      } else if (c == '"') quoted = true;
      else if (c == ',') {
        int j = i + 1;
        while (j < b.length() && Character.isWhitespace(b.charAt(j))) j++;
        if (j < b.length() && (b.charAt(j) == '}' || b.charAt(j) == ']')) continue;
      }
      out.append(c);
    }
    return out.toString();
  }

  public static JSONObject subscription(byte[] data, String origin) throws Exception {
    JSONObject config;
    try {
      config = decode(data);
    } catch (Exception error) {
      String s = new String(data, StandardCharsets.UTF_8).trim();
      if ((s.startsWith("#EXTM3U") || s.startsWith("#EXTINF:") || s.split("\n")[0].contains(","))
          && !PlaylistParser.parse(s, origin, Collections.emptyMap()).isEmpty())
        config = Json.obj("lives", new JSONArray().put(Json.obj("name", "直播", "url", origin)));
      else throw error;
    }
    if (Json.array(config.opt("sites")).length() == 0
        && Json.array(config.opt("lives")).length() == 0) {
      if (config.has("urls") || config.has("storeHouse"))
        throw new IllegalArgumentException("这是订阅目录，请选择其中一个订阅地址");
      throw new IllegalArgumentException("没有找到可用的点播源或直播源");
    }
    return config;
  }

  public static String hash(String s) {
    try {
      return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String hex(byte[] bytes) {
    StringBuilder s = new StringBuilder();
    for (byte b : bytes) s.append(String.format(Locale.ROOT, "%02x", b & 255));
    return s.toString();
  }

  public static byte[] unhex(String s) {
    if ((s.length() & 1) != 0) throw new IllegalArgumentException("无效 hex");
    byte[] out = new byte[s.length() / 2];
    for (int i = 0; i < out.length; i++)
      out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
    return out;
  }
}
