package com.training;

import java.util.*;

/** Fixed synthetic actor/mode regression, not model accuracy or real CV data. */
public final class InstructorModeTest {
    static int checks;
    static final List<String> failures=new ArrayList<>();
    static boolean observation;
    static void check(boolean result,String label) {checks++;if(!result)failures.add(label);}
    static Map<String,Object> assess(String query,String source) {
        Map<String,Object> candidate=new LinkedHashMap<>();
        candidate.put("professional_score",80.0);candidate.put("_rule_relevant",true);
        candidate.put("gaps",new ArrayList<String>());
        new RequirementCoverage(query).assess(candidate,source,false);return candidate;
    }
    static boolean admitted(String topic,String mode,String source) {
        return Boolean.TRUE.equals(assess("培训主题："+topic+"；必须有本人实际授课记录；必须"+mode+"授课",source).get("_admitted"));
    }
    static String block(String topic,String mode) {
        return "实际授课记录\n课程名称："+topic+"\n授课人：本人\n授课角色：主讲\n完成状态：已完成\n授课形式："+mode;
    }
    public static void main(String[] args) {
        observation=args.length>0&&args[0].equals("observe");
        for(String topic:List.of("窑炉巡检","古籍页码核对","园艺工具清洁")) {
            String own="本人实际主讲"+topic;
            check(admitted(topic,"面对面",own+"，采用面对面教学。"),topic+" legacy own onsite");
            check(admitted(topic,"远程",own+"，采用远程教学。"),topic+" legacy own remote");
            check(!admitted(topic,"面对面",own+"，学员在线下教室练习。"),topic+" learner onsite not teacher mode");
            check(!admitted(topic,"远程",own+"，学员线上提交作业。"),topic+" learner online not teacher mode");
            check(admitted(topic,"面对面",own+"，采用面对面教学，学员线上提交作业。"),topic+" learner homework does not veto own onsite");
            check(admitted(topic,"远程",own+"，采用远程教学，学员在线下教室练习。"),topic+" learner classroom does not veto own remote");
            for(String mode:List.of("线下","面对面教学","远程","线上实时教学")) {
                boolean remote=mode.contains("远程")||mode.contains("线上");
                check(admitted(topic,remote?"远程":"面对面",block(topic,mode)),topic+" plain field "+mode);
                check(!admitted(topic,remote?"面对面":"远程",block(topic,mode)),topic+" plain field opposite "+mode);
            }
            for(String value:List.of("学员线下练习","学员线上提交作业","教师远程授课，学员线下练习","教师现场面授，学员线上提交作业")) {
                boolean onsite=value.startsWith("教师现场"),remote=value.startsWith("教师远程");
                check(admitted(topic,"面对面",block(topic,value))==onsite,topic+" bound field onsite "+value);
                check(admitted(topic,"远程",block(topic,value))==remote,topic+" bound field remote "+value);
            }
            for(String value:List.of("本人现场授课由同事负责","本人学习线上教学方法","本人旁听远程授课","本人负责组织线下授课",
                    "本人计划现场授课","本人可以提供面授教学","本人协助远程授课","本人推荐线上授课","本人可能现场授课",
                    "本人指导学员线上练习","学员表示教师远程授课","线下或线上","线上与线下结合","《线下授课》","“远程授课”","《面授",
                    "教师现场面授，教师远程授课","教师现场面授，教学安排可能远程","教师远程授课，学员观看教师线下授课")) {
                check(!admitted(topic,"面对面",block(topic,value)),topic+" ambiguous/nonpersonal onsite "+value);
                check(!admitted(topic,"远程",block(topic,value)),topic+" ambiguous/nonpersonal remote "+value);
            }
            String source=block(topic,"教师现场面授，学员线上提交作业");
            Map<String,Object> result=assess("培训主题："+topic+"；必须有本人实际授课记录；必须面对面授课",source);
            for(Object raw:(List<?>)result.get("teaching_events")) {
                Map<?,?> event=(Map<?,?>)raw;
                String normalized=ProfessionalEvidence.normalized(source);
                int a=normalized.offsetByCodePoints(0,((Number)event.get("source_start")).intValue()),b=normalized.offsetByCodePoints(0,((Number)event.get("source_end")).intValue());
                check(normalized.substring(a,b).equals(event.get("text")),topic+" full normalized source not masked in audit");
            }
            check(!admitted(topic,"面对面",source.replace("已完成","计划中")),topic+" mode cannot create completion");
            check(!admitted(topic,"面对面",source.replace("授课人：本人","授课人：同事")),topic+" mode cannot create personal actor");
            check(!admitted(topic,"面对面",block(topic,"远程")+"\n\n"+block("其他课程","线下")),topic+" onsite mode cannot cross courses");
            check(!admitted(topic,"远程",block(topic,"线下")+"\n\n"+block("其他课程","远程")),topic+" remote mode cannot cross courses");
            check(!admitted(topic,"面对面",own+"，采用面对面教学，教师远程授课。"),topic+" prose teacher conflict stays pending");
            check(admitted(topic,"面对面",source.replace("\n","\r\n")),topic+" CRLF mode field");
            check(!Boolean.TRUE.equals(assess("培训主题："+topic+"；必须线上实时教学",block(topic,"录播")).get("_admitted")),topic+" recording not live");
            check(Boolean.TRUE.equals(assess("培训主题："+topic+"；必须线上实时教学",block(topic,"线上实时教学")).get("_admitted")),topic+" actual declared live");
        }
        for(String topic:List.of("团队管理","组织沟通","学习方法")) {
            check(admitted(topic,"面对面","本人实际主讲面对面"+topic+"课程。"),"ordinary topic words are not actor exclusions "+topic);
            check(admitted(topic,"远程","本人实际主讲远程"+topic+"课程。"),"ordinary topic words are not remote exclusions "+topic);
        }
        for(String mode:List.of("面对面","远程"))for(String time:List.of("2025年","2025年9月","2025年9月7日")) {
            check(admitted("客户服务",mode,time+"本人实际主讲"+mode+"客户服务课程。"),"dated personal action "+time+mode);
            String passive=time+"客户服务"+mode+"课程由本人主讲。";
            check(InstructorMode.prose(passive,mode.equals("远程")?"远程授课形式":"面对面授课形式",Map.of("客户服务",List.of("客户服务"))),"dated passive mode attribution "+time+mode);
            check(admitted("客户服务",mode,passive),"dated passive business history "+time+mode);
            check(!admitted("客户服务",mode,time+"客户服务"+mode+"课程由同事主讲。"),"dated other actor not borrowed "+time+mode);
        }
        System.out.println(Json.write(Map.of("suite","instructor-mode","checks",checks,"failures",failures,"observation_only",observation,"model_used",false,"real_resumes_used",false)));
        if(!observation&&!failures.isEmpty())throw new AssertionError(failures.size()+" instructor mode checks failed");
    }
}
