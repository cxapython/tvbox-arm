package com.yingxia.tv.core;

import java.util.Arrays;

/** Normalizes media payloads without recognizing ordinary images as transport streams. */
public final class MediaPayload {
  private static final byte[] PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
  private static final int PACKET = 188;
  private static final int REQUIRED_SYNCS = 5;

  private MediaPayload() {}

  /** Removes a PNG prefix only when five consecutive 188-byte TS packet syncs prove the payload. */
  public static byte[] transportStream(byte[] data) {
    if (!startsWithPng(data) || data.length < PACKET * REQUIRED_SYNCS) return data;
    int available = Math.min(8192, data.length);
    int limit = Math.min(4096, available - PACKET * (REQUIRED_SYNCS - 1));
    for (int offset = PNG.length; offset < limit; offset++) {
      boolean transportStream = true;
      for (int packet = 0; packet < REQUIRED_SYNCS; packet++) {
        if (data[offset + packet * PACKET] != 0x47) {
          transportStream = false;
          break;
        }
      }
      if (transportStream) return Arrays.copyOfRange(data, offset, data.length);
    }
    return data;
  }

  private static boolean startsWithPng(byte[] data) {
    if (data.length < PNG.length) return false;
    for (int index = 0; index < PNG.length; index++) {
      if (data[index] != PNG[index]) return false;
    }
    return true;
  }
}
