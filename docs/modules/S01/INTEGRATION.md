# 2026-09-22 持久化接入更新

本轮真实会话站内通知适配、同事务接线、接口与原页面局部接入，以 [PERSISTENT_INTEGRATION.md](PERSISTENT_INTEGRATION.md) 为准。以下为首轮设计和内部演示契约，不能直接当成本轮真实接口；正式原系统界面仍由总控局部接入。

# S01 集成说明 · 首轮 v1 · 2026-09-21

状态：通知核心及内部逻辑测试已验证；正式界面沿用原研序系统，不交付独立通知系统。尚未接入宿主路由、真实 M01/M03 权限与事件、数据库或外部渠道。用户已确认163发件邮箱资源与五步岗位提醒方案。未部署，未操作生产。本文接口与 DDL 是提交总控审核的最小提案，不代表公共合同已被修改。


## 正式界面接入：沿用原系统（2026-09-21纠偏，优先于此前独立页面建议）

本节落实MASTER/CONTRACTS最新决定。模块编号只用于代码分工；不新增“S01”“通知体验”“M03”等主导航，不用内部demo替代原页面，不另建登录壳、配色或大标题首页。NotificationChannels及五步提醒规则保留；真实邮箱/权限/深链接仍须接入，不因本节完成而宣称上线。

### 原页面与局部接点

以下行号为本轮只读核对时的app/web/app.js位置；后续以函数名/控件ID定位。

| 用户场景 | 复用原位置与控件 | 仅需补充的行为 |
|---|---|---|
| 看负责人/BP审批、退回补充、团队承接待办 | pageDashboard（1872）、现有待处理事项v9-queue/#priority-list（1981）、rowHtml的v9-row（1947）、原紧急/关注/常规筛选和查看全部（2030） | 从真实通知/任务接口加入当前用户actionable事项；保留原排课、材料、回款、课酬事项及排序习惯，不创建另一套首页 |
| 查看全部通知/已读消息 | 原待处理事项区标题附近增加一个小型“通知”入口；openModal（480）+renderTable（707）+filter-tabs/toolbar及原tag外观 | 弹窗仅列时间、事项、阅读状态与“打开/标为已读”；“全部/未读”是局部筛选，不占主导航，不挪动原状态筛选 |
| 从通知打开需求/审批/承接 | CRUD.demands（1321）、pageCrud（1459）、原详情行操作（1603）、editForm（1703附近）与openModal | M02通知定位原需求；M03通知在同一需求详情弹窗内显示M03审批区/历史，凭服务端actions显示按钮。保留原需求字段/编辑方式；审批只由M03执行 |
| 项目内相关提醒 | pageProjectDetail（1732）、workspace-tasks与taskHtml（1800/1822） | 只在服务端明确该通知关联本项目后追加原样式行；recordId是需求ID，不能直接塞进projectId |
| 首次/新设备邮箱核验 | renderLogin（779）、#login-form/.login-credentials（809）、#login-err与原login-field/login-input/login-submit | 仅在服务端要求核验时在原卡片内切换邮箱验证码步骤；保留原背景、文案层级与布局。核验最终成功后才进入原工作台 |
| 查看邮箱/设备状态 | 原账户菜单#user-menu/.user-popover（1089）及openModal/renderForm（480/568） | 接入统一身份后按需增加一个“邮箱与设备”小弹窗；不新增设置系统，设备期限/撤销规则不能由S01自行决定 |

**待办合并规则：** 当前dashboard已有demand:{id}泛需求提醒（1916）。同一需求进入明确审批/退回/承接节点后，应由当前服务端任务决定显示内容，避免泛提醒与新任务重复，也不能通过旧upsertTask动作文字拼接形成“审批+普通推进”的错误操作。通知eventId负责历史通知幂等；当前待办按实际任务标识/节点聚合，二者不能混用。未读只是阅读状态，不能改变待办数量、原业务状态或既有费用统计。没有截止时间与催办规则时，不把所有审批染成紧急。

**局部失败与现有操作：** 通知请求不能直接放进pageDashboard现有所有业务请求共用的失败链而令整页打不开；服务不可用时保留已有工作台，只在通知区提示。继续复用api（360）、toast（465）、routeEpoch/isRouteCurrent/addRouteCleanup及原图标刷新。当前按旧role计算的canWrite只是已有UI行为，新增通知与办理按钮必须以M01和M03/M02真实权限为准，不修改其他已顺畅业务来迁就通知。

### 原路由与具体事项定位

