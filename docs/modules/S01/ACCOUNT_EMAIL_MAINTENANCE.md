# 现有账号及启用后账号的登录核验邮箱维护

日期：2026-09-23。新类 `NotificationChannelsAccountEmailMaintenance`；兼容修改 `NotificationChannelsAccountEmailPreparation`。仅让已认证的系统管理员明确登记、修订或清空已有账号的登录核验候选邮箱，不发验证码、不验证邮箱归属、不设置密码、不激活账号、不调整角色或组织权限。

## 数据兼容和唯一当前邮箱

导入账号继续使用原 `s01_account_email_heads / revisions / requests` 三表。新维护入口直接追加这些原始记录，原修订序列不断开、不复制为第二份当前邮箱。原待用账号准备入口仍只允许 status=0、viewer、password NULL，资格没有放宽；新维护入口允许管理员维护已有 status=0/1 的账号，包括启用后的导入账号。

非导入账号使用新的 `s01_account_email_maintenance_heads / revisions`，外键直接指向真实 users。绑定摘要只包含稳定的 EXISTING 来源和 user_id，配合外键及删除保护防止账号 ID 被复用。正常修改用户名不破坏邮箱历史；每次保存仍必须精确确认当前用户名，历史保留当时的名字。没有创建导入人员或伪造来源关系。

`s01_account_email_maintenance_requests` 为两种来源共同记录管理员维护声明：真实 actor、目标、来源、当时确认的用户名、固定用途、UUID、expected/result revision、邮箱摘要与确认声明摘要。导入维护记录通过外键关联原 request；非导入维护记录通过外键关联本人的新 head。摘要用于核对内部一致性，不是对数据库管理员篡改的独立外部证明。

来源由实际导入关系决定，不能由客户端选择。导入账号如存在非导入 head，或无导入关系的账号残留导入 head，均拒绝；不会选“较新的一份”或自动迁移。非导入账号以后纳入导入体系，需要总控另行设计显式迁移，本入口不能静默重解释旧邮箱身份。

完整校验保留并加强：原导入 head、连续 revisions、当前 email/key 及导入摘要全部重读；原 requests 的每次变更必须唯一对应修订、操作人、邮箱摘要和前后版本。新维护还核对每条声明摘要、来源和外键指向；非导入每次修订也必须有唯一变更请求。历史损坏拒绝维护及登录，不退回其他来源或最新一行。

## 初始化与宿主接点

原 `NotificationChannelsAccountEmailPreparation.init()` 在建完旧三表后调用新 `init()` 创建三个空表及索引，继续要求在事务外、HTTP 监听前初始化。无账号迁移、种子、真实邮箱回填或自动授权；重复初始化不改变已有记录。新表是首次版本，CREATE IF NOT EXISTS 不替代私有早期试验表的迁移检查。

Auth 和验证码核心继续调用原 `NotificationChannelsAccountEmailPreparation.loginSnapshot(userId)`，无需新增认证旁路。该入口现在统一按真实来源读取；Snapshot 的四个字段保持兼容。导入绑定摘要保持原语义，非导入使用 `existing:` 加稳定摘要。两类都要求当前 status=1、密码非空、有完整且唯一的候选邮箱；没有候选或资料损坏 409，存储不可用 503。用户名变更会使原 Credential 失效，新的有效密码凭证仍可使用未变的邮箱资料。

总控需在 Api 中接入以下新入口，沿用原有受限正文、延迟响应和会话方式：

- `matches(path)`：匹配 `/api/account-email-maintenance` 及子路径，先鉴权再读取正文。
- `parseBody(raw)`：新路由专用、保留 BigDecimal 精度的六字段 JSON 解析；必须在通用 Json 解析前调用。
- `handle(exchange, session)`：再次检查真实 Auth.current、当前 admin、HTTP token 与同一 Session，并设置 no-store。未知子路径在鉴权后 404。
- `guardAccountDeletion(userId)`：保护维护目标及审计 actor。原 Preparation 的删除保护已串联此方法，现有 users/delete 若已调用原方法就不必重复调用。

本候选不修改共享 Auth/Api/Main/Db/原前端。API 必须在真实认证的管理员入口提供，不能用于匿名首次登录或按客户端声称的 role 授权。

## HTTP 合同

GET `/api/account-email-maintenance?user_id=123` 恰好接受一个正整数 user_id；拒绝未知、重复、编码重名或其他查询参数。仅查询，不创建 head 或请求记录。

POST 同一路径，不接受查询参数，要求 application/json。正文恰好六个字段：

```json
{
  "user_id": 123,
  "expected_revision": 0,
  "request_id": "5636cc28-a0f8-4439-97db-cc4176d5a066",
  "email": "candidate@example.invalid",
  "confirmed_username": "synthetic-user",
  "purpose": "LOGIN_VERIFICATION"
}
```

以上只是虚构格式示例。confirmed_username 必须与当前目标完全一致，用途只接受 LOGIN_VERIFICATION。user_id 为 1..9007199254740991，expected_revision 为 0..该上界；数字必须精确为整数，微小小数不能被舍入接受。UUID 规范为小写。拒绝重复及转义后同名字段、非标准空白、布尔/数组/嵌套值，以及 actor/status/role/password/verified 等额外字段。

