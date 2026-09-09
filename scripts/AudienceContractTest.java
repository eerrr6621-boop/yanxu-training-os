package com.training;
import java.util.*;

/** Synthetic developer pairs; no model/real resume/held-out-label access. */
public final class AudienceContractTest {
    static int count;
    static void check(boolean value,String message){count++;if(!value)throw new AssertionError(message);}
    static boolean admitted(String query,String source) {
        Map<String,Object> c=new LinkedHashMap<>();c.put("professional_score",20.0);c.put("_rule_relevant",true);c.put("gaps",new ArrayList<String>());
        new RequirementCoverage(query).assess(c,source,false);return Boolean.TRUE.equals(c.get("_admitted"));
    }
    static String event(String audience){return "课程名称：展柜湿度登记\n授课人：本人\n授课角色：独立主讲\n完成状态：已完成\n培训对象："+audience;}
    static void pair(String requirement,String evidence,boolean expected) {
        AudienceContract.Comparison result=AudienceContract.compare(requirement,evidence);
        check(result.supported()==expected,"contract "+requirement+" / "+evidence);
        check(admitted("培训主题：展柜湿度登记\n参训对象："+requirement+"\n请找以前给这类学员讲完这门课的老师",event(evidence))==expected,"explicit same-audience history "+requirement+" / "+evidence);
        for(AudienceContract.Contract c:List.of(result.requirement(),result.evidence()))for(AudienceContract.Group g:c.groups()) {
            check(c.source().substring(g.span().start(),g.span().end()).equals(g.raw()),"group literal span");
            for(AudienceContract.Span s:g.components())check(c.source().substring(s.start(),s.end()).equals(s.text()),"component literal span");
        }
    }
    public static void main(String[] args) {
        // The original 12 read-only diagnostic pairs, unchanged.
        pair("新一批银行厅堂服务人员","银行厅堂服务人员",true);
        pair("银行厅堂服务人员","银行厅堂服务人员",true);
        pair("新入职的展馆讲解员","刚入职的展馆讲解员",true);
        pair("测站实习观测员","刚到测站实习的观测员",true);
        pair("新入职银行厅堂服务人员","资深银行厅堂服务人员",false);
        pair("新一批资深银行厅堂服务人员","银行厅堂服务人员",false);
        pair("新入职银行厅堂服务人员","新一批银行厅堂服务人员",false);
        pair("测站实习观测员","气象台实习观测员",false);
        pair("测站实习观测员","测站实习设备维护员",false);
        pair("测站实习观测员","测站非实习观测员",false);
        pair("测站实习观测员","测站观测员、实习设备维护员",false);
        pair("完成考核的测站实习观测员","测站实习观测员",false);
        for(String role:List.of("档案管理员","剧场灯光操作员","设备维护员")) {
            pair("新一批甲单位实习"+role,"刚到甲单位实习的"+role,true);
            pair("甲单位实习"+role,"刚到乙单位实习的"+role,false);
            pair("新入职的"+role,"实习"+role,false);
            pair("新入职的"+role+"(完成考核)","刚入职的"+role,false);
            pair("新入职的"+role+"(完成考核)","刚入职的"+role+"(完成考核)",true);
            pair("同一批"+role,role,false);
            pair("实习"+role,"未实习"+role,false);
            pair("实习"+role,"准备安排实习"+role,false);
        }
        pair("档案管理员、财务审核员","财务审核员、档案管理员",true);
        pair("档案管理员、财务审核员","档案管理员",false);
        pair("新入职的档案管理员(零基础)","刚入职的档案管理员(零基础)",true);
        pair("新入职的档案管理员(零基础","刚入职的档案管理员(零基础)",false);
        check(AudienceContract.compare("新一批档案管理员","这批档案管理员").supported(),"batch type references are not seniority");
        check(!AudienceContract.compare("刚到甲单位实习的档案管理员","甲单位实习档案管理员").supported(),"recent arrival requirement remains explicit");
        check(!AudienceContract.compare("新入职的档案管理员","新入职的档案管理员除外").supported(),"excluded positive phrase cannot match");
        String source="本人主讲展柜湿度登记课程，受众：刚入职的档案管理员。";
        check(admitted("培训主题：展柜湿度登记\n受众：新入职的档案管理员",source),"legacy labelled field uses shared contract");
        check(admitted("培训主题：展柜湿度登记\n受众：新入职的档案管理员","本人主讲展柜湿度登记课程。\n本人主讲设备维护课程，受众：刚入职的档案管理员。"),"plain future recipients do not require past audience evidence");
        check(admitted("培训主题：展柜湿度登记；必须面向新入职的档案管理员",event("刚入职的档案管理员")),"internal de particle is preserved");
        for(String property:List.of("老年学员","行政人员","零基础学员")) {
            String query="培训主题：展柜湿度登记\n参训对象："+property;
            check(admitted(query,event(property)),"existing attribute reads same event audience "+property);
            check(admitted(query,event("青年专业人员")+"\n学员活动：学员讨论"+property),"future attribute does not require prior matching audience "+property);
            check(admitted(query,event("青年专业人员")+"\n\n"+event(property).replace("展柜湿度登记","设备维护")),"another course need not supply a non-hard future attribute "+property);
            check(admitted(query,event("非"+property)),"different historical recipient is not an exclusion on future adaptation "+property);
            String explicit=query+"\n课程必须适配"+property;
            check(!admitted(explicit,event("青年专业人员")+"\n学员活动：学员讨论"+property),"activity cannot supply explicitly required audience fit "+property);
            check(!admitted(explicit,event("青年专业人员")+"\n\n"+event(property).replace("展柜湿度登记","设备维护")),"different course cannot supply explicitly required fit "+property);
            check(!admitted(explicit,event("非"+property)),"negative audience attribute cannot satisfy explicit fit "+property);
        }
        check(!admitted("培训主题：展柜湿度登记\n必须包含现场角色演练",event("角色演练学员")),"genuine classroom activity stays an activity");
        for(String identity:List.of("同一批","原班","指定名单中的")) {
            String audience=identity+"档案管理员";
            check(!AudienceContract.compare(audience,audience).supported(),"type equality is not cohort identity "+identity);
            check(!admitted("培训主题：展柜湿度登记\n参训对象："+audience+"\n必须有本人实际授课记录",event(audience)),"structured identity unresolved "+identity);
            check(!admitted("培训主题：展柜湿度登记\n参训对象："+audience+"\n必须有本人实际授课记录","本人曾为"+audience+"主讲展柜湿度登记课程。"),"legacy exact phrase cannot certify cohort identity "+identity);
            check(!admitted("需要本人曾教过"+audience+"的《展柜湿度登记》课程。",event("档案管理员")),"explicit previously taught cohort stays unresolved "+identity);
        }
        System.out.println("Audience contract: "+count+" checks passed");
    }
}