- 复用navigateTo（281）、routeUrl（305）、restoreRouteFromUrl（322）、positiveRouteId（216）和revealFocusedRow（347）。M02/M03是S01服务端内部目标值，不是原系统页面名；不得直接navigateTo('M03')或把模块编号加入NAV（916）。
- M02目标recordId映射到原demands的focusId；M03同时保留原recordId与taskId，先读取服务端详情确认task.businessId与recordId一致，再在该需求的原详情弹窗定位审批节点/记录。view=detail不显示可办理控件；view=task也必须读取M03当前actions重新授权。
- 现有revealFocusedRow只滚动并高亮一行，不会打开详情。因此正式验收必须做到打开同一事项/原任务记录，不能把“到需求列表”当完整深链接完成。原筛选会隐藏目标时，沿用navigateTo的清筛选能力，返回时保留合理的原列表操作习惯。
- 首选继续使用原#/demands?focus=<需求ID>路线；若需要保留notification/task上下文，由总控在原路由解析器增加严格白名单的内部参数，登录后重新查目标，不接受任意returnUrl，不把邮箱/授权码/验证码放URL或localStorage。现有routeUrl只保存project/focus/status，不会自动保留新参数，必须同步调整写入与恢复。
- 已转派/已办但仍可看时打开历史详情；无权限/已删除时用原toast或弹窗内提示，不打开邻近行、其他任务或自动退回审批总列表。M08总结通知需新增明确资源类型及项目关联后进入原项目工作区的总结位置，不能沿用需求ID猜项目ID。

### 登录局部改动与原控件细节

原doLogin在871调用/login后即赋state.user（875）并恢复路由（876）。总控/Auth/M01须提供明确“尚需邮箱核验”响应与有限核验上下文；前端不能在这个阶段设置完整登录态。继续复用853处禁用按钮、aria-busy、current()存活判断及错误恢复；重发验证码需额外取消/序号保护。取消核验清空本次上下文和验证码，回到原表单，不新开认证窗口。

原openModal支持Esc、取消、焦点圈定和关闭后归还；closeModal（540）没有业务onClose回调。如把核验放弹窗，三种关闭方式须统一清理异步请求，不只绑定取消按钮。原yx_last_username（843）只是记住账号名，不等于信任设备。现有退出（1112）先确认服务端撤销再invalidateSession（226），保留此行为；普通退出是否撤销设备信任须另定规则，不能擅自合并。邮箱核验不赋予岗位权限。

### 样式与验收边界

正式系统保持index.html的body.yx-v13及style.css→studio.css→ledger.css→v10.css→v13.css加载顺序。复用v13现有card/data-card（71）、toolbar（76）、table-wrap（87及手机576）、modal-body（126）、v9-row（208）、workspace-task（792）、原登录卡片（269/299）及账户菜单最终规则（924）。不将notifications/style.css加载到原工作台，不复制demo的品牌页头、三张概览卡、配色或大标题。

总控完成公共文件接入后，必须在原index.html的真实浏览器中核验：五层样式实际加载；桌面/手机原导航不变；原队列新增提醒且不重复；原详情准确定位需求及审批任务；已读与已办分离；通知服务失败不影响原工作台；首次/新设备邮箱核验仍在原登录卡片；退出与权限变化生效。本任务当前只做接点核对与内部标识收敛，未改共享文件，未进行这些正式UI验收。此前独立demo的截图和9组契约测试仅是内部逻辑证据，不能作为原系统正式视觉/端到端验收。

## 本轮文件

- `src/com/training/NotificationChannels.java`：纯通知投影、显式收件人校验、权限复查、内部事项目标、渠道接入状态；附仅供合成测试的 `DemoInbox`。
- `web/modules/notifications/index.js`、`style.css`、`demo.html`：仅供内部逻辑/接口联调，不作为正式UI，不挂主导航、不加载到原工作台。
- `scripts/S01NotificationChannelsTest.java`、`scripts/S01-check.sh`：独占临时目录验证，无数据库、网络和生产写操作。
- 本目录 `ACCESS_AUDIT.md`：现有登录/部署静态盘点及真实设备验收记录模板。

## 责任与可信输入

