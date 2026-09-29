package com.training;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static com.training.CuratedPlanningIntegrationTest.*;

/** Synthetic explicit-v2 context binding only; no actual sources or model/data reads. */
public final class CityPlanningPresentationV2Test {
    static int checks,sequence;static Path work;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static Map<String,Object> row(Map<String,Object> a,int n){return m(l(a.get("rows")).get(n));}
    static Map<String,Object> q(Path library,CityPlanningPresentation.Catalog view){return CityPlanningPresentationTest.q(CityPlanningReference.index(library,CuratedPlanningIntegrationTest.TODAY,view));}
    static Path library(String qualifier,String type)throws Exception{
        var out=CuratedCityDurationTest.source(scalar(false,"reported_minutes","24分钟",24),s->s.put("duration_qualifier","reported_fastest"));
        var back=CuratedCityDurationTest.source(scalar(true,"reported_minutes","20分钟",20),s->{s.put("duration_qualifier",qualifier);s.put("source_type",type);});
        Path file=work.resolve("synthetic-library-"+(++sequence)+".json");CityPlanningReference.prepare(bundle(out,back),file,CuratedPlanningIntegrationTest.TODAY);return file;
    }
    static Map<String,Object> annotations(Path file)throws Exception{
        Map<String,Object> a=CityPlanningPresentationTest.annotation(file,m(CityPlanningReference.load(file)),file);a.put("version",CityPlanningPresentation.VERSION_V2);row(a,1).put("source_context","institution_location");return a;
    }
    static Map<String,Object> shown(Path file,Map<String,Object> a,String version)throws Exception{return q(file,CityPlanningPresentation.load(CityPlanningPresentationTest.save(a,false),version));}
    static void rejected(Path file,Map<String,Object> a,Consumer<Map<String,Object>> edit,String label)throws Exception{
        Map<String,Object> bad=copy(a);edit.accept(bad);Map<String,Object> result=shown(file,bad,CityPlanningPresentation.VERSION_V2);
        check("pending".equals(m(result.get("presentation")).get("status")),label);check(CityPlanningReference.same(CityPlanningPresentationTest.noView(result),q(file,null)),label+" leaves all original output fields unchanged");
    }
    public static void main(String[] args)throws Exception{
        check(args.length<=1,"optional new output directory only");work=args.length==0?Files.createTempDirectory("city-presentation-v2-synthetic-"):Files.createDirectory(Path.of(args[0]).toAbsolutePath());CityPlanningPresentationTest.work=work;
        Path file=library("general_reported","institution_recruitment_campus_location_description_not_rail_operator");Map<String,Object> a=annotations(file),result=shown(file,a,CityPlanningPresentation.VERSION_V2),view=m(result.get("presentation"));
        check("ready".equals(view.get("status")),"explicit v2 supports exactly approved new source qualifiers");check("reported_fastest".equals(m(view.get("outbound")).get("source_qualifier")),"fastest forward retained");check("institution_location".equals(m(view.get("inbound")).get("source_context")),"institution context bound");check(!m(view.get("outbound")).containsKey("source_context"),"no invented forward institution context");
        check(CityPlanningReference.same(CityPlanningPresentationTest.noView(result),q(file,null)),"v2 no default-output mutation");
        var catalog=CityPlanningPresentation.load(CityPlanningPresentationTest.save(a,false),CityPlanningPresentation.VERSION_V2);var reverse=CityPlanningReference.index(file,CuratedPlanningIntegrationTest.TODAY,catalog).lookup("上海","上海","江苏","南京");check("institution_location".equals(m(m(reverse.get("presentation")).get("outbound")).get("source_context")),"context follows source direction on reverse query");
        for(int maximum:List.of(24,25,240,241))check(CityPlanningSelection.underConfiguredLimit(result,maximum)==CityPlanningSelection.underConfiguredLimit(q(file,null),maximum),"v2 never changes cutoff");
        rejected(file,a,x->row(x,1).remove("source_context"),"required institution context omitted");rejected(file,a,x->row(x,0).put("source_context","institution_location"),"context cannot move to non-institution source");
        for(Object context:Arrays.asList("__proto__","<img>","unknown",null))rejected(file,a,x->row(x,1).put("source_context",context),"unknown context fails closed");
        rejected(file,a,x->row(x,1).put("source_qualifier","reported_fastest"),"institution never promoted to fastest");rejected(file,a,x->row(x,1).put("direction","outbound"),"wrong direction closed");
        rejected(file,a,x->row(x,1).put("precision","approximate"),"cannot manufacture approximate precision");rejected(file,a,x->row(x,1).put("source_sha256","0".repeat(64)),"source hash binding remains");rejected(file,a,x->row(x,1).put("library_anchor_sha256","0".repeat(64)),"library anchor binding remains");
        Path wrongType=library("general_reported","railway_operator");Map<String,Object> wrong=shown(wrongType,annotations(wrongType),CityPlanningPresentation.VERSION_V2);check("pending".equals(m(wrong.get("presentation")).get("status")),"institution label must bind exact frozen source type");
        Path unknown=library("unknown","institution_recruitment_campus_location_description_not_rail_operator");check("pending".equals(m(shown(unknown,annotations(unknown),CityPlanningPresentation.VERSION_V2).get("presentation")).get("status")),"v2 does not generalize unknown source enums");
        Map<String,Object> v1=copy(a);v1.put("version",CityPlanningPresentation.VERSION);row(v1,1).remove("source_context");check("pending".equals(m(shown(file,v1,CityPlanningPresentation.VERSION).get("presentation")).get("status")),"old v1 continues rejecting new source enum");
        check("pending".equals(m(shown(file,a,CityPlanningPresentation.VERSION).get("presentation")).get("status")),"v1 opt-in cannot activate v2 sidecar");check(CityPlanningPresentation.load(null,null).reason().contains("unsupported"),"null version fails closed");
        Map<String,Object> stale=CityPlanningPresentationTest.q(CityPlanningReference.index(file,CuratedPlanningIntegrationTest.TODAY.plusDays(91),catalog));check(!stale.containsKey("presentation")&&Boolean.FALSE.equals(stale.get("air_fallback_trigger")),"expired source never gains view or airline fallback");
        Files.writeString(work.resolve("receipt.json"),Json.write(obj("checks",checks,"synthetic_only",true,"actual_sources_read",false,"model_called",false,"default_output_changed",false,"result",result))+"\n",StandardOpenOption.CREATE_NEW);
        System.out.println("City presentation v2 synthetic: "+checks+" assertions passed; "+work);
    }
}
