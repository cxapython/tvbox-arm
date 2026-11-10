package com.yingxia.tv.core;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

/** Real netdisk/magnet naming patterns from the reference EpisodeLabelsTests. */
public class EpisodeLabelsTest {
  private static List<String> labels(String... names) {
    return Catalog.lineLabels(Arrays.asList(names));
  }

  @Test
  public void seasonAndChineseEpisodeMarkersBecomeShortLabels() {
    assertEquals(
        List.of("第1集", "第2集", "第3集"),
        labels(
            "[1.1GB] 海市蜃楼.The.Mirage.2025.S01E01.2160p.WEB-DL.H265.DDP5.1.mkv",
            "[1.2GB] 海市蜃楼.The.Mirage.2025.S01E02.2160p.WEB-DL.H265.DDP5.1.mkv",
            "[1.3GB] 海市蜃楼.The.Mirage.2025.S01E03.2160p.WEB-DL.H265.DDP5.1.mkv"));
    assertEquals(
        List.of("第12集", "第13集"), labels("某剧 第十二集 1080p.mp4", "某剧 第十三集 1080p.mp4"));
    assertEquals(
        List.of("S1 第1集", "S1 第2集", "S2 第1集"),
        labels("Show.S01E01.1080p.mkv", "Show.S01E02.1080p.mkv", "Show.S02E01.1080p.mkv"));
  }

  @Test
  public void changingNumberAndMixedNamingPatternsAreRecognized() {
    assertEquals(
        List.of("第1集", "第2集", "第9集"),
        labels(
            "[796.61MB] W‑我DTT鸵N先S 01.mp4",
            "[859.35MB] W‑我DTT鸵N先S 02.mp4",
            "[770.75MB] W‑我DTT鸵N先S 09 W.mp4"));
    assertEquals(
        List.of("第1集", "第2集", "第5集"),
        labels("[572.10MB]01.mp4", "[539.60MB]02.mp4", "[549.21MB]中字EP5.mp4"));
  }

  @Test
  public void duplicateEpisodesUseQualityOrStableOrder() {
    assertEquals(
        List.of("第24集", "第25集 (1)", "第25集 (2)"),
        labels(
            "S01特别版 QYN-S01TBB-E24.4K.mkv",
            "S01特别版 QYN-S01TBB-E25.4K(1).mkv",
            "S01特别版 QYN-S01TBB-E25.4K.mkv"));
    assertEquals(
        List.of(
            "第1集",
            "第2集",
            "H-海市蜃.楼 毛骗团队首部古装剧_定档预告",
            "第1集 · 4K",
            "第2集 · 4K"),
        labels(
            "[394.95MB] H-海市蜃.楼 01.mp4",
            "[483.90MB] H-海市蜃.楼 02.mp4",
            "[186.27MB] H-海市蜃.楼 毛骗团队首部古装剧_定档预告.mp4",
            "[2.00GB] 4K 01.2160p.HDR.60fps.WEB-DL.H265.AAC-XiaoXueshen.mkv",
            "[2.39GB] 4K 02.2160p.HDR.60fps.WEB-DL.H265.AAC-XiaoXueshen.mkv"));
  }

  @Test
  public void rangesAndShareTagsAreCompressedWithoutGuessingDates() {
    assertEquals(
        List.of("第1–4集 · 2160p", "第5–8集 · 2160p", "第1–8集 · 1080p"),
        labels(
            "01-04.2160p.HD国语中字无水印.mp4",
            "05-08.2160p.HD国语中字无水印.mp4",
            "01-08.1080p.HD国语中字无水印.mp4"));
    assertEquals(
        List.of("第1集", "花絮", "第2集"),
        labels("E01.mp4【太阳鱼】", "花絮.mp4【太阳鱼】", "E02.mp4【太阳鱼】"));
    assertNull(labels("Variety.20251220.1080p.mp4", "Variety.20251227.1080p.mp4"));
    assertNull(labels("第01集", "第02集"));
  }

  @Test
  public void compressionIsScopedToEachLineAndEpisodeId() {
    List<JSONObject> episodes =
        Arrays.asList(
            Json.obj("id", "a1", "name", "Show.4K.01.mkv", "flag", "A"),
            Json.obj("id", "b1", "name", "第1集", "flag", "B"),
            Json.obj("id", "a2", "name", "Show.4K.02.mkv", "flag", "A"));
    Map<String, String> labels = Catalog.episodeLabels(episodes);
    assertEquals(2, labels.size());
    assertEquals("第1集", labels.get("a1"));
    assertEquals("第2集", labels.get("a2"));
    assertFalse(labels.containsKey("b1"));
  }
}
