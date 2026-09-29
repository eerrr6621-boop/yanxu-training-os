# M07 正式汇总候选接入合同

2026-09-23。本候选只在本任务私有work，未覆盖共享app，未初始化实际app/data，未部署。主系统已有评分草案预览继续保留。首期保持原电脑界面，不新做手机专项或模块导航。

## 业务边界和待答复

用户已授权完整首期正式闭环。正式统计和复核岗位仍缺业务答复；本轮已仅发一次合并可选题，建议0–10有效（可选排零）、空白/异常排除、每题有效数分母、两位HALF_UP、文件每行保留且不按人去重、同文件拒绝、另一名培训团队授权人员复核、修正版填原因并审后替换。**没有把建议当成答复，没有发布任何实际政策或岗位配置。**

无显式已确认政策时，既有无持久化草案接口照常可用；新正式prepare/confirm/review返回409，不能改标签后把旧草案入正式来源。可信政策配置齐全后，候选已具备保存、复核、退回及修订功能，不需要补原始答卷或重写核心。若用户选择其他统计规则，不得把该选择硬塞到本轮固定策略枚举中。

## 候选文件与公共改动

四个产品源文件：

- SurveySummaryImportsFormal.java：6张空表初始化、服务器策略配置、汇总确认凭证、追加事件账本、复核/替换、总结可信来源与引用保护。
- SurveySummaryImportsPolicy.java：严格政策及匿名汇总schema、恢复统计验证。
- SurveySummaryImportsResponses.java：新增excludeZero，保留旧3参DraftRules及4/5参preview兼容；不把排零错误实现成1分起算，小正数仍可有效。
- SurveySummaryImportsIntegration.java：保持旧config/preview协议；现有7MiB锁外入口新增prepare分支，复用原并发/期限/安全解析和真实Auth/M01来源。

总控在原Db.init的projects/workflow_demands及M01初始化之后、无活动事务时调用`SurveySummaryImportsFormal.init()`。只建空表，不种政策/身份/答卷。候选没有修改Db/Api/Auth/app.js或M08。

旧上传独立分流`SurveySummaryImportsIntegration.matches/handle`继续放在Api统一1MiB正文读取及route总锁之前：新`/api/survey-response-imports/prepare`复用7MiB专属限制，其他接口上限不变。正式结果小JSON路由在原Api已认证route里加`if (SurveySummaryImportsFormal.handle(ex,s)) return;`，继续原1MiB读取、请求私有缓存、MUTATION_LOCK和锁外flushResponse；不要恢复旧共享exchange属性缓存。

## 策略配置（仅可信宿主调用，不开浏览器任意策略写接口）

```java
var policy = new SurveySummaryImportsPolicy.Policy(
    "实际批准的版本编码", true /*零分有效性，以用户答复为准*/,
    "实际决定依据编码", true /*仅已有明确批准时*/, Set.of("真实M01复核岗位编码")
);
SurveySummaryImportsFormal.configure(session, organizationCode, expectedPolicyVersionOrNull, policy);
```

上例是参数示意，不能直接作实际配置；候选没有内置正式岗位名称。`survey.policy / HANDLE`必须显式授权给可信维护人；Policy.reviewerRoles必须存在于当前M01角色目录，evidenceRef不能空。旧admin/manager角色不能隐式配置。每次配置追加不可变政策版本，head用expectedVersion CAS，不能覆写或复用版本编号。

策略固定空白/异常EXCLUDE、分母PER_QUESTION_VALID、记录KEEP_ALL（含隐藏行、不按状态筛除）、2位HALF_UP、总体q10、独立复核和审后替换。zeroValid显式二选一。非法公式/日期/百分数继续列异常。零有效评分的题均值null。Policy.confirmed=false可表达未确认模型，但本轮不允许将其发布为正式策略。

