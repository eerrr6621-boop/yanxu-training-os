# 原系统需求—审批—受理接入

本适配层为 `WorkflowIntegration.java`，保留 M01/M02/M03 核心。无生产初始化人员、默认岗位或授权；首次无组织配置时拒绝新业务办理。组织账号绑定通过 `OrganizationAccessStore` 每次解析当前真实会话。当前资料使用37家组织底稿，但本服务不把底稿推断为岗位或导入生产。

## 总控调用点

- `Db.init()` 在原表/迁移之后调用 `WorkflowIntegration.init()`（仅 SQLException）；创建9张流程表，可重复执行。
- `Api.route()` 验证真实会话后、旧 `requireWrite` 及通用 CRUD 之前调用 `handle(ex,s)`；返回 true 已响应。审批人员可以是原 viewer，但必须明确绑定并获业务权限。
- `guardLegacyMutation(mod,id,body)`：mod 为 demands/bids/projects，body=null表示删除；`bids/win`、`projects/start`用于对应旧专用动作。新 generic demands/projects 拒绝；新流程需求旧写/删拒绝；投标旁路拒绝；已受理项目可以沿原生命周期编辑，来源不可改、不可删。旧无流程记录不自动迁移。
- `visibleRows(mod,rows,s)`：需求/投标/项目，以及携 project_id 的子表按新流程机构范围过滤；q_sends/q_responses通过questionnaire_id追踪。原需求行附完整 workflow 和 actions。
- `requireProjectAccess(projectId,s,write)`：仅对新流程项目实施 demand.read/write；总控在原项目和子表专用动作中调用。统计须先过滤；过滤不是前端隐藏。
- public API由原Api处理内容长度、Content-Type、响应封装；handle复核请求令牌与session对象相同。写入在 Api.MUTATION_LOCK 内开启 Db.transaction，先读行锁，SQL版本CAS检查实际影响一行。

## 接口与返回

- GET `/api/workflow/context`：`{person:{person_code,organization_code},organizations:[{code,label,can_read,can_write,can_accept}],people:[{code,label,organization_code}],teams:[{code,label}],can_create}`。团队是获授权受理人所属机构；可处理的需求机构不是团队列表。当前label为真实配置编码，尚无姓名/机构别名字典。
- GET `/api/demands/workflow?id=N`、POST `/api/demands/draft|submit|accept|bid-result`：`{workflow:...}`。
- workflow：`id/version/data_revision/draft/business_path/organization_code/filler_code/form/legacy/approval/queue/status_label/project_id/bid_result/actions`；actions有 edit/submit/accept/bid_result 布尔值。
- draft body：可选id（编辑时必带expected_version），request_id，以及平铺M02字段、原unit/contact/phone/content/teacher_req/remark/training_province/training_city。旧 hours 可作为十进制字符串输入，与 duration_minutes 二选一；training_mode/period/expect_date兼容映射。审批中编辑另带 change_comment。
- submit body：id、expected_version、request_id。只初次提交；内部对接人须明确属于/负责记录机构；需求单位必填。直接路径填报人、负责人、BP不得重合。
- accept body：id、expected_version、request_id、team_code。核验本人所属团队、明确demand.accept、当前内容版本审批证据/已登记中标。一次受理仅生成一个项目，bid_id留空；项目待启动，申请人数/原单位等保留，预算不作为合同金额，项目amount=0待补。
- bid-result body：id、expected_version、request_id、result(won/lost)、external_approval_ref、result_date、result_note。仅已授权内部对接人办理；签报号与已保存需求一致，不声称已联网验证外部签报。登记结果不新建旧bids、不自动立项，原需求行办理；未中标留档。
- GET `/api/approvals/tasks`：`{tasks:[核心view＋workflow资料...]}`；GET `/tasks/{id}`：`{task:...}`；task沿M03 camelCase。
- POST `/api/approvals/tasks/{id}/actions`：action、expectedVersion、expectedStage、requestId、comment。服务器决定身份、策略、时间和dataRevision；不接受SUBMIT/REVISE（用需求资料入口）。返回 `{task,workflow}`。
- POST同路径`/remind`：expectedVersion、requestId。返回`{task,workflow}`；task.remind={enabled,reason}为真实24h限制探测。不会实际发邮件。
- 全部由Api.ok包装 `{code:0,msg,data}`。422字段/配置错误，403无权限，409旧版本/状态冲突，404不存在。审批Failure通过ApiException映射中文消息。

