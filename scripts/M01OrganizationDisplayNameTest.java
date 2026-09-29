package com.training;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static com.training.OrganizationAccess.*;
import com.training.OrganizationAccountImportSourceTest.Fixture;

/** Compatibility and authority checks, using only this runner's synthetic temporary tree. */
public final class M01OrganizationDisplayNameTest {
    private static final String PASSWORD="SYNTHETIC-ORG-DISPLAY-20260923";
    // Fixed pre-extension serialization: omitted displayName must remain omitted byte-for-byte.
    private static final String LEGACY="{\"version\":\"legacy-v1\",\"codeRules\":{\"organizationPattern\":\"O[0-9]+\",\"personPattern\":\"P[0-9]+\",\"rolePattern\":\"R\"},\"roleCodes\":[\"R\"],\"organizations\":[{\"organizationCode\":\"O1\",\"parentOrganizationCode\":null,\"enabled\":true}],\"people\":[{\"personCode\":\"P1\",\"organizationCode\":\"O1\",\"responsibleOrganizationCodes\":[],\"leaderPersonCode\":null,\"bpPersonCode\":null,\"roleCodes\":[\"R\"],\"enabled\":true}],\"relations\":[{\"roleCode\":\"R\",\"leader\":{\"required\":false,\"allowedTargetRoles\":[\"R\"],\"targetMustCoverOrganization\":false,\"allowSelf\":false},\"bp\":{\"required\":false,\"allowedTargetRoles\":[\"R\"],\"targetMustCoverOrganization\":false,\"allowSelf\":false}}],\"accountBindings\":[{\"accountId\":1,\"personCode\":\"P1\",\"enabled\":true}],\"grants\":[]}";
    private static int checks;
    private static Auth.Session admin;
    private static Path root;
    private static long subjectId,leaderId,bpId,otherLeaderId;
    @FunctionalInterface private interface Work{void run()throws Exception;}

    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Fresh synthetic root required");
        root=Path.of(args[0]).toAbsolutePath().normalize();
        if(!Files.isDirectory(root)||!root.getFileName().toString().startsWith("yanxu-m01-organization-display-name."))throw new IllegalArgumentException("Synthetic root prefix required");
        for(String child:List.of("data","sources")){Path path=root.resolve(child);Files.createDirectories(path);try(var entries=Files.list(path)){if(entries.findAny().isPresent())throw new IllegalArgumentException("Fresh empty test directories required");}}
        compatibility();invalidNames();permissionsUnchanged();
        System.setProperty("data.dir",root.resolve("data").toString());System.setProperty("bootstrap.demo","false");System.setProperty("login.email.mode","legacy");
        try {
            Db.exec("CREATE TABLE users(id IDENTITY PRIMARY KEY,username VARCHAR(64) UNIQUE,password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            long administrator=user("synthetic-admin","admin");check(administrator==1,"fixed legacy account ID in fresh database");
            subjectId=user("synthetic-subject","viewer");leaderId=user("synthetic-leader","viewer");bpId=user("synthetic-bp","viewer");otherLeaderId=user("synthetic-other-leader","viewer");
            Db.init();OrganizationAccessStore.init();OrganizationAccountImport.init();login();
            persistedRoundTrips();relationshipCopies();provisioningNames();
            System.out.println("M01 organization display-name synthetic checks passed: "+checks);
        }finally{try{Db.get().close();}catch(Exception ignored){}}
    }

