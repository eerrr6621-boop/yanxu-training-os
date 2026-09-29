package com.training;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;

/** Standalone synthetic transport checks: no database, external delivery, or real credentials. */
public final class S01LoginVerificationMailAdapterTest {
    private static final String USER = "synthetic@163.com", SECRET = "synthetic-secret";
    private static final String TO = "recipient@example.test", CODE = "739204";
    private static final String SERVICE = "com.yanxu.S01.smtp.163";
    private static int checks;
    private static String failureMessage;
    private static Path root;
    private static SSLContext trusted, untrusted;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new AssertionError("An explicit synthetic fixture directory is required");
        root = Path.of(args[0]).toRealPath();
        check(root.getFileName().toString().startsWith("yanxu-s01-mail-adapter."), "temporary fixture boundary");
        check(Files.isRegularFile(root.resolve("tls.p12")), "synthetic TLS fixture exists");
        trusted = tlsContext(true); untrusted = tlsContext(false);
        PrintStream originalOut = System.out, originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream quiet = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setOut(quiet); System.setErr(quiet);
            configuration(); credentialFiles(); keychain();
            smtpAndContent(); protocolFailures(); inputRejection();
            tlsVerification(); deadlineCancellation(); concurrencyAndClose(); rateLimit();
        } finally { System.setOut(originalOut); System.setErr(originalErr); }
        String logs = captured.toString(StandardCharsets.UTF_8);
        check(!containsSensitive(logs), "adapter emits no synthetic secrets");
        check(logs.isBlank(), "adapter emits no transport debug output");
        originalOut.println("PASS: " + checks + " isolated mail adapter checks (synthetic loopback SMTP/TLS only).");
    }

    private static LoginVerificationMailAdapter.Settings settings(int total, int concurrency, int hourly) {
        return new LoginVerificationMailAdapter.Settings(200, Math.min(500, total), total, concurrency, hourly);
    }
    private static LoginVerificationMailAdapter.Credentials credentials() throws IOException {
        return new LoginVerificationMailAdapter.Credentials(USER, SECRET.toCharArray());
    }
    private static LoginVerificationMailAdapter adapter(Server server) throws IOException {
        return adapter(server, settings(2500, 2, 60));
    }
    private static LoginVerificationMailAdapter adapter(Server server, LoginVerificationMailAdapter.Settings settings) throws IOException {
        return new LoginVerificationMailAdapter(settings, credentials(), server.connector(), System::nanoTime);
    }
    private static Map<String,String> environment() {
        Map<String,String> env = new HashMap<>();
        env.put("YANXU_LOGIN_MAIL_TRANSPORT", "163_smtps");
        env.put("YANXU_LOGIN_SMTP_CREDENTIAL_SOURCE", "macos_keychain");
        return env;
    }
    private static void configuration() throws Exception {
        AtomicInteger loads = new AtomicInteger(), connects = new AtomicInteger();
        LoginVerificationMailAdapter.CredentialLoader loader = (source, file) -> { loads.incrementAndGet(); return credentials(); };
        LoginVerificationMailAdapter.Connector connector = (attempt, config) -> { connects.incrementAndGet(); throw new IOException(SECRET); };
        for (String transport : List.of("", "disabled")) {
            Map<String,String> env = environment(); env.put("YANXU_LOGIN_MAIL_TRANSPORT", transport);
            var result = LoginVerificationMailAdapter.fromEnvironment(env, loader, connector, System::nanoTime);
            check(result.state() == LoginVerificationMailAdapter.State.NOT_CONFIGURED && result.sender() == null, "disabled transport");
        }
        check(loads.get() == 0 && connects.get() == 0, "disabled startup does not access credentials or network");
        List<Map<String,String>> invalid = new ArrayList<>();
        invalid.add(Map.of("YANXU_LOGIN_MAIL_TRANSPORT", "smtp"));
        invalid.add(Map.of("YANXU_LOGIN_MAIL_TRANSPORT", "163_smtps"));
        for (String forbidden : List.of("USERNAME", "PASSWORD", "AUTHORIZATION_CODE", "HOST", "PORT")) {
            Map<String,String> env = environment(); env.put("YANXU_LOGIN_SMTP_" + forbidden, SECRET); invalid.add(env);
        }
        for (String[] field : new String[][] {
                {"CONNECT_TIMEOUT_MS", "99"}, {"CONNECT_TIMEOUT_MS", "10001"},
                {"READ_TIMEOUT_MS", "99"}, {"READ_TIMEOUT_MS", "10001"},
                {"SEND_TIMEOUT_MS", "499"}, {"SEND_TIMEOUT_MS", "30001"},
                {"SEND_TIMEOUT_MS", "2000"}, {"MAX_CONCURRENT", "0"}, {"MAX_CONCURRENT", "5"},
                {"MAX_PER_HOUR", "0"}, {"MAX_PER_HOUR", "1025"}, {"MAX_PER_HOUR", "NaN"},
                {"CREDENTIAL_SOURCE", "environment"}, {"CREDENTIAL_SOURCE", "file"}}) {
            Map<String,String> env = environment(); env.put("YANXU_LOGIN_SMTP_" + field[0], field[1]); invalid.add(env);
        }
        Map<String,String> relative = environment(); relative.put("YANXU_LOGIN_SMTP_CREDENTIAL_SOURCE", "file");
        relative.put("YANXU_LOGIN_SMTP_CREDENTIAL_FILE", "relative-private.txt"); invalid.add(relative);
        for (Map<String,String> env : invalid) {
            var result = LoginVerificationMailAdapter.fromEnvironment(env, loader, connector, System::nanoTime);
            check(result.state() == LoginVerificationMailAdapter.State.CONFIGURATION_INVALID && result.sender() == null, "invalid configuration is inert");
            check(!containsSensitive(result.toString()), "invalid startup result is redacted");
        }
        check(loads.get() == 0 && connects.get() == 0, "invalid settings rejected before credential loading");
        var unavailable = LoginVerificationMailAdapter.fromEnvironment(environment(), (source, file) -> { throw new IOException(USER + SECRET); }, connector, System::nanoTime);
        check(unavailable.state() == LoginVerificationMailAdapter.State.CREDENTIAL_UNAVAILABLE && unavailable.sender() == null, "credential loader failures stay unavailable");
        check(!containsSensitive(unavailable.toString()), "startup failure hides loader details");
        var configured = LoginVerificationMailAdapter.fromEnvironment(environment(), loader, connector, System::nanoTime);
        check(configured.state() == LoginVerificationMailAdapter.State.CONFIGURED && configured.sender() != null, "valid startup configures adapter");
        check(loads.get() == 1 && connects.get() == 0, "startup performs no SMTP probe");
        check(!containsSensitive(configured.toString()) && !containsSensitive(credentials().toString()), "startup and credential representations are redacted");
        configured.sender().close();
        Path file = root.resolve("loader-credential.txt");
        Map<String,String> env = environment(); env.put("YANXU_LOGIN_SMTP_CREDENTIAL_SOURCE", "file"); env.put("YANXU_LOGIN_SMTP_CREDENTIAL_FILE", file.toString());
        AtomicInteger fileLoads = new AtomicInteger();
        var fileConfigured = LoginVerificationMailAdapter.fromEnvironment(env, (source, path) -> {
            check(source.equals("file") && path.equals(file), "file loader receives only selected path"); fileLoads.incrementAndGet(); return credentials();
        }, connector, System::nanoTime);
        check(fileConfigured.state() == LoginVerificationMailAdapter.State.CONFIGURED && fileLoads.get() == 1 && connects.get() == 0, "file startup is offline");
        fileConfigured.sender().close();
        for (String[] boundary : new String[][] {{"100", "100", "500", "1", "1"}, {"10000", "10000", "30000", "4", "1024"}}) {
            Map<String,String> bounded = environment();
            String[] suffixes = {"CONNECT_TIMEOUT_MS", "READ_TIMEOUT_MS", "SEND_TIMEOUT_MS", "MAX_CONCURRENT", "MAX_PER_HOUR"};
            for (int i=0; i<suffixes.length; i++) bounded.put("YANXU_LOGIN_SMTP_" + suffixes[i], boundary[i]);
            var result = LoginVerificationMailAdapter.fromEnvironment(bounded, loader, connector, System::nanoTime);
            check(result.state() == LoginVerificationMailAdapter.State.CONFIGURED, "documented configuration bounds accepted"); result.sender().close();
        }
        check(connects.get() == 0, "all startup configurations remain offline");
        AtomicReference<LoginVerificationMailAdapter.Settings> defaults = new AtomicReference<>();
        var defaultStartup = LoginVerificationMailAdapter.fromEnvironment(environment(), loader, (attempt, config) -> {
            defaults.set(config); throw new IOException(SECRET);
        }, System::nanoTime);
        try (var mail = defaultStartup.sender()) { failure(() -> mail.send(TO, CODE)); }
        var observed = defaults.get();
        check(observed != null && observed.connectMillis() == 5000 && observed.readMillis() == 5000 && observed.totalMillis() == 15000
                && observed.concurrency() == 2 && observed.hourlyLimit() == 60, "documented safe defaults reach transport");
    }

    private static Path privateFile(String name, byte[] content) throws Exception {
        Path file = root.resolve(name); Files.write(file, content); Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); return file;
    }
    private static String credentialText() { return "username=" + USER + "\nauthorization_code=" + SECRET + "\n"; }
    private static void credentialFiles() throws Exception {
        Path valid = privateFile("credentials-valid.txt", credentialText().getBytes(StandardCharsets.UTF_8));
        try (var value = LoginVerificationMailAdapter.readCredentialFile(valid); Server server = new Server(Mode.LOGIN);
                var mail = new LoginVerificationMailAdapter(settings(2500,2,60), value, server.connector(), System::nanoTime)) {
            check(!containsSensitive(value.toString()), "valid credential file is redacted");
            mail.send(TO, CODE); check(server.awaitSession().authenticated, "protected credential file parses exact synthetic values");
        }
        Files.setPosixFilePermissions(valid, PosixFilePermissions.fromString("r--------"));
        try (var value = LoginVerificationMailAdapter.readCredentialFile(valid)) { check(value != null, "owner read-only file accepted"); }
        Files.setPosixFilePermissions(valid, PosixFilePermissions.fromString("rw-r-----")); rejectedCredentialFile(valid, "group-readable credential file");
        Files.setPosixFilePermissions(valid, PosixFilePermissions.fromString("rw-----w-")); rejectedCredentialFile(valid, "world-writable credential file");
        Files.setPosixFilePermissions(valid, PosixFilePermissions.fromString("rw-------"));
        Path link = root.resolve("credentials-link.txt"); Files.createSymbolicLink(link, valid.getFileName()); rejectedCredentialFile(link, "symlink credential file");
        Path unsafeDirectory = root.resolve("group-writable-parent"); Files.createDirectory(unsafeDirectory);
        Files.setPosixFilePermissions(unsafeDirectory, PosixFilePermissions.fromString("rwxrwx---"));
        Path unsafeChild = unsafeDirectory.resolve("credentials.txt"); Files.writeString(unsafeChild, credentialText(), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(unsafeChild, PosixFilePermissions.fromString("rw-------")); rejectedCredentialFile(unsafeChild, "credential file in mutable parent directory");
        rejectedCredentialFile(root, "directory credential path"); rejectedCredentialFile(root.resolve("absent.txt"), "missing credential file");
        rejectedCredentialFile(Path.of("credentials-valid.txt"), "relative credential path");
        List<byte[]> malformed = List.of(
                (credentialText() + "username=" + USER + "\n").getBytes(StandardCharsets.UTF_8),
                ("username=" + USER + "\n").getBytes(StandardCharsets.UTF_8),
                ("username=evil@example.test\nauthorization_code=" + SECRET + "\n").getBytes(StandardCharsets.UTF_8),
                ("username=" + USER + "\nauthorization_code=\n").getBytes(StandardCharsets.UTF_8),
                ("username=" + USER + "\nauthorization_code=" + SECRET + "\rBcc: injected@example.test\n").getBytes(StandardCharsets.UTF_8),
                new byte[] {(byte)0xc3, (byte)0x28}, new byte[4096]);
        for (int i=0; i<malformed.size(); i++) rejectedCredentialFile(privateFile("credentials-bad-" + i + ".txt", malformed.get(i)), "malformed credential file");
    }
    private static void rejectedCredentialFile(Path file, String label) throws Exception {
        try (var ignored = LoginVerificationMailAdapter.readCredentialFile(file)) { throw new AssertionError(label + " accepted"); }
        catch (IOException expected) { check(!containsSensitive(expected.toString()), label + " rejected without secret details"); }
    }

    private static void keychain() throws Exception {
        List<List<String>> commands = new ArrayList<>();
        var value = LoginVerificationMailAdapter.readKeychain((command, timeout, maxBytes) -> {
            commands.add(List.copyOf(command));
            check(timeout > 0 && timeout <= 10_000 && maxBytes > 0 && maxBytes <= 16_384, "keychain process limits");
            return (commands.size() == 1 ? metadata(USER) : SECRET + "\n").getBytes(StandardCharsets.UTF_8);
        });
        check(commands.size() == 2, "keychain metadata and secret read separately");
        for (List<String> command : commands) {
            check(command.get(0).equals("/usr/bin/security") && command.contains("find-generic-password"), "fixed keychain program and command");
            check(command.contains("-s") && command.get(command.indexOf("-s") + 1).equals(SERVICE), "fixed keychain service");
            check(!command.contains(SECRET), "keychain secret never appears in process arguments");
        }
        check(!commands.get(0).contains("-w") && commands.get(1).contains("-w"), "keychain lookup order");
        check(commands.get(1).contains("-a") && commands.get(1).get(commands.get(1).indexOf("-a") + 1).equals(USER), "keychain secret bound to validated account");
        try (Server server = new Server(Mode.LOGIN); LoginVerificationMailAdapter mail = new LoginVerificationMailAdapter(settings(2500,2,60), value, server.connector(), System::nanoTime)) {
            mail.send(TO, CODE); check(server.awaitSession().authenticated, "keychain result usable for synthetic authentication");
        }
        for (String bad : List.of("", metadata("evil@example.test"), metadata(USER) + metadata(USER), "\"acct\"<blob>=\"" + USER + "\"\n")) {
            AtomicInteger calls = new AtomicInteger();
            try (var ignored = LoginVerificationMailAdapter.readKeychain((command, timeout, maxBytes) -> { calls.incrementAndGet(); return bad.getBytes(StandardCharsets.UTF_8); })) {
                throw new AssertionError("Malformed keychain metadata accepted");
            } catch (IOException expected) { check(!containsSensitive(expected.toString()), "malformed keychain response redacted"); }
            check(calls.get() == 1, "invalid keychain metadata never requests secret");
        }
        AtomicInteger calls = new AtomicInteger();
        try (var ignored = LoginVerificationMailAdapter.readKeychain((command, timeout, maxBytes) -> {
            if (calls.incrementAndGet() == 1) return metadata(USER).getBytes(StandardCharsets.UTF_8);
            throw new IOException(USER + SECRET);
        })) { throw new AssertionError("Failed keychain read accepted"); }
        catch (IOException expected) { check(!containsSensitive(expected.toString()), "keychain command failure redacted"); }
        for (String badSecret : List.of("", SECRET + "\r\nBcc: injected@example.test", "x".repeat(4097))) {
            AtomicInteger count = new AtomicInteger();
            try (var ignored = LoginVerificationMailAdapter.readKeychain((command, timeout, maxBytes) ->
                    (count.incrementAndGet() == 1 ? metadata(USER) : badSecret).getBytes(StandardCharsets.UTF_8))) {
                throw new AssertionError("Invalid keychain secret accepted");
            } catch (IOException expected) { check(!containsSensitive(expected.toString()), "invalid keychain secret rejected without disclosure"); }
        }
    }
    private static String metadata(String account) { return "    \"acct\"<blob>=\"" + account + "\"\n    \"svce\"<blob>=\"" + SERVICE + "\"\n"; }

    private static void smtpAndContent() throws Exception {
        for (Mode mode : List.of(Mode.LOGIN, Mode.PLAIN, Mode.BOTH)) {
            try (Server server = new Server(mode); LoginVerificationMailAdapter mail = adapter(server)) {
                mail.send(TO, CODE); Session session = server.awaitSession();
                check(session.authenticated && session.accepted, "advertised SMTP authentication completes delivery");
                check(mode != Mode.PLAIN || session.commands.stream().anyMatch(s -> s.startsWith("AUTH PLAIN")), "PLAIN-only advertisement selects PLAIN");
                check(mode != Mode.LOGIN || session.commands.contains("AUTH LOGIN"), "LOGIN-only advertisement selects LOGIN");
                check(mode != Mode.BOTH || session.commands.contains("AUTH LOGIN"), "LOGIN preferred when both mechanisms advertised");
                check(session.commands.get(0).startsWith("EHLO "), "SMTP begins with EHLO");
                check(session.commands.contains("MAIL FROM:<" + USER + ">") && session.commands.contains("RCPT TO:<" + TO + ">"), "SMTP envelope uses validated addresses");
                String message = session.message;
                check(message != null && message.contains("\r\n\r\n"), "message has MIME header boundary");
                int split = message.indexOf("\r\n\r\n"); String headers = message.substring(0, split), encoded = message.substring(split + 4);
                check(headers.toLowerCase(Locale.ROOT).contains("content-transfer-encoding: base64"), "message body uses base64");
                check(headers.toLowerCase(Locale.ROOT).contains("charset=utf-8") || headers.toLowerCase(Locale.ROOT).contains("charset=\"utf-8\""), "message declares UTF-8");
                check(headers.contains("From:") && headers.contains("To:") && headers.contains("Subject:"), "message includes required headers");
                check(!headers.contains(CODE) && !headers.contains(SECRET), "headers exclude code and authorization secret");
                String body = new String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8);
                check(body.contains(CODE) && body.contains("10") && body.contains("分钟"), "fixed body contains code and ten-minute validity");
                check(!body.contains(SECRET) && !body.contains(USER), "body excludes credential fields");
            }
        }
        try (Server server = new Server(Mode.LOGIN); var mail = new LoginVerificationMailAdapter(settings(2500,2,60), credentials(), (attempt, config) -> {
            Socket raw = server.connector().connect(attempt, config);
            return new Socket() {
                @Override public void setSoTimeout(int timeout) throws SocketException { raw.setSoTimeout(timeout); }
                @Override public InputStream getInputStream() throws IOException { return raw.getInputStream(); }
                @Override public OutputStream getOutputStream() throws IOException {
                    OutputStream destination = raw.getOutputStream();
                    return new FilterOutputStream(destination) {
                        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                            if (new String(bytes, offset, length, StandardCharsets.US_ASCII).startsWith("QUIT")) throw new IOException(SECRET);
                            destination.write(bytes, offset, length);
                        }
                    };
                }
                @Override public void close() throws IOException { raw.close(); }
            };
        }, System::nanoTime)) {
            mail.send(TO, CODE); check(server.awaitSession().accepted, "accepted DATA remains successful when QUIT write fails");
        }
    }
    private static void protocolFailures() throws Exception {
        for (Mode mode : List.of(Mode.NO_AUTH, Mode.AUTH_FAIL, Mode.BAD_GREETING, Mode.BAD_EHLO_CODE, Mode.WRONG_MULTILINE, Mode.DATA_REJECT, Mode.COMMIT_REJECT)) {
            try (Server server = new Server(mode); LoginVerificationMailAdapter mail = adapter(server)) {
                failure(() -> mail.send(TO, CODE)); Session session = server.awaitSession();
                check(!session.accepted, "SMTP rejection never reports accepted delivery");
                if (mode == Mode.NO_AUTH || mode == Mode.BAD_GREETING || mode == Mode.BAD_EHLO_CODE || mode == Mode.WRONG_MULTILINE)
                    check(session.commands.stream().noneMatch(s -> s.startsWith("AUTH")), "invalid greeting or capabilities do not disclose authentication");
                if (mode == Mode.AUTH_FAIL) check(session.commands.stream().filter(s -> s.startsWith("AUTH")).count() == 1, "failed authentication never falls back");
                if (mode != Mode.COMMIT_REJECT) check(session.message == null, "failed pre-DATA transaction sends no message body");
            }
        }
        AtomicInteger connections = new AtomicInteger();
        try (var mail = new LoginVerificationMailAdapter(settings(2500, 2, 60), credentials(), (attempt, settings) -> { connections.incrementAndGet(); throw new IOException(TO + CODE + SECRET); }, System::nanoTime)) {
            failure(() -> mail.send(TO, CODE)); check(connections.get() == 1, "connector exception sanitized without retry");
        }
    }
    private static void inputRejection() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        try (var mail = new LoginVerificationMailAdapter(settings(2500, 2, 60), credentials(), (attempt, settings) -> { connections.incrementAndGet(); throw new IOException(); }, System::nanoTime)) {
            for (String address : Arrays.asList(null, "", "a@example.test\r\nBcc: victim@example.test", "a@example.test\n", "a@example.test,evil@example.test", "<a@example.test>")) failure(() -> mail.send(address, CODE));
            for (String code : Arrays.asList(null, "", "12345", "1234567", "12345\n", "１２３４５６")) failure(() -> mail.send(TO, code));
            synchronized (Api.MUTATION_LOCK) { failure(() -> mail.send(TO, CODE)); }
            check(connections.get() == 0, "invalid input and business-lock sends rejected before connecting");
        }
    }

    private static void tlsVerification() throws Exception {
        for (int scenario=0; scenario<3; scenario++) {
            String host = scenario == 2 ? "127.0.0.1" : "localhost";
            SSLSocketFactory factory = (scenario == 1 ? untrusted : trusted).getSocketFactory();
            try (Server server = new Server(Mode.LOGIN, true); var mail = new LoginVerificationMailAdapter(settings(3500,2,60), credentials(),
                    (attempt, settings) -> LoginVerificationMailAdapter.connectTls(host, server.port(), factory, attempt, settings), System::nanoTime)) {
                if (scenario == 0) { mail.send(TO, CODE); check(server.awaitSession().accepted, "trusted TLS with matching hostname succeeds"); }
                else { failure(() -> mail.send(TO, CODE)); check(server.awaitSession().commands.isEmpty(), scenario == 1 ? "untrusted TLS rejected before SMTP" : "TLS hostname mismatch rejected before SMTP"); }
            }
        }
    }
    private static SSLContext tlsContext(boolean trustFixture) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(root.resolve("tls.p12"))) { store.load(input, "synthetic-test-only".toCharArray()); }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); keyManagers.init(store, "synthetic-test-only".toCharArray());
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trustFixture ? store : (KeyStore)null);
        SSLContext context = SSLContext.getInstance("TLS"); context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null); return context;
    }

    private static void deadlineCancellation() throws Exception {
        for (Mode mode : List.of(Mode.SLOW_EHLO, Mode.GATED_DATA)) {
            try (Server server = new Server(mode); LoginVerificationMailAdapter mail = adapter(server, settings(600, 1, 60))) {
                long start = System.nanoTime(); failure(() -> mail.send(TO, CODE));
                check(elapsedMillis(start) < 1800, "total deadline bounds SMTP transaction");
                Session session = server.awaitSession(); server.gate.countDown();
                check(session.done.await(1800, TimeUnit.MILLISECONDS), "cancelled SMTP worker relinquishes socket");
                check(session.message == null && !session.accepted, "timeout cannot continue sending in background");
                if (mode == Mode.SLOW_EHLO) check(session.commands.stream().noneMatch(s -> s.startsWith("AUTH")), "slow multiline reply is bound by total deadline");
            }
        }
        try (Server server = new Server(Mode.LOGIN)) {
            CountDownLatch connectorEntered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger connects = new AtomicInteger();
            try (var mail = new LoginVerificationMailAdapter(settings(600,1,60), credentials(), (attempt, settings) -> {
                connects.incrementAndGet();
                Socket socket = new Socket(); attempt.add(socket); socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), server.port()), 200);
                connectorEntered.countDown(); awaitIgnoringInterrupts(release, 1800); return socket;
            }, System::nanoTime)) {
                failure(() -> mail.send(TO, CODE)); check(connectorEntered.getCount() == 0, "delayed connector started");
                failure(() -> mail.send(TO, CODE)); check(connects.get() == 1, "cancelled but occupied worker cannot grow thread pool"); release.countDown();
                Session session = server.awaitSession(); check(session.done.await(1800, TimeUnit.MILLISECONDS), "deadline closes registered connector socket");
                check(session.commands.isEmpty() && session.message == null, "late connector cannot resume SMTP after cancellation");
            } finally { release.countDown(); }
        }
    }
    private static void concurrencyAndClose() throws Exception {
        try (Server server = new Server(Mode.GATED_GREETING); LoginVerificationMailAdapter mail = adapter(server, new LoginVerificationMailAdapter.Settings(200,2500,3000,2,60))) {
            ExecutorService callers = Executors.newFixedThreadPool(2);
            try {
                Future<?> first = callers.submit(() -> sendUnchecked(mail)); Future<?> second = callers.submit(() -> sendUnchecked(mail));
                check(server.awaitConnections(2), "two allowed SMTP workers start"); long start = System.nanoTime();
                failure(() -> mail.send(TO, CODE)); check(elapsedMillis(start) < 500, "excess concurrency fails without queueing");
                check(server.sessions.size() == 2, "concurrency cap bounds live connections");
                server.gate.countDown(); first.get(4, TimeUnit.SECONDS); second.get(4, TimeUnit.SECONDS);
                check(server.sessions.stream().allMatch(s -> s.accepted), "reserved workers finish normally");
            } finally { server.gate.countDown(); callers.shutdownNow(); }
        }
        try (Server server = new Server(Mode.GATED_GREETING)) {
            var mail = adapter(server, new LoginVerificationMailAdapter.Settings(200,2500,3000,1,60)); ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Future<Boolean> pending = caller.submit(() -> { try { mail.send(TO, CODE); return false; } catch (IOException expected) { return true; } });
                check(server.awaitConnections(1), "close test has active SMTP attempt"); mail.close();
                check(pending.get(2, TimeUnit.SECONDS), "close cancels active send"); server.gate.countDown();
                Session session = server.awaitSession(); check(session.done.await(1800, TimeUnit.MILLISECONDS) && session.message == null, "close prevents background delivery");
                failure(() -> mail.send(TO, CODE)); check(server.sessions.size() == 1, "closed adapter rejects future sends");
            } finally { mail.close(); server.gate.countDown(); caller.shutdownNow(); }
        }
    }
    private static void rateLimit() throws Exception {
        AtomicLong now = new AtomicLong(TimeUnit.HOURS.toNanos(5)); AtomicInteger connects = new AtomicInteger();
        try (var mail = new LoginVerificationMailAdapter(settings(2500, 2, 2), credentials(), (attempt, settings) -> { connects.incrementAndGet(); throw new IOException(SECRET); }, now::get)) {
            failure(() -> mail.send(TO, CODE)); failure(() -> mail.send(TO, CODE)); failure(() -> mail.send(TO, CODE));
            check(connects.get() == 2, "failed sends consume hourly allowance");
            now.addAndGet(-TimeUnit.MINUTES.toNanos(1)); failure(() -> mail.send(TO, CODE)); check(connects.get() == 2, "backward clock cannot replenish rate allowance");
            now.set(TimeUnit.HOURS.toNanos(6)); failure(() -> mail.send(TO, CODE)); check(connects.get() == 3, "hourly allowance recovers after window expires");
        }
        AtomicLong clock = new AtomicLong(TimeUnit.HOURS.toNanos(5));
        try (Server server = new Server(Mode.LOGIN); var mail = new LoginVerificationMailAdapter(settings(2500,2,2), credentials(), server.connector(), clock::get)) {
            mail.send(TO, CODE); mail.send(TO, CODE); failure(() -> mail.send(TO, CODE));
            check(server.sessions.size() == 2, "successful sends consume hourly allowance");
            clock.addAndGet(TimeUnit.HOURS.toNanos(1)); mail.send(TO, CODE); check(server.awaitConnections(3), "expired successful sends release allowance");
        }
    }

    private enum Mode { LOGIN, PLAIN, BOTH, NO_AUTH, AUTH_FAIL, BAD_GREETING, BAD_EHLO_CODE, WRONG_MULTILINE, DATA_REJECT, COMMIT_REJECT, SLOW_EHLO, GATED_DATA, GATED_GREETING }
    private static final class Session {
        final Socket socket; final List<String> commands = new CopyOnWriteArrayList<>(); final CountDownLatch done = new CountDownLatch(1);
        volatile boolean authenticated, accepted; volatile String message;
        Session(Socket socket) { this.socket = socket; }
    }
    private static final class Server implements AutoCloseable {
        final Mode mode; final ServerSocket listener; final List<Session> sessions = new CopyOnWriteArrayList<>();
        final CountDownLatch gate = new CountDownLatch(1); final Thread acceptor; volatile boolean closed;
        Server(Mode mode) throws Exception { this(mode, false); }
        Server(Mode mode, boolean tls) throws Exception {
            this.mode = mode; listener = tls ? trusted.getServerSocketFactory().createServerSocket() : new ServerSocket();
            listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            acceptor = new Thread(() -> {
                while (!closed) try {
                    Socket socket = listener.accept(); socket.setSoTimeout(4000); Session session = new Session(socket); sessions.add(session);
                    Thread worker = new Thread(() -> serve(session), "synthetic-smtp-session"); worker.setDaemon(true); worker.start();
                } catch (IOException ignored) { if (!closed) closed = true; }
            }, "synthetic-smtp-accept"); acceptor.setDaemon(true); acceptor.start();
        }
        int port() { return listener.getLocalPort(); }
        LoginVerificationMailAdapter.Connector connector() { return (attempt, settings) -> {
            Socket socket = new Socket(); attempt.add(socket); socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port()), 200); attempt.check(); return socket;
        }; }
        boolean awaitConnections(int count) throws InterruptedException {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (sessions.size() < count && System.nanoTime() < end) Thread.sleep(5);
            return sessions.size() >= count;
        }
        Session awaitSession() throws Exception { check(awaitConnections(1), "synthetic server accepted connection"); return sessions.get(0); }
        private void serve(Session session) {
            try (Socket socket = session.socket;
                 BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                 BufferedWriter output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII))) {
                if (socket instanceof SSLSocket secure) secure.startHandshake();
                if (mode == Mode.GATED_GREETING) gate.await(3, TimeUnit.SECONDS);
                reply(output, mode == Mode.BAD_GREETING ? "250 " + SECRET : "220 synthetic.test ESMTP");
                String command;
                while ((command = input.readLine()) != null) {
                    session.commands.add(command);
                    if (command.startsWith("EHLO ")) {
                        if (mode == Mode.BAD_EHLO_CODE) { reply(output, "550 " + SECRET); continue; }
                        if (mode == Mode.WRONG_MULTILINE) { reply(output, "250-synthetic.test"); reply(output, "550 AUTH LOGIN"); continue; }
                        reply(output, "250-synthetic.test");
                        if (mode == Mode.SLOW_EHLO) for (int i=0; i<12; i++) { Thread.sleep(100); reply(output, "250-EXT" + i); }
                        if (mode != Mode.NO_AUTH) reply(output, "250-AUTH " + (mode == Mode.PLAIN ? "PLAIN" : mode == Mode.BOTH || mode == Mode.AUTH_FAIL ? "PLAIN LOGIN" : "LOGIN"));
                        reply(output, "250 SIZE 100000");
                    } else if (command.equals("AUTH LOGIN")) {
                        if (mode == Mode.AUTH_FAIL) { reply(output, "535 " + SECRET); continue; }
                        reply(output, "334 VXNlcm5hbWU6"); String user = input.readLine();
                        reply(output, "334 UGFzc3dvcmQ6"); String secret = input.readLine();
                        session.authenticated = USER.equals(decode(user)) && SECRET.equals(decode(secret));
                        reply(output, session.authenticated ? "235 Authenticated" : "535 Synthetic authentication mismatch");
                    } else if (command.startsWith("AUTH PLAIN")) {
                        String proof = command.length() > 11 ? command.substring(11) : null;
                        if (proof == null || proof.isBlank()) { reply(output, "334 "); proof = input.readLine(); }
                        session.authenticated = ("\0" + USER + "\0" + SECRET).equals(decode(proof));
                        reply(output, session.authenticated ? "235 Authenticated" : "535 Synthetic authentication mismatch");
                    } else if (command.startsWith("MAIL FROM:") || command.startsWith("RCPT TO:")) reply(output, session.authenticated ? "250 OK" : "530 Authentication required");
                    else if (command.equals("DATA")) {
                        if (mode == Mode.DATA_REJECT) { reply(output, "550 " + SECRET); continue; }
                        if (mode == Mode.GATED_DATA) gate.await(3, TimeUnit.SECONDS);
                        reply(output, "354 Continue"); StringBuilder body = new StringBuilder(); String line;
                        while ((line = input.readLine()) != null && !line.equals(".")) body.append(line).append("\r\n");
                        if (line == null) return; session.message = body.toString();
                        if (mode == Mode.COMMIT_REJECT) reply(output, "554 " + SECRET);
                        else { session.accepted = true; reply(output, "250 Queued"); }
                    } else if (command.equals("QUIT")) { reply(output, "221 Bye"); return; }
                    else reply(output, "500 Unexpected synthetic command");
                }
            } catch (IOException | InterruptedException ignored) { /* Expected for rejection/cancellation/TLS tests. */ }
            finally { session.done.countDown(); }
        }
        public void close() throws Exception {
            closed = true; gate.countDown(); listener.close();
            for (Session session : sessions) session.socket.close(); acceptor.join(1000);
            for (Session session : sessions) session.done.await(1000, TimeUnit.MILLISECONDS);
        }
    }
    private static void reply(BufferedWriter writer, String line) throws IOException { writer.write(line); writer.write("\r\n"); writer.flush(); }
    private static String decode(String input) { try { return new String(Base64.getDecoder().decode(input), StandardCharsets.UTF_8); } catch (Exception ignored) { return ""; } }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void failure(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Expected a sanitized mail failure"); }
        catch (IOException expected) {
            check(expected.getCause() == null && expected.getSuppressed().length == 0, "mail failure has no nested transport exception");
            check(expected.getMessage() != null && !expected.getMessage().isBlank() && !containsSensitive(expected.toString()), "mail error contains no recipient, code, or credentials");
            if (failureMessage == null) failureMessage = expected.getMessage();
            check(failureMessage.equals(expected.getMessage()), "mail failures use one fixed message");
        }
    }
    private static void sendUnchecked(LoginVerificationMailAdapter mail) { try { mail.send(TO, CODE); } catch (Exception e) { throw new AssertionError("Synthetic send did not succeed"); } }
    private static boolean containsSensitive(String value) { return value.contains(USER) || value.contains(SECRET) || value.contains(TO) || value.contains(CODE); }
    private static long elapsedMillis(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    private static void awaitIgnoringInterrupts(CountDownLatch latch, long millis) {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < end) try { if (latch.await(Math.max(1, end-System.nanoTime()), TimeUnit.NANOSECONDS)) return; } catch (InterruptedException ignored) { }
    }
    private static void check(boolean condition, String description) { checks++; if (!condition) throw new AssertionError(description); }
}
