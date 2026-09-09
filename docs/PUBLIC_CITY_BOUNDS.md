# 城市级铁路时间区间契约

`PublicCityBounds` 是现有公共证据入口的纯辅助模块，不新增第四套库、配置、审批或跨库优先级。已接入 `PublicTransportEvidence → CityTravelReference → TravelMatrix → NearbySelection/DispatchPriority`，仍使用原 public 文件入口及完整性锚。业务界面应按下文的类型字段展示，不能假定每条路线都有精确整数分钟。

## 接口

- `duration(String)`：返回 `Duration`，保留原文、类型、上下界与开闭、精度及推断提示。
- `validateEvidence(content, today)`：验证单向城市级原文和来源元数据，返回类型化区间；不支持或不满足抛出 `IllegalArgumentException`。
- `Duration.strictlyUnder(limit)`：只有整个区间严格小于阈值才为真。`overlaps` 仅提供区间交集比较，不能单独裁决路线或来源冲突。
- `bothStrictlyUnder(outbound, returning, limit)` 和 `validatePair(...)`：两向各自校验；后一接口亦检查城市/省份正反对应。只产生 rail/unknown，不产生无铁路或航空资格。

`duration_basis=public_city_duration_bounds`，`scope=city_to_city_rail_reference`，仍在 `public-city-travel-reference-v1` 的原证据快照/修订链中使用。原 hash 链、使用依据、参考复核、最新撤销否决及跨库冲突检查不能跳过。独立测试中的 `validatePair` 不是完整业务批准入口。

## 精度与严格边界

| 表述 | 保存的类型/区间 | 是否能证明 <240 分 |
| --- | --- | --- |
| 最快62分钟 | exact，[62,62]，只是公布数字 | 可以 |
| 控制在2小时35分钟以内 | upper_bound，(0,155] | 可以 |
| 不足4小时 | upper_bound，(0,240) | 可以 |
| 4小时以内 / 不超过4小时 | upper_bound，(0,240] | 不可以 |
| 3小时40多分钟 | open_interval，(220,240)，显式语言推断 | 采用下述政策时可以 |
| 约3小时40分钟 / 3小时左右 | approximate_unknown，无严格数值上界 | 不可以 |
| 孤立40多分钟 / 3个多小时 | 只有开放下界 | 不可以 |

显式政策 `zh-within-stated-hour-v1` 只解释 `H小时M多分钟`，其中 `0<M<60` 且 M 为整十分钟：保守区间 `(H*60+M,(H+1)*60)`。不假设“四十多”低于五十；不把这一推导冒充来源公布上界。每条采用该政策的证据必须明确选择 `language_policy`。输出 `precision=colloquial_within_hour_inference` 和可见 `inference_notice`，保留原短语。单独“多小时”、约数、累计或往返时间不能套用这个政策。旧研究记录原精度不改写。

UI 应渲染 `display/original_text`、类型和推断提示；只有 `kind=exact` 才有 `reported_minutes`。上界、约数和区间不写入旧 `reference_minutes`，也不能用整数上界充当全网最短值。两条区间阈值相交未知不取较小者；跨来源范围/时期冲突由原总入口保守裁决。

## 原文与日期

支持有界语法：一个起点一个终点的完整铁路时长断言；单一起点“至 A、B 最快分别 X、Y 可达”的同序、等长明确对应；同一条路线过去驾车与现在动车的完整对比句。不能丢掉否定、未来、临时、累计或上下文前缀，不能换乘相加，不能将城市生活圈拆为全部城市对。并列城市缺一时间、目标错位或重复城市均待核。

### 段落与连续行上下文绑定 v1

`PublicCityContext` 是此类的纯辅助模块，不新增数据库、配置入口、许可声明或审批系统。证据仍使用 `duration_basis=public_city_duration_bounds`、同一 public v1 哈希链和复核流程。仅支持以下明确结构；不声称能解析任意文章或任意 HTML 表格。