email 使用原准备格式校验：常见 ASCII 邮箱、长度及 CRLF/控制字符边界；规范域名大小写而保留 local-part。null、空串或纯普通空格可清空。提交地址不代表已经通过本人验证。

响应仍用 Api 原 code/data 包络，data 字段为：

| 字段 | 含义 |
| --- | --- |
| user_id、username、name | 当前目标身份；name 可为空。 |
| account_kind | 服务端确定的 IMPORTED 或 EXISTING。 |
| revision、email | 当前候选版本和值。 |
| status | 仅 MISSING 或 PENDING_VERIFICATION。 |
| history | 按修订排序的 revision、email、status、actor_user_id、recorded_at。 |
| can_save | 已通过当前管理员与目标资格校验时为 true。 |
| duplicate_email | 仅布尔结果，不返回别人的身份、邮箱或数量。 |
| reauthentication_required | 本次当前管理员实际修改了自己的邮箱并被撤销登录时为 true；GET、重放、同值保存为 false。 |

新维护保存硬拒绝与两种来源其他账号当前邮箱重复，按完整地址不区分大小写。旧准备接口保留 imported↔imported 候选重复提示合同，但新增拒绝与非导入维护邮箱冲突的保护；所有登录快照都拒绝跨两来源的重复。已被替换的历史邮箱不占用当前地址。

## CAS、重放和审计

expected_revision 必须等于当前版本。同值保存不新增修订；首次空值只创建 head 和请求审计，revision 仍为 0。改变或清空实际值时版本加一，原邮箱及管理员、时间留在历史。

同 UUID 绑定真实操作人、目标、来源、确认用户名、用途、邮箱与版本；任何字段变化都拒绝。合法重放重新鉴权和核对历史后返回当前最新状态，不能恢复旧邮箱，也不重复撤销之后建立的新会话。旧准备与新维护入口不能混用同一个请求号，包括两个入口并发竞争。

用户名正常修改后，历史确认名字保持原值；新操作必须确认新名字。旧请求不能换上新名字复用，应创建新的请求号。声明摘要覆盖完整确认字段，因此单独损坏历史用户名或其他字段会被检出，而正常改当前用户名不会使历史永久失效。

所有保存操作在同一业务锁及事务内完成，旧导入请求和新声明也同事务。SQL 写入失败回滚全部邮箱变更；users、导入人员、密码、角色和业务授权不被写入。

## 保存后撤销和管理员自改

顺序固定为：业务锁内认证并固定 actor → 事务校验/写入 → 构造最终响应 → 提交 → 仍持业务锁调用 `Auth.revokeUserSessions(target)` → 返回响应。

写入后不再调用 Auth.current 或带鉴权的 get，否则 required 模式管理员修改自己的邮箱时，旧快照会先失效，导致已写操作被误判未登录而回滚。现在自行修改也能成功提交，再让原登录失效；前端收到 reauthentication_required 应清除旧会话、显示保存成功并回到原登录流程。

既有 revoke 接口同时增加凭证代次，旧登录、已就绪验证码以及仍在发送的挑战都无法继续核验；邮箱 A→B→A 也不会恢复旧挑战。已经开始的邮件网络发送无法撤回，但旧验证码不能签发会话。同值保存和重放不会撤销新会话。

Db.transaction 在提交后恢复 auto-commit 失败时也会抛错，因此本实现记录事务体已完成的阶段：若已进入提交阶段且发生实际变更，在提交结果不确定或连接恢复失败时保守撤销旧登录；业务写入失败并回滚时不误撤。错误仍统一脱敏，不回显 SQL、确认信息或邮箱。

## 错误状态

400 格式/字段/用途；401 无效真实会话；403 当前非系统管理员；404 不存在的目标或未知子接口；405 不支持方法；409 CAS/声明/来源/历史/重复冲突；415 媒体类型；503 存储或操作暂不可用。没有自动降级、自动验证或管理员跳过邮箱的返回状态。

## 验证及发布边界

新专项 **841 项主检查 + 48 项独立冷 JVM = 889 项通过**。覆盖真实 Auth、legacy 初始维护、required 已验证管理员 HTTP 自改、两来源历史和重复、CAS/并发/UUID、正常改名与历史损坏、回滚、提交结果不确定的撤销、已有账号字段零改动及删除保护。测试启动前验证独立临时 data.dir；缺失配置的失败启动不能触库。无网络、外发、真实凭据或生产数据。

在同一最终候选上，原邮箱准备 **616 + 31 冷 JVM = 647 项通过**，原验证码核心 **2459 项通过**；共享全应用编译通过。独立审查发现并修复正常改名兼容与旧审计 actor 一致性问题，复核闭环。

已确定正式总管理员使用 `manager01`。本任务历史只找到系统发件资源，未找到该管理员本人核验收件地址，已仅向用户询问这一个地址，不能把发件账号自动当作本人收件邮箱。用户明确地址后，先由总控在已认证的管理员路径登记，再做真实送达和 required 切换；角色调整、发布备份及实际执行仍由总控统一处理。本候选不自动写入任何实际邮箱或启用184个待用账号，也不决定设备记忆期限。
