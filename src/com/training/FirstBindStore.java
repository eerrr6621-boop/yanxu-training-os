package com.training;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/** Server-only first-email eligibility. It stores no email, password proof, OTP or session. */
final class FirstBindStore {
    private static final long MAX=9007199254740991L;
    private FirstBindStore() {}

    record Eligibility(long userId,long epoch,String binding) {
        @Override public String toString(){return "Eligibility[redacted]";}
    }
    record Gate(long userId,long epoch,String state,String binding,long emailRevision,String requestId,
                String completedAt,String receipt,String changedAt,long actor) {}

    static void init() throws SQLException {
        synchronized(Api.MUTATION_LOCK) {
            if(!Db.get().getAutoCommit())throw new IllegalStateException("首次邮箱绑定建表不能嵌入业务事务");
            Db.exec("CREATE TABLE IF NOT EXISTS s01_account_email_first_bind_receipts (request_id VARCHAR(36) PRIMARY KEY,approval_reference VARCHAR(200) NOT NULL,operation VARCHAR(16) NOT NULL CHECK(operation IN ('PREPARE','REVOKE')),manifest_digest CHAR(64) NOT NULL,approved_manifest CLOB NOT NULL,actor_user_id BIGINT NOT NULL REFERENCES users(id),recorded_at VARCHAR(40) NOT NULL)");
            Db.exec("CREATE TABLE IF NOT EXISTS s01_account_email_first_bind (user_id BIGINT PRIMARY KEY REFERENCES users(id),epoch BIGINT NOT NULL CHECK(epoch>0),state VARCHAR(16) NOT NULL CHECK(state IN ('READY','COMPLETED','REVOKED')),account_binding CHAR(64) NOT NULL,email_revision BIGINT,request_id VARCHAR(36),completed_at VARCHAR(40),authorization_receipt VARCHAR(36) NOT NULL REFERENCES s01_account_email_first_bind_receipts(request_id),changed_at VARCHAR(40) NOT NULL,actor_user_id BIGINT NOT NULL REFERENCES users(id),CHECK((state='COMPLETED' AND email_revision=1 AND request_id IS NOT NULL AND completed_at IS NOT NULL) OR (state<>'COMPLETED' AND email_revision IS NULL AND request_id IS NULL AND completed_at IS NULL)))");
            Db.exec("CREATE TABLE IF NOT EXISTS s01_account_email_first_bind_events (user_id BIGINT NOT NULL REFERENCES users(id),epoch BIGINT NOT NULL CHECK(epoch>0),state VARCHAR(16) NOT NULL CHECK(state IN ('READY','COMPLETED','REVOKED')),account_binding CHAR(64) NOT NULL,authorization_receipt VARCHAR(36) NOT NULL REFERENCES s01_account_email_first_bind_receipts(request_id),actor_user_id BIGINT NOT NULL REFERENCES users(id),recorded_at VARCHAR(40) NOT NULL,email_request_id VARCHAR(36),PRIMARY KEY(user_id,epoch,state))");
        }
    }

    /** Only absence or a valid completed gate delegates to normal registered-email login. */
    static Eligibility ready(Auth.Credential credential) throws Api.ApiException {
        synchronized(Api.MUTATION_LOCK) {
            try {
            LoginVerificationHost.requireEmailMode();
            if(!Auth.credentialCurrent(credential))throw stale();
            Gate gate=read(credential.uid());
            if(gate==null||gate.state.equals("COMPLETED"))return null;
            if(!gate.state.equals("READY"))throw stale();
            var identity=NotificationChannelsAccountEmailMaintenance.firstBindEmpty(credential.uid());
            if(!identity.canLogin()||!gate.binding.equals(identity.binding()))throw stale();
            return new Eligibility(credential.uid(),gate.epoch,gate.binding);
            }catch(Api.ApiException safe){throw safe;}
            catch(Exception unavailable){throw new Api.ApiException(503,"首次邮箱绑定状态暂时无法核对，请稍后重试");}
        }
    }

    static boolean current(Auth.Credential credential,Eligibility eligibility) throws Api.ApiException {
        synchronized(Api.MUTATION_LOCK) {
            try {
            LoginVerificationHost.requireEmailMode();
            if(credential==null||eligibility==null||credential.uid()!=eligibility.userId||!Auth.credentialCurrent(credential))return false;
            Gate gate=read(credential.uid());
            if(gate==null||!gate.state.equals("READY")||gate.epoch!=eligibility.epoch||!gate.binding.equals(eligibility.binding))return false;
            var identity=NotificationChannelsAccountEmailMaintenance.firstBindEmpty(credential.uid());
            return identity.canLogin()&&gate.binding.equals(identity.binding());
            }catch(Api.ApiException safe){throw safe;}
            catch(Exception unavailable){throw new Api.ApiException(503,"首次邮箱绑定状态暂时无法核对，请稍后重试");}
        }
    }

