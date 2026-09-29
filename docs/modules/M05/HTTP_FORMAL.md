# M05 桌面正式课酬 HTTP 合同

授课部分已冻结为界面实现合同。所有金额和课时用十进制字符串；所有日期用 YYYY-MM-DD；未知数据为 null，不转成 0。沿用应用现有 Api.ok / ApiException 响应包装与会话方式，不新增导航或移动版。

## 1. 读取授课申请

GET `/api/delivery-settlement/workflow?dispatch_id=101`，只接受一个 dispatch_id 参数。

业务返回：

```text
dispatch_id, project_id, teacher_id       安全整数
organization_code                       字符串
service_date                            日期或 null
fact_version                            当前授课版本，未保存为 0
version                                 申请版本，未创建为 0
state                                   DRAFT | SUBMITTED | RETURNED | APPROVED | CONFIRMED | PAID
claim                                   下述申请字段，未创建为 null
claim_fact_version                      申请对应授课版本或 null
context                                 已冻结的原授课上下文或 null（不允许客户端提交）
submission, review                      审计对象或 null
evidence_notes                          按 grade/day_type/... 键返回的 note/reference 对象
settings_version                        当前金额规则版本
settings                                只读金额设置
calculation_preview                     条件齐全时的核准前试算，否则 null
financial                               始终为对象，未确认时其中金额/链/支付为 null
capabilities                            当前服务器能力，见第 5 节
```

`claim` 精确业务字段：

| 字段 | 可选值 |
| --- | --- |
| grade | LECTURER / SENIOR / SPECIAL / DISTINGUISHED / null |
| day_type | WORKDAY / REST_DAY / STATUTORY_HOLIDAY / null |
| research_team | YES / NO / UNKNOWN |
| appointed_on | 日期或 null |
| annual_plan | YES / NO / UNKNOWN |
| customer_paid | YES / NO / UNKNOWN |
| service_date_applicable | YES / NO / UNKNOWN |
| evidence | 服务器产生的依据编号映射，只读 |

界面标签分别为“讲师等级、授课日别、是否研发团队成员、获聘日期、是否年度培训计划内、是否客户付费项目、本次是否适用该办法”。未知值保持“待核实”，不得默认已核实或默认工作日。

## 2. 保存与每个动作

POST `/api/delivery-settlement/workflow/<操作>`，不接受 URL 查询参数。每次提交共同字段：

```json
{"dispatch_id":101,"expected_version":0,"expected_fact_version":2,"request_id":"浏览器生成的唯一请求号"}
```

`expected_version` 取最新 workflow.version；`expected_fact_version` 取最新 workflow.fact_version。request_id 使用 UUID 等 ASCII 技术字符串，程序生成且最多 96 字符。

| 操作 | 额外输入 |
| --- | --- |
| save | claim（以上业务值，另带 evidence_notes，见下一节） |
| submit | evidence_note（申报说明），evidence_reference 可选 |
| review | decision=APPROVE 或 RETURN；evidence_note、evidence_reference 可选；退回可带 reason_note，省略时以审核说明为退回原因 |
| confirm | expected_settings_version=当前读取值 |
| correct | expected_settings_version、expected_snapshot_code=financial.head_snapshot_code、reason_note，reason_reference 可选 |
| payment | expected_snapshot_code、payment_date、amount（正数、两位小数的税前实际支付额）、evidence_note、evidence_reference 可选 |

review 的 evidence_note 必填；表中“可选”仅修饰 evidence_reference。confirm 不允许传金额、单价、讲师身份、是否批准等权威字段。金额来自服务端费率与已核准事实。

`settings` 是管理操作：共同字段 + expected_settings_version + settings:{version,amount_scale:2,rounding_mode:"HALF_UP",allow_unpaid_correction:true,evidence_note,evidence_reference?}。首期一般界面不需要展示；已有用户批准的默认规则 `M05-MONEY-USER-20260923`。没有统一生效日字段。

保存/提交不要求所有资格字段齐全；核准与确认才要求该笔满足条件。授课事实改动后，必须重新保存、提交和核准。保存已核准而未付款的申请会清除旧核准。

## 3. 通俗依据与审计编号

save.claim.evidence_notes 结构：

```json
{
  "grade":{"note":"已核对本次授课时讲师为普通讲师","reference":"聘任登记编号，可留空"},
  "day_type":{"note":"本次授课经核对为工作日","reference":"本次课程安排"},
  "research_team":{"note":"已核对不属于研发团队成员","reference":null},
  "appointment":{"note":"已核对聘任时间早于本次授课","reference":null},
  "annual_plan":{"note":"本次项目已纳入年度培训计划","reference":null},
  "customer_paid":{"note":"本次不属于客户付费项目","reference":null},
  "applicability":{"note":"已逐笔核对，本次适用用户批准的管理办法","reference":null}
}
```

