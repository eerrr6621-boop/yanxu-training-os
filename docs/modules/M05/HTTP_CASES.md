# 独立课酬事项 HTTP 合同（前端联调版）

**当前为暂存代码，正在联调与回归，尚未发布。** 本文对应 `DeliverySettlementCases.java`；下文描述宿主 `Api.ok` 的业务载荷，沿用现有登录、请求和响应解析方式。

## 入口与基础类型

基础路径：`/api/delivery-settlement/cases`。GET 仅支持以下三种查询，POST 不接受查询参数。

| 请求 | 返回 |
|---|---|
| `GET ?project_id=10` | `{project_id,organization_code,items:[CaseView...]}`；至多1000事项，无分页 |
| `GET ?case_code=<已有编号>` | 单个 `CaseView`；不存在404 |
| `GET ?project_id=10&view=options` | `{project_id,organization_code,dispatches:[...],financial_chains:[...],deliverables:[...],can_save:boolean}` |

`options.dispatches` 每项：`dispatch_id,teacher_id,teacher_name,subject,teach_date,status`。`financial_chains` 每项为当前余额视图，含 `chain_code,version,current_entry_code,current_service_date,current_amount,activity,teacher_id,paid_amount,balance`；它不等同于某次事项确认时的旧链头。

`options.deliverables` 列出本机构具有当前可信受理来源的既有开发事项，支持跨项目选择；每项为 `case_code,project_id,deliverable_code,deliverable_version,state`，仅在成果编号及版本已填写时返回。最多核验1000条开发事项，超限明确拒绝，不静默截断。新成果必须填写真实稳定的原台账编号和版本，不能随机生成编号规避一次性计发检查。

顶层 `options.can_save` 控制“新建事项”：必须具备当前机构的 `settlement.submit` HANDLE权限且项目未关闭/归档，不能根据旧角色名称推断。既有事项作废仍使用 `CaseView.capabilities.can_withdraw`。

所有 POST 都带以下控制字段：

```json
{"project_id":10,"case_code":"M05-CASE-<UUID>","expected_version":0,"request_id":"M05-REQUEST-<UUID>"}
```

- 首次保存 `expected_version=0`；之后使用最新 `CaseView.version`。每次成功操作（含部分付款）均递增版本。
- 浏览器自动生成并保留新事项 `case_code`；每次新操作生成 `request_id`。网络重试必须保留相同请求号和完整原请求；内容变化须使用新请求号。
- `case_code/request_id/chain_code/expected_entry_code` 是内部标识，不能要求用户手工编写；从响应选择或由浏览器生成。
- 技术编号格式为 `[A-Za-z0-9][A-Za-z0-9._:-]{0,95}`；它也适用于开发成果编号/版本，但不适用于已支持中文的旧账来源与原规则名称。
- ID/版本为安全整数；日期为 `YYYY-MM-DD`；课时/百分比为非负十进制**字符串**；金额为至多两位小数的十进制字符串。
- `Truth = YES | NO | UNKNOWN`；漏填的资格判断保存为 `UNKNOWN`。不要把未知默认成“是”或“否”。空值用 `null`，不要传空字符串。
- 未知字段400；会话无效401；当前机构权限不足403；版本/依据变化、不符合业务条件409。409 后重新读取并提示变化，不自动重新提交确认。

## 操作字段与状态

下面“附加字段”与公共控制字段组成完整请求，不得将完整 `CaseView` 回传。

| POST 子路径 | 附加字段 | 可用状态/效果 |
|---|---|---|
| `/save` | `kind,payload` | 新建或 `DRAFT/RETURNED/APPROVED`；保存为 `DRAFT`，清除旧提交/审核；既有事项不能改kind |
| `/submit` | `evidence_note,evidence_reference?` | `DRAFT/RETURNED → SUBMITTED` |
| `/review` | `decision:APPROVE\|RETURN,evidence_note,evidence_reference?,reason_note?,reason_reference?` | `SUBMITTED → APPROVED/RETURNED`；RETURN必须另填reason_note |
| `/confirm` | 无 | `APPROVED → CONFIRMED`；服务端复核核准时冻结的完整方案 |
| `/payment` | `chain_code,expected_entry_code,payment_date,amount,evidence_note,evidence_reference?` | 仅CONFIRMED；付款后仍CONFIRMED，可分次付款或退款 |
| `/withdraw` | `reason_note,reason_reference?` | 仅 `DRAFT/RETURNED → WITHDRAWN`；保留原申报与全部审计，不产生或删除账本 |