- `context_binding_type=parallel_destination_durations`：同一段“目前 A 至 B 时间、至 C 时间”的共享起点、逐项目的地结构。保留并复算所有项目的位置与所选项目索引；缺时长、重复/未知目的地、跨句/跨段、错位、另一时长背景均拒绝。可保留一段无其他时长的、明确已实现稳定运营的背景，以及限定的常态化通达结语。不会把群体生活圈拆成方向，也不会把多起点无对应关系自行排列组合。
- `context_binding_type=departure_heading_direct_station`：连续区块中的“A 出发”标题、A 的站点、开往 B 的高铁/动车、时长、直达 B 站。整个区块必须被一个产品语法完整消费；不能从另一个标题、配图后的其他服务或其他段落借时间。空行只作为原页面展示换行保留，不能跳过中间内容。站点仅支持原文标题和目的地直接对应的“城市名＋可选单字东/西/南/北＋站”，不对汉口等异名站自行猜测归属。
- `context_binding_type=dated_opening_current_route`：完整“日期＋某高铁已经开通的陈述＋从 A 到 B 当前最快旅时”的运营报道，可保留限定的区域通道结语。日期、开通背景、城市和时间均回切原文；只有月份时保留月精度，不补具体生效日。相对今年按真实发布日期绑定；未来月份/日期、计划开通、往返累计、反向错配、其他路线与附加停运说明均不通过。不再因为已开通背景出现在同句就删掉前缀或丢弃正确方向事实。
- `context_binding_type=operating_station_route_list`：完整已开通首发报道或当前运行图段落中的“站 A 至站 B 最快 X 可达”。显式选择 `selected_from_station/selected_to_station`，全部时长路线及源位置保留；不把相邻路线的数字、开行列数、首发发车钟点绑定为目标时长。首发背景必须与所选方向一致，日期不晚于发布日。精度仍是公布站间参考，不是全辖区最快或门到门保证。
- `context_binding_type=explicit_service_roundtrip_narrative`：完整来程及 1—6 个返程服务段落，分别有服务编号、出发／抵达站点、出发／抵达钟点；来程公布时长必须等于自身钟点差，返程最短公布时长必须等于返程服务中的最小值。用 `selected_service_id` 与两个站点共同选择，不能让慢返程继承快返程数字，不能让返程复用来程。全部服务均保留各自钟点、原文范围和差值，不能拼班次、镜像反向或推断跨日。仅此完整语法允许其固定“稳定往返”引言和定性的生活圈结语；后者不能成为时间依据。旧单向语法仍拒绝往返总时长。

后两类由纯辅助 `PublicStationContext` 处理。普通城市的原文站点角色可使用明确的城市名字面前缀，映射依据写为 `literal_city_prefix_in_station_role`。香港、澳门、台湾以及异名站不能只凭前缀猜测，须提供 `station_city_mappings`：公开政府／运营方来源、原句及完整断言范围、实际核读日期、已核对标记与明确站点／城市关系。目前另支持“某站是高速铁路（某城市段）的总站”这一个完整归属句法；其他异名关系保留待核。没有发布日期的稳定站点介绍须如实写 `source_date_status=not_shown`，不能伪造发布日期。映射原句与元数据一并参与外层证据 hash，绑定保存映射 hash；映射被撤销、未核对、越期或城市/站点不符均拒绝。

跨境证件、通关、市内接驳与授课安排资格仍是另外的调度核实条件。跨境站间铁路时间可以用于投标前筛选，不代表持有合法证件、不代表具体日期有车、不自动安排讲师出行。原来源的 `scope_note` 保留这些边界。

`PublicCityContext.describe(content)` 生成待核对绑定；调用者必须将完整结果作为 `context_binding` 保存于证据内容。它包含完整片段的 `context_canonical_sha256`、UTF-16 范围、站点/城市角色、目的地全表及各个原始 token 的 start/end/text。校验时重新解析原文并比较整个绑定，不能靠手写 offset、station/city 或选中索引改变事实。每个 token 必须是原文连续片段，不能把跨行城市名或时长拼起来。`original_content` 不被改写，完整片段及标点/换行仍在证据快照中参与哈希。

绑定只证明“该数字与该方向在原文中对应”，不证明网页真实性、许可、车站实际地理归属或实时服务。人工/assistant 的来源核读仍须如实记录；hash 是防误改，不是授权或数字签名。原文包含未来、否定、临时、换乘、别种交通方式等不在本次肯定铁路结构内的内容继续待核。旧精确分钟路径不变。

城市叙事里的裸“1小时的快速通达格局”即使可正确绑定，也不自动生成精确 60 分钟或小于 240 的数值上界，返回 `city_context_hour_narrative_precision_pending`；原文“约1小时”按约数保留无确定上界。明确分钟、明确上界或现有受控区间规则仍按原精度处理。绑定成功、单向证据通过和双向业务准入是三个不同结果。

