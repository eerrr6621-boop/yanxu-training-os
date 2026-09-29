package com.training;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.util.*;

/** Offline-only inspection and one historical-format conversion of an HTTP-created synthetic summary. */
public final class IntegrationSummaryDeliveryHttpFixture {
    public static void main(String[] args)throws Exception {
        if(args.length!=1||!Set.of("inspect","legacy-source").contains(args[0]))throw new IllegalArgumentException("Expected inspect or legacy-source");
        Path root=Path.of(required("integration.summary.delivery.root")).toRealPath(),data=Path.of(required("data.dir")).toRealPath();
        if(!root.getFileName().toString().startsWith("yanxu-summary-delivery-http-")||!data.getParent().equals(root)
                ||!"127.0.0.1".equals(System.getProperty("bind.address"))||Boolean.getBoolean("bootstrap.demo")
                ||!required("bootstrap.admin.password").matches("[a-f0-9]{48}")||!Files.isRegularFile(data.resolve("training.mv.db")))
            throw new IllegalArgumentException("Existing isolated synthetic loopback database required");
        if(args[0].equals("inspect")) {
            Map<String,Object> tables=new TreeMap<>();
            for(Map<String,Object> row:Db.query("SELECT TABLE_NAME name FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")) {
                String name=row.get("name").toString();if(!name.matches("[A-Z0-9_]+"))throw new IllegalStateException("Unexpected table identifier");
                List<String> rows=new ArrayList<>();for(Map<String,Object> record:Db.query("SELECT * FROM "+name))rows.add(Json.write(new TreeMap<>(record)));Collections.sort(rows);
                tables.put(name,Map.of("count",rows.size(),"sha256",hash(Json.write(rows))));
            }
            System.out.println(Json.write(tables));
        } else {
            List<Map<String,Object>> rows=Db.query("SELECT r.* FROM m08_summary_revisions r JOIN m08_summary_heads h ON h.project_id=r.project_id JOIN projects p ON p.id=r.project_id WHERE p.title='SYNTHETIC SUMMARY DELIVERY LEGACY' AND h.version=1 AND r.revision=1");
            if(rows.size()!=1)throw new IllegalStateException("Expected one fresh HTTP-created legacy compatibility target");
            Map<String,Object> row=rows.get(0),sources=object(Json.parse(row.get("sources_json").toString()));
            if(!"CREATE".equals(row.get("operation"))||!"0".repeat(64).equals(row.get("previous_hash")))throw new IllegalStateException("Only isolated genesis revision may be converted");
            sources.put("delivery",map("status","UNAVAILABLE","reason","M05_SNAPSHOT_NOT_CONNECTED","value",null));
            sources.remove("source_version");sources.put("source_version",hash(canonical(sources)));
            Map<String,Object> record=map("format",sources.get("format"),"project_id",row.get("project_id"),"revision",row.get("revision"),"operation",row.get("operation"),
                "actor_code",row.get("actor_code"),"account_id",row.get("account_id"),"config_version",row.get("config_version"),"saved_at",row.get("saved_at"),
                "content",Json.parse(row.get("content_json").toString()),"sources",sources,"previous_hash",row.get("previous_hash"));
            String hash=hash(canonical(record));
            Db.transaction(()->{
                Db.exec("UPDATE m08_summary_revisions SET sources_json=?,revision_hash=? WHERE project_id=? AND revision=1",canonical(sources),hash,row.get("project_id"));
                Db.exec("UPDATE m08_summary_heads SET head_hash=? WHERE project_id=? AND version=1",hash,row.get("project_id"));return null;
            });
            System.out.println("{\"converted_synthetic_genesis\":true}");
        }
        Db.get().close();
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){return new LinkedHashMap<>((Map<String,Object>)value);}
    private static Map<String,Object> map(Object...pairs){Map<String,Object>m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
    private static Object sorted(Object value){if(value instanceof Map<?,?> m){Map<String,Object>out=new TreeMap<>();m.forEach((k,v)->out.put(k.toString(),sorted(v)));return out;}if(value instanceof List<?> l)return l.stream().map(IntegrationSummaryDeliveryHttpFixture::sorted).toList();if(value instanceof Number n)return new BigDecimal(n.toString()).stripTrailingZeros();return value;}
    private static String canonical(Object value){return Json.write(sorted(value));}
    private static String hash(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    private static String required(String name){String value=System.getProperty(name,"");if(value.isBlank())throw new IllegalArgumentException("Missing isolated setting: "+name);return value;}
}