`kind = DEVELOPMENT | MIGRATION | ADJUSTMENT | WAIVER`。确认后的事项不能编辑；变更应新建 ADJUSTMENT。保存和提交允许资料尚未齐全，审核 APPROVE / confirm 必须通过服务端业务核验。

误建草稿可以作废：`reason_note`必须为实际原因，服务器登记不可变依据并保存真实操作人、账号和时间。SUBMITTED须先经review RETURN；APPROVED不可作废；CONFIRMED只能通过ADJUSTMENT取消。WITHDRAWN为终态，不能保存、提交、审核、确认、付款或再次作废，不再作为项目未办结事项阻断归档。所有原申报、提交、退回审核和凭证仍可读取。

权限按钮以 `capabilities.save/submit/review/confirm/payment` 为准（这些键**不带 can_ 前缀**），并结合余额及 `missing_items`。这些值反映当前授权、基本状态和项目是否允许写入，不能替代服务端核验。ADJUSTMENT 的 confirm 同时要求 `settlement.confirm` 与 `settlement.correct`；带 `historical_payment` 的 MIGRATION.confirm 同时要求 `settlement.confirm` 与 `settlement.pay`。其他资源依次为 `settlement.submit/review/pay`。项目关闭或归档后，全部写入能力返回false，服务端同时拒绝写入。

作废按钮单独使用 `capabilities.can_withdraw`（仅此新键带can_前缀），要求当前机构的 `settlement.submit` HANDLE权限，以及DRAFT/RETURNED状态；同样检查版本、请求重放和项目是否允许写入。

## 说明与凭证输入

`Proof` 为 `{"note":"实际核实说明","reference":"凭证号、文件位置或其他可查来源"}`；也接受仅说明的字符串。note非空、最长2000字；reference可省略/null、最长500字。单独填写reference而不填note会400。

- 新录入使用通俗 note/reference。服务器生成 `M05-EV-...` 引用并冻结真实登记人、账号、时间及业务内容。
- 响应中的 `evidence_code/reason_code/source_evidence/original_approval_evidence/evidence` 是已登记引用，不是用户表单输入。未知自编代码或换业务内容后复用旧引用会被拒绝。
- 重新编辑资料时由界面回填说明和凭证，再提交新 notes；不要让用户修改内部 `evidence_descriptor/evidence_kind/payload_digest`。
- `evidence_records` 是当前事项和关联账目引用的记录表：`{[code]:{note,reference,actor_code,account_id,created_at,...}}`。用 `evidence_records[引用编号]` 回显通俗内容，并按普通文本转义。

## DEVELOPMENT：开发课酬

`payload` 可保存下表中的已知字段；未列字段不接受。核准必需与条件字段由 `missing_items` 提示，前端不得自行写入费率或总池金额。

| 字段 | 类型、允许值及条件 |
|---|---|
| `activity` | `SOLO_DEVELOPMENT` 或 `JOINT_DEVELOPMENT` |
| `service_date,completed_on` | 实际业务日、成果完成日；核准必填，完成日不得早于业务日；不得为未来日 |
| `deliverable_code,deliverable_version` | 实际台账的稳定成果编号及版本；当前接受≤96字符的技术格式，不接受中文成果名称代替。同一成果版本全局防重，不得随机新造或替换 |
| `lead_teacher_id` | 已存在讲师ID，核准必填 |
| `grade` | `LECTURER/SENIOR/SPECIAL/DISTINGUISHED` 或null；使用本人等级计费时须核实 |
| `research_team,annual_plan,customer_paid,service_date_applicable` | Truth；须核实研发身份、计划或客户付费及逐案适用性 |
| `appointed_on` | 获聘日期；核准必填，不能晚于业务日 |
| `payable_hours` | 开发计酬课时字符串，核准必填 |
| `repetition_percent` | `"0"`至`"100"`或null，表示成果重复率百分比 |
| `previous_grant` | Truth，是否已经获发过对应酬金 |
| `development_path` | `CUSTOMER_REQUESTED/SELF_INITIATED` 或null；核准须明确 |
| `customer_written_payment_agreement,customer_acceptance` | Truth；客户委托路径须核实书面付费约定及验收 |
| `company_need,annual_review_passed` | Truth；自主开发路径须核实公司需要及年度评审 |
| `company_approval` | Truth，公司核准 |
| `customer_acceptance_on` | 客户委托且已验收时必填实际验收日，不早于完成日 |
| `annual_review_on` | 自主开发且通过评审时必填实际评审日，不早于完成日 |
| `company_approval_on` | 已获公司核准时必填实际核准日，不早于适用的验收/评审日 |
| `joint_reference_grade` | 仅合作开发使用；研发负责人逐案依据只能选择LECTURER或已核实的本人grade |
| `allocations` | 合作开发必填 `[{teacher_id,amount}]`；最多100人，无重复，必须包含主负责人 |
| `evidence_notes` | 下列键到Proof的映射；资料不足时可留缺，核准时必须齐备 |