来源仍必须使用公开事实依据，不能伪填许可/人工批准。政府、运营方和有明确运营方采访归属的官方媒体可进入单向校验；官方媒体额外要求 `attribution_basis=transport_operator_statement`、`transport_operator_attribution_checked=true` 与实际归属说明。`transport_context_checked` 和 `scope_note` 记录城市级范围核对，不代表所有站点或门到门出行。

发布日期和研究日分开，原发布日期距比较日超过365天待复核。这里只接已运营陈述；若实际生效日已知，必须不晚于发布日期；不知道具体旅时生效日可留空，不能填线路多年以前的开通日冒充新旅时生效。生效前调图预告暂不走此分支。复核期限仍由现有参考元数据的90天默认/180天上限控制，本类不延长期限、不加载文件或写完整性锚。

365 天是本系统当前的来源复核策略，不是“旧资料已经失真”或“该城市不可达”的结论。长期参考资料可保留原日期；后续可通过近期调图/运营确认材料核对历史通达关系是否持续，而不是强制重新找到一篇逐字重复分钟数字的新闻。这需要独立、可追溯的持续适用性证明契约，不能在本次绑定补丁里修改旧日期、延长有效期或仅凭本次阅读日重新批准。

## 接入边界

`PublicTransportEvidence` 新 duration_basis 分支调用本类；`CityTravelReference` 仍验证证据ID/SHA/方向/修订链，并重新推导比对 reference leg 内的 `duration_bounds`。typed leg 严禁旧 `minutes/reference_minutes` 字段，防止区间伪装为精确值。旧精确路径不改变。

`TravelMatrix` 按完整双向参考择源，保留所有候选来源，不拼接两库的有利方向。两向日期都更新才能替代不同取值的旧对；同日不相同的精确/区间或交叉新旧方向仍待核，不能以区间重叠合并成更窄结果。区间包含/跨越阈值时不靠另一个较有利的数字掩盖不确定性。撤回、显式来源禁用、hash/区间篡改仍否决回退；未审核/单向缺失等普通未知不否定另一有效库。来源原发布日期参与公共资料新旧比较，研究阅读日不充当交通现状日。

`NearbySelection` 只将通过两向严格阈值的结果列入铁路初选池。同城由既有地区校验独立判断，不能生成0分钟铁路证据。全部就近铁路候选继续按模型分、同分按等级排序并保留末位同分，不依据区间上界产生虚构距离分。typed 候选 `dispatch_fit.priority_score=null`、`time_score_applicable=false`；目录的 `priority_score/tier` 亦为 null，只给“铁路就近池·城市时间范围”说明。不得凭慢样本或本类未通过就启动航空。

## UI 输出契约

status 仍为 `city_transport_reference`，reference_basis 仍为 `public_city_reference`；新增 `duration_representation=typed_city_bounds`。顶层 `outbound_duration` 与 `return_duration` 分别带 `kind/original_text/display/lower_minutes/upper_minutes/lower_inclusive/upper_inclusive/precision/inference_notice/language_policy`，`reported_minutes` 只有来源直接公布的 exact 才非空（同车时刻差另用 `calculated_minutes`）。`outbound/return.duration_bounds` 保留同一结构，旁边保留各自 source URL/原发布日期/研究日/范围说明、evidence SHA；顶层修订、审核日期及完整性头照常保留。

typed 结果不生成 `outbound_reference_minutes`、`return_reference_minutes`，不能做 `Number(null)||0` 或取 `upper_minutes` 展示为“全程N分钟”。优先原短语展示，例如“去程：3小时49分；返程：3小时40多分钟”，另标“城市级参考”和口语区间的推断提示。门到门、接驳、具体出行日仍未确认。`near_threshold` 是复核提醒，不改变公开严格边界规则。

## 回归与真实制备范围

`PublicCityBoundsTest` 243项纯语法/证据对偶；`PublicCityBoundsIntegrationTest` 56项同链集成、区间篡改、跨库日期/撤销/阈值、专业排序和临时目录加载测试。另有原8组交通纯回归492项通过。它们不读取实际研究库或讲师记录，不等于全国时长准确率。

