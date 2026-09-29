# M06 统计排名与编码导出集成说明

2026-09-23更新：只读授课统计已由总控接原经营洞察；新增实际“下载授课统计”XLSX已经模块实现并通过483项专项及95项旧接口回归。当前新下载合同见 [TEACHING_EXPORT.md](TEACHING_EXPORT.md)。原 `/export` 正式金额/编码月报仍409；下文关于“导出始终阻止”的说明只适用于该原正式月报分支，不适用于新增 `/teaching-export`。

更新：2026-09-22。核心、编码导出及默认四表月报已实现；本轮新增基于真实Auth、M01和M05持久来源的只读授课适配，具体接口、原页面接点和验证见 [INTEGRATION_TEACHING.md](INTEGRATION_TEACHING.md)。适配已具备独立调用能力，公共Api挂载和原页面接线仍由总控完成。下文正式金额引擎、差额链及编码工作簿合同继续保留；当前授课概览不经过要求完整金额的引擎，不能将缺费用补零。未改共享路由、数据库、主前端，未部署。独立合成页面仅用于内部逻辑验证。

## 用户本轮口径与材料状态

用户直接回复：“授课日期 支付日期；都计入；这个你看着办；这个也是你看着办”。分别对应日期基准、课时类别、取消更正口径、排名规则。

已向用户明确并落实的版本为 `M06-20260921-1`：

- 日期按授课日期或支付日期切换，默认授课日期；起止日均计入。确认日期是引擎的可选能力，本轮已确认规则不允许它，页面也不提供该选项。
- 预计、计划、实际、计酬四类课时分别合计，不能相加为“总课时”。默认排名使用实际课时；也可选择其他课时类别。
- 取消、已被替代记录保留在业务来源中，不计入默认汇总、排名及其同源导出明细。
- 更正按增减差额计入。默认包含 `CONFIRMED` 和 `ADJUSTMENT`。不能同时加原金额与更正后完整金额。
- 默认按课时降序，可切换课酬；并列使用竞争排名 `1,2,2,4`。同指标按讲师编码升序稳定展示，不用其他指标偷偷打破并列。
- 币种必须由上游明确提供；演示币种 CNY 不是新增正式币种政策。`agreedDefaultRules(currency)` / `agreedRules(dateBasis,hourBasis,rankMetric,currency)` 创建上述规则，空币种拒绝。

用户已授权本模块决定取消、更正与排名默认值，无需再重复索取相同决定。用户随后明确“月报是什么，按你的意思来吧 直接做就好”，因此本模块自行确定默认月报布局与字段；模板和用户手工编写的样例不再作为前置材料，也不再重复索取。真实数据验收在总控接入M05已核对事实后进行，不能把合成样例当成真实台账验收。不要索取实名映射或其他系统密码。

已同步总控后续共享材料：课时单位已确认为45分钟，不重复询问。已阅读 M05 的 `POLICY_REVIEW.md` 与最新 `INTEGRATION.md`，共用《金尊公司内部培训师管理办法2.docx》的提取结果；未再次复制原件或独立声称验收文档。该稿为征求意见稿，正式生效仍以 M05 说明为准。评价表的面授开发×5、在线开发×3属于贡献评价，不进入本模块四类课时或课酬累加；以后如需要评价排名，应另有独立指标和规则版本，不能覆盖实际/计酬课时。历史金额继续取源明细，不按该稿新价重算。

页面与探针始终使用 `DEMO-M06-1` / `DEMO-FEE-*` 和明显的合成标识。演示页仍可试选不同并列或状态，以演示引擎能力；正式宿主应从已确认规则构造，不能照搬演示的任意配置权限。

## 文件与运行入口

