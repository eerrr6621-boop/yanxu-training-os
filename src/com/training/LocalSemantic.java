package com.training;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Optional offline evidence matching. The only network destination is IPv4 loopback.
 * No model output can add qualifications, bypass eligibility, or mutate a profile. */
final class LocalSemantic {
    static final String MODEL = "BAAI/bge-small-zh-v1.5";
    static final String REVISION = "7999e1d3359715c523056ef9478215996d62a620";
    private static final Semaphore SLOT = new Semaphore(1);
    private static final int MAX_BYTES = 512 * 1024;

    interface Transport { Map<String, Object> compare(Map<String, Object> request) throws Exception; }

    static Map<String, Object> augment(String query, List<Map<String, Object>> candidates,
                                       Map<Long, String> effectiveTexts) {
        String rawPort = setting("semantic.port", "YANXU_SEMANTIC_PORT");
        String token = setting("semantic.token", "YANXU_SEMANTIC_TOKEN");
        if (rawPort.isEmpty() || token.length() < 32)
            return status("disabled", "当前使用专业标签与关键词匹配");
        if (!SLOT.tryAcquire()) return status("busy", "语义辅助繁忙，本次保留规则匹配结果");
        try {
            int port = Integer.parseInt(rawPort);
            if (port < 1024 || port > 65535) throw new IllegalArgumentException();
            return augment(query, candidates, effectiveTexts, request -> request(port, token, request));
        } catch (Exception ignored) {
            return status("unavailable", "语义辅助暂不可用，本次保留规则匹配结果");
        } finally { SLOT.release(); }
    }

