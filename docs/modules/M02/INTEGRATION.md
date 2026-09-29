# M02 业务服务与原系统集成说明

更新日期：2026-09-22。规则版本：`M02-basic-hours-v3-20260922`。依据 coordination/MASTER.md、CONTRACTS.md、modules/M02/BRIEF.md 及本任务用户最新授权：“你看着做吧，我也不确定，按你的逻辑先做”。未收到表单样例，但不再以该材料为本模块设计的前置条件。

## 2026-09-22 用户明确培训分类（总控接入）

新建需求在原系统弹窗选择“亲子财商、养老丰润、服务资格认证、其他类培训”，无默认选择。编辑已有需求时保留原历史分类值，不静默改成其他；存储字段仍为 category_text。此决定覆盖下文较早的自由填写分类建议。分类不代表具体课目、讲师认证或费率。原需求/审批/受理接口保持兼容，既有分类值不做批量迁移。

## 本轮交付与边界

- `src/com/training/DemandIntake.java`：Java 17 纯输入/输出校验、基本课时两位显示与内部精确分数、路径展示、受理条件投影、投标结果登记和受理动作的校验。无数据库连接、迁移、网络或审批实现。
- `web/modules/intake/`：内部合成逻辑测试工具，保留用于校验规则和状态，不是正式UI交付；禁止接成新增模块导航。
- `scripts/M02-check.sh`、`scripts/M02DemandIntakeTest.java`：隔离临时编译并验证服务边界，不使用共享 out/。
- `scripts/M02-ui-test.mjs`：前端模型、root 事件生命周期、转义与请求隔离测试（Node 无额外依赖）。完整执行结果记录于 coordination/modules/M02/STATUS.md。
- 未改 Main/Api/Db/Auth/Json、web/app.js/index.html/v13.css、共享检查脚本；未部署、未操作生产、未暂存或提交。

服务/校验器可继续集成；独立测试页面不代表原系统UI已接入，不作为最终视觉成果。当前未完成原页面、数据库、正式鉴权或完整流程验收。演示状态仅保存在页面内存，刷新丢弃，不写 localStorage。

## 正式 UI：回到原系统局部接入（2026-09-21 最新要求）

用户已明确认可原系统的界面和整体逻辑。本节覆盖此前把 `mount()` 与 `intake.css` 接入正式宿主的建议：**正式产品继续使用原 web/index.html、app.js、v13 样式及原导航，不挂载独立 intake 整页，不新增“M02”导航，不加载 intake.css 作为正式页面样式。** 独立文件仅保留内部合成逻辑验证，不能作为正式系统已交付或视觉验收证据。模块服务/校验器保留；本次没有修改总控公共文件。

### 原页面和最小改动

以下行号按本次只读核对的 app.js；总控后续整合以函数名为准。

