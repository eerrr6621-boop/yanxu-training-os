# M03 审批与手机待办集成说明

## 2026-09-22 最新兼任分支交付

用户已明确五位BP的区域范围及北京负责人，完整事实引用M01非Git输出，不再重复索取。北京负责人兼北区BP的单次显式确认已实现为新策略；详见 [兼任审批与公共接线说明](COMBINED_APPROVAL_20260922.md)。一次真实办理记录同时承载负责人/BP两职责，仍禁止自批、代理及自动批准。旧11参策略、旧事件和原baseline保留顺序语义，旧单据不迁移。

新增 `Policy.combinedBaseline`、`CombinedAssignment`、`Action.APPROVE_COMBINED`、带可信机构参数的submit重载和 `ApprovalWorkflowSnapshots`。正式启用前必须同步公共WorkflowIntegration的策略读写/权限、S01事件来源与原页面动作，不可仅更新核心。普通流程、持久化、原系统页面和站内通知已由总控前轮接入；下文早期“待接入/尚未完成”描述仅保留为首轮历史，不能作为整个系统的最新状态。本轮新兼任分支尚待上述公共接线及端到端验收。

本轮合成验证：原核心84、兼任135、快照47，共266项通过；未改正式或内部UI，未发布账号/权限、未操作生产。人员职责事实与可信账号绑定/发布仍分别核验。旧36家截图保持历史原件，当前组织继续M01最新37家对照（烟台东区）。

## 首轮基线与组织材料说明（历史契约，兼任例外以上文为准）

基于 coordination/MASTER.md、CONTRACTS.md、M03/BRIEF.md v1。用户于2026-09-21在本任务回复：“这个你来设计就好，我相信你 我给你一份分公司和区域分区的名单吧”。已据此制定下述 v1 基线；随后用户也明确将异常规则交由本任务设计，并提供最初5区36家截图。当前组织归属已同步M01用户指定最新Excel的5区37家，烟台东区；原截图只作历史证据。人员/BP成员名单已由M01接收；岗位/负责范围的最新确认见上文，研序账号绑定仍须显式核验，不能由名单推定。没有修改 Main、Api、Db、Auth、Json、app.js 等共享文件，没有接入生产或执行迁移。投标继续原签报，本模块仅处理直接承接。

## 正式界面回归原系统（2026-09-21最新要求，优先于首轮演示接入建议）

正式产品沿用原 `web/index.html`、`app.js` 和 style.css→studio.css→ledger.css→v10.css→v13.css 加载链。审批核心、政策、验证和材料成果继续使用；`web/modules/approvals/` 的整页、独立配色、标题、筛选与演示身份切换只作内部逻辑测试，**不挂正式NAV、移动导航或正式预览，不作为总体视觉成果**。不在原系统加载该目录的style.css，不调用其mount替代原页面。M03是开发分工编号，不是用户菜单名称。

下列行号来自2026-09-21当前原app.js，仅为定位辅助，合并时以函数名为准；本模块未修改这些公共文件。

