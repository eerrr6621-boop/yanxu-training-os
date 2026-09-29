package com.training;

import java.util.*;

/** Pure fake-worker contract/ordering tests; no credentials, network or real profiles. */
public final class LocalSemanticTest {
    static int passed;
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); passed++; }
    static final String TEXT = "主讲银行客户投诉处理与情绪安抚方法";
    static Map<String,Object> candidate(long id, double score, double professional, boolean local) {
        Map<String,Object> item = new LinkedHashMap<>();
        item.put("teacher_id", id); item.put("score", score); item.put("professional_score", professional);
        item.put("dispatch_fit", Map.of("local_priority", local)); return item;
    }
    static Map<String,Object> response(Object score) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("id", 1); row.put("similarity", score); row.put("evidence", List.of(TEXT));
        return new LinkedHashMap<>(Map.of("model", LocalSemantic.MODEL, "revision", LocalSemantic.REVISION,
                "precision", "int8", "status", "ready", "complete", true, "results", List.of(row)));
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("live")) {
            Map<String,Object> live = candidate(1,50,50,false);
            Map<String,Object> state = LocalSemantic.augment("银行客户服务", List.of(live), Map.of(1L,TEXT));
            check("ready".equals(state.get("status")), "real Java to offline worker contract");
            check(LocalSemantic.similarity(live)!=null, "real finite similarity");
            System.out.println("Real offline model Java integration passed"); return;
        }
        Map<String,Object> candidate = candidate(1, 50, 50, false);
        List<Map<String,Object>> candidates = List.of(candidate);
        Map<Long,String> texts = Map.of(1L, TEXT);
        Map<String,Object> result = LocalSemantic.augment("培训主题：客户投诉", candidates, texts, request -> response(0.6));
        check(result.get("status").equals("ready"), "valid model result accepted");
        Map<String,Object> partial=response(null);
        ((Map<String,Object>)((List<?>)partial.get("results")).get(0)).put("evidence_complete",false);
        Map<Long,Map<String,Object>> partialValidated=LocalSemantic.validate(partial,texts);
        check(Boolean.FALSE.equals(partialValidated.get(1L).get("evidence_complete")) && partialValidated.get(1L).get("similarity")==null,
                "Partial evidence is retained as unscored, not whole-batch failure");
        check(LocalSemantic.similarity(candidate) == 0.6, "semantic score separate");
        check(candidate.get("score").equals(50.0), "no invented professional score");
        check(LocalSemantic.focusedQuery("补充要求：客户单位：银行\n培训主题：服务补救\n期望日期：2099-01-01").equals("服务补救"), "no client/date dominance");
        check(LocalSemantic.focusedQuery("补充要求：银行客户服务\n").equals("银行客户服务"), "plain requirement wrapper is not a professional term");
        check(LocalSemantic.focusedQuery("培训主题：亲子财商\n篮球教学\n期望日期：2099-01-01").equals("亲子财商；篮球教学"), "multiline professional section does not lose mandatory topics");
        check(LocalSemantic.augment("培训主题：客户服务\n补充要求：仅限男性讲师", candidates, texts, request -> { throw new AssertionError("demographic request sent to model"); }).get("status").equals("review_required"), "full request demographic guard precedes focus extraction");
        for (Object invalid : Arrays.asList(Double.NaN, Double.POSITIVE_INFINITY, 1.1, -1.1, "0.8")) {
            candidate.remove("semantic");
            result = LocalSemantic.augment("客户投诉", candidates, texts, request -> response(invalid));
            check(result.get("status").equals("unavailable") && !candidate.containsKey("semantic"), "bad score fails closed");
        }
        for (String key : List.of("model", "revision", "precision", "complete", "results")) {
            Map<String,Object> bad = response(0.6); bad.remove(key);
            result = LocalSemantic.augment("客户投诉", candidates, texts, request -> bad);
            check(result.get("status").equals("unavailable"), "missing " + key);
        }
        check(LocalSemantic.augment("客户投诉", candidates, texts, request -> { throw new java.io.IOException(); }).get("status").equals("unavailable"), "offline fallback");
        check(LocalSemantic.augment("客户投诉", candidates, Map.of(1L,"人工核对后仅擅长办公效能"), request -> response(0.9)).get("status").equals("unavailable"), "raw resume cannot override manual profile");
        Map<String,Object> a = candidate(1,90,85,false), b = candidate(2,80,84,false);
        a.put("semantic", Map.of("similarity",0.5)); b.put("semantic", Map.of("similarity",0.8));
        check(TeacherIntelligence.compareCandidates(a,b,false)>0,"same band semantic comparison");
        b.put("professional_score",79.0);
        check(TeacherIntelligence.compareCandidates(a,b,false)<0,"semantics cannot jump professional band");
        b.put("professional_score",84.0); a.put("dispatch_fit",Map.of("local_priority",true));
        check(TeacherIntelligence.compareCandidates(a,b,true)<0,"locality preserved within band");
        b.put("semantic",new LinkedHashMap<>());
        check(TeacherIntelligence.compareCandidates(a,b,false)<0,"unknown evidence not fabricated");
        DispatchWindow window = new DispatchWindow("2099-01-01",Map.of("training_start_time","14:00","training_end_time","17:00"));
        Map<String,Object> morning = Map.of("teacher_id",1,"teach_date","2099-1-1","start_time","09:00","end_time","12:00","venue","既有场地","status","已确认");
        check(!window.conflicts(morning),"separate intervals");
        check(new DispatchWindow("2099-01-01",Map.of()).conflicts(morning),"unknown requested time uses whole-day check");
        check(window.conflicts(Map.of("teach_date","2099-01-01","start_time","16:00","end_time","18:00")),"overlap excluded");
        check(window.conflicts(Map.of("teach_date","2099-01-01")),"unknown existing time excluded");
        check(window.adjacent(1,List.of(morning)).size()==1,"adjacent venue preserved as unverified");
        for (Object id : Arrays.asList(null, "1", 0, -1, 1.5)) {
            Map<String,Object> orphan = new LinkedHashMap<>(morning); orphan.put("teacher_id", id);
            check(window.adjacent(1,List.of(orphan,morning)).size()==1,"orphan history does not crash recommendation");
        }
        for (Map<String,Object> values : List.<Map<String,Object>>of(Map.of("training_start_time","14:00"),Map.of("training_start_time","18:00","training_end_time","09:00"),Map.of("training_start_time","24:00","training_end_time","25:00"))) {
            try { new DispatchWindow("2099-01-01",values); throw new AssertionError("invalid interval accepted"); }
            catch (IllegalArgumentException expected) { passed++; }
        }
        Map<String,Object> rail = RailTravel.unavailable("杭州","上海","2099-01-01");
        check(Boolean.FALSE.equals(rail.get("verified")),"no fictional rail verification");
        check(rail.get("fare")==null && rail.get("duration_minutes")==null && rail.get("queried_at")==null,"unknown not zero");
        check(((List<?>)rail.get("services")).isEmpty(),"no fake train services");
        check(rail.get("official_query_url").equals("https://www.12306.cn/index/"),"fixed official link only");
        // Total order even when one candidate has no affirmative source evidence.
        List<Map<String,Object>> ordered = new ArrayList<>();
        for (int i=0;i<8;i++) {
            Map<String,Object> entry=candidate(i+1,80+i,75+i,i%2==0);
            entry.put("semantic",i%3==0 ? Map.of() : Map.of("similarity",.3+i*.04)); ordered.add(entry);
        }
        for (boolean local : List.of(false,true)) for (Map<String,Object> x:ordered) for (Map<String,Object> y:ordered) for (Map<String,Object> z:ordered) {
            int xy=TeacherIntelligence.compareCandidates(x,y,local), yz=TeacherIntelligence.compareCandidates(y,z,local);
            if(xy<=0 && yz<=0) check(TeacherIntelligence.compareCandidates(x,z,local)<=0,"comparator transitivity");
        }
        System.out.println("Local semantic and dispatch: " + passed + " checks passed");
    }
}
