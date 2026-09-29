# M05 排课交付与课酬：原系统局部接入说明

2026-09-23修订历史交付：新增真实权限与完整修订链校验的只读history，按相邻原始值对照并分页。原授课、批准政策预览和M08授课来源已由总控整合并保持；本轮字段及接线见 [DELIVERY_HISTORY.md](DELIVERY_HISTORY.md)。新专项849项通过，原UI/真实HTTP由总控并行联调。

2026-09-22更新：已新增 `DeliverySettlementIntegration.java` 持久化适配，真实Auth/M01授权、事实修订、正式配置、冻结快照、差额台账、幂等与旧入口门禁均有独立H2验证。完整HTTP/字段/初始化/门禁合同见 [INTEGRATION_PERSISTENCE.md](INTEGRATION_PERSISTENCE.md)。总控复核修订已补正式配置前核对门槛、实时M01操作能力和原弹窗组件 `web/modules/delivery-settlement/index.js`（见 [DELIVERY_COMPONENT.md](DELIVERY_COMPONENT.md)）。公共路由、Db调用点及原页面由总控接入，本模块未改这些共享文件、未启动生产迁移。

正式功能仍进入既有“课程与排期”和“课酬发放”，不增加M05用户导航、不替换原界面。内部demo不能当作正式UI。下方首轮宿主建议中已实现的持久化部分，以新合同为准；支付流水、正式CSV批次及公共页面验收仍未完成。

用户已明确“直接做，不确定再问”和“60分钟=1.33课时，可以折算”。不再把人力空白表或3个已核对算例当作开发前置要求；先用已有系统编码清单，后续遇到实际差异再做适配。业务疑问使用单个实际场景提问，不给用户布置技术材料清单。

2026-09-22批准依据更新：用户已明确“按这个执行”。新增 `DeliverySettlementApprovedPolicy` 与 `/policy-preview`，不再以原件征求意见稿标注整体阻断明确费率预览。来源状态和本次批准分开保存，不追溯改价。原授课记录/核对/完成及旧入口保护已由总控整合；本轮只交付新预览接口与可挂载组件，详见 [APPROVED_POLICY_PREVIEW.md](APPROVED_POLICY_PREVIEW.md)。

## 交付与验证入口

- `src/com/training/DeliverySettlementIntegration.java`：持久化适配、实时操作能力与旧入口门禁，详见新接入合同。
- `web/modules/delivery-settlement/index.js`：供原排课页按需挂载的授课记录/核对弹窗组件，复用原样式及宿主函数，不启用正式结算或支付。
- `scripts/M05IntegrationUI.test.mjs`、`M05IntegrationBrowser.mjs`：组件交互与实际原弹窗/CSS浏览器专项。
- `scripts/M05Integration-check.sh`：真实Auth、独立H2持久化专项验证，编译实际共享源码至唯一临时目录。
- `src/com/training/DeliverySettlement.java`：Java 17、零外部依赖的纯输入/输出核心；包内restoreFrozen只恢复数据库冻结依据，不重新定价。
- `src/com/training/DeliverySettlementPolicy.java`：保留原来源抽取与旧候选API，不表示新批准版本不可用。
- `src/com/training/DeliverySettlementApprovedPolicy.java`：用户批准执行版本、条件试算、开发总池、正式缺项及历史保护；`scripts/M05ApprovedPolicyTest.java`验证。
- `web/modules/delivery-settlement/policy-preview.js`：原页面只读预览组件；`M05PolicyPreviewUI.test.mjs`与`M05PolicyPreviewBrowser.mjs`验证。
- `src/com/training/DeliverySettlementHours.java`：分钟÷45、课时两位小数及独立实际课时修订；保留换算依据。
- `web/modules/settlement/conversion.js`：可被原表单复用的课时纯换算函数；不计算课酬。
- `web/modules/settlement/index.js`：内部逻辑验证用`mount(root, context)`，不作为正式UI交付；详见 `FRONTEND.md`。
- `scripts/M05DeliverySettlementTest.java`、`scripts/M05_check.sh`：独立临时目录编译/测试，不写共享 out，不跑全套。
- `scripts/M05DeliverySettlementDemo.java`：编译后运行 `com.training.M05DeliverySettlementDemo` 输出 READY 与 NOT_CONFIGURED 两条JSON；传 `--csv` 输出合成编码结算CSV。仅写标准输出。

