package com.training;

import java.time.*;
import java.util.*;

/** Entirely synthetic: no real travel library, private roster or network. */
public final class PublicCityBoundsTest {
    static int checks;
    static final LocalDate TODAY=LocalDate.of(2026,9,8);
    static Map<String,Object> m(Object... pairs) {Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
    static void check(boolean value,String name) {if(!value)throw new AssertionError(name);checks++;}
    static void badDuration(String value) {try{PublicCityBounds.duration(value);throw new AssertionError("accepted "+value);}catch(IllegalArgumentException expected){checks++;}}
    static void bad(Map<String,Object> row,String name) {try{PublicCityBounds.validateEvidence(row,TODAY);throw new AssertionError(name);}catch(IllegalArgumentException expected){checks++;}}
    static Map<String,Object> fixture(boolean reverse,String duration) {
        String from=reverse?"厦门":"福州",to=reverse?"福州":"厦门";
        String quote=from+"至"+to+"动车旅行时间"+duration;
        return m("business_import_allowed",true,"use_basis","independently_curated_public_facts","license_claimed",false,
            "duration_basis",PublicCityBounds.DURATION_BASIS,"scope",PublicCityBounds.SCOPE,
            "evidence_type","public_transport_duration_statement","source_access","public_page","publisher_kind","government",
            "mode","rail","service_state","operating","direction_basis","explicit_direction","city_mapping_checked",true,
            "transport_context_checked",true,"scope_note","虚构城市级测试，不是全站最快、门到门或当前真实行程。",
            "from_province","福建","from_city",from,"to_province","福建","to_city",to,"from_endpoint",from,"to_endpoint",to,
            "source_url","https://synthetic.invalid/city-duration/"+(reverse?"back":"out"),"source_provider","合成测试来源",
            "source_published_on","2026-08-20","research_on","2026-09-08","duration_text",duration,
            "language_policy",PublicCityBounds.LANGUAGE_POLICY,"original_content",quote+"。","assertion_start",0,"assertion_end",quote.length(),
            "synthetic_notice","所有时间均为合成，不是实际公共交通资料。");
    }
    static void quote(Map<String,Object> row,String value) {row.put("original_content",value+"。");row.put("assertion_start",0);row.put("assertion_end",value.length());}
    static boolean eligible(Map<String,Object> a,Map<String,Object> b) {return "rail".equals(PublicCityBounds.validatePair(a,b,TODAY,240).get("eligibility"));}
    public static void main(String[] args) {
        PublicCityBounds.Duration exact=PublicCityBounds.duration("最快1小时02分");
        check(exact.lowerMinutes==62&&exact.upperMinutes==62&&exact.lowerInclusive&&exact.upperInclusive,"62 exact");
        check(exact.strictlyUnder(240)&&!exact.strictlyUnder(62),"exact strict boundary");
        check(exact.toMap().get("reported_minutes").equals(62),"only exact carries reported minute");
        PublicCityBounds.Duration cap=PublicCityBounds.duration("控制在2小时35分钟以内");
        check(cap.kind.equals("upper_bound")&&cap.upperMinutes==155&&cap.upperInclusive,"controlled inclusive bound");
        check(cap.toMap().get("reported_minutes")==null&&cap.strictlyUnder(240),"cap not measured value");
        check(PublicCityBounds.duration("不足4小时").strictlyUnder(240),"open upper at limit passes");
        check(!PublicCityBounds.duration("4小时以内").strictlyUnder(240),"inclusive upper at limit pending");
        check(!PublicCityBounds.duration("不超过四小时").strictlyUnder(240),"Chinese inclusive boundary");
        check(PublicCityBounds.duration("三小时五十九分钟").lowerMinutes==239,"Chinese number");
        check(PublicCityBounds.duration("两小时").lowerMinutes==120,"liang hour");
        check(PublicCityBounds.duration("239分钟").strictlyUnder(240),"239 passes");
        check(!PublicCityBounds.duration("240分钟").strictlyUnder(240),"240 pending not rail exclusion");
        for(int h=0;h<=7;h++)for(int minute:new int[]{10,20,30,40,50}) {
            PublicCityBounds.Duration d=PublicCityBounds.duration(h+"小时"+minute+"多分钟");
            check(d.lowerMinutes==h*60+minute&&d.upperMinutes==(h+1)*60&&!d.lowerInclusive&&!d.upperInclusive,"conservative within-hour interval "+h+"/"+minute);
            check(d.strictlyUnder(240)==(h<4),"within-hour 4h eligibility "+h+"/"+minute);
            check(d.toMap().get("reported_minutes")==null&&Boolean.FALSE.equals(d.toMap().get("published_exact_upper")),"inferred cap never a reported minute");
        }
        PublicCityBounds.Duration more=PublicCityBounds.duration("3小时40多分钟");
        check(more.precision.equals("colloquial_within_hour_inference")&&!more.inferenceNotice.isBlank(),"explicit inference label");
        check(more.toMap().get("display").equals("3小时40多分钟"),"UI retains original wording");
        for(String value:List.of("约4小时","大约3小时40分钟","3小时左右","3小时40分钟左右","40多分钟","3个多小时"))
            check(!PublicCityBounds.duration(value).strictlyUnder(240),"no inferred upper for "+value);
        PublicCityBounds.Duration interval=PublicCityBounds.duration("超过220分钟但不足240分钟");
        check(interval.strictlyUnder(240)&&!interval.lowerInclusive&&!interval.upperInclusive,"explicit two-sided open range");
        check(interval.overlaps(PublicCityBounds.duration("239分钟"))&&!interval.overlaps(PublicCityBounds.duration("240分钟")),"open interval endpoint intersections");
        check(!interval.overlaps(PublicCityBounds.duration("220分钟")),"open lower intersection");
        check(PublicCityBounds.duration("不超过220分钟").overlaps(PublicCityBounds.duration("220分钟")),"inclusive endpoint overlap");
        for(String value:List.of("3小时0多分钟","3小时60多分钟","3小时41多分钟","3小时99分钟","48小时40多分钟","0分钟","NaN分钟","-1分钟","1.5小时","不足约4小时","40多分钟以内","累计3小时40多分钟","往返3小时40多分钟","不是3小时40多分钟","不到不到4小时","超过240分钟但不足220分钟","超过240分钟但不足240分钟","4小时。2小时"))badDuration(value);
        check(eligible(fixture(false,"62分钟"),fixture(true,"控制在2小时35分钟以内")),"separate directions exact and bound");
        check(eligible(fixture(false,"229分钟"),fixture(true,"3小时40多分钟")),"exact plus labeled colloquial interval");
        check(!eligible(fixture(false,"229分钟"),fixture(true,"约3小时40分钟")),"approx return pending");
        check(!eligible(fixture(false,"239分钟"),fixture(true,"4小时以内")),"inclusive return threshold pending");
        check(eligible(fixture(false,"239分钟"),fixture(true,"不足4小时")),"explicit open upper return");
        check(!eligible(fixture(false,"62分钟"),fixture(false,"62分钟")),"one direction not mirrored");
        check(!"rail".equals(PublicCityBounds.validatePair(fixture(false,"62分钟"),Map.of(),TODAY,240).get("eligibility")),"missing return");
        check(!"rail".equals(PublicCityBounds.validatePair(fixture(false,"62分钟"),fixture(true,"63分钟"),TODAY,60).get("eligibility")),"administrator smaller scope");
        check(!"rail".equals(PublicCityBounds.validatePair(fixture(false,"62分钟"),fixture(true,"63分钟"),TODAY,241).get("eligibility")),"cannot widen beyond rail limit");
        Map<String,Object> pending=PublicCityBounds.validatePair(fixture(false,"300分钟"),fixture(true,"301分钟"),TODAY,240);
        check(pending.get("eligibility").equals("unknown")&&Boolean.FALSE.equals(pending.get("rail_exclusion_complete")),"slow bound never global exclusion");
        check(!pending.containsKey("outbound_reference_minutes")&&!pending.containsKey("return_reference_minutes"),"no legacy exact fields");
        Map<String,Object> parallel=fixture(false,"3小时49分");
        quote(parallel,"国铁集团持续优化列车运行图，福州至南平、厦门最快分别1小时02分、3小时49分可达");
        check(PublicCityBounds.validateEvidence(parallel,TODAY).lowerMinutes==229,"positional explicit destinations");
        parallel.put("duration_text","1小时02分");bad(parallel,"wrong positional binding");
        parallel.put("duration_text","3小时49分");
        for(String value:List.of("福州至南平、厦门最快分别1小时02分可达","福州至厦门、厦门最快分别1小时02分、3小时49分可达","福州至南平、厦门最快1小时02分、3小时49分可达","福州、厦门形成三小时交通圈","福州至南平、厦门最快分别1小时02分、3小时49分、4小时可达")) {quote(parallel,value);bad(parallel,"ambiguous group "+value);}
        Map<String,Object> contrast=fixture(true,"3小时40多分钟");
        quote(contrast,"以前从厦门到福州，开汽车需要七到八小时，现在乘坐动车只需要3小时40多分钟");
        check(PublicCityBounds.validateEvidence(contrast,TODAY).strictlyUnder(240),"former car vs current train bound correctly");
        for(String value:List.of("以前从厦门到福州，乘坐动车需要七到八小时，现在开汽车只需要3小时40多分钟","以前从厦门到福州，开汽车需要七到八小时，未来乘坐动车只需要3小时40多分钟","以前从厦门到福州，开汽车需要七到八小时，现在乘坐动车往返只需要3小时40多分钟","以前从厦门到福州，开汽车需要七到八小时，现在乘坐动车累计只需要3小时40多分钟","以前从厦门到福州，开汽车需要七到八小时，现在乘坐动车不是只需要3小时40多分钟")) {quote(contrast,value);bad(contrast,"transport/time polarity "+value);}
        for(String word:List.of("规划","预计","计划","临时","暑运","春运","停运","不再","未能","累计","往返","生活圈")) {
            Map<String,Object> row=fixture(false,"62分钟");quote(row,"福州至厦门"+word+"动车用时62分钟");bad(row,"unsafe context "+word);
        }
        for(String field:List.of("status","review_status","source_status"))for(String state:List.of("revoked","withdrawn","rejected","superseded")) {
            Map<String,Object> row=fixture(false,"62分钟");row.put(field,state);bad(row,"negative source-state "+field+"/"+state);
        }
        for(String field:List.of("research_only","temporary_service","coverage_complete")) {Map<String,Object> row=fixture(false,"62分钟");row.put(field,true);bad(row,"explicit restriction "+field);}
        for(String field:List.of("business_import_allowed","city_mapping_checked","transport_context_checked")) {Map<String,Object> row=fixture(false,"62分钟");row.put(field,false);bad(row,"unchecked "+field);}
        for(String field:List.of("usage_authorized","authorization_ref")) {Map<String,Object> row=fixture(false,"62分钟");row.put(field,field.equals("usage_authorized")?true:"fake");bad(row,"no fabricated authorization "+field);}
        Map<String,Object> row=fixture(false,"62分钟");row.put("source_published_on","2025-09-07");bad(row,"old source not freshened by current research");
        row=fixture(false,"62分钟");row.put("source_published_on","2025-09-08");check(PublicCityBounds.validateEvidence(row,TODAY).strictlyUnder(240),"365 day inclusive policy");
        for(String field:List.of("source_published_on","research_on","effective_from")) {row=fixture(false,"62分钟");row.put(field,"2026-09-09");bad(row,"future "+field);}
        row=fixture(false,"62分钟");row.put("research_on","2026-08-19");bad(row,"research before publication");
        row=fixture(false,"62分钟");row.put("effective_from","2026-08-21");bad(row,"preoperation notice not current report");
        row=fixture(false,"62分钟");row.put("effective_until","2026-09-07");bad(row,"expired period");
        row=fixture(false,"62分钟");row.put("effective_from",null);check(PublicCityBounds.validateEvidence(row,TODAY).lowerMinutes==62,"unknown exact effective day not fabricated");
        for(String field:List.of("source_url","source_provider","scope_note")) {row=fixture(false,"62分钟");row.put(field,"");bad(row,"missing "+field);}
        row=fixture(false,"62分钟");row.put("source_url","https://account:secret@example.invalid/");bad(row,"source credentials rejected");
        row=fixture(false,"62分钟");row.put("publisher_kind","official_media");bad(row,"media needs operator attribution");
        row.put("attribution_basis","transport_operator_statement");row.put("transport_operator_attribution_checked",true);row.put("attribution_text","合成测试：车站客运员的铁路运行说明");
        check(PublicCityBounds.validateEvidence(row,TODAY).lowerMinutes==62,"official media attributed source");
        row=fixture(false,"62分钟");row.put("from_city","虚构未知城");bad(row,"unknown city");
        row=fixture(false,"62分钟");row.put("from_endpoint","福州南站");bad(row,"station scope cannot masquerade as city");
        for(String field:List.of("from_station","reference_minutes","minimum_minutes","minutes")) {row=fixture(false,"62分钟");row.put(field,field.equals("from_station")?"福州":62);bad(row,"legacy field "+field);}
        row=fixture(false,"3小时40多分钟");row.remove("language_policy");bad(row,"inference policy requires explicit selection");
        row=fixture(false,"62分钟");row.put("duration_bounds",PublicCityBounds.duration("61分钟").toMap());bad(row,"declared type tampering");
        row.put("duration_bounds",PublicCityBounds.duration("62分钟").toMap());check(PublicCityBounds.validateEvidence(row,TODAY).lowerMinutes==62,"declared bounds recomputed");
        row=fixture(false,"62分钟");row.put("original_content","预计福州至厦门62分钟。");row.put("assertion_start",2);row.put("assertion_end",13);bad(row,"cannot trim planned prefix");
        System.out.println("PublicCityBoundsTest: "+checks+" checks passed (synthetic only)");
    }
}
