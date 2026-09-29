package com.training;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Scripts-only historical sources and generated XLSX. Never reads user questionnaires or writes preview results. */
public final class IntegrationSurveyHttpFixture {
    private static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String OFFICE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String PACKAGE = "http://schemas.openxmlformats.org/package/2006/relationships";
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected port or inspect");
        Path root = Path.of(required("integration.survey.root")).toRealPath(), data = Path.of(required("data.dir")).toRealPath();
        if (!root.getFileName().toString().startsWith("yanxu-survey-http-") || !data.getParent().equals(root)
                || !"127.0.0.1".equals(System.getProperty("bind.address")) || Boolean.getBoolean("bootstrap.demo")
                || !required("bootstrap.admin.password").matches("[a-f0-9]{48}")) throw new IllegalArgumentException("Isolated synthetic fixture required");
        if (args[0].equals("inspect")) {
            Map<String,Object> result = new TreeMap<>();
            for (Map<String,Object> table : Db.query("SELECT TABLE_NAME AS name FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")) {
                String name=table.get("name").toString(); if(!name.matches("[A-Z0-9_]+")) throw new IllegalStateException("Unexpected identifier");
                List<String> rows=new ArrayList<>();for(Map<String,Object> row:Db.query("SELECT * FROM "+name))rows.add(Json.write(new TreeMap<>(row)));Collections.sort(rows);
                result.put(name,Map.of("count",rows.size(),"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.write(rows).getBytes(StandardCharsets.UTF_8)))));
            }
            System.out.println(Json.write(result));Db.get().close();return;
        }
        try(var files=Files.list(data)){if(files.findAny().isPresent())throw new IllegalArgumentException("Fresh empty data required");}
        Db.init();
        if(OrganizationAccessStore.configuration()!=null)throw new IllegalStateException("Unexpected identity seed");
        project(null,"SYNTHETIC UNMIGRATED SURVEY PROJECT");
        history("SYNTHETIC MISMATCHED SURVEY SOURCE",1,1,false,true);
        history("SYNTHETIC STALE SURVEY SOURCE",2,1,false,false);
        history("SYNTHETIC DRAFT SURVEY SOURCE",1,1,true,false);
        Map<String,String> clean=parts(); Files.write(root.resolve("clean.xlsx"),zip(clean,-1));
        int maximum=SurveySummaryImportsResponses.MAX_BYTES;
        byte[] padded=zip(clean,maximum-zip(clean,0).length);if(padded.length!=maximum)throw new AssertionError("Exact decoded boundary");
        Files.write(root.resolve("maximum.xlsx"),padded);
        Map<String,String> traversal=new LinkedHashMap<>(clean);traversal.put("../SYNTHETIC_PRIVATE.txt","SYNTHETIC_PRIVATE_PAYLOAD");Files.write(root.resolve("traversal.xlsx"),zip(traversal,-1));
        Map<String,String> external=new LinkedHashMap<>(clean);external.compute("xl/_rels/workbook.xml.rels",(key,value)->value.replace("Target=\"worksheets/scores.xml\"","Target=\"https://example.invalid/SYNTHETIC_PRIVATE\" TargetMode=\"External\""));Files.write(root.resolve("external.xlsx"),zip(external,-1));
        Map<String,String> bomb=new LinkedHashMap<>(clean);bomb.put("synthetic-padding.txt","0".repeat(SurveySummaryImportsResponses.MAX_EXPANDED_BYTES+1));Files.write(root.resolve("expanded-limit.xlsx"),zip(bomb,-1));
        Main.main(args);
    }
    private static void history(String title,int revision,int accepted,boolean draft,boolean mismatch)throws Exception{
        long demand=Db.insert("INSERT INTO demands(title,unit,hours,status) VALUES(?,'SYNTHETIC',1,'进行中')",title);
        long p=project(mismatch?null:demand,title);
        Db.exec("INSERT INTO workflow_demands VALUES(?,1,?,?, 'direct','001','P1','{}','{}','SYNTHETIC-HISTORY')",demand,revision,draft);
        Db.exec("INSERT INTO workflow_acceptances VALUES(?,?,'900','P4',?,'2025-01-01T00:00:00Z')",demand,p,accepted);
    }
    private static long project(Long demand,String title)throws Exception{return Db.insert("INSERT INTO projects(demand_id,title,unit,hours,amount,status) VALUES(?,?,'SYNTHETIC',1,0,'进行中')",demand,title);}
    private static String inline(String ref,String value){return "<c r=\""+ref+"\" t=\"inlineStr\"><is><t>"+value.replace("&","&amp;").replace("<","&lt;").replace("\"","&quot;")+"</t></is></c>";}
    private static Map<String,String> parts(){
        StringBuilder sheet=new StringBuilder("<worksheet xmlns=\"").append(MAIN).append("\"><sheetData><row r=\"1\">").append(inline("A1","SYNTHETIC RECORD MARKER"));
        for(int q=0;q<10;q++)sheet.append(inline((char)('B'+q)+"1",SurveySummaryImportsResponses.QUESTION_LABELS.get(q)));
        sheet.append("</row>");
        // Four synthetic response records: q1 demonstrates known zero, blank and invalid; all other questions are eight.
        for(int r=2;r<=5;r++){
            sheet.append("<row r=\"").append(r).append("\">").append(inline("A"+r,"SYNTHETIC_PRIVATE_PERSON_"+r));
            if(r==2||r==3)sheet.append("<c r=\"B").append(r).append("\"><v>").append(r==2?"0":"10").append("</v></c>");
            if(r==5)sheet.append(inline("B5","SYNTHETIC_PRIVATE_INVALID_SCORE"));
            for(int q=2;q<=10;q++)sheet.append("<c r=\"").append((char)('A'+q)).append(r).append("\"><v>8</v></c>");
            sheet.append("</row>");
        }
        sheet.append("</sheetData></worksheet>");
        Map<String,String> parts=new LinkedHashMap<>();
        parts.put("[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/></Types>");
        parts.put("_rels/.rels","<Relationships xmlns=\""+PACKAGE+"\"><Relationship Id=\"root\" Type=\""+OFFICE+"/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
        parts.put("xl/workbook.xml","<workbook xmlns=\""+MAIN+"\" xmlns:r=\""+OFFICE+"\"><sheets><sheet name=\""+SurveySummaryImportsResponses.SHEET_NAME+"\" sheetId=\"1\" r:id=\"scores\"/></sheets></workbook>");
        parts.put("xl/_rels/workbook.xml.rels","<Relationships xmlns=\""+PACKAGE+"\"><Relationship Id=\"scores\" Type=\""+OFFICE+"/worksheet\" Target=\"worksheets/scores.xml\"/></Relationships>");
        parts.put("xl/worksheets/scores.xml",sheet.toString());return parts;
    }
    private static byte[] zip(Map<String,String> parts,int padding)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes,StandardCharsets.UTF_8)){
            for(var part:parts.entrySet()){ZipEntry e=new ZipEntry(part.getKey());e.setTime(0);zip.putNextEntry(e);zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
            if(padding>=0){byte[] zero=new byte[padding];CRC32 crc=new CRC32();crc.update(zero);ZipEntry e=new ZipEntry("synthetic-safe-padding.txt");e.setTime(0);e.setMethod(ZipEntry.STORED);e.setSize(padding);e.setCompressedSize(padding);e.setCrc(crc.getValue());zip.putNextEntry(e);zip.write(zero);zip.closeEntry();}
        }return bytes.toByteArray();
    }
    private static String required(String name){String value=System.getProperty(name,"");if(value.isBlank())throw new IllegalArgumentException("Missing isolated fixture setting: "+name);return value;}
}
