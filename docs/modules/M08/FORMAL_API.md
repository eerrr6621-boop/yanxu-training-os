# M08 正式总结候选接口

2026-09-23。本任务只写此 work 候选目录；总控负责共享整合、原电脑界面与生产发布。四个候选产品类：TrainingSummariesIntegration、TrainingSummariesWorkflow、TrainingSummariesFeedbackSource、TrainingSummariesWord。旧纯核心与M05不改。

## 宿主接点

- TrainingSummariesIntegration.init() 继续在原启动位置调用，内部新增3张空流程表；不迁移、猜测或改写原正文历史，不运行Db.init种子。
- 原正常认证后handle分流保留。API仅增加workflow与workflow-history，并接通submit/review/export。
- Api.file需由总控把原XLSX白名单扩成“XLSX MIME对应.xlsx、DOCX MIME对应.docx”，不能把任意MIME和后缀交叉放行。DOCX MIME为application/vnd.openxmlformats-officedocument.wordprocessingml.document。仍仅暂存字节，在业务锁外发送，不直接sendResponseHeaders。
- M07由启动宿主显式连接一次：TrainingSummariesFeedbackSource.connect(new ReviewedProvider { capture调用SurveySummaryImportsFormal.captureReviewed(session,project,org); validate调用SurveySummaryImportsFormal.validateReviewedSnapshot(snapshot); })。未连接时旧草稿保持M07_PREVIEW_ONLY兼容，正式提交/通过/导出拒绝；不编造正式问卷结果。该接口没有客户端安装或切换入口。
- 服务端显式配置training.summary.review.order=BRANCH_THEN_BP或UNORDERED，并配置training.summary.review.policyVersion。未配置允许草稿但不允许提交。当前用户选择尚待答复，不将合成测试里的策略写进真实配置。

## HTTP

GET /api/training-summaries/workflow?project_id=101

GET /api/training-summaries/workflow-history?project_id=101&offset=0&limit=20

POST /api/training-summaries/submit

    {project_id,expected_version,expected_workflow_version,request_id}

POST /api/training-summaries/review

    {project_id,expected_version,expected_workflow_version,request_id,
     review_role:"BRANCH"|"BP",decision:"APPROVE"|"RETURN",note:"意见"}

POST /api/training-summaries/export

    {project_id,expected_version,expected_workflow_version,request_id}

submit/review为正常Api JSON信封，data为workflow DTO，另有replayed与result_workflow_version。export为.docx附件，文件名training-summary-<项目记录编号>-v<正文版本>.docx。重复原request_id重建同一字节且不新增导出记录；文件生成有记录不代表网络送达成功。响应后GET workflow刷新进度。

正文version/revision沿用原含义；workflow_version是独立流程事件版本，UI不得相互覆盖。任何操作都要传当前两种版本，409时保留用户编辑/意见并重新查询，不能拿旧操作自动重提。读接口不保存或更新业务。

## workflow DTO

    {project_id,revision,workflow_version,status,
     submitted_revision,submitter_code,branch_reviewer_code,bp_reviewer_code,
     review_order,policy_version,policy_configured,
     decisions:[{review_role,actor_code,reviewed_at,workflow_version}],
     capabilities:{edit,refresh_sources,submit,review,review_role,export},
     photo_policy:"TEXT_PLACEHOLDERS_ONLY",synthetic:false}

status为DRAFT/SUBMITTED/RETURNED/APPROVED。原GET总结增加workflow，同步顶层capabilities、draft_only:false；旧revision/history/comparison是正文快照，正式复核状态从workflow/workflow-history读取。历史事件只返回白名单，无原始摘要、来源hash或客户端自带岗位证据。

## 业务规则与授权

提交人必须是当前草稿最后填写人，复用M01该人员明确的leaderPersonCode和bpPersonCode；两位各有唯一有效账号且有本项目机构范围内对应summary.review.branch/HANDLE或summary.review.bp/HANDLE。不猜姓名、不按名册顺序或旧role路由。两人不同且不等于填报人；同一账号重新绑定到其他人员后也不能自审或包办两次复核。顺序由显式策略选择，提交冻结策略及办理人员；后续关系改动只影响新提交，当前办理仍重新验证有效身份和对应岗位权限。

summary.read/VIEW为读取前提，summary.edit/HANDLE用于填写与提交。正式导出还要当前管理员账号及summary.export/EXPORT，旧admin/manager角色不能单独放行。提交、通过复核和导出还需独立delivery.read/VIEW、survey.read/VIEW，确保办理人能查看所确认的数据；退回不要求其刷新来源。M01配置生成须明确给相应业务岗位这些来源查看权限，不由M08增权。

SUBMITTED期间禁止save和refresh。任一方退回必须有意见；保存新版本或显式刷新后重新提交，两方复核重新开始。APPROVED后再保存形成新草稿，旧审核记录仍保留，但新正文不能沿用旧批准导出。归档仅可读和导出已批准版本；尚在复核中时拒绝归档，避免流程被封住。归档/旧更新保护同时校验流程历史。

提交/通过/导出确认冻结的项目数据、授课显示事实、已复核问卷结果与当前可信来源仍一致；M05只忽略每日采集日期和摘要自身，不因跨日但事实未变强制重新复核。项目归档状态变化不改历史事实。M07稳定版本变化要求显式刷新后重新提交；不重算、合并多批次均分或分母。

普通save保留全部冻结来源；refresh分别按delivery.read与survey.read刷新可见源，不把脱敏结果覆盖已存不可见数据。当前无survey.read时，历史、比较两侧及重放均隐藏feedback value和总体source_version；不泄露不可见问卷改动摘要。旧M07_PREVIEW_ONLY历史原样保留。来源单版限256000字符、总体正文历史16Mi字符；不删除旧记录。

Word复用宣传正文与文字图位，附固定项目资料、各类课时、逐份已复核问卷汇总及两方复核记录。仅使用机构/人员编码，项目未分配正式编码时如实标项目记录编号，不伪造正式业务编码。不嵌入真实照片、外链、宏、实名映射或个人答卷。

## 验证状态

产品候选已编译。新核心17组/195项通过，使用明确标注的已复核provider合成替身隔离M08流程；另用M07实际四个正式候选做联合测试，31项通过，覆盖正式源、待审修订不替换当前源、已审替换触发M08重审、Word精确均分、撤权隐藏和历史保全。不能将第一套替身检查单独当成M07验收。

旧持久化298、授课来源279、comparison277通过；只在临时复制的旧持久化测试中将2处退役的“任何正式动作409”断言改成旧缺workflow_version请求的400，旧共享测试不改。脚本默认在本候选目录运行；落到app/scripts后自动从app/src/com/training取四产品源，也可用M08_FORMAL_SOURCE_ROOT显式指定。联合脚本M08_M07_CANDIDATE指向实际M07四候选目录。

Word采用纯OOXML生成、明确图位、无宏无外链；三页合成样稿已由技能render_docx.py和捆绑LibreOffice渲染逐页检查。中文字体读取本机既有字体，只给本次渲染设置工作目录fonts.conf与小型缓存，不复制字体、不改系统字体配置。未验收原生Microsoft Word及原电脑界面/真实主服务HTTP；这些由总控整合验证。