权限资源：survey.read VIEW；survey.preview HANDLE（既有上传解析）；survey.import HANDLE；survey.review HANDLE；survey.policy HANDLE。正式读取需要read，prepare另需import+preview，confirm另需import，review另需review且命中policy指定岗位在本机构的**实际M01授权规则**。不能用A机构BP身份加另一岗位在B机构的review权拼接。全部显式DENY保留优先。实际角色与授权由总控/M01按证据配置。

## 请求与响应

### 1. GET `/api/survey-results?project_id=123`

只接受唯一project_id，正安全整数。返回`project_id,version,records,policy,capabilities{prepare,review},synthetic:false`。records按导入事件顺序列出，含import_id、series_id、replaces_id、state、current、summary、policy、importer_code/imported_at、reason、review_reason、reviewer_code/reviewed_at/review_event_id。无文件名、原始格值、个人答卷或指纹。

`reason`始终是导入时的更正原因，新批次为空；`review_reason`单独表示该条记录复核时的意见或退回原因。待复核或无意见批准时为空字符串；REJECT显示已保存的非空退回原因，APPROVE若填写意见则原样显示。两者从不同历史事件恢复，不互相覆盖，重新打开或服务重启后保持。原页面应以“更正原因”和“复核意见/退回原因”分别按纯文本展示。此次DTO新增只影响记录读取，不改变已保存事件schema、M08来源schema或摘要。

state可能PENDING_REVIEW、APPROVED、REJECTED、SUPERSEDED、SUPERSEDED_PENDING。current=true只表示当前生效的已复核版本；待审修正版不改变它。capabilities只是当前提示，执行仍重新核验；review提示不代表能审核自己提交的那条记录。

### 2. POST `/api/survey-response-imports/prepare`

```json
{"fileName":"评分.xlsx","xlsxBase64":"标准带补位Base64","projectId":123,"replacesId":0,"reason":""}
```

恰好5个简单字段，不接受规则、统计值、身份或状态。新独立批次replacesId=0且reason空；修订必须指向该批次最新tip（可以是待审、退回或已批准版本），并填写非空更正原因。每项目可有多份独立问卷批次，不能把不同批次相加或再平均。

解析前读取当前已确认Policy与可信受理来源，具体项目授权后才解码/解压。整个上传最多7MiB、文件5MiB；2个全局槽/1个账号槽，15秒协作预算和已有ZIP/XML上限。解析锁外，最终来源/权限/政策/账本核验与Api.ok暂存同一短锁，不能在中间撤权后仍返回成功。

- 正常：`state:READY_TO_CONFIRM,canConfirm:true,policyConfirmed:true,summary,policy,project_id,expected_version,replaces_id,reason,preview_token,expires_at,duplicate,review_required:true`。
- 文件错误：`state:ERROR,canConfirm:false,preview:原安全错误结构`。
- 重复拒绝：`state:DUPLICATE,canConfirm:false,summary,duplicate:{status:EXACT_FILE,import_ids:[...]}`，不生成凭证。
- duplicate还可能NONE、SAME_SCORES_CANDIDATE（只有评分相同，不证明同一批）、EXPLICIT_POLICY_RECALCULATION（下面窄范围例外）。

确认凭证只保存服务器汇总白名单与源指纹，不保存XLSX/Base64/文件名/个人行/问题行定位。随机64hex、5分钟、总容量128，绑定**确切会话对象**、账号、人员、完整M01配置摘要、来源、政策、项目账本版本和预览内容。错用户/错项目不能消耗合法凭证；合法持有人观察到过期/来源/权限/历史损坏后永久作废，恢复旧事实不能复活。服务重启后重新预览即可。

### 3. POST `/api/survey-results/confirm`

```json
{"project_id":123,"preview_token":"服务器凭证","request_id":"本次唯一请求编码","acknowledged":true}
```

