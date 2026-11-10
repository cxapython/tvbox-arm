package com.yingxia.tv.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a search result's title is what was searched for (a port of TVCore's TitleMatch). Plenty
 * of sources answer a search with loosely related titles; the ones that match come first, and
 * nothing is hidden.
 */
public final class TitleMatch {
  private TitleMatch() {}

  /**
   * Common traditional → simplified characters. The original relies on ICU's Hant-Hans transform;
   * this table covers the characters usual in media titles. Ambiguous one-to-many mappings (乾,
   * 徵, …) are deliberately left out.
   */
  private static final String SIMPLIFIED =
      "機机動动隊队場场劇剧後后無无慶庆醫医劉刘學学長长風风雲云龍龙愛爱國国華华語语電电視视門门車车馬马"
          + "個个來來時时會会對对說说書书見见關关與与為为麼么裡里餘余於于兒儿們们這这聽听覺觉樂乐戲戏"
          + "劍剑靈灵鐵铁戰战艦舰殺杀數数據据點点網网絕绝級级線线終终結结紅红約约紀纪續续緣缘罰罚職职"
          + "證证護护衛卫諜谍謀谋賊贼傑杰雙双鷹鹰鴉鸦鳳凤鶴鹤齊齐齡龄龜龟遊游韓韩蘇苏葉叶陳陈張张楊杨"
          + "黃黄吳吴趙赵週周鄭郑馮冯蕭萧羅罗鄧邓許许謝谢韋韦盧卢賈贾顧顾陸陆龔龚嚴严蔣蒋鍾钟譚谭閆闫"
          + "喬乔莊庄龐庞萬万錢钱孫孙邊边寶宝貝贝開开還还進进遠远運运過过達达違违選选遺遗遲迟邁迈適适"
          + "飛飞飯饭飲饮餓饿館馆騎骑驚惊驕骄體体髮发鬚须鬥斗鬧闹魚鱼鮮鲜鯊鲨鳥鸟雞鸡鳴鸣鴨鸭鵝鹅鹽盐"
          + "麗丽麥麦麵面黨党師师帥帅帳帐幫帮幹干隻只臺台灣湾災灾滅灭漢汉潛潜澤泽濤涛淚泪淪沦淨净淺浅"
          + "溫温滄沧滬沪濟济瀟潇煙烟煉炼熱热爭争爺爷牽牵獵猎獸兽獨独獲获現现環环畢毕異异當当發发盜盗"
          + "盡尽監监盤盘碼码禍祸禮礼禪禅稱称窮穷筆笔節节簡简經经維维總总繼继義义聞闻聯联聲声脅胁腦脑"
          + "脫脱臉脸臨临處处虛虚蟲虫術术裝装親亲觸触討讨記记詭诡該该詳详誅诛話话誠诚誤误請请諸诸諾诺"
          + "論论謊谎謎谜譯译議议變变貞贞負负財财貨货貪贪貴贵費费資资賭赌贏赢跡迹軍军軌轨軒轩輪轮轟轰"
          + "轉转辦办連连遙遥鄉乡鈴铃銀银鋒锋錯错錦锦鎮镇鏡镜鐘钟鑽钻閃闪閉闭間间闇暗陣阵陰阴陽阳險险"
          + "隨随隱隐難难靜静韻韵響响頂顶順顺領领頭头題题額额顏颜飄飘駕驾騙骗";

  private static final Map<Character, Character> SIMPLIFIED_MAP = new HashMap<>();

  static {
    for (int i = 0; i + 1 < SIMPLIFIED.length(); i += 2)
      SIMPLIFIED_MAP.put(SIMPLIFIED.charAt(i), SIMPLIFIED.charAt(i + 1));
  }

  /**
   * Lower case, half-width, simplified, with spaces and punctuation gone: "Mission: Impossible" and
   * "MISSION IMPOSSIBLE" and "機動搜查隊" and "机动搜查队" compare equal.
   */
  public static String normalize(String text) {
    StringBuilder value = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      // Full-width ASCII and the ideographic space become their half-width forms.
      if (c == '　') c = ' ';
      else if (c >= '！' && c <= '～') c = (char) (c - 0xFEE0);
      Character simplified = SIMPLIFIED_MAP.get(c);
      value.append(simplified != null ? simplified.charValue() : c);
    }
    String lowered = value.toString().toLowerCase(Locale.ROOT);
    StringBuilder result = new StringBuilder(lowered.length());
    for (int i = 0; i < lowered.length(); i++) {
      char c = lowered.charAt(i);
      if (Character.isLetterOrDigit(c)) result.append(c);
    }
    return result.toString();
  }

  /** A season at the end of what was typed ("第二季", "s2", "season 2") doesn't have to be in the title. */
  private static final Pattern SEASON =
      Pattern.compile("(第[一二三四五六七八九十百零0-9]+[季部期]|s[0-9]+|season[0-9]+)$");

  /**
   * The title holds the keyword, or, for a keyword of three characters or more, all of its
   * characters in order (so "机动搜查队" finds "机动搜查队404" and "机动搜查队 劇場版"). An empty
   * keyword matches everything.
   */
  public static boolean matches(String title, String keyword) {
    String wanted = normalize(keyword);
    if (wanted.isEmpty()) return true;
    String name = normalize(title);
    if (name.contains(wanted)) return true;
    Matcher found = SEASON.matcher(wanted);
    if (found.find() && found.start() > 0) {
      wanted = wanted.substring(0, found.start());
      if (name.contains(wanted)) return true;
    }
    if (wanted.length() < 3) return false;
    int next = 0;
    for (int i = 0; i < name.length() && next < wanted.length(); i++)
      if (name.charAt(i) == wanted.charAt(next)) next++;
    return next == wanted.length();
  }

  /** The items whose title matches first, then the rest, each in the order they came. */
  public static <T> List<T> ordered(List<T> items, String keyword, Function<T, String> title) {
    if (normalize(keyword).isEmpty()) return items;
    List<T> matching = new ArrayList<>(), other = new ArrayList<>();
    for (T item : items)
      (matches(title.apply(item), keyword) ? matching : other).add(item);
    matching.addAll(other);
    return matching;
  }
}
