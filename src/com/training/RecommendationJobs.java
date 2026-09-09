package com.training;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Bounded, owner-scoped in-memory jobs. No external calls or synthetic progress. */
final class RecommendationJobs implements AutoCloseable {
    interface Progress { void update(String phase, int completed, int total); }
    interface Work { Map<String,Object> run(Progress progress) throws Exception; }
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
        String state="queued",phase="queued",message=PHASES.get("queued"); int done,total,revision=1;
        long finished; Future<?> future; Map<String,Object> result;
        Job(long owner,long now){this.owner=owner;created=now;}
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
        sweep();
        if(jobs.values().stream().filter(j->j.owner==owner&&!j.terminal()).count()>=2)
            throw new Failure(429,"您已有推荐任务正在处理，请等待或取消后再试");
        if(jobs.size()>=capacity)throw new Failure(429,"推荐任务繁忙，请稍后重试");
        Job job=new Job(owner,clock.getAsLong()); jobs.put(job.id,job);
        try { job.future=executor.submit(()->execute(job,work)); }
        catch(RejectedExecutionException e){jobs.remove(job.id);throw new Failure(429,"推荐任务繁忙，请稍后重试");}
        return snapshot(job);
    }
    private void execute(Job job,Work work) {
        try {
            update(job,"loading",0,0);
            Map<String,Object> value=work.run((phase,done,total)->update(job,phase,done,total));
            if(value==null)throw new IllegalArgumentException();
            String encoded=Json.write(value);
            if(encoded.getBytes(StandardCharsets.UTF_8).length>2*1024*1024)throw new Failure(413,"推荐结果过大，请缩小需求范围后重试");
            Map<String,Object> copy=Json.parseMap(encoded);
            synchronized(this){expire(job);if(!job.terminal()){job.result=copy;finish(job,"completed","分析完成");}}
        } catch(CancellationException ignored) {
            synchronized(this){if(!job.terminal())finish(job,"cancelled","本次分析已取消");}
        } catch(Exception e) {
            synchronized(this){expire(job);if(!job.terminal())finish(job,"failed",e instanceof Failure?e.getMessage():"分析暂时未完成，请稍后重试");}
        }
    }
    private synchronized void update(Job job,String phase,int done,int total) {
        expire(job);
        if(job.terminal()||Thread.currentThread().isInterrupted())throw new CancellationException();
        if(!PHASES.containsKey(phase)||done<0||total<done||ORDER.indexOf(phase)<ORDER.indexOf(job.phase)
                ||phase.equals(job.phase)&&(done<job.done||total!=job.total))throw new IllegalArgumentException("无效的任务进度");
        job.state="running";job.phase=phase;job.done=done;job.total=total;job.message=PHASES.get(phase);job.revision++;
    }
    synchronized Map<String,Object> get(long owner,String id){sweep();return snapshot(owned(owner,id));}
    synchronized Map<String,Object> cancel(long owner,String id){
        sweep();Job job=owned(owner,id);
        if(!job.terminal()){finish(job,"cancelled","本次分析已取消");stop(job);}
        return snapshot(job);
    }
    private Job owned(long owner,String id){
        Job job=jobs.get(id);
        if(job==null||job.owner!=owner)throw new Failure(404,"推荐任务不存在或已过期，请重新提交");
        return job;
    }
    private void finish(Job job,String state,String message){job.state=state;job.message=message;job.finished=clock.getAsLong();job.revision++;}
    private void stop(Job job){if(job.future!=null){job.future.cancel(true);executor.remove((Runnable)job.future);}}
    private void expire(Job job){
        if(!job.terminal()&&clock.getAsLong()-job.created>=deadline){finish(job,"timed_out","分析超时，已停止本次任务，请稍后重试");stop(job);}
    }
    synchronized void sweep(){
        for(Iterator<Job> it=jobs.values().iterator();it.hasNext();){Job job=it.next();expire(job);
            if(job.terminal()&&clock.getAsLong()-job.finished>=retention)it.remove();}
    }
    private Map<String,Object> snapshot(Job job){
        Map<String,Object> value=new LinkedHashMap<>();
        value.put("job_id",job.id);value.put("status",job.state);value.put("phase",job.phase);value.put("message",job.message);
        value.put("completed_count",job.done);value.put("total_count",job.total);value.put("revision",job.revision);
        value.put("created_at",job.created);value.put("deadline_at",job.created+deadline);value.put("terminal",job.terminal());
        value.put("cloud_invoked",false);
        if(job.result!=null)value.put("result",Json.parse(Json.write(job.result)));
        return value;
    }
    @Override public synchronized void close(){
        for(Job job:jobs.values())if(!job.terminal()){finish(job,"cancelled","服务已停止");stop(job);}
        executor.shutdownNow();if(sweeper!=null)sweeper.shutdownNow();
    }
    private RecommendationJobs(){throw new AssertionError();}
}
