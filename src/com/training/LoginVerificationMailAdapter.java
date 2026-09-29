package com.training;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.regex.*;
import javax.net.ssl.*;

/** Server-only 163 SMTP submission. Startup reads explicit protected configuration; it never connects. */
public final class LoginVerificationMailAdapter implements NotificationChannelsLoginVerification.MailSender, AutoCloseable {
    private static final String SMTP_HOST="smtp.163.com", KEYCHAIN_SERVICE="com.yanxu.S01.smtp.163";
    private static final int SMTP_PORT=465, MAX_CREDENTIAL_BYTES=4096;
    private static final long HOUR_NANOS=TimeUnit.HOURS.toNanos(1);
    private static final Set<String> CONFIG_KEYS=Set.of("YANXU_LOGIN_MAIL_TRANSPORT", "YANXU_LOGIN_SMTP_CREDENTIAL_SOURCE",
            "YANXU_LOGIN_SMTP_CREDENTIAL_FILE", "YANXU_LOGIN_SMTP_CONNECT_TIMEOUT_MS", "YANXU_LOGIN_SMTP_READ_TIMEOUT_MS",
            "YANXU_LOGIN_SMTP_SEND_TIMEOUT_MS", "YANXU_LOGIN_SMTP_MAX_CONCURRENT", "YANXU_LOGIN_SMTP_MAX_PER_HOUR");

    public enum State { NOT_CONFIGURED, CONFIGURED, CONFIGURATION_INVALID, CREDENTIAL_UNAVAILABLE }
    public record Startup(LoginVerificationMailAdapter sender, State state) {
        @Override public String toString() {return "Startup[state="+state+"]";}
    }
    record Settings(int connectMillis,int readMillis,int totalMillis,int concurrency,int hourlyLimit) {
        Settings {
            if(connectMillis<100||connectMillis>10_000||readMillis<100||readMillis>10_000
                    ||totalMillis<500||totalMillis>30_000||totalMillis<connectMillis||totalMillis<readMillis
                    ||concurrency<1||concurrency>4||hourlyLimit<1||hourlyLimit>1024)throw new IllegalArgumentException();
        }
    }
    @FunctionalInterface interface CredentialLoader {Credentials load(String source,Path credentialFile) throws Exception;}
    @FunctionalInterface interface Connector {Socket connect(Attempt attempt,Settings settings) throws Exception;}
    @FunctionalInterface interface CommandRunner {byte[] run(List<String> command,int timeoutMillis,int maxBytes) throws Exception;}

    static final class Credentials implements AutoCloseable {
        final String username;
        private char[] authorizationCode;
        Credentials(String username,char[] authorizationCode) throws IOException {
            if(!validAddress(username)||!username.endsWith("@163.com")||authorizationCode==null
                    ||authorizationCode.length<1||authorizationCode.length>512)throw unavailable();
            for(char c:authorizationCode)if(c<33||c>126)throw unavailable();
            this.username=username;this.authorizationCode=authorizationCode.clone();
        }
        synchronized byte[] secretBytes() throws IOException {
            if(authorizationCode==null)throw unavailable();
            byte[] result=new byte[authorizationCode.length];
            for(int i=0;i<result.length;i++)result[i]=(byte)authorizationCode[i];
            return result;
        }
        @Override public synchronized void close() {if(authorizationCode!=null)Arrays.fill(authorizationCode,'\0');authorizationCode=null;}
        @Override public String toString() {return "Credentials[redacted]";}
    }

    private final Settings settings;
    private final Credentials credentials;
    private final Connector connector;
    private final LongSupplier nanoClock;
    private final ThreadPoolExecutor workers;
    private final Set<Attempt> active=ConcurrentHashMap.newKeySet();
    private final Deque<Long> sends=new ArrayDeque<>();
    private final AtomicBoolean closed=new AtomicBoolean();
    private long lastRateTime=Long.MIN_VALUE;

