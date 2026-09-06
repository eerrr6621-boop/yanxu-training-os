package com.training;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;

/** Offline migration gate. Mutates only a caller-created isolated V12 database copy. */
public final class DbMigrationCheck {
    private static final Path PRODUCTION = Path.of("/opt/training-system/data");
    private static final Map<String, Set<String>> ADDED = Map.of(
            "TEACHERS", Set.of("BASE_PROVINCE", "BASE_CITY"),
            "DEMANDS", Set.of("TRAINING_PROVINCE", "TRAINING_CITY", "TRAINING_MODE", "TRAINING_PERIOD"));
    private static final class GateFailure extends Exception {
        private static final long serialVersionUID = 1L;
        final String code;
        GateFailure(String code) { this.code = code; }
    }
    private record Column(String name, int ordinal, int jdbcType, String typeName,
                          int size, int scale, int nullable, String defaultValue, String identity) {}
    private record Table(List<Column> columns, long rows, byte[] hash) {}
    private record Snapshot(SortedMap<String, Table> tables) {}
    private record Inputs(Path copy, Path source, Path copyFile, Path sourceFile, byte[] sourceHash) {}
    private static String phase = "arguments";

    public static void main(String[] args) {
        try {
            Inputs input = validateInputs(args);
            phase = "read_only_baseline";
            Snapshot before;
            try (Connection connection = readOnly(input.copy)) {
                require(userCount(connection) > 0, "EMPTY_USERS_REFUSED");
                before = snapshot(connection, null);
                require(before.tables.containsKey("TEACHERS") && before.tables.containsKey("DEMANDS"), "NOT_V12_SCHEMA");
                for (var entry : ADDED.entrySet())
                    for (Column column : before.tables.get(entry.getKey()).columns)
                        require(!entry.getValue().contains(column.name), "MIGRATION_COLUMNS_ALREADY_EXIST");
            }
            System.setProperty("data.dir", input.copy.toString());
            System.setProperty("bootstrap.demo", "false");
            for (int pass = 1; pass <= 2; pass++) {
                phase = "initialization_" + pass;
                initializeWithoutOutput();
                phase = "verification_" + pass;
                try (Connection connection = readOnly(input.copy)) {
                    Snapshot after = snapshot(connection, before);
                    verify(before, after, connection);
                }
                require(MessageDigest.isEqual(input.sourceHash, fileHash(input.sourceFile)), "COLD_SOURCE_CHANGED");
            }
            long rows = before.tables.values().stream().mapToLong(Table::rows).sum();
            long columns = before.tables.values().stream().mapToLong(table -> table.columns.size()).sum();
            System.out.println("{\"ok\":true,\"tablesChecked\":" + before.tables.size()
                    + ",\"oldColumnsChecked\":" + columns + ",\"rowsChecked\":" + rows
                    + ",\"initPasses\":2,\"addedNullableColumns\":6,\"newColumnsAllNull\":true,"
                    + "\"oldColumnsHashesUnchanged\":true,\"coldSourceUnchanged\":true}");
        } catch (GateFailure failure) {
            System.out.println("{\"ok\":false,\"phase\":\"" + phase + "\",\"error\":\"" + failure.code + "\"}");
            System.exit(1);
        } catch (Exception failure) {
            // SQL exception messages can contain business values: never print messages or stacks.
            System.out.println("{\"ok\":false,\"phase\":\"" + phase + "\",\"error\":\""
                    + failure.getClass().getSimpleName() + "\"}");
            System.exit(1);
        }
    }

