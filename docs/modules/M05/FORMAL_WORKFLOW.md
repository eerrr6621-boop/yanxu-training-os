# M05 正式课酬链候选

本候选只在独立临时数据库验收，未修改真实业务库。共享宿主、桌面界面和发布由总控集成。

## 已采用的业务口径

- 45 分钟／课时，允许折算；60 分钟为 1.33 课时。实际课时与计酬课时分别保存。
- 用户批准的管理办法费率由服务器选择。普通／高级／特级／特聘讲师的工作日课酬分别为 100／150／200／200 元，休息日为 200／300／400／400 元，法定节假日为 300／450／600／600 元。研发成员授课按普通讲师标准。
- 人民币每笔保留两位，HALF_UP 四舍五入；汇总相加已舍入金额。
- 每笔核准是否适用制度，不虚构统一生效日。采用日不等于制度生效日。
- 主办申报、共享交付核准、确认与实际支付核对各留存真实账号、人员、时间和依据。客户端不能直接提交单价或 `APPROVED` 标记完成结算。
- 付款与退款是对实际业务的登记，不执行转账或计算个人所得税；登记金额为与应付口径一致的税前金额。

## 授课办理接口

`GET /api/delivery-settlement/workflow?dispatch_id=N`

`POST /api/delivery-settlement/workflow/{save|submit|review|confirm|correct|payment|settings}`

共同字段：`dispatch_id`、`expected_version`（申报版本，首次为 0）、`expected_fact_version`（授课版本）、`request_id`（请求幂等号）。小数必须使用字符串。

| 操作 | 其他字段 | 行为 |
| --- | --- | --- |
| save | claim | 保存草稿；修改后重新申报核准 |
| submit | evidence_note、可选 evidence_reference | 记录主办申报 |
| review | decision=APPROVE 或 RETURN，evidence_note、可选 evidence_reference、reason_note | 核准资格和逐笔适用；退回原因未另填时使用审核说明 |
| confirm | expected_settings_version | 服务器选择费率，冻结应付 |
| correct | expected_settings_version、expected_snapshot_code、reason_note、可选 reason_reference | 同日未付且有新核对授课版本的差额更正 |
| payment | expected_snapshot_code、payment_date、amount、evidence_note、可选 evidence_reference | 核对当前完整未付余额 |
| settings | expected_settings_version、settings | 发布不可覆盖的执行设置版本 |

`claim` 字段：`grade`（LECTURER/SENIOR/SPECIAL/DISTINGUISHED 或 null）、`day_type`（WORKDAY/REST_DAY/STATUTORY_HOLIDAY 或 null）、`research_team`、`appointed_on`、`annual_plan`、`customer_paid`、`service_date_applicable`、`evidence_notes`。判断值为 YES/NO/UNKNOWN。`evidence_notes` 允许 grade/day_type/research_team/appointment/annual_plan/customer_paid/applicability，每项为 `{note, reference}`；note 是通俗事实说明（必填，最多 2000 字符），reference 是凭证号或来源（可空，最多 500 字符）。服务端连同真实账号、人员、时间、事项及字段值保存为不可变依据，再生成内部 evidence 编号。随意传入未登记编号不能代替真实依据。返回同时提供对应说明供查看和编辑。

执行设置的默认版本为 `M05-MONEY-USER-20260923`，由已收到的用户决定提供，无需向数据库写入模拟业务。`settings` 写入字段：version、evidence_note、可选 evidence_reference、amount_scale=2、rounding_mode=HALF_UP、allow_unpaid_correction。没有全局适用日期字段。服务器生成依据编号，界面不要求用户编写编号。

状态：DRAFT → SUBMITTED → APPROVED 或 RETURNED → CONFIRMED → PAID。已付、跨日、开发及旧制更正通过独立事项办理。草稿可不完整；缺少具体资格事实或适用依据仅阻该笔核准，不阻保存。

返回包括 `version/state/fact_version/claim/submission/review/settings_version/settings/calculation_preview/financial/capabilities`。核准前的计算只供检查，不产生应付账。按钮必须依当前服务器能力与原因显示，服务端在变更事务内再验一次。

## 开发、迁移和调整事项

`GET /api/delivery-settlement/cases?case_code=...`；项目列表使用 `project_id` 替代 case_code。

`POST /api/delivery-settlement/cases/{save|submit|review|confirm|payment|withdraw}`。共同字段为 project_id、case_code、expected_version、request_id。保存另带 kind 和 payload。申报、核准字段同上；事项退回须填 reason_note，确认不接受临时覆盖金额；实际付款或退款另带 chain_code、expected_entry_code、payment_date、amount、evidence_note、可选 evidence_reference。字段以同目录 `HTTP_CASES.md` 为准。