| 原页面 / 接点 | 直接复用 | 仅需增加或改变的业务差异 |
| --- | --- | --- |
| NAV:916 的“培训需求”（demands），CRUD.demands:1317 | 现有标题、需求单位/客户联系人/交付预期/状态列；原搜索、filter-tabs、台账表格和行操作 | 在需求原弹窗局部增加承接路径、内部对接人、授课分类和人数；实际填报人只读。客户对接人继续选填。列表用原状态标识显示审批/受理进展，无需另起受理大页面。 |
| pageCrud:1459、editForm:1701 | 新增/编辑按钮、openModal、renderForm 两列布局、字段错误和保存后刷新 | 需求保存草稿与“提交需求”分开。只有提交才检查完整必填并进入 M03；详情仍使用原详情弹窗。已提交后的可编辑范围和重提规则服从 M03，不能用普通编辑绕过审批。 |
| CRUD.demands.fields 的 training_mode / training_period:1335–1336 | 原授课方式/时段下拉框及已有值“待定、线下、线上、上午、下午、全天”；省市联动保留 | 在原下拉按需补“混合”“晚间”，必要的自定义内容使用原表单文本项。保留已有“待定”文本，内部测试的“待协调”不要求批量改写旧数据。 |
| NAV 的“投标与立项”（bids），CRUD.bids:1344、buildActions:1604 | 原投标关联需求、报价、日期、方案、详情、原表格和弹窗 | 将“评审状态/评审意见”对应结果区调整为“登记结果/结果说明”；专用原风格弹窗填写原签报引用、结果、确认日期、说明。中标进入待团队受理，未中标留档。必须替换旧 /bids/win 自动立项动作，不能仅改按钮名继续调用。保留既有方案/报价/投标日期，不拿结果日期覆盖投标日期。 |
| 培训需求行操作 buildActions:1576 与原状态筛选 | renderRowActions、更多操作菜单、renderTable、bindTableActions | 在宿主返回“可受理”且当前会话获授权的需求行显示“团队受理”；沿原弹窗选择已授权团队并确认。负责人待审、BP待审、待结果、未中标无接单动作；重复受理由服务器拒绝。审批/受理阶段由 M03/M02 以独立字段投影，复用原状态标识外观，不能覆盖原交付状态或让操作者手选“审批已通过”。 |
| 项目总览 CRUD.projects:1360 与项目工作区 pageProjectDetail:1732 | 原项目列表、项目资料编辑、工作区和打开项目动作 | 承接/立项后仍回原项目记录。直接承接保留 demand_id、允许 bid_id 为空；不得造中标。新流程的项目来源受服务端约束，已存在项目的无关资料/交付功能保留。无需增加新项目工作区。 |
| 今日运营需求待办:1921 附近，navigateTo / revealFocusedRow | 原待办卡、点击返回需求列表并定位记录、已有状态筛选上下文 | 根据当前用户获授权动作显示“提交需求/处理审批/团队受理/登记结果”等文案；只引导至原 demands/bids 页面或原审批入口，不跳独立 demo。负责人/BP阶段由 M03提供。 |

### 原字段保留和服务适配

M02 服务的命名是内部契约，不要求把原业务页面全部改成“编码输入框”。正式弹窗继续使用原业务标签、已有资料及经权限控制的组织/人员选择控件，底层提交 *_code；不得要求用户手填一串演示编码才能录入需求。

| 原字段 / 操作 | 接入处理 |
| --- | --- |
| title | 传 M02 title，保留“培训项目名称”标签。 |
| unit | 保留“需求单位”及原值，它不是内部所属分公司 organization_code；另由会话/组织选择提供组织编码，不以单位名称推断权限。 |
| contact / phone | 保留客户联系人及电话的现有编辑习惯，客户可后补；若关联 customer_contact_code，从可信联系人选择取得，不按姓名猜码。内部对接人与填报人另存，不复用客户联系人字段。 |
| training_mode / training_period | 对应 delivery_mode_text / period_text 进入校验。保留既有选项、待定值和未知历史文本；不把时段用作计酬日期类型。 |
| expect_date | 原“期望培训时间”继续使用日期控件，对应 expected_start_date；有实际需求再在同弹窗补结束日期，不在列表外重做日历。 |
| content / teacher_req / remark | 原主要内容、师资要求、备注必须保留；objectives 可在原内容区补“培训目标”或由明确的目标字段映射，不能默默丢弃旧内容或把师资要求塞进目标。 |
| hours | 保留用户熟悉的“预计课时”及原位置，补“1课时=45分钟”提示，取消把 step=0.5 当正式精度限制。新输入以十进制原字符串保存；宿主待实现的局部适配可用 BigDecimal 将新录入课时精确乘45转为 duration_minutes，另保留原输入值和单位。禁止在原始分钟/课时输入上先用 Number / double / toFixed / round 再传 M02；两位舍入只用于派生的基本课时显示。也可在原输入旁局部提供“分钟”单位选择，不能同时维护两个互相覆盖的权威时长值。 |
| 旧记录缺少承接路径/人员编码或原始分钟 | 保留原记录展示，标识需要核对的新流程资料。默认直接承接仅用于新草稿，不把历史记录批量改成直接承接；不得把旧 DOUBLE 课时逆算成“精确原始分钟”。新提交/推进动作再核验所需信息，无关资料修改不擅自清空旧字段。 |

