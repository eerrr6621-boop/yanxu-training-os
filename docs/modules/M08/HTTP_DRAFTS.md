# M08 真实身份下的培训总结草稿持久化


## 2026-09-23 当前接入更新

原总结草稿初始化、路由、引用保护及原项目页面已经由总控接通并验收此前功能；下文“尚未挂载”描述保留为上一轮历史记录。此次M05授课来源已接服务器首次保存/显式刷新，精确JSON、独立delivery.read与历史隐藏、未知值口径、旧快照兼容和验证见 [DELIVERY_SOURCE.md](DELIVERY_SOURCE.md)。该契约覆盖下文M05始终NOT_CONNECTED的旧说明；M07仍PREVIEW_ONLY，正式复核/导出/媒体未开放。


2026-09-22。适配器：`TrainingSummariesIntegration.java`，格式版本 `M08-DRAFT-20260922-1`。本轮独立后端适配，尚未挂公共 Api/Db 或原项目工作区，未部署。此前独立 demo 继续仅作内部测试。

## 实际范围

仅支持当前草稿读取、全量正文保存、不可变修订历史、指定历史版本和显式刷新业务来源。复用原 `TrainingSummaries.Content/Publicity/PublicitySection` 的文字结构及长度约束，不改变旧纯服务、演示或正式 ProjectFacts 必须有编码的要求。

真实草稿用单独的草稿存储表达缺失编码：`project_code:null`、`course_codes:null`，`codes.project/courses:"UNASSIGNED"`。数字项目 id 始终是关联主键，不生成 PROJECT-id 或 DEMO 编码，也不把这个草稿适配器的数据转换成正式导出 DTO。没有照片上传、文件路径、媒体 URL、个人答卷字段或媒体关系表；照片只收普通文字图注。

`submit/review/export` 固定不可用并返回 409；响应能力也固定为 false。负责人/BP 的先后顺序、兼任/自审、代办和退回路线仍待明确。不可套用 M03 顺序，不能因旧纯服务可演示两方复核就打开真实动作，也不能将真实 DTO 交给演示 Word 导出器。

## 认证与范围

每次调用（包括历史读取及幂等重放）重新执行真实 `Auth.current` 和 `OrganizationAccessStore.person`，验证启用账号、启用绑定、人员与机构链。只认可真实 Auth 会话对象；内部调用也不能传伪造 Session 或仅填 uid。

- 读取：`OrganizationAccess.SummaryPermission.READ`，即 `summary.read / VIEW`。
- 保存、刷新：同时要求 READ 和 `summary.edit / HANDLE`。
- 以上只定义技术资源，**没有给任何实际岗位默认授权**。旧 admin/manager、需求权限和客户端 permissions 不能代替它们。

机构来自唯一可信链 `projects → workflow_acceptances → workflow_demands`，同时校验项目需求与受理需求相等、受理资料修订等于需求当前修订、需求非草稿、正且安全的数字主键、有效的组织/受理团队/办理人/时间。无来源、重复或不匹配的旧项目明确不可用，不按用户提交的机构、项目名称或自由正文归属。

已有总结头固定项目 id、受理需求 id 和机构编码。若当前链与历史头不一致，读取和保存均拒绝，不将旧总结自动迁移到新机构。归档项目可按当前 READ 查看当前/历史，保存和刷新（包括命中旧 request_id 的重试）均拒绝；只有待启动、进行中、已完成项目可编辑草稿。未因此新增项目完成或归档的审批前置条件。

## 精确公共挂载位置（由总控修改）

