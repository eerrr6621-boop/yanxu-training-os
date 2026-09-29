package com.training;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import static com.training.OrganizationAccountImportSource.*;

/** Server-only, pinned projection of the two explicitly reviewed manager-office source rows. */
public final class OrganizationManagementSupplementSource {
    private OrganizationManagementSupplementSource() {}
    private static final String ERROR = "补充账号来源核验失败，请重新核对可信材料。";
    private static final String VERSION = "M01-MANAGEMENT-SUPPLEMENT-v1";
    private static final String DECISION_ID = "root-management-group-20260923-confirmed";
    private static final String HOME = "公司总经理室";
    private static final String GROUP_SHEET = "全体职能员工";
    private static final String TEACHER_SHEET = "汇合公司2026年兼职教师在库名单";
    private static final int JSON_LIMIT = 2 * 1024 * 1024;
    private static final int ORIGINAL_LIMIT = 32 * 1024 * 1024;
    private static final int MAX_ROWS = 10000;
    private static final long MAX_ID = 9007199254740991L;
    private static final List<String> CANDIDATE_REFS = List.of("group-main-row-3", "group-main-row-10");

    /** Paths and digest pins come only from trusted host construction, never from input JSON. */
    public record Config(String batchKey, FilePin groupsOriginal, FilePin teacherOriginal,
                         FilePin projection, FilePin decision, FilePin originalReceipt) {
        @Override public String toString() { return "Config[private supplemental source pins]"; }
    }

    /** Revalidate every file on every invocation; no preview result authorizes a later load. */
    public static Snapshot load(Config config) {
        try { return verifiedLoad(config); }
        catch (Exception invalid) { throw failure(); }
    }