    /** Caller has consumed its OTP. No failure path may restore that OTP or issue a session. */
    static NotificationChannelsAccountEmailPreparation.Snapshot complete(Auth.Credential credential,
            Eligibility eligibility,String normalizedEmail,String requestId) throws Api.ApiException {
        synchronized(Api.MUTATION_LOCK) {
            try {
                LoginVerificationHost.requireEmailMode();
                if(!Db.get().getAutoCommit()||!validRequest(requestId)||!current(credential,eligibility))throw stale();
                var committed=Db.transaction(()->{
                    if(!current(credential,eligibility))throw stale();
                    var saved=NotificationChannelsAccountEmailMaintenance.appendFirstVerifiedEmail(credential,eligibility,normalizedEmail,requestId);
                    if(saved.revision()!=1||!Auth.credentialCurrent(credential))throw stale();
                    Gate gate=read(credential.uid());String now=Instant.now().toString();
                    try(PreparedStatement ps=Db.get().prepareStatement("UPDATE s01_account_email_first_bind SET state='COMPLETED',email_revision=1,request_id=?,completed_at=?,changed_at=?,actor_user_id=? WHERE user_id=? AND epoch=? AND state='READY' AND account_binding=?")) {
                        ps.setString(1,requestId);ps.setString(2,now);ps.setString(3,now);ps.setLong(4,credential.uid());ps.setLong(5,credential.uid());ps.setLong(6,eligibility.epoch);ps.setString(7,eligibility.binding);
                        if(ps.executeUpdate()!=1)throw stale();
                    }
                    appendEvent(credential.uid(),eligibility.epoch,"COMPLETED",eligibility.binding,gate.receipt,credential.uid(),now,requestId);
                    LoginVerificationHost.requireEmailMode();
                    if(!Auth.credentialCurrent(credential))throw stale();
                    return saved;
                });
                // A throw from commit or auto-commit restoration never enters this success path.
                LoginVerificationHost.requireEmailMode();
                Gate after=read(credential.uid());
                var reread=NotificationChannelsAccountEmailPreparation.loginSnapshot(credential.uid());
                if(after==null||!after.state.equals("COMPLETED")||after.epoch!=eligibility.epoch
                        ||!requestId.equals(after.requestId)||!Auth.credentialCurrent(credential)||!committed.equals(reread))throw stale();
                return reread;
            }catch(Api.ApiException failure){throw failure;}
            catch(Exception unavailable){throw new Api.ApiException(503,"首次邮箱绑定暂时无法完成，请重新登录");}
        }
    }

    /** Every ordinary issue/get path calls this, including accidental legacy/device flows. */
    static boolean allowsSession(long userId) throws Api.ApiException {
        synchronized(Api.MUTATION_LOCK) {
            try {
            Gate gate=read(userId);
            return gate==null||gate.state.equals("COMPLETED");
            }catch(Api.ApiException safe){throw safe;}
            catch(Exception unavailable){throw new Api.ApiException(503,"首次邮箱绑定状态暂时无法核对，请稍后重试");}
        }
    }

    static void guardAccountDeletion(long userId) throws Exception {
        if(Db.one("SELECT user_id FROM s01_account_email_first_bind WHERE user_id=?",userId)!=null
                ||Db.one("SELECT actor_user_id FROM s01_account_email_first_bind_receipts WHERE actor_user_id=? LIMIT 1",userId)!=null
                ||Db.one("SELECT user_id FROM s01_account_email_first_bind_events WHERE user_id=? OR actor_user_id=? LIMIT 1",userId,userId)!=null)
            throw new Api.ApiException(409,"该账号已有首次绑定记录，请停用并保留历史");
    }