现有 `collectForm`:600 对 type=number 会在619转 Number。M02相关精确字段应局部按字符串收集（仍用原输入外观和错误提示）；不要为本模块改变全系统的数值行为。服务只接受 M02字段白名单，原 unit/contact/phone/teacher_req/remark 等由宿主保留并走原字段验证，不能将整条旧记录直接塞给 M02，也不能只保存 M02.values 而丢掉原字段。

当前 Java 提供的是分钟原值校验和精确课时换算；以上“原课时输入→分钟”的宿主适配及新旧字段持久化还未实施，不能称原表单已接通。

### 复用控件和动作边界

- `openModal`:480、`closeModal`:540、`renderForm`:568、`collectForm`:600：复用现有标题、字段布局、焦点陷阱、Esc、提交防重复、字段错误与返焦。新结果登记及受理弹窗沿此机制，不新造浮层或落地页。保存或动作校验失败返回 false 保留弹窗；按钮禁重复不代替后端幂等。
- `renderRowActions`:698、`renderTable`:707、`bindTableActions`:728、`tag/tagClass`:760附近：复用原表格、自适应标签、操作优先级和更多菜单。新阶段可复用标签映射，不能复制独立队列表。
- `editForm`:1701 当前 `onOk` 在1716发送 `{...row,...d}`。M02的草稿、提交、结果登记、受理都应按各自白名单构造请求；不发送客户端的 filler_code、审批通过位或权限标记。流程状态由服务端推进，需求原 status下拉不能直接写入新审批/受理结论。
- `canWrite`:1285 仅是旧 admin/manager UI粗开关。新动作采用可信服务端允许操作信息配合 M01范围检查，不能拿按钮可见当授权。M02不替代M03审批。
- 成功后复用 `toast`:465、`renderPage`:1250 或当前列表刷新；保留 `state.filters`、`isRouteCurrent`、`routeEpoch`、`addRouteCleanup` 与请求取消逻辑，过期响应不覆盖用户切换后的页面。
- `quickCreateDemand`:941 也调用 editForm，侧栏/移动端/快捷键共用；与列表新增一并验证，避免两个入口出现不同必填或流程行为。
- 原工具均位于 app.js 的 IIFE 内，独立 ES module 不能直接导入它们。总控应在原闭包内调用原控件，或显式传递少量宿主回调；不要为直接挂 demo 引入第二套导航/布局。现有 v13.css 的卡片、工具栏、筛选、表格、行操作、弹窗、表单及移动适配均可原样复用。

### 原系统验收（待总控正式接入后执行）

1. 真实浏览器确认 index.html 的 style.css → studio.css → ledger.css → v10.css → v13.css 全部正常加载，原导航/卡片/表格/弹窗和移动交互未退化。公共预览样式故障由总控处理，本模块不增加第二套主题。
2. 从原“新增培训需求”打开原样式弹窗：不完整草稿可保存；提交检查关键信息；客户可后补；新路径选择、生效后审批顺序和三个身份正确，原单位/客户电话/师资要求/备注保存往返不丢失。
3. 原投标列表登记真实来源的合成测试结果：中标仅待团队受理、未中标留档；重复和无权限请求拒绝，投标方案和日期仍保留。
4. 原需求列表/待办可进入受理，未审完不可受理；直接承接形成项目时无需伪造bid。点击项目进入原工作区。
5. 字段错误、返回焦点、更多菜单、筛选保留、切换页面后过期响应、手机布局和旧历史记录显示均用原系统实测。模块检查不代表这些正式集成场景已经通过。

## 用户授权后的默认决定

用户已授权 M02 自行设计起步规则，不继续向用户索取空白表、编码样例或要求先解释字段。选用下列规则及理由：

