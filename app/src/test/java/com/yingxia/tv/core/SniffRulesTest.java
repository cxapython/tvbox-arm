package com.yingxia.tv.core;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class SniffRulesTest {
  @Test
  public void followWinsOverVideoAndAdsBlockExceptCloudflare() {
    assertEquals(SniffRules.Decision.FOLLOW, SniffRules.decision(
        "https://player.example/player/?url=https://cdn.example/a.m3u8", "https://page.example/",
        true, 0, List.of(), List.of()));
    assertEquals(SniffRules.Decision.BLOCK, SniffRules.decision(
        "https://ads.example/a.js", "https://page.example/", true, 0,
        List.of("ads.example"), List.of()));
    assertEquals(SniffRules.Decision.ALLOW, SniffRules.decision(
        "https://challenges.cloudflare.com/cdn-cgi/challenge-platform/a.js", "https://page.example/",
        true, 0, List.of("cloudflare.com"), List.of()));
  }

  @Test
  public void matchingRuleChecksExcludeBeforeRegexAndDefault() {
    SniffRules.Rule rule = new SniffRules.Rule(List.of("cdn.example"), List.of("token=[0-9]+"),
        List.of(), List.of("preview"), List.of());
    assertTrue(SniffRules.isVideo("https://cdn.example/watch?token=123", "https://page.example/",
        true, List.of(rule)));
    assertFalse(SniffRules.isVideo("https://cdn.example/preview?token=123", "https://page.example/",
        true, List.of(rule)));
  }

  @Test
  public void nestedHostSelectsRuleAndPageScriptsPrependClick() {
    SniffRules.Rule rule = new SniffRules.Rule(List.of("cdn.example"), List.of(), List.of("rule()"),
        List.of(), List.of("auto()"));
    String page = "https://player.example/?url=https%3A%2F%2Fcdn.example%2Fwatch";
    assertEquals(List.of("click()", "rule()"), SniffRules.pageScripts(page, "click()", List.of(rule)));
    assertEquals(List.of("auto()"), SniffRules.pageClicks(page, List.of(rule)));
  }

  @Test
  public void htmlAndWrappedURLsAreNotDefaultVideoFormats() {
    assertFalse(SniffRules.isVideo("https://cdn.example/a.html", "", true, List.of()));
    assertFalse(SniffRules.isVideo("https://player.example/?url=https://cdn.example/a.m3u8",
        "", true, List.of()));
    assertTrue(SniffRules.isVideo("https://cdn.example/long-enough.m3u8?x=1", "", true, List.of()));
  }
}
