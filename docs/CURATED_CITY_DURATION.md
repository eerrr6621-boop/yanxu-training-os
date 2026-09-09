# 统一后台审读城市时长：独立纯组件

`CuratedCityDuration` 是**已完成外层验证之后**的事实机械校验与规划计算器，没有文件、网络、模型或前端入口。现已由 `CityPlanningReference.fact` 按显式 `canonical.verification_method` 接入原受保护链；不是普通用户自签入口。纯pair API仍不是业务准入API。详见 `CURATED_PLANNING_WIRE.md`；合成测试不等于真实来源或上线。

## 信任边界

`verification_method=dual_review_curated_bounded_fact_v1` 明示：来源里的方向、常态性、站点范围及角色含义由后台两次审读确认；代码不证明中文句意、读者身份、来源真实性或使用许可。真人审读应如实标真人，AI审读应如实标AI；有历史背景不可称fresh blind，单一原页不能称两独立来源。

**不能拿两份自行编造的Map直接宣称“核实通过”。** 调用方须先复用既有受保护的验证链：

1. 加载受控文件，校验版本、完整文档字节与SHA、manifest引用、事件头、独立完整性锚、防回退和撤销。不得来自普通用户自签上传。
2. 原来源限制与当前使用检查：显式研究专用、提取/复用禁令、访问/自动化限制、撤销或替代不能由派生文件洗白。旧staging未启用与来源禁用分开；不声称政府许可。
3. 两份审读仍须绑定同一来源包、不同run/agent、原页记录和背景。旧 `prior_A_result_access=false` 保持兼容；B已看过A时只能如实显式 `review_mode=informed_original_source_crosscheck`、`prior_A_result_access=true`、`fresh_blind_claimed=false`，不得假报未见A或自称盲核。当前外层仍只支持assistant，真人入口须另行审阅，不能改actor_type绕过。
4. 历史校验只检查日期合法性/先后及事实结构；使用当日的复核期、生效/结束判定留给lookup。过期历史必须仍能完整验证并追加撤销revision。

本组件复用 `CityPlanningReference.city/day/originalRestrictions/units/same/number` 等已有安全帮助方法，再做防御性日期/限制/事实检查；**不会重新实现或替代文档、身份、许可、版本链**。它返回 `envelope_verified_by_this_component=false`，条件始终是 `requires_validated_envelope=true`。

## 最小结构与API

```java
var out = new CuratedCityDuration.Direction(
    originalSource, originalReadFactA, originalReadFactB,
    fromRegistryCity, toRegistryCity, reviewedOn);
var back = new CuratedCityDuration.Direction(
    returnSource, originalReturnReadFactA, originalReturnReadFactB,
    toRegistryCity, fromRegistryCity, reviewedOn);
var values = CuratedCityDuration.pairAfterEnvelopeValidation(out, back, today);
```

`source` 复用现有 `source_id/url/published_on/units` 结构；每个unit有 `unit_id/text/offset_unit=utf16/text_sha256`。审读fact包含 `from_city/to_city/rationale/canonical/spans`。A/B的整个canonical须完全一致，不能只比较计算后的分钟数：

```text
verification_method, source_id, from_registry_id, to_registry_id,
from_endpoint, to_endpoint,
endpoint_scope: {from, to}, mapping_basis,
transport_mode, route_scope,
published_on, effective_on, effective_until, service_state, status,
duration: {kind, 原值/单位/原token/边界或同服务时钟}
```

必需字段必须存在；日期可空的字段显式null。未知额外字段拒绝，以免默默忽略“已批准”或精度字段。A/B可以选择不同但各自真实可逆的span、保留不同rationale；核心方法、精度、边界开闭、endpoint_scope等不能不同。

`spans` 每个所需role为一个 `{unit_id,start,end,text}` 列表，UTF-16索引与原文完全匹配；起点、终点、上下文必需。数值类型另需完整duration token；时钟类型需service、departure、arrival、segment，同一天无arrival_day，次日需明确arrival_day原词。

### 首版精度

