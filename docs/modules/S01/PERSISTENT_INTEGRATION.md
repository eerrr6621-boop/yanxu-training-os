# S01 持久化通知接线说明（待总控接入）

日期：2026-09-22。本轮适配器已通过116项隔离专项检查，范围及限制见S01 STATUS。本文列出待总控修改的共享宿主接点；S01未改共享宿主或原前端，未初始化正式数据库、读取凭据、发信或部署。专项采用临时Workflow副本接线，不能代表正式主服务或原页面已接通。

## 1. 本轮实现与旧提案的关系

新文件为 `src/com/training/NotificationChannelsIntegration.java`，复用已有 `NotificationChannels.java`、Auth、M01、M02/M03、Db 与 Api。正式页面继续使用原 `web/app.js` 的账户入口、首页局部区及需求详情，不新增 S01/M03 主导航，不加载 `web/modules/notifications/demo.html` 或其独立样式。

本说明以新适配器的持久化结构和实际 HTTP 输出为准，替代旧 `INTEGRATION.md` 中试拟的 `notification_events/notification_inbox` 表及 `{state:"ready",...}` 演示接口。旧文档关于沿用原页面、五步提醒、身份归属、读办分离及不外发的约束继续成立。旧模块 `mount` 不能直接消费本轮接口后就被视为正式接入。

只读核对源文件：共享 `WorkflowIntegration.java`、`Db.java`、`Api.java`、`web/app.js`、旧 `docs/modules/S01/INTEGRATION.md`；为核实调用契约，另核对 `NotificationChannels.java`、`OrganizationAccess.java`、`OrganizationAccessStore.java` 及 `Auth.revokeUserSessions(long uid)`。以下行号是本轮快照提示，集成时以函数名定位。

## 2. 总控需要改动的共享接点

### 2.1 启动建表：M01、Workflow 之后，业务事务之外

当前 `Db.init()` 在基础表与 `seed()` 后依次调用 `OrganizationAccessStore.init()`、`WorkflowIntegration.init()`（约 125–126 行），随后初始化其他模块。总控应在 `WorkflowIntegration.init()` 后、服务开始接收请求前调用一次 `NotificationChannelsIntegration.init()`。

适配器自己取得 `Api.MUTATION_LOCK`，并要求 `Db.get().getAutoCommit()==true`。H2 DDL 可能提交当前连接，故不能把建表塞进审批、受理、已读或配置发布的业务事务，也不能在第一次通知请求中懒初始化。

新适配器 `init()` 与当前 `Db.init()` 均声明 `throws SQLException`，可直接接线：

```java
OrganizationAccessStore.init();
WorkflowIntegration.init();
NotificationChannelsIntegration.init();
// 继续原有其他模块初始化。
```

这只是待接代码片段，本文未写入 `Db.java`。初始化失败必须中止启动，不得吞掉后继续展示“站内已接通”。如果目标库已有不同版本的 `s01_*` 表，`CREATE TABLE IF NOT EXISTS` 不会补齐新列；总控须另行审核显式迁移，不得删表重建或按当前关系补造旧批次。

### 2.2 API 分派：沿用现有认证、请求体限制与响应出口

在 `Api.route()` 已完成 `Auth.get(token(ex))`、拒绝未登录之后，现有 M01/Workflow 分派段附近、`WorkflowLegacyAccess.check` 和通用 CRUD 之前增加：

```java
if (NotificationChannelsIntegration.handle(ex, s)) return;
```

可紧接现有 `WorkflowIntegration.handle(ex, s)`（约 96 行）。不要新增公开免登录路由、独立 HttpServer 或第二套 cookie/token。当前 `Api.handle` 已在 `MUTATION_LOCK` 内执行 route；适配器再取同一 Java monitor 是可重入的，不能改成另一个互不协调的锁。Api 继续负责 JSON 请求体上限、错误包络和锁外网络写回。

`read()` 自己在锁内创建一个 `Db.transaction`，并显式拒绝进入时已关闭 autocommit。因此 Api 分派层不得给整个 S01 handle 再包一层事务。

### 2.3 事件写入：只替换 Workflow 私有 outbox 中的 INSERT