| 原页面/位置 | 已有接点与控件 | M03所需局部接入 |
| --- | --- | --- |
| 今日运营的待处理事项 | pageDashboard（1872）；upsertTask/tasks（1885附近）；rowHtml（1947）、priority-list与drawQueue（2031附近）；v9-row、v9-chip-f、展开/收起 | 合并当前用户真正可办的审批任务，用原待办行呈现。按业务需求ID合并已有demand待处理项，避免同一需求重复两行；保留其他任务、排序和筛选习惯。审批未接入只提示该项状态，不使整张首页失败。 |
| 培训需求列表 | CRUD.demands（1317）、pageCrud（1459）、buildActions（1576）；card/data-card、filter-tabs、search-box、renderTable、renderRowActions、row-more | 在原需求行附加审批状态；行操作增加“办理审批”或“查看审批记录”，重提/撤回/催办按当前身份与服务端可用动作显示。沿用原搜索和状态筛选；需要时仅加“我的待办/我提交”局部筛选，不另造审批首页。 |
| 需求新增/编辑与提交 | quickCreateDemand（941）、editForm（1701）、renderForm（568）、collectForm（600） | 保留原资料填写及保存习惯；M02明确DIRECT后增加显式“提交审批”。审批意见、退回原因和确认按钮放原弹窗；查看本次内容版本的完整需求材料后办理，用户无需填写ID、版本、策略码等实现字段。 |
| 办理和记录弹窗 | openModal（480）、confirmBox（559附近）；modal-head/body/foot、detail-text、form-grid、btn、tag | 在现有“业务详情”增加审批区和记录区，复用表单必填、按钮忙碌、焦点恢复与关闭保护。不创建第二套全屏布局或新大标题。记录展示时间、节点、办理人、意见，代理有实际人与被代理人；缺规则/人员显示业务提示。 |
| 项目总览/项目详情 | pageProjectDetail（1732）；v13-project-brief来源记录（1844）；showProjectStart和现有生命周期动作 | 在关联来源需求的资料区补只读审批摘要及“查看审批记录”。审批与团队承接是两步；M02团队承接动作同事务调用requireReadyForTeam，已有启动/交付/归档步骤保留。不把BP通过显示为项目已启动或已承接。 |
| 手机办理与提醒链接 | NAV（916）保持；mobile-dock（1103，今日/项目/排期）和navigateTo（281）、revealFocusedRow复用 | 用户仍从“今日”待处理事项或需求行进入原弹窗。提醒落到已有#/demands?focus=<需求ID>再读取最新授权数据；focus不是workflowId，需服务端明确businessId与需求的映射。页面没有“M03”独立导航。 |
| 状态与版式 | tagClass/tag（761/769）、renderTable（707）、bindTableActions（728）；原v13样式和移动表格data-label/mobile-hide | 只扩充必要审批状态文本的既有tag颜色映射；不注入新色板、字体、卡片系统或全局CSS。记录过多用现有表格/折叠习惯，不将内部演示流程图搬成新的主页面。 |

### 必须保持的业务及接口边界

- 审批状态与原业务生命周期分别保存和显示；不把demands.status整套替换成审批枚举，也不改变投标原签报路径。仅对已进入DIRECT审批的记录限制通用编辑/删除/状态下拉绕过审批；需由Api/M02服务端实施，不能只藏按钮。其他未涉及记录继续原有逻辑。
- 已确认的负责人→BP顺序只用于直接承接。总结的负责人/BP双方复核顺序尚未确定，不能调用此基线自动套用；总结入口由M08和总控整合。
- 服务端actions是审批权限依据。原canWrite只能用于原有通用编辑入口，不能直接授予审批，也不能代替M01的明确人员绑定；总控应分别接入这两种权限，避免负责人/BP因旧角色名被误挡或普通写权限被误放行。
- 正式页面复用原api(path,opts)（360）：它自动加/api前缀并序列化对象body，成功要求{code:0,msg,data}。例如传路径'/approvals/tasks'，传body对象，不能再传'/api/...'或已经JSON.stringify的body。正式Api按原包封返回，Failure.code放data.approvalCode供局部提示，msg为业务原因；当前独立演示context.request的fetch契约仅用于测试，不直接代替原api。
- 办理前重新读取单据/权限，POST携带expectedVersion/expectedStage/requestId；身份证明、dataRevision取服务端。沿用openModal的锁定按钮并保持失败弹窗可重试/刷新；超时或409先读回状态。办理成功更新原需求行和今日待办，保留原筛选与定位，不跳到另一套主页。
- 沿用routeEpoch、isRouteCurrent（210）、addRouteCleanup（205）及弹窗连接检查；页面切换或关闭后取消请求，迟到结果不得更新其他页面。原api已支持signal。远程标题/意见经esc处理，不拼入未经转义HTML。

### 正式接入验收（总控实施后执行，当前尚未完成）

