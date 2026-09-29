package com.training;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static com.training.OrganizationAccess.*;

/** Private tests only. Fresh loopback H2, explicit synthetic M01, real Main; no product test route. */
public final class IntegrationFormalSettlementHttpFixture {
    private static final String PREFIX="yanxu-formal-settlement-http-";
    private static final LinkedHashMap<String,String> ACTORS=new LinkedHashMap<>();
    static {
        ACTORS.put("organizer","FILLER");ACTORS.put("leader","LEADER");ACTORS.put("bp","BP");ACTORS.put("team","TEAM");
        ACTORS.put("submitter","SUBMIT");ACTORS.put("reviewer","REVIEW");ACTORS.put("confirmer","CONFIRM");ACTORS.put("corrector","CORRECT");
        ACTORS.put("payer","PAY");ACTORS.put("settings","SETTINGS");ACTORS.put("delivery","DELIVERY");ACTORS.put("verifier","VERIFY");
        ACTORS.put("reader","READ");ACTORS.put("reporter","REPORT");ACTORS.put("outsider","OUT");ACTORS.put("otherorganizer","FILLER");ACTORS.put("disabled","SUBMIT");
        ACTORS.put("adjuster","ADJUST");ACTORS.put("importer","IMPORT");ACTORS.put("reportreader","REPORTREAD");
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=1&&!(args.length==2&&"xlsx".equals(args[0])))throw new IllegalArgumentException("Expected port, inspect or xlsx path");
        Path root=Path.of(required("integration.formal.settlement.root")).toRealPath(),data=Path.of(required("data.dir")).toRealPath();
        String password=required("bootstrap.admin.password");
        if(!root.getParent().equals(Path.of(System.getProperty("java.io.tmpdir")).toRealPath())||!root.getFileName().toString().startsWith(PREFIX)||!data.getParent().equals(root)
                ||!"127.0.0.1".equals(System.getProperty("bind.address"))||Boolean.getBoolean("bootstrap.demo")
                ||!"legacy".equals(System.getProperty("login.email.mode"))||!password.matches("[a-f0-9]{48}"))
            throw new IllegalArgumentException("Explicit isolated synthetic fixture required");
        if("xlsx".equals(args[0])){System.out.println(Json.write(workbook(root,Path.of(args[1]))));return;}
        if("inspect".equals(args[0])){System.out.println(Json.write(snapshot()));Db.get().close();return;}
        if(!Boolean.getBoolean("integration.formal.settlement.resume")) {
            try(var files=Files.list(data)){if(files.findAny().isPresent())throw new IllegalArgumentException("Fresh empty fixture required");}
            Db.init();if(Db.count("users")!=1||OrganizationAccessStore.configuration()!=null)throw new IllegalStateException("Unexpected identity seed");
            setup(root,password);
        }else Db.init();
        Thread controller=new Thread(()->controls(root),"synthetic-formal-controls");controller.setDaemon(true);controller.start();
        Main.main(args);
    }
    private static void setup(Path root,String password)throws Exception {
        Map<String,Object> metadata=new LinkedHashMap<>(),accounts=new LinkedHashMap<>();List<AccountBinding> bindings=new ArrayList<>();List<Person> people=new ArrayList<>();
        int index=0;String hash=Auth.hash(password);Set<String> roles=new LinkedHashSet<>(ACTORS.values());
        for(var entry:ACTORS.entrySet()) {
            String actor=entry.getKey(),role=entry.getValue(),person="P"+(++index),org=switch(actor){case "team"->"900";case "leader","bp","outsider","otherorganizer"->"002";default->"001";};
            long account=Db.insert("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,?,1)","synthetic-formal-"+actor,hash,"SYNTHETIC FORMAL "+actor,actor.equals("team")?"manager":"viewer");
            Set<String> responsible=Set.of("LEADER","BP","TEAM").contains(role)?Set.of("001","002"):Set.of();
            people.add(new Person(person,org,responsible,role.equals("FILLER")?"P2":null,role.equals("FILLER")?"P3":null,Set.of(role),true));
            bindings.add(new AccountBinding(account,person,!actor.equals("disabled")));
            accounts.put(actor,Map.of("account_id",account,"username","synthetic-formal-"+actor,"person_code",person,"organization_code",org,"role_code",role));
        }
        List<Grant> grants=new ArrayList<>();
        for(String role:roles) {
            if(!Set.of("REPORT","REPORTREAD").contains(role))grant(grants,role,"delivery.read",Action.VIEW,role.equals("TEAM")?Scope.RESPONSIBLE_ORGS:Scope.OWN_ORG);
            if(Set.of("FILLER","LEADER","BP","TEAM").contains(role))grant(grants,role,"demand.read",Action.VIEW,Set.of("LEADER","BP","TEAM").contains(role)?Scope.RESPONSIBLE_ORGS:Scope.OWN_ORG);
        }
        grant(grants,"FILLER","demand.write",Action.HANDLE,Scope.OWN_ORG);grant(grants,"FILLER","bid.result",Action.HANDLE,Scope.OWN_ORG);
        grant(grants,"TEAM","demand.write",Action.HANDLE,Scope.RESPONSIBLE_ORGS);grant(grants,"TEAM","demand.accept",Action.HANDLE,Scope.RESPONSIBLE_ORGS);
        for(String role:List.of("LEADER","BP"))grant(grants,role,"approval.review",Action.HANDLE,Scope.RESPONSIBLE_ORGS);
        grant(grants,"DELIVERY","delivery.write",Action.HANDLE,Scope.OWN_ORG);grant(grants,"VERIFY","delivery.verify",Action.HANDLE,Scope.OWN_ORG);
        for(var entry:Map.of("SUBMIT","submit","REVIEW","review","CONFIRM","confirm","CORRECT","correct","PAY","pay","SETTINGS","configure").entrySet())
            grant(grants,entry.getKey(),"settlement."+entry.getValue(),Action.HANDLE,Scope.OWN_ORG);
        for(String resource:List.of("delivery.write","delivery.verify","settlement.submit","settlement.review","settlement.confirm","settlement.correct","settlement.pay","settlement.configure"))grant(grants,"OUT",resource,Action.HANDLE,Scope.OWN_ORG);
        for(String role:List.of("REPORT","OUT")) {grant(grants,role,"reports.read",Action.VIEW,Scope.OWN_ORG);grant(grants,role,"reports.export",Action.EXPORT,Scope.OWN_ORG);}
        grant(grants,"REPORTREAD","reports.read",Action.VIEW,Scope.OWN_ORG);
        grant(grants,"ADJUST","settlement.confirm",Action.HANDLE,Scope.OWN_ORG);grant(grants,"ADJUST","settlement.correct",Action.HANDLE,Scope.OWN_ORG);
        grant(grants,"IMPORT","settlement.confirm",Action.HANDLE,Scope.OWN_ORG);grant(grants,"IMPORT","settlement.pay",Action.HANDLE,Scope.OWN_ORG);
        grant(grants,"READ","settlement.export",Action.EXPORT,Scope.OWN_ORG);
        grant(grants,"TEAM","catalog.read",Action.VIEW,Scope.RESPONSIBLE_ORGS);grant(grants,"TEAM","catalog.manage",Action.HANDLE,Scope.RESPONSIBLE_ORGS);
        RelationRule optional=new RelationRule(false,Set.of("LEADER","BP"),false,false);
        List<RoleRelations> relations=new ArrayList<>();for(String role:roles)relations.add(new RoleRelations(role,
                role.equals("FILLER")?new RelationRule(true,Set.of("LEADER"),true,false):optional,
                role.equals("FILLER")?new RelationRule(true,Set.of("BP"),true,false):optional));
        Configuration config=new Configuration("synthetic-formal-v1",new CodeRules("[0-9]{3}","P[0-9]+","[A-Z]+"),roles,
                List.of(new Organization("001",null,true),new Organization("002",null,true),new Organization("900",null,true)),people,relations,bindings,grants);
        String token=Auth.login("admin",password);OrganizationAccessStore.publish(Auth.get(token),null,config);Auth.logout(token);
        token=Auth.login("synthetic-formal-team",password);long scope1=((Number)CourseCatalogIntegration.registerScope(Auth.get(token),"001").get("scope_id")).longValue();
        long scope2=((Number)CourseCatalogIntegration.registerScope(Auth.get(token),"002").get("scope_id")).longValue();Auth.logout(token);
        long teacher=Db.insert("INSERT INTO teachers(name,status,teacher_level,fee_rate,base_province,base_city) VALUES(?,'在库','讲师',99999,'SYNTHETIC','SYNTHETIC')","SYNTHETIC FORMAL TEACHER A");
        long alternate=Db.insert("INSERT INTO teachers(name,status,teacher_level,fee_rate,base_province,base_city) VALUES(?,'在库','高级讲师',88888,'SYNTHETIC','SYNTHETIC')","SYNTHETIC FORMAL TEACHER B");
        metadata.put("accounts",accounts);metadata.put("admin_username","admin");metadata.put("teacher_id",teacher);metadata.put("alternate_teacher_id",alternate);
        metadata.put("scope_ids",Map.of("001",scope1,"002",scope2));metadata.put("service_date",LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1).toString());metadata.put("payment_date",LocalDate.now(ZoneId.of("Asia/Shanghai")).toString());
        metadata.put("synthetic",true);metadata.put("configuration_version",config.version());atomic(root.resolve("metadata.json"),Json.write(metadata));
    }
    private static void grant(List<Grant> grants,String role,String resource,Action action,Scope scope) {
        grants.add(new Grant(role+"-"+resource,role,resource,action,Effect.ALLOW,scope,Set.of()));
    }
    private static void controls(Path root) {
        Path request=root.resolve("control-request.json"),response=root.resolve("control-result.json");
        while(true)try {
            if(!Files.exists(request)){Thread.sleep(20);continue;}
            @SuppressWarnings("unchecked") Map<String,Object> input=(Map<String,Object>)Json.parse(Files.readString(request));Files.delete(request);
            Map<String,Object> result=new LinkedHashMap<>();result.put("nonce",input.get("nonce"));
            try { synchronized(Api.MUTATION_LOCK) {result.put("data",control(input));} }catch(Exception e){result.put("error",e.getClass().getSimpleName());}
            atomic(response,Json.write(result));
        }catch(Exception ignored){try{Thread.sleep(20);}catch(InterruptedException stop){return;}}
    }
    private static Object control(Map<String,Object> input)throws Exception {
        switch(String.valueOf(input.get("operation"))) {
            case "snapshot":return snapshot();
            // Service boundary inspection only, using the same real HTTP-authenticated token.
            // Reflection keeps fixture compilation independent of the not-yet-frozen product candidate.
            case "financial-source": {
                Auth.Session session=Auth.get(String.valueOf(input.get("token")));long project=((Number)input.get("project_id")).longValue();
                try {return DeliverySettlementIntegration.class.getMethod("financialSource",Auth.Session.class,long.class,boolean.class).invoke(null,session,project,Boolean.TRUE.equals(input.get("export")));}
                catch(java.lang.reflect.InvocationTargetException wrapped){if(wrapped.getCause() instanceof Api.ApiException e)return Map.of("status",e.code);throw wrapped;}
            }
            case "revoke":Auth.revokeUserSessions(account(input));return Map.of("revoked",true);
            case "account-status": {int status=((Number)input.get("status")).intValue();if(status!=0&&status!=1)throw new IllegalArgumentException();Db.exec("UPDATE users SET status=? WHERE id=?",status,account(input));return Map.of("updated",true);}
            default:throw new IllegalArgumentException("Unknown isolated control operation");
        }
    }
    private static long account(Map<String,Object> input)throws Exception {
        String actor=String.valueOf(input.get("actor"));if(!ACTORS.containsKey(actor))throw new IllegalArgumentException("Synthetic actor required");
        return ((Number)Db.one("SELECT id FROM users WHERE username=?","synthetic-formal-"+actor).get("id")).longValue();
    }
    private static Map<String,Object> snapshot()throws Exception {
        Map<String,Object> result=new TreeMap<>();
        for(Map<String,Object> table:Db.query("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")) {
            String name=String.valueOf(table.get("table_name"));if(!name.matches("[A-Za-z0-9_]+"))throw new IllegalStateException("Unexpected table");
            List<String> rows=new ArrayList<>();for(Map<String,Object> row:Db.query("SELECT * FROM "+name))rows.add(Json.write(new TreeMap<>(row)));Collections.sort(rows);
            result.put(name.toLowerCase(Locale.ROOT),Map.of("rows",rows.size(),"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.write(rows).getBytes(StandardCharsets.UTF_8)))));
        }
        return result;
    }
    private static Map<String,Object> workbook(Path root,Path source)throws Exception {
        Path file=source.toRealPath();if(!file.getParent().equals(root)||!file.getFileName().toString().endsWith(".xlsx"))throw new IllegalArgumentException("Own workbook required");
        List<String> entries=new ArrayList<>(),sheets=new ArrayList<>();StringBuilder text=new StringBuilder();int formulas=0,external=0;
        var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        try(var zip=new java.util.zip.ZipFile(file.toFile())) {
            var names=zip.entries();while(names.hasMoreElements()) {
                var entry=names.nextElement();entries.add(entry.getName());
                if(entry.getName().endsWith(".xml")||entry.getName().endsWith(".rels"))try(var in=zip.getInputStream(entry)) {
                    var document=factory.newDocumentBuilder().parse(in);formulas+=document.getElementsByTagNameNS("*","f").getLength();
                    var links=document.getElementsByTagNameNS("*","Relationship");for(int i=0;i<links.getLength();i++)if("External".equals(((org.w3c.dom.Element)links.item(i)).getAttribute("TargetMode")))external++;
                    if(entry.getName().equals("xl/workbook.xml")){var nodes=document.getElementsByTagNameNS("*","sheet");for(int i=0;i<nodes.getLength();i++)sheets.add(((org.w3c.dom.Element)nodes.item(i)).getAttribute("name"));}
                    text.append(document.getDocumentElement().getTextContent()).append('\n');
                }
            }
        }
        return Map.of("entries",entries,"sheets",sheets,"formulas",formulas,"external_links",external,"text",text.toString());
    }
    private static void atomic(Path target,String text)throws Exception {Path next=target.resolveSibling(target.getFileName()+".tmp");Files.writeString(next,text,StandardCharsets.UTF_8);Files.move(next,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
    private static String required(String name){String value=System.getProperty(name,"");if(value.isBlank())throw new IllegalArgumentException("Missing isolated setting "+name);return value;}
}