在 app/ 运行 `bash scripts/M05_check.sh`。传 `--with-m06` 可另做6项只读桥接核对，不改M06源码或跑其全套。基础计算核心88项、候选政策83项、新增分钟换算66项、Java/JS实际对照26项、桥接6项通过；浏览器验证记录见 FRONTEND 与 STATUS。

## 核心行为与支持范围

`Hours(estimated, planned, actual, payable)` 四项分别保存；未知为 null。预计/计划缺失不阻止结算，实际/计酬/核对缺失阻止确认。不会从分钟、计划或实际推定计酬课时，也不会把历史 DOUBLE fee_rate 当正式规则。

用户和附件已明确1课时45分钟，后续回复明确60分钟可折算为1.33课时。`DeliverySettlementHours.currentUserRule()`将分钟÷45保留两位课时；HALF_UP是本轮明确记录的常规实现选择，不能声称是原文件规定。`convert`保留原始分钟和规则/舍入依据，例60→1.33、90→2.00、67.5→1.50。`applyToActual`只生成新事实修订并更新实际课时，保留预计/计划/计酬，清空原核对标记以要求重新核对；返回的ActualUpdate连同conversion一并保存。换算不自动改计酬课时，不据此推定金额舍入顺序。

当前计量值均为“课时”，不是自然小时；规则的`minutesPerClassHour`随确认依据冻结并输出到JSON/CSV，原始分钟和转换版本也须随授课事实修订保存。宿主须核对源事实课时单位与转换定义一致。附件批准费率及适用条件详见 `POLICY_REVIEW.md` 和 `DeliverySettlementApprovedPolicy.java`。

`calculate(Fact, RuleVersion, RateVersion)` 显式接收版本；不自行选“最新版本”。规则、费率必须同时给出版本编码、依据编码、生效起日、数据模式；费率绑定规则版本并严格匹配等级、时段、形式、课时单位。规则另要求币种、金额小数位、舍入模式、日期依据、计算方式、舍入范围。缺失或不匹配返回 `NOT_CONFIGURED` 和 issue codes，金额为 null。事实缺失返回 `INCOMPLETE`。完整时为 `READY`。

目前仅实现显式选定的 `PAYABLE_HOURS_TIMES_RATE`、`SERVICE_DATE` 和 `PER_LINE`。这三个值是引擎支持能力，**并非已批准业务政策**，传其他值或不传均阻断。有效区间采用 `[effectiveFrom,effectiveUntil)`，无结束日表示开放区间；若材料写“有效至某日（含当日）”，适配时必须明确转换为次日排他边界。跨日、多行合并后舍入、封顶保底、税费、阶梯等均未实现，不得套用本公式代替。

输入数量/费率用十进制字符串或 BigDecimal；拒绝 Json.num、Double、Float 及JSON数字的隐式转换。金额与课时输出为字符串，null保持null。技术输入限24位有效数字、最多8位小数，超限报错而非舍入；金额输出小数位必须显式为0..8。技术限额不代表业务上限。

公式先精确相乘，再按已选规则对该行金额舍入。`UNNECESSARY`遇到需舍入结果时返回明确问题而不产出金额。显式填写的0费率/0计酬可算出0；未配置不等于0。

## 确认、核对与更正

`Verification`保存核对人编码、时间、依据编码。宿主必须从已授权核对事件创建，不接受浏览器自报“已核对”。`confirm(result,currentFact,snapshotCode,at,access)` 验证 READY、事实/修订一致、确认权限和组织范围以及时间顺序，然后生成不可变 Confirmed；其中冻结完整事实、费率、规则、引擎版本、未舍入金额、最终金额及操作者/时间。升级规则不会修改历史对象，也不重新计算原结果。

