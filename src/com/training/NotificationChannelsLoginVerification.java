package com.training;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;
import java.util.function.LongSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Password-proved email challenges. First binding commits through its store; this engine never signs a session. */
public final class NotificationChannelsLoginVerification {
    private static final long MINUTE=60_000L,TEN_MINUTES=10*MINUTE,HOUR=60*MINUTE;
    private static final int MAX_CHALLENGES=1024,MAX_LIMITS=4096,MAX_GLOBAL_SENDS=1024;
    private final LongSupplier clock;
    private final SecureRandom random=new SecureRandom();
    private final byte[] secret=new byte[32];
    // All state is protected by the application's business lock, including pending deliveries.
    private final Map<String,Entry> challenges=new HashMap<>();
    private final Map<Long,String> accounts=new HashMap<>();
    private final Map<String,EnrollmentProof> enrollments=new HashMap<>();
    private final Map<Long,String> enrolledAccounts=new HashMap<>();
    private final Map<String,Window> limits=new HashMap<>();
    private final Deque<Long> globalSends=new ArrayDeque<>();
    private MailSender sender;
    private long transportGeneration,lastNow=-1;

    @FunctionalInterface public interface MailSender {
        void send(String email,String code) throws Exception;
        default void sendFirst(String email,String code,long remainingSeconds) throws Exception {send(email,code);}
    }
    public record Enrollment(String enrollmentId,long expiresAt) {
        @Override public String toString(){return "Enrollment[redacted]";}
    }
    /** Only these four non-secret values may be mapped to the browser's snake_case DTO. */
    public record Challenge(String challengeId,String maskedEmail,long expiresAt,long resendAfter) {}
    /** Internal handoff only. Auth must recheck both snapshots while holding the same lock. */
    record Verified(Auth.Credential credential,NotificationChannelsAccountEmailPreparation.Snapshot snapshot) {
        @Override public String toString() {return "Verified[redacted]";}
    }

    public NotificationChannelsLoginVerification(LongSupplier nowMillis,MailSender sender) {
        this.clock=Objects.requireNonNull(nowMillis);this.sender=sender;random.nextBytes(secret);
    }
    public NotificationChannelsLoginVerification(Clock clock,MailSender sender) {this(Objects.requireNonNull(clock)::millis,sender);}

    /** Trusted host/fixture only, never routed over HTTP. Quotas survive transport replacement. */
    void installTransport(MailSender replacement) {
        synchronized(Api.MUTATION_LOCK) {
            for(Entry entry:new ArrayList<>(challenges.values()))remove(entry);
            enrollments.clear();enrolledAccounts.clear();
            sender=replacement;transportGeneration++;
        }
    }

    /** Required-mode host calls before costly password checking; use direct socket address only. */
    public void preflight(String remoteAddress) throws Api.ApiException {
        synchronized(Api.MUTATION_LOCK) {
            String remote=remote(remoteAddress);long now=now();cleanup(now);
            String key="pre:"+remote;Window current=limits.get(key);
            if(current!=null&&current.attempts.size()>=30)quota();
            roomForLimits(key);record(key,TEN_MINUTES,now);
        }
    }

    public Challenge begin(Auth.Credential credential,String remoteAddress) throws Api.ApiException {
        requireUnlocked();Pending pending;
        synchronized(Api.MUTATION_LOCK) {
            String remote=remote(remoteAddress);long now=now();cleanup(now);
            requireCredential(credential);
            if(!FirstBindStore.allowsSession(credential.uid()))invalid();
            Entry previous=forAccount(credential.uid());
            NotificationChannelsAccountEmailPreparation.Snapshot candidate;
            try {candidate=NotificationChannelsAccountEmailPreparation.loginSnapshot(credential.uid());}
            catch(Api.ApiException e) {if(previous!=null)remove(previous);throw e;}
            if(previous!=null&&!sameSubject(previous,credential,candidate)) {remove(previous);previous=null;}
            pending=reserve(credential,candidate,null,candidate.email(),remote,previous,now);
        }
        return deliver(pending);
    }

