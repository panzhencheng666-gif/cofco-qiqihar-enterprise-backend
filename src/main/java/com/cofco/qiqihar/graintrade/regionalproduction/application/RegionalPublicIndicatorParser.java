package com.cofco.qiqihar.graintrade.regionalproduction.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Extracts explicitly labelled public facts. Missing facts stay missing; crop parents are never summed. */
public final class RegionalPublicIndicatorParser {
    private RegionalPublicIndicatorParser() {}
    public record Metric(String category, String label, BigDecimal value, String unit,
            int year, String kind, String method) {}
    private record Crop(String name, String aliases, String category) {}
    private static final List<Crop> CROPS = List.of(
            new Crop("玉米", "玉米", "CROP_GRAIN"), new Crop("大豆", "大豆", "CROP_GRAIN"),
            new Crop("稻谷", "稻谷|水稻", "CROP_GRAIN"), new Crop("小麦", "小麦", "CROP_GRAIN"),
            new Crop("高粱", "高粱", "CROP_GRAIN"), new Crop("谷子", "谷子", "CROP_GRAIN"),
            new Crop("杂豆", "杂豆", "CROP_GRAIN"), new Crop("其他粮食", "其他粮食", "CROP_GRAIN"),
            new Crop("马铃薯", "马铃薯|土豆", "CROP_TUBER"), new Crop("甘薯", "甘薯|红薯", "CROP_TUBER"),
            new Crop("油料", "油料", "CROP_OIL"), new Crop("油菜籽", "油菜籽|油菜", "CROP_OIL"),
            new Crop("向日葵", "向日葵|葵花籽", "CROP_OIL"), new Crop("花生", "花生", "CROP_OIL"),
            new Crop("蔬菜及食用菌", "蔬菜及食用菌", "CROP_VEGETABLE"),
            new Crop("番茄", "番茄|西红柿", "CROP_VEGETABLE"), new Crop("辣椒", "辣椒", "CROP_VEGETABLE"),
            new Crop("茄子", "茄子", "CROP_VEGETABLE"), new Crop("黄瓜", "黄瓜", "CROP_VEGETABLE"),
            new Crop("白菜", "大白菜|白菜", "CROP_VEGETABLE"), new Crop("甘蓝", "甘蓝", "CROP_VEGETABLE"),
            new Crop("萝卜", "萝卜", "CROP_VEGETABLE"), new Crop("胡萝卜", "胡萝卜", "CROP_VEGETABLE"),
            new Crop("大葱", "大葱", "CROP_VEGETABLE"), new Crop("洋葱", "洋葱", "CROP_VEGETABLE"),
            new Crop("南瓜", "南瓜", "CROP_VEGETABLE"), new Crop("豆角", "豆角", "CROP_VEGETABLE"),
            new Crop("食用菌", "食用菌", "CROP_VEGETABLE"), new Crop("瓜果类", "瓜果类|瓜果", "CROP_FRUIT"),
            new Crop("西瓜", "西瓜", "CROP_FRUIT"), new Crop("甜瓜", "甜瓜|香瓜", "CROP_FRUIT"),
            new Crop("水果", "水果", "CROP_FRUIT"), new Crop("经济作物", "经济作物", "CROP_ECONOMIC"),
            new Crop("糖料", "糖料", "CROP_ECONOMIC"), new Crop("甜菜", "甜菜", "CROP_ECONOMIC"),
            new Crop("麻类", "麻类", "CROP_ECONOMIC"), new Crop("中草药材", "中草药材|中药材", "CROP_ECONOMIC"),
            new Crop("花卉", "花卉", "CROP_ECONOMIC"), new Crop("其他农作物", "其他农作物", "CROP_ECONOMIC"),
            new Crop("青贮玉米", "青贮玉米", "CROP_FORAGE"), new Crop("苜蓿", "苜蓿", "CROP_FORAGE"));
    private static final String NUM = "([0-9]+(?:\\.[0-9]+)?)";
    private static final String PREFIX = "(?:为|达|达到|实现|约为|约|超过|超|近|突破|稳定在)?";

