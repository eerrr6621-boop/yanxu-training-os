package com.training;
import java.util.*;
import java.time.*;

/** Source-bound passive course/actor regression; authored synthetic examples. */
public final class NamedPassiveHistoryTest {
 static int checks;static final List<String> failures=new ArrayList<>();
 static void check(boolean ok,String label){checks++;if(!ok)failures.add(label);}
 static Map<String,Object> assess(String query,String source){
  var r=new LinkedHashMap<String,Object>();r.put("_rule_relevant",true);r.put("professional_score",80.0);r.put("gaps",new ArrayList<String>());
  new RequirementCoverage(query).assess(r,source,false);return r;
 }
 static boolean admitted(String topic,String source){return Boolean.TRUE.equals(assess("培训主题："+topic+"；必须有本人实际授课记录",source).get("_admitted"));}
 public static void main(String[] args){
  for(String topic:List.of("客户服务","古籍页码核对","园艺工具清洁")) {
   for(String date:List.of("2025年","2025年9月","2025年9月7日"))for(String mode:List.of("面对面","远程")) {
    String source=date+topic+mode+"课程由本人主讲。";
    check(admitted(topic,source),"dated personal passive "+date+topic+mode);
    check(Boolean.TRUE.equals(assess("培训主题："+topic+"；必须有本人实际授课记录；必须"+mode+"授课",source).get("_admitted")),"same passive mode "+date+topic+mode);
    check(!admitted(topic,source.replace("本人","同事")),"different actor "+date+topic+mode);
    check(!admitted(topic,source.replace("由本人主讲","拟由本人主讲")),"dated plan not completion "+date+topic+mode);
   }
   check(admitted(topic,"《"+topic+"》课程已由本人独立主讲。"),"explicit completed passive "+topic);
   check(admitted(topic,"《"+topic+"》已由我讲完。"),"explicit finished passive "+topic);
   check(!admitted(topic,"《"+topic+"》课程由本人主讲。"),"undated assignment not past history "+topic);
   check(!admitted(topic,"2099年《"+topic+"》课程由本人主讲。"),"future date not past history "+topic);
   check(!admitted(topic,"2099年《"+topic+"》课程已由本人主讲。"),"future date with completed marker conflicts "+topic);
   check(!admitted(topic,"《"+topic+"》课程已由本人完成备课。"),"preparation not completed teaching "+topic);
   check(!admitted(topic,"《"+topic+"》课程已由本人主讲。以上只是模板，不代表本人经历。"),"source disclaimer retained "+topic);
   for(String request:List.of("必须本人已讲完这门课","必须本人完成教学")) {
    String q="培训主题："+topic+"；"+request;
    check(!Boolean.TRUE.equals(assess(q,"2025年《"+topic+"》课程由本人主讲。").get("_admitted")),"past delivery does not prove full completion "+topic+request);
    check(Boolean.TRUE.equals(assess(q,"《"+topic+"》课程已由本人讲完。").get("_admitted")),"explicit full completion "+topic+request);
    check(Boolean.TRUE.equals(assess(q,"2025年《"+topic+"》课程由本人主讲。这门课已讲完。").get("_admitted")),"same course later completion "+topic+request);
    check(Boolean.TRUE.equals(assess(q,"2025年《"+topic+"》课程由本人主讲。本人已讲完这门课。").get("_admitted")),"same course active completion "+topic+request);
   }
   check(!admitted(topic,"《另一课程》课程已由本人讲完。\n\n《"+topic+"》课程由本人主讲。"),"completion not borrowed from other course "+topic);
   check(!admitted(topic,"《"+topic+"》课程已由本人主讲。本人只是助教。"),"assistant correction retained "+topic);
   for(String heading:List.of("计划课程","未授课课程","助教经历","团队授课经历")) {
    check(!admitted(topic,heading+"\n\n2025年《"+topic+"》课程由本人主讲。"),"excluded heading retained "+heading+topic);
    check(admitted(topic,heading+"\n\n《另一课程》\n\n实际授课记录\n\n2025年《"+topic+"》课程由本人主讲。"),"separate actual history survives excluded section "+heading+topic);
   }
   check(!admitted(topic,"2025年13月《"+topic+"》课程由本人主讲。"),"invalid month not history "+topic);
   for(String future:List.of("即将","将要","下周","明年"))check(!admitted(topic,future+topic+"课程由本人主讲。"),"future context not a title "+future+topic);
   check(!Boolean.TRUE.equals(assess("培训主题：面对面沟通；必须有本人实际授课记录；必须面对面授课","《面对面沟通》课程已由本人主讲。").get("_admitted")),"quoted course words not delivery proof "+topic);
   String s="2025年《"+topic+"》面对面课程已由本人独立讲完。";
   for(TeachingEvents.Event event:TeachingEvents.extract(s)) {
    String norm=ProfessionalEvidence.normalized(s);
    check(event.map().get("text").equals(norm.substring(event.start,event.end)),"whole literal event "+topic);
    for(TeachingEvents.Field f:event.fields.values())check(f.value().equals(norm.substring(f.start(),f.end())),"literal field "+topic+f.label());
   }
  }
  LocalDate today=LocalDate.of(2026,9,9);
  for(String d:List.of("2025年","2026年8月","2026年9月8日"))check(NamedPassiveTeaching.parse(d+"《工具清洁》课程由本人主讲",today).history(),"elapsed period "+d);
  for(String d:List.of("2026年","2026年9月","2026年9月9日","2026年9月10日","2027年"))check(!NamedPassiveTeaching.parse(d+"《工具清洁》课程由本人主讲",today).history(),"not fully elapsed "+d);
  check(NamedPassiveTeaching.parse("2026年《工具清洁》课程已由本人主讲",today).history(),"explicit marker resolves partly elapsed year");
  check(!NamedPassiveTeaching.parse("2027年《工具清洁》课程已由本人主讲",today).history(),"future year contradicts explicit marker");
  for(String invalid:List.of("2025年2月29日","2024年13月","2024年4月31日"))check(NamedPassiveTeaching.parse(invalid+"《工具清洁》课程由本人主讲",today)==null,"invalid calendar period "+invalid);
  for(String reference:List.of("这门课程","这堂面授课","本次课程","这一主题课程"))check(NamedPassiveTeaching.parse(reference+"由本人主讲",today)==null,"references stay with reference parser "+reference);
  System.out.println(Json.write(Map.of("suite","named-passive-history","checks",checks,"failures",failures,"model_used",false,"real_resumes_used",false,"observe_only",args.length>0)));
  if(args.length==0&&!failures.isEmpty())throw new AssertionError(failures.size()+" named passive checks failed");
 }
}
