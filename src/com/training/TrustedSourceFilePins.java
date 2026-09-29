package com.training;

import java.nio.file.*;
import java.util.*;
import static com.training.OrganizationAccountImportSource.FilePin;

/** Parses only independently pinned server manifests, never request bodies or source-document paths. */
final class TrustedSourceFilePins {
    private TrustedSourceFilePins() {}

    static Path materialRoot(Object raw) throws Exception {
        Path root = absolute(raw);
        check(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS));
        noSymbolicComponents(root);
        check(root.equals(root.toRealPath()));
        return root;
    }

    static FilePin pin(Object raw, Path materialRoot) throws Exception {
        check(raw instanceof Map<?,?>);
        Map<?,?> value = (Map<?,?>) raw;
        check(value.keySet().equals(Set.of("logicalPath", "path", "sha256")));
        check(value.get("sha256") instanceof String hash && hash.matches("[0-9a-f]{64}"));
        Path logical = absolute(value.get("logicalPath"));
        Path physical = absolute(value.get("path"));
        // Recheck the root and every physical component; the evidence path is never opened.
        Path root = materialRoot(materialRoot.toString());
        check(!physical.equals(root) && physical.startsWith(root));
        noSymbolicComponents(physical);
        check(Files.isRegularFile(physical, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(physical));
        Path real = physical.toRealPath();
        check(real.equals(physical) && real.startsWith(root));
        return new FilePin(physical, (String) value.get("sha256"), logical);
    }

    private static Path absolute(Object raw) {
        check(raw instanceof String);
        String value = (String) raw;
        check(!value.isBlank() && value.equals(value.trim()) && value.length() <= 4096
                && value.chars().noneMatch(c -> c < 32 || c == 127));
        Path path = Path.of(value);
        check(path.isAbsolute() && path.equals(path.normalize()));
        return path;
    }

    private static void noSymbolicComponents(Path path) {
        Path current = path.getRoot();
        for (Path component : path) {
            current = current.resolve(component);
            check(!Files.isSymbolicLink(current));
        }
    }

    private static void check(boolean valid) {
        if (!valid) throw new IllegalArgumentException("服务器来源位置映射无法核实");
    }
}