    public static List<Metric> parse(String parserKey, String body) {
        if (!parserKey.startsWith("ANNUAL_") && !parserKey.equals("QQHR_PROCESSING")) return List.of();
        String text = clean(body);
        if (parserKey.equals("QQHR_PROCESSING")) return processing(text);
        String rootName = switch (parserKey) {
            case "ANNUAL_QQHR" -> "齐齐哈尔";
            case "ANNUAL_HEIHE" -> "黑河";
            case "ANNUAL_DXAL" -> "大兴安岭";
            case "ANNUAL_HLBE" -> "呼伦贝尔";
            default -> throw new IllegalArgumentException("未登记统计公报解析规则");
        };
        int year = reportYear(text, rootName);
        List<Metric> result = new ArrayList<>();
        for (String mode : List.of("铁路", "公路", "水路")) {
            extract(result, text, year, "LOGISTICS", mode + "货运量", "(?<![、和])" + mode + "货运量", "OUTPUT");
            extract(result, text, year, "LOGISTICS", mode + "货物周转量", "(?<![、和])" + mode + "货物周转量", "TURNOVER");
        }
        extract(result, text, year, "LOGISTICS", "铁路营业里程", "铁路(?:营业|运营)里程", "DISTANCE");
        extract(result, text, year, "LOGISTICS", "公路通车里程", "公路(?:通车|营业|总)里程", "DISTANCE");
        // Freight includes all goods; it must never be labelled grain outflow.
        for (String flow : List.of("调入", "调出")) {
            extract(result, text, year, "FLOW", "粮食" + flow + "量", "粮食" + flow + "量", "OUTPUT");
        }
        String agri = text;
        int begin = text.indexOf("二、农");
        if (begin >= 0) {
            int end = text.indexOf("三、", begin);
            agri = text.substring(begin, end > begin ? end : text.length());
        }
        extract(result, agri, year, "LAND", "粮食播种面积", "粮食(?:作物)?(?:总)?播种(?:总)?面积", "AREA");
        extract(result, agri, year, "PRODUCTION", "粮食总产量", "粮食(?:总)?产量", "OUTPUT");
        extract(result, agri, year, "ECONOMY", "农林牧渔业总产值", "农林牧渔业总产值", "MONEY");
        for (String[] entry : List.of(new String[]{"种植业产值", "种植业(?:产值)?|农业产值"},
                new String[]{"林业产值", "林业产值"}, new String[]{"畜牧业产值", "畜牧业产值"},
                new String[]{"渔业产值", "渔业产值"}, new String[]{"农林牧渔服务业产值", "农林牧渔(?:服务业|专业及辅助性活动)产值"}))
            extract(result, agri, year, "ECONOMY", entry[0], entry[1], "MONEY");
        for (Crop crop : CROPS) {
            String subject = "(?<![\\p{IsHan}])(?:" + crop.aliases + ")";
            // Direct crop labels also occur after Chinese prose (其中/全年); explicit suffixes are unambiguous.
            String explicit = "(?<![贮胡及])(?:" + crop.aliases + ")";
            extract(result, agri, year, crop.category, crop.name + "播种面积",
                    explicit + "(?:播种|种植)(?:总)?面积|" + subject, "AREA");
            extract(result, agri, year, crop.category, crop.name + "产量",
                    explicit + "(?:总)?产量|" + subject, "OUTPUT");
        }
        // In economic-crop clauses the output follows its area without repeating the crop name.
        for (Crop crop : CROPS) {
            if (crop.category.equals("CROP_GRAIN")) continue;
            var clause = Pattern.compile("(?<![贮胡及])(?:" + crop.aliases + ")(?:播种|种植)?(?:总)?(?:面积)?"
                    + NUM + "(?:万公顷|公顷|万亩|亩)([^；。]{0,70})(?=；|。|$)").matcher(agri);
            if (clause.find()) {
                String tail = clause.group(2);
                var output = Pattern.compile("产量" + NUM + "(万吨|吨)").matcher(tail);
                if (output.find()) {
                    String between = tail.substring(0, output.start());
                    boolean changedSubject = between.contains("其中") || CROPS.stream()
                            .anyMatch(other -> Pattern.compile(other.aliases).matcher(between).find());
                    if (!changedSubject) add(result,crop.category,crop.name + "产量",output.group(1),output.group(2),year,"OBSERVED",clause.group(),"OUTPUT");
                }
            }
        }
        // Parallel lists in the Daxing'anling report explicitly name their crop order.
        for (String unit : List.of("万吨", "万公顷")) {
            var m = Pattern.compile("小麦、玉米、大豆分别为" + NUM + unit + "、" + NUM + unit + "、" + NUM + unit).matcher(agri);
            if (m.find()) for (int i = 0; i < 3; i++) {
                String label = List.of("小麦", "玉米", "大豆").get(i) + (unit.equals("万公顷") ? "播种面积" : "产量");
                add(result, "CROP_GRAIN", label, m.group(i + 1), unit, year, "OBSERVED", m.group(), unit.equals("万公顷") ? "AREA" : "OUTPUT");
            }
        }
        // Converted-grain potatoes must never be interpreted as fresh weight.
        extract(result, agri, year, "CROP_TUBER", "马铃薯折粮产量", "马铃薯产量（折粮）", "OUTPUT");
        for (String name : List.of("猪肉", "牛肉", "羊肉", "禽肉", "禽蛋", "牛奶", "水产品", "猪牛羊禽肉", "肉类"))
            extract(result, agri, year, name.equals("水产品") ? "FISHERY" : "LIVESTOCK", name + "产量", (name.equals("牛奶") ? "(?:生牛奶|牛奶|生奶)" : "(?<![猪牛羊])" + name) + "(?:总)?产量", "OUTPUT");
        for (String name : List.of("生猪", "牛", "羊", "家禽", "活家禽")) for (String stage : List.of("存栏", "出栏"))
            extract(result, agri, year, "LIVESTOCK", name + stage, "(?<![\\p{IsHan}])" + name + stage + "|(?:全年|年末)" + name + stage, "COUNT");
        extract(result, text, year, "RURAL", "乡村人口", "乡村人口", "PEOPLE");
        extract(result, text, year, "RURAL", "农村居民人均可支配收入", "农村(?:牧区)?(?:常住)?居民人均可支配收入", "INCOME");
        extract(result, text, year, "ECONOMY", "第一产业增加值", "第一产业增加值", "MONEY");
        extract(result, agri, year, "TECHNOLOGY", "农牧业机械总动力", "农牧业机械总动力|农业机械总动力", "POWER");
        var unique = new LinkedHashMap<String, Metric>();
        result.forEach(m -> unique.putIfAbsent(m.label, m));
        for (Crop crop : CROPS) {
            var area = unique.get(crop.name + "播种面积");
            var output = unique.get(crop.name + "产量");
            if (area != null && output != null && area.value.signum() > 0
                    && area.kind.equals("OBSERVED") && output.kind.equals("OBSERVED")) {
                BigDecimal yield = output.value.multiply(new BigDecimal("1000")).divide(area.value, 2, RoundingMode.HALF_UP);
                unique.put(crop.name + "平均单产", new Metric(crop.category, crop.name + "平均单产", yield, "公斤/亩", year,
                        "ESTIMATED", "同一地区同一年度同一作物，总产除以面积得到亩均产出。"
                        + "计算：" + output.value + "万吨×1000÷" + area.value + "万亩=" + yield + "公斤/亩。"
                        + "面积依据：" + area.method + "；产量依据：" + output.method));
            }
        }
        if (unique.isEmpty()) throw new IllegalArgumentException("未识别可用农业指标，保留最近有效数据");
        return List.copyOf(unique.values());
    }

