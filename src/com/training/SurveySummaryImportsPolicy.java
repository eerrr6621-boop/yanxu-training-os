package com.training;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Explicit host-supplied policy and identity-free aggregate validation. No default formal policy. */
public final class SurveySummaryImportsPolicy {
    private SurveySummaryImportsPolicy() {}
    private static final int MAX_RECORDS = 10000;
    private static final Set<String> POLICY_KEYS = Set.of("version", "zeroValid", "evidenceRef", "confirmed", "reviewerRoles",
            "blank", "invalid", "denominator", "rows", "displayScale", "rounding", "overallQuestionKey", "independentReviewer", "replacement");
    private static final Set<String> AGGREGATE_KEYS = Set.of("responseRowCount", "overallQuestionKey", "questions");
    private static final Set<String> QUESTION_KEYS = Set.of("key", "label", "column", "validCount", "blankCount", "invalidCount", "sumText", "averageText");

    public record Policy(String version, boolean zeroValid, String evidenceRef, boolean confirmed, Set<String> reviewerRoles) {
        public Policy {
            requireCode(version); requireCode(evidenceRef);
            if (reviewerRoles == null || reviewerRoles.isEmpty() || reviewerRoles.size() > 32) throw invalidPolicy();
            TreeSet<String> copy = new TreeSet<>();
            for (String role : reviewerRoles) { requireCode(role); copy.add(role); }
            reviewerRoles = Collections.unmodifiableSet(copy);
        }

        /** Parsing always remains a preview; confirmation is a separate host state transition. */
        public SurveySummaryImportsResponses.DraftRules rules() {
            return new SurveySummaryImportsResponses.DraftRules(BigDecimal.ZERO, BigDecimal.TEN, 2, !zeroValid);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("version", version); out.put("zeroValid", zeroValid); out.put("evidenceRef", evidenceRef);
            out.put("confirmed", confirmed); out.put("reviewerRoles", List.copyOf(reviewerRoles));
            out.put("blank", "EXCLUDE"); out.put("invalid", "EXCLUDE");
            out.put("denominator", "PER_QUESTION_VALID"); out.put("rows", "KEEP_ALL");
            out.put("displayScale", 2); out.put("rounding", "HALF_UP"); out.put("overallQuestionKey", "q10");
            out.put("independentReviewer", true); out.put("replacement", "REVIEW_BEFORE_REPLACE");
            return Collections.unmodifiableMap(out);
        }

        public static Policy fromMap(Object value) { return SurveySummaryImportsPolicy.fromMap(value); }
    }

    public static Policy fromMap(Object value) {
        Map<?, ?> map = object(value, POLICY_KEYS, true);
        if (!(map.get("zeroValid") instanceof Boolean zero) || !(map.get("confirmed") instanceof Boolean confirmed)
                || !(map.get("reviewerRoles") instanceof List<?> roles) || roles.isEmpty() || roles.size() > 32
                || !"EXCLUDE".equals(map.get("blank")) || !"EXCLUDE".equals(map.get("invalid"))
                || !"PER_QUESTION_VALID".equals(map.get("denominator")) || !"KEEP_ALL".equals(map.get("rows"))
                || !isTwo(map.get("displayScale")) || !"HALF_UP".equals(map.get("rounding"))
                || !"q10".equals(map.get("overallQuestionKey")) || !Boolean.TRUE.equals(map.get("independentReviewer"))
                || !"REVIEW_BEFORE_REPLACE".equals(map.get("replacement"))) throw invalidPolicy();
        Set<String> roleSet = new TreeSet<>();
        for (Object role : roles) {
            if (!(role instanceof String text)) throw invalidPolicy();
            requireCode(text);
            if (!roleSet.add(text)) throw invalidPolicy();
        }
        if (!(map.get("version") instanceof String version) || !(map.get("evidenceRef") instanceof String evidence)) throw invalidPolicy();
        return new Policy(version, zero, evidence, confirmed, roleSet);
    }