| kind | duration字段 | 示例 / 计算 |
|---|---|---|
| `reported_minutes` | value, unit=minute, token | `1小时37分钟`→97；仍只是报道参考 |
| `nominal_hour` | value, unit=hour, token | `1小时`保留小时概述，不改称精确60分 |
| `nominal_half_hour` | value, unit=half_hour, token | `两个半小时`保留5个半小时单位；内部换算150分不代表精确测量或时间上界 |
| `approximate` | value, unit=minute, token | `约1小时30分钟`、`90分钟左右`；保留约数 |
| `upper_bound` | upper, unit=minute, upper_inclusive, token | `不到1小时`是开上界；`1小时以内`是闭上界 |
| `bounded_range` | lower, upper, unit=minute, lower_inclusive, upper_inclusive, token | `1小时至1小时30分钟`、`大于1小时至不足1小时30分钟` |
| `same_service_clocks` | service_id, departure_clock, arrival_clock, arrival_day_offset, calculated_minutes | 源钟点重新计算；次日23:00→01:00为120分 |

这里的正则只识别局部数字、时间单位和明确界词，不依赖新闻整句词序。完整token旁边紧邻“约”“以内”等被故意排除的修饰语会拒绝；不能摘掉修饰语凑成精确类型。未支持的表达（例如未标单位的任意区间、中文数字、多日跨越）保守待核，不能改写源句后导入。

时钟至少在同一事实unit、同一连续segment中，绑定一个服务ID、两端与各钟点原span；不同unit/不同服务拼接或同ID重复服务段拒绝。支持 `:`、`：`、`∶`；同日与明确“次日/翌日/第二天”的一天偏移重新计算，双方同填错分钟仍拒绝。原文角色由审读者标注，代码**不声称自动判断每个时钟究竟是到站或发站**；两名审读者同时把语义读错仍是后台信任边界，不能用SHA冒充解决。

局部站点到/发简写支持 `HH:mm/mm` 和 `HH:mm/HH:mm`（斜线可全角）。必须保留完整原token的span，例如 `21:33/35`：作为 departure 取21:35，作为 arrival 取21:33；不能只截出35或前面的21:33，不能把原文改成另一钟点。缺位分钟、非法时钟、多斜线、跨小时/跨日需要自行猜测的倒序值均拒绝。该功能只核记法与已标注角色，不推断整篇文章的站点或车次。

### 端点与作用范围

`mapping_basis=literal_city_prefix`。每端 `endpoint_scope` 为 `city_summary`（必须恰是规范城市名）、`main_urban_station`（主城区站点），或独立的 `urban_subcentre_station`（原页明确位于该城市副中心的已运营枢纽）。后两者原站名须以规范城市名开头，由两位原页审读者一致确认范围；城市副中心不能改称主城区。**前缀自身不证明地理范围**；county、unknown、unknown_connection即使带城市前缀也待核。涿州东→保定、燕郊→廊坊、常熟→苏州、威远→内江等外部映射尚未支持。

副中心类型依据城市级推荐需求单独增加，不是为了把未知站点放行。保留去返程原端点及差异提示，不代表全城任一地点的耗时，不估算市内接驳，不把注释当成程序证明。真实北京通州的原页定位、已运营状态和读者判断须随私有来源包保留；合成测试不是这个事实的来源。

半小时支持限定语法：`半小时`、`半个小时`，以及一位1–9阿拉伯数或中文一至九（含两）接`个半小时`/`小时半`。带`约`/`大约`/`左右`/`上下`者仍用`approximate`，不升级精度。不支持的泛中文数、分数小数、半小时区间/上下界不能擅自改写为精确分钟。选择片段不能从更长数字或半小时表达里截短，也不能隔着空白丢弃限定词。

两向规范城市必须相反，但不再强迫两个方向经过同一主城区车站：每个方向的站点已分别通过前置审读、主城区scope、城市及原文span核验时，可以是同城不同主城区站；城市概述与具体主城区站也可混用。这是城市就近参考，不是拼接往返行程。县域或接驳未知仍待核，即使名字带城市前缀也不放行。

