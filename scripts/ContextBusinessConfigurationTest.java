package com.training;

import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Consumer;
import static com.training.ContextSharedPlanningIntegrationTest.*;

/** Real pinned references; private configuration and fabricated candidates only. No HTTP/model. */
public final class ContextBusinessConfigurationTest {
    static int checks;static Path out;static final List<Object> cases=new ArrayList<>();
    static final String FILE=ContextSharedPlanningAdapter.CONFIG_FILE_PROPERTY,SHA=ContextSharedPlanningAdapter.CONFIG_SHA_PROPERTY;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static Map<String,Object> role(Path file,String fileSha,String anchorSha,Path source,String sourceSha,String reviewSha){return obj("library_file",file.toString(),"library_sha256",fileSha,"anchor_sha256",anchorSha,"source_fixture",source.toString(),"source_sha256",sourceSha,"root_review",source.resolveSibling("ROOT_SOURCE_REVIEW.md").toString(),"root_review_sha256",reviewSha);}
    static Path write(String name,Object value)throws Exception{Path p=out.resolve(name+".json");Files.writeString(p,Json.write(value)+"\n",StandardOpenOption.CREATE_NEW);return p;}
    static ContextSharedPlanningAdapter.ConfiguredRequest at(Path p,String hash,LocalDate day){System.setProperty(FILE,p==null?"":p.toString());System.setProperty(SHA,hash);return ContextSharedPlanningAdapter.configured(day);}
    static void pending(ContextSharedPlanningAdapter.ConfiguredRequest r,String name){
        check(r.requested()&&"pending".equals(r.summary().get("status"))&&!r.catalog().valid()&&r.catalog().pairCount()==0,name+" fails closed");
        check(!Json.write(r.summary()).contains("/Users/")&&!Json.write(r.summary()).contains(out.toString()),name+" no path leak");
        var t=context(1,"安徽","合肥",90,"讲师");var result=NearbySelection.selectWithContextCatalogs(List.of(t),pref("湖北","武汉"),3,r.catalog(),DAY);
        check(ids(result).isEmpty()&&l(result.get("air_candidates")).isEmpty()&&!fit(t).containsKey("context_planning_reference"),name+" no positive or air");cases.add(obj("case",name,"summary",r.summary()));
    }
    static void fault(Map<String,Object> config,String name,Consumer<Map<String,Object>> edit)throws Exception{var bad=copy(config);edit.accept(bad);Path p=write("FAULT-"+name,bad);pending(at(p,sha(p),DAY),name);}
    public static void main(String[] args)throws Exception{
        Path base=Path.of(args[0]).toAbsolutePath().normalize();out=Files.createDirectory(Path.of(args[1]).toAbsolutePath().normalize());
        Path os=base.resolve(".codex-tmp/yanxu-context-shared-sources.DoNWLx/source-fixtures.private.json"),ns=base.resolve(".codex-tmp/yanxu-context-baseline-source.L6ZzsV/source-fixture.private.json");
        Map<String,Object> old=role(base.resolve(".codex-tmp/yanxu-context-shared-adapter.hqpB43/actual-final/two-context-candidates.private.json"),ContextRequestPlanningTest.OLD_FILE_SHA,ContextRequestPlanningTest.OLD_ANCHOR_SHA,os,ContextSharedPlanningAdapterTest.SOURCE_SHA,ContextSharedPlanningAdapterTest.REVIEW_SHA);
        Map<String,Object> newer=role(base.resolve(".codex-tmp/yanxu-context-baseline-adapter.rxPGgR/actual-final/hefei-wuhan-baseline.private.json"),ContextRequestPlanningTest.NEW_FILE_SHA,ContextRequestPlanningTest.NEW_ANCHOR_SHA,ns,ContextBaselinePlanningTest.PACKET_SHA,ContextBaselinePlanningTest.ROOT_SHA);
        Map<String,Object> config=obj("version",ContextSharedPlanningAdapter.CONFIG_VERSION,"context_shared_v1",old,"current_baseline_v2",newer);
        Map<Path,String> kept=new LinkedHashMap<>();for(var r:List.of(old,newer)){for(String[] k:List.of(new String[]{"library_file","library_sha256"},new String[]{"source_fixture","source_sha256"},new String[]{"root_review","root_review_sha256"}))kept.put(Path.of(r.get(k[0]).toString()),r.get(k[1]).toString());kept.put(Path.of(r.get("library_file")+".integrity.json"),r.get("anchor_sha256").toString());}
        for(var p:kept.entrySet())check(p.getValue().equals(sha(p.getKey())),"existing input pin before");
        Path valid=write("business-context.private",config);String validSha=sha(valid),priorFile=System.getProperty(FILE),priorSha=System.getProperty(SHA);
        try{
            var off=at(null,"",DAY);check(!off.requested()&&off.catalog()==null&&off.summary().isEmpty(),"default OFF no capability or summary");
            var rows=List.of(candidate(1,"湖北","武汉",80,"讲师",true,obj("status","same_city"),null));check(CityPlanningReference.same(NearbySelection.select(List.of(copy(rows.get(0))),pref("湖北","武汉"),3),NearbySelection.selectWithContextCatalogs(List.of(copy(rows.get(0))),pref("湖北","武汉"),3,null,DAY)),"default exact legacy selection");
            pending(at(out.resolve("not-read-when-incomplete.json"),"",DAY),"file-only");pending(at(null,validSha,DAY),"sha-only");
            pending(at(valid,"0".repeat(64),DAY),"config-sha");pending(at(Path.of("relative.json"),validSha,DAY),"relative");
            Path enormous=out.resolve("oversized.json");Files.writeString(enormous,"x".repeat(16385),StandardOpenOption.CREATE_NEW);pending(at(enormous,sha(enormous),DAY),"size");
            Path link=out.resolve("symlink.json");Files.createSymbolicLink(link,valid);pending(at(link,validSha,DAY),"final-symlink");
            Path broken=out.resolve("invalid-json.json");Files.writeString(broken,"{",StandardOpenOption.CREATE_NEW);pending(at(broken,sha(broken),DAY),"json");
            for(String missing:List.of("version","context_shared_v1","current_baseline_v2"))fault(config,"missing-"+missing,d->d.remove(missing));
            fault(config,"version",d->d.put("version","context-request-config-v99"));fault(config,"extra",d->d.put("success",true));
            fault(config,"roles-swapped",d->{d.put("context_shared_v1",newer);d.put("current_baseline_v2",old);});
            for(String role:List.of("context_shared_v1","current_baseline_v2")){
                fault(config,role+"-field",d->m(d.get(role)).put("policy","forged"));
                for(String pin:List.of("library_sha256","anchor_sha256","source_sha256","root_review_sha256"))fault(config,role+"-"+pin,d->m(d.get(role)).put(pin,"0".repeat(64)));
            }
            var ready=at(valid,validSha,DAY);check(ready.requested()&&"ready".equals(ready.summary().get("status"))&&ready.catalog().valid()&&ready.catalog().pairCount()==3&&ready.catalog().independentDirectionCount()==0,"both actual roles ready");
            check(ready.summary().keySet().equals(Set.of("status","as_of","reason"))&&DAY.toString().equals(ready.summary().get("as_of")),"minimal sanitized date summary");
            for(String[] pair:List.of(new String[]{"河北","承德","北京","北京","reported_minutes"},new String[]{"辽宁","抚顺","辽宁","沈阳","approximate"},new String[]{"安徽","合肥","湖北","武汉","nominal_hour"})){
                var decision=ready.catalog().select(pair[0],pair[1],pair[2],pair[3],240);check(Boolean.TRUE.equals(decision.get("planning_pool_eligible")),"real pair ready");var ref=m(decision.get("reference"));check(pair[4].equals(m(ref.get("shared_observation")).get("precision"))&&!ref.containsKey("outbound")&&!ref.containsKey("inbound"),"original precision single observation");
            }
            var later=at(valid,validSha,LocalDate.of(2026,11,1));check("ready".equals(later.summary().get("status"))&&!Boolean.TRUE.equals(later.catalog().select("辽宁","抚顺","辽宁","沈阳",240).get("planning_pool_eligible")),"structural ready does not claim unexpired route");
            check(Boolean.TRUE.equals(later.catalog().select("安徽","合肥","湖北","武汉",240).get("planning_pool_eligible")),"unrelated current baseline retained");
            var expired=at(valid,validSha,LocalDate.of(2026,12,8));check(!Boolean.TRUE.equals(expired.catalog().select("安徽","合肥","湖北","武汉",240).get("planning_pool_eligible")),"fresh request rechecks review expiry");
            Path mutable=write("MUTABLE-config-control",config);String mutableSha=sha(mutable);check(at(mutable,mutableSha,DAY).catalog().valid(),"initial config bytes ready");Files.writeString(mutable,"{}\n");pending(at(mutable,mutableSha,DAY),"changed-config-no-cache");
            check(!at(null,"",DAY).requested(),"OFF after success does not retain configuration");
            System.setProperty(FILE,"\u0000");System.setProperty(SHA,validSha);pending(ContextSharedPlanningAdapter.configured(DAY),"malformed-path");
            for(var p:kept.entrySet())check(p.getValue().equals(sha(p.getKey())),"existing input pin after");
            var result=obj("status","PASS_private_configuration_only","checks",checks,"config_file",valid.toString(),"config_sha256",validSha,"cases",cases,"original_input_pins",kept.entrySet().stream().map(e->obj("path",e.getKey().toString(),"sha256",e.getValue())).toList(),"model_called",false,"network_called",false,"production_changed",false);
            Files.writeString(out.resolve("result.json"),Json.write(result)+"\n",StandardOpenOption.CREATE_NEW);System.out.println("Context business configuration: "+checks+" checks PASS");
        }finally{if(priorFile==null)System.clearProperty(FILE);else System.setProperty(FILE,priorFile);if(priorSha==null)System.clearProperty(SHA);else System.setProperty(SHA,priorSha);}
    }
}