`evidence_notes` 允许键：`grade,research_team,appointment,annual_plan,customer_paid,applicability,payable_hours,repetition,previous_grant,customer_written_payment_agreement,customer_acceptance,company_need,annual_review_passed,company_approval,allocation,joint_basis,completion,deliverable`。

- 所有开发核准均须成果版本、计酬课时、完成事实和获聘依据；已明确YES/NO的资格事实须有对应说明。
- 单独开发不得提交多人allocations或joint_reference_grade。合作开发须填allocation说明；研发负责人采用逐案计酬等级时还须joint_basis，使用本人等级还须grade说明。
- 合作开发各成员**明确金额之和必须等于服务端舍入后的总池**；界面不能自动均分、指定任意尾差承担人或替用户补分配事实。
- 客户委托/自主开发的资格、重复率与既往发放等具体可支付条件以服务端 `missing_items` 为准；不在浏览器重写计算规则。

## MIGRATION：旧账核对接入

核准必需 `payload`：`legacy_source_code,legacy_rule_code,service_date,teacher_id,amount,source_note,original_approval_note`。可另填 `source_reference,original_approval_reference,hours,historical_payment`。

- `legacy_source_code` 是真实旧系统/原单据的可定位标识或中文名称（≤300字），全局防重；`legacy_rule_code` 是原规则的真实名称或原编号（≤200字）。二者支持中文，不允许控制字符；不得随机编造来绕过重复登记。
- `amount` 是原规则下已核准的非负应付总额；服务端保留原规则依据，不套新费率。
- `hours` 为 `{ESTIMATED,PLANNED,ACTUAL,PAYABLE}`，每项十进制字符串或null；可省略，未知保持null，不倒推课时。
- `historical_payment` 可为null；填写时为 `{"payment_date":"2026-08-20","amount":"80.00","note":"原付款核对说明","reference":"原支付流水"}`。日期、金额必填；金额须大于0且不超过原应付总额，日期不得为未来日。只登记实际已付款，不能以计划日期代替。确认该类事项会登记实际现金事件，因此确认人还须具备当前机构的 `settlement.pay` 权限。

## ADJUSTMENT：已确认业务更正、冲销及跨日调整

共同 `payload`：`chain_code,expected_entry_code,reason_note,evidence_note`，可带 `reason_reference,evidence_reference`。选用 options 中的**当前**链头；`reason_note`说明为何变更，`evidence_note`说明本次核实内容。

| 条件分支 | 追加字段与要求 |
|---|---|
| `cancellation:true` | 将应付总额核准为0；保留原付款，形成需退款的余额。无需编造新金额或日期 |
| 非取消、原activity=TEACHING | `service_date,claim,hours`；claim使用教学申报字段及claim.evidence_notes；hours为`{estimated,planned,actual_minutes,payable}`，每项字符串或null；必须有可核实的实际分钟和明确计酬课时 |
| 非取消、原activity=LEGACY | `service_date,amount,legacy_rule_code,source_note,original_approval_note`；可带各reference、`legacy_hours`（大写四类课时）。原规则实际标识须保持一致 |
| 非取消、原activity=SOLO_DEVELOPMENT/JOINT_DEVELOPMENT | `service_date,development`；development为完整开发payload和evidence_notes，日期须一致；成果、版本、活动、主负责人及成员集合不能转移 |

其他允许字段：`cancellation`（默认false）、`expected_entries`。非取消更正的 `service_date` 必填。教学更正的 `claim` 包含 `grade,day_type,research_team,appointed_on,annual_plan,customer_paid,service_date_applicable,evidence_notes`；枚举、说明规则沿用教学工作流合同，不接受输入rate或amount。

合作开发的**整组更正或整组取消**须传 `expected_entries:{"实际chain_code":"实际current_entry_code",...}`：主链仍必须有 `expected_entry_code`，主链可不重复放进map；其余全部成员必须放进map，不能夹带无关链。用原开发事项 `confirmation.chains` 确定成员链集合，再从 options 刷新各链当前entry。单个成员改价/删除成员不能代替整组重核。

