package com.yingxia.tv.core;

import static org.junit.Assert.*;
import org.junit.Test;

public class ItaloRadioTest {
  private String response(long server, String duration) {
    return Json.obj("song", Json.obj("title", "Time To Rock", "artist", "Gabry Ponte",
        "duration", duration, "albumcover", Json.obj("style_url", "https://italosound.com/cover.jpg")),
        "start_time", 1000, "servertime", server).toString();
  }

  @Test public void songTimingUsesTheServerClock() throws Exception {
    ItaloRadio radio = ItaloRadio.parse(response(1100, "273776"));
    assertEquals("Time To Rock", radio.title);
    assertEquals("Gabry Ponte", radio.artist);
    assertEquals("https://italosound.com/cover.jpg", radio.cover);
    assertEquals(100000, radio.elapsed);
    assertEquals(273776, radio.duration);
    assertEquals(176000, radio.nextPoll);
    assertEquals(105000, radio.progress(5000));
    assertEquals(273776, radio.progress(1000000));
  }

  @Test public void missingAndInvalidTimingHaveBoundedRetries() throws Exception {
    assertEquals(30000, ItaloRadio.parse(response(1100, "")).nextPoll);
    assertEquals(5000, ItaloRadio.parse(response(1059, "60000")).nextPoll);
    assertEquals(180000, ItaloRadio.parse(response(1000, "3600000")).nextPoll);
    assertEquals(0, ItaloRadio.parse(response(999, "60000")).elapsed);
  }
}
