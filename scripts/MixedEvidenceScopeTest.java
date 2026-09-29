package com.training;
import java.util.*;

/** Author-fixed synthetic protocol pairs; no model, database or held-out input. */
public final class MixedEvidenceScopeTest {
    static int checks;static final List<String> failures=new ArrayList<>();
    static void check(boolean value,String label){checks++;if(!value)failures.add(label);}
    static String intro(String topic,String catalog){return "✓ "+topic+"\n✓ 分类标记整理\n个人介绍\n本人参加过陶艺课程培训。\n精品课程\n《"+catalog+"》\n\n实际授课记录\n\n";}
    static String event(String topic,String audience){return "本人已独立面向"+audience+"讲授过"+topic+"。";}
    static Map<String,Object> meta(String text){List<Map<String,Object>> scopes=new ArrayList<>(EvidenceSections.sections(text));scopes.addAll(TeachingEvents.scoringSections(text));
        return new LinkedHashMap<>(Map.of("evidence_complete",false,"scoped_evidence_complete",true,"scoring_scope","mixed_source_scopes_v1","scoring_sections",scopes));}
    static Map<String,Object> assess(String query,String source,Map<String,Object> details){Map<String,Object> c=new LinkedHashMap<>();c.put("professional_score",20.0);c.put("_rule_relevant",true);c.put("gaps",new ArrayList<String>());c.put("semantic",details);new RequirementCoverage(query).assess(c,source,false);return c;}
    static boolean admitted(String query,String source){return Boolean.TRUE.equals(assess(query,source,meta(source)).get("_admitted"));}
    @SuppressWarnings("unchecked") static Map<String,Object> copy(Map<String,Object> value){return (Map<String,Object>)Json.parse(Json.write(value));}
    static Map<String,Object> unit(String source,Map<?,?> scope){int a=((Number)scope.get("source_start")).intValue(),b=((Number)scope.get("source_end")).intValue();
        Map<String,Object> unit=new LinkedHashMap<>(Map.of("text",source.substring(source.offsetByCodePoints(0,a),source.offsetByCodePoints(0,b)),"source_start",a,"source_end",b,"paragraph_start",a,"paragraph_end",b,
            "offset_unit","unicode_code_point","kind","retrieval_only","context_status","server_source_event"));unit.put("section_heading",null);return unit;}
    static boolean transportAccepts(String text,Map<String,Object> details,Map<String,Object> u){Map<String,Object> row=new LinkedHashMap<>(details);row.put("id",1);row.put("similarity",0.5);row.put("evidence",List.of(u.get("text")));row.put("evidence_units",List.of(u));
        try{LocalSemantic.validate(new LinkedHashMap<>(Map.of("complete",true,"status","ready","model",LocalSemantic.MODEL,"revision",LocalSemantic.REVISION,"precision","int8","results",List.of(row))),Map.of(1L,text));return true;}catch(IllegalArgumentException expected){return false;}}
    @SuppressWarnings("unchecked") public static void main(String[] args){
        for(String[] d:List.of(new String[]{"岩样编号核对","岩样封存"},new String[]{"皮革裁片归档","库存盘点"},new String[]{"酿造桶号标识","容器清洗"})){
            String topic=d[0],catalog=d[1],text=LocalSemantic.normalized(intro(topic,catalog)+event(topic,"新员工"));Map<String,Object> details=meta(text);
            check(!TeachingEvents.scoringSections(text).isEmpty(),"source parser really generated event "+topic);
            check(EvidenceSections.verified(text,details),"mixed families reverified "+topic);
            check(EvidenceSections.containsQuote(text,details,"✓ "+topic),"leading catalog retained "+topic);
            check(EvidenceSections.containsQuote(text,details,"《"+catalog+"》"),"named catalog retained "+topic);
            Map<String,Object> profileScope=EvidenceSections.sections(text).stream().filter(s->"leading_profile_list_v1".equals(s.get("kind"))).findFirst().orElseThrow();
            Map<String,Object> profileUnit=unit(text,profileScope);profileUnit.put("context_status","paragraph_preserved");
            check(EvidenceSections.containsUnit(text,details,profileUnit),"actual worker retained paragraph may use null heading "+topic);
            profileUnit.put("section_heading","");check(EvidenceSections.containsUnit(text,details,profileUnit),"created headingless profile may use empty heading "+topic);
            profileUnit.put("section_heading","精品课程");check(!EvidenceSections.containsUnit(text,details,profileUnit),"headingless profile cannot borrow actual distant title "+topic);
            check(EvidenceSections.maskedSource(text,details).isEmpty(),"mixed never exposes a synthesized biography for reparsing "+topic);
            EvidenceSections.MixedEvidence view=EvidenceSections.mixedEvidence(text,details);
            check(view.events().size()==1,"same event found by section and event paths counted once "+topic);
            check(view.records().stream().anyMatch(r->r.get("text").toString().equals("《"+catalog+"》")),"catalog original record retained "+topic);
            check(view.records().stream().filter(r->r.get("text").toString().equals("《"+catalog+"》")).noneMatch(r->r.get("role").equals("teaching_history_statement")),"catalog not promoted to history "+topic);
            for(Map<String,Object> r:view.records()){int a=((Number)r.get("source_offset")).intValue();check(text.startsWith(r.get("text").toString(),a),"original record span "+topic);}
            check(admitted("培训主题："+topic+"；必须有本人实际授课记录；必须面向新员工",text),"same event positive remains usable "+topic);
            check(!admitted("培训主题："+catalog+"；必须有本人实际授课记录",text),"catalog cannot borrow other-course history "+topic);
            String laterCatalog=LocalSemantic.normalized("个人介绍\n本人参加过陶艺课程培训。\n\n授课风采\n\n"+event(topic,"新员工")+"\n\n精品课程\n《"+catalog+"课程》");
            EvidenceSections.MixedEvidence laterView=EvidenceSections.mixedEvidence(laterCatalog,meta(laterCatalog));
            check(laterView.records().stream().anyMatch(r->r.get("text").equals("《"+catalog+"课程》")),"later catalog still recalled "+topic);
            check(laterView.records().stream().filter(r->r.get("text").equals("《"+catalog+"课程》")).noneMatch(r->"teaching_history_statement".equals(r.get("role"))),"earlier historical heading does not upgrade later catalog "+topic);
            check(assess("培训主题："+catalog,laterCatalog,meta(laterCatalog)).get("scoped_evidence_role_limits") instanceof List<?> limits&&!limits.isEmpty(),"content-only source role limit retained in candidate audit "+topic);
            for(String heading:List.of("主讲课程","课程列表")) {
                String headed=laterCatalog.replace("精品课程",heading);
                check(EvidenceSections.mixedEvidence(headed,meta(headed)).records().stream().filter(r->r.get("text").equals("《"+catalog+"课程》")).noneMatch(r->"teaching_history_statement".equals(r.get("role"))),"catalog heading alone is not completion "+heading+topic);
            }
            String presentOnly=laterCatalog.replace("《"+catalog+"课程》","本人主讲"+catalog+"课程。");
            check(EvidenceSections.mixedEvidence(presentOnly,meta(presentOnly)).records().stream().filter(r->r.get("text").equals("本人主讲"+catalog+"课程。")).noneMatch(r->"teaching_history_statement".equals(r.get("role"))),"present capability without completion cannot inherit past event "+topic);
            String laterActual=laterCatalog.replace("《"+catalog+"课程》","\n"+event(catalog,"新员工"));
            check(admitted("培训主题："+catalog+"；必须有本人实际授课记录；必须面向新员工",laterActual),"explicit personal teaching in same catalog region still supported "+topic);
            String cross=LocalSemantic.normalized(text+"\n\n"+event(catalog,"资深员工"));
            check(EvidenceSections.verified(cross,meta(cross)),"two independent events scope-valid "+topic);
            check(!admitted("培训主题："+topic+"；必须有本人实际授课记录；必须面向资深员工",cross),"other course audience cannot transfer "+topic);
            check(admitted("培训主题："+catalog+"；必须有本人实际授课记录；必须面向资深员工",cross),"correct second event not lost "+topic);
            String counted=LocalSemantic.normalized(intro(topic,catalog)+event(catalog,"资深员工")+"\n\n本人实际主讲"+topic+"1场。\n\n本人实际主讲"+catalog+"3场。");
            check(EvidenceSections.verified(counted,meta(counted)),"count fixture has mixed source ranges "+topic);
            check(!admitted("培训主题："+topic+"；至少3场",counted),"other-course count not borrowed "+topic);
            check(admitted("培训主题："+topic+"；至少1场",counted),"same course explicit count remains usable "+topic);
            check(!admitted("培训主题："+topic+"；至少2场",text),"duplicate discovery is not two sessions "+topic);
            for(String change:List.of("\n以上不是本人授课经历。","\n上述列表属于团队能力。","\n本人角色：助教"))check(!EvidenceSections.verified(text+change,details),"full context invalidates stale mixed scope "+topic);
            String denied=text+"\n以上不是本人授课经历。";
            check(TeachingEvents.scoringSections(denied).isEmpty()&&EvidenceSections.sections(denied).isEmpty(),"fresh recomputation respects full-source denial "+topic);
            for(String version:List.of("independent_profiles_v1","independent_facts_v2","independent_sections_v1","source_teaching_events_v1")){Map<String,Object> old=copy(details);old.put("scoring_scope",version);check(!EvidenceSections.verified(text,old),"old version cannot carry mixed kinds "+topic);}
            List<?> eventScopes=TeachingEvents.scoringSections(text);Map<String,Object> goodUnit=unit(text,(Map<?,?>)eventScopes.get(0));
            check(EvidenceSections.containsUnit(text,details,goodUnit),"exact event unit permitted "+topic);
            check(transportAccepts(text,details,goodUnit),"production protocol accepts literal mixed unit "+topic);
            Map<String,Object> tampered=copy(goodUnit);tampered.put("source_start",0);check(!transportAccepts(text,details,tampered),"same quote with forged occurrence rejected "+topic);
            tampered=copy(goodUnit);tampered.remove("context_status");check(!transportAccepts(text,details,tampered),"missing unit metadata rejected "+topic);
            tampered=copy(details);((List<Object>)tampered.get("scoring_sections")).add(copy((Map<String,Object>)((List<?>)tampered.get("scoring_sections")).get(0)));check(!EvidenceSections.verified(text,tampered),"duplicate scope rejected "+topic);
            for(String key:List.of("source_start","source_end","context_end","offset_unit","heading","kind")){
                tampered=copy(details);Map<String,Object> profile=((List<Map<String,Object>>)tampered.get("scoring_sections")).stream().filter(s->"leading_profile_list_v1".equals(s.get("kind"))).findFirst().orElseThrow();
                profile.put(key,key.equals("offset_unit")?"utf16":key.equals("heading")?"擅长领域":key.equals("kind")?"source_teaching_events_v1":1);
                check(!EvidenceSections.verified(text,tampered),"profile tamper rejected "+topic+" "+key);
            }
            Map<String,Object> eventOnly=copy(details);eventOnly.put("scoring_sections",eventScopes);check(!EvidenceSections.verified(text,eventOnly),"mixed must actually have both families "+topic);
            tampered=copy(details);Map<String,Object> forgedEvent=((List<Map<String,Object>>)tampered.get("scoring_sections")).stream().filter(s->TeachingEvents.SCORING_SCOPE.equals(s.get("kind"))).findFirst().orElseThrow();
            forgedEvent.put("event_id","event-0");check(!EvidenceSections.verified(text,tampered),"event identity not supplied by the worker "+topic);
            tampered=copy(goodUnit);int unauthorised=text.indexOf("本人参加过");String omitted="本人参加过陶艺课程培训。";
            tampered.put("text",omitted);tampered.put("source_start",unauthorised);tampered.put("source_end",unauthorised+omitted.length());tampered.put("paragraph_start",unauthorised);tampered.put("paragraph_end",unauthorised+omitted.length());
            check(!transportAccepts(text,details,tampered),"literal quotation outside every approved range rejected "+topic);
            String unicode=LocalSemantic.normalized(intro(topic+"📚",catalog)+event(topic,"新员工"));Map<String,Object> uMeta=meta(unicode);
            check(EvidenceSections.verified(unicode,uMeta),"non-BMP full source indices "+topic);
            check(EvidenceSections.containsUnit(unicode,uMeta,unit(unicode,TeachingEvents.scoringSections(unicode).get(0))),"non-BMP quote unit exact "+topic);
            String beforeHeading=LocalSemantic.normalized("教育背景\n本人参加过陶艺课程培训。\n个人介绍\n擅长资料整理。\n《"+catalog+"》\n《样本分类》\n精品课程\n《文档编号》\n\n实际授课记录\n\n"+event(topic,"新员工"));
            Map<String,Object> orderMeta=meta(beforeHeading);check(EvidenceSections.verified(beforeHeading,orderMeta),"course entries before heading are real scopes "+topic);
            for(Map<String,Object> s:EvidenceSections.sections(beforeHeading))if("course_catalog_entry_v2".equals(s.get("kind"))) {
                Map<String,Object> ordered=unit(beforeHeading,s);ordered.put("section_heading",s.get("heading"));ordered.put("context_status","independent_fact_after_section_review");
                ordered.put("paragraph_start",s.get("context_start"));ordered.put("paragraph_end",s.get("context_end"));
                check(EvidenceSections.containsUnit(beforeHeading,orderMeta,ordered),"following heading bound through exact catalog scope "+topic);
                check(transportAccepts(beforeHeading,orderMeta,ordered),"production accepts following-heading literal unit "+topic);
                ordered.put("section_heading","实际授课记录");check(!transportAccepts(beforeHeading,orderMeta,ordered),"another real heading cannot be borrowed "+topic);
                ordered.put("section_heading",null);check(!transportAccepts(beforeHeading,orderMeta,ordered),"nonempty catalog title cannot be omitted "+topic);
            }
            check(!EvidenceSections.containsUnit(text,details,null),"null evidence unit rejected "+topic);
        }
        System.out.println(Json.write(Map.of("checks",checks,"failures",failures,"notice","synthetic protocol/eligibility regression; no model execution")));
        if(!failures.isEmpty())throw new AssertionError(failures.size()+" mixed failures");
    }
}
