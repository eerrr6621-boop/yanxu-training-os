# M08 培训总结与 Word 集成说明


## 2026-09-23 当前接入更新

原总结草稿初始化、路由、引用保护及原项目页面已经由总控接通并验收此前功能；下文“尚未挂载”描述保留为上一轮历史记录。此次M05授课来源已接服务器首次保存/显式刷新，精确JSON、独立delivery.read与历史隐藏、未知值口径、旧快照兼容和验证见 [DELIVERY_SOURCE.md](DELIVERY_SOURCE.md)。该契约覆盖下文M05始终NOT_CONNECTED的旧说明；M07仍PREVIEW_ONLY，正式复核/导出/媒体未开放。


更新：2026-09-22。宣传正文版本 `M08-publicity-v2` 保留；新增真实草稿适配 `M08-DRAFT-20260922-1`，正式 UI 尚未接入。依据为 coordination/MASTER.md、CONTRACTS.md、modules/M08/BRIEF.md，以及本任务用户后续确认。

## 2026-09-22 本轮真实草稿适配

当前生效的真实草稿HTTP、权限、数据库、幂等、恢复及公共挂载合同见 [HTTP_DRAFTS.md](HTTP_DRAFTS.md)。已实现 `TrainingSummariesIntegration.java` 及专门隔离H2验证（20组场景、279项检查）；尚未挂公共Api/Db或原项目页面。

只开放草稿读取/保存、不可变历史和显式刷新，真实summary.read VIEW / summary.edit HANDLE专用授权且无默认岗位放行。可信受理项目事实由服务器获取；缺项目/课程正式编码通过草稿专用表达保留null，不修改下文纯服务ProjectFacts的编码门槛。M07仍为UNAVAILABLE/M07_PREVIEW_ONLY，M05可追溯来源尚未连接；不将预览草案或未知null当成已确认没有评价。真实提交、复核、导出均不可用，归档只读。

本文件的宣传正文结构、原系统局部UI方案和旧纯服务演示合同继续保留；下文首轮持久化DDL/路由建议已被HTTP_DRAFTS取代，不应同时创建两套存储或接口。原UI行号为2026-09-21快照，需按函数/选择器定位，公共文件由总控修改。

## 已确认岗位和待补材料

用户已确认：培训项目分公司对接人填写；分公司负责人和 BP 复核；管理员账号导出，管理员为教学研发团队成员和领导。两方复核的先后顺序尚未确认，首轮演示允许任意顺序，两方都通过才完成。这是总结草稿复核，不改变 M03 对直接承接业务的负责人先审、BP 后审规则。

用户已提供培训宣传总结文字样例，并明确正文后添加照片。此样例已足以作为内容结构依据，不再把空白 Word 模板或另一份脱敏总结作为前置材料。系统采用标题、项目介绍、课程理论、方法工具、互动实战、组织保障、后续展望与照片位置；段落标题可调整。照片实际文件、存放及访问边界尚未提供，本期仍仅留照片文字位置。原样例的客户及讲师姓名未进入合成演示代码或测试，未将真实案例伪标为合成。

正式岗位对应哪些账号、项目组织范围、兼任/代办/自审、退回的正式路线仍由 M01 与总控统一确认。模块不根据岗位名称、用户姓名或客户端自报权限授权。内容结构已按文字样例落实；字号、页式等视觉细节由演示稿供后续确认，尚未声称正式版式验收。

## 独占交付文件

- `src/com/training/TrainingSummariesIntegration.java`：真实Auth/M01保护的草稿持久化、不可变修订、幂等与来源刷新；本轮新增。
- `scripts/M08Integration-check.sh`、`M08IntegrationTest.java`：真实依赖及隔离H2专项验证；本轮新增。
- `src/com/training/TrainingSummaries.java`：Java 17 纯数据服务，无数据库、文件或网络 IO。
- `web/modules/summaries/index.js`：独立内部总结表单、两方复核、不可变版本记录和导出操作。
- `web/modules/summaries/docx.js`：依赖为零的真实 DOCX 导出器，合成演示专用。
- `web/modules/summaries/styles.css`、`demo.html`：隔离样式与仅 localhost 可用的演示壳。
- `scripts/M08_check.sh`、`M08DomainTest.java`、`M08_ui_check.mjs`：模块定向校验，使用自有临时目录。

