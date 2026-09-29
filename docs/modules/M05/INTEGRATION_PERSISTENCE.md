# M05 持久化接入合同（2026-09-22）

本轮新增 `DeliverySettlementIntegration.java`，并按总控复核补齐配置前核对门槛、实时操作能力和 `web/modules/delivery-settlement/index.js` 原弹窗组件，只改M05独占文件。总控负责接原路由、Db初始化、旧入口门禁与原页面；本文不是这些公共接点已经生效的证明。无生产初始化、无默认授权或费率，无支付接口。

## 初始化与路由

- 在旧业务表、`OrganizationAccessStore.init()`、`WorkflowIntegration.init()`完成后，事务外调用 `DeliverySettlementIntegration.init() throws SQLException`。重复调用不产生配置或业务数据。
- 登录后、旧`requireWrite`之前接 `if (DeliverySettlementIntegration.handle(ex, session)) return;`。沿用Api的1 MiB请求体上限、JSON检查、`Api.MUTATION_LOCK`和延迟响应。未知路径先返回false，不触碰认证。
- `handle(HttpExchange, Auth.Session) throws Exception`要求请求token解析出的真实Session对象与传入对象相同，且`Auth.current`仍有效。包内`read`/`preview`/`mutate`以及公开`ledger`、`projectHours`和`requireCompletion`也重验真实Auth和M01。
- 所有变更自持同一锁，在一个`Db.transaction`内重验身份、范围、来源、事实版本、正式配置和结算head；已有事务时拒绝嵌套，避免Db内层提前commit。门禁helper只读且不自行开启事务。

| HTTP | 路径 | 返回 |
|---|---|---|
| GET | `/api/delivery-settlement?dispatch_id=123` | 当前事实、独立四课时、换算依据、当前配置、全部不可变快照 |
| GET | `/api/delivery-settlement/policy-preview?dispatch_id=123` | 用户批准文件的只读预览；不写配置或快照，见 APPROVED_POLICY_PREVIEW.md |
| GET | `/api/delivery-settlement/preview?dispatch_id=123` | 核心计算view；缺配置amount=null、NOT_CONFIGURED；不写入 |
| GET | `/api/delivery-settlement/ledger?dispatch_id=123` | 冻结统计分录及独立持久ID，需EXPORT授权 |
| POST | `/api/delivery-settlement/save` | 保存新事实修订，清空原核对 |
| POST | `/api/delivery-settlement/verify` | 服务端登记真实人员、时间和核对依据，新修订 |
| POST | `/api/delivery-settlement/configure` | 经授权登记完整正式适用依据，生成不可变配置版本 |
| POST | `/api/delivery-settlement/confirm` | 首次确认完整冻结快照和原始统计分录 |
| POST | `/api/delivery-settlement/correct` | 明确未支付的同授课日更正，新完整快照及差额分录 |

GET只接受`dispatch_id`。POST只接受每项白名单，不接受客户端organization、actor、permission、verification、amount、teacher_id、project_id或service_date。格式不合法400、失效会话401、越权403、不存在404、错误方法405、缺规则/版本冲突/未迁移/状态冲突409。

## 组织和身份边界

排课必须真实关联现有projects和teachers。机构仅来自`workflow_acceptances JOIN workflow_demands`，同时验证`projects.demand_id=workflow_acceptances.demand_id`。持久事实的project/teacher/org/serviceDate必须仍匹配服务器来源；漂移时拒绝继续处理，不覆盖旧历史。

无该来源的旧项目明确409、等待有审计依据的显式迁移；不从unit、teacher.org、当前人的机构或浏览器入参猜。旧数据不自动写入新表。`TEACHER-<teachers.id>`、`DISPATCH-<dispatches.id>`只是稳定系统引用，返回`teacher_code_kind=SYSTEM_TEACHER_ID`，不能展示成人事正式编码。已有teacher_level/fee_rate不作为历史聘任资格或正式费率。