    public Challenge resend(String challengeId,String remoteAddress) throws Api.ApiException {
        requireUnlocked();Pending pending;
        synchronized(Api.MUTATION_LOCK) {
            String remote=remote(remoteAddress);long now=now();cleanup(now);Entry old=lookup(challengeId);
            if(!old.ready||old.enrollment!=null)invalid();
            current(old);
            pending=reserve(old.credential,old.snapshot,null,old.email,remote,old,now);
        }
        return deliver(pending);
    }

    /** Caller may hold MUTATION_LOCK and immediately pass this to Auth.issueVerified under that lock. */
    Verified verify(String challengeId,String code) throws Api.ApiException {
        synchronized(Api.MUTATION_LOCK) {
            long now=now();cleanup(now);Entry entry=lookup(challengeId);
            if(!entry.ready||entry.enrollment!=null)invalid();
            current(entry);
            boolean wellFormed=code!=null&&code.matches("[0-9]{6}");
            boolean accepted=wellFormed&&MessageDigest.isEqual(entry.verifier,verifier(entry.id,code));
            if(!accepted) {
                entry.failures++;
                if(entry.failures>=5) {remove(entry);invalid();}
                throw new Api.ApiException(400,"验证码不正确，请重试或重新登录");
            }
            remove(entry);return new Verified(entry.credential,entry.snapshot);
        }
    }

    public void cancel(String challengeId) throws Api.ApiException {
        synchronized(Api.MUTATION_LOCK) {
            if(!validChallengeId(challengeId))throw new Api.ApiException(400,"核验请求格式无效");
            cleanup(now());Entry entry=challenges.get(challengeId);if(entry!=null){if(entry.enrollment!=null)invalid();remove(entry);}
        }
    }

    /** Password proof creates only a bounded capability, never an Auth.Session. */
    Enrollment enroll(Auth.Credential credential) throws Api.ApiException {
        synchronized(Api.MUTATION_LOCK) {
            LoginVerificationHost.requireEmailMode();long now=now();cleanup(now);requireCredential(credential);
            EnrollmentProof prior=forEnrollmentAccount(credential.uid());if(prior!=null)removeEnrollment(prior);
            FirstBindStore.Eligibility eligibility=FirstBindStore.ready(credential);
            if(eligibility==null)return null;
            Entry old=forAccount(credential.uid());if(old!=null)remove(old);
            if(enrollments.size()>=MAX_CHALLENGES)quota();
            String id;do{id=randomId();}while(enrollments.containsKey(id));
            EnrollmentProof proof=new EnrollmentProof(id,credential,eligibility,now+TEN_MINUTES,transportGeneration);
            enrollments.put(id,proof);enrolledAccounts.put(credential.uid(),id);
            return new Enrollment(id,proof.expiresAt);
        }
    }

    Challenge beginFirst(String enrollmentId,String email,String remoteAddress) throws Api.ApiException {
        requireUnlocked();Pending pending;
        synchronized(Api.MUTATION_LOCK) {
            long now=now();cleanup(now);EnrollmentProof proof=lookupEnrollment(enrollmentId);firstCurrent(proof);
            String candidate=NotificationChannelsAccountEmailPreparation.normalizeMaintenanceEmail(email);
            if(candidate==null)throw new Api.ApiException(400,"请填写可接收验证码的个人邮箱");
            pending=reserve(proof.credential,null,proof,candidate,remote(remoteAddress),forAccount(proof.credential.uid()),now);
        }
        return deliver(pending);
    }

    Challenge resendFirst(String challengeId,String remoteAddress) throws Api.ApiException {
        requireUnlocked();Pending pending;
        synchronized(Api.MUTATION_LOCK) {
            long now=now();cleanup(now);Entry old=lookup(challengeId);
            if(!old.ready||old.enrollment==null)invalid();current(old);
            pending=reserve(old.credential,null,old.enrollment,old.email,remote(remoteAddress),old,now);
        }
        return deliver(pending);
    }

