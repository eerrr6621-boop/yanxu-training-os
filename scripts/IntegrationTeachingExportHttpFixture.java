package com.training;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/** Test-only legacy/corruption fixture. Normal workflow and reviewed facts are created through real HTTP. */
public final class IntegrationTeachingExportHttpFixture {
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("Expected isolated port, inspect, corrupt or xlsx PATH");
        Path root = Path.of(required("integration.teaching.export.root")).toRealPath();
        Path data = Path.of(required("data.dir")).toRealPath();
        if (!root.getFileName().toString().startsWith("yanxu-teaching-export-http-") || !data.getParent().equals(root)
                || !"127.0.0.1".equals(System.getProperty("bind.address")) || Boolean.getBoolean("bootstrap.demo")
                || !required("bootstrap.admin.password").matches("[a-f0-9]{48}"))
            throw new IllegalArgumentException("Explicit isolated loopback fixture directory required");
        if (args[0].equals("xlsx")) {
            if (args.length != 2) throw new IllegalArgumentException("Expected XLSX path");
            System.out.println(Json.write(inspectXlsx(root, Path.of(args[1])))); return;
        }
        if (args.length != 1) throw new IllegalArgumentException("Unexpected second argument");
        if (args[0].equals("inspect")) {
            Map<String,Object> result = new TreeMap<>();
            for (Map<String,Object> table : Db.query("SELECT TABLE_NAME AS name FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")) {
                String name = table.get("name").toString();
                if (!name.matches("[A-Z0-9_]+")) throw new IllegalStateException("Unexpected table identifier");
                List<String> rows = new ArrayList<>();
                for (Map<String,Object> row : Db.query("SELECT * FROM " + name)) rows.add(Json.write(new TreeMap<>(row)));
                Collections.sort(rows);
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.write(rows).getBytes(StandardCharsets.UTF_8)));
                result.put(name, Map.of("count", rows.size(), "sha256", hash));
            }
            System.out.println(Json.write(result)); Db.get().close(); return;
        }
        if (args[0].equals("corrupt")) {
            // Deliberate offline faults only after the script has created genuine HTTP verified sources.
            long invalid = record("SYNTHETIC CORRUPT REVISION"), moved = record("SYNTHETIC DATE DRIFT");
            Db.transaction(() -> {
                Db.exec("UPDATE m05_fact_revisions SET actor_code='P999' WHERE dispatch_id=? AND revision=(SELECT revision FROM m05_delivery_facts WHERE dispatch_id=?)", invalid, invalid);
                Db.exec("UPDATE dispatches SET teach_date='2025-02-18' WHERE id=?", moved);
                return null;
            });
            Db.get().close(); return;
        }
        try (var entries = Files.list(data)) {
            if (entries.findAny().isPresent()) throw new IllegalArgumentException("Fresh empty fixture data required");
        }
        Db.init();
        if (OrganizationAccessStore.configuration() != null || Db.count("m05_delivery_facts") != 0)
            throw new IllegalStateException("Unexpected identity or fact seed");
        long teacher = Db.insert("INSERT INTO teachers(name,status,fee_rate,base_province,base_city) VALUES(?,'在库',999,'浙江','杭州')", "SYNTHETIC HISTORICAL TEACHER");
        long legacy = project(null, "SYNTHETIC UNMIGRATED REPORT PROJECT");
        dispatch(legacy, teacher, "SYNTHETIC UNMIGRATED HOURS", "2025-01-10", 999);
        Db.exec("INSERT INTO fees(project_id,teacher_id,hours,rate,amount,status,pay_date,remark) VALUES(?,?,1,765432.1,765432.1,'已发放','2024-12-25','PRIVATE_TEACHING_FEE')", legacy, teacher);
        long demand = Db.insert("INSERT INTO demands(title,unit,hours,status) VALUES(?,'SYNTHETIC',1,'进行中')", "SYNTHETIC REPORT HISTORY");
        long controlled = project(demand, "SYNTHETIC CONTROLLED REPORT HISTORY");
        Db.exec("INSERT INTO workflow_demands VALUES(?,1,1,FALSE,'direct','001','P1','{}','{}','SYNTHETIC-HISTORY')", demand);
        Db.exec("INSERT INTO workflow_acceptances VALUES(?,?,'900','P4',1,'2025-01-01T00:00:00Z')", demand, controlled);
        dispatch(controlled, teacher, "SYNTHETIC HISTORICAL FACT MISSING", "2025-01-15", 999);
        dispatch(controlled, teacher, "SYNTHETIC HISTORICAL DATE UNKNOWN", "", 999);
        dispatch(controlled, teacher, "SYNTHETIC HISTORICAL FUTURE", "2099-01-01", 999);
        Main.main(args);
    }
    /** Parse the downloaded OOXML independently with JDK ZIP/XML, without opening links or macros. */
    private static Map<String,Object> inspectXlsx(Path root,Path input) throws Exception {
        Path file=input.toRealPath();
        if(!file.getParent().equals(root)||!file.getFileName().toString().endsWith(".xlsx"))
            throw new IllegalArgumentException("Downloaded XLSX must be in this isolated test root");
        Map<String,byte[]> entries=new LinkedHashMap<>();
        try(var zip=new java.util.zip.ZipInputStream(Files.newInputStream(file))) {
            java.util.zip.ZipEntry entry; int total=0;
            while((entry=zip.getNextEntry())!=null) {
                String name=entry.getName();
                if(entry.isDirectory()||name.startsWith("/")||name.contains("..")||entries.containsKey(name))
                    throw new IllegalArgumentException("Invalid or duplicate ZIP entry");
                byte[] bytes=zip.readNBytes(8*1024*1024+1); total+=bytes.length;
                if(bytes.length>8*1024*1024||total>32*1024*1024)throw new IllegalArgumentException("Unexpected XLSX size");
                entries.put(name,bytes);
            }
        }
        for(String required:List.of("[Content_Types].xml","_rels/.rels","xl/workbook.xml","xl/_rels/workbook.xml.rels"))
            if(!entries.containsKey(required))throw new IllegalArgumentException("Missing OOXML part: "+required);
        Map<String,org.w3c.dom.Document> documents=new LinkedHashMap<>();
        StringBuilder allXml=new StringBuilder(); int formulas=0,external=0;
        for(var entry:entries.entrySet()) if(entry.getKey().endsWith(".xml")||entry.getKey().endsWith(".rels")) {
            var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            var doc=factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(entry.getValue()));
            documents.put(entry.getKey(),doc);allXml.append(new String(entry.getValue(),StandardCharsets.UTF_8));
            formulas+=doc.getElementsByTagNameNS("*","f").getLength();
            var rels=doc.getElementsByTagNameNS("*","Relationship");
            for(int i=0;i<rels.getLength();i++)if("External".equals(((org.w3c.dom.Element)rels.item(i)).getAttribute("TargetMode")))external++;
        }
        List<String> shared=new ArrayList<>();
        if(documents.containsKey("xl/sharedStrings.xml")) {
            var nodes=documents.get("xl/sharedStrings.xml").getElementsByTagNameNS("*","si");
            for(int i=0;i<nodes.getLength();i++)shared.add(nodes.item(i).getTextContent());
        }
        Map<String,String> targets=new HashMap<>();
        var rels=documents.get("xl/_rels/workbook.xml.rels").getElementsByTagNameNS("*","Relationship");
        for(int i=0;i<rels.getLength();i++){var e=(org.w3c.dom.Element)rels.item(i);targets.put(e.getAttribute("Id"),e.getAttribute("Target"));}
        List<Map<String,Object>> sheets=new ArrayList<>();
        var names=documents.get("xl/workbook.xml").getElementsByTagNameNS("*","sheet");
        for(int i=0;i<names.getLength();i++) {
            var sheet=(org.w3c.dom.Element)names.item(i);String target=targets.get(sheet.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships","id"));
            if(target==null)throw new IllegalArgumentException("Sheet relationship missing");
            String part=target.startsWith("/")?target.substring(1):Path.of("xl").resolve(target).normalize().toString();
            var doc=documents.get(part);if(doc==null)throw new IllegalArgumentException("Sheet XML missing: "+part);
            List<List<Map<String,Object>>> rows=new ArrayList<>();var rawRows=doc.getElementsByTagNameNS("*","row");
            for(int j=0;j<rawRows.getLength();j++) {
                List<Map<String,Object>> row=new ArrayList<>();var cells=((org.w3c.dom.Element)rawRows.item(j)).getElementsByTagNameNS("*","c");
                for(int k=0;k<cells.getLength();k++) {
                    var cell=(org.w3c.dom.Element)cells.item(k);String type=cell.getAttribute("t"),value="";
                    var v=cell.getElementsByTagNameNS("*",type.equals("inlineStr")?"is":"v");
                    if(v.getLength()>0)value=v.item(0).getTextContent();
                    if(type.equals("s"))value=shared.get(Integer.parseInt(value));
                    row.add(Map.of("reference",cell.getAttribute("r"),"type",type,"value",value));
                }
                rows.add(row);
            }
            sheets.add(Map.of("name",sheet.getAttribute("name"),"state",sheet.getAttribute("state"),"rows",rows));
        }
        return Map.of("entries",new ArrayList<>(entries.keySet()),"sheets",sheets,"formula_count",formulas,
                "external_relationships",external,"all_xml",allXml.toString());
    }
    private static long record(String subject) throws Exception {
        List<Map<String,Object>> rows = Db.query("SELECT d.id FROM dispatches d JOIN m05_delivery_facts f ON f.dispatch_id=d.id WHERE d.subject=? AND d.status='已完成' AND f.revision=2", subject);
        if (rows.size() != 1) throw new IllegalStateException("Expected one genuine HTTP reviewed synthetic source");
        return ((Number)rows.get(0).get("id")).longValue();
    }
    private static long project(Long demand, String title) throws Exception {
        return Db.insert("INSERT INTO projects(demand_id,title,unit,hours,amount,start_date,end_date,owner,participant_count,delivery_mode,status) VALUES(?,?,'SYNTHETIC',1,0,'2025-01-01','2025-01-31','SYNTHETIC',1,'线上','进行中')", demand, title);
    }
    private static void dispatch(long project, long teacher, String subject, String day, int hours) throws Exception {
        Db.insert("INSERT INTO dispatches(project_id,teacher_id,subject,teach_date,hours,status,material_status) VALUES(?,?,?,?,?,'已完成','已就绪')", project, teacher, subject, day, hours);
    }
    private static String required(String name) {
        String value = System.getProperty(name, "");
        if (value.isBlank()) throw new IllegalArgumentException("Missing isolated fixture setting: " + name);
        return value;
    }
}