用原index.html入口实际启动原系统，在浏览器确认五层CSS、图标和app.js均真实加载且无404，再对桌面与手机检查原首页待办、需求行、更多操作、办理弹窗、意见输入、关闭与返回。核对负责人→BP→待团队、退回重提、撤回、旧版本/重复点击、手机窄屏与长标题；非当前人员无办理权限，提交后原待办及时更新，退出/返回保留原操作习惯。未经这次正式入口的视觉和操作验收，不将首轮内部demo的390px检查或18项测试表述为正式系统验收。

## 已实施的服务边界

`ApprovalWorkflow` 是 Java 17 纯状态服务，无数据库、网络和时钟隐式调用。显式参与人、不可变策略快照、认证上下文、当前单据内容版本和服务端时间作为输入。`submit` → `LEADER_PENDING` → 负责人 `APPROVE` → `BP_PENDING` → BP `APPROVE` → `READY_FOR_TEAM`。状态仅表示可交团队，实际团队承接由 M02 完成。

- `submit(workflowId, businessId, title, "DIRECT", participants, policy, dataRevision, requestId, at, access)` 创建 version=1 的流程。投标路由返回 `EXTERNAL_APPROVAL_REQUIRED`，不得绕开原签报。
- `apply(state, command, access)` 返回新状态；拒绝越权、旧版本、旧节点、重复 requestId、版本倒退、非本阶段办理及不合法的历史。失败不修改旧对象。
- `requireReadyForTeam(state, currentDataRevision)` 是 M02 承接的强制门禁，核对两级批准和本次内容版本证据。**不能只判断 status 或统计历史上出现过两条通过记录。**
- `view(state, access, currentDataRevision)` 返回可由现有 `Json.write` 输出的 Map；含 `actions` 服务端可办理性、参与人、当前负责人、版本和完整时间线。直接用 `Json.write(record)` 不会产生预期 JSON。
- `State` 可由可信持久化数据构造恢复，构造器核对事件版本连续、requestId 唯一、状态链、两级顺序、内容版本、重提轮次和批准证据。State/Policy/Access/Command **均不可从请求体整体反序列化后直接使用**。

## 规则及未配置行为

### 已按用户委托制定的 v1 基线

正式组装时显式调用 `Policy.baseline("M03-BASELINE-20260921-v1")`，不要依赖缺值默认补齐。这一工厂包含：负责人/BP都退回填报人；所有重提从负责人重新开始；禁止自批和同一人连续办理两级；本版本不开放代理（包括离岗代理）；待负责人、待BP、已退回均允许填报人撤回，完成审批后不可撤回；审批中内容变更从负责人重审。离岗或角色冲突需先由 M01 完成另行授权的人员配置，不能自动替换。若后续用户决定开放代理，需明确授权范围、期限及审计要求后另发策略版本。

名单将用于组织层级（区域→分公司），不是审批身份的证明；不能单靠姓名生成负责人/BP绑定。通知对象已按总控转达的S01用户确认决定对齐，下文载明；渠道/催办入口、已完成流程修改、团队承接后回退、现有流程换人/换策略仍保留为明确的集成待办。此基线是用户委托后的设计决定，应由总控同步；不是将合成演示参数冒充用户原有制度。

### 可配置能力

`Participants` 的 submitterId/leaderId/bpId 都必须显式非空；不按姓名或角色推断。ID 是不透明字符串，可使用 M01 已确认 person_code；业务记录 id/businessId 仍为数字。

`Policy` 需要非空 version，随流程保存完整不可变快照：

