package com.training;

import java.util.*;
import java.text.Normalizer;

/** Pre-bid logistics hints. A city match is not a verified itinerary or availability. */
final class DispatchPreference {
    static final List<String> PROVINCES = Collections.unmodifiableList(Arrays.asList(
            "北京", "天津", "河北", "山西", "内蒙古", "辽宁", "吉林", "黑龙江", "上海", "江苏", "浙江", "安徽",
            "福建", "江西", "山东", "河南", "湖北", "湖南", "广东", "广西", "海南", "重庆", "四川", "贵州",
            "云南", "西藏", "陕西", "甘肃", "青海", "宁夏", "新疆", "香港", "澳门", "台湾", "境外"));
    private static final Set<String> MUNICIPALITIES = new HashSet<>(Arrays.asList("北京", "天津", "上海", "重庆", "香港", "澳门"));
    final String province, city, mode, period;
    final boolean preferLocal;

    private DispatchPreference(String province, String city, String mode, String period, boolean preferLocal) {
        this.province = province; this.city = city; this.mode = mode; this.period = period; this.preferLocal = preferLocal;
    }

    static String text(Object value) { return value == null ? "" : Normalizer.normalize(String.valueOf(value), Normalizer.Form.NFKC).strip(); }

    static String province(String raw) {
        String value = text(raw).replaceAll("\\s+", "");
        return value.replaceFirst("(?:壮族自治区|回族自治区|维吾尔自治区|特别行政区|自治区|省|市)$", "");
    }

    static String city(String raw) { return text(raw).replaceAll("\\s+", "").replaceFirst("市$", ""); }

    static void validateRegion(Map<String, Object> body, String provinceKey, String cityKey, boolean required) {
        for (String key : Arrays.asList(provinceKey, cityKey))
            if (body.get(key) != null && !(body.get(key) instanceof String)) throw new IllegalArgumentException("省份和城市必须填写文字");
        String rawProvince = text(body.get(provinceKey)), rawCity = text(body.get(cityKey));
        if (!required && rawProvince.isEmpty() && rawCity.isEmpty()) { body.put(provinceKey, ""); body.put(cityKey, ""); return; }
        String label = provinceKey.startsWith("base") ? "常驻地区" : "授课地区";
        if (rawProvince.length() > 64 || rawCity.length() > 64) throw new IllegalArgumentException(label + "不能超过64字");
        String p = province(rawProvince), c = city(rawCity);
        if (!PROVINCES.contains(p) || c.length() < 2 || rawCity.matches("(?s).*[<>\\p{Cntrl}\\p{Cf}、,;；].*") || !c.matches("(?s).*\\p{L}.*") ||
                c.toLowerCase(Locale.ROOT).matches("(?:待定|未知|暂无|待补充|不详|未填写|全国|无|待确认|同上|不确定|unknown|none|n/a)"))
            throw new IllegalArgumentException("请填写" + label + "的省份和城市，不确定时请先向讲师或客户核实");
        if (MUNICIPALITIES.contains(p) && !p.equals(c))
            throw new IllegalArgumentException(label + "选择" + p + "时，城市请填写" + p + "，不填写区县");
        if (!RegionDirectory.known(p, c) && (PROVINCES.contains(c) || c.endsWith("省")))
            throw new IllegalArgumentException(label + "需要填写到城市或地级地区，不是省份");
        if (RegionDirectory.knownElsewhere(p, c)) throw new IllegalArgumentException(label + "的省份与城市不一致，请核对省市归属");
        body.put(provinceKey, p); body.put(cityKey, c);
    }

    static void validateChoice(Map<String, Object> body, String field, String... choices) {
        String value = text(body.get(field));
        if (!Arrays.asList(choices).contains(value)) throw new IllegalArgumentException("授课方式或授课时段不正确");
        body.put(field, value);
    }