1. `Db.init()` 的已有原表、`OrganizationAccessStore.init()`、`WorkflowIntegration.init()` 之后，在无活动事务时调用 `TrainingSummariesIntegration.init()`。只建 3 张空表，不读取演示文件、不种真实数据、不自动迁移旧项目。**本模块本轮没有在主程序调用 init，也没有自行初始化生产。**
2. 在 `Api.route` 的真实登录检查后、通用 CRUD 分派前加 `if (TrainingSummariesIntegration.handle(ex, s)) return;`，与普通 Workflow/M05 路由同类。保留原 1 MiB JSON 请求上限、正文解析和 `MUTATION_LOCK`，不要套 M07 锁外大文件上传分流，不扩大全局上限。
3. 保留 Api 原有 `ApiException` 处理和最终 `flushResponse`，在事务成功提交之后才发送响应。适配器使用 `Api.ok` 暂存统一 `{code:0,data:...}` 封套，不自行写 socket；本轮没有额外服务器。
4. 原 `pageProjectDetail` “效果评估”下方的小块和原 `openModal/renderForm/collectForm/renderTable` 接法见 INTEGRATION。这个适配器不改界面、不加导航、不加载独立 demo CSS；总控挂好接口后再接局部原表单。只以实际响应 capability 展示入口。
5. POST 使用原宿主 JSON 类型检查；适配器再检查路径、方法、类型及查询参数。GET/POST 均重新比对 Cookie/X-Token 的真实会话与 supplied 是同一会话。额外和重复查询参数拒绝。

### 旧项目引用与归档保护

提供公开的只读保护方法：

```java
TrainingSummariesIntegration.guardLegacyProjectMutation("delete", projectId, null);
TrainingSummariesIntegration.guardLegacyProjectMutation("update", projectId, proposedBody);
TrainingSummariesIntegration.guardLegacyProjectMutation("archive", projectId, null);
```

宿主在同一 `Api.MUTATION_LOCK` 和原写事务内、实际删除/更新及任何“已完成”快捷返回之前调用，不能在检查与写入之间释放锁。

- `Api.delete` 项目分支：紧接已有 `WorkflowIntegration.guardLegacyMutation` 的适当位置增加 delete 保护。存在 M08 总结头即拒绝删除，不级联抹除修订和幂等记录。新增 FK 同时引用 projects 和 workflow_demands，是第二层数据库引用保护。
- `Api.save/validateSave` 项目已有记录的分支：在持久化更新之前加 update 保护，禁止改变已有总结的 demand_id；归档/未知只读状态下不允许旧入口更新或解封。原 Workflow 对 demand_id/bid_id 的保护和原 Api 状态保护仍保留。
- `Api.projectTransition` 的归档事务开头，与现有 `DeliverySettlementIntegration.guardLegacyArchive(id)` 并列追加 archive 保护。M08 只验证历史关联、版本和内容仍可恢复，并保留全部草稿记录；**它不批准归档、不代替 M05 正式支付/结清门禁、不隐式删除/审批总结**。归档成功后 M08 写入直接拒绝。原完成交付不新增 M08 门槛。
- 任何新的批量删除、重绑受理来源或归档入口也须复用相同保护；不得通过旧通用 CRUD 写 m08 表。M08 不提供删除、清空、改机构、解封或直接写历史接口。

这些公共改动本轮均未实施。父级已有 Workflow/M05 保护继续有效，不把独立保护方法的补充描述为原 Api 已存在可解封漏洞。

## HTTP 合同

| 方法与路径 | 输入 | 结果 |
| --- | --- | --- |
| GET `/api/training-summaries` | 必需查询 `project_id` | 当前草稿；未创建时 version/revision 为 0、current 为 null，并返回可用来源和真实能力。 |
| GET `/api/training-summaries/history` | `project_id`，可选 offset=0、limit=20（1–100） | items 倒序元数据、total、offset、limit；每个历史版本仍独立按当前项目范围核验。 |
| GET `/api/training-summaries/revision` | `project_id`、`revision` | 指定不可变正文和来源；标为 read_only。不存在 404。 |
| POST `/api/training-summaries/save` | 下文完整正文请求 | 建草稿或新增不可变正文修订。 |
| POST `/api/training-summaries/refresh` | project_id、expected_version、request_id | 沿用已保存正文，新增业务来源修订；未保存草稿时 409。 |
| POST `/api/training-summaries/submit`、`/review`、`/export` | 无可用业务请求 | 真实会话验证后 409；不办理任何真实复核或导出。 |