当前 `WorkflowIntegration.mutate()` 已在 `synchronized(Api.MUTATION_LOCK)` 和 `Db.transaction(...)` 中写业务；`saveState()` 先保存审批状态及 `approval_events`，再调用私有 `outbox(...)`，实际团队受理也通过该方法写事件。总控只需把私有 `outbox`（约 377 行）的直接 INSERT 改为：

```java
private static void outbox(String key, long demand,
                           Map<String, Object> payload) throws Exception {
    NotificationChannelsIntegration.appendOutboxInTransaction(key, demand, payload);
}
```

适配器会自行插入原 `workflow_outbox` 行，并在同一个现有事务中冻结事件及收件人。不得保留旧 INSERT 后再调用，否则会被当成无法证明历史绑定的旧 outbox；不得提交业务后另开事务补通知；不得在 append 内外再套 `Db.transaction`。当前 `Db.transaction` 会直接 commit/rollback 同一共享连接，不具备嵌套事务语义。

保留现有 `event_key`、`demand_id`、payload、业务请求幂等与事件顺序，不在 S01 生成审批人、承接团队或新业务事件。来源不一致等完整性错误仍应抛出并回滚整笔业务；业务有效但未能确定通知收件人的情形由适配器记录 `BLOCKED`，不得把该返回值转换成业务失败。

`appendOutboxInTransaction` 返回 `{status:"READY"|"BLOCKED",reason,noticeCount}`。它始终保留原 outbox 的 `PENDING`，因为本轮只是站内投影，并没有外部发送器；不能把 `READY` 或 `noticeCount=1` 理解为邮件已送达。

### 2.4 账号删除与历史保留

在 `Api.delete(..., "users", ...)` 现有当前账号、M01 绑定、末位管理员检查所在分支中，实际删除之前增加 `NotificationChannelsIntegration.guardAccountDeletion(id)`，继续使用原 `MUTATION_LOCK`。不要只依赖当前 M01 配置中的绑定检查：账号即使已从当前配置移除，仍可能被历史通知引用。

guard 对存在历史通知的账号返回 409，并提示停用而非删除；`s01_notifications.account_id → users.id` 外键是第二道约束。不得级联删除通知来绕过 guard，不得关闭外键或先清空历史再删账号。原用户删除成功后已有的 `Auth.revokeUserSessions(id)` 应保留。

事件与通知外键还保留 `workflow_outbox`、`workflow_demands`、`approval_events`、`workflow_acceptances`、`workflow_documents` 和冻结版本 `organization_access_config` 的关联。除直接外键引用外，配置 `previous_version` 的完整链也必须持续保留；不能把未被通知直接引用的中间版本当作可清理缓存。不得清理原事件、原资料版本、通知配置历史以修复读取失败。确需留存/归档方案应由总控另行设计，本轮不提供清理任务。

## 3. 身份与权限：正式接入前的必接边界

### 3.1 两项显式资源，默认拒绝

由 M01 通过既有 `Grant` 配置明确授予适当岗位及机构范围，不新增账号、角色或自动授权：

| 资源与动作 | 用途 | 还必须满足的条件 |
|---|---|---|
| `notifications.read` / `VIEW` | 接收与查看本人的通知；详情、目标、标为已读均只操作本人的记录 | 同一事项机构上具有 `demand.read` / `VIEW`；账号启用，人员启用，唯一有效绑定及机构范围可证明 |
| `notifications.audit` / `VIEW` | 只读核查本机构范围内未投影或 BLOCKED 事件 | 同一事项机构上具有 `demand.read` / `VIEW`；没有明确 audit 范围则返回 403 |

M01 资源键精确匹配，不支持通配符或自动下级机构覆盖，显式 DENY 优先；旧 `admin/manager` 或前端 `canWrite()` 不等于这些资源的授权。不能因管理员身份自动赋予 audit，也不能把 read 变成审批/受理 HANDLE 权限。

列表先按已验证会话 `uid` 过滤，再逐条检查可见性；没有可见通知可以返回空列表，不能据此判断渠道故障。详情、目标、已读遇到不存在/他人/当前不可见通知统一返回 404。渠道状态接口只确认当前身份绑定，不返回他人通知或收件人资料。

### 3.2 冻结绑定与完整配置历史链