- `src/com/training/ManagementReports.java`：Java 17 纯读取服务，无数据库、HTTP、定价、迁移与后台动作。
- `web/modules/reports/index.js`：ES module `async mount(root, context)`，返回 cleanup。
- `web/modules/reports/demo.html`：独立合成演示；通过本地 HTTP 打开。`?mode=live` 显示“统计口径已确定，正式数据尚未接入”，不会请求接口或加载假正式数据。
- `web/modules/reports/demo-model.js`：仅合成事实与浏览器演示计算；正式 live 必须读取 Java 结果，不以浏览器计算作为正式统计依据。
- `web/modules/reports/demo-model.test.mjs`：32项演示模型测试。
- `scripts/M06ReportsTest.java`：73项核心检查；`--demo` 输出合成 JSON，`--demo-csv` 输出合成明细CSV。
- `scripts/M06ReportsProbe.java`：只读合成 JSON 文件并输出多个规则结果，用于前后端一致性验证；必须 `dataMode=SYNTHETIC_DEMO` 和全套 DEMO 编码。它不是正式导入接口、数据认证或权限机制。
- `scripts/M06_compare_frontend.mjs`：同一组合成事实、147组条件的 Java/JS 对照，包含每类 CSV 的金额与课时回算。
- `scripts/M06_check.sh`：在自己的系统临时目录编译，仅编译 Json、ManagementReports 与 M06 测试，不写共享 out、不跑全套、不碰数据库。默认运行核心及一致性验证；编译临时目录小于1MB，不自动删除其他文件。

在 app/ 执行 `bash scripts/M06_check.sh` 和 `node web/modules/reports/demo-model.test.mjs`。可用 `M06_JAVA` 指定 Java 17；默认已验证的 `$HOME/.local/bin/java`。未依赖系统 Git/Python 的 Xcode stub。

## 核心调用

```java
// trustedOrgCodes/grants 来自登录态与服务端，不来自请求体。
var access = new ManagementReports.AccessScope(trustedOrgCodes, canView, canExport);
var rules = ManagementReports.agreedRules(dateBasis, hourBasis, rankMetric, sourceCurrency);
var filter = new ManagementReports.Filter(start, end, requestedOrgCodes);
var report = ManagementReports.build(authorizedSnapshot, rules, filter, access);
String responseJson = Json.write(report.toMap());
// 下载前宿主重新核查当前权限与组织范围。
String detailCsv = ManagementReports.exportCsv(report, ManagementReports.ExportKind.DETAIL);
String manifest = Json.write(ManagementReports.exportManifest(report, ManagementReports.ExportKind.DETAIL));
// 材料提供者的独立正确汇总，不能用本次计算结果自填。
var comparison = ManagementReports.compareExpected(report, expectedTotal);
```

`rulesFromMap`、`filterFromMap`、`factFromMap` 可供可信适配层使用；不要将客户端规则版本或授权范围当作已确认政策。结构错误抛 IllegalArgumentException（包含日期解析错误），查看/导出权限或机构越界抛 SecurityException；统一由宿主返回适当4xx及安全说明，不返回堆栈。

## 上游最小事实合同（待总控与 M05 接线）

每个 `Fact` 是一条不可变可加明细：

| 字段 | 含义和约束 |
| --- | --- |
| id | 明细快照/台账行的独立正数字 ID；同批唯一。不能把同一个业务单据 id 直接用于多个更正版本。 |
| courseId | 课程业务记录数字 ID；同一课程多讲师、多次课时事实时用于唯一课程数去重。必须由上游明确对应哪个业务实体。 |
| recordCode | 已分配的明细或快照编码，同批唯一；可映射 M05 snapshot_code。 |
| teacherCode/orgCode/courseCode | 正式编码；讲师编码需跨机构唯一。课程数字 id 与编码必须一一对应。 |
| dates | DateBasis 到 LocalDate 的映射；TEACHING/PAYMENT 为本轮政策允许基准。无日期为未知，不能用0、当天或授课日期代填。 |
| hours | 预计/计划/实际/计酬四类课时的独立 BigDecimal。缺失保持缺失，不能从另一类别复制或按分钟猜换算。 |
| fee | 该明细已经保存的历史金额或更正增减额。M06直接累加，不读取费率、不重算。 |
| currency | 明确的单一币种，入选行必须一致。 |
| feeVersion | 原历史金额的依据版本编码，不是当前最新费率版本。 |
| status | CONFIRMED / CANCELLED / SUPERSEDED / ADJUSTMENT。需由可信业务状态映射。 |

所有金额/课时 wire 值必须是十进制字符串；不得用 Json.num、Double 或浮点 JSON 数值传递。输入最多24位有效数字、8位小数，超限报错，不舍入；累加结果不截断。数字 ID 建议正整数字符串以支持完整 long；JSON数字只接受安全正整数。对外 JSON 数字 ID 也以字符串返回。编码格式为字母或数字开头，后续可含 `_ . : -`，最长96位；校验格式不代表已经认证正式编码，也不会自动编码英文姓名。