异站输出 `endpoint_difference=true`；城市概述与具体站混用另输出 `endpoint_specificity_mixed=true`。`endpoint_notices` 按城市保存去/返原始端点、在两向中的角色及差异，不重命名凑一致。每向结果仍保存完整原始端点。相同端点的两个flag均为false、notices为空。提醒中固定 `transfer_time_estimated=false`：不估算两站间接驳，不承诺已完成完整行程规划。历史30分钟提醒不参与筛选或档位计算。

仅接受铁路 `city_summary/station_corridor/station_service_pair` 与已运营、常态图、回顾性铁路参考的事实类型。计划、临时、未知、停运不升格。历史结构可保留已过期/尚未生效/结束的记录，但当前lookup待核；撤销head先返回撤销待核。复核期仍为 `min(原发布日期+365天, 实际复核日+90天)`；不能借新核读改原发布日期。

## 返回值

- 成功：独立 `status=curated_planning_values`，`planning_state=planning_reference`，两向原精度/端点/来源/范围和内部参考值、固定粗档ID；同城异站/城市概述混用时保留上述差异提醒。
- 无法确认或边界：`status=curated_planning_pending`、`planning_state=planning_pending`、具体原因；不输出可准入粗档。
- 两向分别用明确分钟、名义值或范围上端作为规划参考值，直接比较**小于240**，不加30分钟。227/239均可作初筛参考；240分钟或闭上界240不通过，原文明确开上界“不到240分钟”可通过。
- `selection_reference` 保存统一比较所需的双向原值/精度/开闭边界，自定义门槛同样使用此结构。约数、名义值不授予数学上界；上下界开闭同时保持在 `source_duration` 中。
- 固定 `transport_verified/time_score_applicable/strict_eligibility/rail_exclusion_complete/air_fallback_trigger=false`；不输出精确排序分数。结果递归不可变，不别名引用传入Map。

纯pair API独立status故意不是现有 `CityPlanningSelection` 识别的 `planning_reference`。只有受保护的 `CityPlanningReference` 外层验证后才包装业务规划结果，保留方法、原类型、范围和审读性质；不认证自然语言语义或来源身份。

## 已实现的最小wire点

`fact`在原 `doc/manifest/sourcePolicy/review/readDay` 外层之后，只有A/B都显式匹配方法才调用 `directionAfterEnvelopeValidation`。该API不使用today，不让过期历史破坏链；当前lookup再做两向日期与规划门槛，并通过无准入能力的 `endpointNoticesAfterValidation` 给异站告知。未知/单边方法不回退旧parser，同对新旧方向协议混用待核。旧规划输入不变。

原 `CuratedCityDurationTest.java` 356条合成断言保留。新增 `CuratedPlanningIntegrationTest.java` 实际构造合成外层包并prepare/load，覆盖类型/双读/原件/锚、informed真假边界、异站、历史期限/撤销、较小用户配置和不可变index。两测试均接入check.sh，不依赖私有真实资料。

门槛修复后原纯组件测试扩展为393条，受保护集成350条；`CuratedClockNotationTest`现覆盖117条到/发简写、错误角色、截断、倒序和空白隔开的斜线负例。合成测试数不是实际交通覆盖数。

2026-09-08：新增`CuratedHalfHourTest` 182条算术/边界加20条受保护入库断言，`CuratedSubcentreTest` 38条范围加20条受保护入库断言。以同一新测试运行修改前类，两者分别因`curated_duration_kind_unsupported`、`curated_county_or_unknown_endpoint_scope`失败；新类通过。原393/350/117/77/67/269条定向回归同时通过。这些是表示及准入边界测试，不是全国覆盖或真实线路验证。

空白隔开的简写（例如 `21:33 / 35`）目前仍不解析，但不得截取其中的 `21:33` 当作完整离站时刻。span边界会检查最多32个Unicode空白及其后的斜线；超过此长度也保守待核。此检查防止记法被截断，不声称解决审读者把到站/发站语义读反的问题。

2026-09-08 后续政策修复已将纯组件、外层lookup与自定义限制统一为 `unbuffered_city_reference_v1`。原文、原审读文件、历史政策元数据和完整性锚保持不变；变更的是调用时的初筛口径，不是交通事实。