    LoginVerificationMailAdapter(Settings settings,Credentials credentials,Connector connector,LongSupplier nanoClock) {
        this.settings=Objects.requireNonNull(settings);this.credentials=Objects.requireNonNull(credentials);
        this.connector=Objects.requireNonNull(connector);this.nanoClock=Objects.requireNonNull(nanoClock);
        workers=new ThreadPoolExecutor(0,settings.concurrency(),30,TimeUnit.SECONDS,new SynchronousQueue<>(),r -> {
            Thread thread=new Thread(r,"yanxu-login-mail");thread.setDaemon(true);return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }

    /** Call once before accepting HTTP requests. No process/network is touched when transport is disabled. */
    public static Startup fromEnvironment() {
        try {
            return fromEnvironment(System.getenv(),(source,file) -> "file".equals(source)?readCredentialFile(file):readKeychain(LoginVerificationMailAdapter::runCommand),
                    (attempt,settings) -> connectTls(SMTP_HOST,SMTP_PORT,(SSLSocketFactory)SSLSocketFactory.getDefault(),attempt,settings),System::nanoTime);
        } catch(Exception ignored) {return new Startup(null,State.CONFIGURATION_INVALID);}
    }

    static Startup fromEnvironment(Map<String,String> environment,CredentialLoader loader,Connector connector,LongSupplier clock) {
        Settings settings;String source;Path file=null;
        try {
            String transport=environment.get("YANXU_LOGIN_MAIL_TRANSPORT");
            if(transport==null||transport.isEmpty()||transport.equals("disabled"))return new Startup(null,State.NOT_CONFIGURED);
            if(!transport.equals("163_smtps"))throw new IllegalArgumentException();
            for(String key:environment.keySet())if((key.startsWith("YANXU_LOGIN_SMTP_")||key.equals("YANXU_LOGIN_MAIL_TRANSPORT"))&&!CONFIG_KEYS.contains(key))throw new IllegalArgumentException();
            source=environment.get("YANXU_LOGIN_SMTP_CREDENTIAL_SOURCE");
            if(!Set.of("macos_keychain","file").contains(Objects.toString(source,"")))throw new IllegalArgumentException();
            String path=environment.get("YANXU_LOGIN_SMTP_CREDENTIAL_FILE");
            if(source.equals("file")) {
                if(path==null||path.isEmpty()||!Path.of(path).isAbsolute())throw new IllegalArgumentException();
                file=Path.of(path);
            } else if(path!=null&&!path.isEmpty())throw new IllegalArgumentException();
            settings=new Settings(integer(environment,"CONNECT_TIMEOUT_MS",5000),integer(environment,"READ_TIMEOUT_MS",5000),
                    integer(environment,"SEND_TIMEOUT_MS",15000),integer(environment,"MAX_CONCURRENT",2),integer(environment,"MAX_PER_HOUR",60));
        } catch(Exception ignored) {return new Startup(null,State.CONFIGURATION_INVALID);}
        Credentials credentials=null;
        try {
            credentials=loader.load(source,file);if(credentials==null)throw unavailable();
            return new Startup(new LoginVerificationMailAdapter(settings,credentials,connector,clock),State.CONFIGURED);
        } catch(Exception ignored) {
            if(credentials!=null)credentials.close();return new Startup(null,State.CREDENTIAL_UNAVAILABLE);
        }
    }
    private static int integer(Map<String,String> environment,String suffix,int fallback) {
        String value=environment.get("YANXU_LOGIN_SMTP_"+suffix);if(value==null)return fallback;
        if(!value.matches("[1-9][0-9]{0,5}"))throw new IllegalArgumentException();return Integer.parseInt(value);
    }

    @Override public void send(String email,String code) throws IOException {send(email,code,false,600);}
    @Override public void sendFirst(String email,String code,long remainingSeconds) throws IOException {
        if(remainingSeconds<1||remainingSeconds>600)throw unavailable();
        send(email,code,true,remainingSeconds);
    }
    private void send(String email,String code,boolean firstBind,long remainingSeconds) throws IOException {
        if(Thread.holdsLock(Api.MUTATION_LOCK)||!validAddress(email)||code==null||!code.matches("[0-9]{6}"))throw unavailable();
        reserve();Attempt attempt=new Attempt(settings.totalMillis());active.add(attempt);Future<?> work=null;
        try {
            if(closed.get())throw unavailable();
            work=workers.submit(() -> {try {submit(attempt,email,code,firstBind,remainingSeconds);}finally {attempt.cancel();active.remove(attempt);}return null;});
            work.get(settings.totalMillis(),TimeUnit.MILLISECONDS);
        } catch(InterruptedException ignored) {
            Thread.currentThread().interrupt();if(!attempt.accepted)throw unavailable();
        } catch(Exception ignored) {
            if(!attempt.accepted)throw unavailable();
        } finally {
            attempt.cancel();if(work!=null&&!work.isDone())work.cancel(true);
            if(work==null)active.remove(attempt);
        }
    }

    private synchronized void reserve() throws IOException {
        if(closed.get())throw unavailable();
        long now;try {now=Math.max(lastRateTime,nanoClock.getAsLong());}catch(Exception ignored){throw unavailable();}
        lastRateTime=now;
        while(!sends.isEmpty()&&now-sends.peekFirst()>=HOUR_NANOS)sends.removeFirst();
        if(sends.size()>=settings.hourlyLimit())throw unavailable();sends.addLast(now);
    }

    private void submit(Attempt attempt,String recipient,String code,boolean firstBind,long remainingSeconds) throws IOException {
        try {
            attempt.check();Socket socket=connector.connect(attempt,settings);attempt.add(socket);attempt.check();
            socket.setSoTimeout(settings.readMillis());
            InputStream input=socket.getInputStream();OutputStream output=socket.getOutputStream();
            expect(reply(input,attempt),220);command(output,attempt,"EHLO yanxu.local");
            Reply capabilities=reply(input,attempt);expect(capabilities,250);
            Set<String> methods=new HashSet<>();
            for(String line:capabilities.lines()) {
                String upper=line.toUpperCase(Locale.ROOT);
                if(upper.startsWith("AUTH ")||upper.startsWith("AUTH="))methods.addAll(Arrays.asList(upper.substring(5).trim().split(" +")));
            }
            byte[] user=credentials.username.getBytes(StandardCharsets.US_ASCII),secret=credentials.secretBytes();
            try {
                if(methods.contains("LOGIN")) {
                    command(output,attempt,"AUTH LOGIN");expect(reply(input,attempt),334);
                    authentication(output,attempt,user);expect(reply(input,attempt),334);
                    authentication(output,attempt,secret);expect(reply(input,attempt),235);
                } else if(methods.contains("PLAIN")) {
                    byte[] plain=new byte[user.length+secret.length+2];
                    System.arraycopy(user,0,plain,1,user.length);System.arraycopy(secret,0,plain,user.length+2,secret.length);
                    try {command(output,attempt,"AUTH PLAIN");expect(reply(input,attempt),334);authentication(output,attempt,plain);expect(reply(input,attempt),235);}
                    finally {Arrays.fill(plain,(byte)0);}
                } else throw unavailable();
            } finally {Arrays.fill(user,(byte)0);Arrays.fill(secret,(byte)0);}
            command(output,attempt,"MAIL FROM:<"+credentials.username+">");expect(reply(input,attempt),250);
            command(output,attempt,"RCPT TO:<"+recipient+">");Reply rcpt=reply(input,attempt);if(rcpt.code()!=250&&rcpt.code()!=251)throw unavailable();
            command(output,attempt,"DATA");expect(reply(input,attempt),354);
            byte[] message=message(credentials.username,recipient,code,firstBind,remainingSeconds);
            try {attempt.check();output.write(message);output.flush();}finally {Arrays.fill(message,(byte)0);}
            expect(reply(input,attempt),250);attempt.accepted=true;
            // Server acceptance is final. A failed/blocked QUIT must never cause automatic resubmission.
            try {command(output,attempt,"QUIT");}catch(Exception ignored){}
        } catch(Exception ignored) {if(!attempt.accepted)throw unavailable();}
    }

    static Socket connectTls(String host,int port,SSLSocketFactory factory,Attempt attempt,Settings settings) throws Exception {
        Socket raw=new Socket();attempt.add(raw);attempt.check();
        InetSocketAddress address=new InetSocketAddress(host,port);attempt.check(); // DNS may finish after cancellation.
        raw.connect(address,settings.connectMillis());attempt.check();raw.setSoTimeout(settings.readMillis());
        SSLSocket tls=(SSLSocket)factory.createSocket(raw,host,port,true);attempt.add(tls);
        SSLParameters parameters=tls.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");
        if(!host.matches("[0-9.:]+"))parameters.setServerNames(List.of(new SNIHostName(host)));
        parameters.setProtocols(Arrays.stream(tls.getSupportedProtocols()).filter(p -> p.equals("TLSv1.3")||p.equals("TLSv1.2")).toArray(String[]::new));
        tls.setSSLParameters(parameters);tls.setSoTimeout(settings.readMillis());attempt.check();tls.startHandshake();attempt.check();return tls;
    }

    static final class Attempt {
        private final long start=System.nanoTime(),limit;
        private final List<Socket> sockets=new ArrayList<>();
        private boolean cancelled;
        volatile boolean accepted;
        Attempt(int totalMillis){limit=TimeUnit.MILLISECONDS.toNanos(totalMillis);}
        synchronized void check() throws IOException {if(cancelled||Thread.currentThread().isInterrupted()||System.nanoTime()-start>=limit)throw unavailable();}
        void add(Socket socket) throws IOException {
            boolean reject;
            synchronized(this){reject=cancelled;if(!reject)sockets.add(Objects.requireNonNull(socket));}
            if(reject){closeSocket(socket);throw unavailable();}
        }
        void cancel() {
            List<Socket> current;
            synchronized(this){cancelled=true;current=new ArrayList<>(sockets);sockets.clear();}
            // The raw TCP socket was registered first; closing it also releases blocked TLS writes.
            for(Socket socket:current)closeSocket(socket);
        }
        private static void closeSocket(Socket socket){try{socket.close();}catch(Exception ignored){}}
    }

    private record Reply(int code,List<String> lines) {}
    private static Reply reply(InputStream input,Attempt attempt) throws IOException {
        List<String> lines=new ArrayList<>();int code=-1;
        for(int count=0;count<32;count++) {
            String line=line(input,attempt);
            if(line.length()<4||!line.substring(0,3).matches("[2-5][0-9]{2}")||(line.charAt(3)!=' '&&line.charAt(3)!='-'))throw unavailable();
            int current=Integer.parseInt(line.substring(0,3));if(code!=-1&&code!=current)throw unavailable();code=current;
            lines.add(line.substring(4));if(line.charAt(3)==' ')return new Reply(code,lines);
        }
        throw unavailable();
    }
    private static String line(InputStream input,Attempt attempt) throws IOException {
        StringBuilder result=new StringBuilder();
        for(int i=0;i<512;i++) {
            attempt.check();int value=input.read();if(value<0)throw unavailable();
            if(value=='\r'){attempt.check();if(input.read()!='\n')throw unavailable();return result.toString();}
            if(value<32||value>126)throw unavailable();result.append((char)value);
        }
        throw unavailable();
    }
    private static void expect(Reply reply,int expected) throws IOException {if(reply.code()!=expected)throw unavailable();}
    private static void command(OutputStream output,Attempt attempt,String value) throws IOException {
        byte[] bytes=value.getBytes(StandardCharsets.US_ASCII);try{writeLine(output,attempt,bytes);}finally{Arrays.fill(bytes,(byte)0);}
    }
    private static void authentication(OutputStream output,Attempt attempt,byte[] value) throws IOException {
        byte[] encoded=Base64.getEncoder().encode(value);try{writeLine(output,attempt,encoded);}finally{Arrays.fill(encoded,(byte)0);}
    }
    private static void writeLine(OutputStream output,Attempt attempt,byte[] bytes) throws IOException {
        attempt.check();output.write(bytes);output.write('\r');output.write('\n');output.flush();
    }
    private static byte[] message(String sender,String recipient,String code,boolean firstBind,long remainingSeconds) {
        String content=firstBind?"您的研序首次邮箱绑定验证码是："+code+"\n请在登录页面显示的截止时间前完成绑定。本次发送时剩余有效时间不超过"+remainingSeconds+"秒，重发不会延长首次申请的有效期。请勿向他人透露。如非本人操作，请忽略此邮件。\n"
                :"您的研序登录验证码是："+code+"\n验证码自申请起10分钟内有效，请勿向他人透露。如非本人操作，请忽略此邮件。\n";
        byte[] body=content.getBytes(StandardCharsets.UTF_8);
        String encoded=Base64.getMimeEncoder(76,new byte[]{'\r','\n'}).encodeToString(body);Arrays.fill(body,(byte)0);
        String subject=Base64.getEncoder().encodeToString((firstBind?"研序首次邮箱绑定验证码":"研序登录验证码").getBytes(StandardCharsets.UTF_8));
        String mail="Date: "+DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC))+"\r\n"
                +"Message-ID: <"+UUID.randomUUID()+"@163.com>\r\nFrom: <"+sender+">\r\nTo: <"+recipient+">\r\n"
                +"Subject: =?UTF-8?B?"+subject+"?=\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n"
                +"Content-Transfer-Encoding: base64\r\n\r\n"+encoded+"\r\n.\r\n";
        return mail.getBytes(StandardCharsets.US_ASCII);
    }
    private static boolean validAddress(String email) {
        if(email==null||email.length()>254||email.indexOf('@')<1||email.indexOf('@')!=email.lastIndexOf('@'))return false;
        int at=email.indexOf('@');String local=email.substring(0,at),domain=email.substring(at+1);
        if(at>64||!local.matches("[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]+")||local.startsWith(".")||local.endsWith(".")||local.contains(".."))return false;
        String[] labels=domain.split("\\.",-1);if(labels.length<2)return false;
        for(String label:labels)if(label.length()>63||!label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"))return false;
        return !labels[labels.length-1].matches("[0-9]+");
    }

    static Credentials readCredentialFile(Path path) throws IOException {
        byte[] bytes=null;char[] secret=null;
        try {
            if(path==null||!path.isAbsolute())throw unavailable();
            path=path.normalize();if(!path.equals(path.toRealPath())||Files.isSymbolicLink(path))throw unavailable();
            protectedAncestors(path);
            PosixFileAttributes attributes=Files.readAttributes(path,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!attributes.isRegularFile()||attributes.size()<1||attributes.size()>MAX_CREDENTIAL_BYTES
                    ||!attributes.owner().equals(path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"))))throw unavailable();
            Set<PosixFilePermission> allowed=Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE);
            if(!attributes.permissions().contains(PosixFilePermission.OWNER_READ)||!allowed.containsAll(attributes.permissions()))throw unavailable();
            try(InputStream input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(MAX_CREDENTIAL_BYTES+1);}
            if(bytes.length>MAX_CREDENTIAL_BYTES)throw unavailable();
            PosixFileAttributes after=Files.readAttributes(path,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!Objects.equals(attributes.fileKey(),after.fileKey())||!attributes.owner().equals(after.owner())
                    ||!attributes.permissions().equals(after.permissions())||attributes.size()!=after.size()
                    ||!attributes.lastModifiedTime().equals(after.lastModifiedTime()))throw unavailable();
            String text=new String(bytes,StandardCharsets.UTF_8);String[] lines=text.split("\n",-1);
            if(lines.length==3&&lines[2].isEmpty())lines=Arrays.copyOf(lines,2);
            if(lines.length!=2||!lines[0].startsWith("username=")||!lines[1].startsWith("authorization_code="))throw unavailable();
            secret=lines[1].substring("authorization_code=".length()).toCharArray();
            return new Credentials(lines[0].substring("username=".length()),secret);
        } catch(Exception ignored) {throw unavailable();}
        finally {if(bytes!=null)Arrays.fill(bytes,(byte)0);if(secret!=null)Arrays.fill(secret,'\0');}
    }

    private static void protectedAncestors(Path path) throws IOException {
        UserPrincipalLookupService lookup=path.getFileSystem().getUserPrincipalLookupService();
        UserPrincipal user=lookup.lookupPrincipalByName(System.getProperty("user.name")),root=lookup.lookupPrincipalByName("root");
        for(Path directory=path.getParent();directory!=null;directory=directory.getParent()) {
            PosixFileAttributes attributes=Files.readAttributes(directory,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!attributes.isDirectory()||(!attributes.owner().equals(user)&&!attributes.owner().equals(root)))throw unavailable();
            Set<PosixFilePermission> permissions=attributes.permissions();
            boolean writable=permissions.contains(PosixFilePermission.GROUP_WRITE)||permissions.contains(PosixFilePermission.OTHERS_WRITE);
            // A root-owned sticky temporary directory cannot be used to replace a user-owned child.
            if(writable&&(!attributes.owner().equals(root)||(((Number)Files.getAttribute(directory,"unix:mode",LinkOption.NOFOLLOW_LINKS)).intValue()&01000)==0))throw unavailable();
        }
    }

    static Credentials readKeychain(CommandRunner runner) throws IOException {
        byte[] metadata=null,bytes=null;char[] secret=null;
        try {
            metadata=runner.run(List.of("/usr/bin/security","find-generic-password","-s",KEYCHAIN_SERVICE),5000,8192);
            if(metadata==null||metadata.length>8192)throw unavailable();
            String text=new String(metadata,StandardCharsets.UTF_8);
            String account=attribute(text,"acct"),service=attribute(text,"svce");
            if(!KEYCHAIN_SERVICE.equals(service)||!validAddress(account)||!account.endsWith("@163.com"))throw unavailable();
            bytes=runner.run(List.of("/usr/bin/security","find-generic-password","-s",KEYCHAIN_SERVICE,"-a",account,"-w"),5000,514);
            if(bytes==null||bytes.length>514)throw unavailable();
            int length=bytes.length;if(length>0&&bytes[length-1]=='\n'){length--;if(length>0&&bytes[length-1]=='\r')length--;}
            secret=new char[length];for(int i=0;i<length;i++)secret[i]=(char)(bytes[i]&255);
            return new Credentials(account,secret);
        } catch(Exception ignored){throw unavailable();}
        finally {if(metadata!=null)Arrays.fill(metadata,(byte)0);if(bytes!=null)Arrays.fill(bytes,(byte)0);if(secret!=null)Arrays.fill(secret,'\0');}
    }
    private static String attribute(String metadata,String name) throws IOException {
        Matcher matcher=Pattern.compile("(?m)^\\s*\""+name+"\"<blob>=\"([^\"\\r\\n]+)\"[ \\t]*$").matcher(metadata);
        if(!matcher.find())throw unavailable();String result=matcher.group(1);if(matcher.find())throw unavailable();return result;
    }
    private static byte[] runCommand(List<String> command,int timeoutMillis,int maxBytes) throws IOException {
        Process process=null;
        try {
            if(!System.getProperty("os.name","").startsWith("Mac"))throw unavailable();
            process=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if(!process.waitFor(timeoutMillis,TimeUnit.MILLISECONDS)||process.exitValue()!=0)throw unavailable();
            byte[] bytes=process.getInputStream().readNBytes(maxBytes+1);
            if(bytes.length>maxBytes){Arrays.fill(bytes,(byte)0);throw unavailable();}return bytes;
        } catch(InterruptedException ignored){Thread.currentThread().interrupt();throw unavailable();}
        catch(Exception ignored){throw unavailable();}
        finally {if(process!=null){process.destroyForcibly();try{process.getInputStream().close();process.getOutputStream().close();process.getErrorStream().close();}catch(Exception ignored){}}}
    }
    private static IOException unavailable(){return new IOException("邮件发送服务暂不可用");}
    @Override public void close() {if(closed.compareAndSet(false,true)){for(Attempt attempt:active)attempt.cancel();workers.shutdownNow();credentials.close();}}
    @Override public String toString(){return "LoginVerificationMailAdapter[redacted]";}
}