- 初始草稿预选直接承接，可显式切换投标；直接承接仍需负责人→BP顺序，不能把默认路径当审批结论。
- 时段默认“待协调”，避免编造具体日期；日期仍选填。授课方式建议线下/线上/混合，时段建议上午/下午/晚间/全天/待协调，分类建议通用能力/管理能力/业务技能/专业技术/其他。建议均可自由改写，不是正式编码字典，也不参与费率判断。
- 客户对接人改为选填、可后补，内部对接人仍必填，实际填报人仍取可信会话；允许需求尚在接洽时先进入内部流程。
- 接单默认业务职责为“培训团队中获授权的人员”；原签报结果登记默认为“分公司内部对接人获授权后办理”。这是流程设计，不能按岗位名称自动授予能力；具体账号、记录范围、团队关系仍由 M01 和宿主验证。
- 暂不新增正式编码格式，不把 DEMO 编号当正式号；需求编码仍可空，由总控统一的编码体系分配。
- 2026-09-22总控转达M05用户确认60分钟=1.33课时；基本分钟÷45保留两位，HALF_UP为已记录实现选择。原始分钟保留，计酬课时及金额规则独立，不能从基本课时显示自动推导。时段与“工作日/休息日/法定节假日”也不能互相推导。

这次授权消除了“等用户材料才能定表单”的等待项；正式鉴权、持久化和跨模块集成仍由总控负责。后续发现真实流程差异再调整默认方案。

## 内部逻辑测试入口（不是正式接入方案）

仅内部测试入口：`web/modules/intake/index.js`，`export async function mount(root, context)`，返回 cleanup。宿主传入既定 context：mode、signal、request、user、notify、navigate。模块只操作 root 范围，样式使用 `.yx-intake` 命名范围。demo.html 自带 intake.css，仅供内部验证；正式宿主不载入该整页样式，使用原系统控件和 v13 样式链。

- demo 不调用 request，不访问生产；必须提供显式合成会话 `user: { user_code: 'DEMO-FILLER-01', display_name: '合成填报人 A', synthetic: true }`，不能将真实用户当演示身份。
- live 当前不调用旧 CRUD，也不展示假数据；提示尚未接入。
- 静态入口 `web/modules/intake/demo.html` 仅内部调试，可经本地静态服务访问；不得作为正式交付/视觉预览，不为此启动业务服务或生产连接。
- 实际填报人与内、外部对接人分别展示。所有 DEMO 前缀均明确为合成编码，不分配正式编码。

## 输入模型

Java 所有表单值均使用字符串，包括数字。现有 Json 将 JSON 数字解析为 Double，故不得先走 Json.num/lng、Number 或旧 hours DOUBLE 再传服务。

| 字段 | 首轮规则 | 说明 |
| --- | --- | --- |
| title | 提交时必填 | 需求名称 |
| business_path | 提交时 direct / bid 必选 | 独立于现有 status |
| organization_code | 提交时必填 | M01 核验；不转成数字记录 id |
| internal_contact_code | 提交时必填 | 内部对接人，不自动等同填报人 |
| customer_contact_code | 选填，可后补 | 客户对接人单独记录，不设客户入口 |
| category_text / delivery_mode_text / period_text | 提交时必填 | 常用建议可自由填写；时段初始为“待协调”，非费率类别 |
| duration_minutes | 提交时 > 0 普通十进制字符串 | 原分钟不改写；基本课时按分钟÷45、两位HALF_UP，不等于计酬课时 |
| participant_count | 提交时 > 0 整数字符串 | 禁止小数，不经浮点转换 |
| budget_amount | 选填，非负普通十进制字符串 | 金额单位、正式精度及上限待确认，不计费 |
| expected_start_date / expected_end_date | 选填有效 YYYY-MM-DD | 同时存在时结束不得早于开始 |
| objectives | 提交时必填 | 培训目标 |
| external_approval_ref | 投标提交时必填，直接承接不接受 | 原办公签报关联，宿主验证来源 |
| demand_code | 选填 | 未分配不生成；正式格式待材料 |
| filler_code | 禁止从表单写入 | 服务参数来自可信内部会话 |

草稿可缺字段，但已有数值/日期必须有效。禁止 NaN、Infinity、指数、负值、数组/对象数值。数值文本长度上限 120；编码 120；objectives/result_note 4000；其余文本 500。长度是技术输入边界，不是正式业务精度。宿主不得将超出现有 title VARCHAR(200) 等列长的校验结果直接写旧列，需同步存储结构。

`initialDraft()` 返回 business_path=direct、period_text=待协调，前端 INITIAL_DRAFT 与之对应；它不会通过完整提交校验，也不包含任何身份或权限。

