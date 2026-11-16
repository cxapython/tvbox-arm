package com.yingxia.tv.core;
import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;
public class TrackChoiceTest {
  @Test public void picksSameTrackAfterReorderingOrSameLanguageAfterRename() {
    TrackChoice choice = new TrackChoice("简体", "zh");
    assertEquals(1, choice.find(List.of(new TrackChoice("English", "en"), new TrackChoice("简体", "zh"))));
    assertEquals(0, choice.find(List.of(new TrackChoice("中文", "zh"))));
    assertEquals(-1, choice.find(List.of(new TrackChoice("English", "en"))));
  }
  @Test public void unnamedUnknownTrackDoesNotRandomlySelectAnotherIndex() {
    assertEquals(-1, new TrackChoice(null, "und").find(List.of(new TrackChoice(null, "und"))));
    assertEquals(0, new TrackChoice("评论", null).find(List.of(new TrackChoice("评论", null))));
  }
}