    private static Inputs validateInputs(String[] args) throws Exception {
        require(args.length == 4 && args[0].equals("--isolated-copy") && args[2].equals("--cold-source"), "ARGUMENTS_REQUIRED");
        Path copy = checkedDirectory(args[1]), source = checkedDirectory(args[3]);
        require(!copy.equals(source) && !copy.startsWith(source) && !source.startsWith(copy), "SOURCE_COPY_OVERLAP");
        Path copyFile = copy.resolve("training.mv.db"), sourceFile = source.resolve("training.mv.db");
        for (Path file : List.of(copyFile, sourceFile)) {
            require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS), "EXISTING_REGULAR_DATABASE_REQUIRED");
            require(!file.toRealPath().startsWith(PRODUCTION), "PRODUCTION_PATH_REFUSED");
            require(!Files.exists(file.getParent().resolve("training.lock.db")), "DATABASE_LOCK_PRESENT");
        }
        require(!Files.isSameFile(copyFile, sourceFile), "SOURCE_COPY_SAME_FILE");
        byte[] sourceHash = fileHash(sourceFile);
        require(MessageDigest.isEqual(sourceHash, fileHash(copyFile)), "COPY_BYTES_DIFFER_FROM_COLD_SOURCE");
        return new Inputs(copy, source, copyFile, sourceFile, sourceHash);
    }

    private static Path checkedDirectory(String value) throws Exception {
        require(!value.contains(";") && !value.contains("\n") && !value.contains("\r"), "UNSAFE_PATH");
        Path lexical = Path.of(value).toAbsolutePath().normalize();
        require(!lexical.startsWith(PRODUCTION), "PRODUCTION_PATH_REFUSED");
        require(Files.isDirectory(lexical), "EXISTING_DIRECTORY_REQUIRED");
        Path canonical = lexical.toRealPath();
        require(!canonical.startsWith(PRODUCTION), "PRODUCTION_PATH_REFUSED");
        require(canonical.getParent() != null, "ROOT_DIRECTORY_REFUSED");
        return canonical;
    }

    private static Connection readOnly(Path data) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:h2:" + data.resolve("training")
                + ";ACCESS_MODE_DATA=r;IFEXISTS=TRUE;DB_CLOSE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        connection.setReadOnly(true);
        return connection;
    }

    private static void initializeWithoutOutput() throws Exception {
        PrintStream stdout = System.out, stderr = System.err;
        try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
            System.setOut(quiet); System.setErr(quiet);
            try { Db.init(); }
            finally {
                // Db normally uses DB_CLOSE_DELAY=-1. Explicit shutdown is necessary before
                // reopening read-only and before another JVM opens this isolated database.
                Connection connection = Db.get();
                try (Statement statement = connection.createStatement()) { statement.execute("SHUTDOWN"); }
                finally { try { connection.close(); } catch (SQLException ignored) {} }
            }
        } finally { System.setOut(stdout); System.setErr(stderr); }
    }

    private static long userCount(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM PUBLIC.USERS")) {
            result.next(); return result.getLong(1);
        }
    }

    private static Snapshot snapshot(Connection connection, Snapshot old) throws Exception {
        SortedMap<String, Table> tables = new TreeMap<>();
        DatabaseMetaData metadata = connection.getMetaData();
        List<String> names = new ArrayList<>();
        try (ResultSet result = metadata.getTables(null, "PUBLIC", "%", new String[]{"TABLE", "BASE TABLE"})) {
            while (result.next()) names.add(result.getString("TABLE_NAME"));
        }
        Collections.sort(names);
        for (String name : names) {
            List<Column> columns = new ArrayList<>();
            String escape = metadata.getSearchStringEscape();
            String exactNamePattern = name.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
            try (ResultSet result = metadata.getColumns(null, "PUBLIC", exactNamePattern, "%")) {
                while (result.next()) columns.add(new Column(result.getString("COLUMN_NAME"), result.getInt("ORDINAL_POSITION"),
                        result.getInt("DATA_TYPE"), result.getString("TYPE_NAME"), result.getInt("COLUMN_SIZE"),
                        result.getInt("DECIMAL_DIGITS"), result.getInt("NULLABLE"), result.getString("COLUMN_DEF"), result.getString("IS_AUTOINCREMENT")));
            }
            columns.sort(Comparator.comparingInt(Column::ordinal));
            List<Column> selected = old != null && old.tables.containsKey(name) ? old.tables.get(name).columns : columns;
            String projection = String.join(",", selected.stream().map(column -> quote(column.name)).toList());
            List<byte[]> rowHashes = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT " + projection + " FROM PUBLIC." + quote(name))) {
                while (result.next()) {
                    MessageDigest hash = MessageDigest.getInstance("SHA-256");
                    try (DataOutputStream output = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), hash))) {
                        for (int i = 0; i < selected.size(); i++) {
                            int jdbcType = selected.get(i).jdbcType;
                            output.writeInt(jdbcType);
                            byte[] value;
                            if (jdbcType == Types.BINARY || jdbcType == Types.VARBINARY || jdbcType == Types.LONGVARBINARY || jdbcType == Types.BLOB) {
                                value = result.getBytes(i + 1);
                            } else {
                                String text = result.getString(i + 1);
                                value = text == null ? null : text.getBytes(StandardCharsets.UTF_8);
                            }
                            output.writeBoolean(value != null);
                            if (value != null) { output.writeInt(value.length); output.write(value); }
                        }
                    }
                    rowHashes.add(hash.digest());
                }
            }
            // Hash sorted fixed-width row digests: independent of physical scan/row order,
            // but preserves duplicate rows and all primary/foreign key cell values.
            rowHashes.sort(Arrays::compareUnsigned);
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (byte[] row : rowHashes) hash.update(row);
            tables.put(name, new Table(List.copyOf(columns), rowHashes.size(), hash.digest()));
        }
        return new Snapshot(tables);
    }

    private static void verify(Snapshot before, Snapshot after, Connection connection) throws Exception {
        require(before.tables.keySet().equals(after.tables.keySet()), "PUBLIC_TABLE_SET_CHANGED");
        int addedCount = 0;
        for (var entry : before.tables.entrySet()) {
            String name = entry.getKey(); Table previous = entry.getValue(), current = after.tables.get(name);
            require(previous.rows == current.rows && MessageDigest.isEqual(previous.hash, current.hash), "OLD_COLUMN_DATA_CHANGED");
            Map<String, Column> columns = new LinkedHashMap<>();
            for (Column column : current.columns) columns.put(column.name, column);
            for (Column column : previous.columns)
                require(column.equals(columns.remove(column.name)), "OLD_COLUMN_DEFINITION_CHANGED");
            Set<String> expected = ADDED.getOrDefault(name, Set.of());
            require(columns.keySet().equals(expected), "UNEXPECTED_ADDED_COLUMNS");
            for (Column column : columns.values()) {
                require(column.nullable == DatabaseMetaData.columnNullable && column.jdbcType == Types.VARCHAR
                        && column.defaultValue == null && column.identity.equals("NO"), "UNEXPECTED_NEW_COLUMN_DEFINITION");
                int size = column.name.equals("TRAINING_MODE") || column.name.equals("TRAINING_PERIOD") ? 16 : 64;
                require(column.size == size, "UNEXPECTED_NEW_COLUMN_SIZE");
                try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                        "SELECT COUNT(*) FROM PUBLIC." + quote(name) + " WHERE " + quote(column.name) + " IS NOT NULL")) {
                    result.next(); require(result.getLong(1) == 0, "HISTORICAL_REGIONS_WERE_FILLED");
                }
                addedCount++;
            }
        }
        require(addedCount == 6, "EXPECTED_SIX_ADDED_COLUMNS");
    }

    private static byte[] fileHash(Path file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
        }
        return hash.digest();
    }

    private static String quote(String name) { return "\"" + name.replace("\"", "\"\"") + "\""; }
    private static void require(boolean condition, String code) throws GateFailure { if (!condition) throw new GateFailure(code); }
}
