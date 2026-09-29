# 账号负责人与 BP 关系维护（R14-v1）

本功能供当前真实管理员维护一个已在完整正式 M01 配置中绑定的人员的负责人、BP。它不能建立初始组织配置、绑定人员、启用账号、增加业务岗位、扩大岗位负责范围或代替多人审批路由决定。

## 冻结接口

- `GET/HEAD /api/organization/account-relationships/{account_id}`：当前关系、可选人员及影响提示。
- `POST /api/organization/account-relationships/{account_id}/preview`：仅接受 `expected_version`（字符串）、`leader_person_code`（字符串或 null）、`bp_person_code`（字符串或 null）三个字段。
- `POST /api/organization/account-relationships/{account_id}/confirm`：仅接受 `review_token`（字符串）。目标账号从路径取得，必须与服务端核对记录一致。

所有路径禁止 query。账号编号须为正 JavaScript 安全整数的十进制规范写法，无前导零、正号、小数或指数。正文限 4096 字节、严格 UTF-8、单层 JSON 对象，值只能为字符串或 null；拒绝解码后重名键、嵌套、非字符串标量、尾随数据及未知/缺少字段。请求和响应都使用 snake_case，仅沿用原系统 `sessionInvalidated`。

`OrganizationAccountRelationships` 提供公开静态 `matches(String)`、`preflight(HttpExchange)`、`handle(HttpExchange, Auth.Session)`、`parseBody(byte[])`，以及包级 `editor(actor,id)`、`preview(actor,id,Map)`、`confirm(actor,id,Map)`。根 Api 负责前置鉴权、锁外限量读取正文、缓存已解析 Map，再在认证分发中调用 handle。handle 使用 Api.body，不重复读取请求流。

preflight 在读正文前验证真实 token 对应的当前管理员。handle 再验证真实 token 的 Session 与 supplied 为同一对象。匿名/失效 401、非管理员 403 优先于路径、方法、参数及配置。未知子路径在认证后 404；方法不支持 405 并提供 Allow。所有匹配接口响应 Cache-Control: no-store。

## 响应结构

Relation 结构为：`{person_code, account_id, username, name, role_codes, eligible}`。账号信息允许 null，岗位为数组，eligible 为布尔值。当前已失效关系仍展示，不能因不在候选内而消失；候选均 eligible=true。

GET 返回：

```text
{
  configuration_version,
  subject: {account_id, username, name, account_enabled, binding_enabled,
            person_code, organization_code, person_enabled, role_codes},
  current: {leader: Relation|null, bp: Relation|null},
  leader_required, bp_required,
  leader_options: Relation[], bp_options: Relation[],
  combined_person_codes: string[], warnings: string[]
}
```

PREVIEW 返回：

```text
{
  configuration_version, subject,
  before: {leader: Relation|null, bp: Relation|null},
  after: {leader: Relation|null, bp: Relation|null},
  changed, review_token, expires_at, warnings,
  workflow_impact: {historical_assignments_changed: false,
                   pending_tasks_reassigned: false,
                   combined_workflows_may_pause: changed}
}
```

review_token 为服务器随机 64 位十六进制字符串，expires_at 为 ISO 时间。未改变关系也可预览，确认后不发布新版本。

CONFIRM 返回：

```text
{saved, unchanged, configuration_version, account_id, person_code,
 leader_person_code, bp_person_code, sessionInvalidated}
```

无变更 saved=false、unchanged=true；实际保存 saved=true、unchanged=false。Store 发布撤销当前管理员本人会话时，仍返回成功与 sessionInvalidated=true，handler 清除 Cookie；不得在提交后重新鉴权而把成功变成 401。

## 候选及保存约束

人员由正式配置的 AccountBinding 关联，不能按姓名、旧来源编号或 users.role 猜定。源人员的每个岗位 RelationRule 都须满足；任一岗位要求某关系，该关系即必填。候选须满足 allowedTargetRoles、allowSelf 和 targetMustCoverOrganization。机构范围使用匹配目标岗位自己的 responsibleOrganizations，不能把另一岗位范围并入。