M01须显式发布以下精确资源，没有旧admin/manager自动授权：

| 资源 | Action | 操作 |
|---|---|---|
| `delivery.read` | VIEW | 读取事实、预览、项目四类课时汇总 |
| `delivery.write` | HANDLE | 保存事实 |
| `delivery.verify` | HANDLE | 核对事实、原课程完成门禁 |
| `settlement.configure` | HANDLE | 登记正式适用规则和资格依据 |
| `settlement.confirm` | HANDLE | 初次确认 |
| `settlement.correct` | HANDLE | 更正 |
| `settlement.export` | EXPORT | 读取冻结统计台账 |

登记配置者的岗位映射由组织权限管理确定。配置端点只记录经授权登记的依据，不能声称已经联网验真原文件或自动完成聘任、审批、税务审核。

## 请求字段

所有POST必填`dispatch_id`、`expected_version`、`request_id`。首次事实版本为0；保存、核对均令事实修订+1。详情能力字段的动态语义见下节，不能将旧请求缓存的权限用于新操作。整数ID/版本允许安全整数数字或十进制字符串，最多JavaScript安全整数。业务数量一律十进制字符串或null，禁止JSON浮点数；不经过`Number`、`Json.num`或旧double字段。

`save`额外允许：

```json
{
  "dispatch_id": 123, "expected_version": 0, "request_id": "SAVE-123-1",
  "estimated_hours": "2.00", "planned_hours": "2.00",
  "actual_minutes": "60", "payable_hours": null,
  "dimensions": {"grade": "LECTURER", "time_band": "WORKDAY", "form": "TEACHING", "hour_unit": "CLASS45"}
}
```

缺少的数量字段保留原值，显式null清空；首次缺少为null。dimensions提供时作为整组替换，未提供则保留。实际课时只从原始分钟÷45、两位HALF_UP产生，60→1.33；不推定计酬课时。45分钟/60分钟折算已由用户确定，HALF_UP为已记录的实现选择，金额舍入独立配置。转换对象含原始分钟、定义、转换版本及依据，随每个事实修订和快照保留。

授课日先在原排课补齐；缺日期、已拒绝排课不能新增事实，避免生成无法核对的孤立草稿。事实保存允许待启动/进行中项目，保持原项目交付开放范围。

`verify`额外必填`evidence_code`。仅已确认/已完成、授课日期已到、老师在库、项目仍允许交付且已记录实际分钟可核对。无需计酬课时或正式费率。客户端不能自行提交核对人/时间/已核对标记。任意save（包括改变预计/计划/计酬/维度）都会增加修订并清空核对。

`configure`额外必填`expected_config_version`（首次null）、`configuration`。configuration是完整对象：

```json
{
  "version": "APPROVED-CONFIG-123-1",
  "publication_evidence": "FORMAL-PUBLICATION-REF",
  "eligibility_evidence": "VERIFIED-ELIGIBILITY-123",
  "approval_status": "APPROVED",
  "rule": {
    "version": "FORMAL-RULE-V1", "evidence": "FORMAL-RULE-REF",
    "effective_from": "2026-01-01", "effective_until": null,
    "date_basis": "SERVICE_DATE", "formula": "PAYABLE_HOURS_TIMES_RATE",
    "currency": "CNY", "amount_scale": 2, "rounding_mode": "HALF_UP",
    "rounding_scope": "PER_LINE", "minutes_per_class_hour": 45
  },
  "rate": {
    "version": "FORMAL-RATE-V1", "evidence": "FORMAL-RATE-REF",
    "rule_version": "FORMAL-RULE-V1",
    "effective_from": "2026-01-01", "effective_until": null,
    "dimensions": {"grade": "LECTURER", "time_band": "WORKDAY", "form": "TEACHING", "hour_unit": "CLASS45"},
    "unit_rate": "100"
  },
  "correction": null
}
```