课时单位已确认为45分钟；M06读取上游已保存的课时数，不再次除以45或重做不足一课时的进位。业务时间戳转换为业务日及小数折算仍由上游明确提供，本模块不会自行推测时区或折算精度。唯一课程数是相同课程只统计一次，不能将讲师/机构各自的去重课程数简单相加来当全局课程数；课时、金额和记录数可跨组对账。

### M05 更正衔接

已只读参考 M05 本轮 INTEGRATION.md：其 `entry_type=ORIGINAL` 与 `CORRECTION_DELTA` 应分别成为 `CONFIRMED` 与 `ADJUSTMENT`，金额读取 `settlement_amount`，不累加 `confirmed_total_amount`。差额明细必须有新的独立台账行 id 与 snapshot_code，课程和讲师关联继续使用原实体。

M05 当前更正输出的四类课时可能是“修正后完整事实”。M06 的 ADJUSTMENT 课时必须是各类别增减差额，不能把修正后完整课时再次相加。由 M05/总控提供原快照和当前快照的已核对课时增减额，或在适配层按显式合同做新旧各类课时差。缺失值不能当0；费用直接使用 M05 已保存差额，绝不重定价。

替换式统计如要保留旧完整行，必须旧行标 SUPERSEDED、新最终行标 CONFIRMED；不得同时又附加同次差额。不同更正表达只能选一种。取消无金额影响不得擅自写0；按本轮统计政策排除来源已明确取消行。

同一业务记录及其修订链在进入 M06 前必须选择并验证下表的一种表达，不能混用：

| 来源情况 | 唯一可加表达 | 禁止的重复表达 |
| --- | --- | --- |
| M05差额链（首选） | 原确认600仍计入，更正差额−150计入，合计450；后续正负差额继续沿原链 | 同时再计更正后完整金额450；或把原确认排除后只计−150 |
| 完整替换快照 | 旧600为SUPERSEDED排除，新450为CONFIRMED计入 | 再附加同次−150差额，导致300 |
| 未入账/未支付的业务取消 | 整条来源链明确取消，统一不入统计；无凭空负数 | 先排除原行，再生成负数“扣减” |
| 已入账/已支付后的取消或冲销 | 必须由M05提供经核准的完整冲销链及其日期；按差额链保留原600与实际冲销−600，净额0 | 将原600排除并继续计−600；又排除又扣减 |

最后一行不是自动退款/工资冲销授权：M05仍未接正式取消、冲销流程。宿主不能仅用业务单据的当前“已取消”状态覆盖原已入账事实；缺少核准冲销来源时暂停该正式来源的适配。需要M05/宿主保留 original business record id、revision、snapshot_code、replaces_snapshot_code、entry_type、当前head、来源模式与冲销依据；先校验唯一原始行、链无分叉、同一前序只有一个后继，再展开M06可加事实。

当前 `Fact` 是已规范化统计输入，未包含全部修订谱系；核心会拒绝重复统计id/编码，但**不能仅凭不同id的两个数值识别它们是否属于同次更正**。上表互斥和链来源校验是正式适配的硬性前置，尚未接入；不能把本轮演示对平当作已完成跨模块防双扣验收。

### 支付日期仍需真实来源字段

当前核心对计入状态但缺少所选日期的行明确报错，因为无法区分“尚未支付”和“支付日期漏录”。演示中故意保留一条缺支付日期记录展示此校验；只选已完整支付日期的机构可得到支付日期汇总。

正式接入时，宿主必须明确未支付/已支付/信息缺失状态，并仅供给对应可统计的支付事实快照，同时给出未支付/缺失提示；不能静默丢行。分期支付或一次费用分多次付时，要先定义每笔支付的金额、课时归属、去重关联与支付日，不能每笔重复全额费用及全量课时。当前 M05 文档未提供已支付事实合同，故此部分未接入，不承诺已经支持正式支付台账。

## 筛选、对账与权限

`build` 先检查查看权限和请求机构是否为授权子集；空授权集合返回空结果，无“默认全机构”。授权范围外事实不进入结果。来源校验只在授权且被筛选机构内检查重复 id、重复明细编码与课程映射；状态和日期过滤前拒绝重复，防止污染快照。

