package com.github.catvod.utils;

import java.io.*;

public final class Path {
  public static void requireSpace(File directory, long wanted) throws IOException {
    if (wanted > 0
        && directory.getUsableSpace() > 0
        && directory.getUsableSpace() < wanted + 16L * 1024 * 1024)
      throw new IOException("电视存储空间不足");
  }

  public static void chmod(File file) {
    file.setReadable(true, true);
    file.setWritable(true, true);
  }
}