**以上只是接口字段示例，不是已批准费率、日期或金额舍入制度，不写入任何种子配置。** 旧正式配置接口的字段契约尚未改变、宿主仍未开放。用户已批准采用附件明确条款，新批准预览另存执行依据并逐项检查授课资格，不再整体要求先取得原稿发布日期；原候选标识仍不能冒充新批准版本。正式接口不从候选表取默认值。rule/rate必须完整、适用日期/维度吻合，分钟定义为45；日期区间`[from,until)`。两个课时或两个课次的金额不能靠此单行规则推定其他支付政策。

登记时冻结授课、项目、老师、组织、日期、维度、**完整事实修订号**及登记人/账号/时间。配置端点现在显式检查当前事实已核对、原始分钟和实际课时完整且与45分钟换算一致，以及当前排课、项目、讲师、日期仍满足交付核对条件。未核对、任何事实修改后尚未重新核对、源状态或关联改变均拒绝登记，不能因为计算结果只是INCOMPLETE就冻结配置。计酬课时仍不是verify的前提。须先核对最终事实再登记适用配置；后续事实变化或再次核对，需要新配置版本，不能沿用旧资格凭据。全局相同RULE/RATE/CORRECTION版本不可同码异义；配置版本本身不可复用或覆盖。

支持的更正配置为`correction:{version,evidence,effective_from,effective_until,date_basis:"SERVICE_DATE",method:"REPLACEMENT_DELTA"}`，缺失则更正阻断。该配置是显式审批后的规则登记，不是提供草案即可自动开放。

`confirm`必填`expected_config_version`。要求对应当前核对事实的正式配置及完整计酬数据；已有head须使用更正。

`correct`再填`expected_snapshot_code`、`reason_code`。需新增事实修订、重新核对及对应新配置；比较数据库head，支持明确未支付的同日期差额。整条祖先链任一支付状态非UNPAID、有支付日或同项目/老师存在旧fees行时拒绝；已支付、支付不明、跨授课日、取消结算暂不支持。初次confirm也会检查同项目/老师旧fees行，防止未迁移旧账与新账双计。

请求账本键是**真实account_id＋request_id**，并核对personCode、操作和递归排序规范化载荷；幂等重试首先重验当前真实身份、该操作权限和来源关联，然后返回该次原业务结果，并重新计算当前capabilities；业务version与capabilities.fact_version可能因此不同，组件会保留输入并只读刷新当前详情。相同key不同内容409。未来版本与head冲突不会偷偷重算或覆盖。

## 持久化与历史读取

六张M05表：`m05_delivery_facts`当前head、`m05_fact_revisions`事实修订、`m05_policy_versions`全局版本内容、`m05_settlement_configs`适用配置、`m05_settlement_snapshots`完整快照和分录、`m05_requests`幂等请求。小数以字符串保存在CLOB中，无double金额列。每授课/事实修订唯一结算；前快照唯一后继，CAS比较当前head。

普通read直接返回已存完整快照，不调用今天的定价代码。更正时，包内`DeliverySettlement.restoreFrozen`从完整冻结事实、规则、金额、差额和引擎版本恢复旧链；不做乘法或舍入，不以当前配置重新定价。未知旧引擎版本阻止新更正，但已存JSON仍可读。该方法仅供可信数据库边界，不是请求解析/授权接口。

结算快照及分录独立持久ID；原始分录CONFIRMED保持在账，更正ADJUSTMENT分别保存四类课时差分和金额差额。统计累计原分录+差额，禁止同时累计两个完整金额，也禁止删除原分录只留差额。未知任一侧课时差分为null。

`ledger`返回M06字段、`id`、`projectId`、`payment_status`和`payment_date`。授课日期来自冻结事实；未支付日期为null。只有真实PAID且有效支付日才能提供`dates.PAYMENT`。本轮没有登记支付的入口，新确认明确UNPAID；UNKNOWN不能当作UNPAID。courseId/courseCode暂为null，宿主必须从真实课程绑定补齐，不能把排课、项目或名字冒充课程。正式CSV批次/支付流水仍未接入。