    private static void extract(List<Metric> result, String text, int year, String category,
            String label, String subject, String measure) {
        String units = switch (measure) {
            case "AREA" -> "万公顷|公顷|万亩|亩";
            case "OUTPUT" -> "亿吨|亿斤|万公斤|万吨|吨|公斤";
            case "MONEY" -> "亿元|万元";
            case "TURNOVER" -> "亿吨公里|万吨公里|吨公里";
            case "DISTANCE" -> "万公里|公里";
            case "COUNT" -> "万头|万只|头|只";
            case "PEOPLE" -> "万人|人";
            case "POWER" -> "万千瓦|千瓦";
            case "INCOME" -> "元";
            default -> throw new IllegalArgumentException(measure);
        };
        var m = Pattern.compile("(?:" + subject + ")" + PREFIX + NUM + "(" + units + ")").matcher(text);
        if (m.find()) {
            String quote = m.group();
            String kind = quote.matches(".*(?:约|超过|超|近|突破|稳定在).*?") ? "CONTEXT" : "OBSERVED";
            add(result, category, label, m.group(1), m.group(2), year, kind, quote, measure);
        }
    }

    private static void add(List<Metric> result, String category, String label, String number,
            String sourceUnit, int year, String kind, String quote, String measure) {
        String unit = switch (measure) { case "AREA" -> "万亩"; case "OUTPUT" -> "万吨";
            case "MONEY" -> "亿元"; default -> sourceUnit; };
        BigDecimal factor = switch (sourceUnit) {
            case "万公顷" -> new BigDecimal("15"); case "公顷" -> new BigDecimal("0.0015");
            case "亩" -> new BigDecimal("0.0001"); case "亿斤" -> new BigDecimal("5");
            case "亿吨" -> new BigDecimal("10000");
            case "吨" -> new BigDecimal("0.0001"); case "公斤" -> new BigDecimal("0.0000001");
            case "万公斤" -> new BigDecimal("0.001"); case "万元" -> new BigDecimal("0.0001");
            default -> BigDecimal.ONE;
        };
        BigDecimal value = new BigDecimal(number).multiply(factor).stripTrailingZeros();
        String method = "原文依据：" + quote + "。" + (factor.compareTo(BigDecimal.ONE) == 0
                ? "沿用原文单位，无需换算。" : "统一单位：" + number + sourceUnit + "×" + factor.toPlainString() + "=" + value.toPlainString() + unit + "。")
                + (kind.equals("CONTEXT") ? "原文为约数或边界值，保留原有限定，不能当作精确统计。" : "保留公报统计口径；分项与总计不重复累加。");
        result.add(new Metric(category, label, value, unit, year, kind, method));
    }

