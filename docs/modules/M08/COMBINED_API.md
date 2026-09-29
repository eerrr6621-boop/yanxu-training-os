# M08 明确兼任复核接口增量

2026-09-24 冻结。实现和验证于 2026-09-23 完成，按总控授权在已冻结照片版之上修复明确负责人/BP兼任。最终 DTO/按钮合同已按代码核对，交付文件以本目录 MANIFEST.sha256 为准。照片 12 文件原清单不改；本轮不增加 internal_contact_code 限制，不修改普通编辑人范围或公共宿主。

## 新模式和动作

新提交在服务端发现负责人=BP，并且 M01 对本机构/本人已有两个岗位分别覆盖机构的明确兼任依据、有效唯一账号及两项 summary.review 授权时，选择 SINGLE_EXPLICIT。不能凭相同人员 ID、管理员身份或 M03 approval.review 权限代替该核验。两项 summary.review 资源各自以 M01 正式权限引擎结果为准，包含跨岗位显式拒绝；不额外要求允许规则必须挂在某个固定岗位名下。岗位本人及其机构职责仍由明确兼任证据独立核验。

明确兼任策略版本 M08-COMBINED-20260923-v1，review_order=COMBINED。本轮已获总控明确实现授权，不依赖普通双人先后顺序的待答复问题；普通模式依旧使用原显式配置，不新增默认顺序。存量待办不自动转换模式。

原 GET workflow / 总结 workflow 增加：

    review_mode: null | "DUAL_REVIEW" | "SINGLE_EXPLICIT"
    review_blocked_reason: null | "兼任职责、人员关系或权限已变化，请核对配置"

既有 capabilities.review_role 扩展为 COMBINED。界面仅按服务端 capabilities 显示可办动作，不能根据 branch_reviewer_code==bp_reviewer_code 自行推断。兼任待审文案“待负责人及BP复核（兼任）”。

POST /api/training-summaries/review 保留路径和版本字段，新增明确的组合：

    {project_id,expected_version,expected_workflow_version,request_id,
     review_role:"COMBINED",decision:"APPROVE_COMBINED",note:"意见"}

按钮文字 **同时完成两岗位复核**。这是一次明确动作，不能连发两次 review，也不能用普通 APPROVE 代替。退回使用 review_role=COMBINED、decision=RETURN，意见必填。普通 BRANCH/BP 的 APPROVE/RETURN 组合不变；混用模式或动作拒绝。

## 展示和审计

兼任批准只有一条 APPROVE_COMBINED 事件、一个真实 actor_code/account_id、一个时间、一次 workflow_version 递增。decisions 返回一项：

    {review_role:"COMBINED",responsibilities:["BRANCH","BP"],
     actor_code,reviewed_at,workflow_version}

普通 decisions 仍分别列 BRANCH 和 BP，每项 responsibilities 为对应单岗位数组。复核和退回历史事件也返回 responsibilities；兼任退回表示一次针对两职责的退回，不代表两项已通过。

进度可分别显示两个岗位已完成，但必须明确同一兼任事件。历史和 Word 展示“负责人及BP（兼任）：同一人员同时完成两岗位复核”，不能复制成两个不同办理人或“第二人自动通过”。普通 Word 输出保持逐字节兼容。

review_mode、decisions、submitted_revision 描述最近一次记录的提交。已批准后另存新正文/照片时，status 为 DRAFT，revision 为新版本，旧 decisions 仍用于历史展示。界面须用 submitted_revision 与 revision 区分旧批准，不将旧记录显示为最新版已通过；办理按钮始终取当前 capabilities。首次尚无提交流程时 review_mode 为 null。

## 冻结与当前核验

新 SUBMIT 冻结机构、人员、两个岗位代码、依据引用、M01 配置版本和唯一办理账号。待审办理及重试核验当前明确依据、人员与账号、两项职责机构范围、两项 summary.review 权限和填报人的负责人/BP关系；相关事实变化暂停办理，不静默换人。无关配置版本变化不自动改写旧依据，也不单凭版本不同否定相关事实仍一致的待办。

已完成复核的历史按冻结事实回放，不因今天更换人员改写旧批准。历史查看/Word 导出继续验证当前查看或导出账号及来源权限。自审和账号重绑后自审仍拒绝；普通模式不放开同一账号完成两次复核。退回、改正文/照片后需新修订重新提交，旧兼任批准不能复用。