更正金额是新的业务总应付，服务器只追加差额；跨日期则成对追加原日冲回和新日重记。已付记录保持原样，确认后从当前余额办理补付或退款。

## WAIVER：核准该次授课不计课酬

`payload = {dispatch_id,reason_note,evidence_note,reason_reference?,evidence_reference?}`。从options的排课选项取dispatch_id；须属于本项目、实际授课已核验且尚无财务链。已有财务链应走ADJUSTMENT。

确认形成金额0、计酬课时0的明确依据，保留实际授课事实，不创建虚假付款。该事项仍为CONFIRMED；余额0时不要显示付款按钮。

## 付款、退款与显示返回

付款请求使用 `CaseView.financial[].chain` 当前 `chain_code/current_entry_code`。正amount付款、负amount退款；方向须与balance一致，非零且绝对值不能超过余额。可分次登记。普通实际付款日不得早于本次核准日，也不得为未来日；旧制历史款只从MIGRATION的historical_payment进入。

`CaseView` 含 `case_code,project_id,organization_code,version,kind,state,payload,submission,review,confirmation,withdrawal,last_event,missing_items,calculation_preview,financial,capabilities,audit,evidence_records`。

- `submission/review/confirmation` 可为null。审核对象含 `decision,reason_code,approved_case_version,approved_plan`；RETURN的approved_plan为null。
- `review.approved_plan` 与 `confirmation.plan` 包含 `totals,chain_codes,expected_entries,origin_key,historical_payment,basis`；均为服务器结果，只读。
- `confirmation.chains` 是确认时的 `List<head>` 快照，每项包含 `chain_code,version,current_entry_code,current_service_date,current_amount,activity,teacher_id,total`；之后发生更正时它不会跟着改变。`confirmation.plan.chain_codes` 是 `List<String>`，记录该次事项涉及的完整链集合。合作开发整组更正/取消须先从原开发事项读取这组链编码，再用options获取每条链的当前 `current_entry_code`，不能把确认时的旧head当成当前版本。
- 未CONFIRMED且未WITHDRAWN的 `calculation_preview` 为 `{confirmed:false,conditional:boolean,totals:[...],basis:{...},issues:[中文缺项...]}`；无法求值时可为null。CONFIRMED时为null，应读取冻结confirmation.plan。WITHDRAWN时为null且missing_items为空。预览不写账，也不授予确认权。
- 开发plan的 `basis` 可含 `unit_rate,raw_pool_amount,rounded_pool_amount`；合作开发还含逐案joint_reference_grade/joint_basis。`totals`逐成员列出冻结金额，不能当作可编辑请求模板。
- 合作开发可先保存基础资格并读取preview.basis.rounded_pool_amount，再录入明确成员金额；分配未齐时conditional为true，issues仍保留缺项。不要把条件预览显示成“已核准”。
- `financial` 每项为 `{chain,accrual_entries,payment_entries}`；`chain.balance` 为正表示待补付、负表示待退款、零表示已结清。事项state并不存在PAID。
- `accrual_entries` 含 `hours_before/hours_after`，分别保存更正前后课时事实；`hours`为该分录的变动课时。未知课时仍为null，不能在界面或汇总时当成0；应结合前后事实判断由未知到已知等变化。
- 作废后 `withdrawal` 包含真实 `actor_code,account_id,created_at,reason_code,evidence_code` 及内部核验字段。通过 `evidence_records[withdrawal.reason_code]` 显示原作废原因和凭证；不可把审计字段重新提交。
- `audit` 为 `{version,actor_code,account_id,created_at}[]`；`evidence_records[review.reason_code]`或其他引用可用于查看说明；服务器审计字段、内部descriptor不可在表单中让用户填写。

## 联调备注与当前getter边界

- 当前options提供项目已排课讲师、财务链及本机构跨项目的已有开发成果标识；开发/迁移所需的其他讲师沿用现有师资列表 `GET /api/teachers`。列表请求失败时关闭选择并处理401/403，不让用户手填数字ID；M05不新授予目录权限，服务端仍核实所选讲师存在。
- 成果标识应由真实成果台账选择/登记流程管理；当前无独立成果目录getter。可从项目事项列表的DEVELOPMENT payload复用已有实际编号；没有真实标识时保留草稿待补，切勿随机生成成果码以“避免重复”。