保存示例仅含合成的自由文字与技术 id，实际项目由宿主当前项目选择取得：

```json
{
  "project_id": 123,
  "expected_version": 0,
  "request_id": "save-unique-request-1",
  "content": {
    "achievements": "",
    "issues": "",
    "nextSteps": "",
    "publicity": {
      "title": "培训总结",
      "introduction": "",
      "sections": [{"heading": "理论与方法", "body": ""}],
      "photoCaptions": ["课堂授课", "互动研讨"]
    }
  }
}
```

保存为**全量正文替换**，不是 merge patch。省略的旧三字段为空；publicity 可省略/null，旧正文不会被自动改成宣传段落。草稿可留空。章节/图注的数组只允许明确字符串和 heading/body 对象，嵌套未知字段拒绝。项目事实、feedback、actor、organization、synthetic、permissions 等字段都不在请求白名单内。图注先检查 300 字符，再拒绝媒体地址/文件路径；没有下载或解析媒体。

JSON 主键和 expected_version 必须为 Number、整数且处于 JS 安全范围；创建用 0，后续用返回的已保存版本。查询参数只接受十进制数字。request_id 长 1–96，以字母或数字起始，其余允许字母数字、下划线、点、冒号和短横线；按账号区分。同一次网络重试应复用原 request_id，新操作使用新 id。

响应主要字段：`project_id,organization_code,project_status,version,revision,latest_version,current,sources,source_changed,capabilities,synthetic:false,draft_only:true,photo_policy:"TEXT_PLACEHOLDERS_ONLY",format,replayed,reload_required`。

- 正常读取/写入 current 是当前版本，version=revision=latest_version。
- 幂等重放返回原操作的 result revision，replayed=true；如果已有后续保存，latest_version 指向当前头，reload_required=true。宿主只确认原操作成功并重新读取当前头，不能用历史重放内容覆盖新编辑，也不能把 latest_version 套给重放的旧正文后静默保存。
- 历史元数据包含 revision、operation、actor_code、saved_at、status=DRAFT，不返回原账号姓名或全部 M01 配置；历史内容只按单条版本返回。能力是当前状态提示，服务端仍逐次复核。
- 状态码：400 输入/分页、401 无效会话、403 授权或唯一可信来源缺失、404 路径/历史不存在、405 方法、409 版本/幂等冲突、历史损坏、只读归档、嵌套事务或未开放动作、415 请求类型；原宿主超体积为 413。

## 业务来源与明确不可用

服务器只查询可信受理项目的起止日期、人数和业务状态，以及受理链版本。项目本身标题可能含客户名称，现有项目页已展示，本轮不把其全文或联系人、手机号、原需求正文搬入快照。日期/人数缺失或无效时保持 null，并列出 `unavailable_fields`，不补今天或 0；日期颠倒不伪造有效日期。

sources 内容：

- `project.status:"AVAILABLE"`：value 中含数字项目 id、可信机构、项目状态、日期/人数（可空），provenance 中含受理需求 id、资料修订、工作流版本、团队/办理人编码和受理时间。
- `project.value.project_code:null`、`course_codes:null`，`codes:{project:"UNASSIGNED",courses:"UNASSIGNED"}`：正式编码未接入，不用旧合同号、排课标题、课程技术 id 冒充。
- `feedback:{status:"UNAVAILABLE",reason:"M07_PREVIEW_ONLY",value:null,policyConfirmed:false,canCommit:false,historyAvailable:false}`：表示正式评价来源尚未接入。**不是“已确认本项目没有评价”**，不从临时评分预览、个人答卷、旧问卷回收字段或宣传词计算评分。
- `delivery:{status:"UNAVAILABLE",reason:"M05_SNAPSHOT_NOT_CONNECTED",value:null}`：本次尚未接 M05 可追溯授课快照。现有 projectHours 需要额外 delivery.read，且不能凭 summary.read 扩权，因此没有调用它或自行复制统计逻辑。M06 经营洞察的只读聚合也不当作 M08 来源凭据。
- `source_version` 为服务器对本次明确字段快照生成的 SHA-256；用于判断变化，**不是正式业务编码或评价确认标记**。