未修改公共 Java 类、前端主应用、全量检查脚本，未运行生产写接口、部署、暂存或提交。原系统项目工作区、数据库和正式登录尚未接入；不计划为 M08 新增主导航。

## 正式前端：接回原系统项目工作区

本节落实用户在总控任务明确的原系统设计要求，取代“直接挂载独立模块作为正式页面”的方案。正式基线为 `web/index.html`、`web/app.js` 和既有 `style.css → studio.css → ledger.css → v10.css → v13.css` 加载链。保留原导航、蓝色视觉、项目工作区、表格、弹窗和手机适配；M08 是开发分工，不新增同名用户菜单或另一套首页。以下为已核对的接入方案，尚未写入公共前端。

### 原页面位置与最小变更

行号基于 2026-09-21 当前代码，接入时以函数和选择器定位为准。

| 接点 | 现有位置 | 由总控实施的局部变更 |
| --- | --- | --- |
| 进入项目 | `app.js:1589` 的 `crudActions`、`:1728` 的 `showProjectOverview` | 继续通过项目总览中的“打开项目”进入既有 `project_detail`；保留列表、筛选与当前项目上下文。 |
| 总结概览 | `app.js:1732` 的 `pageProjectDetail`，`:1830–1834` 的 `.evaluation-panel` 之后、`.v13-workspace-main` 结束之前 | 增加一个原样式 `.workspace-panel`，标题为“培训总结”，显示当前状态、已保存修订、最近保存时间及负责人/BP 复核进度；按服务端能力提供填写、查看、复核、版本记录及导出 Word 操作。无记录时用原 `.workspace-empty` 说明并显示有权限的填写入口。 |
| 表单、复核、历史 | 总结概览的局部按钮 | 使用原弹窗，正文预览和版本内容在弹窗内展示；不整页挂载独立 demo。按钮使用 M08 专用局部选择器及宿主回调，不设 `data-project-goto="summaries"`，原路由并无该页面。 |
| 待办（可选后续接入） | `app.js:1872` 的 `pageDashboard`，既有任务集合及项目入口 | 仅在服务端有真实的总结待复核事项时，追加进入对应 `project_detail` 的待办。沿用统一待办协议；本轮不自造待办数量、优先级或额外导航。 |

保留原项目页标题、指标、五步流程条、课程安排、财务侧栏和完成/归档动作；本轮没有要求把总结审批变成项目完成或归档的新前置条件。已归档项目的既有只读展示继续保留，是否允许归档后补总结由总控与业务规则统一确定，不在前端另开绕过入口。

原效果评估区域和 `app.js:1759–1761` 的旧问卷回收统计由 M07/总控调整。M08 仅引用 M07 已核对的汇总输出，不从旧问卷原始记录重新计算，也不将宣传语中的“高度认可”自动生成满意度结论。项目页侧栏的真实客户联系人等信息不得整体复制到编码化总结 DTO。

### 原控件复用与宿主边界