目标人员、目标所属机构及祖先、可信绑定、对应账号须启用；同一 OrganizationAccess.Engine 对目标账号在源人员所属机构的 approval.review HANDLE 须允许，显式 DENY 生效。候选及最终配对还须通过完整 Configuration.validate 和负责人关系循环检查。当前关系展示不等于仍可选择。

负责人和 BP 选为同一人时，必须已有同机构、同人员、有效的 CombinedApprovalAssignment 及现有职责、范围、许可。复用 Store.combinedAssignment 检验现有证据，不新增兼任证明，不为上海/西安等未决多人情况自动选人。

新配置仅替换目标 Person 的 leaderPersonCode、bpPersonCode，并生成唯一版本。保留该 Person 的其他字段、所有其他人员、岗位范围、组织、规则、绑定和兼任证据。唯一写入入口为 OrganizationAccessStore.publish(actor, expectedVersion, candidate)，沿用完整校验、事务 CAS 和成功提交后的会话撤销；不能外包 Db.transaction。

源 subject 的账号、绑定、人员启停字段用于如实显示，不额外禁止对已停用但可信绑定的人员准备关系；保存不改变启停。preview 提前核实 Store 发布时已有的全配置账号约束：每个绑定账号存在，启用绑定对应启用账号，避免把已知必然失败的变更显示为可保存。

## 核对记录与失败处理

核对记录仅存服务器内存，5 分钟有效，最多 128 条；绑定当前管理员真实会话对象、目标账号、当前完整配置版本与内容摘要、管理员凭据事实以及目标/所选候选的当前账号和绑定事实。confirm 重新读取并验证全部事实及候选约束。重启、过期、被消费、不同会话、路径目标不符、版本/事实变化都必须重新预览；重复 confirm 不得再次写入。

合法拥有者确认时，一旦观察到核对记录失效，该标识永久撤销；后来恢复原字段也不能使它重新有效。其他会话或错误目标请求不能消费属于原拥有者的合法核对记录。

没有正式配置或目标尚无正式绑定返回可理解的 409。账号不存在 404；持久配置损坏统一固定 503，不回传私有正文、路径或解析细节。参数非法 400；超过正文限制 413。GET 和 preview 不写数据库；无变更 confirm 不写版本。

原 UI 在 409 后清除旧确认标识，重新读取当前关系。网络中断或服务端错误造成结果不明时，禁用旧确认按钮，仅允许显式重新读取以确认实际状态，不自动重发 confirm。核心仍拒绝同 token 重复写入。成功且 sessionInvalidated=true 显示保存成功、需重新登录。

## 对已有流程的影响

本功能不修改 WorkflowIntegration、历史记录或待办参与人。新流程采用保存后的关系。已有普通顺序流程继续使用已冻结的办理人；已有明确兼任流程仍按原 combinedProblem 重核当前关系和证据，关系变化可能使其暂停。preview 明确提示这一影响，不把关系修改当作自动改派，也不增加额外的流程阻断政策。

## 接入与验证状态

R14-v1 契约由根冻结，公共 Api、原用户页、真实 HTTP 专项由根分别负责。M01 交付新核心类、合成专项/检查脚本、本说明及自有 STATUS，核心 301 项检查通过。覆盖全表只读、仅配置表保存、无变更不写、逐岗位与兼任约束、失效记录不可复活、凭据/配置变化、同账号不同会话、过期/容量、严格解析，以及真实 Store 事务失败完整回滚、失败不撤销会话、本人成功保存后的会话失效与 Cookie 清理。所有验证使用独立临时 H2 和合成账号、组织、关系，不操作真实 app/data，不生成正式配置或激活真实账号。

共享安装后运行 `M01_RELATIONSHIPS_JAVA=$HOME/.local/bin/java bash app/scripts/M01-account-relationships-check.sh`。脚本完整编译当前产品 Java，只运行本模块专项；可用 M01_RELATIONSHIPS_APP_ROOT 指定 app 根目录。直接调用测试主类时只传一个已创建的临时目录，目录名须以 `yanxu-m01-account-relationships.` 开头，与根统一检查入口一致。无额外 fixture 文件、端口、测试 HTTP 路由或正式数据依赖。