    /** Correct proof is consumed before any SQL; a failed/uncertain commit never restores it. */
    Verified verifyFirst(String challengeId,String code) throws Api.ApiException {
        synchronized(Api.MUTATION_LOCK) {
            long now=now();cleanup(now);Entry entry=lookup(challengeId);
            if(!entry.ready||entry.enrollment==null)invalid();current(entry);
            boolean accepted=code!=null&&code.matches("[0-9]{6}")&&MessageDigest.isEqual(entry.verifier,verifier(entry.id,code));
            if(!accepted) {
                entry.enrollment.failures++;entry.failures=entry.enrollment.failures;
                if(entry.failures>=5){removeEnrollment(entry.enrollment);invalid();}
                throw new Api.ApiException(400,"验证码不正确，请重试或重新登录");
            }
            EnrollmentProof proof=entry.enrollment;String email=entry.email;removeEnrollment(proof);
            var snapshot=FirstBindStore.complete(proof.credential,proof.eligibility,email,UUID.randomUUID().toString());
            LoginVerificationHost.requireEmailMode();
            if(!Auth.credentialCurrent(proof.credential))invalid();
            return new Verified(proof.credential,snapshot);
        }
    }

    void cancelEnrollment(String id) throws Api.ApiException {
        synchronized(Api.MUTATION_LOCK) {
            if(!validChallengeId(id))throw new Api.ApiException(400,"核验请求格式无效");
            cleanup(now());EnrollmentProof proof=enrollments.get(id);if(proof!=null)removeEnrollment(proof);
        }
    }

    private Pending reserve(Auth.Credential credential,NotificationChannelsAccountEmailPreparation.Snapshot snapshot,
                            EnrollmentProof enrollment,String email,String remote,Entry previous,long now) throws Api.ApiException {
        if(sender==null){if(enrollment!=null)removeEnrollment(enrollment);throw unavailable();}
        String accountKey="account:"+credential.uid(),remoteKey="send:"+remote,
                recipientKey="recipient:"+HexFormat.of().formatHex(verifier("recipient",email.toLowerCase(Locale.ROOT)));
        Window account=limits.get(accountKey),ip=limits.get(remoteKey),recipient=limits.get(recipientKey);
        if(account!=null&&(account.attempts.size()>=5||now-account.attempts.peekLast()<MINUTE))quota();
        if(ip!=null&&ip.attempts.size()>=30||globalSends.size()>=MAX_GLOBAL_SENDS)quota();
        if(recipient!=null&&(recipient.attempts.size()>=5||now-recipient.attempts.peekLast()<MINUTE))quota();
        if(previous==null&&challenges.size()>=MAX_CHALLENGES)quota();
        roomForLimits(accountKey,remoteKey,recipientKey);
        String id;do{id=randomId();}while(challenges.containsKey(id));
        String code;
        do {code=String.format(Locale.ROOT,"%06d",random.nextInt(1_000_000));}
        while(previous!=null&&MessageDigest.isEqual(previous.verifier,verifier(previous.id,code)));
        byte[] proof=verifier(id,code);
        int failures=enrollment!=null?enrollment.failures:previous==null?0:previous.failures;
        if(previous!=null)remove(previous); // Never restore the previous challenge after a failed resend.
        record(accountKey,HOUR,now);record(remoteKey,HOUR,now);record(recipientKey,HOUR,now);globalSends.addLast(now);
        long expires=enrollment==null?now+TEN_MINUTES:enrollment.expiresAt;
        Entry entry=new Entry(id,credential,snapshot,enrollment,email,proof,
                expires,Math.min(now+MINUTE,expires),failures,transportGeneration);
        challenges.put(id,entry);accounts.put(credential.uid(),id);
        return new Pending(entry,sender,code,Math.max(1,(entry.expiresAt-now+999)/1000));
    }

