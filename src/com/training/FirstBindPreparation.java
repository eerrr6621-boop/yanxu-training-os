package com.training;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.*;

/**
 * Package-only stopped-database preparation. No route, main, generated password or delivery side effect.
 * A reviewed caller supplies the exact independently approved manifest and administrator password.
 * Existing enabled accounts retain their password; only approved uninitialized imported accounts may
 * receive an already-created PBKDF2 hash and become enabled in the same transaction as READY.
 */
final class FirstBindPreparation {
    private static final long MAX=9007199254740991L;
    private FirstBindPreparation() {}

    record Account(long userId,String username,String name,String role,int status,String passwordFingerprint,
                   String accountKind,String binding,long expectedEpoch,String provisionedPasswordHash) {
        @Override public String toString(){return "Account[redacted]";}
    }
    record Receipt(String requestId,String approvalReference,String manifestDigest,List<Account> accounts) {
        Receipt {if(accounts!=null)accounts=List.copyOf(accounts);}
        @Override public String toString(){return "Receipt[redacted]";}
    }

    static List<FirstBindStore.Eligibility> prepare(String adminUsername,String adminPassword,Receipt receipt) throws Exception {
        return apply(adminUsername,adminPassword,receipt,false);
    }
    static void revoke(String adminUsername,String adminPassword,Receipt receipt) throws Exception {
        apply(adminUsername,adminPassword,receipt,true);
    }