    /** Also validates durable receipt/event linkage; a damaged row is never treated as absence. */
    static Gate read(long userId) throws Exception {
        if(!Thread.holdsLock(Api.MUTATION_LOCK)||userId<1||userId>MAX)throw stale();
        Map<String,Object> row=Db.one("SELECT user_id,epoch,state,account_binding,email_revision,request_id,completed_at,authorization_receipt,changed_at,actor_user_id FROM s01_account_email_first_bind WHERE user_id=?",userId);
        if(row==null) {
            if(Db.one("SELECT user_id,epoch,state,account_binding,authorization_receipt,actor_user_id,recorded_at,email_request_id FROM s01_account_email_first_bind_events WHERE user_id=? LIMIT 1",userId)!=null)throw stale();
            // Validate the third store too; receipt-only orphan protection must not silently disappear.
            for(Map<String,Object> receipt:Db.query("SELECT request_id,approval_reference,operation,manifest_digest,approved_manifest,actor_user_id,recorded_at FROM s01_account_email_first_bind_receipts"))
                if(FirstBindPreparation.receiptMentionsUser(receipt,userId))throw stale();
            return null;
        }
        long epoch=number(row,"epoch"),revision=number(row,"email_revision"),actor=number(row,"actor_user_id");
        String state=(String)row.get("state"),binding=(String)row.get("account_binding"),request=(String)row.get("request_id"),
                completed=(String)row.get("completed_at"),receipt=(String)row.get("authorization_receipt"),at=(String)row.get("changed_at");
        if(epoch<1||epoch>MAX||!Set.of("READY","COMPLETED","REVOKED").contains(state)
                ||binding==null||!binding.matches("[a-f0-9]{64}")||actor<1||!validRequest(receipt))throw stale();
        Instant.parse(at);
        Map<String,Object> authorization=Db.one("SELECT request_id,approval_reference,operation,manifest_digest,approved_manifest,actor_user_id,recorded_at FROM s01_account_email_first_bind_receipts WHERE request_id=?",receipt);
        if(authorization==null||!FirstBindPreparation.validStoredReceipt(authorization,userId,epoch,binding,state))throw stale();
        Map<String,Object> event=Db.one("SELECT user_id,epoch,state,account_binding,authorization_receipt,actor_user_id,recorded_at,email_request_id FROM s01_account_email_first_bind_events WHERE user_id=? AND epoch=? AND state=?",userId,epoch,state);
        if(event==null||!binding.equals(event.get("account_binding"))||!receipt.equals(event.get("authorization_receipt"))
                ||number(event,"actor_user_id")!=actor||!at.equals(event.get("recorded_at"))||!Objects.equals(request,event.get("email_request_id"))
                ||Db.one("SELECT user_id FROM s01_account_email_first_bind_events WHERE user_id=? AND epoch>? LIMIT 1",userId,epoch)!=null)throw stale();
        if(!state.equals("COMPLETED")&&(actor!=number(authorization,"actor_user_id")||!at.equals(authorization.get("recorded_at"))))throw stale();
        if(state.equals("COMPLETED")) {
            Map<String,Object> ready=Db.one("SELECT user_id,epoch,state,account_binding,authorization_receipt,actor_user_id,recorded_at,email_request_id FROM s01_account_email_first_bind_events WHERE user_id=? AND epoch=? AND state='READY'",userId,epoch);
            if(ready==null||!receipt.equals(ready.get("authorization_receipt"))||!binding.equals(ready.get("account_binding"))
                    ||number(ready,"actor_user_id")!=number(authorization,"actor_user_id")
                    ||!Objects.equals(ready.get("recorded_at"),authorization.get("recorded_at"))||ready.get("email_request_id")!=null)throw stale();
            if(revision!=1||!validRequest(request)||completed==null||!completed.equals(at)||actor!=userId)throw stale();
            Instant.parse(completed);
            Map<String,Object> audit=Db.one("SELECT * FROM s01_account_email_maintenance_requests WHERE request_id=?",request);
            if(audit==null||number(audit,"user_id")!=userId||number(audit,"actor_user_id")!=userId
                    ||number(audit,"expected_revision")!=0||number(audit,"result_revision")!=1||!binding.equals(audit.get("account_binding")))throw stale();
            // The original first revision remains auditable even after a later administrator change.
            var identity=NotificationChannelsAccountEmailMaintenance.firstBindIdentity(userId);
            if(!binding.equals(identity.binding()))throw stale();
        } else if(row.get("email_revision")!=null||request!=null||completed!=null)throw stale();
        return new Gate(userId,epoch,state,binding,revision,request,completed,receipt,at,actor);
    }

    static void appendEvent(long user,long epoch,String state,String binding,String receipt,long actor,String now,String request) throws Exception {
        if(!Thread.holdsLock(Api.MUTATION_LOCK)||Db.get().getAutoCommit())throw stale();
        Db.exec("INSERT INTO s01_account_email_first_bind_events(user_id,epoch,state,account_binding,authorization_receipt,actor_user_id,recorded_at,email_request_id) VALUES(?,?,?,?,?,?,?,?)",user,epoch,state,binding,receipt,actor,now,request);
    }
    static boolean validRequest(String value){return value!=null&&value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
    private static long number(Map<String,Object> row,String name){return row.get(name) instanceof Number value?value.longValue():-1;}
    private static Api.ApiException stale(){return new Api.ApiException(409,"首次邮箱绑定资格已变化，请重新登录或联系管理员");}
}
