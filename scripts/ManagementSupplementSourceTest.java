package com.training;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import com.training.OrganizationAccountImportSource.FilePin;
import com.training.OrganizationManagementSupplementSource.Config;

/** Source-contract tests use synthetic text and caller-owned temporary directories only. */
public final class ManagementSupplementSourceTest {
    private static int checks;
    private static final String DECISION="root-management-group-20260923-confirmed";
    private interface Attempt { void run() throws Exception; }
    private interface Mutation { void apply(Fixture fixture) throws Exception; }
    public static final class Fixture {
        public final Path dir;
        public final FilePin groupsOriginal,teacherOriginal;
        public final Map<String,Object> projection,decision,receipt;
        public String batchKey="synthetic-management-supplement";
        public Fixture(Path directory,OrganizationAccountImportSource.Config oldConfig,Map<String,Object> oldReceipt)throws Exception {
            dir=directory.toAbsolutePath().normalize();Files.createDirectories(dir);
            // Distinct fixtures represent independently reviewed synthetic originals/namespaces.
            groupsOriginal=write("groups.original","SYNTHETIC GROUP ORIGINAL "+dir.getFileName());
            teacherOriginal=oldConfig.originals().get(0);
            receipt=copy(oldReceipt);
            decision=m("sourceThreadId","SYNTHETIC SOURCE TASK","decisionId",DECISION,
                    "membership","SYNTHETIC explicit two-person membership","excludedConflict","SYNTHETIC nine history records excluded",
                    "explicitHomeMapping",m("group-main-row-3",home("总经理室成员"),"group-main-row-10",home("总经理室")),
                    "executionBoundary","SYNTHETIC receive accounts only; no configuration publication");
            List<Object> candidates=l(candidate(3,"SYNTHETIC SUPPLEMENT A","000SUP003"),candidate(10,"SYNTHETIC SUPPLEMENT B","000SUP010"));
            List<Object> excluded=l();
            for(int row=185;row<=193;row++)excluded.add(m("reference","teacher-row-"+row,"name","SYNTHETIC EXCLUDED "+row,"organization","SYNTHETIC HISTORY ORG",
                    "source",m("sheet","汇合公司2026年兼职教师在库名单","row",row,"range","A"+row+":F"+row)));
            Map<String,Map<String,Object>> oldRows=new HashMap<>();
            for(Object value:a(o(Json.parse(Files.readString(oldConfig.candidates().path()))).get("candidates"))){Map<String,Object> row=o(value);oldRows.put((String)row.get("candidate_reference"),row);}
            List<Object> mappings=l();
            for(Object value:a(receipt.get("rows"))){Map<String,Object> row=o(value),source=oldRows.get(row.get("reference"));
                if(source==null)throw new IllegalArgumentException("Synthetic fixture requires complete old candidate references");
                mappings.add(m("reference",row.get("reference"),"name",source.get("name"),"organization",source.get("organization"),
                        "sourceStaffId",source.get("source_staff_id_candidate"),"accountId",row.get("accountId"),"personCode",row.get("personCode")));
            }
            projection=m("schemaVersion","M01-MANAGEMENT-SUPPLEMENT-v1","groupsSha256",groupsOriginal.sha256(),"teacherSha256",teacherOriginal.sha256(),
                    "decisionSha256",null,"originalReceiptSha256",null,"decisionId",DECISION,"candidates",candidates,"excluded",excluded,"existingMappings",mappings);
        }
        /** Re-pin a reviewed synthetic mutation, so rejection must cover semantics as well as hashes. */
        public Config save()throws Exception {
            FilePin decisionPin=write("decision.json",Json.write(decision)),receiptPin=write("original-receipt.json",Json.write(receipt));
            projection.put("decisionSha256",decisionPin.sha256());projection.put("originalReceiptSha256",receiptPin.sha256());
            return new Config(batchKey,groupsOriginal,teacherOriginal,write("projection.json",Json.write(projection)),decisionPin,receiptPin);
        }
        private FilePin write(String name,String body)throws Exception{Path file=dir.resolve(name);Files.writeString(file,body,StandardCharsets.UTF_8);return new FilePin(file,hash(file));}
        private static Map<String,Object> home(String label){return m("sourceLabel",label,"targetOrganizationCode","SYNTHETIC_HQ","targetDisplayName","公司总经理室");}
        private static Map<String,Object> candidate(int row,String name,String id){return m("reference","group-main-row-"+row,"name",name,"sourceStaffId",id,
                "source",m("sheet","全体职能员工","row",row,"range","B"+row+":D"+row,"organizationLabel",row==3?"总经理室成员":"总经理室",
                        "organizationLabelRange",row==3?"B3:B8":"B10","nameCell","C"+row,"staffIdCell","D"+row));}
    }

    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Caller-owned empty synthetic source directory required");
        Path root=Path.of(args[0]).toAbsolutePath();
        if(!Files.isDirectory(root))throw new IllegalArgumentException("Missing synthetic directory");
        try(var files=Files.list(root)){if(files.findAny().isPresent())throw new IllegalArgumentException("Only an empty synthetic directory is accepted");}
        OrganizationAccountImportSource.Config oldConfig=new OrganizationAccountImportSourceTest.Fixture(root.resolve("old")).save();
        var oldSource=OrganizationAccountImportSource.load(oldConfig);List<Object> oldRows=l();long id=100;
        for(var person:oldSource.candidates()){id++;oldRows.add(m("reference",person.reference(),"personCode","imp_p_"+String.format("%032x",id),"accountId",id,"action","CREATE_PENDING"));}
        // Explicit simulated receipt for source-only contract checks; intake suite uses the real old engine.
        Map<String,Object> receipt=m("batchKey",oldConfig.batchKey(),"sourceFingerprint",oldSource.fingerprint(),"revision",1,"imported",true,"replayed",false,
                "summary",m("candidates",oldRows.size(),"createdPending",oldRows.size(),"linkedExisting",0,"historicalExcluded",9),"rows",oldRows,"permissionsPublished",false,"accountsActivated",false);
        run(root,oldConfig,receipt);
        System.out.println("ManagementSupplementSource: "+checks+" checks passed (synthetic pinned files only)");
    }

    private static void run(Path root,OrganizationAccountImportSource.Config oldConfig,Map<String,Object> oldReceipt)throws Exception {
        Fixture valid=new Fixture(root.resolve("valid"),oldConfig,oldReceipt);Config config=valid.save();var source=OrganizationManagementSupplementSource.load(config);
        ok(source.candidates().size()==2&&source.excludedReferences().size()==9,"exact supplemental scope");
        ok(source.candidates().get(0).reference().equals("group-main-row-3")&&source.candidates().get(1).reference().equals("group-main-row-10"),"stable candidate order");
        ok(source.branchCoverage().isEmpty(),"no synthesized branch coverage");
        ok(source.fingerprint().equals(OrganizationManagementSupplementSource.load(config).fingerprint()),"stable fingerprint");
        ok(OrganizationManagementSupplementSource.namespace(config).equals(hashText("M01-GROUP-MAIN-v1\n"+config.groupsOriginal().sha256()+"\n全体职能员工\n")),"namespace exactly follows group-source protocol");
        Config renamed=with(config,"batchKey","synthetic-renamed");
        ok(OrganizationManagementSupplementSource.namespace(config).equals(OrganizationManagementSupplementSource.namespace(renamed)),"namespace does not change with batch name");
        ok(!source.fingerprint().equals(OrganizationManagementSupplementSource.load(renamed).fingerprint()),"fingerprint binds batch name");
        for(var candidate:source.candidates()){
            ok(candidate.organization().equals("公司总经理室"),"explicit home organization");
            ok(a(candidate.preparation().get("approvalRoles")).isEmpty()&&Boolean.FALSE.equals(candidate.preparation().get("canApprove")),"source cannot grant approval");
            String safe=Json.write(candidate.preparation());ok(!safe.contains("000SUP")&&!safe.contains(root.toString()),"preparation hides employee IDs and source paths");
            immutable(()->candidate.preparation().put("role","admin"),"preparation immutable");
            immutable(()->a(candidate.preparation().get("approvalRoles")).add(m("kind","ADMIN")),"nested roles immutable");
        }
        immutable(()->source.evidence().put("x",true),"private evidence immutable");
        String evidence=Json.write(source.evidence());
        ok(evidence.contains("000SUP003")&&!evidence.contains("SYNTHETIC PRIVATE")&&!evidence.contains("SYNTHETIC EXCLUDED"),"private evidence retains new source identity without cloning legacy personnel");
        Fixture sameName=new Fixture(root.resolve("same-name"),oldConfig,oldReceipt);row(sameName,"candidates",1).put("name",row(sameName,"candidates",0).get("name"));
        ok(OrganizationManagementSupplementSource.load(sameName.save()).candidates().size()==2,"two distinct source IDs with same name are not merged");
        Fixture reorder=new Fixture(root.resolve("reorder"),oldConfig,oldReceipt);Collections.reverse(a(reorder.projection.get("candidates")));
        ok(OrganizationManagementSupplementSource.load(reorder.save()).candidates().get(0).reference().equals("group-main-row-3"),"input order cannot change output source order");
        Fixture linked=new Fixture(root.resolve("linked-old-receipt"),oldConfig,oldReceipt);o(a(linked.receipt.get("rows")).get(0)).put("action","LINK_EXISTING");o(linked.receipt.get("summary")).put("createdPending",6);o(linked.receipt.get("summary")).put("linkedExisting",1);
        ok(OrganizationManagementSupplementSource.load(linked.save()).candidates().size()==2,"complete legacy receipt supports linked-existing actions");

        mutation(root,oldConfig,oldReceipt,"drop-candidate",f->a(f.projection.get("candidates")).remove(1));
        mutation(root,oldConfig,oldReceipt,"duplicate-candidate-reference",f->row(f,"candidates",1).put("reference","group-main-row-3"));
        mutation(root,oldConfig,oldReceipt,"candidate-multirow-range",f->o(row(f,"candidates",0).get("source")).put("range","B3:D10"));
        mutation(root,oldConfig,oldReceipt,"candidate-wrong-row",f->o(row(f,"candidates",0).get("source")).put("row",10));
        mutation(root,oldConfig,oldReceipt,"candidate-wrong-name-cell",f->o(row(f,"candidates",0).get("source")).put("nameCell","C4"));
        mutation(root,oldConfig,oldReceipt,"candidate-wrong-label-range",f->o(row(f,"candidates",0).get("source")).put("organizationLabelRange","B3:B10"));
        mutation(root,oldConfig,oldReceipt,"duplicate-staff-id",f->row(f,"candidates",1).put("sourceStaffId",row(f,"candidates",0).get("sourceStaffId")));
        mutation(root,oldConfig,oldReceipt,"numeric-staff-id",f->row(f,"candidates",0).put("sourceStaffId",123));
        mutation(root,oldConfig,oldReceipt,"trimmed-staff-id",f->row(f,"candidates",0).put("sourceStaffId"," 000SUP003"));
        mutation(root,oldConfig,oldReceipt,"nonascii-staff-id",f->row(f,"candidates",0).put("sourceStaffId","工号003"));
        mutation(root,oldConfig,oldReceipt,"candidate-name-over-user-column",f->row(f,"candidates",0).put("name","S".repeat(65)));
        mutation(root,oldConfig,oldReceipt,"candidate-name-padding",f->row(f,"candidates",0).put("name"," SYNTHETIC SUPPLEMENT A"));
        mutation(root,oldConfig,oldReceipt,"candidate-name-control",f->row(f,"candidates",0).put("name","SYNTHETIC\nSUPPLEMENT"));
        mutation(root,oldConfig,oldReceipt,"mapping-name-over-user-column",f->row(f,"existingMappings",0).put("name","S".repeat(65)));
        mutation(root,oldConfig,oldReceipt,"historical-name-over-user-column",f->row(f,"excluded",0).put("name","S".repeat(65)));
        mutation(root,oldConfig,oldReceipt,"mapping-organization-over-column",f->row(f,"existingMappings",0).put("organization","S".repeat(201)));
        mutation(root,oldConfig,oldReceipt,"candidate-original-id-conflict",f->row(f,"candidates",0).put("sourceStaffId",row(f,"existingMappings",1).get("sourceStaffId")));
        mutation(root,oldConfig,oldReceipt,"candidate-old-home-name-conflict",f->{row(f,"existingMappings",0).put("name",row(f,"candidates",0).get("name"));row(f,"existingMappings",0).put("organization","公司总经理室");});
        mutation(root,oldConfig,oldReceipt,"candidate-history-name-conflict",f->row(f,"excluded",0).put("name",row(f,"candidates",0).get("name")));
        mutation(root,oldConfig,oldReceipt,"historical-drop",f->a(f.projection.get("excluded")).remove(8));
        mutation(root,oldConfig,oldReceipt,"historical-wrong-reference",f->row(f,"excluded",0).put("reference","teacher-row-184"));
        mutation(root,oldConfig,oldReceipt,"historical-multirow-range",f->o(row(f,"excluded",0).get("source")).put("range","A185:F186"));
        mutation(root,oldConfig,oldReceipt,"historical-wrong-sheet",f->o(row(f,"excluded",0).get("source")).put("sheet","SYNTHETIC WRONG"));
        mutation(root,oldConfig,oldReceipt,"old-mapping-drop",f->a(f.projection.get("existingMappings")).remove(6));
        mutation(root,oldConfig,oldReceipt,"old-mapping-account-mismatch",f->row(f,"existingMappings",0).put("accountId",9999));
        mutation(root,oldConfig,oldReceipt,"old-mapping-person-mismatch",f->row(f,"existingMappings",0).put("personCode","imp_p_"+"f".repeat(32)));
        mutation(root,oldConfig,oldReceipt,"old-mapping-duplicate-account",f->row(f,"existingMappings",1).put("accountId",row(f,"existingMappings",0).get("accountId")));
        mutation(root,oldConfig,oldReceipt,"old-mapping-reference-mismatch",f->row(f,"existingMappings",0).put("reference","teacher-row-184"));
        mutation(root,oldConfig,oldReceipt,"old-receipt-drop",f->a(f.receipt.get("rows")).remove(6));
        mutation(root,oldConfig,oldReceipt,"old-receipt-summary",f->o(f.receipt.get("summary")).put("candidates",184));
        mutation(root,oldConfig,oldReceipt,"old-receipt-action-summary",f->o(a(f.receipt.get("rows")).get(0)).put("action","LINK_EXISTING"));
        mutation(root,oldConfig,oldReceipt,"old-receipt-invalid-action",f->o(a(f.receipt.get("rows")).get(0)).put("action","ACTIVATE"));
        mutation(root,oldConfig,oldReceipt,"old-receipt-history-reference",f->o(a(f.receipt.get("rows")).get(0)).put("reference","teacher-row-185"));
        mutation(root,oldConfig,oldReceipt,"old-receipt-unsafe-id",f->o(a(f.receipt.get("rows")).get(0)).put("accountId",9007199254740992L));
        mutation(root,oldConfig,oldReceipt,"old-receipt-publish",f->f.receipt.put("permissionsPublished",true));
        mutation(root,oldConfig,oldReceipt,"old-receipt-activation",f->f.receipt.put("accountsActivated",true));
        mutation(root,oldConfig,oldReceipt,"decision-extra-home",f->o(f.decision.get("explicitHomeMapping")).put("group-main-row-11",m()));
        mutation(root,oldConfig,oldReceipt,"decision-different-home",f->o(o(f.decision.get("explicitHomeMapping")).get("group-main-row-10")).put("targetOrganizationCode","SYNTHETIC_OTHER"));
        mutation(root,oldConfig,oldReceipt,"decision-wrong-display",f->o(o(f.decision.get("explicitHomeMapping")).get("group-main-row-3")).put("targetDisplayName","总经理室"));
        mutation(root,oldConfig,oldReceipt,"decision-wrong-label",f->o(o(f.decision.get("explicitHomeMapping")).get("group-main-row-3")).put("sourceLabel","总经理室"));
        mutation(root,oldConfig,oldReceipt,"decision-wrong-id",f->f.decision.put("decisionId","SYNTHETIC UNAPPROVED"));
        mutation(root,oldConfig,oldReceipt,"projection-wrong-original-hash",f->f.projection.put("groupsSha256","f".repeat(64)));
        mutation(root,oldConfig,oldReceipt,"unknown-projection-key",f->f.projection.put("activateAccounts",true));
        mutation(root,oldConfig,oldReceipt,"unknown-candidate-key",f->row(f,"candidates",0).put("accountId",999));
        mutation(root,oldConfig,oldReceipt,"unknown-source-key",f->o(row(f,"candidates",0).get("source")).put("path","SYNTHETIC PRIVATE PATH"));
        mutation(root,oldConfig,oldReceipt,"unknown-excluded-key",f->row(f,"excluded",0).put("active",true));
        mutation(root,oldConfig,oldReceipt,"unknown-mapping-key",f->row(f,"existingMappings",0).put("password","SYNTHETIC PRIVATE"));
        mutation(root,oldConfig,oldReceipt,"unknown-decision-key",f->f.decision.put("role","admin"));
        mutation(root,oldConfig,oldReceipt,"unknown-home-key",f->o(o(f.decision.get("explicitHomeMapping")).get("group-main-row-3")).put("publish",true));
        mutation(root,oldConfig,oldReceipt,"unknown-receipt-key",f->f.receipt.put("password","SYNTHETIC PRIVATE"));
        mutation(root,oldConfig,oldReceipt,"unknown-summary-key",f->o(f.receipt.get("summary")).put("activated",0));
        mutation(root,oldConfig,oldReceipt,"unknown-receipt-row-key",f->o(a(f.receipt.get("rows")).get(0)).put("name","SYNTHETIC PRIVATE"));

        // Each configured file is rechecked independently; restoring bytes repairs only the source.
        for(String component:List.of("groupsOriginal","teacherOriginal","projection","decision","originalReceipt")){
            FilePin pin=(FilePin)config.getClass().getMethod(component).invoke(config);byte[] original=Files.readAllBytes(pin.path());
            try{Files.writeString(pin.path(),"SYNTHETIC PRIVATE TAMPER");fails(()->OrganizationManagementSupplementSource.load(config),"tampered "+component);}finally{Files.write(pin.path(),original);}
        }
        fails(()->OrganizationManagementSupplementSource.load(with(config,"decision",config.projection())),"duplicate path");
        Path hard=root.resolve("projection-hard-link");Files.createLink(hard,config.projection().path());
        fails(()->OrganizationManagementSupplementSource.load(with(config,"decision",new FilePin(hard,config.projection().sha256()))),"same inode alias");
        Path symbolic=root.resolve("projection-symbolic-link");Files.createSymbolicLink(symbolic,config.projection().path());
        fails(()->OrganizationManagementSupplementSource.load(with(config,"projection",new FilePin(symbolic,config.projection().sha256()))),"symbolic link pin");
        fails(()->OrganizationManagementSupplementSource.load(with(config,"projection",new FilePin(root,"a".repeat(64)))),"directory pin");
        fails(()->OrganizationManagementSupplementSource.load(with(config,"projection",new FilePin(root.resolve("missing"),"a".repeat(64)))),"missing file pin");
        fails(()->OrganizationManagementSupplementSource.load(with(config,"projection",new FilePin(config.projection().path(),"a".repeat(64)))),"wrong file hash");
        for(String field:List.of("projection","decision","originalReceipt")){
            Path invalid=root.resolve(field+"-invalid-utf8.json");Files.write(invalid,new byte[]{(byte)0xc3,0x28});
            fails(()->OrganizationManagementSupplementSource.load(withJson(config,field,new FilePin(invalid,hash(invalid)))),field+" invalid UTF8");
            FilePin original=(FilePin)config.getClass().getMethod(field).invoke(config);String text=Files.readString(original.path());
            Map<String,Object> parsed=o(Json.parse(text));String first=parsed.keySet().iterator().next();
            Path duplicate=root.resolve(field+"-duplicate.json");Files.writeString(duplicate,"{"+Json.write(first)+":"+Json.write(parsed.get(first))+","+text.substring(1));
            fails(()->OrganizationManagementSupplementSource.load(withJson(config,field,new FilePin(duplicate,hash(duplicate)))),field+" duplicate valid key");
        }
        Path nested=root.resolve("duplicate-nested.json");Files.writeString(nested,Files.readString(config.projection().path()).replace("\"name\":\"SYNTHETIC SUPPLEMENT A\"","\"name\":\"SYNTHETIC SUPPLEMENT A\",\"name\":\"SYNTHETIC SUPPLEMENT A\""));
        fails(()->OrganizationManagementSupplementSource.load(with(config,"projection",new FilePin(nested,hash(nested)))),"nested duplicate key");
        Path malformed=root.resolve("malformed.json");Files.writeString(malformed,"{\"SYNTHETIC PRIVATE\":");
        fails(()->OrganizationManagementSupplementSource.load(with(config,"projection",new FilePin(malformed,hash(malformed)))),"malformed JSON");
        Path oversized=root.resolve("oversized.json");try(var file=new java.io.RandomAccessFile(oversized.toFile(),"rw")){file.setLength(2L*1024*1024+1);}
        fails(()->OrganizationManagementSupplementSource.load(with(config,"projection",new FilePin(oversized,"a".repeat(64)))),"bounded JSON size");
        fails(()->OrganizationManagementSupplementSource.load(null),"null config");
    }
    private static void mutation(Path root,OrganizationAccountImportSource.Config oldConfig,Map<String,Object> oldReceipt,String name,Mutation change)throws Exception{Fixture fixture=new Fixture(root.resolve(name),oldConfig,oldReceipt);change.apply(fixture);Config config=fixture.save();fails(()->OrganizationManagementSupplementSource.load(config),name);}
    private static void fails(Attempt attempt,String label)throws Exception{try{attempt.run();throw new AssertionError("Expected source rejection: "+label);}catch(IllegalArgumentException e){ok(e.getCause()==null&&"补充账号来源核验失败，请重新核对可信材料。".equals(e.getMessage()),label+" sanitized rejection: "+e.getMessage());}}
    private static void immutable(Attempt attempt,String label)throws Exception{try{attempt.run();throw new AssertionError(label);}catch(UnsupportedOperationException e){checks++;}}
    private static void ok(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static Map<String,Object> row(Fixture f,String list,int index){return o(a(f.projection.get(list)).get(index));}
    private static Config with(Config c,String component,Object value)throws Exception{var fields=Config.class.getRecordComponents();Class<?>[] types=new Class<?>[fields.length];Object[] values=new Object[fields.length];for(int i=0;i<fields.length;i++){types[i]=fields[i].getType();values[i]=fields[i].getName().equals(component)?value:fields[i].getAccessor().invoke(c);}return (Config)Config.class.getDeclaredConstructor(types).newInstance(values);}
    /** Keep cross-document hashes consistent so malformed decision/receipt tests reach their parsers. */
    private static Config withJson(Config c,String component,FilePin pin)throws Exception{
        Config updated=with(c,component,pin);if(component.equals("projection"))return updated;
        Map<String,Object> projection=o(Json.parse(Files.readString(c.projection().path())));
        projection.put(component.equals("decision")?"decisionSha256":"originalReceiptSha256",pin.sha256());
        Path file=pin.path().resolveSibling(pin.path().getFileName()+"-projection.json");Files.writeString(file,Json.write(projection),StandardCharsets.UTF_8);
        return with(updated,"projection",new FilePin(file,hash(file)));
    }
    private static String hash(Path file)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
    private static String hashText(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    @SuppressWarnings("unchecked") private static Map<String,Object> o(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private static List<Object> a(Object value){return (List<Object>)value;}
    private static Map<String,Object> m(Object... values){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;}
    private static List<Object> l(Object... values){return new ArrayList<>(Arrays.asList(values));}
    private static Map<String,Object> copy(Map<String,Object> value){return o(Json.parse(Json.write(value)));}
}