    private static List<FirstBindStore.Eligibility> apply(String adminUsername,String adminPassword,Receipt receipt,boolean revoke) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            List<Long> affected=new ArrayList<>();boolean[] enteredCommit={false};
            try {
                requireOffline();LoginVerificationHost.requireEmailMode();
                if(!Db.get().getAutoCommit())throw rejected();
                validate(receipt);
                Map<String,Object> admin=admin(adminUsername,adminPassword);
                long actor=number(admin,"id");
                Map<String,Object> previous=Db.one("SELECT * FROM s01_account_email_first_bind_receipts WHERE request_id=?",receipt.requestId);
                if(previous!=null) {
                    if(number(previous,"actor_user_id")!=actor||!receipt.approvalReference.equals(previous.get("approval_reference"))
                            ||!(revoke?"REVOKE":"PREPARE").equals(previous.get("operation"))
                            ||!receipt.manifestDigest.equals(previous.get("manifest_digest"))
                            ||!manifest(receipt.accounts).equals(previous.get("approved_manifest")))throw rejected();
                    List<FirstBindStore.Eligibility> original=new ArrayList<>();
                    for(Account account:receipt.accounts) {
                        FirstBindStore.Gate current=FirstBindStore.read(account.userId);
                        if(current==null||current.epoch()<account.expectedEpoch+1)throw rejected();
                        original.add(new FirstBindStore.Eligibility(account.userId,account.expectedEpoch+1,account.binding));
                    }
                    // Receipt replay cannot re-enable, rewrite a credential, resurrect READY, or revoke a new session.
                    return List.copyOf(original);
                }
                var result=Db.transaction(()->{
                    requireOffline();LoginVerificationHost.requireEmailMode();
                    if(!admin.equals(admin(adminUsername,adminPassword)))throw rejected();
                    // Validate the entire approved set before writing a single target.
                    for(Account account:receipt.accounts)check(account,revoke);
                    String now=Instant.now().toString(),operation=revoke?"REVOKE":"PREPARE";
                    Db.exec("INSERT INTO s01_account_email_first_bind_receipts(request_id,approval_reference,operation,manifest_digest,approved_manifest,actor_user_id,recorded_at) VALUES(?,?,?,?,?,?,?)",
                            receipt.requestId,receipt.approvalReference,operation,receipt.manifestDigest,manifest(receipt.accounts),actor,now);
                    List<FirstBindStore.Eligibility> prepared=new ArrayList<>();
                    for(Account account:receipt.accounts) {
                        long next=account.expectedEpoch+1;
                        if(!revoke&&account.status==0) {
                            try(PreparedStatement ps=Db.get().prepareStatement("UPDATE users SET password=?,status=1 WHERE id=? AND username=? AND role=? AND status=0 AND password IS NULL")) {
                                ps.setString(1,account.provisionedPasswordHash);ps.setLong(2,account.userId);ps.setString(3,account.username);ps.setString(4,account.role);
                                if(ps.executeUpdate()!=1)throw rejected();
                            }
                        }
                        String state=revoke?"REVOKED":"READY";
                        if(account.expectedEpoch==0) {
                            Db.exec("INSERT INTO s01_account_email_first_bind(user_id,epoch,state,account_binding,authorization_receipt,changed_at,actor_user_id) VALUES(?,?,?,?,?,?,?)",
                                    account.userId,next,state,account.binding,receipt.requestId,now,actor);
                        } else {
                            try(PreparedStatement ps=Db.get().prepareStatement("UPDATE s01_account_email_first_bind SET epoch=?,state=?,account_binding=?,email_revision=NULL,request_id=NULL,completed_at=NULL,authorization_receipt=?,changed_at=?,actor_user_id=? WHERE user_id=? AND epoch=?")) {
                                ps.setLong(1,next);ps.setString(2,state);ps.setString(3,account.binding);ps.setString(4,receipt.requestId);ps.setString(5,now);ps.setLong(6,actor);ps.setLong(7,account.userId);ps.setLong(8,account.expectedEpoch);
                                if(ps.executeUpdate()!=1)throw rejected();
                            }
                        }
                        FirstBindStore.appendEvent(account.userId,next,state,account.binding,receipt.requestId,actor,now,null);
                        // Preparation invalidates any old durable device without expanding account authority.
                        if(Db.one("SELECT user_id FROM s01_trusted_device_epochs WHERE user_id=?",account.userId)!=null
                                ||Db.one("SELECT user_id FROM s01_trusted_devices WHERE user_id=? LIMIT 1",account.userId)!=null)
                            TrustedDevices.revokeUserInTransaction(account.userId);
                        affected.add(account.userId);
                        prepared.add(new FirstBindStore.Eligibility(account.userId,next,account.binding));
                    }
                    requireOffline();LoginVerificationHost.requireEmailMode();
                    if(!admin.equals(admin(adminUsername,adminPassword)))throw rejected();
                    for(Account account:receipt.accounts) {
                        Map<String,Object> after=Db.one("SELECT * FROM users WHERE id=?",account.userId);
                        if(!account.username.equals(after.get("username"))||!Objects.equals(account.name,after.get("name"))
                                ||!account.role.equals(after.get("role"))||number(after,"status")!=(revoke?account.status:1)
                                ||!passwordFingerprint((String)after.get("password")).equals(account.provisionedPasswordHash==null?account.passwordFingerprint:passwordFingerprint(account.provisionedPasswordHash)))throw rejected();
                        FirstBindStore.Gate gate=FirstBindStore.read(account.userId);
                        if(gate==null||gate.epoch()!=account.expectedEpoch+1||!gate.state().equals(revoke?"REVOKED":"READY"))throw rejected();
                    }
                    enteredCommit[0]=true;return List.copyOf(prepared);
                });
                for(long user:affected)Auth.revokeUserSessions(user);
                return result;
            }catch(Exception failure) {
                // Commit may have succeeded before an auto-commit restoration failure. Never leave cached access.
                if(enteredCommit[0])for(long user:affected){TrustedDevices.blockUser(user);Auth.revokeUserSessions(user);}
                if(failure instanceof Api.ApiException safe)throw safe;
                throw new Api.ApiException(503,"首次绑定准备未能确认完成，请停机核对准备回执");
            }
        }
    }

    private static void check(Account expected,boolean revoke) throws Exception {
        Map<String,Object> row=Db.one("SELECT * FROM users WHERE id=?",expected.userId);
        if(row==null||!expected.username.equals(row.get("username"))||!Objects.equals(expected.name,row.get("name"))
                ||!expected.role.equals(row.get("role"))||number(row,"status")!=expected.status
                ||!expected.passwordFingerprint.equals(passwordFingerprint((String)row.get("password"))))throw rejected();
        var identity=revoke?NotificationChannelsAccountEmailMaintenance.firstBindIdentity(expected.userId)
                :NotificationChannelsAccountEmailMaintenance.firstBindEmpty(expected.userId);
        if(!expected.accountKind.equals(identity.kind())||!expected.binding.equals(identity.binding()))throw rejected();
        FirstBindStore.Gate gate=FirstBindStore.read(expected.userId);
        if((gate==null?0:gate.epoch())!=expected.expectedEpoch||expected.expectedEpoch>=MAX)throw rejected();
        if(revoke) {
            if(gate==null||expected.provisionedPasswordHash!=null)throw rejected();
        } else if(expected.status==1) {
            if(expected.provisionedPasswordHash!=null||!Auth.validStoredPassword((String)row.get("password"))
                    ||(gate!=null&&gate.state().equals("COMPLETED")))throw rejected();
        } else if(expected.status==0) {
            if(!expected.accountKind.equals("IMPORTED")||row.get("password")!=null
                    ||expected.provisionedPasswordHash==null||!expected.provisionedPasswordHash.startsWith("pbkdf2$")
                    ||!Auth.validStoredPassword(expected.provisionedPasswordHash))throw rejected();
        } else throw rejected();
    }

    private static Map<String,Object> admin(String username,String password) throws Exception {
        Map<String,Object> row=Db.one("SELECT id,username,password,name,role,status FROM users WHERE username=?",username);
        if(row==null||number(row,"status")!=1||!"admin".equals(row.get("role"))
                ||!Auth.verify(password,(String)row.get("password")))throw new Api.ApiException(401,"管理员身份验证失败");
        return row;
    }

    private static void requireOffline() throws Exception {
        String directory=System.getProperty("data.dir");
        if(!"true".equals(System.getProperty("s01.first-bind.offline"))||directory==null||directory.isBlank()
                ||!Files.isRegularFile(Path.of(directory).resolve("training.mv.db")))throw rejected();
        // H2's embedded file lock excludes another application process; do not enable AUTO_SERVER.
        String url=Db.get().getMetaData().getURL().toUpperCase(Locale.ROOT);
        if(!url.startsWith("JDBC:H2:")||url.contains("AUTO_SERVER")||url.contains("TCP:")||url.contains("MEM:"))throw rejected();
        if(number(Db.one("SELECT COUNT(*) AS total FROM INFORMATION_SCHEMA.SESSIONS"),"total")!=1)throw rejected();
        for(Thread thread:Thread.getAllStackTraces().keySet())
            if(thread.isAlive()&&thread.getName().equals("HTTP-Dispatcher"))throw rejected();
    }

    static String passwordFingerprint(String stored) throws Exception {return digest(stored==null?"password:null":"password:"+stored);}
    static String manifestDigest(List<Account> accounts) throws Exception {return digest(manifest(accounts));}
    private static String manifest(List<Account> accounts) throws Exception {
        List<Map<String,Object>> rows=new ArrayList<>();
        for(Account account:accounts) {
            Map<String,Object> row=new TreeMap<>();row.put("user_id",account.userId);row.put("username",account.username);row.put("name",account.name);
            row.put("role",account.role);row.put("status",account.status);row.put("password_fingerprint",account.passwordFingerprint);
            row.put("account_kind",account.accountKind);row.put("binding",account.binding);row.put("expected_epoch",account.expectedEpoch);
            row.put("provisioned_password_fingerprint",account.provisionedPasswordHash==null?null:passwordFingerprint(account.provisionedPasswordHash));rows.add(row);
        }
        return Json.write(rows);
    }
    private static void validate(Receipt receipt) throws Exception {
        if(receipt==null||!FirstBindStore.validRequest(receipt.requestId)||receipt.approvalReference==null
                ||receipt.approvalReference.isBlank()||receipt.approvalReference.length()>200
                ||receipt.approvalReference.chars().anyMatch(Character::isISOControl)
                ||receipt.accounts==null||receipt.accounts.isEmpty()||receipt.accounts.size()>1000
                ||!Objects.equals(receipt.manifestDigest,manifestDigest(receipt.accounts)))throw rejected();
        Set<Long> ids=new HashSet<>();Set<String> names=new HashSet<>();
        for(Account account:receipt.accounts) {
            if(account==null||account.userId<1||account.userId>MAX||!ids.add(account.userId)
                    ||account.username==null||account.username.isEmpty()||account.username.length()>64||!names.add(account.username)
                    ||account.role==null||!Set.of("admin","manager","viewer").contains(account.role)
                    ||account.passwordFingerprint==null||!account.passwordFingerprint.matches("[a-f0-9]{64}")
                    ||account.accountKind==null||!Set.of("IMPORTED","EXISTING").contains(account.accountKind)
                    ||account.binding==null||!account.binding.matches("[a-f0-9]{64}")||account.expectedEpoch<0||account.expectedEpoch>=MAX)throw rejected();
        }
    }

    /** Bind the current gate to its exact durable approved list without persisting raw password hashes. */
    static boolean validStoredReceipt(Map<String,Object> receipt,long user,long epoch,String binding,String state) throws Exception {
        if(!(state.equals("REVOKED")?"REVOKE":"PREPARE").equals(receipt.get("operation")))return false;
        int found=0;
        for(Map<?,?> row:storedAccounts(receipt))if(((Number)row.get("user_id")).longValue()==user) {
            if(((Number)row.get("expected_epoch")).longValue()+1!=epoch||!binding.equals(row.get("binding")))return false;
            found++;
        }
        return found==1;
    }
    static boolean receiptMentionsUser(Map<String,Object> receipt,long user) throws Exception {
        for(Map<?,?> row:storedAccounts(receipt))if(((Number)row.get("user_id")).longValue()==user)return true;
        return false;
    }
    private static List<Map<?,?>> storedAccounts(Map<String,Object> receipt) throws Exception {
        String manifest=(String)receipt.get("approved_manifest");
        if(manifest==null||!digest(manifest).equals(receipt.get("manifest_digest"))||number(receipt,"actor_user_id")<1
                ||!(receipt.get("approval_reference") instanceof String reference)||reference.isBlank()||reference.length()>200
                ||reference.chars().anyMatch(Character::isISOControl)||!FirstBindStore.validRequest((String)receipt.get("request_id"))
                ||!Set.of("PREPARE","REVOKE").contains(receipt.get("operation")))throw rejected();
        Instant.parse((String)receipt.get("recorded_at"));
        Object parsed=Json.parse(manifest);if(!(parsed instanceof List<?> rows)||rows.isEmpty()||rows.size()>1000)throw rejected();
        List<Map<?,?>> result=new ArrayList<>();Set<Long> ids=new HashSet<>();
        for(Object object:rows) {
            if(!(object instanceof Map<?,?> row)||!row.keySet().equals(Set.of("user_id","username","name","role","status","password_fingerprint","account_kind","binding","expected_epoch","provisioned_password_fingerprint"))
                    ||!(row.get("user_id") instanceof Number id)||!exactInteger(id,1,MAX)||!ids.add(id.longValue())
                    ||!(row.get("expected_epoch") instanceof Number before)||!exactInteger(before,0,MAX-1)
                    ||!(row.get("username") instanceof String username)||username.isEmpty()||username.length()>64
                    ||!(row.get("binding") instanceof String source)||!source.matches("[a-f0-9]{64}")
                    ||!(row.get("password_fingerprint") instanceof String password)||!password.matches("[a-f0-9]{64}")
                    ||!Set.of("EXISTING","IMPORTED").contains(row.get("account_kind")))throw rejected();
            result.add(row);
        }
        return result;
    }
    private static boolean exactInteger(Number number,long min,long max) {
        try {long value=new java.math.BigDecimal(number.toString()).longValueExact();return value>=min&&value<=max;}
        catch(ArithmeticException|NumberFormatException invalid){return false;}
    }
    private static String digest(String value) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    private static long number(Map<String,Object> row,String name){return row!=null&&row.get(name) instanceof Number value?value.longValue():-1;}
    private static Api.ApiException rejected(){return new Api.ApiException(409,"首次绑定准备条件或批准名单已变化，请停机核对");}
}
