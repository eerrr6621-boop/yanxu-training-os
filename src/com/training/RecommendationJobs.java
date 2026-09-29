package com.training;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Bounded, owner-scoped in-memory jobs. No external calls or synthetic progress. */
final class RecommendationJobs implements AutoCloseable {
    interface Progress { void update(String phase, int completed, int total); }
    interface Work { Map<String,Object> run(Progress progress) throws Exception; }
    interface QualifiedWork { Scored run(Progress progress) throws Exception; }
    interface Access { void check(Auth.Session actor) throws Exception; }
    /** Server-only handoff from full-pool scoring to atomic qualified completion. */
    record Scored(RecommendationQualification.Pool pool,Map<String,Object> value) {}
    static final Progress SILENT = (phase,completed,total) -> {};
    static final Map<String,String> PHASES = Map.of(
        "queued", "已提交，等待处理", "loading", "正在读取需求与师资档案",
        "profiles", "正在整理讲师资料与就近条件", "local_semantic", "正在对照需求与简历原文",
        "evidence", "正在核对推荐依据", "ranking", "正在整理推荐名单");
    private static final List<String> ORDER = List.of("queued","loading","profiles","local_semantic","evidence","ranking");
    static final RecommendationJobs INSTANCE = new RecommendationJobs(System::currentTimeMillis, 60_000, 900_000, 32, 2, true);
    static final class Failure extends RuntimeException {
        final int code;
        Failure(int code, String message) { super(message); this.code=code; }
    }
    private static final class Job {
        final String id=UUID.randomUUID().toString(); final long owner, created;
        final Auth.Session session; final RecommendationQualification.Capture capture; final Access access;
        String state="queued",phase="queued",message=PHASES.get("queued"); int done,total,revision=1,errorCode;
        long finished; Future<?> future; Map<String,Object> result; RecommendationQualification.Result qualifiedResult;
        Job(long owner,long now){this(owner,now,null,null,null);}
        Job(long owner,long now,Auth.Session session,RecommendationQualification.Capture capture,Access access){
            this.owner=owner;created=now;this.session=session;this.capture=capture;this.access=access;
        }
        boolean terminal(){return !state.equals("queued")&&!state.equals("running");}
    }
    private final LinkedHashMap<String,Job> jobs=new LinkedHashMap<>();
    private final LongSupplier clock; private final long deadline,retention; private final int capacity;
    private final ThreadPoolExecutor executor; private final ScheduledExecutorService sweeper;
    RecommendationJobs(LongSupplier clock,long deadline,long retention,int capacity,int workers,boolean automaticSweep) {
        if(deadline<=0||retention<=0||capacity<1||workers<1)throw new IllegalArgumentException("任务配置无效");
        this.clock=clock;this.deadline=deadline;this.retention=retention;this.capacity=capacity;
        ThreadFactory factory=work->{Thread t=new Thread(work,"yanxu-recommendation-job");t.setDaemon(true);return t;};
        executor=new ThreadPoolExecutor(workers,workers,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(capacity),factory,new ThreadPoolExecutor.AbortPolicy());
        sweeper=automaticSweep?Executors.newSingleThreadScheduledExecutor(factory):null;
        if(sweeper!=null)sweeper.scheduleAtFixedRate(this::sweep,1,1,TimeUnit.SECONDS);
    }
    synchronized Map<String,Object> submit(long owner,Work work) {
        if(owner<=0||work==null)throw new IllegalArgumentException("任务所有者无效");
        capacity(owner);
        Job job=new Job(owner,clock.getAsLong()); jobs.put(job.id,job);
        schedule(job,()->execute(job,work));return snapshot(job);
    }
    Map<String,Object> submit(Auth.Session actor,RecommendationQualification.Capture capture,Access access,QualifiedWork work) throws Exception {
        if(actor==null||capture==null||access==null||work==null)throw new IllegalArgumentException("缺少服务端推荐上下文");
        synchronized(Api.MUTATION_LOCK) {
            RecommendationQualification.revalidate(actor,capture);access.check(actor);
            synchronized(this) {
                capacity(actor.uid);
                Job job=new Job(actor.uid,clock.getAsLong(),actor,capture,access);jobs.put(job.id,job);
                schedule(job,()->executeQualified(job,work));return snapshot(job);
            }
        }
    }
    private void capacity(long owner) {
        sweep();
        if(jobs.values().stream().filter(j->j.owner==owner&&!j.terminal()).count()>=2)
            throw new Failure(429,"您已有推荐任务正在处理，请等待或取消后再试");
        if(jobs.size()>=capacity)throw new Failure(429,"推荐任务繁忙，请稍后重试");
    }
    private void schedule(Job job,Runnable work) {
        try { job.future=executor.submit(work); }
        catch(RejectedExecutionException e){jobs.remove(job.id);throw new Failure(429,"推荐任务繁忙，请稍后重试");}
    }
    private static String bounded(Map<String,Object> value) {
        if(value==null)throw new IllegalArgumentException();String encoded=Json.write(value);
        if(encoded.getBytes(StandardCharsets.UTF_8).length>2*1024*1024)throw new Failure(413,"推荐结果过大，请缩小需求范围后重试");return encoded;
    }
    private void execute(Job job,Work work) {
        try {
            update(job,"loading",0,0);
            Map<String,Object> copy=Json.parseMap(bounded(work.run((phase,done,total)->update(job,phase,done,total))));
            synchronized(this){expire(job);if(!job.terminal()){job.result=copy;finish(job,"completed","分析完成");}}
        } catch(Exception e) { failed(job,e); }
    }
    private void executeQualified(Job job,QualifiedWork work) {
        try {
            update(job,"loading",0,0);
            Scored scored=work.run((phase,done,total)->update(job,phase,done,total));
            if(scored==null)throw new IllegalArgumentException("推荐评分未返回完整结果");
            // Complete AND publish under the same business boundary. Every qualified entry
            // acquires business -> job; memory-only sweep/update/close never take the business lock.
            synchronized(Api.MUTATION_LOCK) {
                job.access.check(job.session);
                RecommendationQualification.revalidate(job.session,job.capture);
                RecommendationQualification.Result result=RecommendationQualification.complete(job.session,scored.pool(),scored.value());
                if(!job.capture.cacheKey().equals(result.cacheKey()))throw new Failure(409,"评分资格上下文与原任务不一致，请重新分析");
                bounded(RecommendationQualification.deliver(job.session,result));
                synchronized(this){expire(job);if(!job.terminal()){job.qualifiedResult=result;finish(job,"completed","分析完成");}}
            }
        } catch(Exception e) { failed(job,e); }
    }
    private synchronized void failed(Job job,Exception e) {
        expire(job);if(job.terminal())return;
        job.result=null;job.qualifiedResult=null;
        if(e instanceof CancellationException){finish(job,"cancelled","本次分析已取消");return;}
        job.errorCode=e instanceof Failure f?f.code:e instanceof Api.ApiException a?a.code:500;
        finish(job,"failed",e instanceof Failure||e instanceof Api.ApiException?e.getMessage():"分析暂时未完成，请稍后重试");
    }
    private synchronized void update(Job job,String phase,int done,int total) {
        expire(job);
        if(job.terminal()||Thread.currentThread().isInterrupted())throw new CancellationException();
        if(!PHASES.containsKey(phase)||done<0||total<done||ORDER.indexOf(phase)<ORDER.indexOf(job.phase)
                ||phase.equals(job.phase)&&(done<job.done||total!=job.total))throw new IllegalArgumentException("无效的任务进度");
        job.state="running";job.phase=phase;job.done=done;job.total=total;job.message=PHASES.get(phase);job.revision++;
    }
    synchronized Map<String,Object> get(long owner,String id){sweep();return snapshot(legacyOwned(owner,id));}
    synchronized Map<String,Object> cancel(long owner,String id){
        sweep();Job job=legacyOwned(owner,id);cancel(job);return snapshot(job);
    }
    Map<String,Object> get(Auth.Session actor,String id) throws Exception {
        synchronized(Api.MUTATION_LOCK) {synchronized(this){sweep();Job job=qualifiedOwned(actor,id);validate(job,actor);return qualifiedSnapshot(job,actor);}}
    }
    Map<String,Object> cancel(Auth.Session actor,String id) throws Exception {
        synchronized(Api.MUTATION_LOCK) {synchronized(this){sweep();Job job=qualifiedOwned(actor,id);validate(job,actor);cancel(job);return qualifiedSnapshot(job,actor);}}
    }
    private void cancel(Job job){if(!job.terminal()){finish(job,"cancelled","本次分析已取消");stop(job);}}
    private void validate(Job job,Auth.Session actor) throws Exception {
        try {RecommendationQualification.revalidate(actor,job.capture);job.access.check(actor);}
        catch(Exception e){
            // A different login may not erase the original session's job. Invalidated
            // contexts seen by their original owner never keep a stale completed result.
            if(actor==job.session){job.result=null;job.qualifiedResult=null;job.errorCode=e instanceof Failure f?f.code:e instanceof Api.ApiException a?a.code:500;
                if(!job.terminal())finish(job,"failed",e.getMessage());
                else if(job.state.equals("completed")){job.state="failed";job.message=e.getMessage();job.revision++;}
                // Rejected polling must not renew the original terminal retention deadline.
                stop(job);}
            throw e;
        }
    }
    private Map<String,Object> qualifiedSnapshot(Job job,Auth.Session actor) throws Exception {
        Map<String,Object> value=snapshot(job);
        if(job.qualifiedResult!=null)value.put("result",RecommendationQualification.deliver(actor,job.qualifiedResult));return value;
    }
    private Job qualifiedOwned(Auth.Session actor,String id) throws Exception {
        if(Auth.current(actor)==null)throw new Api.ApiException(401,"原登录会话无效或已失效，请重新登录并分析");
        Job job=owned(actor.uid,id);if(job.capture==null)throw new Failure(404,"推荐任务不存在或已过期，请重新提交");return job;
    }
    private Job legacyOwned(long owner,String id){Job job=owned(owner,id);if(job.capture!=null)throw new Failure(403,"该推荐任务须使用原登录会话核验");return job;}
    private Job owned(long owner,String id){
        Job job=jobs.get(id);
        if(job==null||job.owner!=owner)throw new Failure(404,"推荐任务不存在或已过期，请重新提交");return job;
    }
    private void finish(Job job,String state,String message){job.state=state;job.message=message;job.finished=clock.getAsLong();job.revision++;}
    private void stop(Job job){if(job.future!=null){job.future.cancel(true);executor.remove((Runnable)job.future);}}
    private void expire(Job job){
        if(!job.terminal()&&clock.getAsLong()-job.created>=deadline){job.result=null;job.qualifiedResult=null;finish(job,"timed_out","分析超时，已停止本次任务，请稍后重试");stop(job);}
    }
    synchronized void sweep(){
        for(Iterator<Job> it=jobs.values().iterator();it.hasNext();){Job job=it.next();expire(job);
            if(job.terminal()&&clock.getAsLong()-job.finished>=retention){job.result=null;job.qualifiedResult=null;it.remove();}}
    }
    private Map<String,Object> snapshot(Job job){
        Map<String,Object> value=new LinkedHashMap<>();
        value.put("job_id",job.id);value.put("status",job.state);value.put("phase",job.phase);value.put("message",job.message);
        value.put("completed_count",job.done);value.put("total_count",job.total);value.put("revision",job.revision);
        value.put("created_at",job.created);value.put("deadline_at",job.created+deadline);value.put("terminal",job.terminal());
        value.put("cloud_invoked",false);if(job.errorCode!=0)value.put("error_code",job.errorCode);
        if(job.result!=null)value.put("result",Json.parse(Json.write(job.result)));return value;
    }
    @Override public synchronized void close(){
        for(Job job:jobs.values())if(!job.terminal()){finish(job,"cancelled","服务已停止");stop(job);}
        jobs.clear();executor.shutdownNow();if(sweeper!=null)sweeper.shutdownNow();
    }
    private RecommendationJobs(){throw new AssertionError();}
}