事件首次写入时，通过 M03 已持久化参与人或 M02 原填报人找到 `personCode`，再用 M01 的唯一启用 `AccountBinding` 解析 `users.id`。不把人员码转数字，不从姓名、邮箱、客户端参数猜账号。首次解析同时要求账号/人员启用及事项机构上的 `demand.read VIEW`、`notifications.read VIEW`。

通知冻结账号、人员码、人员所属机构、配置版本。每次读取还要求：

1. 当前会话账号、人员码、人员机构与冻结值一致。
2. 从当前配置沿 `previous_version` 一直追溯到冻结版本；每一版都能证明同一人员仍绑定到同一账号、人员机构相同、所需权限连续有效。
3. 配置链缺失、循环、超过 10,000 个版本，或任一历史版本中断绑定/机构/权限，均隐藏该通知。不能因后来恢复原配置关系而重现已经中断的历史。
4. 原 outbox 的需求与规范化 payload 未变，原审批事件/参与人/策略版本、原受理事实与对应文档版本重新计算出的来源指纹与冻结值一致。源记录变更、缺失或无法验证时隐藏，而非用最新资料改写旧通知。

这里的“连续”针对已保存的配置版本链；它不能取代 Auth 对会话身份的控制，也不表示能凭空还原未记录在配置链中的历史启停事件。

### 3.3 M01 发布成功后撤销受影响账号的旧会话

当前 `OrganizationAccessStore.publish()` 能发布新的账号人员绑定，但没有在该发布路径调用 `Auth.revokeUserSessions(long uid)`。S01 能防止 P 的旧通知在重绑/撤权后复活，却不能单靠通知校验阻止旧登录会话在账号改绑为 Q 后继承 Q 的新通知与业务身份。

总控必须选择统一修复方案后才能认定真实身份端到端接通：

- 最小方案：在 M01 发布中比较前后绑定、人员机构/启停及影响身份或授权范围的变化，保存受影响账号集合；`Db.transaction` 成功返回后、仍在同一 `Api.MUTATION_LOCK` 内，对该集合调用现有 `Auth.revokeUserSessions(uid)`，随后返回发布结果。发布失败不得假装已经切换身份，也不得在事务未提交前先撤会话；旧会话必须在下一次业务读取前失效。
- 或由统一 Auth 引入绑定代际，并在每次会话校验中拒绝旧代际。该方案仍属于 Auth/M01，不由 S01 建立第二套登录。

涉及账号人员重绑、人员所属机构变化、人员/绑定停用时必须覆盖；权限撤销、岗位或机构范围变化也应纳入统一会话失效策略。用户账号本身的停用/降权/改密/删除继续沿用 Api 已有撤会话逻辑。未补齐此边界，不得把通知的行级防护或局部测试宣称为真实身份接入完成。

## 4. 五条已批准提醒与未确认团队

固定规则版本为 `S01-USER-20260921-v1`。本轮写入时冻结 `business_moment`、`rule_version`、`event_type`、`event_intent`、`event_destination`，读时从冻结字段构造通知，不用未来版本的 `approvedRule()` 重新解释旧事件。

| 已持久化业务时点 | 通知类型/用途 | 目标 | 收件人及本轮处理 |
|---|---|---|---|
| 提交/重提进入负责人先审 `SUBMITTED` | `REVIEW_REQUIRED` / `ACTION` | M03 | 原流程明确负责人，经 M01 唯一绑定 |
| 负责人通过，进入 BP `LEADER_APPROVED` | `REVIEW_REQUIRED` / `ACTION` | M03 | 原流程明确 BP，经 M01 唯一绑定 |
| BP 通过，待团队承接 `BP_APPROVED` | `HANDOVER_REQUIRED` / `ACTION` | M02 | 具体团队收件人仍未配置，本轮只记 `BLOCKED / TEAM_RECIPIENTS_UNCONFIGURED`，不写可见收件箱 |
| 退回原填报人 `RETURNED` | `RETURNED` / `ACTION` | M02 | 原填报人，经 M01 唯一绑定；不改两级退回规则 |
| M02 团队实际承接成功 `TEAM_ACCEPTED` | `HANDOVER_ACCEPTED` / `INFORMATION` | M02 | 知会原填报人，经 M01 唯一绑定 |