    private static Configuration legacy(){RelationRule optional=new RelationRule(false,Set.of("R"),false,false);return new Configuration("legacy-v1",new CodeRules("O[0-9]+","P[0-9]+","R"),Set.of("R"),
        List.of(new Organization("O1",null,true)),List.of(new Person("P1","O1",Set.of(),null,null,Set.of("R"),true)),List.of(new RoleRelations("R",optional,optional)),List.of(new AccountBinding(1,"P1",true)),List.of());}
    private static void compatibility()throws Exception {
        Configuration original=legacy();check(original.organizations().get(0).displayName()==null,"legacy three-argument constructor compiles and means absent");
        check(OrganizationAccess.validate(original).valid(),"legacy unnamed configuration validates");
        String old=Json.write(OrganizationAccessStore.toMap(original));check(LEGACY.equals(old),"pre-extension bytes exactly retained");check(digest(LEGACY).equals(digest(old)),"pre-extension digest unchanged");
        Configuration decoded=OrganizationAccessStore.parseConfiguration(Json.parse(LEGACY));
        check(decoded.equals(original)&&LEGACY.equals(Json.write(OrganizationAccessStore.toMap(decoded))),"legacy parse and serialization exact roundtrip");
        check(!object(rows(OrganizationAccessStore.toMap(decoded).get("organizations")).get(0)).containsKey("displayName"),"null omitted, never serialized as explicit null");
        for(String name:List.of("合成机构", "  合成机构  ", "Cafe\u0301 合成", "Caf\u00e9 合成", "合成 <分公司> & \"机构\"", "甲".repeat(200),"😀".repeat(100))){
            Map<String,Object> raw=rawLegacy();rows(raw.get("organizations")).get(0).put("displayName",name);
            Configuration named=OrganizationAccessStore.parseConfiguration(raw);check(OrganizationAccess.validate(named).valid(),"legal display name validates");
            check(name.equals(named.organizations().get(0).displayName()),"no trim or Unicode normalization");
            String encoded=Json.write(OrganizationAccessStore.toMap(named));check(name.equals(rows(OrganizationAccessStore.toMap(named).get("organizations")).get(0).get("displayName")),"named serialization retains exact original string");
            check(OrganizationAccessStore.parseConfiguration(Json.parse(encoded)).equals(named),"named JSON roundtrip");
            check(!digest(encoded).equals(digest(LEGACY)),"provided name participates in configuration digest");
        }
        Configuration explicitNull=withOrganizations(original,List.of(new Organization("O1",null,true,null)),original.version());
        check(LEGACY.equals(Json.write(OrganizationAccessStore.toMap(explicitNull))),"Java null retains legacy JSON shape");
        Configuration mixed=withOrganizations(original,List.of(new Organization("O1",null,true,"同名合成机构"),new Organization("O2",null,true),new Organization("O3",null,true,"同名合成机构")),original.version());
        Configuration mixedParsed=OrganizationAccessStore.parseConfiguration(Json.parse(Json.write(OrganizationAccessStore.toMap(mixed))));
        check(mixed.equals(mixedParsed)&&OrganizationAccess.validate(mixedParsed).valid(),"named and unnamed entries coexist; duplicate display labels are not identity collisions");
        check(!rows(OrganizationAccessStore.toMap(mixedParsed).get("organizations")).get(1).containsKey("displayName"),"mixed legacy entry remains omitted");
    }
    private static void invalidNames()throws Exception {
        List<String> invalid=new ArrayList<>(List.of(""," ","\u2003","a".repeat(201),"😀".repeat(101),"合成\u2028机构","合成\u2029机构"));
        for(int c=0;c<=0x9f;c++)if(Character.isISOControl(c))invalid.add("合成"+(char)c+"机构");
        for(String name:invalid){Configuration c=withOrganizations(legacy(),List.of(new Organization("O1",null,true,name)),"legacy-v1");Validation validation=OrganizationAccess.validate(c);
            check(!validation.valid()&&validation.issues().stream().anyMatch(i->i.code().equals("INVALID_DISPLAY_NAME")&&i.path().equals("organizations[0].displayName")),"direct Configuration rejects invalid name");
            Map<String,Object> raw=rawLegacy();rows(raw.get("organizations")).get(0).put("displayName",name);reject(400,()->OrganizationAccessStore.parseConfiguration(raw),"Store rejects invalid display name");}
        List<Object> nonStrings=new ArrayList<>();nonStrings.add(null);nonStrings.add(1);nonStrings.add(true);nonStrings.add(List.of("合成"));nonStrings.add(Map.of("name","合成"));
        for(Object value:nonStrings){Map<String,Object> raw=rawLegacy();rows(raw.get("organizations")).get(0).put("displayName",value);reject(400,()->OrganizationAccessStore.parseConfiguration(raw),"present null or non-string rejected");}
        Map<String,Object> alias=rawLegacy();rows(alias.get("organizations")).get(0).put("display_name","合成");reject(400,()->OrganizationAccessStore.parseConfiguration(alias),"alternate field spelling not accepted");
        Map<String,Object> extra=rawLegacy();rows(extra.get("organizations")).get(0).put("displayName","合成");rows(extra.get("organizations")).get(0).put("officeCode","private");reject(400,()->OrganizationAccessStore.parseConfiguration(extra),"extension does not permit unrelated fields");
    }
    private static void permissionsUnchanged()throws Exception {
        Configuration old=OrganizationAccessDemo.configuration();check(OrganizationAccess.validate(old).valid(),"old demo constructors compile unchanged");
        List<Organization> organizations=old.organizations().stream().map(o->new Organization(o.organizationCode(),o.parentOrganizationCode(),o.enabled(),"同名合成标签")).toList();
        Configuration named=withOrganizations(old,organizations,old.version());Engine before=new Engine(old),after=new Engine(named);
        check(OrganizationAccess.validate(named).valid(),"adding names preserves valid policy");
        for(AccountBinding b:old.accountBindings())for(String org:List.of("ORG-000","ORG-001","ORG-002","missing","同名合成标签"))for(Action action:Action.values())for(String resource:List.of("training.record","approval.review")){
            Decision a=before.authorize("synthetic credential",ignored->OptionalLong.of(b.accountId()),new Resource(resource,org),action);
            Decision n=after.authorize("synthetic credential",ignored->OptionalLong.of(b.accountId()),new Resource(resource,org),action);
            check(a.equals(n),"name changes neither allow/deny scope nor reasons");}
        check(!after.authorize("synthetic credential",ignored->OptionalLong.of(1004),new Resource("training.record","同名合成标签"),Action.HANDLE).allowed(),"display labels cannot be resource identifiers");
        check(after.authorize("synthetic credential",ignored->OptionalLong.of(1002),new Resource("training.record","ORG-001"),Action.HANDLE).allowed()&&!after.authorize("synthetic credential",ignored->OptionalLong.of(1002),new Resource("training.record","ORG-002"),Action.HANDLE).allowed(),"equal display names do not merge scopes");
    }