| 字段 | 支持值与说明 |
| --- | --- |
| leaderReturnTo | SUBMITTER / UNCONFIGURED；负责人退回本人不受支持 |
| bpReturnTo | SUBMITTER / LEADER / UNCONFIGURED |
| resubmitFrom | FROM_LEADER / FROM_RETURNED_STAGE / UNCONFIGURED |
| selfApproval | ALLOW / DENY / UNCONFIGURED；实际办理人或名义审批人为填报人均需规则 |
| combinedRoles | 同上；名义负责人兼BP或两级实际由同一代理人办理均需规则 |
| delegation | 同上；只允许 M01 已验证、且只针对本流程当前节点的代理授权 |
| inactiveAssigneeDelegation | 同上；原审批人离岗后的代理另需明确规则 |
| withdrawalConfigured + withdrawableStatuses | 未配置、明确禁止（空集合）、按节点允许三者分开；仅待负责人、待BP、已退回可配置 |
| documentChanges | RESTART_FROM_LEADER / DENY / UNCONFIGURED |

缺策略返回 `CONFIGURATION_REQUIRED`（提示“待配置”）；明确禁止返回 `POLICY_DENIED`。普通、不同人员的顺序通过不依赖退回等异常规则。每条规则仅在相应动作上触发，不把未知规则当作允许或禁止。

退回要求原因。BP退回可保留当前内容版本的负责人通过证据，但只有显式 `FROM_RETURNED_STAGE` 且内容未变才能返回BP。显式 `FROM_LEADER` 会清除前次审批。撤回清除通过证据；撤回后重提仅明确 `FROM_LEADER` 才可执行，`FROM_RETURNED_STAGE` 不解释为撤回策略，而提示待配置。内容版本增长需要 documentChanges；支持明确从头重审或禁止，不支持默许保留旧批准。`REVISE` 仅供 M02 在审批中修改内容时原子调用，不在首轮手机动作中展示。

v1不开放批准完毕后的直接修改/撤回、团队承接后的回退、人员替换或特殊退回目标；离岗暂停办理，重新配置须另走管理员授权和审计迁移。不得直接编辑现有 State 的人员/策略/通过标记来修复；后续需要独立授权、版本变更和审计迁移接口。现有流程策略固定，补全规则后已有流程如何迁移需总控明确设计；不能替换同一策略 version 对应的内容。人员在岗状态每次由 M01 提供，UNKNOWN 提示待配置，INACTIVE 本人不能办理。

## 总控接入位置

1. **Auth / M01**：现有 `Auth.Session` 只有 uid/username/name/role，需以认证 uid 解析已核实人员编码；不得以 name、role 或任意 username 替代。`Access.actorId`、目录在岗状态、代理范围均由宿主提供，忽略客户端的 actorId、人员、策略、审批标记。列表与详情先按 M01 的组织范围、参与人/代理及审计权限过滤，不能泄露其他流程。
2. **M02 提交**：确认 DIRECT 单据、服务端当前内容版本和参与人，唯一 `businessId` 对应一个流程。参与人尚未齐全时保留业务草稿并返回配置缺失，不创建假审批人。
3. **Api 写入**：在现有 `Api.MUTATION_LOCK` 内开启 `Db.transaction`，读取最新状态及当前业务版本，解析经过校验的枚举和整数，核对业务可写范围，构造服务端时间和 Access，调用 `apply`，按 expectedVersion 条件更新，写历史，按同一事务更新 M02 业务状态。恢复/校验异常映射为结构化错误，不输出堆栈给手机。
4. **并发/重试**：数据库须同时约束 `(workflow_id,version)` 和 `(workflow_id,request_id)` 唯一。客户端超时重试同 requestId 应返回已办理提示/查询结果，不产生新事件。新 requestId 但旧 expectedVersion 同样拒绝。多实例需数据库版本 CAS，不能仅依赖 JVM 锁。
5. **团队承接**：同一事务调用 `requireReadyForTeam` 后再由 M02 校验团队角色及承接状态，更新业务。重复承接的幂等与承接后锁定由 M02 管理。
6. **通知**：本轮无通知外发。调用`notices(before, after)`生成稳定幂等键的通知计划，在状态变更同一事务内保存出箱，再由S01处理。名单/通道未解析时待配置，不能猜培训团队成员。不得在数据库事务外凭页面点击结果发送。下面给出用户已确认的事件与催办设计。