## 持久化与状态

workflow_demands保存原需求ID、行版本、内容版本、草稿位、身份/组织、表单/原字段；workflow_documents保存各内容版本快照。approval_workflows与approval_events保存完整策略和事件证据，加载时M03 State验证；工作流ID等于需求ID。workflow_bid_results、workflow_acceptances各以需求ID唯一。workflow_requests按actor_code/request_id唯一，同时保存操作和规范化请求，用相同内容重试返回已存在当前结果；不同内容复用请求号409。approval_reminders按流程行锁串行追加。workflow_outbox只保存PENDING计划，不宣称外发成功。

原 demands.status 仅在真实受理后置已立项，未中标置已流标；审批状态单独存储。草稿修改增长内容版本；待审修改调用REVISE从负责人重审；退回/撤回修改后经RESUBMIT，完成审批/已受理不能修改原审批资料。

原分钟字符串永久保留，课时输入使用BigDecimal乘45。按用户M05已确认口径，旧hours投影为分钟÷45、两位HALF_UP（60→1.33）；不拿预算当计费事实。原数据列长度限制在保存前明确校验（名称200、方式/时段16等），不让数据库静默截断。原单据字段与新字段分别保存，不只保存M02.values而丢失原资料。

## 验证

执行 `bash scripts/IntegrationWorkflow-check.sh`，仅新建系统临时目录、合成用户和独立H2数据库；不调用Db.init/原seed，不读生产数据，不启动正式服务。覆盖真实Auth登录、Json数值解析、草稿/提交/负责人/BP/受理、预算与合同额分离、人数保留、投标独立登记、原字段保存、重复/旧版本/并发、范围过滤、旧入口门禁、重审/退回/催办限制和重开连接持久化。

实际断言数量与执行结果以脚本输出为准；此文档不替代原页面桌面/手机浏览器验收。

## 已执行的增量兼容检查

省市仍调用原 `DispatchPreference.validateRegion` 做“浙江省/杭州市→浙江/杭州”归一；部分草稿更新先合并已存原字段，不会省略即清空。原推荐只支持其既有方式/时段枚举：新表单的“混合”“晚间”“待协调”及自定义文字完整保存在form中，投影到旧demands.training_mode/period时用“待定”，不猜测为已核实的线下、线上或某个时段。明确的旧枚举值保持不变。

context.people包含同机构及明确负责当前可读机构的候选人员，responsible_organization_codes仅返回可读交集；不在可读范围的所属机构编码留空。新项目行workflow_source包含来源需求、路径、机构、承接团队和can_delete=false，供原页面隐藏不适用操作。

追加 `scripts/IntegrationWorkflowHttp.cjs --java <java> --classes <隔离编译输出>`：随机回环端口、独立临时H2、合成认证账号，验证真实Api及原列表/汇总/项目子表门禁，推荐demand_id与异步任务，以及私人评价/履约统计、邻近地点和权限变更后的缓存拒绝。首次完整边界运行49项通过；后续结果以输出为准。不会发真实通知。

最终定向结果（2026-09-22）：`IntegrationWorkflow-check.sh` 53项通过；真实接口 `IntegrationWorkflowHttp.cjs` 51项通过，包括隐藏邻近排课仍保留departure_uncertain=true且local_priority=false、但不返回地点和排课明细。`test_requirement_input_resolution_http.cjs`迁移版55项通过。原`test_requirement_input_http.cjs`保留全部原断言，为53/55；两条失败均为其旧“自由补充要求一律人工复核”预期，与迁移版开头明确标记已关闭并更新的两条相同，本轮未重写预期。两脚本只更新合成M01配置和新草稿准备步骤。

审批首次提交额外核实负责人/BP已绑定启用的账号，避免流程交给不能登录的人员。现有审批在岗状态也重新读取人员、机构、账号绑定和users状态，不缓存岗位或权限。