## 总控必须接入的旧入口门禁

只新增适配helper，不改Api/Db/Auth/WorkflowIntegration或页面。以下接点未接时，不应对外宣布已阻断旧入口：

1. `feeCalc`进入原事务、删除待发放fees之前，调用`guardLegacyMutation("fees/calc", projectId, body)`。新流程受控项目禁止旧teacher.fee_rate和SUM(dispatch.hours)计算。
2. generic `fees`保存读existing后、写入前调用`guardLegacyMutation("fees", id或0, body)`，同时检查旧项目和请求目标项目，避免移出受控项目绕过。删除和支付分别用`fees/delete`、`fees/pay`、真实fee id；**支付须在已发放幂等成功分支前**。
3. generic `dispatches`保存/删除分别传`dispatches`、`dispatches/delete`。已有M05事实禁止换project/teacher/date或删除；原普通字段仍走原白名单。受控项目禁止新建已完成排课或从其他状态经泛写迁入已完成；原已完成排课在status不变时允许原材料/备注维护，仍执行关联/日期不可变检查，不误伤原页面整行提交。
4. 原`dispatchComplete`在“已完成无需重复”分支前，若`controlled(projectId)`，调用`requireCompletion(session,dispatchId,expected_version)`；同一锁和业务事务内重验。它只要求当前实际授课事实已核对，不依赖计酬课时或正式费率。原完成动作负责状态/消息日志，不再把旧hours当实际值。不要对未迁移旧项目无条件调用此helper。
5. 原项目汇总/完成前置使用`projectHours(session,projectId)`的精确字符串，分别显示四类课时。已拒绝排课排除；未完成计入pending_count；完成但缺事实/核对计入unverified_completed_count，并保持actual/payable未知。实际总量只累计已完成已核对记录，不能把四项相加。宿主结合pending/unverified和真实交付条件决定项目完成。
6. 原项目归档/结清须在旧fees相等判断和成功分支前调用`guardLegacyArchive(projectId)`；受控项目在支付核对未接入时明确阻断，不为了旧代码兼容而向fees或dispatch.hours回写新账。
7. 保留原`pageDispatches`/`pageFees`/项目工作区布局、导航、筛选、表格和弹窗。分钟/数量以字符串提交，课时支持0.01。内部demo不作为正式UI。原路由与UI接入后仍须实际页面验收。

## 验证

运行`bash scripts/M05Integration-check.sh`：真实Auth登录/真实M01配置，独立新建H2目录；只建合成最小旧表，调用实际M01/M02/M03初始化，不调用Db.init或seed，不发邮件、不启动生产服务。编译实际共享源码至唯一临时目录，JVM退出后仅清理该目录。测试覆盖版本、幂等、并发CAS、冻结、更正、全四课时统计、权限变化、旧入口门禁及重开持久。

运行`bash scripts/M05_check.sh --with-m06`继续验证原核心、候选政策、分钟转换和M06桥接。本轮复核修订后实测：485项集成检查、60项冻结恢复断言；原核心88项、候选83项、分钟66项、M06桥接6项、Java/JS对照26项均通过。公共路由/原页面尚由总控接入，不能把适配层模拟HTTP检查当作原页面端到端验收。


## 当前详情能力协议与原弹窗挂载

所有详情GET以及save/verify/configure/confirm/correct响应都新增以下独立动态字段：

```json
{
  "capabilities": {
    "current_server": true,
    "fact_version": 12,
    "permissions": {"save": true, "verify": false, "complete": false},
    "can_save": true, "can_verify": false, "can_complete": false,
    "reasons": {
      "save": [],
      "verify": [{"code":"MISSING_PERMISSION","message":"没有本机构授课记录的核对权限"}],
      "complete": [{"code":"MISSING_PERMISSION","message":"没有本机构课程完成的核对权限"}]
    }
  }
}
```