不接收汇总值或政策。原子追加IMPORT事件和同项目文件认领及幂等记录，状态PENDING_REVIEW；不是正式生效。网络重试复用request_id；同账号同请求只返回原回执，不重复写入，返回`project_id,result_version,state,replayed,reload_required:true`后重新读取当前账本。新操作新request_id。不同内容/项目/人员绑定复用旧request_id冲突409。

### 4. POST `/api/survey-results/review`

```json
{"project_id":123,"import_id":1,"expected_version":1,"decision":"APPROVE","reason":"","request_id":"review-unique"}
```

仅APPROVE/REJECT，退回原因必填。当前会话具备独立review权限且在策略准许岗位对这个机构的实际授权范围；账号与人员编码分别都不能等于该版本提交人。仅最新tip且PENDING可审。批准要求政策仍一致、可信受理来源未变化。退回保留内容与原因。事务追加REVIEW事件并CAS项目账本头，重试仍要现有权限/岗位/绑定/可编辑状态。

批准新修订后旧已批准记录派生为SUPERSEDED，所有旧内容、原审核证据和替代链永久保留。候选不提供删除、改写历史、撤销审核或任意指定生效版本入口。

## 同项目查重及政策重算

同一项目同文件在同政策下禁止再次确认，包括已退回或被修订记录；数据库唯一键(project_id,file_hash,policy_hash)兜底。评分指纹只作候选提醒，含异常时不生成评分指纹。不同项目之间不返回重复结果，也不按个人身份去重。

政策更新后，旧结果不会被自动按新政策解释；总结来源显示POLICY_CHANGED_REVIEW_REQUIRED。为避免旧文件无法按新制度重算，存在一个**明确修订**的窄路径：必须replacesId指向该批次最新tip、原文件hash相同、当前已批准新政策hash不同且该文件/新政策未用过、填写原因。响应明确EXPLICIT_POLICY_RECALCULATION，用户重新确认、另一人重新复核后才替换。不能利用换政策把相同文件当新批次重复计数。此路径是政策修订，不是自动重算已生效历史；请总控在原界面明确展示。

## 账本和数据完整性

6表：m07_policy_revisions / m07_policy_heads；m07_result_heads / m07_result_events；m07_file_claims；m07_result_requests。项目/需求/事件FK保护。每个导入和复核是仅INSERT的事件，带actor/account、M01摘要、时间、前序hash、request_hash、精确policy/summary/source；heads只CAS更新version/hash。active/tip从完整不可变链恢复，不能篡改一个裸状态字段就变成已审核。

恢复校验连续版本、前序/本条摘要、所有政策字段、各题人数/精确和/均值、复核事件引用、账号与人员独立性、替代顺序、文件认领对应关系及源项目/机构。所有JSON数字规范化，兼容宿主Json恢复为Double。单项目1000事件、16Mi字符是技术上限；超限拒绝新操作，不删除历史。摘要用于发现损坏，不是抵御数据库管理员篡改的签名。

公开写方法自行开启事务，拒绝嵌套；权限先于幂等回执。所有写入和最后复核在同一事务中，失败不留下半条记录；确认凭证仅成功提交后消费，纯事务故障可按同request重试。文件内容从不写盘或日志；仅统计汇总、源编码、用户输入的修订/退回原因和服务端摘要入库。原因框不能用于录入原始答卷或人员明细，UI应提示。

## M08 当前已复核来源

```java
boolean readable = SurveySummaryImportsFormal.canReadReviewed(session, organizationCode);
Map<String,Object> source = SurveySummaryImportsFormal.captureReviewed(session, projectId, organizationCode);
SurveySummaryImportsFormal.validateReviewedSnapshot(frozenSource);
```

captureReviewed只读，允许M08在自己的事务中调用，不开启/提交事务。独立survey.read/VIEW不可由summary.read继承；项目组织必须与expectedOrg及可信受理链一致。撤权后M08须隐藏历史反馈投影、禁止缺权限刷新覆盖旧快照；canReadReviewed=false只用于能力投影，不能把读取失败伪装成无数据。