1. Auth/宿主继续作为唯一会话来源，M01 继续负责人员、组织范围与身份映射。S01 不建用户、角色、权限表，也不提供登录接口。S01 的 `userId` 为宿主从已验证会话解析的稳定用户键；不得从查询参数、表单、邮箱地址或姓名推定。合成例的 `demo-*` 不得作为正式编码。
2. M03 是审批顺序、当前办理人、退回、重提、撤回状态的唯一来源；实际团队承接由 M02 负责。S01 不进行审批操作、不生成下一审批人、不把已读当作已办。
3. 宿主将 M03 已确认的业务事件转换为 `Event(eventId,type,recordId,taskId,destination,occurredAt)`。`recordId` 沿用现有数字事项 ID；`eventId/taskId` 为稳定原始标识，不能每次重试随机生成；标识限定 ASCII 字母、数字、点、横线、下划线、冒号，最长 128。若 M03 实际标识不同，由总控统一调整，禁止截断导致碰撞。
4. `Destination.M02/M03` 是建议的宿主模块 ID；宿主必须将其映射到真实业务/审批模块。类型、事项 ID、目标和任务 ID 只能由已核验的服务端事件给出，不能接受浏览器创建通知请求。ID 限正整数且不超 JavaScript 安全整数上限，JSON 目标中用十进制字符串保持精度。
5. 由宿主实现 `Authority`，使用 M01/M03 约定的输出，不重新维护关系：
   - `audience(event)`：验证事件来源及所有字段，返回该事件确定的 `Audience(rulesVersion, recipients)`；版本为空/无规则时阻断。
   - `active(userId)`：复用 M01 当前账号启停状态。
   - `canView(userId, recordId)`：M01 当前事项数据范围判断；事项不存在/已删除也返回 false。
   - `actionState(userId,event)`：当前具体审批任务由M03判断；退回修改/团队承接由M02结合M03结果判断是否仍可由该人办理。待办返回 PENDING，办完返回 RESOLVED，重新分派返回 NOT_ASSIGNED；无法确定返回 null 或抛出明确不可用错误，禁止返回默认 PENDING。
6. `Recipient(userId,ACTION|INFORMATION)` 仅为事件通知用途，不是新账号或权限角色。用户已批准五步岗位提醒，使用显式 `approvedRule(BusinessMoment)` 取得 `S01-USER-20260921-v1`：提交→负责人；负责人通过→BP；BP通过→团队；退回→原填报人（ACTION）；团队承接→原填报人（INFORMATION）。前三步均ACTION。方法只给岗位/类型/目标，不产生具体人员，宿主须用M01/M03/M02可信关系解析。额外抄送/催办尚未确定，不默认扩大发送。

## 与本轮 M01 / M03 / M02 实际输出对齐

已只读核对三模块本轮 INTEGRATION；以下为宿主转换提案，未直接修改它们的代码。

- M01 AccountBinding.accountId 对应现有 users.id，M03参与人是 personCode。S01建议统一保存账号ID的十进制字符串；由显式绑定从 M03人员码找到唯一启用账号，未绑定则阻断，绝不把人员码转数字或直接当账号。S01示例 demo-leader 等只限测试。
- M03 State.id 为流程ID、State.businessId 为原需求数字ID、Event.version 是流程事件版本；建议通知eventId使用 approval:{流程ID}:{事件version}，recordId=businessId，审批目标taskId=State.id的十进制字符串。这样同一流程多轮通知不会被误去重，也不会把需求ID错当流程ID。
- M03流程到 READY_FOR_TEAM 仅表示审批已齐备；团队实际承接由 M02执行。因此待承接/已承接目标指向 M02，ACTION判定必须复用 M02 validateTeamAcceptance 所依据的当前事实及M01 HANDLE授权。不能把“已审批齐备”当作“已由团队承接”。

| 原始事件/结果 | S01类型 | 建议目标 | 收件人来源 |
|---|---|---|---|
| M03 提交/重提/内容重审进入负责人节点 | REVIEW_REQUIRED | M03，原流程ID+原需求ID | M03当前明确办理人，经M01绑定 |
| M03负责人通过，进入BP节点 | REVIEW_REQUIRED | M03，原流程ID+原需求ID | M03当前BP，经M01绑定 |
| M03 RETURN | RETURNED | 退回填报人用M02；如策略指定退回负责人，由总控适配M03目标 | 已保存策略和returnTargetId，不能自行猜定 |
| M03 BP通过，READY_FOR_TEAM | HANDOVER_REQUIRED | M02，原需求ID | 由用户确认的团队通知配置及M01/M02权限 |
| M02真实承接事务成功 | HANDOVER_ACCEPTED | M02，原需求ID | 已确认提醒原填报人，经M01绑定 |

M03基线策略已在其任务按用户委托制定为两级退回填报人；S01不改变它。用户已明确确认退回提醒原填报人、承接后知会原填报人；额外抄送或催办不在该确认范围。

M03当前前端 mount 尚无初始 taskId 参数读取、M02 live尚未接通。总控必须补齐宿主目标分派和模块定位，不可仅挂载总列表后宣称深链接完成。无需S01复制它们的详情或业务办理逻辑。