入选记录缺所选日期无法判断范围时阻断；已知不在时间范围内的行不要求其金额/课时完整。入选后缺选中类别课时、历史金额或原版本则阻断，不按0处理。四类课时同时输出 `hourTotals`，某个非选中类别有缺失则该类总量为null，附 missingRecords；不能输出不完整的部分和冒充总量。空结果各类为0。

`Report`保存同一次计算的不可变明细、讲师排名、机构汇总与规则；所有汇总来自同一明细列表。返回内部对账差额及 matches；不平则停止输出。内部对账只能证明算法自洽，不证明来源无漏数，`compareExpected`用于已核对样例的独立记录数、唯一课程数、课时及金额控制总额，差额方向为计算值减正确值。

`AccessScope`只是宿主可信参数，不能从请求体的 canView/canExport/orgCodes 创建。每次读取与下载必须重新核查用户、机构和权限，必要时重新生成受限快照；不能仅因为旧 Report.canExport 为true就永久允许导出。该纯核心自身不执行真实认证；新只读适配的真实Auth/M01校验及其合成验证见INTEGRATION_TEACHING.md，实际人员权限配置及宿主接入仍归总控。

## 编码导出与追溯

Java输出 DETAIL / TEACHER / ORGANIZATION 三类 UTF-8 BOM、CRLF、RFC4180 CSV。白名单列仅编码、日期、数字、状态与规则；不含数字记录ID、姓名、电话、账号、自由备注或解码映射。每个数据行保留统计版本、日期/课时口径、状态、排名、筛选范围和币种。

必须同时保存 `exportManifest` 的JSON清单：包含规则、有效授权/筛选机构、范围、记录数、四类课时总量和对账状态。空CSV只有表头，清单仍保留完整口径；不得加伪明细行用于放元数据。正式宿主另附服务端数据快照/查询截止版本和导出批次时间，以便以后复现。

CSV保存精确十进制文本，但 Excel 直接双击可能吞掉编码前导零或超过15位数字精度。用户应按文本导入；不使用公式包装“保精度”。后续用户授权自行设计后，已新增四表XLSX默认月报，日常使用优先该整份月报；仍不制作或假定人力结算表。

演示JS导出字段为camelCase，Java导出为snake_case；正式下载以Java及用户授权的默认月报表头为准，不再等待现用模板。演示清单包含mode=demo；正式清单需宿主附可信数据模式，不能将DEMO规则用于正式数据。

## 宿主待接点

1. 独立slug `reports`及其mount仅供内部验证；正式UI沿用现有report导航与pageReport，按末节局部接入，不增加M06导航或移植独立样式。内部demo仍只操作分配root并遵守context.signal与cleanup。
2. 建议一个只读统计端点加三种导出及JSON清单端点，路径由总控最终确定。日期、课时、排名可以来自用户筛选，但版本、状态口径与授权从服务端构造。可缓存同一授权查询结果快照供下载，不能浏览器自己重新读取另一批事实。
3. 在一致的只读事务/快照内取得业务事实、原金额/版本、取消更正状态，避免图表与导出不同批。不会执行任何新增DDL；可复用上游不可变快照/台账。
4. 记录正式统计规则配置版本与依据，后续更改新建版本，不覆盖 `M06-20260921-1` 的含义。支付事实、更正链互斥校验、课时增减额、日期/折算与授权仍为接入前置项；45分钟课时单位和默认月报表头已确定，不再等待模板。
5. 总控统一做跨模块权限和数据验收及全套测试；本任务仅跑M06专项。

## 已验证

2026-09-21：73项Java核心检查、32项JS模型测试、147组Java/JS对照通过，其中123组输出一致、24组一致拒绝缺支付日期；对每组正常结果的三类CSV回算课时金额通过。支持重复/错误/越权、跨月日期、负数更正、精确小数、三种并列策略、空结果追溯、四类分别总量等场景。

内部演示交互验收结果写入 coordination/modules/M06/STATUS.md。正式原系统接入、真实业务数据、权限和跨模块更正/支付流程尚未验收；默认月报格式已由用户授权本模块自主完成。