    static DispatchPreference from(Map<String, Object> body, Map<String, Object> demand) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String field : Arrays.asList("training_province", "training_city", "training_mode", "training_period"))
            values.put(field, body.containsKey(field) ? body.get(field) : demand == null ? "" : demand.get(field));
        validateRegion(values, "training_province", "training_city", false);
        validateChoice(values, "training_mode", "", "线下", "线上", "待定");
        validateChoice(values, "training_period", "", "上午", "下午", "全天", "待定");
        if (body.containsKey("prefer_local") && !(body.get("prefer_local") instanceof Boolean))
            throw new IllegalArgumentException("同城优先应为布尔值");
        return new DispatchPreference(text(values.get("training_province")), text(values.get("training_city")),
                text(values.get("training_mode")), text(values.get("training_period")), !Boolean.FALSE.equals(body.get("prefer_local")));
    }

    boolean localPreferenceRequested() { return preferLocal && "线下".equals(mode); }
    boolean localPreferenceActive() { return localPreferenceRequested() && RegionDirectory.known(province, city); }

    Map<String, Object> describe(Map<String, Object> teacher, String date) {
        String baseProvince = text(teacher.get("base_province")), baseCity = text(teacher.get("base_city"));
        boolean known = !baseProvince.isEmpty() && !baseCity.isEmpty();
        boolean destinationKnown = !province.isEmpty() && !city.isEmpty();
        boolean baseRecognized = RegionDirectory.known(province(baseProvince), city(baseCity));
        boolean resolved = baseRecognized && RegionDirectory.known(province, city);
        boolean sameCity = resolved && province.equals(province(baseProvince)) && city.equals(city(baseCity));
        boolean remoteMorning = "线下".equals(mode) && resolved && !sameCity && Arrays.asList("上午", "全天").contains(period);
        List<String> notes = new ArrayList<>();
        if (!known) notes.add("常驻地区待补充，不能视为本地讲师");
        else if (!baseRecognized) notes.add("常驻地区名称不在当前城市候选字典中，需核对名称或更新字典，暂不参与同城优先");
        if ("线上".equals(mode)) notes.add("线上授课不参与地区排序；仍需联系确认授课时间");
        else {
            if (!destinationKnown) notes.add("授课地区未确定，暂不启用同城优先");
            else if (!RegionDirectory.known(province, city)) notes.add("授课地区名称待核对，暂不参与同城优先；可填写新地名，但不会自动猜测归属");
            if (mode.isEmpty() || "待定".equals(mode)) notes.add("授课方式待确认，暂不启用同城优先");
            if (sameCity) notes.add("常驻地与授课城市一致；市内通勤、讲师档期仍需确认，不等于差旅费为零");
            else if (resolved) notes.add("异地交通待核实：比较往返耗时、票价、市内接驳及总差旅，不以同省代替就近判断");
            if (remoteMorning) notes.add("上午或全天异地授课：按提前一天到达预留，核对前一日档期、住宿和餐补");
            if ("线下".equals(mode) && "下午".equals(period) && resolved && !sameCity) notes.add("下午课可优先核实当天往返方案，不能仅凭城市判断可达");
            if (period.isEmpty() || "待定".equals(period)) notes.add("授课时段待确认，暂不能判断是否需要提前一天到达");
        }
        notes.add(date == null || date.isEmpty() ? "授课日期未确定，档期待确认" : "已检查授课日系统记录；无记录不代表讲师已确认有空");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("base_province", baseProvince); result.put("base_city", baseCity);
        result.put("residence_complete", known); result.put("same_city", sameCity);
        result.put("residence_recognized", baseRecognized);
        result.put("local_priority", localPreferenceActive() && sameCity);
        result.put("arrival_day_before", remoteMorning);
        result.put("label", "线上".equals(mode) ? "线上 · 不限地区" : !known ? "常驻地待补充" : !destinationKnown ? "授课地待定" : !resolved ? "地区名称待核对" : !"线下".equals(mode) ? "授课方式待确认" : sameCity ? "同城" : "异地 · 交通待核实");
        result.put("notes", notes); result.put("transport_verified", false);
        if (!"线上".equals(mode)) result.put("rail", RailTravel.unavailable(baseCity, city,
                remoteMorning && date != null && !date.isEmpty() ? java.time.LocalDate.parse(date).minusDays(1).toString() : date));
        return result;
    }

    Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("training_province", province); result.put("training_city", city);
        result.put("training_mode", mode); result.put("training_period", period); result.put("prefer_local", preferLocal);
        result.put("local_preference_active", localPreferenceActive());
        result.put("stage", "投标前师资推荐");
        result.put("ranking_policy", localPreferenceActive() ? "检查全部城市，同城及所有铁路小于4小时候选统一比较；铁路优先、航空后备，各池比模型分、同分等级，第三名同分全留" : localPreferenceRequested() ? "授课地区待核实，暂不自动纳入就近推荐；不会按不限地区处理" : "按模型匹配分排序，同分再比等级；未启用地区偏好");
        return result;
    }
}