    /** Digest is independent of JSON field order and reviewer role insertion order. */
    public static String digestPolicy(Policy policy) {
        if (policy == null) throw invalidPolicy();
        try {
            byte[] bytes = Json.write(new TreeMap<>(policy.toMap())).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable", unavailable); }
    }

    /** Only a successful parser result is projected. Raw rows, issues, identities and fingerprints are discarded. */
    public static Map<String, Object> aggregate(Map<String, Object> coreResult, Policy policy) {
        if (policy == null) throw invalidPolicy();
        if (coreResult == null || !"PREVIEW".equals(coreResult.get("state"))
                || !"RESPONSE_SUMMARY_PREVIEW".equals(coreResult.get("mode"))) throw invalidAggregate();
        if (!(coreResult.get("rulesDraft") instanceof Map<?, ?> rules)
                || !decimalEquals(rules.get("minScore"), BigDecimal.ZERO)
                || !decimalEquals(rules.get("maxScore"), BigDecimal.TEN)
                || (rules.containsKey("excludeZero") && !(rules.get("excludeZero") instanceof Boolean))
                || Boolean.TRUE.equals(rules.get("excludeZero")) != !policy.zeroValid()) throw invalidAggregate();
        if (!(coreResult.get("questions") instanceof List<?> source) || source.size() != 10) throw invalidAggregate();
        List<Map<String, Object>> questions = new ArrayList<>();
        for (Object value : source) {
            if (!(value instanceof Map<?, ?> question)) throw invalidAggregate();
            Map<String, Object> copied = new LinkedHashMap<>();
            for (String key : QUESTION_KEYS) copied.put(key, question.get(key));
            questions.add(copied);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("responseRowCount", coreResult.get("responseRowCount"));
        out.put("overallQuestionKey", coreResult.get("overallQuestionKey")); out.put("questions", questions);
        return validateAggregate(out, policy);
    }

    /** Restores only the exact aggregate schema; performs no averaging across questions or batches. */
    public static Map<String, Object> validateAggregate(Object value, Policy policy) {
        if (policy == null) throw invalidPolicy();
        Map<?, ?> aggregate = object(value, AGGREGATE_KEYS, false);
        int rowCount = count(aggregate.get("responseRowCount"));
        if (rowCount == 0 || !"q10".equals(aggregate.get("overallQuestionKey")) || !(aggregate.get("questions") instanceof List<?> questions)
                || questions.size() != 10) throw invalidAggregate();
        Map<String, Map<String, Object>> byKey = new HashMap<>();
        Set<String> columns = new HashSet<>();
        for (Object item : questions) {
            Map<?, ?> question = object(item, QUESTION_KEYS, false);
            if (!(question.get("key") instanceof String key) || !key.matches("q(?:[1-9]|10)")) throw invalidAggregate();
            int index = Integer.parseInt(key.substring(1)) - 1;
            if (!SurveySummaryImportsResponses.QUESTION_LABELS.get(index).equals(question.get("label"))
                    || !(question.get("column") instanceof String column) || !column.matches("(?:[A-Z]|A[A-Z]|B[A-L])")
                    || !columns.add(column)) throw invalidAggregate();
            int valid = count(question.get("validCount")), blank = count(question.get("blankCount")), invalid = count(question.get("invalidCount"));
            if (valid + blank + invalid != rowCount) throw invalidAggregate();
            BigDecimal sum = scoreSum(question.get("sumText"));
            if (sum.signum() < 0 || sum.compareTo(BigDecimal.TEN.multiply(BigDecimal.valueOf(valid))) > 0
                    || (valid == 0 && sum.signum() != 0)
                    || (!policy.zeroValid() && sum.compareTo(BigDecimal.valueOf(valid).scaleByPowerOfTen(-SurveySummaryImportsResponses.MAX_SCORE_SCALE)) < 0)) throw invalidAggregate();
            String average = null;
            if (valid == 0) {
                if (question.get("averageText") != null) throw invalidAggregate();
            } else {
                if (!(question.get("averageText") instanceof String text) || !text.matches("(?:0|[1-9][0-9]*)\\.[0-9]{2}")) throw invalidAggregate();
                average = sum.divide(BigDecimal.valueOf(valid), 2, RoundingMode.HALF_UP).toPlainString();
                if (!average.equals(text)) throw invalidAggregate();
            }
            Map<String, Object> checked = new LinkedHashMap<>();
            checked.put("key", key); checked.put("label", SurveySummaryImportsResponses.QUESTION_LABELS.get(index)); checked.put("column", column);
            checked.put("validCount", valid); checked.put("blankCount", blank); checked.put("invalidCount", invalid);
            checked.put("sumText", question.get("sumText")); checked.put("averageText", average);
            if (byKey.put(key, Collections.unmodifiableMap(checked)) != null) throw invalidAggregate();
        }
        List<Map<String, Object>> ordered = new ArrayList<>();
        for (int index = 1; index <= 10; index++) {
            Map<String, Object> question = byKey.get("q" + index);
            if (question == null) throw invalidAggregate();
            ordered.add(question);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("responseRowCount", rowCount); out.put("overallQuestionKey", "q10"); out.put("questions", List.copyOf(ordered));
        return Collections.unmodifiableMap(out);
    }

    private static Map<?, ?> object(Object value, Set<String> keys, boolean policy) {
        if (!(value instanceof Map<?, ?> map) || !map.keySet().equals(keys)) throw policy ? invalidPolicy() : invalidAggregate();
        return map;
    }
    private static void requireCode(String value) {
        if (value == null || value.isEmpty() || value.length() > 96 || !value.matches("[A-Za-z0-9_.:-]+")) throw invalidPolicy();
    }
    private static boolean isTwo(Object value) {
        try { return value instanceof Number && new BigDecimal(value.toString()).compareTo(BigDecimal.valueOf(2)) == 0; }
        catch (NumberFormatException invalid) { return false; }
    }
    private static boolean decimalEquals(Object value, BigDecimal expected) {
        try { return value instanceof String text && text.length() <= 64 && new BigDecimal(text).compareTo(expected) == 0; }
        catch (NumberFormatException invalid) { return false; }
    }
    private static int count(Object value) {
        try {
            if (!(value instanceof Number)) throw invalidAggregate();
            int result = new BigDecimal(value.toString()).intValueExact();
            if (result < 0 || result > MAX_RECORDS) throw invalidAggregate();
            return result;
        } catch (ArithmeticException | NumberFormatException invalid) { throw invalidAggregate(); }
    }
    private static BigDecimal scoreSum(Object value) {
        if (!(value instanceof String text) || text.length() > 64 || !text.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")) throw invalidAggregate();
        BigDecimal result = new BigDecimal(text);
        if (result.precision() > 32 || result.scale() > SurveySummaryImportsResponses.MAX_SCORE_SCALE) throw invalidAggregate();
        return result;
    }
    private static IllegalArgumentException invalidPolicy() { return new IllegalArgumentException("评分统计政策配置无效"); }
    private static IllegalArgumentException invalidAggregate() { return new IllegalArgumentException("评分统计汇总快照无效"); }
}