    private static void persistedRoundTrips()throws Exception {
        Configuration old=legacy();OrganizationAccessStore.publish(admin,null,old);login();
        check(LEGACY.equals(payload()),"Store publish persists old JSON exactly");check(OrganizationAccessStore.configuration().equals(old),"legacy database readback");
        Configuration named=withOrganizations(old,List.of(new Organization("O1",null,true,"  合成名称 Cafe\u0301  ")),"named-v1");
        Auth.Session beforeRename=admin;OrganizationAccessStore.publish(admin,old.version(),named);
        check(Auth.current(beforeRename)==null,"explicit name publication retains conservative mapped-session revocation");login();
        String saved=payload();check(saved.equals(Json.write(OrganizationAccessStore.toMap(named))),"named database payload exact");check(OrganizationAccessStore.configuration().equals(named),"named database readback");
        Map<String,Object> copied=OrganizationAccessStore.toMap(OrganizationAccessStore.configuration());copied.put("version","binding-copy-v1");rows(copied.get("accountBindings")).get(0).put("enabled",false);
        Configuration bindingCopy=OrganizationAccessStore.parseConfiguration(Json.parse(Json.write(copied)));OrganizationAccessStore.publish(admin,named.version(),bindingCopy);login();
        check(OrganizationAccessStore.configuration().organizations().equals(named.organizations()),"binding-only full configuration copy preserves names");
        check(rows(OrganizationAccessStore.toMap(OrganizationAccessStore.configuration()).get("organizations")).get(0).get("displayName").equals("  合成名称 Cafe\u0301  "),"binding-copy serialization keeps exact whitespace and Unicode");
        Db.exec("DELETE FROM organization_access_config");
    }
    private static Configuration relationshipConfig(){
        Set<String> roles=Set.of("STAFF","LEAD","BP");RelationRule leader=new RelationRule(false,Set.of("LEAD"),true,false),bp=new RelationRule(false,Set.of("BP"),true,false);
        return new Configuration("relationships-named-v1",new CodeRules("O[0-9]+","P[0-9]+","[A-Z]+"),roles,
            List.of(new Organization("O0",null,true,"合成目录"),new Organization("O1","O0",true,"  合成业务机构  "),new Organization("O2","O0",true)),
            List.of(new Person("P1","O1",Set.of(),"P2","P3",Set.of("STAFF"),true),new Person("P2","O1",Set.of("O1"),null,null,Set.of("LEAD"),true),new Person("P3","O0",Set.of("O1"),null,null,Set.of("BP"),true),new Person("P4","O1",Set.of("O1"),null,null,Set.of("LEAD"),true)),
            List.of(new RoleRelations("STAFF",new RelationRule(true,Set.of("LEAD"),true,false),new RelationRule(true,Set.of("BP"),true,false)),new RoleRelations("LEAD",leader,bp),new RoleRelations("BP",leader,bp)),
            List.of(new AccountBinding(subjectId,"P1",true),new AccountBinding(leaderId,"P2",true),new AccountBinding(bpId,"P3",true),new AccountBinding(otherLeaderId,"P4",true)),
            List.of(new Grant("leader-approve","LEAD","approval.review",Action.HANDLE,Effect.ALLOW,Scope.RESPONSIBLE_ORGS,Set.of()),new Grant("bp-approve","BP","approval.review",Action.HANDLE,Effect.ALLOW,Scope.RESPONSIBLE_ORGS,Set.of())));
    }
    private static void relationshipCopies()throws Exception {
        Configuration initial=relationshipConfig();OrganizationAccessStore.publish(admin,null,initial);login();
        Map<String,Object> preview=OrganizationAccountRelationships.preview(admin,subjectId,relationBody(initial.version(),"P4"));
        OrganizationAccountRelationships.confirm(admin,subjectId,Map.of("review_token",preview.get("review_token")));
        Configuration copied=OrganizationAccessStore.configuration();check(copied.organizations().equals(initial.organizations()),"actual R14 replacement preserves named and unnamed organizations");
        check(copied.people().get(0).leaderPersonCode().equals("P4"),"actual relationship mutation performed");
        String unchanged=payload();Map<String,Object> noop=OrganizationAccountRelationships.preview(admin,subjectId,relationBody(copied.version(),"P4"));
        OrganizationAccountRelationships.confirm(admin,subjectId,Map.of("review_token",noop.get("review_token")));check(unchanged.equals(payload()),"relationship no-op retains exact named JSON");
        Map<String,Object> stale=OrganizationAccountRelationships.preview(admin,subjectId,relationBody(copied.version(),"P2"));
        Map<String,Object> renamed=object(Json.parse(unchanged));rows(renamed.get("organizations")).get(1).put("displayName","合成名已变，编码未变");
        Db.exec("UPDATE organization_access_config SET payload=? WHERE active_slot=1",Json.write(renamed));
        check(OrganizationAccessStore.configuration().version().equals(copied.version()),"name-only test change keeps version");
        String changedPayload=payload();reject(409,()->OrganizationAccountRelationships.confirm(admin,subjectId,Map.of("review_token",stale.get("review_token"))),"R14 confirmation hash binds name-only configuration changes");
        check(changedPayload.equals(payload()),"rejected name-stale confirm does not overwrite new name");
        Db.exec("UPDATE organization_access_config SET payload=? WHERE active_slot=1",unchanged);
        reject(409,()->OrganizationAccountRelationships.confirm(admin,subjectId,Map.of("review_token",stale.get("review_token"))),"restoring old name never resurrects observed stale token");
        Db.exec("DELETE FROM organization_access_config");
    }
    private static Map<String,Object> relationBody(String version,String leader){return map("expected_version",version,"leader_person_code",leader,"bp_person_code","P3");}

