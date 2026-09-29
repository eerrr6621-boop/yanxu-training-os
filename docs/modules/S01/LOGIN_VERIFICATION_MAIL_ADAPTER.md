# 服务器登录验证码发件适配器

日期：2026-09-23。实现 `LoginVerificationMailAdapter.java`，仅为原邮箱验证码核心提供 MailSender。不得从浏览器接收 SMTP 地址、账号、授权码、收件人或邮件模板；真实收件人和验证码由原登录核验核心提供。适配器不读取业务数据库、不创建账号、不修改登录模式或业务权限。

## Main 的只读初始化接点

在接受 HTTP 请求前调用一次：

```java
var mail = LoginVerificationMailAdapter.fromEnvironment();
LoginVerificationHost.installService(
    new NotificationChannelsLoginVerification(System::currentTimeMillis, mail.sender()));
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    if (mail.sender() != null) mail.sender().close();
}, "yanxu-login-mail-shutdown"));
```

此初始化只读取明确选择的服务器配置与凭据，不连接 SMTP，也不发信。可记录 `mail.state()` 的固定枚举，禁止输出环境变量、凭据对象内部、传输异常或命令输出。适配器及凭据对象的 toString 已脱敏。

| state | sender | 含义 |
| --- | --- | --- |
| NOT_CONFIGURED | null | 未启用发件配置。 |
| CONFIGURATION_INVALID | null | 配置来源、参数或值不符合约定。 |
| CREDENTIAL_UNAVAILABLE | null | 无法读取指定凭据，或凭据格式/权限不符合约定。 |
| CONFIGURED | 非 null | 配置材料已读取，可尝试发送；尚不代表 SMTP 认证成功或实际送达。 |

required 模式仍由原 Host/Auth 管理。任何 null sender 都使邮箱核验失败关闭，不切换 legacy，不豁免管理员，不自动把缺邮箱账号放行。适配器不接管既有站内通知或 workflow outbox，更不补发历史通知。

## 服务器环境配置

所有设置仅来自服务端环境变量；没有对应 HTTP 配置入口。产品固定使用普通 163 的 `smtp.163.com:465` 隐式 TLS，不提供任意主机/明文端口/跳过证书校验选项。

| 环境变量 | 默认/允许值 |
| --- | --- |
| YANXU_LOGIN_MAIL_TRANSPORT | 缺省、空或 disabled：不加载凭据；启用时仅 163_smtps。 |
| YANXU_LOGIN_SMTP_CREDENTIAL_SOURCE | 启用时必填：macos_keychain 或 file。 |
| YANXU_LOGIN_SMTP_CREDENTIAL_FILE | 仅 file 模式必填：受控凭据文件的绝对、规范路径。 |
| YANXU_LOGIN_SMTP_CONNECT_TIMEOUT_MS | 默认 5000；100–10000。 |
| YANXU_LOGIN_SMTP_READ_TIMEOUT_MS | 默认 5000；100–10000。 |
| YANXU_LOGIN_SMTP_SEND_TIMEOUT_MS | 默认 15000；500–30000，且不小于前两项。 |
| YANXU_LOGIN_SMTP_MAX_CONCURRENT | 默认 2；1–4。 |
| YANXU_LOGIN_SMTP_MAX_PER_HOUR | 默认 60；1–1024。 |

整数参数只接受普通正十进制文本，不接受空值、符号、空格或指数。未知 SMTP 配置键拒绝；不接受在环境中直接填写 password/authorization_code。适配器的默认小时限额是本系统的保守发送上限，不是网易公布的账号配额；原验证码核心每账号/IP/全局限频仍同时生效。

### 复用本机 163 钥匙串记录

macos_keychain 模式固定只读服务 `com.yanxu.S01.smtp.163`，不创建或覆盖条目。先读取对应账号元数据，核对该账号为普通 163 地址，再通过固定系统程序读取该账号的授权码。授权码不出现在命令参数、环境变量、控制台或日志。每个系统读取命令最多等待 5 秒，输出有长度上限；系统拒绝访问、等待超时或非 macOS 环境均返回 CREDENTIAL_UNAVAILABLE。

系统可能要求当前运行用户授权访问钥匙串，既有条目不保证所有进程可静默读取。此适配器实现和合成测试没有实际执行钥匙串读取，也没有复制本机凭据到项目。切换服务运行用户或迁移服务器时，应在目标受控环境配置，不能把本机钥匙串复制到 Git。

### 私有文件来源

file 模式适用于支持 POSIX 权限的服务端。文件由运行用户所有，权限只能为 0400 或 0600；普通文件、最大 4096 字节，无符号链接。路径祖先必须由运行用户或 root 所有且不可由其他用户写入；root 所有并带 sticky 位的临时目录可用。读取前后核对文件身份、所有者、权限、大小及修改时间，发现变化即拒绝。

文件恰好两行，UTF-8、LF 换行，可有一个末尾 LF：第一行键为 `username`，第二行键为 `authorization_code`，均以等号紧接实际值。不接受空白装饰、重复键或额外字段。账号必须是普通 @163.com 邮箱；授权码使用邮件客户端授权码，不用网页登录密码。文件应由部署配置工具写入受控目录，不放入项目、源码、静态站点、测试数据或 Git。不要把文件内容传入浏览器。