## 核心调用

- 服务端在同一事项事务快照内调用 `prepare(event, proposedRecipients, authority)`；校验完整集合：遗漏、多出、停用、无事项访问权、ACTION 不再由该人办理均拒绝整批，不进行部分发送。重复收件人被归一，同一人同时 ACTION/INFORMATION 被视为冲突。
- 返回 `Batch` 为不可变通知计划。生产中必须由总控在 `Api.MUTATION_LOCK` 内、`Db.transaction` 中将业务变化、M03 事件/outbox 和通知事件/收件人一起落库；事务失败不得保留可见通知。
- **重试应重放首次落库的 Batch。不可在重试时再次 prepare**：办理状态/权限/收件人规则可能已经变化；重新计算可能拒绝历史事件或把通知发给另一人。事件 ID 及收件人唯一约束负责去重，旧事件 ID 的内容/收件人/规则版本变化必须冲突，不覆盖。
- `view(notice, sessionUserId, authority, readAt)` 每次先验本人所有权、账号有效和事项访问权，再输出通用通知文本；实时计算 `actionable`，不得用 unread 推断待办。
- `target(notice, sessionUserId, authority)` 同样再次验权，再生成 `{moduleId,params}` 内部目标。仍待办且目标为 M03 时 `view=task`，已办/已转派/仅知会时 `view=detail`。所有通知均保留原事项 ID；M03 目标保留原任务 ID以便定位历史。
- **`view=detail` 只是导航提示**，M02/M03 的详情/办理 API 仍须核验事项和任务权限，并拒绝过期或他人办理请求。M03 若不能打开历史任务，应根据 recordId 展示获授权的事项办理记录，不回退到待办总表或误开当前其他任务。
- 生产读取先从当前会话过滤 `recipient_user_id`，再做当前 M01 权限复查；撤权后历史通知也不返回业务信息。未知通知和他人通知返回同一 404 类错误。
- `DemoInbox` 是带同步保护的内存合成适配器，仅演示/测试；不可接生产、不可作为数据库异常时的回退。内存条目不耐久、无分页，不具备生产 outbox 工作器。
- 现有 `Json.write` 不自动序列化 Java record。HTTP 仅序列化 `view/target/channelStatus` 返回的 Map/List；Batch/Notice 要按字段持久化或显式转换为 Map，不能直接 `Json.write(batch)`。

## 宿主 HTTP 与前端接点（提案）

内部测试入口 `mount(root, context)` 及其style.css仅用于隔离测试；其中live模式是接口联调模式，不是获批的正式UI。正式接入由总控在原app.js消费下列接口和核心输出，复用原样式与控件，不加载本模块独立页面/CSS。内部联调仍要求有效context.user、同源context.request、context.navigate与AbortSignal，前端不读取/保存令牌。

| 方法 | 路径 | 返回/行为 |
|---|---|---|
| GET | `/api/notifications` | `{state:"ready",items:[view(...)],channels:channelStatus(...)}`，仅当前会话本人；宿主负责分页上限，当前前端支持返回一个完整受限批次 |
| GET | `/api/notifications/{id}/target` | `{state:"ready",target:target(...)}`；每次打开重新验权，不更改已读或审批 |
| POST | `/api/notifications/{id}/read` | 空对象请求；由服务端设置首次 readAt，重复调用保留首次时间；返回 `{state:"ready",item:view(...)}` |

失效会话由宿主返回401；通知接口未接入/失败只让原通知局部提示暂不可用，不使原工作台或其他业务页整体失败；绝不使用合成数据补位。生产客户端不得提供 recipientUserId、权限角色或通知内容。

`items` 字段：`id,eventId,type,title,body,createdAt,readAt(null|ISO),actionable(boolean)`。
目标白名单：`moduleId` 仅 M02/M03；`params` 仅 `recordId`、`eventId`、`view(task|detail)` 与可选 `taskId`；不带 URL、姓名、邮箱、会话信息或可执行审批指令。
渠道字段：`channel=IN_APP|EMAIL|PUBLIC_ACCOUNT`；`status=ready|unconfigured|adapter_not_connected`。外部渠道即使配置存在，当前也最多返回 adapter_not_connected，从不宣称已发送。`channelStatus(true, ...)` 的 true 只能在真实站内持久化接入后设置；静态演示明确是合成站内流程。

## 持久化建议（DDL 仅供审核，不自动执行）