    private static List<Metric> processing(String text) {
        if (!text.contains("齐齐哈尔") || !text.contains("农产品加工")) throw new IllegalArgumentException("加工报道地区或主题不匹配");
        var date = Pattern.compile("日期[:：](20[0-9]{2})-").matcher(text);
        if (!date.find()) throw new IllegalArgumentException("加工报道日期未识别");
        int year = Integer.parseInt(date.group(1));
        List<Metric> result = new ArrayList<>();
        String[][] entries = {{"农产品加工规上企业", "农产品加工规上企业", "户", "PROCESSING"},
                {"粮食加工企业", "粮食加工企业", "家", "PROCESSING"},
                {"粮食设计加工能力", "设计加工能力", "万吨", "PROCESSING"},
                {"奶牛存栏", "奶牛存栏量", "万头", "LIVESTOCK"},
                {"规模以上乳制品企业", "规模以上乳制品企业", "家", "PROCESSING"},
                {"年原奶加工能力", "年原奶加工能力", "万吨", "PROCESSING"},
                {"预制和休闲食品产业规模", "预制和休闲食品产业规模", "亿元", "PROCESSING"},
                {"冷藏容量", "冷藏容量", "万吨", "LOGISTICS"},
                {"农产品配送专线", "农产品配送专线", "条", "LOGISTICS"}};
        for (String[] e : entries) {
            var m = Pattern.compile(e[1] + PREFIX + NUM + e[2]).matcher(text);
            if (m.find()) result.add(new Metric(e[3], e[0], new BigDecimal(m.group(1)), e[2], year, "CONTEXT",
                    "原文依据：" + m.group() + "。采用报道发布日期时点的产业规模，不代表" + year + "年全年实际完成值；保留原文口径。"));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("加工报道无可提取指标");
        return List.copyOf(result);
    }

    static int reportYear(String text, String rootName) {
        String region = Pattern.quote(rootName) + "(?:市|地区)?";
        var report = Pattern.compile("(?:(20[0-9]{2})年" + region + "|" + region
                + "(20[0-9]{2})年)国民经济和社会发展统计公报").matcher(text);
        if (!report.find()) throw new IllegalArgumentException("未识别登记地区及年度的统计公报原文，保留最近有效数据");
        return Integer.parseInt(report.group(1) == null ? report.group(2) : report.group(1));
    }

    static String clean(String body) {
        return body.replaceAll("(?is)<script[^>]*>.*?</script>|<style[^>]*>.*?</style>|<!--.*?-->", "")
                .replaceAll("<[^>]+>", "").replaceAll("&(?:nbsp|ensp|emsp);|&#(?:160|12288);", "")
                .replaceAll("[\\s\\p{Z}]+", "");
    }
}