源结构严格如下（results含完整已确认policy快照，保证恢复时仍能验证排零规则）：

```text
format:"M07-REVIEWED-SOURCE-1",
status:"AVAILABLE"|"UNAVAILABLE", reason:""|下述原因,
value:null|{project_id,organization_code,results:[{
  import_id,series_id,revision,policy_version,policy_hash,policy,
  summary:{responseRowCount,overallQuestionKey:"q10",questions:[
    {key,label,column,validCount,blankCount,invalidCount,sumText,averageText}
  ]},
  review:{person_code,reviewed_at,review_event_id}, source_digest
}]},
source_version:sha256(不含source_version的规范化完整对象)
```

results只含每条series当前active已批准版本；没有待审/退回/被替换内容，没有答卷明细/文件hash。source_digest绑定受理来源、导入事件hash和复核事件hash。revision是该项目账本导入版本，不是正式项目/课程业务编码。每条单独显示，不能再次平均或相加分母。source_version基于有效来源与政策，不因新增待审版本而变化。

无已确认政策：UNAVAILABLE/POLICY_NOT_CONFIRMED；没有当前已复核：NO_REVIEWED_RESULT；政策变化：POLICY_CHANGED_REVIEW_REQUIRED；受理来源变化：SOURCE_CHANGED_REVIEW_REQUIRED。损坏409、身份401、权限403、基础服务故障固定503，不附原始异常、不冒充UNAVAILABLE。缺政策优先标记，即使尚无记录也不假称本项目已经确认没有评价。

SOURCE_CHANGED_REVIEW_REQUIRED 的页面提示应为“受理来源与保存时不一致，请先核实来源记录”。当前 M02 在受理后禁止修改原需求、审批或重复受理，提醒不增加 workflow_version；项目正常标题/状态变更不进入持久来源摘要。因而本轮不把异常来源变化当作允许重复文件的新依据，也不让用户通过改文件规避。将来 M02 若正式提供受理后更正，须另接有可信更正证据的来源核实修订流程。

M08旧feedback=M07_PREVIEW_ONLY快照原样保留，另加本FORMAT的严格schema分支；只新草稿/显式刷新捕获，普通保存与已提交/已审核历史保留精确版本。M08提交/复核/导出时重核当前source_version和权限，不能让已被替换或撤权的历史来源成为“当前”事实。validateReviewedSnapshot仅验证schema/摘要/计算自洽，不证明快照目前仍有效，不能替代live capture。

M08现refreshSources有“可读delivery即整体返回”的旧分支，接入时应分别处理delivery和feedback权限，避免借授课权限覆盖不应变更的反馈快照。

## 旧项目引用保护和原页面

同一旧项目删除/重绑/归档事务内加`SurveySummaryImportsFormal.guardLegacyProjectMutation(operation,projectId,proposed)`。delete有历史即拒绝；update不能改已有汇总的demand_id；所有受保护操作校验源与账本完整。归档不抹除历史，归档后prepare/confirm/review拒绝，read可按权查看；不自动增加项目完成或M05付款门槛。数据库FK是第二层保护，不能拿它替代业务提示。

原app.js`mountSurveyPreviewEntry`（约2815行）、效果评估原列表及项目详情入口保留，不重做导航/配色。原宿主2840/2871附近强制policyConfirmed/canCommit/historyAvailable=false、仅config/preview和3字段白名单，模块index.js也有草案硬锁。正式流程需**单独DTO和动作分支**，不要把这些锁整体放宽。旧草案继续原状；新正式prepare结果展示政策/汇总/重复提醒/替代目标/原因，用户确认才调confirm。记录列表按server state/current/capability展示，复核弹窗显示待审版本与现行版本差异，提交精确expected_version。所有成功回执重新read，不用旧回执覆盖新列表。

公共路由、Db初始化、M08来源投影和电脑浏览器实测由总控完成。候选没有交付另一套演示系统，也不声称正式页面已经接入。
