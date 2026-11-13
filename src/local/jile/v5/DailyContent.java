package local.jile.v5;
import java.time.*;import java.time.format.DateTimeFormatter;import java.util.Locale;
/** Curated public-domain texts; no network requests or generated attributions. */
public final class DailyContent {
 private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
 private static final String[][] QUOTES={
 {"不积跬步，无以至千里。","荀子《劝学》"},
 {"学而不思则罔，思而不学则殆。","《论语·为政》"},
 {"读书破万卷，下笔如有神。","杜甫《奉赠韦左丞丈二十二韵》"},
 {"纸上得来终觉浅，绝知此事要躬行。","陆游《冬夜读书示子聿》"},
 {"欲穷千里目，更上一层楼。","王之涣《登鹳雀楼》"},
 {"长风破浪会有时，直挂云帆济沧海。","李白《行路难·其一》"},
 {"问渠那得清如许？为有源头活水来。","朱熹《观书有感·其一》"},
 {"千里之行，始于足下。","《老子》第六十四章"},
 {"知之为知之，不知为不知，是知也。","《论语·为政》"},
 {"山重水复疑无路，柳暗花明又一村。","陆游《游山西村》"},
 {"会当凌绝顶，一览众山小。","杜甫《望岳》"},
 {"海内存知己，天涯若比邻。","王勃《送杜少府之任蜀州》"},
 {"落霞与孤鹜齐飞，秋水共长天一色。","王勃《滕王阁序》"},
 {"天生我材必有用，千金散尽还复来。","李白《将进酒》"},
 {"随风潜入夜，润物细无声。","杜甫《春夜喜雨》"},
 {"行到水穷处，坐看云起时。","王维《终南别业》"},
 {"不识庐山真面目，只缘身在此山中。","苏轼《题西林壁》"},
 {"等闲识得东风面，万紫千红总是春。","朱熹《春日》"},
 {"三人行，必有我师焉。","《论语·述而》"},
 {"博观而约取，厚积而薄发。","苏轼《稼说送张琥》"},
 {"路漫漫其修远兮，吾将上下而求索。","屈原《离骚》"},
 {"穷则变，变则通，通则久。","《周易·系辞下》"},
 {"志不强者智不达。","《墨子·修身》"},
 {"苟日新，日日新，又日新。","《礼记·大学》"}
 };
 private static ZonedDateTime local(long t){return Instant.ofEpochMilli(t).atZone(ZONE);}
 private static int index(long t){return Math.floorMod(local(t).toLocalDate().toEpochDay()-LocalDate.of(2026,10,29).toEpochDay(),QUOTES.length);}
 public static String clock(long t){return local(t).format(DateTimeFormatter.ofPattern("HH:mm",Locale.CHINA));}
 public static String date(long t){return local(t).format(DateTimeFormatter.ofPattern("M月d日  EEEE",Locale.CHINA));}
 static int nextQuote(int offset){return (offset + 1) % QUOTES.length;}
 static String quote(long t, int offset){return QUOTES[(index(t) + offset) % QUOTES.length][0];}
 static String source(long t, int offset){return QUOTES[(index(t) + offset) % QUOTES.length][1];}
}
