package com.yingxia.tv.core;

import java.util.Locale;

/** Reports decoder/extractor evidence, never infers an audio format from a requested tier or URL. */
public final class MusicAudioQuality {
  private MusicAudioQuality() {}

  public static String describe(String requested, String mime, int bitrate, int sampleRate) {
    if (mime == null || mime.isEmpty()) return "实际音质待确认";
    boolean lossless = mime.equals("audio/flac") || mime.equals("audio/alac") || mime.equals("audio/raw");
    String codec;
    switch (mime) {
      case "audio/flac": codec = "FLAC"; break;
      case "audio/alac": codec = "ALAC"; break;
      case "audio/raw": codec = "PCM"; break;
      case "audio/mpeg": codec = "MP3"; break;
      case "audio/mp4a-latm": codec = "AAC"; break;
      case "audio/opus": codec = "Opus"; break;
      case "audio/vorbis": codec = "Vorbis"; break;
      default: codec = mime;
    }
    String label = "实际 " + codec;
    if (bitrate > 0 && !lossless) label += " · " + Math.round(bitrate / 1000f) + " kbps";
    else if (!lossless) label += " · 码率待确认";
    if (sampleRate > 0) label += " · " + String.format(Locale.ROOT, "%.1f kHz", sampleRate / 1000f);
    String tier = MusicResolver.normalizeQuality(requested);
    if (!"original".equals(requested) && !lossless && (tier.equals(MusicResolver.FLAC) || tier.equals(MusicResolver.HIGH) && bitrate > 0 && bitrate < 288000))
      label += " · 已降级";
    return label;
  }
}