```sql
CREATE TABLE notification_events (
  event_id VARCHAR(128) PRIMARY KEY,
  event_type VARCHAR(32) NOT NULL,
  record_id BIGINT NOT NULL,
  task_id VARCHAR(128),
  destination VARCHAR(8) NOT NULL,
  occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
  rules_version VARCHAR(128) NOT NULL
);
CREATE TABLE notification_inbox (
  id VARCHAR(36) PRIMARY KEY,
  event_id VARCHAR(128) NOT NULL REFERENCES notification_events(event_id),
  recipient_user_id VARCHAR(128) NOT NULL,
  intent VARCHAR(16) NOT NULL,
  read_at TIMESTAMP WITH TIME ZONE,
  UNIQUE(event_id, recipient_user_id)
);
CREATE INDEX notification_inbox_recipient ON notification_inbox(recipient_user_id, read_at);
```

用户键字段类型最终跟随 M01/现有 users 主键映射，不能自行建实名映射。事务重试读取事件全部字段及排序后的收件人/用途，与原 Batch 比较（规则版本也比较）后才可视为已处理。`id` 是由 eventId + 分隔符 + userId 派生的确定性 UUID；它不是权限凭证，不能仅凭 ID 授权。readAt 更新应 `WHERE id=? AND recipient_user_id=?` 且只在 null 时设置，再读出首次值。宿主提供上限、分页、留存策略；不在本轮新增后台进程。

## 外部渠道与统一身份的待办

- 用户已确认可用163邮箱、五步岗位提醒范围；用户随后提供的发件凭据已存入本机系统钥匙串；运行服务读取、管理岗位、账号实际发送能力/回执仍未接入。公众号无已确认资源。没有发送适配器、渠道连接或外发。说明见 `MAIL_163_SETUP.md`；页面/合成状态不是接入验收。
- 用户已确认首次/新设备邮箱核验、同设备记住登录。163发件邮箱具体接入、设备信任期限和账号关联规则待补齐；当前未实现验证码或设备记忆。由总控/Auth 与 M01 在同一身份体系接入，S01 不建第二套认证。
- 用户已确认仅要求手机外部网络访问，不要求办公内网。真实入口 TLS、手机可达、首次/新设备邮箱验证、同设备登录保持、消息点击回登录后回到原事项均待真实验证，详见 ACCESS_AUDIT。返回目标只保留受限内部参数，不存会话令牌或外部 returnUrl；会话恢复后必须再次验权。

## 本轮验证与局限

通过 S01 独立 Java 合成验证（65 项，含用户批准的5条岗位提醒）：正常/重复/缺漏收件人、冲突、账号停用、撤权、已读幂等、读/办分离、重新分派、状态不可用、正确事项/任务、标识与安全整数、外部渠道不伪报成功以及现有 Json 输出兼容。

未执行全量 check，未编译到共享 out，未初始化真实数据库。`node scripts/S01-notifications-ui-test.mjs` 9组契约检查通过，含26个目标白名单分支。前端具体检查记录与最终文件清单以 `coordination/modules/S01/STATUS.md` 为准。正式 M01/M03 接口、正式通知规则和真实网络验收完成前，不可标记为生产接入完成。

## M08 总结复核通知接点（已收到总控同步，待扩展）

总控同步用户决定：分公司对接人填写总结，分公司负责人和BP两方复核，管理员（教学研发团队成员和领导）导出；两方复核先后未定。S01可在M08公布“当前待复核人”“退回给填报人”等可信事件后读取收件人并提醒，不自行安排复核顺序或填实名。

当前代码/前端目标白名单仅M02/M03，事件recordId为需求事项；不能将总结ID误当需求ID。接入M08前需总控统一新增资源类型、M08目标及明确总结事件标识（与审批事件命名空间隔离）。需列入的接点为：总结提交后进入已确认复核节点、复核退回、两方复核都完成。管理员有导出权不自动意味着每次复核都要群发通知。当前没有实现这些扩展或发送总结通知。

## 163凭据的本机保存（2026-09-21补充）

用户主动提供的发件资料已用S01专用脚本存入macOS系统钥匙串，服务名 `com.yanxu.S01.smtp.163`。项目不收录邮箱地址/授权码；未建明文env文件，未放前端或Git。`scripts/S01-store-163-keychain.sh`只负责交互存储与元数据存在性验证，拒绝覆盖已有配置，不连接邮箱或发信。

该秘密尚未接到Java运行服务。默认不授予任何应用静默读取，需由总控确定受控读取/运行环境后接入。此时可知EMAIL配置材料存在，但channelStatus只能保留adapter_not_connected。不要把本机钥匙串存在、65项合成检查或资料已提供解释为真实邮箱认证/验证码/送达成功。完整说明见MAIL_163_SETUP.md。