### 通知与催办（已按S01对齐）

总控于本任务同步S01的用户确认：提交→负责人；负责人通过→BP；BP通过→培训团队；退回→原填报人；团队承接→原填报人。M03 `notices` 已覆盖前四项（基线退回对象固定填报人），BP通过不提前通知填报人已承接；最后一项必须消费M02真实承接事件，不能用M03的READY_FOR_TEAM代替。重提/字段重审按新的当前节点生成待办；撤回产生填报人确认与旧待办取消计划，任务取消事件应作站内状态更新，若S01不支持则暂不外发。培训团队是显式TEAM_QUEUE目标，实际组织/接收人由M01/M02解析后交S01；没有成员映射时留待配置。

`remind(state, expectedVersion, requestId, at, access, ledger)`实现手动催办：仅原填报人、仅当前在岗审批人、节点停留满24小时、同一流程24小时内至多一次；拒绝重复请求、旧版本、已完成/退回/撤回状态或离岗目标，不推进审批状态。时间按连续24小时计算，不假设工作日历。本轮不创建自动定时催办。at必须由服务器时钟生成，不能采用客户端提交时间。ledger必须由服务端从数据库完整读取并与新记录/出箱同锁同事务保存；不能接受客户端历史。多实例必须按流程在数据库层串行化记录的读取与追加（例如对流程行SELECT FOR UPDATE），仅请求ID唯一约束不能阻止两个不同请求并发催办。建议独立approval_reminders表约束(workflow_id,request_id)唯一，保存Reminder所有字段。通知/催办服务端适配与手机催办入口尚未接入，不能声称已发送或已具备端到端催办。

### 组织名单来源

**当前组织归属（2026-09-22同步）**：用户明确指定最新的`20260803-v1-区域分公司对应表.xlsx`，采用M01输出版本`M01-ORG-DRAFT-20260921-v3`。共5区37家：**东8、南9、西7、北6、中7；烟台分公司归东区**。M01已核对“区域分公司对应表”A2:B38，烟台在A9:B9；主来源SHA-256为`52b80d372c4ef3a8e76e21cccf90082c53dbf68beacd6aa8cf11ae0553e0fb3c`。

当前对照引用`<private-workspace>/2026-09-21/yanxu-m01/outputs/M01-公司区域对照.json`；来源判定、完整公司名及后续组织配置由M01唯一维护。M03不另建当前组织清单，不把历史截图重新包装为最新版。

**历史材料（原样保留）**：本目录`ORG_INTAKE_V1.json`、`ORG_INTAKE_V1.md`及共享截图`coordination/materials/分公司区域名单_20260921.png`记录东7、南9、西7、北6、中7，共36家；原文含南区两个大厦独立条目。先前M03输出的36家清单也仅作历史来源。其数量、名称、空编码和空人员字段不改写，用于追溯，不再作当前归属依据。

**组织与账号分开**：最新区域表及已收群组名单不能证明负责人/BP的正式职责、负责分区或研序账号身份。M01已收人员和BP成员名单，不重复向用户索取；M03只消费M01明确授权、已绑定账号的参与人。不能将成员名单顺序对应分区，也不能把一事通ID当成users.id或密码。正式编码和岗位/账号关系仍须M01显式配置。

本轮只读核对M01 STATUS和上述JSON的版本、计数与烟台归属，仅修改M03 STATUS/INTEGRATION；历史证据、审批核心签名、公共文件均不变，无需重跑业务测试。

### 建议路由（尚未接入）

- GET `/api/approvals/tasks`：返回 `{tasks:[view...]}`，经权限过滤；可包含当前办理、我提交、办理记录，正式分页由宿主实现。
- GET `/api/approvals/tasks/{id}`：返回 `{task:view}`。
- POST `/api/approvals/tasks/{id}/actions`：接受 `{action,expectedVersion,expectedStage,requestId,comment}`；`dataRevision` 必须从服务端当前 M02 单据读取。成功返回 `{task:view}`。客户端不能传人员身份决定授权。
- 正式返回沿用原 `{code,msg,data}` 包封：成功code=0，data为上述tasks/task；失败code使用相应错误值，data.approvalCode携带核心Failure.code。409用于旧版本/节点/重复/内容已变，403用于非指定人员/策略明确禁止，422用于参数/缺规则/原因缺失，404用于不可见流程或未接入路由。原api会读取msg展示错误。