团队通知尚未配置只阻断该通知投影，不阻断 BP 的合法批准、`READY_FOR_TEAM` 状态或后续已有权限的团队受理操作。不得临时以全部管理员、全部 BP、当前操作者、某机构所有人代替团队收件人，也不要求本轮补材料。五步岗位方案已批准不等于具体团队账号已确定。

当前未批准额外抄送、自动催办等通知规则。来源有效但尚不属于这五个业务时点的事件，例如当前 `REMINDER` 及编辑产生的 `REVISE`，记录 `BLOCKED / UNSUPPORTED_BUSINESS_MOMENT`，不得擅自扩展收件人。REVISE 的 outbox 先于新文档 INSERT，适配器对该未配置时点不强求尚未写入的文档引用，以免中断原审批资料修改事务。

相同事件 key 的合法重试只返回首次冻结结果，不重新解析现在的人员/规则；内容或需求不一致返回冲突。已有 outbox 若没有 S01 冻结批次，仅返回/核查展示 `HISTORICAL_BINDING_UNPROVEN`，不按今天的绑定补造历史。后来补齐团队配置也不能自动重放或改写既有 BLOCKED 批次；如需后续补发，必须另有经批准的新事件/补发方案。

## 5. 当前 HTTP 契约与白名单

所有成功 HTTP 响应仍是 Api 的 `{code:0,data:...}`；下表列的是 `data`。原 `app.js` 的 `api()`（约 360 行）自动添加 `/api`、使用同源凭据、处理非零 code 并返回 `j.data`，所以前端调用写 `api('/notifications...')`，不要重复添加 `/api`。

| 方法与完整路径 | `data` 结构 | 限制 |
|---|---|---|
| GET `/api/notifications?offset=0&limit=20` | `{items:[NotificationView],total,unreadCount,offset,limit}` | 仅本人可见记录；无 `state`、无 `channels` |
| GET `/api/notifications/{id}` | `{notification:NotificationView}` | 当前身份、所有权、配置链与来源再次检查 |
| GET `/api/notifications/{id}/target` | `{target:{moduleId,params}}` | 打开前每次重取；不自动已读、不办理业务 |
| POST `/api/notifications/{id}/read` | `{id,readAt}` | `Content-Type: application/json`、请求体严格空对象 `{}`；不是旧契约的完整 item |
| GET `/api/notifications/channels` | `{channels:[{channel,status}]}` | 不接受查询参数 |
| GET `/api/notifications/diagnostics?offset=0&limit=20` | `{items:[{eventId,recordId,createdAt,status:"BLOCKED",reason}],total,offset,limit}` | 明确 audit 与 demand.read 范围，仅只读诊断，无补发/消费/改状态操作 |

`NotificationView` 字段为 `id,eventId,type,title,body,createdAt,readAt,actionable`。type 为上述四种类型；时间为 ISO 字符串，未读 `readAt=null`。`actionable` 由当前 Workflow 详情/版本/办理人及启用动作实时判断，不能从未读状态推断。重复 read 保留首次时间，更新条件包含通知 id、当前账号及 `read_at IS NULL`。

请求白名单：

- 仅列表与 diagnostics 接受 `offset`、`limit`；offset 为 `0..2147483647`，默认 0；limit 为 `1..100`，默认 20。其他路径拒绝所有查询参数；不接受收件人、人员码、角色、机构、通知正文或任意跳转地址。
- 路径中的通知 id 当前为小写标准 UUID（`8-4-4-4-12` 十六进制形式）。客户端只按不透明 id 使用 `encodeURIComponent`，不得据 id 推导权限。
- target 顶层仅 `moduleId`、`params`；moduleId 仅 `M02|M03`。params 仅 `recordId,eventId,view`，M03 还包含 `taskId`。不接收 URL、任意 hash、脚本或审批指令。
- recordId 是安全整数范围内的正十进制字符串，最大 `9007199254740991`，保持字符串传递。eventId/taskId 核心标识白名单为 `^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$`；冒号是合法标识字符，不按 URL scheme 解释。本适配器 eventId 为确定性 UUID、M03 taskId 为真实持久化流程编号的十进制字符串。
- M03 的 view 为当前待办 `task` 或历史/已办 `detail`，两者都保留 taskId；M02 只返回 `detail`。这些是导航提示，不是后续操作授权。