| 原控件/机制 | 代码位置 | M08 使用方法 |
| --- | --- | --- |
| 弹窗和关闭 | `app.js:480` `openModal`、`:540` `closeModal` | 编辑/查看用 `wide:true`，复用原标题、按钮、焦点圈定和焦点恢复。`onOk` 返回 `false` 保留当前弹窗。请求期间由宿主显式设置并最终清理 `mask.dataset.locked`；关闭和 Esc 没有自动脏表单保护，需局部接入。 |
| 表单读取及错误 | `app.js:568` `renderForm`、`:600` `collectForm` | 标题、引言、分段正文、图注复用输入框、textarea、hint、`.field-error` 和错误聚焦。实际方法为 `collectForm`，没有 `readForm`。嵌套数组需局部序列化；M08 负责字符/条目上限、有效正文及业务状态校验。草稿允许不完整，送复核时再检查有效正文。 |
| 版本表格 | `app.js:698` `renderRowActions`、`:707` `renderTable`、`:728` `bindTableActions` | 使用稳定行 ID，列出内容修订、状态版本、保存时间、两方复核情况及只读查看动作；隐藏不允许的行操作。主按钮顺序由宿主显式指定，原 `actionPriority` 未为 M08 新标签排序。 |
| 状态和反馈 | `app.js:761` `tagClass`、`:769` `tag`、`:465` `toast` | 使用既有 tag、按钮和成功/失败提示。草稿、复核中、退回、复核通过的颜色需按原语义显式映射；原映射不完整，不新增另一套配色。退回在当前弹窗内填写必填意见。 |
| 异步与路由 | `app.js:198` `beginRouteEpoch`、`:205` `addRouteCleanup`、`:210` `isRouteCurrent`、`:1701` `editForm` | 沿用路由版本、原弹窗仍连接且身份一致的前后校验，销毁时取消请求和监听；切项目后旧响应不得填回新页面。 |
| 请求与下载 | `app.js:360` `api`，`:435` 的现有二进制下载示例 | JSON 操作复用 api 的会话、signal 和错误处理；正式 Word 走独立且经服务端授权的二进制响应，核验响应后再下载并释放对象 URL。不能用只处理 JSON 的 api 解析 DOCX，也不能以 demo 导出器代替服务端权限。 |
| 样式与手机端 | `v13.css` 的 `.workspace-panel`、`.workspace-panel-head`、`.modal`、`.form-grid`、`.tbl`、`.tag` 及既有窄屏规则 | 沿用完整原样式链，不向正式页面加载独立绿色 `summaries/styles.css` 或复制独立大标题布局。仅在既有组件无法表达时，由总控补最小局部样式。 |

这些 helper 都在 `app.js` 的 IIFE 内，当前没有导出，模块 context 也没有 UI helper。`openModal` 和行菜单会操作 `document.body`，与独立 mount 只能操作 root 的约定不同。因此应由总控在原页面内部调用原 helper、实现宿主适配；M08 提供正文模型、纯服务、校验和导出契约，不复制一套全局控件，也不假设当前 mount 可直接获得原 helper。

`openModal` 先调用 `closeModal`，全应用只有一个弹窗；`confirmBox` 也会替换它。未保存编辑时不要再打开第二个预览/历史/确认弹窗。优先在原弹窗内展开预览、历史或确认区域，或完成保存后再替换弹窗；切换、关闭和路由离开时保护未保存正文及复核意见，不借机修改全应用的其他表单流程。

### 正式交互与权限

- 编辑：只读显示项目事实和 M07 来源版本；填写标题、导语、五类可调整分段和照片图注，旧 achievements/issues/nextSteps 保留折叠区。照片图注仍是文字位置，真实媒体接入另列待办。保存创建不可变修订，预览明确区分未保存内容与已保存内容。
- 复核：展示指定已保存修订及两方意见；复核中正文冻结，退回必须填写意见，两方通过才完成。不自行确定两方顺序、兼任、代办或自审。通过/退回结果更新局部总结区，不能写回无关项目状态。
- 历史与导出：历史只读且保留原来源快照；导出绑定精确修订、状态版本和模板版本，未保存内容不能进入导出。正式导出需管理员身份、项目范围和服务端导出接口；当前没有该接口时显示未接入。
- 能力来源：既有 `app.js:1285` 的 `canWrite()` 只认 admin/manager，`:1290` 的 `roleAction()` 和 viewer 只读横幅不能表达 M08 岗位。服务器必须按 M01 会话及当前项目返回 READ、EDIT、REVIEW_BRANCH、REVIEW_BP、EXPORT 能力及当前允许动作，每次操作重新校验并检查 expectedVersion。客户端的禁用、隐藏、disabled 字段均不是授权证明。
- 加载与异常：新增总结请求独立处理失败，不能因未接入或加载失败使原项目详情的整组 Promise.all 失败。仅在总结区域显示未接入、无权访问或重试提示，不回退合成记录；无记录与请求失败分别呈现。冲突时保留本地编辑并提示刷新版本，不静默覆盖。

### 原系统接入后的验收（尚未执行）