permissions只反映当前真实Session/M01精确授权；can_*进一步结合当前服务器业务条件。不可操作的项有非空原因数组，可操作项原因为空。该字段仅为界面能力提示；服务端办理时仍在事务内重新校验，不能提交该字段充当授权。

- save权限=`delivery.write/HANDLE`，同时要求项目待启动/进行中、授课日已填写、排课未拒绝。
- verify权限=`delivery.verify/HANDLE`，同时要求当前事实存在、原始分钟/实际课时完整且一致、师资已确认/已完成、讲师在库、授课日已到、项目允许交付。已有核对可再次核对，产生新事实修订。
- complete权限同verify，再要求当前事实已核对且排课尚未完成。已完成返回`ALREADY_COMPLETED`，不再显示可重复完成。
- 稳定原因码：`MISSING_PERMISSION`、`SOURCE_NOT_EDITABLE`、`SOURCE_NOT_READY`、`FACT_MISSING`、`ACTUAL_MINUTES_MISSING`、`TEACHING_NOT_VERIFIED`、`ALREADY_COMPLETED`。前端直接显示message即可，不从旧role/canWrite推断新功能权限。
- 幂等命中仍返回原业务version/数据，但capabilities实时重算，fact_version为当前事实修订。不能把当前能力配旧数据直接提交；组件遇差异只补一次GET并保留全部输入。

挂载组件为 `web/modules/delivery-settlement/index.js` 的 `mount(root,context) -> {open,cleanup}`。它复用原 `api/renderForm/openModal/closeModal`、原CSS和现有分钟转换函数；不新增菜单、独立页面或演示框架。组件本轮只发详情GET、save、verify；无正式配置、结算或支付控件。400保留输入且同一被拒数据需先修改；409保留输入且需明确读取最新记录后手动提交；403只重读能力；网络未知只由用户主动点击同request_id重试。切页/注销必须把宿主route cleanup的AbortController.signal传入，取消请求并清监听。

完整示例和依赖合同见 [DELIVERY_COMPONENT.md](DELIVERY_COMPONENT.md)。原app.js在IIFE中，建议在原排课页按需 `await import('/modules/delivery-settlement/index.js')`，不要把静态import直接插入IIFE。传入宿主函数和页面AbortSignal后，在原排课动作中调用open(id)。onSaved/onVerified只更新列表对应行或提示，不重建整页而丢弃弹窗输入。

can_complete是M05门禁能力，不代表旧路由自动兼容。原 `/dispatches/complete` 仍有旧requireWrite/需求写权限门禁，总控须在接线时统一为真实M01并重验事实版本；接好之前不传onComplete，组件不会调用旧完成接口。原费用/完成/归档门禁以及公共写路由一次接好后，才对外开放该组件。公共文件仍未由本模块修改。

## 组件专项验证

- `node --test scripts/M05IntegrationUI.test.mjs`：16项交互用例；只用本地宿主/DOM桩，不下载依赖。默认读取实际交付模块，可用M05_WEB_ROOT/M05_UI_MODULE覆盖暂存位置。
- `node scripts/M05IntegrationBrowser.mjs`：35项真实Chromium检查，临时本机回环端口，只用合成API；从实际app.js提取原openModal/closeModal/renderForm和图标处理函数，加载原style/studio/ledger/v10/v13 CSS，检查实际权限按钮可见性、60→1.33、字符串/null、脏表单、400/409/403/401、原请求幂等重试、依据输入保留、注销/切页和晚响应隔离。浏览器路径可用M05_CHROME覆盖；Playwright来自当前已安装运行库，无下载。
- 已实际查看1280及390宽度截图，页面与弹窗均无横向溢出，0浏览器运行错误。截图为内部验证，真实原页面接线后的端到端验收仍需总控完成；不将合成API当作生产联调成功。
