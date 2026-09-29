# M01 持久化与现有会话桥接（2026-09-22）

实现：`src/com/training/OrganizationAccessStore.java`。仅连接现有 `Auth/users` 与不变的 `OrganizationAccess` 核心，不创建账号、不按旧 admin/manager/viewer 自动生成业务岗位，不导入真实名册、不发送邮件。配置由管理员显式确认发布。本模块不代替首次/新设备邮箱验证和设备信任功能。

## 宿主接点和固定签名

```java
public static void init() throws SQLException;
public static OrganizationAccess.Configuration configuration() throws Exception;
public static OrganizationAccess.Person person(Auth.Session s) throws Exception;
public static OrganizationAccess.Decision authorize(
    Auth.Session s, String resource, OrganizationAccess.Action action, String organizationCode);
public static boolean handle(HttpExchange ex, Auth.Session s) throws Exception;
```

`Db.init` 创建 users 后、事务之外调用 `init()`。`Api.route` 登录校验之后、通用 CRUD 之前调用 `if (OrganizationAccessStore.handle(ex,s)) return;`。`Api.handle` 负责 JSON Content-Type、1 MiB 请求大小、解析缓存、共享业务锁与延迟响应写出；Store 再校验方法和字段，不自行读取无限长请求体。未知路由返回 false。

`configuration()` 每次读取有效快照；无配置返回 null，存储损坏抛异常，不自动恢复/生成演示配置。`person()` 每次通过 `Auth.current(s)` 重验真实会话归属和 `users` 状态，要求启用的显式 AccountBinding、启用的 Person 及其所属机构链。无有效会话 401；无配置/无绑定/绑定停用/人员停用/机构链停用 403。`authorize()` 返回核心 Decision（含 `allowed()`），不以权限异常驱动列表筛选；账号或配置不可核实则失败关闭。资源机构参数必须来自服务端持久化业务记录。调用者须在整个业务操作中使用 `Api.MUTATION_LOCK`，同事务重读当前身份、配置与业务状态。

`Auth.current(Session)` 由总控提供：会话必须仍归属 Auth 内部会话表，再经过 Auth.get 刷新；伪造同 uid 的 Session、退出旧对象、过期和被篡改的签发 uid 不可认证。Store 不接受客户端 uid、personCode 或 authenticated 作为调用人来源。旧 admin 只控制配置维护，不自然取得审批/承接岗位。一个账号重新启用不会恢复已撤销会话。

业务资源键约定：`demand.read`→VIEW、`demand.write`→HANDLE、`approval.review`→HANDLE、`demand.accept`→HANDLE、`bid.result`→HANDLE。这些键不自动配置给任何真实人员；其他已约定键仍按核心精确匹配，不支持通配符。

## JSON 公共约定

所有响应外包原 `{ "code": 0, "data": ... }`。失败由 Api 输出非零 code、msg 和对应 HTTP 状态。配置所有层级严格字段白名单：下表字段全部必传，未知字段拒绝；字符串不转数字、不去前导零。集合必须 JSON 数组，集合字段禁止重复，每个数组至多 10000 项；布尔值必须 JSON true/false。普通编码/版本/资源字符串最长 128、正则最长 256，无首尾空白且非空；标注可空的字段只能 null 或非空字符串（不接受空字符串）。枚举大小写严格。

### GET /api/organization/config

仅当前数据库仍为启用 admin 的真实会话。无查询参数。GET/HEAD：

```json
{"code":0,"data":{"version":null,"configuration":null}}
```

配置存在时 version 为当前字符串版本，configuration 是下述完整对象（不返回 Java record 字符串）。此接口含人员编码、关系和账号对应，仅管理员可读。

### POST /api/organization/config

仅 admin。Content-Type: application/json，无查询参数。唯一请求结构：

```json
{"expectedVersion":null,"configuration":{}}
```

`expectedVersion` 必传：首次 null；更新时等于刚读取的当前 version。configuration 不能空，字段如下。

| 对象 | 完整字段与类型 |
|---|---|
| configuration | version:string；codeRules:CodeRules；roleCodes:string[]；organizations:Organization[]；people:Person[]；relations:RoleRelations[]；accountBindings:AccountBinding[]；grants:Grant[] |
| CodeRules | organizationPattern:string；personPattern:string；rolePattern:string（Java 正则） |
| Organization | organizationCode:string；parentOrganizationCode:string/null；enabled:boolean |
| Person | personCode:string；organizationCode:string；responsibleOrganizationCodes:string[]；leaderPersonCode:string/null；bpPersonCode:string/null；roleCodes:string[]；enabled:boolean |
| RoleRelations | roleCode:string；leader:RelationRule；bp:RelationRule |
| RelationRule | required:boolean；allowedTargetRoles:string[]；targetMustCoverOrganization:boolean；allowSelf:boolean |
| AccountBinding | accountId:JSON 正整数数字（1～9007199254740991）；personCode:string；enabled:boolean |
| Grant | ruleId:string；roleCode:string；resource:string；action:VIEW/HANDLE/EXPORT；effect:ALLOW/DENY；scope:OWN_ORG/RESPONSIBLE_ORGS/NAMED_ORGS；organizationCodes:string[] |

