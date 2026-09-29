package com.training;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Isolated core contract tests: real Auth, synthetic H2/imports, fake time, captured mail only. */
public final class S01LoginVerificationTest {
    private static final String PASSWORD = "synthetic-login-verification-only";
    private static final long MINUTE = 60_000, HOUR = 60 * MINUTE;
    private static final long FIRST = 100, LAST = 1130;
    private static int checks;
    private static Auth.Session admin;
    private static String passwordHash;
    private static final Map<Long,Auth.Credential> credentials = new HashMap<>();
    private static final List<String> ALL_TABLES = List.of("users", "organization_account_import_state", "organization_account_import_batches",
            "organization_account_import_people", "s01_account_email_heads", "s01_account_email_revisions", "s01_account_email_requests");
    @FunctionalInterface interface Work { void run() throws Exception; }
    static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    static Api.ApiException reject(int code, Work work, String label) throws Exception {
        try { work.run(); throw new AssertionError("Expected rejection: " + label); }
        catch (Api.ApiException failure) {
            check(failure.code == code, label + " expected=" + code + " actual=" + failure.code);
            String message = Objects.toString(failure.getMessage(), "");
            check(!message.contains("@example.test") && !message.contains(PASSWORD) && !message.contains("synthetic-ref-")
                    && !message.contains(passwordHash == null ? "never-private" : passwordHash), label + " has a private error message");
            return failure;
        }
    }
    static final class FakeClock extends Clock {
        final AtomicLong now = new AtomicLong(1_800_000_000_000L);
        void advance(long millis) { now.addAndGet(millis); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        @Override public long millis() { return now.get(); }
    }
    record Delivery(String email, String code) {}
    static class Mailbox implements NotificationChannelsLoginVerification.MailSender {
        final List<Delivery> sent = new CopyOnWriteArrayList<>();
        @Override public void send(String email, String code) throws Exception {
            check(!Thread.holdsLock(Api.MUTATION_LOCK), "mail transport runs outside global mutation lock");
            check(code.matches("[0-9]{6}"), "transport receives exactly six ASCII digits");
            sent.add(new Delivery(email, code));
        }
        Delivery last() { return sent.get(sent.size() - 1); }
    }
    static final class SlowMailbox extends Mailbox {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        @Override public void send(String email, String code) throws Exception {
            super.send(email, code); entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Slow-mail test was not released");
        }
        void awaitEntered() throws Exception { check(entered.await(5, TimeUnit.SECONDS), "slow mail enters transport"); }
    }
    static NotificationChannelsLoginVerification service(FakeClock time, Mailbox mailbox) {
        return new NotificationChannelsLoginVerification(time::millis, mailbox);
    }
    static String email(long id) { return "candidate-" + id + "@example.test"; }
    static String wrong(String code) { return code.equals("000000") ? "000001" : "000000"; }
    static Auth.Credential credential(long id) { return credentials.get(id); }
    static int sessionCount() throws Exception {
        Field field = Auth.class.getDeclaredField("SESSIONS"); field.setAccessible(true);
        return ((Map<?,?>)field.get(null)).size();
    }
    static Map<String,List<String>> storedRows() throws Exception {
        Map<String,List<String>> result = new TreeMap<>();
        for (String table : ALL_TABLES) {
            List<String> rows = new ArrayList<>();
            for (Map<String,Object> row : Db.query("SELECT * FROM " + table)) rows.add(Json.write(row));
            Collections.sort(rows); result.put(table, rows);
        }
        return result;
    }
    static void challenge(NotificationChannelsLoginVerification.Challenge value, FakeClock time) {
        check(value.challengeId().matches("[0-9a-f]{64}"), "challenge is a 256-bit hexadecimal identifier");
        check(value.maskedEmail().equals("***@***"), "client email mask reveals no local or domain characters");
        check(value.expiresAt() == time.millis() + 10 * MINUTE, "challenge expires after ten fake minutes");
        check(value.resendAfter() == time.millis() + MINUTE, "challenge cooldown is sixty fake seconds");
        check(Arrays.stream(value.getClass().getRecordComponents()).map(RecordComponent::getName).collect(java.util.stream.Collectors.toSet())
                .equals(Set.of("challengeId", "maskedEmail", "expiresAt", "resendAfter")), "public challenge exposes only four approved fields");
    }
    /** Only field inspection; no reflection output or invocation of implementation methods. */
    static List<String> liveIds(NotificationChannelsLoginVerification core) throws Exception {
        List<String> ids = new ArrayList<>();
        for (Field field : core.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true); Object value = field.get(core);
            if (value instanceof Map<?,?> map) for (Map.Entry<?,?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key && key.matches("[0-9a-f]{64}") && entry.getValue() != null
                        && entry.getValue().getClass().getName().startsWith(NotificationChannelsLoginVerification.class.getName() + "$")) ids.add(key);
            }
        }
        return ids;
    }
    static void noPlainCode(Object value, String code, Set<Object> visited) throws Exception {
        if (value == null || !visited.add(value)) return;
        if (value instanceof String text) { check(!text.equals(code), "core stores no plaintext verification code string"); return; }
        if (value instanceof byte[] bytes) { check(!Arrays.equals(bytes, code.getBytes(StandardCharsets.US_ASCII)), "core stores no plaintext verification code bytes"); return; }
        if (value instanceof char[] chars) { check(!Arrays.equals(chars, code.toCharArray()), "core stores no plaintext verification code chars"); return; }
        if (value instanceof Map<?,?> map) { for (Map.Entry<?,?> entry : map.entrySet()) { noPlainCode(entry.getKey(),code,visited); noPlainCode(entry.getValue(),code,visited); } return; }
        if (value instanceof Collection<?> values) { for (Object item : values) noPlainCode(item,code,visited); return; }
        if (!value.getClass().getName().startsWith(NotificationChannelsLoginVerification.class.getName())) return;
        for (Field field : value.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true); Object nested = field.get(value);
            if (nested instanceof NotificationChannelsLoginVerification.MailSender || nested instanceof Clock || nested instanceof java.util.function.LongSupplier) continue;
            noPlainCode(nested,code,visited);
        }
    }
    static void assertNoPlainCode(NotificationChannelsLoginVerification core, String code) throws Exception {
        noPlainCode(core, code, Collections.newSetFromMap(new IdentityHashMap<>()));
    }
    static void basic() throws Exception {
        FakeClock time = new FakeClock(); Mailbox mail = new Mailbox();
        NotificationChannelsLoginVerification core = service(time, mail);
        int sessions = sessionCount(); Map<String,List<String>> rows = storedRows();
        Auth.Credential proof = Auth.checkedCredential("synthetic-login-100", PASSWORD);
        check(proof != null && proof.equals(credential(100)), "real Auth produces the expected password proof");
        check(Auth.checkedCredential("synthetic-login-100", "wrong-password") == null, "real Auth rejects wrong password without proof");
        check(Auth.checkedCredential("nonexistent", PASSWORD) == null, "real Auth rejects missing user without proof");
        Db.exec("UPDATE users SET name=NULL WHERE id=1128");
        String nullableNameToken=Auth.login("synthetic-login-1128",PASSWORD);
        check(nullableNameToken!=null&&Auth.get(nullableNameToken)!=null,"legacy login remains compatible with nullable display name");
        Auth.logout(nullableNameToken);
        Db.exec("UPDATE users SET name='Synthetic user 1128' WHERE id=1128");
        var created = core.begin(proof, "basic-ip"); challenge(created,time);
        check(mail.last().email().equals(email(100)), "mail uses exact server-owned candidate");
        assertNoPlainCode(core,mail.last().code());
        check(sessionCount() == sessions, "begin grants no session");
        var verified = core.verify(created.challengeId(),mail.last().code());
        check(verified.credential().equals(proof), "verification returns same credential proof");
        check(verified.snapshot().equals(NotificationChannelsAccountEmailPreparation.loginSnapshot(100)), "verification returns current candidate snapshot");
        reject(401, () -> core.verify(created.challengeId(),mail.last().code()), "successful code is atomically consumed");
        check(sessionCount() == sessions, "verification core never signs a session");
        check(rows.equals(storedRows()), "core begin and verify leave all database rows unchanged");
        time.advance(MINUTE);
        var restarted = core.begin(proof,"basic-ip");
        NotificationChannelsLoginVerification fresh = new NotificationChannelsLoginVerification((Clock)time,mail);
        reject(401, () -> fresh.verify(restarted.challengeId(),mail.last().code()), "fresh service has no previous challenge");
        core.cancel(restarted.challengeId()); core.cancel(restarted.challengeId());
        core.cancel("a".repeat(64));
        reject(400, () -> core.cancel("invalid"), "cancel rejects malformed identifier");
        reject(401, () -> core.verify(restarted.challengeId(),mail.last().code()), "cancel invalidates challenge");
        synchronized (Api.MUTATION_LOCK) {
            reject(503, () -> core.begin(proof,"locked-ip"), "begin refuses caller-held global mutation lock");
            reject(503, () -> core.resend(restarted.challengeId(),"locked-ip"), "resend refuses caller-held global mutation lock");
        }
    }
    static void badCodesAndExpiry() throws Exception {
        FakeClock time = new FakeClock(); Mailbox mail = new Mailbox(); var core = service(time,mail);
        var value = core.begin(credential(101),"attempts-ip");
        String actual = mail.last().code();
        for (String malformed : Arrays.asList(null,"", "12345", "1234567"))
            reject(400, () -> core.verify(value.challengeId(),malformed), "malformed code consumes one of five attempts");
        reject(401, () -> core.verify(value.challengeId(),wrong(actual)), "fifth incorrect code exhausts challenge");
        reject(401, () -> core.verify(value.challengeId(),actual), "exhausted challenge rejects real code");
        time.advance(MINUTE);
        var expiring = core.begin(credential(101),"attempts-ip"); String expiryCode=mail.last().code();
        time.advance(10 * MINUTE - 1);
        check(core.verify(expiring.challengeId(),expiryCode) != null, "code works just before expiry");
        var boundary = core.begin(credential(102),"attempts-ip"); String boundaryCode=mail.last().code();
        time.advance(10 * MINUTE);
        reject(401, () -> core.verify(boundary.challengeId(),boundaryCode), "code expires exactly at deadline");
        core.cancel(boundary.challengeId());
        reject(401, () -> core.verify("bad-id", "123456"), "verify treats malformed identifier as an invalid challenge");
    }
    static void resendAndAccountQuota() throws Exception {
        FakeClock time=new FakeClock(); Mailbox mail=new Mailbox(); var core=service(time,mail);
        var original=core.begin(credential(103),"resend-ip"); String oldCode=mail.last().code();
        reject(429, () -> core.resend(original.challengeId(),"resend-ip"), "resend enforces cooldown");
        reject(429, () -> core.begin(credential(103),"other-ip"), "new begin cannot bypass per-account cooldown");
        reject(400, () -> core.verify(original.challengeId(),wrong(oldCode)), "first incorrect code");
        reject(400, () -> core.verify(original.challengeId(),wrong(oldCode)), "second incorrect code");
        time.advance(MINUTE-1);
        reject(429, () -> core.resend(original.challengeId(),"resend-ip"), "resend remains blocked one millisecond early");
        time.advance(1);
        var replacement=core.resend(original.challengeId(),"resend-ip"); challenge(replacement,time);
        check(!replacement.challengeId().equals(original.challengeId()), "resend rotates challenge identifier");
        check(!mail.last().code().equals(oldCode), "resend rotates numeric code"); String newCode=mail.last().code();
        reject(401, () -> core.verify(original.challengeId(),oldCode), "resend invalidates old id and code");
        reject(400, () -> core.verify(replacement.challengeId(),oldCode), "old numeric code cannot verify replacement");
        reject(400, () -> core.verify(replacement.challengeId(),wrong(newCode)), "resend preserves fourth cumulative failure");
        reject(401, () -> core.verify(replacement.challengeId(),wrong(newCode)), "resend preserves fifth cumulative failure");
        reject(401, () -> core.verify(replacement.challengeId(),newCode), "resend never resets failed attempts");
        FakeClock quotaTime=new FakeClock(); Mailbox quotaMail=new Mailbox();
        var accountCore=service(quotaTime,quotaMail);
        var current=accountCore.begin(credential(104),"account-ip");
        for(int i=1;i<5;i++) { quotaTime.advance(MINUTE); current=accountCore.resend(current.challengeId(),"account-ip"); }
        String currentId=current.challengeId();
        quotaTime.advance(MINUTE);
        reject(429, () -> accountCore.resend(currentId,"account-ip"), "sixth hourly account send denied");
        reject(429, () -> accountCore.begin(credential(104),"fresh-ip"), "fresh begin cannot evade hourly account send cap");
        check(quotaMail.sent.size()==5, "only five account mails are sent per hour");
        quotaTime.advance(HOUR-5*MINUTE);
        check(accountCore.begin(credential(104),"fresh-ip")!=null, "account window releases at exactly one hour");
        FakeClock replaceTime=new FakeClock(); Mailbox replaceMail=new Mailbox(); var replaceCore=service(replaceTime,replaceMail);
        var first=replaceCore.begin(credential(105),"replace-ip"); String firstCode=replaceMail.last().code();
        replaceTime.advance(MINUTE);
        var second=replaceCore.begin(credential(105),"replace-ip");
        reject(401, () -> replaceCore.verify(first.challengeId(),firstCode), "same-user begin supersedes old challenge");
        check(replaceCore.verify(second.challengeId(),replaceMail.last().code())!=null, "replacement challenge remains verifiable");
    }
    static void transportFailure() throws Exception {
        FakeClock time=new FakeClock(); Mailbox mail=new Mailbox();
        var core=new NotificationChannelsLoginVerification(time::millis,(NotificationChannelsLoginVerification.MailSender)null);
        reject(503, () -> core.begin(credential(106),"missing-transport-ip"), "missing transport fails closed");
        check(liveIds(core).isEmpty(), "missing transport leaves no challenge");
        core.installTransport(mail);
        // Installing a transport is startup wiring; use a distinct account for the successful retry.
        check(core.begin(credential(107),"installed-transport-ip")!=null, "package transport installer wires a sender");
        FakeClock failureTime=new FakeClock(); Mailbox fail=new Mailbox() {
            @Override public void send(String email,String code) throws Exception { super.send(email,code); throw new java.io.IOException("private transport detail " + email + " " + code); }
        };
        var failing=service(failureTime,fail);
        reject(503, () -> failing.begin(credential(108),"failing-ip"), "transport exception becomes private unavailable response");
        check(liveIds(failing).isEmpty(), "failed delivery leaves no live challenge");
        reject(429, () -> failing.begin(credential(108),"failing-ip"), "failed delivery still consumes cooldown");
        for(int i=1;i<5;i++) { failureTime.advance(MINUTE); reject(503, () -> failing.begin(credential(108),"failing-ip"), "failed delivery consumes hourly send slot"); }
        failureTime.advance(MINUTE);
        reject(429, () -> failing.begin(credential(108),"failing-ip"), "five failed deliveries consume account hourly cap");
        check(fail.sent.size()==5, "no transport call happens after hourly cap");
    }
    static void preflightQuotas() throws Exception {
        FakeClock time=new FakeClock(); var core=service(time,new Mailbox());
        for(int i=0;i<30;i++)core.preflight("password-ip");
        reject(429, () -> core.preflight("password-ip"), "preflight caps password attempts before validation");
        core.preflight("different-password-ip");
        time.advance(10*MINUTE-1);
        reject(429, () -> core.preflight("password-ip"), "password preflight remains blocked before ten minutes");
        time.advance(1);core.preflight("password-ip");
        FakeClock capacityTime=new FakeClock(); var bounded=service(capacityTime,new Mailbox());
        for(int i=0;i<4096;i++) bounded.preflight("bounded-password-ip-"+i);
        reject(429, () -> bounded.preflight("overflow-password-ip"), "at most 4096 limiter entries can be admitted");
        for(int i=1;i<30;i++)bounded.preflight("bounded-password-ip-0");
        reject(429, () -> bounded.preflight("bounded-password-ip-0"), "full limiter map retains oldest live counter");
        capacityTime.advance(10*MINUTE);
        bounded.preflight("overflow-password-ip");
        bounded.preflight("bounded-password-ip-0");
        check(true, "expired limiter slots are reclaimed without early eviction");
    }
    static void sendIpAndGlobalQuotas() throws Exception {
        FakeClock time=new FakeClock(); Mailbox mail=new Mailbox(); var core=service(time,mail);
        for(long id=FIRST;id<FIRST+30;id++)core.begin(credential(id),"send-ip");
        reject(429, () -> core.begin(credential(FIRST+30),"send-ip"), "send IP cap is thirty per hour");
        check(mail.sent.size()==30, "IP cap blocks transport invocation");
        core.begin(credential(FIRST+30),"other-send-ip");
        time.advance(HOUR-1);
        reject(429, () -> core.begin(credential(FIRST+31),"send-ip"), "send IP window persists for one hour");
        time.advance(1);core.begin(credential(FIRST+31),"send-ip");
        FakeClock fullTime=new FakeClock(); Mailbox fullMail=new Mailbox(); var full=service(fullTime,fullMail);
        NotificationChannelsLoginVerification.Challenge first=null,last=null; String firstCode=null,lastCode=null;
        for(int i=0;i<1024;i++) {
            var value=full.begin(credential(FIRST+i),"global-ip-"+(i/30));
            if(i==0){first=value;firstCode=fullMail.last().code();}
            last=value;lastCode=fullMail.last().code();
        }
        check(liveIds(full).size()==1024, "live challenge map holds at most 1024 admitted challenges");
        reject(429, () -> full.begin(credential(FIRST+1024),"global-overflow-ip"), "global send and challenge capacity fail closed at 1024");
        check(fullMail.sent.size()==1024, "global cap prevents additional delivery");
        check(full.verify(first.challengeId(),firstCode)!=null && full.verify(last.challengeId(),lastCode)!=null, "capacity overflow never evicts earliest or latest live challenge");
        fullTime.advance(10*MINUTE);
        reject(429, () -> full.begin(credential(FIRST+1024),"global-overflow-ip"), "expired challenges do not reset hourly global send count");
        fullTime.advance(HOUR-10*MINUTE);
        check(full.begin(credential(FIRST+1024),"global-overflow-ip")!=null, "global send window releases after one hour");
        check(liveIds(full).size()==1, "expired challenges are reclaimed on subsequent request");
    }
    static void rewriteCandidate(long id, String address) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            Db.exec("UPDATE users SET status=0,password=NULL WHERE id=?",id);
            long revision=((Number)Db.one("SELECT revision FROM s01_account_email_heads WHERE user_id=?",id).get("revision")).longValue();
            try { NotificationChannelsAccountEmailPreparation.save(admin,Map.of("user_id",id,"expected_revision",revision,"request_id",UUID.randomUUID().toString(),"email",address)); }
            finally { Db.exec("UPDATE users SET status=1,password=? WHERE id=?",passwordHash,id); }
        }
    }
    static void snapshotValidation() throws Exception {
        long id=1125;
        check(NotificationChannelsAccountEmailPreparation.loginSnapshot(id).email().equals(email(id)), "active imported account has current login candidate");
        reject(409, () -> NotificationChannelsAccountEmailPreparation.get(admin,id), "existing preparation GET still refuses enabled accounts");
        reject(409, () -> NotificationChannelsAccountEmailPreparation.save(admin,Map.of("user_id",id,"expected_revision",1L,"request_id",UUID.randomUUID().toString(),"email",email(id))), "existing preparation POST still refuses enabled accounts");
        for(String update:List.of("status=0", "password=NULL", "password=''")) {
            Db.exec("UPDATE users SET "+update+" WHERE id=?",id);
            reject(409, () -> NotificationChannelsAccountEmailPreparation.loginSnapshot(id), "login candidate requires enabled account and password");
            Db.exec("UPDATE users SET status=1,password=? WHERE id=?",passwordHash,id);
        }
        // Login-only candidate reads do not re-open the disabled/viewer/password-null preparation contract.
        Db.exec("UPDATE users SET role='manager' WHERE id=?",id);
        check(NotificationChannelsAccountEmailPreparation.loginSnapshot(id)!=null, "enabled imported role does not inherit preparation-only viewer restriction");
        Db.exec("UPDATE users SET role='viewer' WHERE id=?",id);
        var core=service(new FakeClock(),new Mailbox());
        reject(409, () -> core.begin(credential(1130),"missing-candidate-ip"), "new begin rejects imported account with missing candidate");
        reject(409, () -> NotificationChannelsAccountEmailPreparation.loginSnapshot(1), "unimported admin is outside login candidate scope");
        String binding=String.valueOf(Db.one("SELECT import_binding FROM s01_account_email_heads WHERE user_id=?",id).get("import_binding"));
        Db.exec("UPDATE s01_account_email_heads SET import_binding=? WHERE user_id=?","0".repeat(64),id);
        reject(409, () -> core.begin(credential(id),"broken-binding-ip"), "new begin rejects head binding hash mismatch");
        Db.exec("UPDATE s01_account_email_heads SET import_binding=? WHERE user_id=?",binding,id);
        Db.exec("UPDATE s01_account_email_heads SET email_key='corrupt-key' WHERE user_id=?",id);
        reject(409, () -> NotificationChannelsAccountEmailPreparation.loginSnapshot(id), "candidate rejects inconsistent normalized email key");
        Db.exec("UPDATE s01_account_email_heads SET email_key=? WHERE user_id=?",email(id),id);
        rewriteCandidate(id,"second-candidate@example.test");
        String firstAt=String.valueOf(Db.one("SELECT recorded_at FROM s01_account_email_revisions WHERE user_id=? AND revision=1",id).get("recorded_at"));
        Db.exec("UPDATE s01_account_email_revisions SET recorded_at='corrupt-history-time' WHERE user_id=? AND revision=1",id);
        reject(409, () -> NotificationChannelsAccountEmailPreparation.loginSnapshot(id), "candidate checks old history rows, not only current revision");
        Db.exec("UPDATE s01_account_email_revisions SET recorded_at=? WHERE user_id=? AND revision=1",firstAt,id);
        Db.exec("UPDATE s01_account_email_revisions SET email='corrupt-email' WHERE user_id=? AND revision=1",id);
        reject(409, () -> core.begin(credential(id),"broken-history-ip"), "new begin rejects corrupted historical email");
        Db.exec("UPDATE s01_account_email_revisions SET email=? WHERE user_id=? AND revision=1",email(id),id);
        rewriteCandidate(id,email(id));
        rewriteCandidate(1129,email(id).toUpperCase(Locale.ROOT));
        reject(409, () -> core.begin(credential(id),"duplicate-candidate-ip"), "new begin rejects case-insensitive candidate duplication");
        rewriteCandidate(1129,email(1129));
        check(NotificationChannelsAccountEmailPreparation.loginSnapshot(id)!=null, "restored candidate integrity becomes usable again");
    }
    static void finishRejected(Future<?> future, int code, String label) throws Exception {
        try { future.get(5,TimeUnit.SECONDS); throw new AssertionError("Expected asynchronous rejection: "+label); }
        catch(ExecutionException failure) {
            check(failure.getCause() instanceof Api.ApiException, label+" uses expected private API rejection");
            check(((Api.ApiException)failure.getCause()).code==code, label+" rejects with expected status");
        }
    }
    static void slowRace(long id, Work mutation, Work restore, String label) throws Exception {
        FakeClock time=new FakeClock(); SlowMailbox mail=new SlowMailbox(); var core=service(time,mail);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        Future<?> delivery=null;
        try {
            delivery=pool.submit(() -> core.begin(credential(id),"slow-race-ip"));
            mail.awaitEntered();
            String pending;
            synchronized(Api.MUTATION_LOCK) { List<String> ids=liveIds(core); check(ids.size()==1,"one pending delivery reserves one challenge"); pending=ids.get(0); }
            reject(401, () -> core.verify(pending,mail.last().code()), "pending delivery cannot be verified before send completion");
            Future<?> change=pool.submit(() -> { synchronized(Api.MUTATION_LOCK) { mutation.run(); } return null; });
            change.get(2,TimeUnit.SECONDS);
            check(true,label+" can finish while transport is blocked");
            mail.release.countDown();
            finishRejected(delivery,401,label+" blocks delivery from becoming ready");
            reject(401, () -> core.verify(pending,mail.last().code()),label+" leaves no verifiable code");
            check(liveIds(core).isEmpty(),label+" removes failed pending challenge");
        } finally {
            mail.release.countDown(); pool.shutdownNow();
            check(pool.awaitTermination(5,TimeUnit.SECONDS),"slow mail worker terminates");
            synchronized(Api.MUTATION_LOCK) { restore.run(); }
        }
    }
    static void concurrency() throws Exception {
        FakeClock time=new FakeClock(); SlowMailbox slow=new SlowMailbox(); var pendingCore=service(time,slow);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<NotificationChannelsLoginVerification.Challenge> sending=pool.submit(() -> pendingCore.begin(credential(110),"cancel-ip"));
            slow.awaitEntered();String id;
            synchronized(Api.MUTATION_LOCK){id=liveIds(pendingCore).get(0);}
            reject(401, () -> pendingCore.verify(id,slow.last().code()),"pending code is never ready");
            reject(429, () -> pendingCore.begin(credential(110),"parallel-begin-ip"),"simultaneous begin cannot bypass a pending account cooldown");
            pool.submit(() -> {pendingCore.cancel(id);return null;}).get(2,TimeUnit.SECONDS);
            slow.release.countDown(); finishRejected(sending,401,"cancel during delivery");
            check(liveIds(pendingCore).isEmpty(),"cancelled delivery never resurrects challenge");
        } finally {slow.release.countDown();pool.shutdownNow();check(pool.awaitTermination(5,TimeUnit.SECONDS),"cancel worker terminates");}
        String alternate=Auth.hash("synthetic-changed-password");
        slowRace(111, () -> Db.exec("UPDATE users SET password=? WHERE id=111",alternate), () -> Db.exec("UPDATE users SET password=? WHERE id=111",passwordHash),"password change during delivery");
        slowRace(112, () -> Db.exec("UPDATE users SET status=0 WHERE id=112"), () -> Db.exec("UPDATE users SET status=1 WHERE id=112"),"account disable during delivery");
        slowRace(113, () -> Db.exec("UPDATE users SET role='manager' WHERE id=113"), () -> Db.exec("UPDATE users SET role='viewer' WHERE id=113"),"role change during delivery");
        slowRace(114, () -> rewriteCandidate(114,"changed-during-send@example.test"), () -> rewriteCandidate(114,email(114)),"candidate change during delivery");
        slowRace(115, () -> rewriteCandidate(1129,email(115)), () -> rewriteCandidate(1129,email(1129)),"duplicate candidate appearing during delivery");
        slowRace(116, () -> Db.exec("UPDATE organization_account_import_people SET source_reference='changed-reference' WHERE account_id=116"),
                () -> Db.exec("UPDATE organization_account_import_people SET source_reference='synthetic-ref-116' WHERE account_id=116"),"import association change during delivery");
        FakeClock atomicTime=new FakeClock(); Mailbox atomicMail=new Mailbox(); var atomic=service(atomicTime,atomicMail);
        var challenge=atomic.begin(credential(117),"atomic-ip");String code=atomicMail.last().code();
        ExecutorService racers=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        Callable<Integer> verify=() -> {start.await();try{atomic.verify(challenge.challengeId(),code);return 200;}catch(Api.ApiException denied){return denied.code;}};
        try {
            Future<Integer> a=racers.submit(verify),b=racers.submit(verify);start.countDown();
            List<Integer> outcomes=new ArrayList<>(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS)));Collections.sort(outcomes);
            check(outcomes.equals(List.of(200,401)),"concurrent correct verification has exactly one winner");
        } finally {start.countDown();racers.shutdownNow();check(racers.awaitTermination(5,TimeUnit.SECONDS),"atomic verify workers terminate");}
        // A send which outlasts the reservation's ten-minute life cannot refresh its own expiry.
        FakeClock expiryTime=new FakeClock(); SlowMailbox expiryMail=new SlowMailbox(); var expiryCore=service(expiryTime,expiryMail);
        ExecutorService expiryWorker=Executors.newSingleThreadExecutor();
        try {
            Future<?> future=expiryWorker.submit(() -> expiryCore.begin(credential(118),"slow-expiry-ip"));expiryMail.awaitEntered();
            expiryTime.advance(10*MINUTE);expiryMail.release.countDown();finishRejected(future,401,"delivery finishing at expiry");
            check(liveIds(expiryCore).isEmpty(),"expired pending delivery is discarded");
        } finally {expiryMail.release.countDown();expiryWorker.shutdownNow();check(expiryWorker.awaitTermination(5,TimeUnit.SECONDS),"expiry worker terminates");}
        // A new password login after cooldown may replace a still-running delivery, without reviving it.
        FakeClock replacingTime=new FakeClock();SlowMailbox replacingMail=new SlowMailbox();var replacingCore=service(replacingTime,replacingMail);
        ExecutorService replacingWorker=Executors.newSingleThreadExecutor();
        try {
            Future<?> original=replacingWorker.submit(() -> replacingCore.begin(credential(119),"pending-replace-ip"));replacingMail.awaitEntered();
            String oldId; synchronized(Api.MUTATION_LOCK){oldId=liveIds(replacingCore).get(0);}
            replacingCore.installTransport(new Mailbox());
            reject(429, () -> replacingCore.begin(credential(119),"pending-replace-ip"),"sender replacement preserves pending account cooldown");
            replacingTime.advance(MINUTE);
            var replacement=replacingCore.begin(credential(119),"pending-replace-ip");
            replacingMail.release.countDown();finishRejected(original,401,"old delivery after transport replacement");
            check(!replacement.challengeId().equals(oldId)&&liveIds(replacingCore).equals(List.of(replacement.challengeId())),"stale delivery cannot delete replacement challenge");
        } finally {replacingMail.release.countDown();replacingWorker.shutdownNow();check(replacingWorker.awaitTermination(5,TimeUnit.SECONDS),"replacement worker terminates");}
    }
    static void readyStateChanges() throws Exception {
        FakeClock time=new FakeClock();Mailbox mail=new Mailbox();var core=service(time,mail);
        var first=core.begin(credential(120),"ready-change-ip");String code=mail.last().code();
        rewriteCandidate(120,"changed-after-send@example.test");
        reject(401, () -> core.verify(first.challengeId(),code),"candidate change invalidates already-ready challenge");
        rewriteCandidate(120,email(120));
        var second=core.begin(credential(121),"ready-change-ip");String secondCode=mail.last().code();
        Db.exec("UPDATE users SET name='changed-name' WHERE id=121");
        reject(401, () -> core.verify(second.challengeId(),secondCode),"credential display-name change invalidates ready challenge");
        Db.exec("UPDATE users SET name='Synthetic user 121' WHERE id=121");
        var third=core.begin(credential(122),"ready-change-ip");String thirdCode=mail.last().code();
        core.installTransport(new Mailbox());
        reject(401, () -> core.verify(third.challengeId(),thirdCode),"transport replacement clears outstanding challenge");
        reject(429, () -> core.begin(credential(122),"ready-change-ip"),"transport replacement preserves send quota");
        FakeClock rollbackTime=new FakeClock();var rollback=service(rollbackTime,new Mailbox());
        rollback.begin(credential(123),"rollback-ip");rollbackTime.advance(-HOUR);
        reject(429, () -> rollback.begin(credential(123),"rollback-ip"),"backwards clock cannot evade account cooldown");
        reject(400, () -> core.preflight("remote\nforged"),"preflight rejects malformed remote key");
        reject(401, () -> core.begin(null,"null-proof-ip"),"missing password proof fails closed");
        FakeClock revokeTime=new FakeClock();Mailbox revokeMail=new Mailbox();var revokeCore=service(revokeTime,revokeMail);
        Auth.Credential oldProof=Auth.checkedCredential("synthetic-login-124",PASSWORD);
        Map<String,Object> unchangedUser=Db.one("SELECT * FROM users WHERE id=124");
        var revoked=revokeCore.begin(oldProof,"revoked-proof-ip");String revokedCode=revokeMail.last().code();
        Auth.revokeUserSessions(124);
        check(unchangedUser.equals(Db.one("SELECT * FROM users WHERE id=124")),"revocation leaves credential database fields unchanged");
        check(!Auth.credentialCurrent(oldProof),"revocation invalidates old proof despite identical database fields");
        reject(401, () -> revokeCore.begin(oldProof,"revoked-proof-ip"),"revoked password proof cannot start another challenge");
        reject(401, () -> revokeCore.verify(revoked.challengeId(),revokedCode),"revocation invalidates already-ready challenge");
        Auth.Credential newProof=Auth.checkedCredential("synthetic-login-124",PASSWORD);
        check(newProof!=null&&newProof.generation()!=oldProof.generation()&&Auth.credentialCurrent(newProof),"new password check obtains current credential generation");
        credentials.put(124L,newProof);
        revokeTime.advance(MINUTE);
        var fresh=revokeCore.begin(newProof,"revoked-proof-ip");
        check(revokeCore.verify(fresh.challengeId(),revokeMail.last().code()).credential().equals(newProof),"fresh generation can verify after existing send cooldown");
    }
    static Path configureData(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("One fresh temporary synthetic data directory is required");
        Path supplied=Path.of(args[0]);
        if(!supplied.isAbsolute()||Files.isSymbolicLink(supplied))throw new IllegalArgumentException("Data directory must be absolute and not symbolic");
        Path data=supplied.toRealPath();Path parent=data.getParent();
        if(!data.getFileName().toString().equals("data")||parent==null||!parent.getFileName().toString().startsWith("yanxu-s01-login-verification.")
                ||!Files.isRegularFile(parent.resolve(".s01-login-verification-test-root")))throw new IllegalArgumentException("Data directory is not owned by this test invocation");
        try(var contents=Files.list(data)){if(contents.findAny().isPresent())throw new IllegalArgumentException("Synthetic data directory must be empty");}
        String suppliedProperty=System.getProperty("data.dir");
        if(suppliedProperty==null||!Path.of(suppliedProperty).toRealPath().equals(data))throw new IllegalArgumentException("JVM data.dir must already point at the same temporary directory");
        System.setProperty("data.dir",data.toString());
        return data;
    }
    static void setup() throws Exception {
        Db.exec("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64) UNIQUE,password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT)");
        // Current Auth/M01 also require the real first-bind and trusted-device schemas.
        FirstBindStore.init(); TrustedDevices.init();
        passwordHash=Auth.hash(PASSWORD);
        Db.exec("INSERT INTO users VALUES(1,'synthetic-login-admin',?,'Synthetic admin','admin',1)",passwordHash);
        OrganizationAccountImport.init();NotificationChannelsAccountEmailPreparation.init();
        String token=Auth.login("synthetic-login-admin",PASSWORD);admin=Auth.get(token);
        check(admin!=null&&Auth.isAdmin(admin),"setup uses a real authenticated admin session");
        for(long id=FIRST;id<=LAST;id++) {
            Db.exec("INSERT INTO users VALUES(?,?,NULL,?,'viewer',0)",id,"synthetic-login-"+id,"Synthetic user "+id);
            Db.exec("INSERT INTO organization_account_import_people(person_code,source_namespace,source_reference,account_id,batch_key,source_fingerprint,payload,disposition,created_by) VALUES(?,?,?,?,?,?,?,?,1)",
                    "person-"+id,"a".repeat(64),"synthetic-ref-"+id,id,"synthetic-login-batch","b".repeat(64),"{}","create_pending");
            if(id!=1130)NotificationChannelsAccountEmailPreparation.save(admin,Map.of("user_id",id,"expected_revision",0L,"request_id",UUID.randomUUID().toString(),"email",email(id)));
            Db.exec("UPDATE users SET status=1,password=? WHERE id=?",passwordHash,id);
            // Internal fixture records match users exactly; focused tests also exercise checkedCredential.
            credentials.put(id,new Auth.Credential(id,"synthetic-login-"+id,passwordHash,"Synthetic user "+id,"viewer"));
        }
    }
    public static void main(String[] args) throws Exception {
        Path data=configureData(args); // Must succeed before any Db/Auth/feature initialization or cleanup.
        boolean databaseOpened=false;
        try {
            String url=Db.get().getMetaData().getURL();databaseOpened=true;
            check(url.startsWith("jdbc:h2:"+data.resolve("training")),"database connection is confined to validated temporary directory");
            setup();basic();badCodesAndExpiry();resendAndAccountQuota();transportFailure();preflightQuotas();
            snapshotValidation();concurrency();readyStateChanges();sendIpAndGlobalQuotas();
            check(sessionCount()==1,"all core flows retain only the fixture's admin session");
            System.out.println("S01 login verification: "+checks+" checks passed (real Auth, synthetic H2, fake clock, captured transport; no network/private inputs).");
        } finally {
            if(databaseOpened)Db.exec("SHUTDOWN"); // Never invokes Db after an earlier path-validation failure.
        }
    }
}