首次保存取得服务器当前来源。之后普通 save 保留原版本来源，刷新来源必须显式调用 refresh：复制已保存正文，生成新来源版本并保留全部旧版本。读取会计算 source_changed 提醒，但不自动更新正文/来源。暂不可用状态也持久化在每一修订中，将来 M07/M05 接通需版本化适配，不能静默解释旧 null 的含义。

## 锁、事务、幂等与恢复

所有读取和写入均使用 `Api.MUTATION_LOCK`。公开 read/history/revision/mutate 进入时拒绝嵌套业务事务，防止现有 Db.transaction 自动提交调用方事务。mutate 自己开启一个 Db.transaction，头、不可变修订、请求幂等记录在同一事务中写入；最后再次核验当前会话、人员绑定、配置版本和项目来源。任何阶段失败全部回滚。保护方法不创建事务，可在原宿主事务中调用。

- `m08_summary_heads`：project_id 主键/FK，固定 demand_id FK、机构及 version/head_hash。本轮每项目一个草稿版本流是技术存储选择，不据此规定最终业务能否有多份总结；未来扩展须显式迁移，不能覆盖现有记录。
- `m08_summary_revisions`：主键(project_id,revision)，仅 INSERT，保存完整 Content、来源 JSON、操作、操作者编码、账号 id、M01配置版本、时间、前序/本条校验值。无 UPDATE/DELETE 入口。
- `m08_summary_requests`：主键(account_id,request_id)，保存 actor_code、项目、操作、规范化请求的摘要和 result_revision FK；不复制第二份完整正文作为幂等日志。
- 更新头使用 version + head_hash CAS，创建靠主键唯一约束；即使进程内锁外出现冲突，也不会部分写入。当前共享 Db 是单连接模式；本模块并不宣称提供分布式跨服务器部署支持。
- 同账号同 request_id 命中前重验 READ/EDIT、真实人员绑定和归档状态；项目、操作、规范化正文/预期版本或绑定不同为 409。授权撤销或账号停用后不能借幂等返回旧正文。不同账号的 request_id 互不混用。
- 每次恢复检查修订连续性、前序 hash、头 hash，并验证**所有历史正文及来源**的 schema、语义关联和校验摘要；未知字段或损坏记录拒绝。JSON 数值统一规范化，兼容宿主 Json 将数字恢复为 Double 的行为，不因 1/1.0 产生假损坏。
- 当前单项目技术上限 1000 修订、历史 content_json+sources_json 合计 16 Mi 字符；超限拒绝新写入，不删历史。恢复逐条校验而不把全部正文一起读入内存。此限制用于控制锁内工作量，不是业务定额或清理授权。
- Hash 用于发现持久化损坏及版本不一致，不是签名或防数据库管理员篡改的证明。当前格式只恢复本适配器生成的草稿，不自动接受旧 demo JSON 或扩展为已复核状态。

## 验证与未完成

`M08Integration-check.sh` 使用现有真实 Auth/Db/M01 和自有 `/tmp` H2 数据目录，只运行本模块测试；编译当前源文件是为满足真实依赖，不执行共享全套测试或 Db.init 的示例种子。精确结果见 STATUS。没有生产读写、共享 out、网络监听、部署、Git暂存提交、邮件或钥匙串访问。

本轮不会声称公共Api已挂、原系统浏览器通过或真实岗位授权已配置。总控需同轮挂 init/handle/旧引用保护，按原系统样式接表单，再测真实HTTP、会话过期/撤权、项目切换、脏表单和桌面/手机视觉。正式评价、授课快照、编码、照片、复核和Word导出仍分别保持明确的待接入或待确认状态。
