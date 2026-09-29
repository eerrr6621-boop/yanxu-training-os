#!/usr/bin/env bash
set -euo pipefail
s01_mail_script_dir="$(cd "$(dirname "$0")" && pwd)"
s01_mail_root="${APP_ROOT:-$(cd "$s01_mail_script_dir/.." && pwd)}"
s01_mail_java="${S01_LOGIN_JAVA:-java}"
s01_mail_java="$(command -v "$s01_mail_java")" || { echo 'Java 17 executable was not found on PATH or at the configured location.' >&2; exit 2; }
s01_mail_source="${S01_LOGIN_MAIL_SOURCE:-$s01_mail_script_dir/../src/com/training/LoginVerificationMailAdapter.java}"
[[ -f "$s01_mail_source" ]] || s01_mail_source="$s01_mail_root/src/com/training/LoginVerificationMailAdapter.java"
[[ -x "$s01_mail_java" && -f "$s01_mail_root/lib/ecj.jar" && -f "$s01_mail_source" ]] || {
  echo 'Set APP_ROOT to the app directory; Java 17, ECJ, and the adapter source are required.' >&2
  exit 2
}
command -v openssl >/dev/null || { echo 'OpenSSL is required for temporary synthetic TLS fixtures.' >&2; exit 2; }
s01_mail_tmp="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-s01-mail-adapter.XXXXXX")"
# This invocation owns only these temporary classes, test credentials, and test certificates.
trap 'rm -rf "$s01_mail_tmp"' EXIT
mkdir -p "$s01_mail_tmp/classes" "$s01_mail_tmp/fixture/com/training"
cat > "$s01_mail_tmp/fixture/com/training/NotificationChannelsLoginVerification.java" <<'JAVA'
package com.training;
public final class NotificationChannelsLoginVerification {
    @FunctionalInterface public interface MailSender {
        void send(String email, String code) throws Exception;
        default void sendFirst(String email, String code, long remainingSeconds) throws Exception { send(email, code); }
    }
}
JAVA
cat > "$s01_mail_tmp/fixture/com/training/Api.java" <<'JAVA'
package com.training;
public final class Api { public static final Object MUTATION_LOCK = new Object(); }
JAVA
cat > "$s01_mail_tmp/tls.conf" <<'CONF'
[req]
distinguished_name = dn
x509_extensions = ext
prompt = no
[dn]
CN = localhost
[ext]
subjectAltName = DNS:localhost
basicConstraints = critical,CA:TRUE
keyUsage = critical,digitalSignature,keyEncipherment,keyCertSign
extendedKeyUsage = serverAuth
CONF
# Synthetic keys stay inside the temporary fixture. No network or real keychain access.
openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
  -config "$s01_mail_tmp/tls.conf" -keyout "$s01_mail_tmp/tls.key" \
  -out "$s01_mail_tmp/tls.crt" > "$s01_mail_tmp/openssl.log" 2>&1
openssl pkcs12 -export -inkey "$s01_mail_tmp/tls.key" -in "$s01_mail_tmp/tls.crt" \
  -out "$s01_mail_tmp/tls.p12" -name loopback-test -passout pass:synthetic-test-only \
  >> "$s01_mail_tmp/openssl.log" 2>&1
"$s01_mail_java" -jar "$s01_mail_root/lib/ecj.jar" -17 -encoding UTF-8 -nowarn \
  -d "$s01_mail_tmp/classes" \
  "$s01_mail_tmp/fixture/com/training/NotificationChannelsLoginVerification.java" \
  "$s01_mail_tmp/fixture/com/training/Api.java" \
  "$s01_mail_source" "$s01_mail_script_dir/S01LoginVerificationMailAdapterTest.java"
cd "$s01_mail_tmp"
"$s01_mail_java" -cp "$s01_mail_tmp/classes" \
  com.training.S01LoginVerificationMailAdapterTest "$s01_mail_tmp"