以上仅为字段示例，不能作为所有真实业务的默认说明。界面让用户填写/核实内容，未核实项留空。note 非空且最多 2000 字符；reference 可空且最多 500 字符。也允许某项用字符串作为 note 的简写。服务端在事务内记录原说明、凭证号、真实人员、账号、时间、具体业务和字段值，生成不可变依据编号。未登记、跨业务或与字段值不符的编号不能冒充依据。浏览器只需提交通俗说明，不需要生成制度代码。

返回 evidence_notes 按原业务键提供 note/reference，供编辑时复制。submission/review 审计对象包含 actor_code、account_id、created_at、evidence_code，并提供 evidence_note/evidence_reference 供显示；review 另含 decision、reason_code。financial.payment 同样提供对应通俗凭据说明。所有文本按纯文本转义显示。

## 4. 试算与已确认金额

calculation_preview 为 null，或：

```json
{"unit_rate":"100","payable_hours":"1.33","unrounded_amount":"133.00","amount":"133.00","currency":"CNY","confirmed":false,"fact_version":2,"claim_version":1}
```

它只解释将如何计算，不代表已经形成应付。不得把试算金额显示为“已确认/已付款”。

financial 对象：

```text
head_snapshot_code       原授课快照号或 null，用于普通未付更正/付款
current_entry_code       统一财务账当前分录号或 null
amount                   当前核准总应付，两位字符串或 null
paid                     是否曾有净实际支付，布尔；不是“全部结清”标志
balance                  当前应付减实际支付，两位字符串或 null；负数为待退款
chain                    链对象或 null
payment                  最近实际付款/退款记录或 null
```

chain 包含 chain_code、version、current_entry_code、current_service_date、current_amount、paid_amount、balance、activity、teacher_id。payment 包含 entry_code、chain_code、accrual_entry_codes、kind=PAYMENT/REFUND、payment_date、amount（付款正、退款负）、currency、evidence_code、recorded_at、actor_code、account_id、historical 及通俗凭据说明。

已付、跨日、同一授课版本只改费率资格、以及已存在独立调整分录的更正，转到“调整事项”。授课 payment 只办理整笔当前未付余额；分次支付和退款从调整事项办理。按钮文案用“登记实际付款/退款”，不会执行转账或自动扣税。

## 5. 按钮能力

capabilities 精确结构：

```text
can_save / can_submit / can_review / can_approve / can_confirm
can_correct / can_payment / can_settings                全部 boolean
permissions.{save,submit,review,confirm,correct,payment,settings}    全部 boolean
reasons.{save,submit,review,confirm,correct,payment,settings}        字符串数组
eligibility_issues                                     字符串数组
fact_binding_current                                   boolean
```

“审核”入口可在 can_review=true 时打开；“通过核准”使用 can_approve；“退回补充”使用 can_review。核准不能点时显示 eligibility_issues 和审核原因。不要用前端角色名自行推断业务权限，也不要把“可以退回”等同于“可以通过”。每次读和变更响应都含当前权限；后端在事务里重验。

## 6. 幂等、冲突与未知结果

成功响应返回该次操作的业务结果。相同账号的相同 request_id 与相同完整内容重复请求返回原业务结果，不产生第二笔；响应 capabilities 按当前服务器刷新。相同 request_id 改内容返回 409。

网络中断或响应未知：保留原请求号与完整内容，先用原请求重试，不改请求号再次付款。随后重新 GET 读取当前申请；幂等响应可能是旧版本的成功结果，不能覆盖更新的页面状态。刷新后若事实/申请/设置版本有变化，以新读取值生成新的操作请求，重新核实内容。

401 要求重新登录；403 表示当前业务权限或范围不足；400 是输入不符合合同；409 显示服务器原因并刷新，不自动覆盖版本或绕开核准。任何余额、证据或历史无法核实时，不显示“0 元/已结清”。

## 7. 项目课酬卡

总控从 `DeliverySettlementIntegration.projectSettlement(session, projectId)` 挂接到原项目响应。字段：payment_integration="CONFIGURED"、missing_claim_count、pending_cases、known_confirmed_amount、known_paid_amount、known_balance、status=AVAILABLE/INCOMPLETE、confirmed_amount/paid_amount/balance（不完整时 null）、can_archive、archive_reason。

known_* 是已记录部分，须标“已核准部分”；不能用它掩盖尚未核准事项。原三个金额 HTTP 护栏保持，界面只调用新 workflow/cases。

## 8. Cases 分支

开发、旧制、调整和免付的精确 payload、可信选择列表、付款与作废入口见同目录 `HTTP_CASES.md`。本文件仅定义授课工作流及共用项目课酬卡。