1. 在原入口的真实浏览器中确认五个样式资源实际加载，桌面和手机宽度下布局正常；菜单、项目列表、原弹窗和已有课程/评估/结算仍保持原设计。
2. 从项目总览打开项目，在同页总结区域完成填写、保存、再次打开；项目事实和 M07 来源正确，嵌套章节及图注不丢失。
3. 使用真实授权范围的测试账号验证对接人、负责人、BP 和管理员操作；两方复核、退回、新修订、过期版本和跨项目拒绝与后端一致。
4. 验证关闭、Esc、切换项目、未保存正文/意见、重复点击与慢响应，不丢失内容、不串项目、不覆盖已保存版本；接口不可用时原项目其他区域仍可用。
5. 验证历史只读及正式 Word 与指定修订一致，确认真实照片接入方案后再检查媒体；未接入的正式导出不能转成 demo 下载。
6. 对最终 Word 逐页渲染和目视检查，另外验收原系统实际页面。现有 Java 44 项、独立页面/导出 37 项以及三页样稿检查只证明内部逻辑和演示排版，不代表以上正式接入验收。

## 内部测试页接点（不作为正式 UI）

仅内部合成测试宿主导入 `/modules/summaries/index.js`，调用 `await mount(root, context)`。context 使用约定的 mode、signal、request、user、notify、navigate。切换页面时调用返回的 cleanup 或 abort signal。所有样式限定于 `.yx-summaries`，事件、下载 URL 和计时器随销毁清理。

`demo` 使用文件内明确标为合成的数据，状态只存页面内存，刷新即清空；不调用 request，不读写生产，不写 localStorage。`live` 明确显示“正式模式尚未接入”，不假装加载项目、不回退合成数据。目前没有可授权的正式填写/复核/导出 API。

页面以 content.publicity 的标题、引言、分段正文及照片图注为主；旧 achievements、issues、nextSteps 保留在可展开区域，原版本不会被重排或覆盖。项目事实及 M07 来源结果只读。未保存修改时禁止提交复核和导出。保存保留旧版本；待复核时冻结内容；退回或两方通过后需新建草稿才可修改。导出包含已保存修订号、状态、两方复核意见以及 M07 来源版本。

导出器 `buildDemoDocx(dto) -> Uint8Array` 供页面构建 Blob 下载，输出真实 ZIP/OOXML，不是改扩展名的 HTML。`validateDemoExport(dto)` 检查显式 synthetic、同项目关联、已确认状态和编码字段；通过版本必须有负责人和 BP 两方证据。导出使用白名单字段，未知字段及实名映射不输出，不包含图片或外部关系。自由正文不能靠编码化保证自动脱敏，界面明确要求勿输入真实身份或敏感信息。

`createDemoSummary()`、`createDemoController(initial)` 为合成演示测试接点。前端控制器不执行正式权限；正式权限必须在服务器验证。浏览器能修改 synthetic 标记不是权限证明，因此该导出器不得被当作真实文档导出门禁。

## Java 服务接点与可信输入

| 类型 | 内容及来源 |
| --- | --- |
| Actor | actorCode、internal、Capability 集合、项目数字 ID 范围；由服务器会话与 M01 解析 |
| ProjectFacts | projectId 数字主键、projectCode、branchCode、courseCodes、起止日期、人数、sourceVersion、synthetic；由服务器读取 M02/M05 已约定事实 |
| FeedbackSummary | projectId、summaryId、revision、sourceLabel、importedAt、responseCount、metrics(label/displayValue)、highlights、synthetic；只接 M07 已统计输出 |
| Content | 保留 achievements、issues、nextSteps，新增可选 publicity；由内部填写表单提供的正文和照片图注 |
| Snapshot | version 状态版本、status、全部 Revision、Event、reviewNote；只从可信持久化恢复 |

数字记录 ID 与编码独立，不互相转换。未分配的分公司编码可为 null、课程列表可为空，日期及人数可为 null；缺失在导出中显示未提供，不能替换为零。本节纯服务 ProjectFacts 的项目编码必须有已分配值，演示使用 DEMO 编码；缺正式编码时不要伪造。编码格式与长度目前是技术边界，正式编码样例到位后可能调整。

`FeedbackSummary == null` 只表示确认尚无已汇总来源，不能把上游错误或无权限解释为 null。指标展示值原样传递，不计算满意度、不平均、不换算百分比；禁止传原始个人答卷、个人标识或原问卷附件。M07 正式输出结构尚未完成联调，以上为最小建议 DTO，不代表双方已确认的线上契约。

