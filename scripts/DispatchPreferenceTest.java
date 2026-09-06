package com.training;

import java.nio.file.*;
import java.util.*;

/** Pure dispatch rules and a disposable legacy H2 migration. Never production data. */
public final class DispatchPreferenceTest {
    private static int passed;
    static void check(boolean value, String label) { if (!value) throw new AssertionError(label); passed++; }
    static Map<String,Object> region(String province, String city) {
        Map<String,Object> value = new LinkedHashMap<>(); value.put("base_province", province); value.put("base_city", city); return value;
    }
    static void invalid(String province, String city) {
        try { DispatchPreference.validateRegion(region(province, city), "base_province", "base_city", true); }
        catch (IllegalArgumentException expected) { passed++; return; }
        throw new AssertionError("invalid residence accepted: " + province + "/" + city);
    }
    static Map<String,Object> candidate(long id, double professional, double total, boolean local) {
        return Map.of("teacher_id", id, "professional_score", professional, "score", total,
                "dispatch_fit", Map.of("local_priority", local));
    }
    public static void main(String[] args) throws Exception {
        for (String missing : Arrays.asList("", " ", "　", "\n", "待定", "未知", "UNKNOWN", "n/a")) invalid("浙江", missing);
        invalid("浙江", "浙江省"); invalid("北京", "朝阳区"); invalid("浙江", "杭州、宁波");
        invalid("浙江", "杭州\u0000"); invalid("浙江", "杭\n州"); invalid("浙江", "a".repeat(65)); invalid("不存在省", "杭州");
        invalid("浙江", "南京"); invalid("浙江", "1234"); invalid("浙江", "杭\u202e州");
        for (Object bad : Arrays.asList(true, 1234, Map.of("name", "杭州"))) {
            Map<String,Object> value = region("浙江", "杭州"); value.put("base_city", bad);
            try { DispatchPreference.validateRegion(value, "base_province", "base_city", true); throw new AssertionError("non-string accepted"); }
            catch (IllegalArgumentException expected) { passed++; }
        }
        for (Map.Entry<String,Set<String>> entry : RegionDirectory.CITIES.entrySet()) {
            for (String place : entry.getValue()) {
                DispatchPreference.validateRegion(region(entry.getKey(), place), "base_province", "base_city", true);
                check(RegionDirectory.known(entry.getKey(), place), "bundled area accepted: " + place);
            }
        }
        Map<String,Object> normalized = region("　浙江省　", "　杭州市　");
        DispatchPreference.validateRegion(normalized, "base_province", "base_city", true);
        check(normalized.get("base_province").equals("浙江") && normalized.get("base_city").equals("杭州"), "normalize suffix/full-width blanks");
        DispatchPreference.validateRegion(region("北京市", "北京市"), "base_province", "base_city", true); passed++;
        DispatchPreference local = DispatchPreference.from(new LinkedHashMap<>(Map.of("training_province", "浙江", "training_city", "杭州", "training_mode", "线下", "training_period", "上午")), null);
        Map<String,Object> fit = local.describe(region("浙江省", "杭州市"), "2099-01-01");
        check(Boolean.TRUE.equals(fit.get("local_priority")), "same city preference");
        check(Boolean.FALSE.equals(fit.get("arrival_day_before")), "local morning no forced hotel");
        check(Boolean.FALSE.equals(fit.get("transport_verified")), "no fictional transport verification");
        fit = local.describe(region("江苏", "南京"), "2099-01-01");
        check(Boolean.TRUE.equals(fit.get("arrival_day_before")), "remote morning arrival buffer");
        check(Boolean.FALSE.equals(fit.get("local_priority")), "different city never local");
        fit = local.describe(region("浙江", "宁波"), "");
        check(Boolean.FALSE.equals(fit.get("local_priority")), "same province does not mean local");
        fit = local.describe(region("", ""), "");
        check(Boolean.FALSE.equals(fit.get("residence_complete")) && Boolean.FALSE.equals(fit.get("same_city")), "unknown not local");
        check(fit.get("notes").toString().contains("档期待确认"), "no date not available");
        DispatchPreference online = DispatchPreference.from(Map.of("training_mode", "线上", "training_province", "浙江", "training_city", "杭州"), null);
        check(!online.localPreferenceActive(), "online disables location ranking");
        check(Boolean.FALSE.equals(online.describe(region("江苏", "南京"), "").get("arrival_day_before")), "online no travel");
        check(!DispatchPreference.from(Map.of("training_province", "浙江", "training_city", "杭州"), null).localPreferenceActive(), "unknown mode no location ranking");
        DispatchPreference custom = DispatchPreference.from(Map.of("training_province", "浙江", "training_city", "新地名", "training_mode", "线下"), null);
        check(!custom.localPreferenceActive() && !Boolean.TRUE.equals(custom.describe(region("浙江", "新地名"), "").get("same_city")), "unverified custom names not local");
        check(TeacherIntelligence.compareCandidates(candidate(1, 82, 90, false), candidate(2, 81, 75, true), true) > 0, "same professional band local before composite performance/budget");
        check(TeacherIntelligence.compareCandidates(candidate(1, 82, 75, false), candidate(2, 79, 95, true), true) < 0, "higher professional band before locality");
        check(TeacherIntelligence.compareCandidates(candidate(1, 100, 100, false), candidate(2, 99.9, 99.9, true), true) > 0, "100 and 99.9 same professional band");
        check(TeacherIntelligence.compareCandidates(candidate(1, 82, 90, false), candidate(2, 81, 75, true), false) < 0, "disabled locality uses composite score");
        check(ScheduleDates.canonical(" 2099-1-1 ").equals("2099-01-01"), "normalize unpadded scheduling date");
        for (String bad : Arrays.asList("2099-02-30", "2099/01/01", "0000-01-01")) {
            try { ScheduleDates.canonical(bad); throw new AssertionError("bad date accepted"); }
            catch (IllegalArgumentException expected) { passed++; }
        }
        check(!DispatchPreference.from(Map.of("training_province", "浙江", "training_city", "杭州", "training_mode", "线下", "prefer_local", false), null).localPreferenceActive(), "user can disable preference");
        Map<String,Object> demand = Map.of("training_province", "北京", "training_city", "北京", "training_mode", "线下");
        check(DispatchPreference.from(Map.of(), demand).city.equals("北京"), "import pre-bid city");
        check(DispatchPreference.from(Map.of("training_province", "浙江", "training_city", "杭州"), demand).city.equals("杭州"), "visible override wins");

        // Recreate only the old teachers schema; Db.init adds the rest non-destructively.
        Path data = Files.createTempDirectory("yanxu-dispatch-migration-");
        System.setProperty("data.dir", data.toString());
        System.setProperty("bootstrap.demo", "false");
        System.setProperty("bootstrap.admin.password", "MigrationTest-NotForProduction-2026");
        Db.exec("CREATE TABLE teachers(id IDENTITY PRIMARY KEY,name VARCHAR(64),gender VARCHAR(8),org VARCHAR(200),title VARCHAR(64),field VARCHAR(200),phone VARCHAR(32),email VARCHAR(64),fee_rate DOUBLE,intro CLOB,status VARCHAR(16),in_date VARCHAR(32),out_date VARCHAR(32),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        Db.exec("INSERT INTO teachers(id,name,org,status,fee_rate) VALUES(42,'迁移测试老师','上海示例机构','在库',1800)");
        Db.init();
        Map<String,Object> old = Db.one("SELECT * FROM teachers WHERE id=42");
        check(old.get("base_city") == null && old.get("base_province") == null, "never infer residence from organization");
        check(old.get("name").equals("迁移测试老师"), "preserve old teacher ID/name");
        Db.exec("INSERT INTO dispatches(id,teacher_id,subject,status,hours) VALUES(77,42,'原授课记录','已完成',6)");
        Db.exec("INSERT INTO teacher_resumes(teacher_id,file_name,storage_name,file_size,sha256,is_current,parse_status) VALUES(42,'legacy.pdf','legacy-test.pdf',1,'legacy-test-hash',TRUE,'ready')");
        Db.exec("UPDATE teachers SET base_province='浙江',base_city='杭州' WHERE id=42");
        Db.get().close(); Db.init();
        check(Db.one("SELECT base_city FROM teachers WHERE id=42").get("base_city").equals("杭州"), "restart retains confirmed residence");
        check(((Number)Db.one("SELECT teacher_id FROM dispatches WHERE id=77").get("teacher_id")).longValue() == 42, "history link remains");
        check(Db.one("SELECT file_name FROM teacher_resumes WHERE teacher_id=42").get("file_name").equals("legacy.pdf"), "resume link remains");
        check(((Number)Db.one("SELECT COUNT(*) n FROM teachers").get("n")).intValue() == 1, "no demo teacher backfill");
        for (String status : Arrays.asList("待发送", "已发送", "已确认", "已完成", "已拒绝")) {
            Db.exec("UPDATE dispatches SET status=?,teach_date=' 2099-1-1 ' WHERE id=77", status);
            check(TeacherIntelligence.scheduleConflicts("2099-01-01").contains(42L) != status.equals("已拒绝"), "legacy date conflict: " + status);
        }
        check(TeacherIntelligence.scheduleConflicts("2099-01-02").isEmpty(), "other day not a hard conflict");
        Db.get().close();
        System.out.println("Dispatch rules + legacy migration: " + passed + " passed; isolated database " + data);
    }
}
