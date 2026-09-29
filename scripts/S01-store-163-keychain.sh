#!/usr/bin/env bash
# Store a user-provided credential locally. No network, mail, repository credential or secret argv.
set -euo pipefail
S01_SERVICE='com.yanxu.S01.smtp.163'
if [[ "$(uname -s)" != 'Darwin' ]]; then
  echo '此设置工具只适用于 macOS 系统钥匙串。' >&2
  exit 1
fi
if [[ ! -t 0 ]]; then
  echo '请在交互终端运行；不接受文件或管道中的授权码。' >&2
  exit 1
fi
printf '163 发件邮箱地址：'
IFS= read -r S01_SENDER
if [[ ! "$S01_SENDER" =~ ^[A-Za-z0-9._%+-]+@163\.com$ ]]; then
  echo '地址格式不符；本工具仅设置普通 @163.com 邮箱。' >&2
  exit 1
fi
if /usr/bin/security find-generic-password -s "$S01_SERVICE" >/dev/null 2>&1; then
  echo 'S01 发件凭据已存在。本工具不覆盖已有项，请在系统钥匙串中核对。' >&2
  exit 1
fi
# -w must be last: Apple's CLI prompts for the secret without echo instead of using argv.
# Empty trusted-app list: no application receives automatic password access.
if /usr/bin/security add-generic-password -a "$S01_SENDER" -s "$S01_SERVICE" \
    -l '研序 S01 通知发件邮箱（163）' -j '仅保存配置；邮件适配器未接入、未外发。' -T '' -w; then
  if /usr/bin/security find-generic-password -a "$S01_SENDER" -s "$S01_SERVICE" >/dev/null 2>&1; then
    echo '已保存到本机系统钥匙串；未连接邮箱或发送邮件。'
  else
    echo '保存后的元数据检查未通过；请在系统钥匙串确认。' >&2
    exit 1
  fi
else
  echo '系统钥匙串未确认保存成功；未写入配置文件。' >&2
  exit 1
fi