    private static void provisioningNames()throws Exception {
        Fixture fixture=new Fixture(root.resolve("sources/names"));
        String renamedHome="  合成总部 Cafe\u0301  ",renamedBranch="合成分公司 <B> & \"示例\"",renamedRegion="合成区域 一";
        for(Map<String,Object> value:List.of(fixture.batch,fixture.cp,fixture.roles,fixture.auth,fixture.audit,fixture.regions,fixture.scope,fixture.preview)){
            rename(value,"SYNTHETIC HR",renamedHome);rename(value,"SYNTHETIC BRANCH B",renamedBranch);rename(value,"SYNTHETIC REGION ONE",renamedRegion);}
        var config=fixture.save();var source=OrganizationAccountImportSource.load(config);OrganizationAccountImport importer=new OrganizationAccountImport(config);
        Map<String,Object> incoming=importer.preview(admin);List<Object> decisions=new ArrayList<>();for(Map<String,Object> row:rows(incoming.get("rows")))decisions.add(map("reference",row.get("reference"),"action","CREATE_PENDING","accountId",null,"accountProof",null,"reviewed",true));
        importer.commit(admin,map("expectedRevision",incoming.get("revision"),"sourceFingerprint",incoming.get("sourceFingerprint"),"reviewToken",incoming.get("reviewToken"),"decisions",decisions));
        OrganizationAccountProvisioning service=new OrganizationAccountProvisioning(importer);String databaseBefore=database();Map<String,Object> p=service.preview(admin);
        Field reviewsField=OrganizationAccountProvisioning.class.getDeclaredField("REVIEWS");reviewsField.setAccessible(true);Object review=((Map<?,?>)reviewsField.get(service)).get(p.get("review_token"));
        Field hashField=review.getClass().getDeclaredField("configurationHash");hashField.setAccessible(true);String reviewedHash=(String)hashField.get(review);
        var prepared=service.confirm(admin,map("batch_key",p.get("batch_key"),"review_token",p.get("review_token")));Configuration candidate=prepared.configuration();
        check(databaseBefore.equals(database()),"named preview and confirm retain zero database writes");check(OrganizationAccess.validate(candidate).valid(),"named initial candidate fully validates");
        Map<String,Map<String,Object>> displayed=new HashMap<>();for(Map<String,Object> org:rows(p.get("organizations")))displayed.put((String)org.get("organization_code"),org);
        check(candidate.organizations().size()==displayed.size()&&candidate.organizations().size()==7,"named candidate retains complete directory");
        Set<String> expectedLabels=new HashSet<>(List.of("系统组织目录",renamedHome));for(var branch:source.branchCoverage()){expectedLabels.add(branch.branch());expectedLabels.add(branch.region());}
        check(candidate.organizations().stream().map(Organization::displayName).collect(java.util.stream.Collectors.toSet()).equals(expectedLabels),"all initial names come from trusted source or fixed root label");
        for(Organization org:candidate.organizations()){check(org.displayName()!=null&&org.displayName().equals(displayed.get(org.organizationCode()).get("display_name")),"persisted candidate label exactly equals reviewed DTO");
            check(displayed.get(org.organizationCode()).get("office_code")==null,"display name never becomes office code");}
        check(candidate.organizations().stream().anyMatch(o->renamedHome.equals(o.displayName())),"nonbranch home whitespace and combining form retained");
        check(reviewedHash.equals(digest(Json.write(canonical(OrganizationAccessStore.toMap(candidate))))),"R15 review hashes the entire named candidate");
        Configuration withoutNames=withOrganizations(candidate,candidate.organizations().stream().map(o->new Organization(o.organizationCode(),o.parentOrganizationCode(),o.enabled())).toList(),candidate.version());
        check(!reviewedHash.equals(digest(Json.write(canonical(OrganizationAccessStore.toMap(withoutNames))))),"omitting only names changes the confirmation digest");
        check(candidate.equals(OrganizationAccessStore.parseConfiguration(Json.parse(Json.write(OrganizationAccessStore.toMap(candidate))))),"generated candidate survives Store roundtrip with names");
        OrganizationAccessStore.publish(admin,null,candidate);login();
        check(candidate.organizations().equals(OrganizationAccessStore.configuration().organizations()),"trusted synthetic Store publication retains all generated names");
        check(OrganizationAccessStore.configuration().accountBindings().stream().noneMatch(AccountBinding::enabled),"adding names does not enable bindings");
    }