方法：

- `create(actor, project, feedback, content, now)`：创建草稿及第一个修订。
- `save(actor, snapshot, expectedVersion, content, now)`：新增草稿版本；完全未变化的草稿为幂等 no-op。复核中拒绝；已通过版本不被覆盖。
- `refreshSources(actor, snapshot, expectedVersion, project, feedback, now)`：显式拉取来源并新增草稿版本，保留已复核旧来源。不能跨项目或混用合成/真实。调用方必须确认当前权威来源；不应因临时读取失败清空已有汇总，也不应倒退到旧 M07 修订。
- `submit(actor, snapshot, expectedVersion, now)`：非全空草稿送复核。哪些正式章节必填尚未猜定。
- `review(actor, snapshot, expectedVersion, ReviewRole.BRANCH|BP, approved, note, now)`：按对应能力复核；同一岗位每修订只通过一次，两方都通过才 APPROVED；退回须意见。
- `view(actor, snapshot)`：只读 Map，可由现有 `Json.write` 序列化。不要直接序列化 record。
- `prepareDemoExport(actor, snapshot, expectedVersion)`：需要 EXPORT 能力，且仅 synthetic 来源；真实来源拒绝，提示正式模板/规则未接入。返回白名单 DTO、模板版本 `M08-PUBLICITY-DEMO-2`、layoutAccepted=false、照片占位标记。

能力映射建议：分公司项目对接人 -> EDIT；项目分公司负责人 -> REVIEW_BRANCH；项目 BP -> REVIEW_BP；已验证管理员 -> EXPORT。READ 也需独立项目可见范围。管理员成员身份不能仅凭普通用户自称“教学研发”或“领导”获得。

Snapshot 记录每次保存、送复核及两方意见；恢复时校验事件连续性、合法迁移、修订引用、最终状态和合成/真实一致性。内容修订与状态版本分别递增：相同正文上的两次复核也增加 version，避免过期页面覆盖。

## 首轮持久化和路由建议（已由 HTTP_DRAFTS.md 取代）

1. 在 Api.MUTATION_LOCK 内读取最新快照、会话、项目和 M07 已统计来源；多步写入放入 Db.transaction。恢复快照和 Actor 不得由请求体整体构造。
2. 客户端只提供 Content、expectedVersion、动作、岗位动作标识和意见。服务器独立核验岗位、项目范围及当前状态。所有更新执行数据库 CAS，`WHERE id=? AND version=?`，失败返回冲突。纯服务本身不会提供跨进程锁。
3. 建议 GET `/api/projects/{projectId}/summaries/current`、POST `.../drafts`、POST `.../actions`、POST `.../exports`。URL 尚未接入，由总控最终决定。旧 CRUD 不得绕过同样的授权和状态检查。
4. 建议存储一个摘要头和不可变修订/事件。以下仅是待评审 DDL，未执行，不自动迁移：

```sql
CREATE TABLE training_summaries (
  id BIGINT PRIMARY KEY,
  project_id BIGINT NOT NULL,
  version INT NOT NULL,
  current_revision INT NOT NULL,
  status VARCHAR(24) NOT NULL,
  rule_version VARCHAR(100) NOT NULL
);
CREATE TABLE training_summary_revisions (
  summary_id BIGINT NOT NULL REFERENCES training_summaries(id),
  revision INT NOT NULL,
  revision_json CLOB NOT NULL,
  PRIMARY KEY(summary_id, revision)
);
CREATE TABLE training_summary_events (
  summary_id BIGINT NOT NULL REFERENCES training_summaries(id),
  version INT NOT NULL,
  event_json CLOB NOT NULL,
  PRIMARY KEY(summary_id, version)
);
```

项目与总结是否一对一需正式样例确认，暂未加 project_id 唯一约束。正式导出还需专门导出记录（对应 summary_id、revision、state version、template version、导出管理员、时间、文件校验值）；本轮仅返回导出内容，不声称永久版本归档已完成。表和审计事件归总控统一接入，不扩展 M03 通用审批系统。

## 验证与限制

