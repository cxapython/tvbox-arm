package com.yingxia.tv.core;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Locates top-level CatVod spider class names in the DEX files of a plugin archive. */
public final class PluginLocator {
  private static final byte[] SPIDER_PREFIX =
      "Lcom/github/catvod/spider/".getBytes(StandardCharsets.UTF_8);
  private static final int MAX_CLASS_NAME_BYTES = 128;
  private static final int MAX_ENTRY_SIZE = 64 << 20;
  private static final int EOCD_SEARCH_SIZE = 65_557;

  private PluginLocator() {}

  /**
   * Returns simple class names below {@code com.github.catvod.spider}; nested packages and inner
   * classes are excluded. Malformed archives and entries simply contribute no names.
   */
  public static Set<String> spiderClasses(byte[] archive) {
    Objects.requireNonNull(archive, "archive");
    Set<String> names = new HashSet<>();
    for (byte[] dex : dexEntries(archive)) {
      int cursor = 0;
      while (cursor < dex.length) {
        int match = indexOf(dex, SPIDER_PREFIX, cursor);
        if (match < 0) break;
        int nameStart = match + SPIDER_PREFIX.length;
        int end = nameStart;
        while (end < dex.length
            && end - nameStart < MAX_CLASS_NAME_BYTES
            && dex[end] != ';') {
          end++;
        }
        if (end < dex.length && dex[end] == ';') {
          String name = strictUtf8(dex, nameStart, end);
          if (name != null
              && !name.isEmpty()
              && name.indexOf('/') < 0
              && name.indexOf('$') < 0) {
            names.add(name);
          }
        }
        cursor = nameStart;
      }
    }
    return names;
  }

  private static String strictUtf8(byte[] bytes, int start, int end) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes, start, end - start))
          .toString();
    } catch (CharacterCodingException malformed) {
      return null;
    }
  }

  private static int indexOf(byte[] bytes, byte[] wanted, int from) {
    int last = bytes.length - wanted.length;
    for (int i = from; i <= last; i++) {
      int j = 0;
      while (j < wanted.length && bytes[i + j] == wanted[j]) j++;
      if (j == wanted.length) return i;
    }
    return -1;
  }

  /** Strip a disguised archive's prefix so Android's ZipFile/DexClassLoader sees a normal ZIP. */
  public static byte[] normalizeZip(byte[] archive) {
    Objects.requireNonNull(archive, "archive");
    for (int end = archive.length - 22;
        end >= Math.max(0, archive.length - EOCD_SEARCH_SIZE);
        end--) {
      if (u32(archive, end) != 0x0605_4b50L) continue;
      long shift = end - u32(archive, end + 12) - u32(archive, end + 16);
      if (shift > 0 && shift < archive.length && u32(archive, shift) == 0x0403_4b50L)
        return Arrays.copyOfRange(archive, (int) shift, archive.length);
      return archive;
    }
    return archive;
  }

  /** Minimal ZIP reader which also accepts arbitrary bytes prepended to an otherwise normal ZIP. */
  private static List<byte[]> dexEntries(byte[] archive) {
    int end = -1;
    for (int position = archive.length - 22;
        position >= Math.max(0, archive.length - EOCD_SEARCH_SIZE);
        position--) {
      if (u32(archive, position) == 0x0605_4b50L) {
        end = position;
        break;
      }
    }
    if (end < 0) return List.of();

    long size = u32(archive, end + 12);
    long declared = u32(archive, end + 16);
    long shift = end - size - declared;
    long cursor = end - size;
    List<byte[]> found = new ArrayList<>();
    int count = u16(archive, end + 10);
    for (int i = 0; i < count; i++) {
      if (cursor < 0 || u32(archive, cursor) != 0x0201_4b50L) break;
      int method = u16(archive, cursor + 10);
      long compressed = u32(archive, cursor + 20);
      long length = u32(archive, cursor + 24);
      int nameLength = u16(archive, cursor + 28);
      int extra = u16(archive, cursor + 30);
      int comment = u16(archive, cursor + 32);
      long local = u32(archive, cursor + 42) + shift;
      int nameStart = (int) Math.min(archive.length, Math.max(0, cursor + 46));
      int nameEnd =
          (int) Math.min(archive.length, Math.max(0, cursor + 46 + nameLength));
      String name = new String(archive, nameStart, nameEnd - nameStart, StandardCharsets.UTF_8);
      cursor += 46 + nameLength + extra + comment;
      if (!name.endsWith(".dex")
          || local < 0
          || u32(archive, local) != 0x0403_4b50L
          || length <= 0
          || length >= MAX_ENTRY_SIZE) {
        continue;
      }
      long start = local + 30 + u16(archive, local + 26) + u16(archive, local + 28);
      if (start < 0 || compressed < 0 || start + compressed > archive.length) continue;
      if (method == 0) {
        // A valid stored entry has compressed == length. Also cap malformed declarations.
        if (compressed >= MAX_ENTRY_SIZE) continue;
        found.add(Arrays.copyOfRange(archive, (int) start, (int) (start + compressed)));
      } else if (method == 8) {
        byte[] output = inflate(archive, (int) start, (int) compressed, (int) length);
        if (output != null) found.add(output);
      }
    }
    return found;
  }

  private static byte[] inflate(byte[] archive, int start, int compressed, int length) {
    Inflater inflater = new Inflater(true);
    try {
      inflater.setInput(archive, start, compressed);
      byte[] output = new byte[length];
      return inflater.inflate(output) == length ? output : null;
    } catch (DataFormatException malformed) {
      return null;
    } finally {
      inflater.end();
    }
  }

  private static int u16(byte[] bytes, long at) {
    if (at < 0 || at + 1 >= bytes.length) return 0;
    int position = (int) at;
    return (bytes[position] & 0xff) | ((bytes[position + 1] & 0xff) << 8);
  }

  private static long u32(byte[] bytes, long at) {
    if (at < 0 || at + 3 >= bytes.length) return 0;
    return u16(bytes, at) | ((long) u16(bytes, at + 2) << 16);
  }
}
