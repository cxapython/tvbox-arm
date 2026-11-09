package com.yingxia.tv.runtime;

import com.yingxia.tv.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.zip.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.json.*;

public final class Crypto {
  private static byte[] field(JSONObject o, String name) {
    if (o.has(name + "Text")) return o.optString(name + "Text").getBytes(StandardCharsets.UTF_8);
    if (o.has(name + "Hex")) return ConfigCodec.unhex(o.optString(name + "Hex"));
    return Base64.getDecoder().decode(o.optString(name));
  }

  public static String run(String request) throws Exception {
    JSONObject o = new JSONObject(request);
    String op = o.optString("op");
    byte[] result;
    switch (op) {
      case "random":
        int n = o.optInt("count");
        if (n < 1 || n > 1024) throw new IllegalArgumentException("invalid random count");
        result = new byte[n];
        new SecureRandom().nextBytes(result);
        break;
      case "hash":
        return Json.obj(
                "hex",
                ConfigCodec.hex(
                    MessageDigest.getInstance(o.optString("alg").replace("sha", "SHA-"))
                        .digest(field(o, "data"))))
            .toString();
      case "hmac":
        String alg = "Hmac" + o.optString("alg").toUpperCase(Locale.ROOT);
        Mac mac = Mac.getInstance(alg);
        mac.init(new SecretKeySpec(field(o, "key"), alg));
        return Json.obj("hex", ConfigCodec.hex(mac.doFinal(field(o, "data")))).toString();
      case "aes":
        String mode = o.optString("mode", "CBC").toUpperCase(Locale.ROOT);
        Cipher c =
            Cipher.getInstance(
                "AES/"
                    + mode
                    + "/"
                    + (o.optBoolean("padding", true) ? "PKCS5Padding" : "NoPadding"));
        SecretKeySpec key = new SecretKeySpec(field(o, "key"), "AES");
        int kind = o.optBoolean("encrypt") ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE;
        if (mode.equals("ECB")) c.init(kind, key);
        else c.init(kind, key, new IvParameterSpec(field(o, "iv")));
        result = c.doFinal(field(o, "data"));
        break;
      case "inflate":
        Inflater inflater = new Inflater(o.optBoolean("raw"));
        try (InflaterInputStream in =
            new InflaterInputStream(new ByteArrayInputStream(field(o, "data")), inflater)) {
          ByteArrayOutputStream out = new ByteArrayOutputStream();
          byte[] buf = new byte[8192];
          int read;
          while ((read = in.read(buf)) != -1) {
            if (out.size() + read > 32_000_000) throw new IOException("inflated data too large");
            out.write(buf, 0, read);
          }
          result = out.toByteArray();
        } finally {
          inflater.end();
        }
        break;
      case "rsaRaw":
        String pem = o.optString("key").replaceAll("-----[^-]+-----|\\s", "");
        PublicKey pub =
            KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
        Cipher rsa = Cipher.getInstance("RSA/ECB/NoPadding");
        rsa.init(Cipher.ENCRYPT_MODE, pub);
        result = rsa.doFinal(field(o, "data"));
        break;
      case "pow":
        long deadline = System.currentTimeMillis() + Math.min(o.optLong("limitMs", 20000), 30000);
        String target = o.optString("target").toLowerCase(Locale.ROOT),
            prefix = o.optString("prefix"),
            match = o.optString("match");
        MessageDigest digest =
            MessageDigest.getInstance(o.optString("alg").equals("md5") ? "MD5" : "SHA-256");
        long end = o.optLong("end", 2100000);
        for (long nonce = o.optLong("start"); nonce <= end && !target.isEmpty(); nonce++) {
          String hex =
              ConfigCodec.hex(digest.digest((prefix + nonce).getBytes(StandardCharsets.UTF_8)));
          if (match.equals("equal") ? hex.equals(target) : hex.startsWith(target))
            return Json.obj("nonce", "" + nonce).toString();
          if ((nonce & 1023) == 0 && System.currentTimeMillis() > deadline) break;
        }
        return Json.obj("nonce", "").toString();
      default:
        throw new IllegalArgumentException("unsupported crypto: " + op);
    }
    String output = o.optString("output");
    if (output.equals("hex")) return Json.obj("hex", ConfigCodec.hex(result)).toString();
    if (output.equals("text") || (op.equals("inflate") && !output.equals("base64")))
      return Json.obj("text", new String(result, StandardCharsets.UTF_8)).toString();
    return Json.obj("data", Base64.getEncoder().encodeToString(result)).toString();
  }
}