`correct(previous,revised,currentFact,snapshotCode,reasonCode,policy,at,access)`要求修订递增，同一记录与授课/人员/组织编码、同币种/数据模式、独立更正权限，并显式提供带依据/生效区间的更正规则。当前仅支持 `REPLACEMENT_DELTA`：保留原快照，新增修正后的总额及“新已舍入总额减原已舍入总额”差额，可为负。演示政策也是合成，未获正式规则时更正功能不得开放。取消规则未配置，**尚无自动取消计酬或默认置零逻辑**。

`Access`是可信宿主适配参数，包含 actorCode、权限集合和组织编码集合。核心有范围检查；新持久化适配现已接真实Auth/M01并独立验证，原API路由接入后的整体验收仍待总控。禁止把请求体的 grants/org scopes直接构造成Access。`calculate/view`自身不做授权；读取事实和试算前由宿主验证访问范围。CONFIRM、CORRECT、EXPORT的业务角色映射待总控确定。

## 编码导出

`encodedCsv(entries,access,mode)`按白名单输出编码、数字记录ID、四类课时、原始与已舍入金额、规则/费率/引擎版本、日期、舍入依据、核对及确认审计。第一行是英文技术字段名；尚未套人力表。编码字段仅支持安全ASCII传输格式（字母/数字开头，后续字母、数字、点、下划线、冒号、横线，总长不超过96），不产生正式编码、不转数字、不查姓名。缺编码阻止READY；不存在姓名、电话、账号、自由备注、解码表导出。

每行含 `data_mode`。SYNTHETIC_DEMO不能冒充CONFIGURED。原始行 `entry_type=ORIGINAL`，`settlement_amount`为原确认金额；更正行为 `CORRECTION_DELTA`，`settlement_amount`为差额，`confirmed_total_amount`是该次更正后的完整金额，`replaces_snapshot_code`指向原依据。**结算合计只能累加 settlement_amount；不要把新旧 confirmed_total_amount相加。**

拒绝本批重复快照、重复记录修订、同一记录多个原始结算、可见或隐藏祖先链分叉。共享祖先需使用同一不可变实例，未来持久层重建时按snapshot_code统一实例化。核心无法判断某条差额是否此前已在其他批次支付；正式导出批次、幂等、已发放标记必须由宿主管理。

## 首轮宿主建议（持久化已实现范围以新合同为准）

1. **不增加settlement独立用户导航或把mount演示替换正式页面。** 在原`pageDispatches`与`pageFees`局部接入下列字段/核对控件，继续使用原导航、项目上下文、列表、筛选、弹窗、状态与v13样式。demo仅内部验证。
2. API由总控决定路径；建议读事实、试算、核对、确认、更正、编码导出分离。请求金额/课时/费率用JSON字符串，版本由配置库读取，不允许客户端伪造规则、权限或核对依据。
3. 在 `Api.MUTATION_LOCK` 与 `Db.transaction` 内读当前事实、授权组织、版本依据，重算并确认。确认需要锁定/比较事实修订；更正需比较数据库当前head恰为previous。全部失败时事务回滚。
4. 数据库建议保存：事实记录与四项nullable十进制；不可变规则/费率配置；不可变完整确认快照（含历史engine_version）；每事实唯一current_head；确认/更正操作的幂等key与请求摘要；导出批次及条目。规则同一version_code不得覆盖内容，调整新建版本。快照以数字record_id关联现有业务，不以编码或姓名替代主键。
5. 至少建立 `snapshot_code`、`(record_id,revision)`、非空`previous_snapshot_code`唯一约束；current_head对record_id主键。首次确认要求不存在head，更正比较旧head后CAS更新。相同幂等key+相同内容返回原结果，不同内容拒绝。核心是无状态纯服务，不负责跨请求幂等。
6. 以CLOB保存完整十进制字符串快照可保留原始精度与所有依据；若增加查询列，课时/费率建议至少DECIMAL(32,8)，原始乘积至少DECIMAL(64,16)，金额至少DECIMAL(64,8)。适配器init现提供独立M05表的幂等DDL，仅由宿主启动时调用；本轮不执行生产DDL。不要将金额落DOUBLE。
7. 导出前再次做权限/组织范围检查、批次去重与发放状态验证；保存该批准确行清单，重试返回同一文件/批次结果。M06/M08读取确认快照及更正差额，不自行重算定价。