列表与 diagnostics 按每批 200 条内部扫描，在可见性/机构权限过滤后计算分页与 total；列表还计算全部可见通知的 unreadCount，仅返回所请求页面的正文。不再因账号超过 10,000 条通知就整体拒绝；10,000 限制只用于配置历史链防循环。当前会为统计扫描相关记录，不能把这次实现描述成已经验证的大规模性能方案。

错误仍由 Api 返回 `{code,msg}`：失效会话 401；缺少核查权限 403；通知不存在/他人/不可见 404；不支持方法 405 并带 Allow；非 JSON 已读请求 415；不允许参数/非法分页 400；来源冲突等 409。前端按实际响应处理，不能遇到异常就加载合成条目。

## 6. 原页面最小接入与准确打开事项

### 6.1 入口复用，不扩主导航

总控可选原账户菜单 `#user-menu/.user-popover`（约 1102 行）的一个小型“通知”入口，或原首页 `pageDashboard`（约 2650 行）待处理区 `#priority-list` 附近的局部通知入口。原 `pageUsers`（约 5026 行）主要是管理员用户管理，不应成为普通业务用户查看本人通知的唯一通道；若在该页增加局部区，也只能显示当前登录账号的本人通知。

沿用 `openModal`、`renderTable`、`toolbar/filter-tabs/tag`、`toast`、原图标与五层样式。通知列表只需时间、事项、未读/已读及“打开事项/标为已读”，渠道状态放同一小弹窗或局部区。不要挂内部 demo、加品牌大页头、换配色、另建登录壳，或把 `notifications/style.css` 加到原工作台。

现有首页已经通过 `workflowQueueTasks(demands, approvalsPayload.tasks)`（约 2075 行）按 `demand:{id}` 归并需求/审批待办。历史通知 eventId 与当前任务不能混作去重键；通知接入不得让同一需求新增第二条相同审批待办，也不得把已读通知从仍有效的审批待办中删除。

通知请求独立捕获失败，复用 `quiet:true` 在局部显示“通知服务暂不可用，请稍后重试”，不能加入原首页共用的未隔离失败链而拖垮已有业务。分页只表示当前一页；接口未提供 `unreadOnly` 查询参数，若仅在当前页筛未读须明确标为“本页未读”，不要伪装成完整未读列表。要支持全体未读列表，须由总控明确补接口后再实现，不能偷偷传未获支持的参数。

### 6.2 M02/M03 目标转回原需求详情与审批记录

每次点击先 GET `/notifications/{id}/target`，严格校验上述白名单，再使用原需求详情路径。不得直接 `navigateTo('M03')`、新增模块导航或把需求 recordId 当 projectId。

现有可复用点：`navigateTo`（281）、`routeUrl`（305）、`restoreRouteFromUrl`（322）、`positiveRouteId`（216）、`showDemandWorkflow`（2004）、`workflowDetailHtml`（1957）、`workflowCommands`、`openWorkflowCommand`。`showDemandWorkflow` 目前会重新获取 `/demands/workflow?id=<id>` 与 `/workflow/context`，并在原弹窗显示“需求详情与审批记录”。

总控应在这套原详情逻辑中补一个严格内部目标桥接；本轮不新建详情组件：

1. M02：目标 recordId 定位原需求，读取最新 Workflow 详情并核对返回 `workflow.id`。view=detail 打开原资料与记录，不自动提交、重提、受理或审批。
2. M03：保留 recordId、taskId、eventId。读取 `/approvals/tasks/<taskId>` 或原 Workflow 详情，在最终用于渲染的响应上核对 `task.id==taskId`、`task.businessId==recordId`（用字符串比较）；使用同一需求的原审批区/历史记录，不能仅跳到审批总表。当前任务 API 只接受数字流程 id；若以后收到不兼容的 opaque taskId，应提示暂不可用，不能截断、强转或猜另一任务。
3. view=task 也不代表可以批准：仅展示最新 `workflowCommands/approval.actions` 允许的操作，用户明确选择后才进入原办理弹窗；view=detail 作为历史/只读打开提示，不因新节点出现而从旧通知直接打开另一个审批动作。需要只读呈现时复用 `workflowDetailHtml(workflow,context,'')` 与原 `openModal`，由总控给现有详情函数加内部选项，不能假定现有 `showDemandWorkflow` 已支持该选项。
4. 目标确认后到详情/办理仍可能发生状态变化。Workflow 详情再次执行 `demand.read VIEW`；正式操作继续走原 M02/M03 mutation，验证 HANDLE 权限、当前办理人、版本、阶段及 requestId。不能把通知验证或按钮是否显示当作后端授权替代。

