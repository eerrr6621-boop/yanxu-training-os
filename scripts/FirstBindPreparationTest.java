package com.training;

import java.nio.file.*;
import java.util.*;

/** Focused real-store tests using a newly owned, explicitly marked synthetic database only. */
public final class FirstBindPreparationTest {
    static int checks;
    static final String PASSWORD="SYNTHETIC-preparation-password-only";
    interface Work {void run() throws Exception;}
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static void denied(Work work,String label)throws Exception{try{work.run();throw new AssertionError(label);}catch(Api.ApiException safe){checks++;}}
    static FirstBindPreparation.Account account(long uid,String supplied)throws Exception{
        synchronized(Api.MUTATION_LOCK){var row=Db.one("SELECT * FROM users WHERE id=?",uid);var identity=NotificationChannelsAccountEmailMaintenance.firstBindIdentity(uid);var gate=Db.one("SELECT epoch FROM s01_account_email_first_bind WHERE user_id=?",uid);
        return new FirstBindPreparation.Account(uid,(String)row.get("username"),(String)row.get("name"),(String)row.get("role"),((Number)row.get("status")).intValue(),FirstBindPreparation.passwordFingerprint((String)row.get("password")),identity.kind(),identity.binding(),gate==null?0:((Number)gate.get("epoch")).longValue(),supplied);}
    }
    static FirstBindPreparation.Receipt receipt(List<FirstBindPreparation.Account> accounts)throws Exception{return new FirstBindPreparation.Receipt(UUID.randomUUID().toString(),"SYNTHETIC-184-PLUS-2-APPROVAL",FirstBindPreparation.manifestDigest(accounts),accounts);}
    static Map<String,Object> counts()throws Exception{Map<String,Object> map=new TreeMap<>();for(String table:List.of("s01_account_email_first_bind","s01_account_email_first_bind_receipts","s01_account_email_first_bind_events","s01_account_email_maintenance_requests","s01_account_email_requests","s01_trusted_devices","s01_trusted_device_epochs"))map.put(table,Db.count(table));return map;}
    public static void main(String[] args)throws Exception{
        if(args.length!=1||!"disabled".equals(System.getenv("YANXU_LOGIN_MAIL_TRANSPORT")))throw new IllegalArgumentException("Owned data and disabled mail required");
        Path data=Path.of(args[0]).toRealPath();if(!data.toString().equals(System.getProperty("data.dir"))||!Files.isRegularFile(data.getParent().resolve(".preparation-test-owned")))throw new IllegalArgumentException("Explicit owned fixture required");
        try(var files=Files.list(data)){if(files.findAny().isPresent())throw new IllegalArgumentException("Fresh fixture only");}
        Db.exec("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64) UNIQUE,password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT)");
        String hash=Auth.hash(PASSWORD);
        for(int id=1;id<=6;id++)Db.exec("INSERT INTO users VALUES(?,?,?,?,?,?)",id,id==2?"manager01":"synthetic-"+id,id==3||id==4||id==6?null:hash,"Synthetic user "+id,id<=3?"admin":"viewer",id==3||id==4||id==6?0:1);
        OrganizationAccountImport.init();NotificationChannelsAccountEmailPreparation.init();TrustedDevices.init();FirstBindStore.init();
        for(int id:List.of(3,4))Db.exec("INSERT INTO organization_account_import_people(person_code,source_namespace,source_reference,account_id,batch_key,source_fingerprint,payload,disposition,created_by) VALUES(?,?,?,?,?,?,?,?,1)","SYNTHETIC-P"+id,"b".repeat(64),"SYNTHETIC-REF"+id,id,id==3?"SYNTHETIC-184":"SYNTHETIC-PLUS-2","a".repeat(64),Json.write(Map.of("synthetic",true,"readyForExecution",0)),"CREATE_PENDING");
        Map<String,Object> untouched=Db.one("SELECT * FROM users WHERE id=5");String importBefore=Json.write(Db.query("SELECT * FROM organization_account_import_people ORDER BY account_id"));
        System.setProperty("s01.first-bind.offline","true");System.setProperty("login.email.mode","required");
        var manager=account(2,null);var approved=receipt(List.of(manager,account(3,hash),account(4,hash)));
        denied(()->FirstBindPreparation.prepare("synthetic-1","wrong",approved),"actual admin password required");
        System.setProperty("login.email.mode","legacy");denied(()->FirstBindPreparation.prepare("synthetic-1",PASSWORD,approved),"required at admission");System.setProperty("login.email.mode","required");
        denied(()->FirstBindPreparation.prepare("synthetic-5",PASSWORD,approved),"enabled viewer is not administrator");
        var epochs=FirstBindPreparation.prepare("synthetic-1",PASSWORD,approved);check(epochs.size()==3,"exact approved count");
        check("admin".equals(Db.one("SELECT role FROM users WHERE id=3").get("role")),"pending imported administrator role retained");
        check("viewer".equals(Db.one("SELECT role FROM users WHERE id=4").get("role")),"pending imported viewer role retained");
        check(hash.equals(Db.one("SELECT password FROM users WHERE id=2").get("password")),"manager password untouched");
        check(untouched.equals(Db.one("SELECT * FROM users WHERE id=5")),"unapproved enabled user untouched");
        check(importBefore.equals(Json.write(Db.query("SELECT * FROM organization_account_import_people ORDER BY account_id"))),"184 and supplemental source facts unchanged");
        check(Db.count("s01_trusted_device_epochs")==0,"no nonexistent device epoch invented");
        Map<String,Object> before=counts();check(epochs.equals(FirstBindPreparation.prepare("synthetic-1",PASSWORD,approved)),"exact receipt replay returns original epochs");check(before.equals(counts()),"receipt replay write free");
        var changedReceipt=new FirstBindPreparation.Receipt(approved.requestId(),"OTHER-SYNTHETIC-APPROVAL",approved.manifestDigest(),approved.accounts());denied(()->FirstBindPreparation.prepare("synthetic-1",PASSWORD,changedReceipt),"modified receipt replay rejected");
        var managerCredential=Auth.checkedCredential("manager01",PASSWORD);var eligibility=FirstBindStore.ready(managerCredential);check(eligibility!=null,"manager01 bootstrap without Session");
        check(!FirstBindStore.allowsSession(2),"READY blocks ordinary issue");System.setProperty("login.email.mode","legacy");check(Auth.login("manager01",PASSWORD)==null,"legacy cannot bypass READY");System.setProperty("login.email.mode","required");
        for(String table:List.of("s01_account_email_first_bind","s01_account_email_first_bind_events","s01_account_email_first_bind_receipts")){
            Db.exec("ALTER TABLE "+table+" RENAME TO "+table+"_missing");try{denied(()->FirstBindStore.allowsSession(5),"missing schema denies ungated legacy account");denied(()->FirstBindStore.allowsSession(2),"missing schema denies READY");}finally{Db.exec("ALTER TABLE "+table+"_missing RENAME TO "+table);}
        }
        synchronized(Api.MUTATION_LOCK){try{Db.transaction(()->{Db.exec("DELETE FROM s01_account_email_first_bind_events WHERE user_id=2");Db.exec("DELETE FROM s01_account_email_first_bind WHERE user_id=2");denied(()->FirstBindStore.allowsSession(2),"receipt-only orphan cannot bypass");throw new IllegalStateException("synthetic rollback fixture");});}catch(IllegalStateException expected){check(FirstBindStore.current(managerCredential,eligibility),"orphan test rolled back");}}
        var snapshot=FirstBindStore.complete(managerCredential,eligibility,"manager-proof@example.invalid",UUID.randomUUID().toString());check(snapshot.revision()==1&&Auth.credentialCurrent(managerCredential),"completion does not rotate credential generation");
        String token=Auth.issueVerified(managerCredential,snapshot);check(Auth.get(token)!=null,"postcommit ordinary issue succeeds");
        before=counts();check(epochs.equals(FirstBindPreparation.prepare("synthetic-1",PASSWORD,approved)),"replay after completion retains original descriptors");check(before.equals(counts())&&Auth.get(token)!=null,"postcompletion receipt replay does not revoke new session");check(!FirstBindStore.current(managerCredential,epochs.get(0)),"old replay descriptor is not fresh READY authority");
        var revoked=receipt(List.of(account(3,null)));FirstBindPreparation.revoke("synthetic-1",PASSWORD,revoked);check(!FirstBindStore.allowsSession(3),"revocation blocks ordinary access");var third=FirstBindPreparation.prepare("synthetic-1",PASSWORD,receipt(List.of(account(3,null))));check(third.get(0).epoch()==3,"revoke and reauthorize epochs increase monotonically");
        check(!FirstBindStore.current(Auth.checkedCredential("synthetic-3",PASSWORD),epochs.get(1)),"old READY epoch remains stale after reauthorization");
        denied(()->FirstBindPreparation.prepare("synthetic-1",PASSWORD,receipt(List.of(account(6,hash)))),"uninitialized nonimported activation not allowed");
        var expectedField=account(5,null);var expectedPlan=receipt(List.of(expectedField));Db.exec("UPDATE users SET name='Changed synthetic' WHERE id=5");denied(()->FirstBindPreparation.prepare("synthetic-1",PASSWORD,expectedPlan),"approved current fields must still match");Db.exec("UPDATE users SET name=? WHERE id=5",untouched.get("name"));
        check(FirstBindStore.allowsSession(5),"healthy uninvolved account retains legacy eligibility");synchronized(Api.MUTATION_LOCK){FirstBindStore.guardAccountDeletion(5);denied(()->FirstBindStore.guardAccountDeletion(2),"participating target history retained");denied(()->FirstBindStore.guardAccountDeletion(1),"participating approving actor retained");}
        System.out.println("FirstBindPreparationTest passed: "+checks+" checks; synthetic owned data only.");Db.get().close();
    }
}