    /** The only sender invocation is here, strictly outside MUTATION_LOCK; the host must not wrap it in a DB transaction. */
    private Challenge deliver(Pending pending) throws Api.ApiException {
        try {
            if(pending.entry.enrollment==null)pending.transport.send(pending.entry.email,pending.code);
            else pending.transport.sendFirst(pending.entry.email,pending.code,pending.remainingSeconds);
        }
        catch(Exception ignored) {
            synchronized(Api.MUTATION_LOCK) {
                if(pending.entry.enrollment==null)remove(pending.entry);else removeEnrollment(pending.entry.enrollment);
            }
            throw unavailable();
        } finally {pending.code=null;}
        synchronized(Api.MUTATION_LOCK) {
            long now=now();cleanup(now);Entry entry=pending.entry;
            if(challenges.get(entry.id)!=entry||entry.generation!=transportGeneration)invalid();
            current(entry);entry.ready=true;
            return new Challenge(entry.id,"***@***",entry.expiresAt,entry.resendAfter);
        }
    }

    private void current(Entry entry) throws Api.ApiException {
        if(entry.enrollment!=null){firstCurrent(entry.enrollment);return;}
        boolean valid;
        try {
            valid=Auth.credentialCurrent(entry.credential)&&FirstBindStore.allowsSession(entry.credential.uid())
                    &&entry.snapshot.equals(NotificationChannelsAccountEmailPreparation.loginSnapshot(entry.credential.uid()));
        } catch(Exception ignored) {valid=false;}
        if(!valid) {remove(entry);invalid();}
    }
    private static void requireCredential(Auth.Credential credential) throws Api.ApiException {
        boolean valid=false;try{valid=credential!=null&&Auth.credentialCurrent(credential);}catch(Exception ignored){}
        if(!valid)invalid();
    }
    private static boolean sameSubject(Entry previous,Auth.Credential credential,NotificationChannelsAccountEmailPreparation.Snapshot candidate) {
        return previous.enrollment==null&&previous.credential.equals(credential)&&Objects.equals(previous.snapshot,candidate);
    }
    private Entry lookup(String id) throws Api.ApiException {
        Entry entry=validChallengeId(id)?challenges.get(id):null;if(entry==null)invalid();return entry;
    }
    private Entry forAccount(long uid) {String id=accounts.get(uid);return id==null?null:challenges.get(id);}
    private void remove(Entry entry) {
        if(challenges.get(entry.id)!=entry)return;
        challenges.remove(entry.id);accounts.remove(entry.credential.uid(),entry.id);Arrays.fill(entry.verifier,(byte)0);
    }
    private EnrollmentProof lookupEnrollment(String id) throws Api.ApiException {
        EnrollmentProof proof=validChallengeId(id)?enrollments.get(id):null;if(proof==null)invalid();return proof;
    }
    private EnrollmentProof forEnrollmentAccount(long uid) {
        String id=enrolledAccounts.get(uid);return id==null?null:enrollments.get(id);
    }
    private void firstCurrent(EnrollmentProof proof) throws Api.ApiException {
        try {
            LoginVerificationHost.requireEmailMode();
            if(enrollments.get(proof.id)!=proof||proof.generation!=transportGeneration
                    ||!Auth.credentialCurrent(proof.credential)||!FirstBindStore.current(proof.credential,proof.eligibility)) {
                removeEnrollment(proof);invalid();
            }
        } catch(Api.ApiException failure){removeEnrollment(proof);throw failure;}
    }
    private void removeEnrollment(EnrollmentProof proof) {
        if(enrollments.get(proof.id)!=proof)return;
        enrollments.remove(proof.id);enrolledAccounts.remove(proof.credential.uid(),proof.id);
        Entry entry=forAccount(proof.credential.uid());if(entry!=null&&entry.enrollment==proof)remove(entry);
    }
    private void cleanup(long now) {
        for(EnrollmentProof proof:new ArrayList<>(enrollments.values()))if(now>=proof.expiresAt)removeEnrollment(proof);
        for(Entry entry:new ArrayList<>(challenges.values()))if(now>=entry.expiresAt)remove(entry);
        Iterator<Window> iterator=limits.values().iterator();
        while(iterator.hasNext()) {Window window=iterator.next();prune(window.attempts,now-window.duration);if(window.attempts.isEmpty())iterator.remove();}
        prune(globalSends,now-HOUR);
    }
    private static void prune(Deque<Long> values,long cutoff) {while(!values.isEmpty()&&values.peekFirst()<=cutoff)values.removeFirst();}
    private void record(String key,long duration,long now) {limits.computeIfAbsent(key,k->new Window(duration)).attempts.addLast(now);}
    private void roomForLimits(String... keys) throws Api.ApiException {
        int missing=0;for(String key:keys)if(!limits.containsKey(key))missing++;
        if(limits.size()+missing>MAX_LIMITS)quota();
    }
    private long now() throws Api.ApiException {
        long supplied;try{supplied=clock.getAsLong();}catch(Exception ignored){throw unavailable();}
        if(supplied<0||supplied>Long.MAX_VALUE-HOUR)throw unavailable();
        lastNow=Math.max(lastNow,supplied);return lastNow; // A backwards wall-clock adjustment cannot bypass quotas.
    }
    private String randomId() {byte[] bytes=new byte[32];random.nextBytes(bytes);return HexFormat.of().formatHex(bytes);}
    private byte[] verifier(String id,String code) throws Api.ApiException {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            return mac.doFinal((id+"\n"+code).getBytes(StandardCharsets.US_ASCII));
        } catch(Exception ignored) {throw unavailable();}
    }
    private static boolean validChallengeId(String id) {return id!=null&&id.matches("[0-9a-f]{64}");}
    private static String remote(String value) throws Api.ApiException {
        if(value==null||!value.matches("[A-Za-z0-9:._%\\-]{1,128}"))throw new Api.ApiException(400,"登录来源无法核实");return value;
    }
    private static void requireUnlocked() throws Api.ApiException {if(Thread.holdsLock(Api.MUTATION_LOCK))throw unavailable();}
    private static void invalid() throws Api.ApiException {throw new Api.ApiException(401,"登录核验已失效，请重新登录");}
    private static void quota() throws Api.ApiException {throw new Api.ApiException(429,"核验请求过于频繁，请稍后重试");}
    private static Api.ApiException unavailable() {return new Api.ApiException(503,"邮箱核验服务暂不可用，请稍后重试");}

    private static final class Window {
        final long duration;final Deque<Long> attempts=new ArrayDeque<>();Window(long duration){this.duration=duration;}
    }
    private static final class Entry {
        final String id;final Auth.Credential credential;final NotificationChannelsAccountEmailPreparation.Snapshot snapshot;
        final EnrollmentProof enrollment;final String email;
        final byte[] verifier;final long expiresAt,resendAfter,generation;int failures;boolean ready;
        Entry(String id,Auth.Credential credential,NotificationChannelsAccountEmailPreparation.Snapshot snapshot,
              EnrollmentProof enrollment,String email,byte[] verifier,long expiry,long resend,int failures,long generation) {
            this.id=id;this.credential=credential;this.snapshot=snapshot;this.enrollment=enrollment;this.email=email;this.verifier=verifier;this.expiresAt=expiry;this.resendAfter=resend;this.failures=failures;this.generation=generation;
        }
    }
    private static final class EnrollmentProof {
        final String id;final Auth.Credential credential;final FirstBindStore.Eligibility eligibility;
        final long expiresAt,generation;int failures;
        EnrollmentProof(String id,Auth.Credential credential,FirstBindStore.Eligibility eligibility,long expiresAt,long generation) {
            this.id=id;this.credential=credential;this.eligibility=eligibility;this.expiresAt=expiresAt;this.generation=generation;
        }
        @Override public String toString(){return "EnrollmentProof[redacted]";}
    }
    /** Method-local send work only; never placed in the persistent challenge/rate maps or returned. */
    private static final class Pending {
        final Entry entry;final MailSender transport;final long remainingSeconds;String code;
        Pending(Entry entry,MailSender transport,String code,long remainingSeconds){this.entry=entry;this.transport=transport;this.code=code;this.remainingSeconds=remainingSeconds;}
        @Override public String toString(){return "Pending[redacted]";}
    }
}