当前 `navigateTo('demands',{focusId:recordId})` 只定位列表，`revealFocusedRow` 也只滚动高亮，不会打开详情。因此该行为单独完成不能算深链接接通；总控必须补齐原弹窗的准确定位。若保留通知任务上下文到 URL，应同步更新 `routeUrl` 和 `restoreRouteFromUrl` 的内部参数白名单；当前它们只保存 project/focus/status。无需在 URL 保存邮箱、身份信息或任意 returnUrl。

已办仍可见时打开原历史详情；撤权/删除/失效时保留在原页面并提示，不打开邻近需求或其他任务。`recordId` 是需求编号；要进入项目须取得服务端明确关联的 project_id，不能复用同一个数字猜测。

### 6.3 异步生命周期与读办分离

局部请求沿用 `routeEpoch/isRouteCurrent/addRouteCleanup`、`workflowRouteScope` 和 AbortController，切页、关弹窗、退出后不更新旧 DOM，不触发迟到的导航。原 `openModal` 当前已支持 `onClose`；通知局部区仍应明确注册取消。字符串用原 `esc` 或 textContent，不把服务端正文当 HTML。

标为已读仅 POST 空对象；成功后只依据 `{id,readAt}` 更新相应阅读状态，并在必要时刷新通知计数。没有完整 item 响应时，不从缓存推定新的 actionable。打开不会自动已读，已读不会改变审批/受理状态、待办数量或原业务统计；业务办理必须由用户在原详情中另行选择并经后端核权。

## 7. 渠道与范围边界

本适配器的渠道输出为 IN_APP=`ready`、EMAIL=`adapter_not_connected`、PUBLIC_ACCOUNT=`unconfigured`。IN_APP ready 只有在总控完成启动建表、API 与事件接线后才可作为正式页面状态；本说明本身不代表该接线完成。

EMAIL 状态仅表示已有资源确认但没有发送适配器，不能改成 ready/已发送；本轮不读取钥匙串或其他凭据，不测试登录邮箱，不发送测试邮件、验证码或公众号消息。没有后台 outbox worker、定时扫描或自动补发。

首次/新设备邮箱核验、同设备登录保持与手机外部网络访问仍由 Auth/M01/总控统一处理；S01 不新增验证码、账号绑定表或设备会话。M08 总结提醒不在当前 M02/M03 目标白名单内，本轮不得借需求 id 或现有通知类型扩大接入。

## 8. 总控接入后的验收项（尚待执行）

- 在隔离测试库验证启动顺序与事务外建表；在原业务锁/事务中确认业务、outbox、冻结事件与通知同成同败，无嵌套事务。
- 覆盖五个已批准时点、团队未配置仍可完成 BP 批准、重复事件不重复收件箱、来源冲突回滚、旧 outbox 不补历史映射、规则类型/用途/目标不随未来规则变化。
- 核查 read/audit 显式资源默认拒绝、本人所有权、机构范围、配置链中断、重绑/撤权后不复活、源事件/原文档变更隐藏，以及发布成功后旧 Auth 会话失效；特别验证 P 旧会话不能读取改绑 Q 后的新通知。
- 验证删除账号 guard 和外键；历史配置链、审批事件、受理事实与原资料均保留。不得用清表或删除历史来让验证通过。
- 验证 200 条内部扫描边界、过滤后分页/计数、超过 10,000 条通知仍可分页、当前页正文范围、非法 offset/limit，以及读时权限变化。
- 在原 index.html 的桌面与手机页面检查小入口、原样式与导航不变、通知失败不影响首页、打开准确需求/审批、已读与办理分离、切页/关闭/退出后无迟到更新。
- 渠道只显示真实连接状态，无任何外发；邮箱认证、送达、设备信任和外网可达仍须各自完成正式验收，不能从站内接口推定通过。

以上是待执行清单，不是已通过记录。共享宿主接线、身份会话边界与真实页面验证未完成前，只能标记“持久化适配器及接线草案待集成”。
