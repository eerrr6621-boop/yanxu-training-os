package com.training;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static com.training.OrganizationAccountImportSource.*;

/** Scripts-only synthetic source generator. Does not initialize a database or read any real roster. */
public final class IntegrationAccountImportBrowserFixture {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("One isolated browser fixture directory required");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path owner = root.getParent().toRealPath();
        if (!owner.getParent().equals(Path.of(System.getProperty("java.io.tmpdir")).toRealPath())
                || !owner.getFileName().toString().startsWith("yanxu-account-import-browser-") || Files.exists(root))
            throw new IllegalArgumentException("Use a new directory under the browser test's private temporary root");
        Config c = OrganizationAccountImportSourceTest.fixture(root.resolve("source"));
        OrganizationAccountImportSource.load(c);
        Map<String,Object> pins = new LinkedHashMap<>();
        pins.put("candidates", pin(c.candidates())); pins.put("candidatePolicy", pin(c.candidatePolicy()));
        pins.put("roleSource", pin(c.roleSource())); pins.put("authorization", pin(c.authorization()));
        pins.put("audit", pin(c.audit())); pins.put("regionReference", pin(c.regionReference()));
        pins.put("scopeDecision", pin(c.scopeDecision())); pins.put("preparedPreview", pin(c.preparedPreview()));
        Map<String,Object> manifest = new LinkedHashMap<>();
        manifest.put("schema", "M01-SERVER-PIN-MANIFEST-v1"); manifest.put("batchKey", c.batchKey());
        manifest.put("usage", "SYNTHETIC ONLY original users page browser verification");
        manifest.put("pins", pins); manifest.put("originals", c.originals().stream().map(IntegrationAccountImportBrowserFixture::pin).toList());
        Path file = root.resolve("synthetic-manifest.json"); Files.writeString(file, Json.write(manifest));
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        System.out.println(Json.write(Map.of("manifest", file.toString(), "sha256", digest, "candidates", 7, "historicalExcluded", 2)));
    }
    private static Map<String,Object> pin(FilePin pin) { return Map.of("path", pin.path().toString(), "sha256", pin.sha256()); }
}
