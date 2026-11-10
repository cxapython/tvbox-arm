package com.yingxia.tv.data;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import com.yingxia.tv.core.AdSkip;
import com.yingxia.tv.core.MediaPayload;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Streaming boundary for conservative HLS ad cleaning and PNG-prefixed MPEG-TS segments. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlaybackDataSource implements DataSource {
  private static final int LOOKAHEAD = 8192;
  private static final int MAX_PLAYLIST = 4 << 20;
  private final DataSource upstream;
  private final boolean cleanAds;
  private byte[] pending = new byte[0];
  private int pendingOffset;
  private boolean initialized, wholePayload;
  private long removedPrefix;

  public PlaybackDataSource(DataSource upstream, boolean cleanAds) {
    this.upstream = upstream;
    this.cleanAds = cleanAds;
  }

  @Override public void addTransferListener(TransferListener listener) {
    upstream.addTransferListener(listener);
  }

  @Override public long open(DataSpec dataSpec) throws IOException {
    initialized = false;
    wholePayload = false;
    removedPrefix = 0;
    pending = new byte[0];
    pendingOffset = 0;
    long remaining = upstream.open(dataSpec);
    try {
      initialize();
      if (wholePayload) return pending.length;
      return remaining == C.LENGTH_UNSET ? C.LENGTH_UNSET : Math.max(0, remaining - removedPrefix);
    } catch (IOException error) {
      upstream.close();
      throw error;
    }
  }

  @Override public Uri getUri() {
    return upstream.getUri();
  }

  @Override public Map<String, List<String>> getResponseHeaders() {
    return upstream.getResponseHeaders();
  }

  @Override public int read(byte[] buffer, int offset, int length) throws IOException {
    if (length == 0) return 0;
    if (!initialized) initialize();
    if (pendingOffset < pending.length) {
      int count = Math.min(length, pending.length - pendingOffset);
      System.arraycopy(pending, pendingOffset, buffer, offset, count);
      pendingOffset += count;
      return count;
    }
    return upstream.read(buffer, offset, length);
  }

  private void initialize() throws IOException {
    initialized = true;
    ByteArrayOutputStream prefix = new ByteArrayOutputStream(LOOKAHEAD);
    readInto(prefix, LOOKAHEAD, false);
    byte[] first = prefix.toByteArray();
    if (isPlaylist(first)) {
      readInto(prefix, MAX_PLAYLIST, true);
      byte[] playlist = prefix.toByteArray();
      if (playlist.length > MAX_PLAYLIST) throw new IOException("HLS 清单超过 4 MiB，无法安全清洗");
      if (cleanAds) {
        Uri uri = getUri();
        AdSkip.Result result = AdSkip.clean(new String(playlist, StandardCharsets.UTF_8),
            uri == null ? "" : uri.toString());
        if (result != null) {
          android.util.Log.i("Yingxia.Player", "Removed HLS ad runs=" + result.runs
              + " seconds=" + (long) result.seconds + " host=" + result.host);
          playlist = result.text.getBytes(StandardCharsets.UTF_8);
        }
      }
      pending = playlist;
      wholePayload = true;
      return;
    }
    pending = MediaPayload.transportStream(first);
    removedPrefix = first.length - pending.length;
  }

  private void readInto(ByteArrayOutputStream out, int limit, boolean enforceLimit)
      throws IOException {
    byte[] buffer = new byte[16384];
    while (out.size() < limit || enforceLimit && out.size() == limit) {
      int remaining = enforceLimit && out.size() == limit ? 1 : Math.min(buffer.length, limit - out.size());
      int count = upstream.read(buffer, 0, Math.max(1, remaining));
      if (count == C.RESULT_END_OF_INPUT) return;
      if (count == 0) continue;
      out.write(buffer, 0, count);
      if (enforceLimit && out.size() > limit) return;
      if (!enforceLimit && out.size() >= limit) return;
    }
  }

  private static boolean isPlaylist(byte[] bytes) {
    String prefix = new String(bytes, StandardCharsets.UTF_8);
    if (prefix.startsWith("\uFEFF")) prefix = prefix.substring(1);
    return prefix.trim().startsWith("#EXTM3U");
  }

  @Override public void close() throws IOException {
    upstream.close();
    pending = new byte[0];
    pendingOffset = 0;
    initialized = false;
    wholePayload = false;
    removedPrefix = 0;
  }
}