## 传输和错误语义

- 只启用 TLS 1.2/1.3，使用 JDK 默认信任链，显式验证服务器主机名并发送 SNI。TLS 握手成功后才读取 SMTP 和认证；不降级到明文。
- 读取 EHLO 声明的能力，只使用服务端实际声明的 AUTH LOGIN 或 PLAIN；LOGIN 优先，失败不换机制盲重试。响应代码、同码多行、行长及最多 32 行均校验。
- 收件人和发件人严格检查格式，拒绝 CRLF/多地址/头部注入。邮件为固定中文文本，以 UTF-8 MIME Base64 编码；只含本次登录验证码及自申请起 10 分钟有效的说明。
- DATA 后服务器最终返回 250 才表示服务器已接受。之后 QUIT 或关闭失败不能把已确认接受改成发送失败；服务器接受仍不等于用户已收到。
- 网络/认证/拒收/超时等异常统一为固定、无 cause 的错误，不把服务器回复、邮箱、验证码或授权码暴露给上层。原核心将发送失败转为 503 并删除当前挑战。
- 不自动重试或后台排队。如果服务器已接受但成功回复未能在截止前被客户端确认，结果存在网络层不确定性；仍按失败处理，不自行重复发信，收到的旧验证码也不能绕过核心的失效判定。

端点参数参考客户端厂商的一手配置资料：[华为云客户端文档](https://support.huaweicloud.com/faq-welink/faq-welink.pdf)、[Lexmark 客户端文档](https://publications.lexmark.com/publications/lexmark_hardware/QRG/CX930_CX931_XC9325_XC9335/Lexmark_CX930_CX931_XC9325_XC9335_QRG_sc.pdf)。本轮未能打开网易帮助站，因此不声称已经核对网易账号实时能力；实际 AUTH 能力以 TLS 后的 EHLO 为准。协议依据 [RFC 5321](https://www.rfc-editor.org/rfc/rfc5321)、[RFC 4954](https://www.rfc-editor.org/rfc/rfc4954)、[RFC 4616](https://www.rfc-editor.org/rfc/rfc4616)；TLS 校验依据 [JDK SSLParameters](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/javax/net/ssl/SSLParameters.html)。

## 超时、限流和关闭

连接超时、单次读取超时之外，整次发送还有使用单调时钟的期限，覆盖 DNS、连接、TLS、认证及写入等待。最多保留指定数量的工作线程，没有排队缓冲。调用超时会设置取消标记、先关闭原始 TCP socket，再关闭 TLS socket并取消任务；DNS 即使晚返回，也必须再次通过取消检查才能继续。传统 DNS 不能保证立即被中断，滞留任务最多占据既定线程数，其间额外请求立即失败，不无限创建线程。

每个 SMTP 阶段推进及写入前检查取消/期限。发送尝试在进入工作线程前占小时额度，失败、超时和并发饱和不退还额度；原核心的配额也不回退。取消任务不会继续从 DNS/连接阶段推进到认证或发信。适配器 close 会拒绝新请求、关闭活动连接、停止线程并清除可变凭据缓存。

配额仅为单进程内存计数，重新创建适配器会重置；Main 应只在启动时组合一次。默认总超时 15 秒须与 HTTP 响应时限一致，不应在请求中反复创建适配器或读取钥匙串。

## 测试与发布前的实际缺口

专项 `S01LoginVerificationMailAdapter-check.sh` **319 项通过**，只使用本机回环合成 SMTP/TLS 服务、临时生成的证书和虚构凭据；不会调用真实钥匙串、连接外部 SMTP 或读取 app/data。测试以最小 MailSender/业务锁编译夹具运行，另行全应用编译通过以确认真实接口匹配。两种验证均不能替代真实认证与送达，具体覆盖另记 S01 STATUS。

本轮实现适配器不代表可以直接把现网模式切成 required。以下现有身份边界仍需总控解决：

1. 用户已明确正式总管理员使用原有 `manager01`，不再询问人选。总控已只读确认其当前角色为 manager，计划正式发布备份后按授权调整为 admin；本适配器没有修改该账号。required 会覆盖所有账号，而当前 loginSnapshot 只支持有导入关系及完整候选邮箱历史的账号，`manager01` 等非导入账号会无法登录。必须先为已确定的管理员提供可信、经过权限与历史审计的邮箱准备途径；若仍缺核验邮箱，只缺该地址这一项。不能通过管理员豁免或失败时降级绕过。此准备途径及角色变更由总控另行接入。
2. 当前候选邮箱登记只允许停用、viewer、password NULL 的导入账号。个人邮箱必须先登记，再设置首次密码及启用；一旦有密码，既有准备接口拒绝修改。启用后的邮箱纠错及重验证应单独设计，适配器不能偷偷放宽原资格。
3. 已有名单未提供逐人身份核验邮箱。163 发件账号是系统发送资源，不能代替每个人的收件邮箱，也不能据来源 ID 推算邮箱。
4. 电脑登录的真实 SMTP 认证与一次明确收件目标的送达验收、最终服务器受控配置及 required 切换由总控统一进行。本轮没有实际外发、生产配置或账号变更；用户取消手机办理不等于取消电脑邮箱核验。设备记忆期限仍未确定，本适配器不猜定。