    static Map<String, Object> augment(String query, List<Map<String, Object>> candidates,
                                       Map<Long, String> effectiveTexts, Transport transport) {
        // Inspect the complete request before focusedQuery can omit other fields.
        // This worker compares professional content, not demographic preferences.
        if (normalized(query).matches("(?s).*(?:(?:男|女)(?:性)?(?:讲师|老师)|(?:讲师|老师)(?:性别)?[:：]?(?:男|女)|(?:性别|年龄|出生日期|民族|婚姻状况|宗教信仰)\\s*[:：]).*"))
            return status("review_required", "本次保留专业规则匹配，个人属性不参与语义排序");
        if (candidates.isEmpty()) return status("no_candidates", "暂无通过当前条件筛选的候选");
        if (candidates.size() > 200) return status("capacity_limit", "候选较多，本次保留规则匹配结果");
        Map<Long, String> documentsById = new LinkedHashMap<>();
        List<Map<String, Object>> documents = new ArrayList<>();
        for (Map<String, Object> candidate : candidates) {
            long id = ((Number) candidate.get("teacher_id")).longValue();
            String text = normalized(effectiveTexts.getOrDefault(id, ""));
            documentsById.put(id, text);
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("id", id); document.put("text", text);
            List<Map<String,Object>> eventScopes=TeachingEvents.scoringSections(text);
            if(!eventScopes.isEmpty())document.put("server_teaching_event_scopes",eventScopes);
            documents.add(document);
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("query", focusedQuery(query)); request.put("documents", documents);
        if (Json.write(request).getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            return status("capacity_limit", "专业资料较长，本次保留规则匹配结果");
        try {
            Map<String, Object> response = transport.compare(request);
            Map<Long, Map<String, Object>> validated = validate(response, documentsById);
            // Attach only after EVERY response item is validated. No partial rankings.
            for (Map<String, Object> candidate : candidates) {
                long id = ((Number) candidate.get("teacher_id")).longValue();
                candidate.put("semantic", validated.get(id));
            }
            Map<String, Object> result = status("ready", "本地语义辅助已完成；相似度仅供比较，不代表胜任概率");
            result.put("model", MODEL); result.put("precision", response.get("precision"));
            result.put("candidate_count", candidates.size());
            result.put("eligibility_changed", false);
            return result;
        } catch (Exception ignored) {
            return status("unavailable", "语义辅助暂不可用，本次保留规则匹配结果");
        }
    }

    static Map<Long, Map<String, Object>> validate(Map<String, Object> response, Map<Long, String> texts) {
        if (!Boolean.TRUE.equals(response.get("complete")) || !"ready".equals(response.get("status")) ||
                !MODEL.equals(response.get("model")) || !REVISION.equals(response.get("revision")) ||
                !Arrays.asList("int8", "fp32").contains(response.get("precision")) ||
                !(response.get("results") instanceof List<?>)) throw new IllegalArgumentException();
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        for (Object raw : (List<?>) response.get("results")) {
            if (!(raw instanceof Map<?, ?>)) throw new IllegalArgumentException();
            Map<?, ?> row = (Map<?, ?>) raw;
            if (!row.containsKey("similarity")) throw new IllegalArgumentException();
            Object idValue = row.get("id");
            if (!(idValue instanceof Number)) throw new IllegalArgumentException();
            long id = ((Number) idValue).longValue();
            if (((Number) idValue).doubleValue() != id || !texts.containsKey(id) || result.containsKey(id)) throw new IllegalArgumentException();
            Object score = row.get("similarity");
            if (score != null && (!(score instanceof Number) || !Double.isFinite(((Number) score).doubleValue()) ||
                    ((Number) score).doubleValue() < -1 || ((Number) score).doubleValue() > 1)) throw new IllegalArgumentException();
            if (!(row.get("evidence") instanceof List<?>)) throw new IllegalArgumentException();
            List<String> evidence = new ArrayList<>();
            for (Object quote : (List<?>) row.get("evidence")) {
                if (!(quote instanceof String) || ((String) quote).length() < 2 || ((String) quote).length() > 440 ||
                        !texts.get(id).contains((String) quote)) throw new IllegalArgumentException();
                evidence.add((String) quote);
            }
            Object evidenceComplete=row.getOrDefault("evidence_complete",null);
            if(row.containsKey("evidence_complete") && !(evidenceComplete instanceof Boolean)) throw new IllegalArgumentException();
            boolean scoped=Boolean.FALSE.equals(evidenceComplete) && EvidenceSections.verified(texts.get(id),row);
            if(Boolean.TRUE.equals(row.get("scoped_evidence_complete")) && !scoped) throw new IllegalArgumentException();
            if(EvidenceSections.MIXED_VERSION.equals(row.get("scoring_scope"))) {
                if(!scoped||!(row.get("evidence_units") instanceof List<?> units)||units.size()!=evidence.size())throw new IllegalArgumentException();
                for(int i=0;i<units.size();i++)if(!(units.get(i) instanceof Map<?,?> unit)||!evidence.get(i).equals(unit.get("text"))||
                    !EvidenceSections.containsUnit(texts.get(id),row,unit))throw new IllegalArgumentException();
            }
            if(scoped && (score==null || evidence.isEmpty() || evidence.stream().anyMatch(q->!EvidenceSections.containsQuote(texts.get(id),row,q)))) throw new IllegalArgumentException();
            if(Boolean.FALSE.equals(evidenceComplete) && score!=null && !scoped) throw new IllegalArgumentException();
            if(evidence.size()>2 || score!=null && evidence.isEmpty() ||
                    !Boolean.FALSE.equals(evidenceComplete) && (score==null)!=evidence.isEmpty()) throw new IllegalArgumentException();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("status", score == null ? "no_evidence" : "ready");
            item.put("similarity", score); item.put("evidence", evidence);
            item.put("source", "effective_profile"); item.put("verified_qualification", false);
            item.put("evidence_complete",!Boolean.FALSE.equals(evidenceComplete));
            if(scoped) {
                item.put("scoped_evidence_complete",true);item.put("scoring_scope",row.get("scoring_scope"));
                item.put("scoring_sections",row.get("scoring_sections"));
            }
            if(response.containsKey("requirement_analysis")) {
                if(!(response.get("requirement_analysis") instanceof Map<?,?> analysis) ||
                        !(analysis.get("requires_review") instanceof Boolean) ||
                        !(analysis.get("exclusions") instanceof List<?> exclusions)) throw new IllegalArgumentException();
                if(exclusions.size()>64 || exclusions.stream().anyMatch(e->!(e instanceof String) || ((String)e).length()>10000)) throw new IllegalArgumentException();
                item.put("query_requires_review",analysis.get("requires_review"));
                item.put("query_exclusions",new ArrayList<>(exclusions));
            }
            result.put(id, item);
        }
        if (!result.keySet().equals(texts.keySet())) throw new IllegalArgumentException();
        return result;
    }

    static Double similarity(Map<String, Object> candidate) {
        Object raw = candidate.get("semantic");
        if (!(raw instanceof Map<?, ?>)) return null;
        Object score = ((Map<?, ?>) raw).get("similarity");
        if (!(score instanceof Number)) return null;
        double value = ((Number) score).doubleValue();
        return Double.isFinite(value) && value >= -1 && value <= 1 ? value : null;
    }

    private static Map<String, Object> status(String state, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", state); result.put("message", message);
        result.put("external_ai", false); result.put("mode", "evidence_assist");
        return result;
    }

    static String normalized(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).strip();
    }

    static String focusedQuery(String value) {
        String normalized = normalized(value).replaceAll("(?m)^补充要求[:：]", "");
        // A soft wrap after a comma must not become a semicolon that detaches
        // a postposed rejection. This is a retrieval projection only; the
        // complete original requirement remains the hard-check source.
        StringBuilder folded=new StringBuilder(normalized);
        for(PostposedExclusion.Span span:PostposedExclusion.spans(normalized))
            for(int i=span.start();i<span.end();i++)if(folded.charAt(i)=='\r'||folded.charAt(i)=='\n')folded.setCharAt(i,' ');
        normalized=folded.toString();
        List<String> focus = new ArrayList<>(), fallback = new ArrayList<>();
        String dateLabel="(?:期望日期|计划日期|培训日期|授课日期|计划授课日期|开课日期|培训时间|授课时间)";
        String moneyLabel="(?:项目总预算|总预算|课酬预算|预算(?:上限)?|每课时(?:预算|课酬)上限)";
        String separator="[ \\t]*[:：][ \\t]*";
        String operations="(?:"+dateLabel+separator+"20\\d{2}[年./-]\\d{1,2}[月./-]\\d{1,2}日?(?:[ \\t]+\\d{1,2}[:：]\\d{2})?(?:开课|授课)?|"+
                moneyLabel+separator+"(?:每课时)?(?:不超过|最多|上限|≤|<=)?[ \\t]*(?:人民币|[¥￥])?[ \\t]*\\d+(?:\\.\\d+)?[ \\t]*(?:万元|元)?(?:[/／]课时)?|"+
                "预计课时"+separator+"\\d+(?:\\.\\d+)?[ \\t]*(?:课时|小时)?)";
        boolean inProfessionalSection=false;
        for (String line : normalized.split("\\R")) {
            String text = line.strip().replaceFirst("^补充要求[:：]", "");
            // Omit only a complete operational line, never a date/amount inside
            // a course name, goal, or a mixed line with another requirement.
            if(text.matches(operations)) {
                // The following physical line was NOT part of that match.
                // Do not let an operational heading hide a later condition.
                inProfessionalSection=true;
                continue;
            }
            fallback.add(line);
            if (text.matches("^(授课对象|培训对象|参训对象|学员对象|受众)"+separator+".*")) {
                inProfessionalSection=true;
                focus.add(text);
            } else if (text.matches("^(培训主题|培训内容|希望解决的问题|培训目标|师资要求|讲师要求|补充说明)"+separator+".*")) {
                inProfessionalSection=true;
                focus.add(text.replaceFirst("^[^:：]+[:：][ \\t]*", ""));
            } else if(text.matches("^(?:"+dateLabel+"|"+moneyLabel+"|预计课时)"+separator+".*")) {
                // A recognized operational label with a non-complete value is
                // retained literally; projection cannot decide its qualifiers.
                inProfessionalSection=true;
                focus.add(text);
            } else if(text.matches("^[^:：]{1,30}[:：].*")) inProfessionalSection=false;
            else if(inProfessionalSection && !text.isBlank()) focus.add(text);
        }
        String professional=focus.isEmpty()?String.join("\n",fallback):String.join("；",focus);
        return professional.replaceAll("[，,；;]+$","").strip();
    }

    /** Full hard-condition input, independent of retrieval focus. Only exact
     * operational date/money fields are masked; other fields and qualifiers
     * remain intact. Spaces preserve original UTF-16 offsets and line breaks. */
    static String requirementInput(String value) {
        String source=value==null?"":value;
        StringBuilder result=new StringBuilder(source);
        String money="(?:项目总预算|总预算|课酬预算|预算(?:上限)?|每课时(?:预算|课酬)上限)[:：]\\s*(?:每课时)?(?:不超过|最多|上限|≤|<=)?\\s*(?:人民币|[¥￥])?\\s*\\d+(?:[.．]\\d+)?\\s*(?:万元|元)?(?:[/／]课时)?";
        String date="(?:计划日期|培训日期|授课日期|计划授课日期|开课日期|培训时间|授课时间)[:：]\\s*20\\d{2}[年./-]\\d{1,2}[月./-]\\d{1,2}日?(?:\\s*\\d{1,2}[:：]\\d{2})?";
        java.util.regex.Matcher field=java.util.regex.Pattern.compile("(?m)^[ \\t]*(?:"+money+"|"+date+")[ \\t]*$").matcher(source);
        while(field.find()) for(int at=field.start();at<field.end();at++)
            if(source.charAt(at)!='\r'&&source.charAt(at)!='\n') result.setCharAt(at,' ');
        return result.toString();
    }

    private static String setting(String property, String environment) {
        return System.getProperty(property, System.getenv().getOrDefault(environment, "")).strip();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> request(int port, String token, Map<String, Object> request) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http", "127.0.0.1", port, "/compare").openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(500); connection.setReadTimeout(8500);
        connection.setInstanceFollowRedirects(false); connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setDoOutput(true);
        byte[] body = Json.write(request).getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);
        try {
            try (OutputStream out = connection.getOutputStream()) { out.write(body); }
            if (connection.getResponseCode() != 200) throw new IOException();
            byte[] bytes;
            try (InputStream in = connection.getInputStream()) { bytes = in.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) throw new IOException();
            Object parsed = Json.parse(new String(bytes, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map)) throw new IOException();
            return (Map<String, Object>) parsed;
        } finally { connection.disconnect(); }
    }
}