`version` 是管理员明确给出的唯一发布标识。已使用的版本永久不可再次发布，避免 ABA 并发问题；需要恢复旧规则时，以新版本重新提交旧内容。expectedVersion 过时、新版本已用、CAS冲突为 409。完整核心 validate、账户存在及启用检查在发布前完成；每条绑定必须指向存在账号，启用绑定还须账号启用。停用绑定可以保留已停用但仍存在的账号以保留明确关系。实际业务每次仍重新检查账号状态。

成功响应与 GET 同形 `{version,configuration}`。不得把预览或客户端校验当作正式发布成功。

以下**仅为隔离合成样例**，accountId 2 必须是隔离测试库已创建的启用账号；不可以直接导入正式系统，也不是对真实编码或岗位的默认约定：

```json
{
  "expectedVersion": null,
  "configuration": {
    "version": "SYNTHETIC-ID-v1",
    "codeRules": {"organizationPattern":"[0-9]{3}","personPattern":"[0-9]{4}","rolePattern":"[A-Z]+"},
    "roleCodes": ["FILLER"],
    "organizations": [{"organizationCode":"001","parentOrganizationCode":null,"enabled":true}],
    "people": [{"personCode":"0001","organizationCode":"001","responsibleOrganizationCodes":[],"leaderPersonCode":null,"bpPersonCode":null,"roleCodes":["FILLER"],"enabled":true}],
    "relations": [{"roleCode":"FILLER","leader":{"required":false,"allowedTargetRoles":["FILLER"],"targetMustCoverOrganization":false,"allowSelf":false},"bp":{"required":false,"allowedTargetRoles":["FILLER"],"targetMustCoverOrganization":false,"allowSelf":false}}],
    "accountBindings": [{"accountId":2,"personCode":"0001","enabled":true}],
    "grants": [{"ruleId":"VIEW-OWN","roleCode":"FILLER","resource":"demand.read","action":"VIEW","effect":"ALLOW","scope":"OWN_ORG","organizationCodes":[]}]
  }
}
```

### GET /api/organization/me

当前有效登录会话即可；GET/HEAD，无查询参数，不接受 accountId/personCode 等切换身份。无业务配置或无映射也可读本人状态，不抛出整份名册。

```json
{"code":0,"data":{"version":"SYNTHETIC-ID-v1","status":"BOUND","person":{"personCode":"0001","organizationCode":"001","responsibleOrganizationCodes":[],"leaderPersonCode":null,"bpPersonCode":null,"roleCodes":["FILLER"],"enabled":true},"organizations":[{"organizationCode":"001"}]}}
```

status 为 NOT_CONFIGURED / NOT_BOUND / BOUND。前两者 person=null、organizations=[]；NOT_CONFIGURED 的 version=null。NOT_BOUND 也包括绑定、人员或所属机构停用，不向普通用户泄露其他人员详情。organizations 仅当前本人所属/负责及显式允许访问的启用机构，每项仅 organizationCode；没有姓名、显示名称或全体人员列表。选项不代表任意业务都可办理，业务接口仍须逐资源/动作授权。Person 是本人核心结构，只含编码及本人的关系字段。

## 存储与并发

单表 `organization_access_config`：id 自增主键；version VARCHAR(128) UNIQUE；active_slot INT UNIQUE，当前=1、历史=null（CHECK仅允许这两种）；previous_version；payload CLOB；created_by；created_at。不保存会话凭证/密码。没有种子配置，也不覆盖或删除历史。

所有入口使用 Api.MUTATION_LOCK，遵循原单 H2 连接模式。publish 在 Db.transaction 内重验 admin、当前版本及绑定账号，以 `UPDATE ... WHERE active_slot=1 AND version=?` 的 `executeUpdate()==1` 检查 CAS，然后追加新行；首次发布受 active_slot 唯一约束保护。失败回滚旧有效标记及新记录。无成功前缓存更新。禁止在其他事务内调用 publish，避免现有 Db.transaction 嵌套提前提交。读配置/person/authorize 可在业务事务内使用。

包级 `publish(Auth.Session,String,Configuration)`、`parseConfiguration(Object)`、`toMap(Configuration)` 仅供同包可信测试/适配复用；publish 同样强制真实 admin、完整白名单和核心校验。公开HTTP不能传会话对象。其他模块测试请用真实 Auth.login 建立隔离会话后 publish，不直接写生产配置。

## 验证

运行 `bash scripts/IntegrationIdentity-check.sh`。编译输出与H2数据均使用新建临时目录；仅合成账号、通过真实 Auth.login 创建会话，JVM退出后删除该次测试产物。不会读取生产数据或调用 Db.init 的演示种子逻辑；测试自建最小users表。需要所有正在联接的实际共享源码到位，不使用替代Stub。

覆盖配置持久化/重连、版本冲突、唯一当前版本、并发两个保存只有一个成功、插入失败整事务回滚、无映射拒绝、旧role不升权、前导零、字段白名单/布尔/安全整数/重复集合/悬空关系、真实账号停用、绑定/人员停用、伪造/退出/篡改会话、admin降权即时生效、GET管理员限制与me无名册泄露。2026-09-22 已以实际共享源码编译运行，58 项检查全部通过；不把单模块验证当原系统UI、邮箱或生产验收。