最新执行结果见 coordination/modules/M08/STATUS.md。Java 脚本仅编译 Json、TrainingSummaries 及 M08 测试到自有临时目录，不抢共享 out，不跑全量 check.sh。UI 脚本通过临时 localhost 静态服务和独立浏览器实例检查流程，不启动正式应用。浏览器/运行时路径可通过 M08_PLAYWRIGHT、M08_BROWSER_BIN、M08_JAVA 调整。

文字样例的结构已实现；用户后续指定版式或提供实际 Word 时再据此微调并重新渲染。已导出的合成演示文件及视觉检查不代表正式账号、正式项目或最终版式验收。没有开发客户入口、问卷发布、自动 H5/PPT、解码工具、云 AI 或照片接收。

### 首轮实际验证结果

2026-09-21：Java 34 项、页面/导出 29 项定向检查通过。页面验证使用已有 Google Chrome 的独立临时实例，覆盖桌面和 390px 窄屏。Java 测试导出的 APPROVED DTO 已直接交给同一 docx.js 生成用户演示 Word，验证服务/导出器字段一致。

演示制品 `<private-workspace>/2026-09-21/yanxu-m08/outputs/M08_培训总结_合成演示.docx` 已通过 ZIP/全部 XML 解析、两岗位意见、M07 来源及无外部关系检查；使用 documents 技能的 render_docx.py 与 bundled LibreOffice 渲染为两页，逐页检查中文、分页、间距和溢出通过。这里只验证合成演示布局，没有正式模板验收，也没有原生 Microsoft Word 跨平台验证。

渲染环境曾缺少中文字体，已通过任务目录的 FONTCONFIG_FILE 指向现有 Songti.ttc 软链接修正，未下载/复制字体或改系统字体配置。当前字体为 Songti SC，项目事实和正文在第一页，M07 汇总、复核和照片位置在第二页。QA 的 PDF/PNG 仅存任务 work/，不作为正式交付文件。


## 宣传式总结数据扩展 v2

新增 `PublicitySection(heading, body)` 与 `Publicity(title, introduction, sections, photoCaptions)`；`Content` 新增可选 publicity，第一个三参数构造器保留以兼容已有调用。JSON 没有 publicity 的旧版本继续旧布局；存在 publicity 时正文按标题、引言、分段和照片位置输出，M07 与复核事实放附录。旧正文仍保留，不推断或自动迁移到新的段落含义。

- title 最多300字符；introduction和每段body最多12000字符；heading最多100字符；photoCaptions每项最多300字符。
- 最多12个分段与6个照片位置是技术上限；默认5段、3张图位是可修改演示值，不是正式业务定额。
- publicity 的标题、空段标题和图注不算有效总结正文；没有引言或分段正文且旧正文也全空时，禁止送复核。
- 段落和照片图注复制成不可变列表并进入当前正文修订。图注变化也必须另存新版本重新复核，不覆盖已通过版本。
- photoCaptions 只保存图注字符串，不接收文件路径、图片URL、二进制、照片授权声明或真实照片。
- API 仓储恢复需兼容旧 publicity 缺省/null，显式解析 sections 与 photoCaptions；不得将未知字段整体映射为真实媒体对象。

用户样例中的项目描述是内容结构参考，不会自动转成正式编码或复用到其他项目。M07 引用保持原值，不从宣传语生成满意度评分或“高度认可”的新结论。

### v2 实际完成验证

2026-09-21：Java 44 项定向检查、页面/导出 37 项检查通过。新增覆盖嵌套正文不可变、仅标题/图注不能送复核、照片图注修改产生新版本、XML 转义、旧 DTO/旧正文兼容、照片数量上限。浏览器验证继续使用临时 localhost 与独立 Chrome，无生产或外部请求，桌面布局已目视检查、390px 窄屏无溢出。

新版制品 `<private-workspace>/2026-09-21/yanxu-m08/outputs/M08_宣传式培训总结_照片占位.docx` 由 Java 已测试 DTO 经同一导出器生成，包含五段宣传正文、三个照片文字位置及来源/复核附录。ZIP、全部 XML、分段/照片图注和汇总原值检查通过，documents packaged renderer 渲染三页并全部目视检查通过，页式继续为 Letter。无真实图片或外部关系；正式照片未接入。旧两页制品保留作为历史演示，不覆盖。