`validateDraft(body, authenticatedFillerCode)` 和 `validateForSubmission(body, authenticatedFillerCode)` 返回不可变 `Validation`，含 valid、values、errors、notices。通过 `toMap()` 供 `Json.write` 序列化；不要直接序列化 Java record（现有 Json 不支持 record 展开）。不得保存 valid=false 的 values。

创建草稿时由可信会话赋予 filler_code。编辑草稿须宿主校验权限并保留首次实际填报人，另记编辑人；不能用当前编辑人覆写首次填报人。未知或受控字段（如 status、filler_code、hours）直接报错，避免表单批量赋值。

## 基本课时两位显示与内部精确值（2026-09-22同步M05）

已确认：60分钟=1.33课时；基本课时采用分钟÷45，保留两位。HALF_UP是M05已记录的实现选择。本轮消除基本课时“精度待定”口径，金额舍入、计酬换算和制度生效不由这一确认推定。

为保持总控正在调用的服务兼容，所有既有方法签名、CourseUnits四个record组件和构造器不变：originalMinutes、numerator、denominator、exactDecimal。exactDecimal仍只表示数学上的有限小数，循环小数仍为null；它不是业务页面显示值，也不能替换为舍入后的数字。

- 新增无参数访问方法 CourseUnits.basicClassHours()，返回固定两位字符串。用与M05相同的 BigDecimal.divide(45, 2, HALF_UP)，不经double。
- CourseUnits.toMap() 保留旧字段，并新增 class_hours（业务显示）、class_hour_scale=2、class_hour_rounding=HALF_UP、rule_version=M05-CLASS45-2DP-V1、evidence_code=USER-20260921-60MIN-1.33CLASS、rounding_decision_code=IMPLEMENTATION-2DP-HALF-UP；原 rounding_policy 更新为 HALF_UP。原 minutes_per_unit=45 保持不变。
- 正式原页面接入应显示“1.33 基本课时（原始时长60分钟）”一类值，保留原始分钟；内部 numerator/denominator 不展示，也不作为计酬字段。当前仅同步M02内部测试文案，不改公共app.js或另做正式UI。
- 前端 lessonUnits(raw) 保留已有签名与内部精确分数，增加classHours/originalMinutes/ruleVersion/scale/roundingPolicy；以BigInt作正数HALF_UP，全部有效值固定两位显示，避免浮点临界误差。
- 样例：60→1.33，50→1.11，45→1.00，67.50→1.50；0.225→0.01，44.775→1.00。正分钟折为0.00时仍保留原文，不将其倒推成零分钟或零授课事实。

严格对照而未在运行时直接调用M05.convert：M02原接口允许120字符及更高小数精度，M05结算模块另有40字符、有效数字24位、小数8位和输出范围约束。直接复用会收窄总控既有M02输入契约。因此保留M02输入范围，模块Java测试共同编译只读M05 DeliverySettlement/DeliverySettlementHours，并对共同有效输入逐例核对真实conversion及版本/证据/舍入metadata；前端对同组结果回归。M02宽范围展示不代表输入已符合M05结算事实接收范围，不产生payable_hours或金额。

## 流程与动作

`route("direct")` 仅展示“内部填报 → 分公司负责人先审 → BP 后审 → 培训团队承接”。负责人/BP 审批及事件由 M03 唯一负责。直接承接无投标、中标记录，立项时必须允许保留 demand_id 而 bid_id 为空。

`route("bid")` 仅展示“内部填报 → 原办公签报 → 登记投标结果 → 中标待承接 / 未中标留档”。不在研序重建签报、不执行“确定中标”审批。

`Readiness(demandId, businessPath, draft, directApprovedByM03, bidResult, alreadyAccepted)` 是宿主从数据库和 M03 读取的事实，不能绑定请求体。directApprovedByM03 表示 M03 在当前需求版本上完整通过负责人→BP顺序，不是前端勾选或泛化的“已批准”。bidResult 仅空、won、lost。`queueDisposition` 返回 draft / waiting_approval / waiting_bid_result / archived_lost / ready / accepted / inconsistent。

