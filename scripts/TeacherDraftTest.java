package com.training;

import java.lang.reflect.*;
import java.util.*;

/** Pure draft boundary checks; no DB, network, real data or inferred values. */
public final class TeacherDraftTest {
    static int passed;
    static void check(boolean ok,String message) {if(!ok) throw new AssertionError(message);passed++;}
    static Map<String,Object> row(String status,String province,String city) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("name","【灰度测试】匿名讲师");
        result.put("status",status);result.put("base_province",province);result.put("base_city",city);return result;
    }
    static boolean valid(Map<String,Object> row) {
        try {Api.validateTeacherResidence(row);return true;} catch(IllegalArgumentException expected) {return false;}
    }
    static void saveValidation(Map<String,Object> body,Map<String,Object> old) throws Exception {
        Method method=Api.class.getDeclaredMethod("validateSave",String.class,Map.class,long.class,Map.class);
        method.setAccessible(true);
        try {method.invoke(null,"teachers",body,old==null?0L:1L,old);}
        catch(InvocationTargetException error) {throw (Exception)error.getCause();}
    }
    public static void main(String[] args) throws Exception {
        Map<String,Object> blank=row("待完善","","");saveValidation(blank,null);
        check("待完善".equals(blank.get("status")),"Explicit draft retained");
        check(blank.get("fee_rate")==null,"Unknown fee remains null, never fabricated zero");
        check(blank.get("teacher_level")==null,"Unknown grade remains null");
        check("".equals(blank.get("base_city")),"Unknown city remains empty");
        check(valid(row("待完善","浙江","")),"Province-only draft allowed");
        check(valid(row("待完善","浙江","杭州")),"Known draft city can be saved without implicit activation");
        check(!valid(row("在库","","")),"Non-draft still requires city");
        check(!valid(row("在库","浙江","")),"Non-draft province alone insufficient");
        check(!valid(row("出库","浙江","")),"Re-entry cannot bypass residence completeness");
        for(String city:List.of("未知","待完善","<杭州>","1234","杭\n州"))
            check(!valid(row("待完善","浙江",city)),"Malformed or placeholder city rejected");
        check(!valid(row("待完善","江苏","杭州")),"Known province/city mismatch rejected");
        check(!valid(row("待完善","虚构省","")),"Invalid province is not accepted for draft");
        Map<String,Object> typed=row("待完善","浙江","");typed.put("base_city",123L);
        check(!valid(typed),"Draft does not bypass field type validation");
        Map<String,Object> old=row("在库","浙江","杭州");old.put("fee_rate",2500.0);old.put("teacher_level","高级讲师");
        Map<String,Object> partial=new LinkedHashMap<>();partial.put("intro","补充真实专业资料");saveValidation(partial,old);
        check("杭州".equals(partial.get("base_city")),"Partial update preserves known city");
        check(Double.valueOf(2500).equals(partial.get("fee_rate")),"Partial update preserves confirmed fee");
        check("高级讲师".equals(partial.get("teacher_level")),"Partial update preserves confirmed grade");
        Map<String,Object> downgrade=row("待完善","","");boolean rejected=false;
        try {saveValidation(downgrade,old);} catch(Exception expected) {rejected=true;}
        check(rejected,"In-library teacher cannot claim draft to clear real residence");
        Map<String,Object> draft=row("待完善","浙江","");Map<String,Object> promote=row("在库","浙江","杭州");saveValidation(promote,draft);
        check("待完善".equals(promote.get("status")),"Generic save does not silently activate draft");
        for(Object fee:List.of(-1,"invalid","NaN","Infinity")) {
            Map<String,Object> input=row("待完善","","");input.put("fee_rate",fee);rejected=false;
            try {saveValidation(input,null);} catch(Exception expected) {rejected=true;}
            check(rejected,"Invalid fee never coerced to zero");
        }
        System.out.println("Teacher draft: "+passed+" pure checks passed; no DB or upload performed");
    }
}
