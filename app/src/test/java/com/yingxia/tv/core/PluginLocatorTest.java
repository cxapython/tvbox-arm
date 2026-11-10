package com.yingxia.tv.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import org.junit.Test;

public class PluginLocatorTest {
  private static final String PREFIX = "Lcom/github/catvod/spider/";

  @Test
  public void storedAndDeflatedDexFilesAreScannedAndDeduplicated() throws Exception {
    byte[] first =
        utf8(
            PREFIX
                + "Stored;"
                + PREFIX
                + "Shared;"
                + PREFIX
                + "Outer$Inner;"
                + PREFIX
                + ";"
                + PREFIX
                + "nested/Excluded;"
                + "Lcom/github/catvod/other/Excluded;");
    byte[] second = utf8(PREFIX + "Deflated;" + PREFIX + "Shared;");
    byte[] archive = zip(entry("classes.dex", first, false), entry("classes2.dex", second, true));

    assertEquals(
        new HashSet<>(Arrays.asList("Stored", "Shared", "Deflated")),
        PluginLocator.spiderClasses(archive));
  }

  @Test
  public void prependedBytesDoNotChangeRecordedLocalHeaderOffsets() throws Exception {
    byte[] zip = zip(entry("classes.dex", utf8(PREFIX + "Prefixed;"), true));
    byte[] prefix = utf8("\u0000\u00ffPNG not really an image\n");
    byte[] disguised = new byte[prefix.length + zip.length];
    System.arraycopy(prefix, 0, disguised, 0, prefix.length);
    System.arraycopy(zip, 0, disguised, prefix.length, zip.length);

    assertEquals(Set.of("Prefixed"), PluginLocator.spiderClasses(disguised));
    byte[] normalized = PluginLocator.normalizeZip(disguised);
    assertEquals(zip.length, normalized.length);
    assertEquals('P', normalized[0]);
    assertEquals('K', normalized[1]);
    assertEquals(Set.of("Prefixed"), PluginLocator.spiderClasses(normalized));
    assertTrue(PluginLocator.normalizeZip(zip) == zip);
  }

  @Test
  public void classNamesAreStrictUtf8AndAtMost128Bytes() throws Exception {
    String longest = "a".repeat(128);
    ByteArrayOutputStream dex = new ByteArrayOutputStream();
    dex.write(utf8(PREFIX + longest + ";" + PREFIX + "b".repeat(129) + ";" + PREFIX + "影视;"));
    dex.write(utf8(PREFIX));
    dex.write(0xc3);
    dex.write(0x28);
    dex.write(';');

    assertEquals(
        new HashSet<>(Arrays.asList(longest, "影视")),
        PluginLocator.spiderClasses(zip(entry("classes.dex", dex.toByteArray(), false))));
  }

  @Test
  public void nonZipInputReturnsAnEmptySet() {
    assertTrue(PluginLocator.spiderClasses(utf8("not a ZIP archive")).isEmpty());
    assertTrue(PluginLocator.spiderClasses(new byte[0]).isEmpty());
  }

  private static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static Entry entry(String name, byte[] data, boolean deflate) {
    byte[] compressed = deflate ? deflate(data) : data;
    CRC32 crc = new CRC32();
    crc.update(data);
    return new Entry(utf8(name), data.length, compressed, deflate ? 8 : 0, crc.getValue());
  }

  private static byte[] deflate(byte[] data) {
    Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
    try {
      deflater.setInput(data);
      deflater.finish();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[256];
      while (!deflater.finished()) {
        int count = deflater.deflate(buffer);
        out.write(buffer, 0, count);
      }
      return out.toByteArray();
    } finally {
      deflater.end();
    }
  }

  private static byte[] zip(Entry... entries) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (Entry entry : entries) {
      entry.offset = out.size();
      write32(out, 0x0403_4b50L);
      write16(out, 20);
      write16(out, 0);
      write16(out, entry.method);
      write16(out, 0);
      write16(out, 0);
      write32(out, entry.crc);
      write32(out, entry.compressed.length);
      write32(out, entry.length);
      write16(out, entry.name.length);
      write16(out, 0);
      out.write(entry.name, 0, entry.name.length);
      out.write(entry.compressed, 0, entry.compressed.length);
    }
    int centralOffset = out.size();
    for (Entry entry : entries) {
      write32(out, 0x0201_4b50L);
      write16(out, 20);
      write16(out, 20);
      write16(out, 0);
      write16(out, entry.method);
      write16(out, 0);
      write16(out, 0);
      write32(out, entry.crc);
      write32(out, entry.compressed.length);
      write32(out, entry.length);
      write16(out, entry.name.length);
      write16(out, 0);
      write16(out, 0);
      write16(out, 0);
      write16(out, 0);
      write32(out, 0);
      write32(out, entry.offset);
      out.write(entry.name, 0, entry.name.length);
    }
    int centralSize = out.size() - centralOffset;
    write32(out, 0x0605_4b50L);
    write16(out, 0);
    write16(out, 0);
    write16(out, entries.length);
    write16(out, entries.length);
    write32(out, centralSize);
    write32(out, centralOffset);
    write16(out, 0);
    return out.toByteArray();
  }

  private static void write16(ByteArrayOutputStream out, int value) {
    out.write(value & 0xff);
    out.write((value >>> 8) & 0xff);
  }

  private static void write32(ByteArrayOutputStream out, long value) {
    write16(out, (int) value);
    write16(out, (int) (value >>> 16));
  }

  private static final class Entry {
    final byte[] name;
    final int length;
    final byte[] compressed;
    final int method;
    final long crc;
    int offset;

    Entry(byte[] name, int length, byte[] compressed, int method, long crc) {
      this.name = name;
      this.length = length;
      this.compressed = compressed;
      this.method = method;
      this.crc = crc;
    }
  }
}