## 用户授权的默认月报（本轮追加）

月报为一个XLSX文件，包含月度概览、讲师排名、机构汇总、明细对账四表。概览有记录/课程/讲师/机构数、四类课时各自合计、历史课酬、所用统计口径及差额。排名及机构表包含当前选中的课时类别和历史金额；明细列出编码、所选日期、状态、课时、历史金额及原费用版本。不导出姓名或数字业务ID。

`ManagementReportsWorkbook.export(report)`返回真实XLSX字节；内部通过受权限约束的exportManifest取得有效机构，下载前仍需宿主重验当前权限。响应类型为 `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`，附件名包含月份或自定义日期范围。全程只读取报表快照，不重定价；不含公式、宏或外链。计数和不超过15有效位的普通金额为数值型；编码及超过Excel精度的值为精确文本。空结果也保留四表、表头和口径。

内部演示增加自然月选择、上月/下月和整份导出按钮，验证跨年、闰年及自定义区间。非整月时清空月份并标自定义范围，不冒称月报。`monthly-workbook.js`提供demo专用 `exportMonthlyWorkbook(result, model)`，仅接受DEMO规则，不可用于live回退。生成中切换条件/卸载会丢弃旧导出。

新文件：`src/com/training/ManagementReportsWorkbook.java`、`scripts/M06WorkbookTest.java`、`scripts/M06_monthly_test.mjs`、`web/modules/reports/monthly-workbook.js`。M06_check.sh已纳入新增核心和工作簿验证。

新增验证：Java工作簿248项检查、浏览器工作簿7项测试通过；月份纯函数6064项边界断言通过。独立XLSX读取器核对Java和浏览器生成文件的ZIP、四表、编码、长数值及金额。内部浏览器已核验闰年2月29日、12月到次年1月、非整月提示、重置和空月导出反馈。用于交付样式查看的合成样例已渲染逐表检查，金额7600，实际/计酬19课时，预计/计划18.5课时均与明细一致。

## 正式原系统局部接入（按用户最新纠偏）

已只读定位原系统，不改这些共享文件：

| 原入口 / 函数 | 复用项 | 必须增加的局部功能 |
| --- | --- | --- |
| `web/app.js` 的 `report` 导航、“经营洞察”、`pageReport(c)`（当前约3930行） | 现有 `report-hero`、刷新/打印、`module-summary`、图表与报告文本 | 在现有页增加紧凑月份/日期基准/课时类别筛选及“导出月度报表”，使用原btn、toolbar、select-filter、card/data-card；保留原合同/回款/成本内容 |
| `pageReport(c)` 内师资交付效率区域 | `panel-head`、`chart-box`、`renderTable`与既有移动表格 | 展示讲师排名、机构汇总；用原filter-tabs在同一局部区域切视图，点击明细使用原openModal，无独立大标题页 |
| `pageFees(c)`（当前约2458行） | 原项目筛选、课酬列表、发放状态/日期、项目上下文 | 只作为支付来源及必要的“查看对应明细”接点；不要改变自动计算、发放按钮或重算旧费用来生成统计 |
| `renderTable(cols, rows, actions, kind)`（当前约707行） | 原`.table-wrap`、`.tbl`、`data-label`、对齐/空状态 | 传当前已授权服务端结果；精确金额字符串直接格式化，不转Number重新累计 |
| `routeEpoch` / `isRouteCurrent` / `state.filters.report` | 已有导航销毁保护与筛选记忆 | 等待统计/下载返回后确认仍是原页、原筛选；不能把前一月份结果回写到新页 |

接口仍由总控设置，不能以旧 `/stats/overview` 的无明确版本汇总代替本模块事实计算。新月度统计按 ManagementReports.build 的单一授权快照输出；原页合同等其他经营指标继续沿用原来源，不能因新增课时筛选而悄悄改变它们的口径。机构筛选列表只取服务端授权机构。

正式入口继续使用原 `web/index.html` 与 style/studio/ledger/v10/v13 加载链，不引入reports/styles.css，不新增开发模块导航或配色。现有独立demo及本轮月选择仅用来验证输入、生成与销毁行为；不得拿该页面作为正式UI整体效果。总控接入后应真实浏览器核查原样式资源、月份交互、表格/弹窗及下载，再更新正式UI验收状态。
