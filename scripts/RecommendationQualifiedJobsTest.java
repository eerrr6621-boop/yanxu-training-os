package com.training;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.net.*;
import com.sun.net.httpserver.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real Auth/H2/qualified jobs with deterministic barriers; no sockets or external services. */
public final class RecommendationQualifiedJobsTest {
    static int checks;static final String PASSWORD="a".repeat(48);static Auth.Session actor,second,other;
    interface Work{void run()throws Exception;}
    static synchronized void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static void denied(int code,Work work,String label)throws Exception{try{work.run();throw new AssertionError("Expected denial: "+label);}catch(Api.ApiException e){check(e.code==code,label+" actual="+e.code);}catch(RecommendationJobs.Failure e){check(e.code==code,label+" actual="+e.code);}}
    static RecommendationQualification.Capture capture()throws Exception{return RecommendationQualification.capture(actor,Map.of("standard_course",Map.of("scope_id",IntegrationRecommendationHttpFixture.scope,"expected_version",1,"course_code","001","as_of","2026-09-22","accepted_levels",List.of("讲师"),"allowed_cities",List.of("杭州"))));}
    static RecommendationJobs.Scored scored(RecommendationQualification.Capture c)throws Exception{return new RecommendationJobs.Scored(RecommendationQualification.filterBeforeScoring(actor,c,Db.query("SELECT id,teacher_level,base_city FROM teachers WHERE status='在库' ORDER BY id")),new LinkedHashMap<>(Map.of("recommendations",List.of(Map.of("teacher_id",1L)))));}
    static String id(Map<String,Object> value){return value.get("job_id").toString();}
    static Map<String,Object> waitFor(RecommendationJobs jobs,String id)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);Map<String,Object> value;do{value=jobs.get(actor,id);if(Boolean.TRUE.equals(value.get("terminal")))return value;Thread.sleep(5);}while(System.nanoTime()<end);throw new AssertionError("Job did not terminate");}
    static Object field(Object o,String key)throws Exception{Field f=o.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(o);}
    static Object job(RecommendationJobs jobs,String id)throws Exception{synchronized(jobs){return ((Map<?,?>)field(jobs,"jobs")).get(id);}}
    static String state(RecommendationJobs jobs,String id)throws Exception{synchronized(jobs){return field(job(jobs,id),"state").toString();}}
    static void barrier(CountDownLatch latch)throws Exception{if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("Barrier timeout");}
    static void uninterruptible(CountDownLatch latch){boolean interrupted=false;for(;;)try{latch.await();break;}catch(InterruptedException e){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();}
    static RecommendationJobs.Access access=current->{check(Thread.holdsLock(Api.MUTATION_LOCK),"Access recheck holds business lock");if(Auth.current(current)==null)throw new Api.ApiException(401,"Synthetic session expired");};
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Fresh empty directory required");IntegrationRecommendationHttpFixture.setup(Path.of(args[0]),PASSWORD);actor=IntegrationRecommendationHttpFixture.manager;other=IntegrationRecommendationHttpFixture.other;
        second=Auth.get(Auth.login("synthetic-recommendation-manager",PASSWORD));Db.exec("INSERT INTO teachers(id,name,teacher_level,base_city,status) VALUES(1,'SYNTHETIC','讲师','杭州','在库')");
        publishCatalog();try{statesAndOwnership();atomicCompletion();retentionAfterInvalidation();changedQualificationDuringScoring();lateWorkers();originalApi();System.out.println("RecommendationQualifiedJobs: "+checks+" checks passed (real Auth/H2, deterministic barriers, no network)");}finally{Db.exec("SHUTDOWN");}
    }
    static void publishCatalog()throws Exception{
        Map<String,Object> catalog=Map.of("schema_version","m04_catalog_v1","catalog_version","SYNTHETIC-JOBS-1","courses",List.of(Map.of("course_code","001","course_name","SYNTHETIC COURSE","active",true)),"teachers",List.of(Map.of("teacher_code","0001","teacher_level","讲师","city","杭州")),"certifications",List.of(Map.of("teacher_code","0001","course_code","001","status","certified","source_ref","SYNTHETIC-EVIDENCE","valid_from","2026-01-01","valid_to","2026-12-31")));
        Map<String,Object> preview=catalogCall("preview",Map.of("expected_version",0,"catalog",catalog,"bindings",List.of(Map.of("teacher_code","0001","teacher_id",1)),"change_comment","SYNTHETIC"));
        check(Boolean.TRUE.equals(preview.get("ready")),"Synthetic catalog is ready for explicit confirmation");catalogCall("confirm",Map.of("batch_id",preview.get("batch_id"),"expected_version",0,"confirm",true));
    }
    @SuppressWarnings("unchecked") static Map<String,Object> catalogCall(String operation,Map<String,Object> body)throws Exception{
        String token=Auth.login("synthetic-recommendation-manager",PASSWORD);Exchange ex=new Exchange("/api/course-catalog/scopes/"+IntegrationRecommendationHttpFixture.scope+"/"+operation,token,body);
        CourseCatalogIntegration.handle(ex,Auth.get(token));Object response=ex.getAttribute(Api.class.getName()+".response");return (Map<String,Object>)Json.parseMap(new String((byte[])field(response,"body"),StandardCharsets.UTF_8)).get("data");
    }
    static final class Exchange extends HttpExchange{
        final Headers request=new Headers(),response=new Headers();final Map<String,Object> attributes=new HashMap<>();final URI uri;
        Exchange(String path,String token,Map<String,Object> body){uri=URI.create(path);request.set("X-Token",token);request.set("Content-Type","application/json");attributes.put(Api.class.getName()+".body",body);}
        public Headers getRequestHeaders(){return request;}public Headers getResponseHeaders(){return response;}public URI getRequestURI(){return uri;}public String getRequestMethod(){return "POST";}public HttpContext getHttpContext(){return null;}public void close(){}public InputStream getRequestBody(){return InputStream.nullInputStream();}public OutputStream getResponseBody(){return OutputStream.nullOutputStream();}public void sendResponseHeaders(int c,long n){}public InetSocketAddress getRemoteAddress(){return new InetSocketAddress("127.0.0.1",0);}public int getResponseCode(){return -1;}public InetSocketAddress getLocalAddress(){return new InetSocketAddress("127.0.0.1",0);}public String getProtocol(){return "HTTP/1.1";}public Object getAttribute(String key){return attributes.get(key);}public void setAttribute(String key,Object value){attributes.put(key,value);}public void setStreams(InputStream i,OutputStream o){}public HttpPrincipal getPrincipal(){return null;}
    }
    static void changedQualificationDuringScoring()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(RecommendationJobs jobs=new RecommendationJobs(System::currentTimeMillis,10000,10000,2,1,false)){
            RecommendationQualification.Capture c=capture();String id=id(jobs.submit(actor,c,access,p->{RecommendationJobs.Scored value=scored(c);entered.countDown();barrier(release);return value;}));barrier(entered);
            synchronized(Api.MUTATION_LOCK){Db.exec("UPDATE teachers SET base_city='宁波' WHERE id=1");}release.countDown();
            for(int i=0;i<200&&!state(jobs,id).equals("failed");i++)Thread.sleep(5);
            check(state(jobs,id).equals("failed")&&field(job(jobs,id),"errorCode").equals(409)&&field(job(jobs,id),"qualifiedResult")==null,"Worker completion itself rejects changed qualification before saving any Result, without a GET to trigger invalidation");
            synchronized(Api.MUTATION_LOCK){Db.exec("UPDATE teachers SET base_city='杭州' WHERE id=1");}
            Map<String,Object> failed=jobs.get(actor,id);check(failed.get("error_code").equals(409)&&!failed.containsKey("result"),"Source restoration cannot resurrect a failed worker result");
        }finally{release.countDown();synchronized(Api.MUTATION_LOCK){Db.exec("UPDATE teachers SET base_city='杭州' WHERE id=1");}}
    }
    static void statesAndOwnership()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(RecommendationJobs jobs=new RecommendationJobs(System::currentTimeMillis,10000,20000,8,1,false)){
            RecommendationQualification.Capture c=capture();String running=id(jobs.submit(actor,c,access,progress->{entered.countDown();barrier(release);return scored(c);}));barrier(entered);
            String queued=id(jobs.submit(actor,c,access,progress->scored(c)));
            check(state(jobs,running).equals("running")&&state(jobs,queued).equals("queued"),"Actual running and queued states available");
            for(String id:List.of(running,queued)){
                denied(403,()->jobs.get(second,id),"Same uid new session cannot read queued/running");denied(403,()->jobs.cancel(second,id),"Same uid new session cannot cancel queued/running");denied(404,()->jobs.get(other,id),"Different account cannot read task");denied(403,()->jobs.get(actor.uid,id),"Legacy uid getter cannot extract a qualified task");denied(403,()->jobs.cancel(actor.uid,id),"Legacy uid cancellation cannot bypass original session");
            }
            check(state(jobs,running).equals("running")&&state(jobs,queued).equals("queued"),"Unauthorized cancellation never changes either task state");
            release.countDown();Map<String,Object> complete=waitFor(jobs,running);waitFor(jobs,queued);
            check(complete.get("status").equals("completed")&&complete.get("result") instanceof Map,"Typed completed result delivered through qualification");
            check(field(job(jobs,running),"result")==null&&field(job(jobs,running),"qualifiedResult") instanceof RecommendationQualification.Result,"Completed task stores opaque Result instead of JSON Map");
            denied(403,()->jobs.get(second,running),"Same uid new session cannot read completed result");denied(403,()->jobs.cancel(second,running),"Same uid new session cannot cancel completed task");
            String failed=id(jobs.submit(actor,c,access,progress->{throw new Api.ApiException(409,"SYNTHETIC conflict");}));Map<String,Object> failure=waitFor(jobs,failed);
            check(failure.get("status").equals("failed")&&failure.get("error_code").equals(409)&&failure.get("message").equals("SYNTHETIC conflict")&&!failure.containsKey("result"),"Worker ApiException code and reason are retained without result");
            denied(403,()->jobs.get(second,failed),"Same uid new session cannot read failed state");denied(403,()->jobs.cancel(second,failed),"Same uid new session cannot cancel failed state");
            // Version-only change leaves Auth alive, but all queued/running/completed tickets stale.
            CountDownLatch busy=new CountDownLatch(1),resume=new CountDownLatch(1);String live=id(jobs.submit(actor,c,access,progress->{busy.countDown();uninterruptible(resume);return scored(c);}));barrier(busy);
            String pending=id(jobs.submit(actor,c,access,progress->scored(c)));
            var old=OrganizationAccessStore.configuration();var next=new OrganizationAccess.Configuration("synthetic-recommendation-v2",old.codeRules(),old.roleCodes(),old.organizations(),old.people(),old.relations(),old.accountBindings(),old.grants());
            OrganizationAccessStore.publish(IntegrationRecommendationHttpFixture.admin,old.version(),next);
            check(Auth.current(actor)==actor,"Version-only publication retains the real login");
            for(String id:List.of(running,queued,failed,live,pending))denied(409,()->jobs.get(actor,id),"Every state rechecks its captured M01 context");
            check(field(job(jobs,running),"qualifiedResult")==null,"Detected invalidation erases cached typed Result");
            resume.countDown();
            Auth.revokeUserSessions(actor.uid);denied(401,()->jobs.get(actor,running),"Revoked original session gets 401");denied(401,()->jobs.cancel(actor,queued),"Revoked original session cannot cancel");
            actor=Auth.get(Auth.login("synthetic-recommendation-manager",PASSWORD));second=Auth.get(Auth.login("synthetic-recommendation-manager",PASSWORD));
        }finally{release.countDown();}
    }
    static void atomicCompletion()throws Exception{
        CountDownLatch scoring=new CountDownLatch(1),finishScoring=new CountDownLatch(1),checked=new CountDownLatch(1),finishCheck=new CountDownLatch(1),published=new CountDownLatch(1),mutationAcquired=new CountDownLatch(1);
        AtomicInteger visits=new AtomicInteger();AtomicBoolean finished=new AtomicBoolean();
        try(RecommendationJobs jobs=new RecommendationJobs(System::currentTimeMillis,10000,20000,2,1,false)){
            RecommendationQualification.Capture c=capture();RecommendationJobs.Access gate=current->{check(Thread.holdsLock(Api.MUTATION_LOCK),"Completion gate holds business lock");if(visits.incrementAndGet()==2){checked.countDown();barrier(finishCheck);}};
            String id=id(jobs.submit(actor,c,gate,progress->{RecommendationJobs.Scored value=scored(c);scoring.countDown();barrier(finishScoring);return value;}));barrier(scoring);finishScoring.countDown();barrier(checked);
            Thread mutation=new Thread(()->{synchronized(Api.MUTATION_LOCK){mutationAcquired.countDown();try{check(state(jobs,id).equals("completed"),"Business mutation cannot enter between completion recheck and completed save");check(field(job(jobs,id),"qualifiedResult") instanceof RecommendationQualification.Result,"Typed result is already stored when following mutation acquires lock");Db.exec("UPDATE teachers SET fee_rate=123 WHERE id=1");finished.set(true);}catch(Exception e){throw new RuntimeException(e);}finally{published.countDown();}}});mutation.start();
            synchronized(jobs){finishCheck.countDown();check(!mutationAcquired.await(150,TimeUnit.MILLISECONDS),"Completion keeps business lock while waiting for occupied job lock; following mutation cannot enter");check(field(job(jobs,id),"qualifiedResult")==null,"Job monitor deliberately blocks completed storage during atomicity probe");}barrier(published);mutation.join();check(finished.get(),"Completion publication ordering verified");waitFor(jobs,id);
        }finally{finishScoring.countDown();finishCheck.countDown();}
    }
    static void retentionAfterInvalidation()throws Exception{
        AtomicLong clock=new AtomicLong(1000);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(RecommendationJobs jobs=new RecommendationJobs(clock::get,10000,1000,4,1,false)){
            RecommendationQualification.Capture c=capture();String complete=id(jobs.submit(actor,c,access,p->scored(c)));waitFor(jobs,complete);
            String active=id(jobs.submit(actor,c,access,p->{entered.countDown();uninterruptible(release);return scored(c);}));barrier(entered);
            var old=OrganizationAccessStore.configuration();var next=new OrganizationAccess.Configuration("synthetic-recommendation-retention",old.codeRules(),old.roleCodes(),old.organizations(),old.people(),old.relations(),old.accountBindings(),old.grants());OrganizationAccessStore.publish(IntegrationRecommendationHttpFixture.admin,old.version(),next);
            clock.set(1040);denied(409,()->jobs.get(actor,complete),"Completed stale context refuses cached result");denied(409,()->jobs.get(actor,active),"Active stale context fails once");
            check(field(job(jobs,complete),"finished").equals(1000L),"Invalidating completed task preserves original terminal retention timestamp");check(field(job(jobs,active),"finished").equals(1040L),"Active first invalidation starts its terminal retention once");
            clock.set(1900);for(String id:List.of(complete,active))denied(409,()->jobs.get(actor,id),"Repeated stale read is rejected without refreshing TTL");
            check(field(job(jobs,complete),"finished").equals(1000L)&&field(job(jobs,active),"finished").equals(1040L),"Repeated invalid reads cannot indefinitely occupy bounded job capacity");
            clock.set(2001);jobs.sweep();denied(404,()->jobs.get(actor,complete),"Original completed retention deadline still releases its slot");
            clock.set(2041);jobs.sweep();denied(404,()->jobs.get(actor,active),"First active failure deadline releases its slot");release.countDown();
        }finally{release.countDown();}
    }
    static void lateWorkers()throws Exception{
        for(boolean timeout:List.of(false,true)){
            AtomicLong clock=new AtomicLong(1000);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),exited=new CountDownLatch(1);
            try(RecommendationJobs jobs=new RecommendationJobs(clock::get,100,1000,2,1,false)){
                RecommendationQualification.Capture c=capture();String id=id(jobs.submit(actor,c,access,progress->{RecommendationJobs.Scored value=scored(c);entered.countDown();uninterruptible(release);exited.countDown();return value;}));barrier(entered);
                if(timeout){clock.addAndGet(101);jobs.sweep();}else jobs.cancel(actor,id);
                release.countDown();barrier(exited);Thread.sleep(50);Map<String,Object> value=jobs.get(actor,id);
                check(value.get("status").equals(timeout?"timed_out":"cancelled")&&!value.containsKey("result")&&field(job(jobs,id),"qualifiedResult")==null,"Late worker cannot publish after "+(timeout?"timeout":"cancel"));
                clock.addAndGet(1001);jobs.sweep();denied(404,()->jobs.get(actor,id),"Expired terminal task releases its typed references");
            }finally{release.countDown();}
        }
    }
    static void originalApi()throws Exception{
        try(RecommendationJobs jobs=new RecommendationJobs(System::currentTimeMillis,10000,10000,2,1,false)){
            String id=id(jobs.submit(77,progress->Map.of("legacy",true)));Map<String,Object> value=null;for(int i=0;i<100;i++){value=jobs.get(77,id);if(Boolean.TRUE.equals(value.get("terminal")))break;Thread.sleep(5);}
            check(((Map<?,?>)value.get("result")).get("legacy").equals(true),"Original long-owner Map Work API remains usable by existing internal tests");denied(404,()->jobs.get(actor,id),"Typed HTTP entry cannot expose unqualified legacy jobs");
        }
    }
}