`Access(actorCode, capabilities)` 必须在宿主已验证当前会话、组织/记录权限及团队关系后构造；不能将客户输入的 capability/actorCode 当权限。当前代码只消费许可结果，不实现 M01 权限分配。

- `validateBidResult(facts, body, access)`：仅已提交投标需求；需要 REGISTER_BID_RESULT 能力；字段 result(won/lost)、external_approval_ref、result_date 必填，result_note 选填；recorded_by_code 由可信 access 提供。防止重复登记/覆盖。内部对接人经授权后登记，记录人不自动等同外部签报的决定人；原办公审批结论与依据仍需核验。
- `validateTeamAcceptance(facts, teamCode, access)`：需要 ACCEPT_TEAM 能力及宿主已核验团队编码；直接承接要 M03 有效通过，投标要已中标；草稿、等待、未中标、重复受理、矛盾数据均拒绝。
- 当前以“需求最终投标结果摘要”作单结果演示，不定义同需求多轮投标/多个标包的正式基数；业务样例确认后再设计结果更正及多轮留痕，不覆盖旧结果。
- 前端 `registerSyntheticBidResult` 只模拟结果/依据引用和队列变化，不是正式 API 请求体；正式接入还须将结果确认日期、来源签报核验和可信登记人带入 Java 校验。前端不会将界面合成状态直接交服务器作为权限/审批事实。

## 宿主待集成（建议，尚未实施）

所有写入放在已有 Api.MUTATION_LOCK 内，读取当前版本和事实后校验；多步写入用 Db.transaction。建议新增专用草稿、提交、结果登记、受理动作，不能让新模块复用旧“确定中标”接口。建议响应结构沿用 Validation.toMap；正式接口 URL 由总控统一决定。

1. 按 M01 校验填报人、组织、联系人编码与当前记录范围，不接受姓名关联或客户端权限。M01 未分配正式编码时阻止正式提交，保留草稿。
2. 分配/保留数字 demand_id；文书、人员、组织编码是独立字符串。更新带版本，冲突拒绝；重复受理和重复结果须在锁内重读并加唯一约束，纯函数不能替代事务保护。
3. 直接需求提交交 M03；审批依据发生修改须按 M03 版本重提。受理不自动跳过审批，不自动伪造项目/bid。
4. 原办公签报编号与需求关联必须服务端核验。结果仅登记；未中标保留；更正需后续受控且留痕。
5. 示例持久化建议（非迁移）：扩展需求 sidecar 以 demand_id 主键关联现有 demands；存表单原文、business_path、filler_code、version、rule_version。单独结果表存 demand_id、原签报、result、result_date、recorded_by_code、留痕时间；受理表存 demand_id、team_code、accepted_by_code、版本和时间。M03 审批字段不复制维护。精确小数用文本或经确认的合适 DECIMAL，不能先定 scale 后截断。
6. 新路径接通前需同时约束旧 CRUD 入口，防止旧页面/直接请求绕过新校验和审批。

## 已核实的旧代码冲突（行号为本轮审查时）

- Api.java:421–439 要求需求与投标同时关联且中标：直接承接须改路径分支。
- Api.java:425–427 允许两关联均为零；937–967 对无需求项目不校验来源：需封住新业务绕行立项/启动。
- Api.java:401–409、577–590、738–741 需求普通编辑只保护 status；712–715 删除只检查投标/项目引用：需接 M03 提交/版本约束。
- Api.java:1011–1039 内部“中标立项”并自动把其他投标改未中标：不可当作外部结果登记使用。
- Api.java:608–614 批量未中标，708–711、720–726 可删除：需保留外部结果记录和更正历史。
- Db.java:33–42、Api.java:40–41 无路径及三方身份区分；web/app.js:1322、1329–1330 混用联系人。
- web/app.js:619、Api.java:585/633/1038、Json.java:228、Db.java:35/42 经 Number/Double 链，不能承诺精确保真。
- web/app.js:1331/1377 固定 step:0.5，app.js:17 默认显示精度：基本课时应在原控件局部按已确认两位规则展示并保留分钟，不能用半课时步长或通用格式隐藏已确认精度。

此清单只建议总控修改其独占文件，本任务未越界修改。
