package com.yingxia.tv.core;

import static org.junit.Assert.*;

import java.util.Arrays;
import org.junit.Test;

public class MediaPayloadTest {
  private static final byte[] PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

  private static byte[] transportStream(int packets) {
    byte[] data = new byte[188 * packets];
    for (int index = 0; index < packets; index++) data[index * 188] = 0x47;
    return data;
  }

  private static byte[] prefixed(int offset, int packets) {
    byte[] prefix = new byte[offset];
    System.arraycopy(PNG, 0, prefix, 0, PNG.length);
    byte[] ts = transportStream(packets);
    byte[] data = Arrays.copyOf(prefix, prefix.length + ts.length);
    System.arraycopy(ts, 0, data, prefix.length, ts.length);
    return data;
  }

  @Test
  public void prefixedTransportStreamIsNormalized() {
    byte[] input = prefixed(61, 6);
    byte[] expected = Arrays.copyOfRange(input, 61, input.length);
    assertArrayEquals(expected, MediaPayload.transportStream(input));
    byte[] plain = transportStream(6);
    assertSame(plain, MediaPayload.transportStream(plain));
  }

  @Test
  public void transportStreamCanImmediatelyFollowSignature() {
    byte[] input = prefixed(8, 5);
    assertArrayEquals(transportStream(5), MediaPayload.transportStream(input));
  }

  @Test
  public void regularImagesEmptyAndUnrecognizedPayloadsArePreserved() {
    byte[] png = new byte[1208];
    System.arraycopy(PNG, 0, png, 0, PNG.length);
    png[61] = 0x47;
    assertSame(png, MediaPayload.transportStream(png));
    byte[] empty = new byte[0];
    assertSame(empty, MediaPayload.transportStream(empty));
  }

  @Test
  public void signatureAndFiveSyncsAreBothRequired() {
    byte[] fourSyncs = prefixed(61, 4);
    assertSame(fourSyncs, MediaPayload.transportStream(fourSyncs));

    byte[] noPng = transportStream(6);
    noPng[0] = 0;
    assertSame(noPng, MediaPayload.transportStream(noPng));

    byte[] wrongSignature = prefixed(61, 6);
    wrongSignature[7] = 0;
    assertSame(wrongSignature, MediaPayload.transportStream(wrongSignature));
  }

  @Test
  public void searchUsesOriginalUpperBound() {
    byte[] lastAccepted = prefixed(4095, 5);
    assertEquals(4095 + 188 * 5 - 4095, MediaPayload.transportStream(lastAccepted).length);
    assertArrayEquals(transportStream(5), MediaPayload.transportStream(lastAccepted));

    byte[] excluded = prefixed(4096, 5);
    assertSame(excluded, MediaPayload.transportStream(excluded));
    byte[] beyondSearchWindow = prefixed(8192, 5);
    assertSame(beyondSearchWindow, MediaPayload.transportStream(beyondSearchWindow));
  }

  @Test
  public void isolatedSyncBytesDoNotTriggerStripping() {
    byte[] input = new byte[1000];
    System.arraycopy(PNG, 0, input, 0, PNG.length);
    for (int packet = 0; packet < 4; packet++) input[61 + packet * 188] = 0x47;
    assertSame(input, MediaPayload.transportStream(input));
  }
}