- 开发成果按成果及版本防止重复支付，客户开发和自主开发分别核对制度规定的验收条件。合作开发的核准分配金额合计必须等于已舍入总池，不擅自均分或选择尾差归属。
- 旧制迁移冻结已核对的原规则、原金额与来源，不从原雏形 fees 表推断真实历史。历史付款只有明确的金额、日期和凭据经核对后登记。
- 付款后更正保留原付款。应付增加形成待付余额，减少形成待退余额；没有真实退款记录时不得显示已结清。
- 同日调整追加差额；跨日调整在原日完整冲回并在新日重新记账，两笔同组，事务一起成功。原授课记录与财务记录均不被覆盖。
- 免付必须有明确核准结论；缺少应付单不等于零。
- 误建事项仅在 DRAFT/RETURNED 状态下允许作废，须填写真实 reason_note，可附 reason_reference，且具备本机构 settlement.submit 权限。WITHDRAWN 为终态，保留全部内容、凭据和历史，不生成或删除账目，不阻碍归档。已提交的先退回，已确认的通过调整事项处理。

各类 payload 以 `DeliverySettlementCases` 的严格字段清单和对应测试为准。未知成果证据只阻具体事项核准，不取消该功能入口。

## 权限与宿主挂接

所有授课和事项读取须有当前机构范围的 delivery.read VIEW。保存／申报为 settlement.submit，审核为 settlement.review，确认为 settlement.confirm，更正为 settlement.correct，支付核对为 settlement.pay，设置为 settlement.configure，动作均为 HANDLE。旧 admin/manager 标记不能代替 M01 授权。

`DeliverySettlementIntegration.init()` 创建本模块空表；`handle()` 转交新 Workflow 和 Cases 路由。旧 HTTP configure/confirm/correct 保持拒绝，不能解除后绕过核准。原纯计算与历史读取接口保留兼容。

`projectSettlement(session, projectId)` 为桌面项目页提供已核准／已付／余额的已知部分、缺项、完整总额和归档原因。完整总额在仍缺申报时为 null。`guardLegacyArchive(projectId)` 核查每笔授课已完成且已核对、已有应付或免付结论、对应最新事实版本、开发／迁移／调整事项均办结、付款退款余额为零。

## M06 冻结财务来源

`DeliverySettlementIntegration.financialSource(session, projectId, export)` 返回 `M05-FINANCIAL-1`。项目发现使用 `financialProjectIds(session, organizationCode)`，取当前可信受理项目与冻结财务账持久机构项目的并集，避免受理来源删除后漏账。方法在同一业务锁内读取，不再开启数据库事务，可由 M06 外层只读事务调用。方法复核 reports.read VIEW；导出另验 reports.export EXPORT，不依赖课酬办理权限。

顶层：schema_version、project_id、organization_code、source_version、accrual_entries、payment_entries、chain_heads。

应付分录：entry_code、chain_code、previous_entry_code、kind（CONFIRMED/ADJUSTMENT/REVERSAL/REBOOK/LEGACY_OPENING）、activity、source_record_id、source_code、service_date、teacher_id、teacher_code、system_teacher_code、course_id、course_code、amount、hours、currency、data_mode、policy_version、rate_version、evidence_code、confirmed_at、correction_group_code。hours 使用 ESTIMATED/PLANNED/ACTUAL/PAYABLE，值为带符号十进制字符串或 null。每条还冻结 hours_before / hours_after 的完整四类状态，非负十进制或 null；同日补齐未知课时不把原未知当零差额。原单 before 全零；冲回 after 全零；重记 before 全零。M06 课时按每个 chain_code 与 service_date 的最后 hours_after 重建，金额仍相加 signed amount。

支付分录：entry_code、chain_code、accrual_entry_codes、kind（PAYMENT/REFUND）、payment_date、amount（付款正、退款负）、currency、evidence_code、recorded_at、actor_code、account_id、historical。支付口径不重新累加整笔课时；身份从所引用的当时冻结应付分录取得。

链头：chain_code、current_entry_code、current_service_date、current_amount、paid_amount、balance、activity、teacher_id。链头用于净额核对，不再作为另一笔金额相加。所有金额为 CNY 两位字符串。

正式讲师／课程编码未知时保持 null，系统 TEACHER-id 不冒充正式编码。独立成果与迁移没有排课编号时 source_record_id 为 null。项目必须具有可信受理组织来源；旧项目先完成该来源核对，不能从 unit 或姓名推测归属。

## 验收与发布

`M05FormalWorkflow-check.sh` 使用实际共享源码并允许以 `M05_FORMAL_SOURCE_DIR` 指定本候选，所有数据库均为新建空的临时 H2 目录。`M05_FORMAL_REGRESSION=1` 加跑已有授课集成、冻结恢复和修订历史回归。只删除该次运行自己建立的临时产物。

产品文件冻结、全部对应测试结果与 SHA 通知总控后，才能纳入单独集成轮。本文件不是已发布声明。