本轮另在工作区私有目录制备拉萨—林芝新 public artifact：两篇公开来源重新核读后独立绑定，去程229与返程3小时40多的推导区间经原制备器/loader/TravelMatrix/合成候选铁路池两个方向验证。原研究与未准入 source-check 文件不改；新增库记录 assistant 的真实核对与公开事实依据，不冒称人类或许可，尚未发布生产。此例证明城市级证据能完成接入，不代表全国矩阵核齐。

测试全部是 `synthetic.invalid` 合成来源，不读取实际研究库、简历、模型或生产。任何真实制备需重新正常阅读原页，保留少量原句、真实日期、适用范围及推断依据；原研究底稿不升级批准。

新增 `PublicCityContextTest` 覆盖段落/连续行同源绑定、全项目位置、去返独立、小时精度待核、否定/未来/跨段/错站/错位、日期和授权门禁、绑定篡改，以及原 public hash 链到 `TravelMatrix` 的双向合成回归。它不改旧测试预期，也不代表真实全国时长的准确率。

站点/独立服务补丁后，该测试从 140 项扩到 262 项，原有检查保留；另外 11 组交通回归一并通过。真实香港—广州和广州—湛江原文在私有目录另制备，不写入合成测试或公开仓库：独立方向为 77/72 与 92/92 分，香港另一返程服务的 78 分单独保留。原始研究、旧失败和旧 artifact 不改，新的实际制备文件经双向原链、`TravelMatrix` 及私有合成候选池复核。它只新增这两对参考，不代表全国核齐。

## 正常运行图连续停站与实际乘车报道

`published_diagram_same_service_stop_chain` 保存完整新增动车产品块和独立的同页新图生效通知。只接受明确G/D/C同一列车的连续箭头停站序列：首站单时刻为发车、末站单时刻为到达，中间站须明确到/发双时刻；`6:35/40` 的后一个时刻仅继承同站到达时刻的小时，原始span仍是`40`。不推测跨小时/跨日，不允许重复站点、错序、换乘、不同列车拼接、未知中间时刻角色；取所选起站**发车**到所选终站**到达**的差值。

该语法必须是 `service_state=published_schedule_baseline`、`schedule_basis=regular_published_running_diagram`，同URL/同发布日期的明确实施通知与 `effective_from` 相符且研究时已生效。它不把开图前新闻改成开通后报道，也不承诺出行当日仍开每个班次。其他 type 不因这项分支接受将来或未生效计划。

这种值保留 `kind=exact` 的确定算术边界，但 `precision=computed_same_service_departure_arrival`、`reported_minutes=null`、`published_exact_upper=false`；另带 `calculated_minutes` 和计算说明。`original_text` 保留完整原产品块，`display` 明确为“X分钟（公开时刻差）”，不会制造来源不存在的 duration_text 或 duration_span。同样只供城市池判断，不新增精确交通评分或全网最短保证。

`calculation_input` 同时保留原文、断言范围、所选服务/站点与城市方向。`CityTravelReference.legDuration` 只将原来的短语解析调用替换为 `PublicCityBounds.fromStored`，随后仍逐字段 canonical 完全比较；计算型必须从保存的时钟重新计算，不读存入的分钟为真值。旧短语型按旧路径解析，未知/篡改字段照样失败。此版连续停站链仅支持明示站点角色里的字面城市前缀，不引入猜测的车站别名。

`dated_opening_current_route` 增补“上月某日已开通—本方向最快—较以往压缩”的完整句式：按原发布日期解释上月，可跨年；真正通达时长与压缩量分别绑定，不能混用。

`dated_reported_actual_rail_journey` 保存完整带引号时长、独立方向、日期、乘坐高铁站点及到达城市的实际出行报道。要求 `journey_observation_basis=reported_actual_trip`，拒绝最快/全城最短声明，绑定 `not_operator_or_fastest_claim=true`。居民已乘实例可以证明该方向公开报道过的通达实例，不能冒称铁路运营方公布的全城最快。两方向仍独立；当前更新不放宽名义小时或营销交通圈政策。

新增 `PublicRailJourneyContextTest` 113项合成检查，覆盖到/发取值、分钟简写、错一分、站点/方向/原文/范围/hash篡改、跨夜、未来/撤销新图、个人行程与最快范围混淆、旧 public hash链及 `TravelMatrix`。此前12组交通回归1484项原预期不改并全部通过；合计1597项。这些测试数量不等于全国时长准确率；三对真实公共来源另在私有目录制备与真实loader完整性锚验证。