    /** Stable source identity across batch/decision edits; the intake transaction enforces uniqueness. */
    public static String namespace(Config config) {
        try {
            check(config != null);
            validPin(config.groupsOriginal());
            return digest(("M01-GROUP-MAIN-v1\n" + config.groupsOriginal().sha256() + "\n" + GROUP_SHEET + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        } catch (Exception invalid) { throw failure(); }
    }

    private static Snapshot verifiedLoad(Config c) throws Exception {
        check(c != null);
        batchKey(c.batchKey());
        LinkedHashMap<String,FilePin> pins = new LinkedHashMap<>();
        pins.put("groupsOriginal", c.groupsOriginal());
        pins.put("teacherOriginal", c.teacherOriginal());
        pins.put("projection", c.projection());
        pins.put("decision", c.decision());
        pins.put("originalReceipt", c.originalReceipt());
        distinctFiles(pins.values());
        Map<String,byte[]> json = new LinkedHashMap<>();
        for (var entry : pins.entrySet()) {
            byte[] contents = readPinned(entry.getValue(), limit(entry.getKey()));
            if (limit(entry.getKey()) == JSON_LIMIT) json.put(entry.getKey(), contents);
        }
        Map<String,Object> projection = parse(json.get("projection"));
        keys(projection, "schemaVersion", "groupsSha256", "teacherSha256", "decisionSha256",
                "originalReceiptSha256", "decisionId", "candidates", "excluded", "existingMappings");
        equal(projection.get("schemaVersion"), VERSION);
        equal(projection.get("groupsSha256"), c.groupsOriginal().sha256());
        equal(projection.get("teacherSha256"), c.teacherOriginal().sha256());
        equal(projection.get("decisionSha256"), c.decision().sha256());
        equal(projection.get("originalReceiptSha256"), c.originalReceipt().sha256());
        equal(projection.get("decisionId"), DECISION_ID);
        Map<String,Object> decision = parse(json.get("decision"));
        Map<String,Object> homes = verifyDecision(decision);
        Map<String,Object> receipt = parse(json.get("originalReceipt"));
        Map<String,Map<String,Object>> receiptRows = verifyReceipt(receipt, c.batchKey());

        LinkedHashMap<String,Map<String,Object>> excluded = new LinkedHashMap<>();
        Set<String> excludedNames = new HashSet<>();
        List<Object> excludedRows = list(projection.get("excluded"));
        check(excludedRows.size() == 9);
        for (Object value : excludedRows) {
            Map<String,Object> row = object(value);
            keys(row, "reference", "name", "organization", "source");
            String ref = string(row, "reference");
            String name = limitedString(row, "name", 64);
            limitedString(row, "organization", 200);
            Map<String,Object> source = object(row.get("source"));
            keys(source, "sheet", "row", "range");
            long sourceRow = number(source.get("row"), false);
            check(sourceRow >= 185 && sourceRow <= 193);
            equal(ref, "teacher-row-" + sourceRow);
            equal(source.get("sheet"), TEACHER_SHEET);
            equal(source.get("range"), "A" + sourceRow + ":F" + sourceRow);
            check(excluded.putIfAbsent(ref, row) == null && !receiptRows.containsKey(ref));
            excludedNames.add(name);
        }

        List<Object> mappings = list(projection.get("existingMappings"));
        check(mappings.size() == receiptRows.size());
        Set<String> existingRefs = new HashSet<>(), existingStaffIds = new HashSet<>(), existingCodes = new HashSet<>();
        Set<Long> existingAccountIds = new HashSet<>();
        Set<List<String>> existingIdentities = new HashSet<>();
        for (Object value : mappings) {
            Map<String,Object> row = object(value);
            keys(row, "reference", "name", "organization", "sourceStaffId", "accountId", "personCode");
            String ref = string(row, "reference"), name = limitedString(row, "name", 64), organization = limitedString(row, "organization", 200);
            Map<String,Object> received = receiptRows.get(ref);
            check(received != null && existingRefs.add(ref) && !excluded.containsKey(ref));
            long accountId = number(row.get("accountId"), false);
            String code = personCode(row.get("personCode"));
            check(existingAccountIds.add(accountId) && existingCodes.add(code));
            check(accountId == number(received.get("accountId"), false));
            equal(code, received.get("personCode"));
            String staffId = staffId(row.get("sourceStaffId"), true);
            if (staffId != null) check(existingStaffIds.add(staffId));
            existingIdentities.add(List.of(name, organization));
        }
        equal(existingRefs, receiptRows.keySet());

        List<Object> candidateRows = list(projection.get("candidates"));
        check(candidateRows.size() == 2);
        Map<String,Map<String,Object>> candidates = new HashMap<>();
        Set<String> candidateStaffIds = new HashSet<>();
        for (Object value : candidateRows) {
            Map<String,Object> row = object(value);
            keys(row, "reference", "name", "sourceStaffId", "source");
            String ref = string(row, "reference"), name = limitedString(row, "name", 64);
            check(CANDIDATE_REFS.contains(ref) && candidates.putIfAbsent(ref, row) == null);
            String staffId = staffId(row.get("sourceStaffId"), false);
            check(candidateStaffIds.add(staffId) && !existingStaffIds.contains(staffId));
            check(!existingRefs.contains(ref) && !excluded.containsKey(ref));
            // A historical name collision requires separate identity review even if its old home differs.
            check(!excludedNames.contains(name) && !existingIdentities.contains(List.of(name, HOME)));
            int sourceRow = ref.equals("group-main-row-3") ? 3 : 10;
            String label = sourceRow == 3 ? "总经理室成员" : "总经理室";
            Map<String,Object> source = object(row.get("source"));
            keys(source, "sheet", "row", "range", "organizationLabel", "organizationLabelRange", "nameCell", "staffIdCell");
            equal(source.get("sheet"), GROUP_SHEET);
            check(number(source.get("row"), false) == sourceRow);
            equal(source.get("range"), "B" + sourceRow + ":D" + sourceRow);
            equal(source.get("organizationLabel"), label);
            equal(source.get("organizationLabelRange"), sourceRow == 3 ? "B3:B8" : "B10");
            equal(source.get("nameCell"), "C" + sourceRow);
            equal(source.get("staffIdCell"), "D" + sourceRow);
            equal(object(homes.get(ref)).get("sourceLabel"), label);
        }
        equal(candidates.keySet(), new HashSet<>(CANDIDATE_REFS));

        List<Candidate> output = new ArrayList<>();
        List<Object> privateCandidates = new ArrayList<>();
        for (String ref : CANDIDATE_REFS) {
            Map<String,Object> row = candidates.get(ref);
            output.add(new Candidate(ref, string(row, "name"), HOME,
                    map("approvalRoles", List.of(), "identities", List.of("总经理室成员"),
                            "approvalEligibility", false, "combinedDutiesRequiresWorkflowReview", false,
                            "sourceIdentityEvidence", "AVAILABLE", "canApprove", false)));
            privateCandidates.add(row);
        }
        List<String> excludedReferences = new ArrayList<>();
        for (int row = 185; row <= 193; row++) excludedReferences.add("teacher-row-" + row);
        Map<String,Object> privatePins = new LinkedHashMap<>();
        StringBuilder fingerprint = new StringBuilder(VERSION).append('\n').append(c.batchKey()).append('\n');
        // Check all aliases and all file hashes again after parsing/validation, before returning evidence.
        distinctFiles(pins.values());
        for (var entry : pins.entrySet()) {
            readPinned(entry.getValue(), limit(entry.getKey()));
            privatePins.put(entry.getKey(), map("path", normalized(entry.getValue().logicalPath()).toString(), "sha256", entry.getValue().sha256()));
            fingerprint.append(entry.getKey()).append(':').append(entry.getValue().sha256()).append('\n');
        }
        // The reviewed documents remain pinned on disk. Do not duplicate the old roster/receipt
        // into every new account payload after its complete cross-check has succeeded.
        Map<String,Object> receiptEvidence = map("batchKey", receipt.get("batchKey"),
                "sourceFingerprint", receipt.get("sourceFingerprint"), "revision", receipt.get("revision"),
                "summary", receipt.get("summary"));
        Map<String,Object> evidence = map("schemaVersion", VERSION, "pins", privatePins,
                "sourceNamespace", namespace(c), "decisionId", DECISION_ID, "rootDecision", decision,
                "explicitHomeMapping", homes, "candidates", privateCandidates, "excludedReferences", excludedReferences,
                "existingMappingsCount", mappings.size(), "originalReceipt", receiptEvidence);
        return new Snapshot(c.batchKey(), digest(fingerprint.toString().getBytes(StandardCharsets.UTF_8)),
                output, excludedReferences, evidence, List.of());
    }

    private static Map<String,Object> verifyDecision(Map<String,Object> decision) {
        keys(decision, "sourceThreadId", "decisionId", "membership", "excludedConflict", "explicitHomeMapping", "executionBoundary");
        equal(decision.get("decisionId"), DECISION_ID);
        for (String key : List.of("sourceThreadId", "membership", "excludedConflict", "executionBoundary")) string(decision, key);
        Map<String,Object> homes = object(decision.get("explicitHomeMapping"));
        keys(homes, "group-main-row-3", "group-main-row-10");
        String homeCode = null;
        for (String ref : CANDIDATE_REFS) {
            Map<String,Object> home = object(homes.get(ref));
            keys(home, "sourceLabel", "targetOrganizationCode", "targetDisplayName");
            equal(home.get("sourceLabel"), ref.equals("group-main-row-3") ? "总经理室成员" : "总经理室");
            equal(home.get("targetDisplayName"), HOME);
            String code = string(home, "targetOrganizationCode");
            check(code.matches("[A-Za-z][A-Za-z0-9._-]{0,127}"));
            if (homeCode == null) homeCode = code; else equal(homeCode, code);
        }
        return homes;
    }

    private static Map<String,Map<String,Object>> verifyReceipt(Map<String,Object> receipt, String supplementalBatch) {
        keys(receipt, "batchKey", "sourceFingerprint", "revision", "imported", "replayed", "summary", "rows", "permissionsPublished", "accountsActivated");
        String oldBatch = string(receipt, "batchKey"); batchKey(oldBatch);
        check(!oldBatch.equals(supplementalBatch));
        check(string(receipt, "sourceFingerprint").matches("[0-9a-f]{64}"));
        number(receipt.get("revision"), false);
        equal(receipt.get("imported"), true);
        check(receipt.get("replayed") instanceof Boolean);
        equal(receipt.get("permissionsPublished"), false); equal(receipt.get("accountsActivated"), false);
        Map<String,Object> summary = object(receipt.get("summary"));
        keys(summary, "candidates", "createdPending", "linkedExisting", "historicalExcluded");
        List<Object> rows = list(receipt.get("rows")); check(!rows.isEmpty());
        Map<String,Map<String,Object>> byRef = new LinkedHashMap<>();
        Set<Long> accountIds = new HashSet<>(); Set<String> personCodes = new HashSet<>();
        int created = 0, linked = 0;
        for (Object value : rows) {
            Map<String,Object> row = object(value);
            keys(row, "reference", "personCode", "accountId", "action");
            String ref = string(row, "reference");
            check(ref.matches("(?:teacher|lead)-row-[1-9][0-9]{0,6}"));
            check(byRef.putIfAbsent(ref, row) == null);
            check(accountIds.add(number(row.get("accountId"), false)) && personCodes.add(personCode(row.get("personCode"))));
            Object action = row.get("action");
            if ("CREATE_PENDING".equals(action)) created++;
            else { equal(action, "LINK_EXISTING"); linked++; }
        }
        check(number(summary.get("candidates"), false) == rows.size());
        check(number(summary.get("createdPending"), true) == created && number(summary.get("linkedExisting"), true) == linked);
        check(number(summary.get("historicalExcluded"), true) == 9);
        return byRef;
    }

    private static int limit(String key) { return key.equals("groupsOriginal") || key.equals("teacherOriginal") ? ORIGINAL_LIMIT : JSON_LIMIT; }
    private static void distinctFiles(Collection<FilePin> pins) throws Exception {
        Set<Path> logical = new HashSet<>(), lexical = new HashSet<>(), real = new HashSet<>(); List<Path> paths = new ArrayList<>();
        for (FilePin pin : pins) {
            validPin(pin); Path path = normalized(pin.path());
            check(logical.add(normalized(pin.logicalPath())) && !Files.isSymbolicLink(path)
                    && lexical.add(path) && real.add(path.toRealPath()));
            for (Path prior : paths) check(!Files.isSameFile(prior, path));
            paths.add(path);
        }
    }
    private static byte[] readPinned(FilePin pin, int limit) throws Exception {
        Path path = normalized(pin.path()); check(!Files.isSymbolicLink(path) && Files.isReadable(path));
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        check(before.isRegularFile() && before.size() > 0 && before.size() <= limit);
        byte[] bytes;
        try (InputStream in = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { bytes = in.readNBytes(limit + 1); }
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        check(bytes.length <= limit && bytes.length == before.size() && after.isRegularFile() && after.size() == before.size()
                && Objects.equals(before.fileKey(), after.fileKey()) && before.lastModifiedTime().equals(after.lastModifiedTime()));
        equal(digest(bytes), pin.sha256()); return bytes;
    }
    private static Map<String,Object> parse(byte[] bytes) throws Exception {
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        // The host Json parser rejects duplicate keys, trailing data and excessive nesting.
        return object(Json.parse(text));
    }
    private static void validPin(FilePin pin) {
        check(pin != null && pin.path() != null && pin.path().isAbsolute()
                && pin.logicalPath() != null && pin.logicalPath().isAbsolute()
                && pin.sha256() != null && pin.sha256().matches("[0-9a-f]{64}"));
    }
    private static Path normalized(Path path) { return path.toAbsolutePath().normalize(); }
    private static void batchKey(String value) { check(value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")); }
    private static String staffId(Object value, boolean nullable) {
        if (value == null && nullable) return null;
        check(value instanceof String && ((String)value).matches("[A-Za-z0-9._-]{1,64}")); return (String)value;
    }
    private static String personCode(Object value) {
        check(value instanceof String && ((String)value).matches("imp_p_[0-9a-f]{32}")); return (String)value;
    }
    private static String string(Map<String,Object> row, String key) {
        Object value = row.get(key);
        check(value instanceof String && !((String)value).isBlank() && ((String)value).length() <= 4096);
        String result = (String)value;
        check(result.equals(result.strip()));
        for (int i = 0; i < result.length(); i++) {
            char ch = result.charAt(i);
            check(!Character.isISOControl(ch));
            if (Character.isHighSurrogate(ch)) check(++i < result.length() && Character.isLowSurrogate(result.charAt(i)));
            else check(!Character.isLowSurrogate(ch));
        }
        return result;
    }
    private static String limitedString(Map<String,Object> row, String key, int limit) {
        String value = string(row, key); check(value.length() <= limit); return value;
    }
    private static long number(Object value, boolean zero) {
        check(value instanceof Number); Number n = (Number)value;
        check(Double.isFinite(n.doubleValue()) && n.doubleValue() == n.longValue() && n.longValue() >= (zero ? 0 : 1) && n.longValue() <= MAX_ID);
        return n.longValue();
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) { check(value instanceof Map<?,?>); return (Map<String,Object>)value; }
    @SuppressWarnings("unchecked") private static List<Object> list(Object value) { check(value instanceof List<?> && ((List<?>)value).size() <= MAX_ROWS); return (List<Object>)value; }
    private static void keys(Map<String,Object> row, String... fields) { equal(row.keySet(), Set.of(fields)); }
    private static void equal(Object actual, Object expected) { check(Objects.equals(actual, expected)); }
    private static String digest(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static void check(boolean value) { if (!value) throw failure(); }
    private static IllegalArgumentException failure() { return new IllegalArgumentException(ERROR); }
    private static Map<String,Object> map(Object... values) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String)values[i], values[i + 1]);
        return result;
    }
}