    @SuppressWarnings("unchecked")private static void rename(Object value,String from,String to){if(value instanceof Map<?,?> raw){Map<String,Object> m=(Map<String,Object>)raw;for(var e:m.entrySet()){if(from.equals(e.getValue()))e.setValue(to);else rename(e.getValue(),from,to);}}else if(value instanceof List<?> raw){List<Object> values=(List<Object>)raw;for(int i=0;i<values.size();i++){if(from.equals(values.get(i)))values.set(i,to);else rename(values.get(i),from,to);}}}
    private static Configuration withOrganizations(Configuration c,List<Organization> organizations,String version){return new Configuration(version,c.codeRules(),c.roleCodes(),organizations,c.people(),c.relations(),c.accountBindings(),c.grants(),c.combinedApprovals());}
    private static long user(String username,String role)throws Exception{return Db.insert("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,?,1)",username,Auth.hash(PASSWORD),"SYNTHETIC DISPLAY USER",role);}
    private static void login()throws Exception{admin=Auth.get(Auth.login("synthetic-admin",PASSWORD));check(admin!=null&&Auth.isAdmin(admin),"current real administrator session");}
    private static String payload()throws Exception{return (String)Db.one("SELECT payload FROM organization_access_config WHERE active_slot=1").get("payload");}
    private static String database()throws Exception{Map<String,Object> tables=new TreeMap<>();try(var rs=Db.get().getMetaData().getTables(null,"PUBLIC","%",new String[]{"TABLE"})){while(rs.next()){String table=rs.getString("TABLE_NAME");List<String> values=new ArrayList<>();for(Map<String,Object> row:Db.query("SELECT * FROM \""+table+"\""))values.add(Json.write(new TreeMap<>(row)));Collections.sort(values);tables.put(table,values);}}return Json.write(tables);}
    private static Object canonical(Object value){if(value instanceof Map<?,?> m){Map<String,Object> sorted=new TreeMap<>();m.forEach((k,v)->sorted.put((String)k,canonical(v)));return sorted;}if(value instanceof Set<?> values)return values.stream().map(M01OrganizationDisplayNameTest::canonical).sorted(Comparator.comparing(Json::write)).toList();if(value instanceof Collection<?> values)return values.stream().map(M01OrganizationDisplayNameTest::canonical).toList();return value;}
    private static String digest(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    private static Map<String,Object> rawLegacy(){return object(Json.parse(LEGACY));}
    private static void reject(int code,Work work,String label)throws Exception{try{work.run();throw new AssertionError(label+" must reject");}catch(Api.ApiException rejected){check(rejected.code==code,label+" status "+rejected.code);}}
    private static void check(boolean valid,String label){checks++;if(!valid)throw new AssertionError(label);}
    @SuppressWarnings("unchecked")private static Map<String,Object> object(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked")private static List<Map<String,Object>> rows(Object value){return (List<Map<String,Object>>)value;}
    private static Map<String,Object> map(Object... values){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)m.put((String)values[i],values[i+1]);return m;}
}