**以下仅记录内部逻辑测试夹具契约，不是正式页面安装步骤。** `web/modules/approvals/index.js` 的 `mount(root, context)`、独立style.css与demo.html保留供内部测试，正式系统不加载整页组件。内部测试销毁时执行cleanup或中止signal；demo禁业务网络，live契约模拟不得回退合成数据。正式接入按上节原app.js的现有页面与api方法实施。

`context.request` 使用 fetch 形式的 options（JSON 字符串 body、headers、AbortSignal），支持返回已解析对象或原生 Response。`context.user.personCode`（兼容 person_code）须提供 M01 已核实编码，供“我发起”筛选；没有人员编码时提示身份未接入，不能把数字账号 uid 当成人员编码。列表/详情实际授权仍在服务端。正式模式重提不接受手填材料版本，使用服务端已保存内容；不强迫每次重提都修改单据。时间线会同时显示代理的实际办理人和被代理人。首轮详情展示主题、流程/参与人及办理记录，完整业务材料由 M02 详情适配补齐；正式审批接入前须确保办理人能查看对应内容版本。

### 建议 DDL（待总控确认并加入 Db，未执行）

```sql
CREATE TABLE approval_workflows (
  id BIGINT PRIMARY KEY,
  business_id BIGINT NOT NULL UNIQUE,
  status VARCHAR(32) NOT NULL,
  version BIGINT NOT NULL,
  data_revision BIGINT NOT NULL,
  round_no INT NOT NULL,
  submitter_id VARCHAR(160) NOT NULL,
  leader_id VARCHAR(160) NOT NULL,
  bp_id VARCHAR(160) NOT NULL,
  policy_version VARCHAR(160) NOT NULL,
  policy_json CLOB NOT NULL,
  leader_approved BOOLEAN NOT NULL,
  bp_approved BOOLEAN NOT NULL,
  returned_stage VARCHAR(16) NOT NULL,
  return_target_id VARCHAR(160) NOT NULL,
  title VARCHAR(500) NOT NULL
);
CREATE TABLE approval_events (
  workflow_id BIGINT NOT NULL REFERENCES approval_workflows(id),
  version BIGINT NOT NULL,
  request_id VARCHAR(160) NOT NULL,
  event_json CLOB NOT NULL,
  PRIMARY KEY(workflow_id, version),
  UNIQUE(workflow_id, request_id)
);
```

事件 JSON 保存 Event 所有字段并逐条恢复，枚举值按严格白名单读取；at 保存 UTC ISO 时间。title 只作列表摘要，审批查看的正式内容仍由 M02 输出并携带同一 data_revision。本轮未提供数据库仓储与HTTP适配，不能宣称正式端到端审批已上线。

## 验证

`bash scripts/M03-check.sh` 仅编译本类、现有 Json 和模块测试到独立临时目录，执行合成纯状态测试，无数据库和网络，未运行全量 check.sh。覆盖正常两级、BP提前审批、越权、自批、兼任、双级共用代理、离岗、未知目录、退回重提、版本变更、撤回、缺配置、重复请求、损坏恢复状态、不可变记录及 Json 输出。前端测试与视觉结果在模块 STATUS 汇总。

内部逻辑验证结果（不等于原系统正式UI验收）：核心84项、`node --test scripts/M03frontendUI.test.mjs` 的18项均通过。仅对内部独立演示做过浏览器检查：桌面与390×844手机布局及负责人→BP→可交团队操作；团队步骤仍保持未承接。所有演示为合成且无业务请求，临时预览服务已停止。
