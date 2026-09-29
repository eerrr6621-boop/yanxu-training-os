package com.training;

import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static com.training.OrganizationAccountImportSource.*;

/** Creates only fresh synthetic materials and a caller-owned isolated H2, then exits before Main. */
public final class ManagementSupplementHttpFixture {
    static final String PASSWORD = "SYNTHETIC-HTTP-SUPPLEMENT-ONLY-20260924";
    static final String OLD_BATCH = "synthetic-account-import";

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toRealPath();
        if (!root.getFileName().toString().startsWith("yanxu-management-supplement-http-test.")) throw new IllegalArgumentException("Dedicated synthetic root required");
        Path data = root.resolve("data"); Files.createDirectory(data);
        System.setProperty("data.dir", data.toString());
        System.setProperty("bootstrap.demo", "false");
        System.setProperty("bootstrap.admin.password", PASSWORD);
        try {
            Db.init();
            Auth.Session admin = Auth.get(Auth.login("admin", PASSWORD));
            if (admin == null) throw new AssertionError("Real synthetic administrator login required");
            long linked = Db.insert("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,?,1)",
                    "synthetic-existing", Auth.hash(PASSWORD), "SYNTHETIC EXISTING", "manager");
            Db.insert("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,?,1)",
                    "synthetic-viewer", Auth.hash(PASSWORD), "SYNTHETIC VIEWER", "viewer");
            OrganizationAccountImportSourceTest.Fixture old = new OrganizationAccountImportSourceTest.Fixture(root.resolve("old-source"));
            object(old.cp.get("teacher")).put("historyRows", List.of(12,20));
            List<Object> histories = new ArrayList<>(), projected = new ArrayList<>();
            for (int row=12; row<=20; row++) {
                histories.add(map("row",row,"name","SYNTHETIC EXCLUDED "+row,"organization","SYNTHETIC BRANCH A","account_disposition","DO_NOT_CREATE",
                        "source",map("path",old.originals.get(0).path().toString(),"sheet","Synthetic Teachers","source_row",row,"range","A"+row+":F"+row)));
                projected.add(map("source",map("sheet","Synthetic Teachers","range","A"+row+":F"+row,"row",row),"disposition","DO_NOT_CREATE"));
            }
            old.batch.put("historical_exclusions", histories); old.preview.put("historicalExclusions", projected);
            object(old.preview.get("summary")).put("historicalExcluded",9);
            Config original = old.save();
            OrganizationAccountImport importer = new OrganizationAccountImport(original);
            Map<String,Object> preview = importer.preview(admin);
            List<Object> decisions = new ArrayList<>();
            for (Object row : (List<?>)preview.get("rows")) decisions.add(map("reference",object(row).get("reference"),"action","CREATE_PENDING","accountId",null,"accountProof",null,"reviewed",true));
            Map<String,Object> receipt = importer.commit(admin,map("expectedRevision",preview.get("revision"),"sourceFingerprint",preview.get("sourceFingerprint"),"reviewToken",preview.get("reviewToken"),"decisions",decisions));
            var fixture = new ManagementSupplementSourceTest.Fixture(root.resolve("supplement-source"), original, receipt);
            var supplemental = fixture.save();
            Map<String,Object> pins = map("groupsOriginal",pin(supplemental.groupsOriginal()),"teacherOriginal",pin(supplemental.teacherOriginal()),
                    "projection",pin(supplemental.projection()),"decision",pin(supplemental.decision()),"originalReceipt",pin(supplemental.originalReceipt()));
            Files.writeString(root.resolve("manifest.json"), Json.write(map("schema","M01-MANAGEMENT-SUPPLEMENT-MANIFEST-v1","batchKey",supplemental.batchKey(),"pins",pins)));
            Files.writeString(root.resolve("original-manifest.json"),Json.write(originalManifest(original)));
            long oldId=((Number)object(((List<?>)receipt.get("rows")).get(0)).get("accountId")).longValue();
            Files.writeString(root.resolve("fixture.json"),Json.write(map("linkedId",linked,"oldId",oldId,"oldBatch",original.batchKey(),"sourceFile",supplemental.groupsOriginal().path().toString())));
            Files.writeString(root.resolve("protected-before.json"),protectedState(Db.get(),original.batchKey(),linked));
            System.out.println("Prepared synthetic HTTP fixture: real original intake, independent supplemental pins.");
        } finally { Db.exec("SHUTDOWN"); }
    }

    static String protectedState(Connection db,String oldBatch,long linked) throws Exception {
        return Json.write(map("oldBatch",query(db,"SELECT * FROM organization_account_import_batches WHERE batch_key=?",oldBatch),
                "oldPeople",query(db,"SELECT * FROM organization_account_import_people WHERE batch_key=? ORDER BY source_reference",oldBatch),
                "oldUsers",query(db,"SELECT u.* FROM users u JOIN organization_account_import_people p ON p.account_id=u.id WHERE p.batch_key=? ORDER BY u.id",oldBatch),
                "linked",query(db,"SELECT * FROM users WHERE id=?",linked),"configuration",query(db,"SELECT * FROM organization_access_config ORDER BY id"),
                "teachers",query(db,"SELECT * FROM teachers ORDER BY id"),"teacherReceipts",query(db,"SELECT * FROM teacher_roster_import_batches ORDER BY batch_key")));
    }
    private static List<Object> query(Connection db,String sql,Object...values) throws Exception {
        List<Object> rows = new ArrayList<>();
        try (PreparedStatement statement=db.prepareStatement(sql)) {
            for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);
            try(ResultSet result=statement.executeQuery()) {
                while(result.next()) {Map<String,Object> row=new LinkedHashMap<>();for(int i=1;i<=result.getMetaData().getColumnCount();i++)row.put(result.getMetaData().getColumnLabel(i),result.getString(i));rows.add(row);}
            }
        }
        return rows;
    }
    private static Map<String,Object> originalManifest(Config c) {
        return map("schema","M01-SERVER-PIN-MANIFEST-v1","batchKey",c.batchKey(),"usage","Synthetic original intake retained",
                "pins",map("candidates",pin(c.candidates()),"candidatePolicy",pin(c.candidatePolicy()),"roleSource",pin(c.roleSource()),"authorization",pin(c.authorization()),
                        "audit",pin(c.audit()),"regionReference",pin(c.regionReference()),"scopeDecision",pin(c.scopeDecision()),"preparedPreview",pin(c.preparedPreview())),
                "originals",c.originals().stream().map(ManagementSupplementHttpFixture::pin).toList());
    }
    static Map<String,Object> pin(FilePin pin) { return map("path",pin.path().toString(),"sha256",pin.sha256()); }
    static String sha(Path file) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
    @SuppressWarnings("unchecked") static Map<String,Object> object(Object value) {return (Map<String,Object>)value;}
    static Map<String,Object> map(Object...values) {Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;}
}