## 与M06的更正口径对齐

按最新MASTER，M06排除取消/被替换业务记录，计入CONFIRMED与ADJUSTMENT正负明细。M05的完整旧/新快照用于审计，**不能把它们直接当作两条完整统计事实**。

`reportingContribution(Confirmed)`提供同一授课日下的最小适配数据：原始不可变结算分录状态为CONFIRMED、fee为原额；更正分录状态为ADJUSTMENT、fee为本次差额，四类课时分别传“新值-前值”。不变的已知课时传显式0；任何一侧未知则该差额为null，不伪造0。这个方法不重新定价。

- 例：原实际2.5、计酬2、金额600；更正后实际3、计酬2.5、金额750。统计输入为原CONFIRMED(2.5,2,600) + ADJUSTMENT(0.5,0.5,150)，得到(3,2.5,750)。预计/计划未变则差额0，不能再次传完整预计/计划。
- 旧**审计快照/待替换业务行**可标SUPERSEDED并从原始业务列表排除；已进入统计的**原始结算分录**必须保持CONFIRMED。禁止一边删除600基数、一边只加150；也禁止保留600再把完整新额750加进去。
- 如果宿主选择“仅最新完整快照”的另一种读取方式，则只能传最新750和最新四类课时，旧完整快照SUPERSEDED排除，同时不得再附加150差额。两种输入约定不能混用。本轮提供和测试的是前述“原始分录+差额”方式。
- M06的数字id应是持久化结算分录ID，每次更正独立；`recordCode`取snapshotCode。M05的授课recordId另外输出为sourceRecordId，不能让两条分录共用M06 id。courseId/courseCode由宿主按已有业务关联补齐，禁止猜造。
- TEACHING日期来自授课事实。PAYMENT日期必须来自实际支付记录；附件“纳入次月工资”不能当作实际支付日，未支付时不得伪造。付款后的更正差额按其自身支付事实归期。
- 跨授课日期更正会影响按日/跨月统计；当前单差额适配显式阻断，需要宿主提供原日期冲回和新日期重记的成对事实后再接入，不静默归入新日期。
- 未确认取消无确认分录；已确认/已发放后的取消处理待课酬政策明确，不靠把历史分录标CANCELLED来抹去已付金额。

`M05StatisticsBridgeTest`已用现有ManagementReports做6项合成只读联验：600+150=750、四类课时分别归到最新总值、明细与合计一致。未改M06文件。

## 原候选政策API（兼容保留，不是当前批准版入口）

`DeliverySettlementPolicy.snapshot()`保留原文件名、SHA256、草案状态、未知文号/发布日、45分钟课时与原文4×4费率表；技术candidateVersion不可当作正式文号。`lookupCandidateRate(RateRequest)`仅查询来源费率，研发成员授课/独立开发按讲师；合作开发仅可参考主负责人总池费率，分配功能UNSUPPORTED，研发主负责人组合待解释。

`assess(RateRequest,VerifiedFacts)`只检查获聘前后、年度计划OR客户付费、开发重复率≤50%及此前是否已发开发课酬等局部门槛。该旧API结果canConfirm恒false，formalBlockers保留抽取时草案/发布日期状态，新批准预览不继承这些阻断项；局部门槛通过不代表验收、核准、工资税费或完整发放资格已满足。获聘事实应来自有当时聘任证据的可信宿主记录；M04现等级/在库日期不替代历史聘任。已付开发课件需由持久层按成果版本查重。旧制补发分支始终要求旧版本及原资格证据，不使用新表。

## 已收到及待补材料

已收到《金尊公司内部培训师管理办法2.docx》并核对45分钟课时、等级/日期分类费率、研发成员特殊标准、开发一次性支付、资格条件和旧制补发。用户已明确按所提供文件执行；原始发布日、正式文号继续空置而不禁用明确费率预览，金额舍入另列具体缺项。新辅助类产出精确未舍入试算与条件提示，不自动确认结算。详见 `POLICY_REVIEW.md`。

用户不需要补整套材料才开工。分钟折算现已明确并实现，不再询问45分钟或索要算例来确认基本数学。没有人力空白表时先使用系统编码清单，只有以后实际报送格式不一致时再适配；自建合成测试验证实现，真实核对例以后有需要再取单例。

具体业务的新旧制适用依据、金额舍入及取消/已付更正等，仅在对应操作需要时核实。保留原稿来源事实和单独的用户批准执行版本，批准日期不冒充原始发布日期。

## 原界面局部接点（总控执行，本模块未改公共文件）

只读核对当前`web/app.js`后，接入点如下，函数名比行号更稳定：

| 原有入口 | 复用控件 | 必要局部变化 |
|---|---|---|
| `pageDispatches`（约2064行），“课程与排期” | `card data-card`、`toolbar`、`#flt-proj`、`renderTable`、`openModal/renderForm` | 原排课字段作为计划口径。新增实际授课分钟与折算预览；`hours`当前step=0.5不能验证1.33，相关新课时字段支持0.01，保留原操作流。不要从开始/结束时间猜扣休息时长。 |
| 同页“完成”按钮（约2123行） | 原确认弹窗与完成接口 | 弹窗局部增加实际分钟/课时核对和独立计酬课时。后端新事实修订保存Conversion依据，核对完成后再确认；不要把预约确认当作授课核对。 |
| `pageFees`（约2458行），“课酬发放” | 原项目筛选、`#calc-btn`、`openModal`、结果表、发放操作 | 仍从“自动计算课酬”进入，显示显式适用费率、计酬课时、规则依据和缺项；改后端计算服务，原表格局部增加依据查看/更正入口。已确认结果保留，不重新定价。 |
| `pageFees`原表格/工具栏 | `renderTable`、`tag`、原按钮样式 | 编码导出放原工具栏；引用M05正式服务结果与不可变快照，无需新落地页。 |
| 项目工作区原课程安排/财务区（约1738行起） | 现有项目上下文及跳转 | 保留导航和汇总布局，只让计划/实际/计酬标签与对应数据相符；不把四类课时相加。 |

新分钟字段从输入框读取字符串，传`convertMinutesToClassHours`做即时预览，提交仍用十进制字符串由Java复算。`collectForm`现有number分支会转换Number，需总控仅对新分钟/精确课时字段保留字符串，不能因此改变全部已有数字字段。不要用已有`money`/`num`或旧`teacher.fee_rate`当计费引擎；不改界面风格，只替换相应数据来源。

当前`/fees/calc`会写入课酬，不能把“查看候选政策/试算”直接接到这个旧写入动作。应先完成服务端明确规则及核对接入；候选表仅复用只读说明/详情控件。已发送后的`openDispatchEditor`目前只放行材料状态和备注，实际授课核对需在对应完成/核对弹窗局部接入，不能因复用旧编辑白名单而丢掉新字段，也不能放开任意已确认数据编辑。现有费用汇总使用Number加减的部分，接入新结果时需一并读取服务端汇总字符串，不能只改明细金额显示。

原系统接入后，总控还需检查现有样式资源加载、弹窗实际操作、1.33课时输入、项目筛选/移动显示。当前内部demo截图与单测不等同于这项正式视觉验收。
