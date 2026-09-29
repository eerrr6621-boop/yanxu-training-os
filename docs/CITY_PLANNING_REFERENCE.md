# 投标前城市初筛参考组件

`CityPlanningReference` 根据经过两次来源核读的公开铁路通行时间，给出独立标识的城市初筛参考，默认未配置为空。主流程可在一次请求中构建 Index，并通过 `planning_reference` / `planning_included` 单独接入投标前候选；它不替代严格 `TravelMatrix` 证据，不能称为班次确认。本组件制备与测试没有启用生产配置、上传生产或改动原有 9 对严格参考库。

## 用途与输出边界

- `planning_reference`：双向城际公开参考用时均小于 240 分钟，显示“参考范围内，出行待核”。不额外加 30 分钟收紧筛选范围。
- `planning_pending`：未收集、城市未知、来源/复核过期、撤销、未生效、资料冲突或落在边界，显示“范围待核”。未知不等于不可达。
- 粗档的稳定 ID 为 `planning_0_1h`、`planning_1_2h`、`planning_2_3h`、`planning_3_4h`，另有中文 `planning_time_band`。调用方不得反解析中文构造分数。
- 每向单独返回 `from/to`（省份、城市、registry ID）、起讫 `endpoint`、原精度、来源 URL/发布日期、复核日期/截止日、作用范围与服务状态。返向不会镜像正向。
- 不输出严格准入字段、精确时长分数或班次承诺。`time_score_applicable`、`transport_verified`、`air_fallback_trigger`、`rail_exclusion_complete` 均为 `false`。

历史文件中的 30 分钟元数据保留以保护原始版本和锚，但不参与初筛：`buffer_applied_to_selection=false`。候车、安检和市内接驳另行确认，不凭空估算。名义值或约数保留原精度，不能改称已证明的数学上界；它们也不能用于排除铁路或触发航空后备。

当前输出增加 `selection_reference`，以双向原值、精度和开闭上界进行统一比较。自定义较小门槛也使用此值：227 分钟满足小于 240，不满足小于 227；原文明确“不到 227 分钟”可满足后者，约 227 分钟不能。旧无此字段的粗档快照仍保守比较档位上边界；新字段损坏时不得回退旧档放行。

## 接口

```java
Map<?, ?> library = CityPlanningReference.bundle(); // 默认 Map.of()
Map<String, Object> result = CityPlanningReference.lookup(
    library, "湖北", "襄阳", "湖北", "宜昌", LocalDate.now());
```

独立配置项（本轮未配置）：

```text
dispatch.city.planning.references.file
YANXU_CITY_PLANNING_REFERENCE_FILE
```

两者只指向本组件文件，不能指向旧严格交通库或某份“已批准=true”材料。`load(Path)` 要求同路径 `.integrity.json` 侧车完整性锚。`prepare(data, newPath, today)` 仅允许新文件路径，完整验证后生成文件及锚，不覆盖原研究件，不设置任何运行配置。

`bundle/lookup` 是保留的完整验证基线，**不适合按每位老师重复调用**：会重复解析原始核读档案。新增的请求级接口会先完整验证一次，再构建不可变 city-pair index：

```java
CityPlanningReference.Index index = CityPlanningReference.index(LocalDate.now());
// 本次请求的所有老师复用该 index；下次请求必须重新构建，不放 session 或全局缓存。
Map<String, Object> result = index.lookup("湖北", "襄阳", "湖北", "宜昌");
```

每个新请求重读文件/锚，重新检查全部原始文档、版本、限制、撤销和当次日期；没有跨请求的资料缓存。返回结果递归只读，调用方无法修改嵌套城市、核读背景或分歧列表来污染后续查询。详见 `CITY_PLANNING_INDEX.md`。主流程的选人和 UI 接入按独立标识进行验收，不扩大本组件的交通保证。

## 文件与完整性

顶层 `version=city-planning-reference-v1`，正整数 `library_revision`，固定 `purpose=prebid_city_planning_only`。局部 `references` 按 ID 保存顺序版本、`previous_hash`、`change_reason`、完整快照、事件 SHA；城市对在同一 ID 历史中不得变更或重复。撤销作为新版本保留原证据，不删除历史。

`documents` 保存原始来源包 manifest、最小来源片段和 A/B 核读文件的原始 JSON 字节字符串与 SHA-256。loader 校验：

1. 原始文档字节 SHA、来源文件在 manifest 中的 SHA、核读文件绑定的 manifest SHA。
2. A/B角色、不同run/agent、是否读过A、背景、原页记录和源限制；旧未看A模式兼容，B可显式 `informed_original_source_crosscheck` 如实保存已看A状态并再核原页，不能自称盲核。不认证运行或人类身份。
3. 两向各自选择原 A/B 对应城市事实；逐字段比较城市、端点、交通方式、时间值/精度、服务、发布/生效日期和作用范围。
4. 原始 UTF-16 span 与保留片段完全一致。分钟/约数/小时字段仍从对应时间 token 核验；同车时间由开/到时钟重新作差并绑定同一站点—服务片段，不能串同篇文章中不同列车的时钟。
5. 文件 SHA、版本和每条事件头与独立锚完全一致。同一进程记住的锚阻止回退、重写或删除已见历史。

这不是“两个布尔值签字即通过”，但也不是读者身份认证或自然语言正确性的密码学证明。后台整理者和受保护的部署流程是信任边界；能同时篡改事实、原始核读、来源包和外部锚的人仍能伪造一套自洽输入。因此不得提供普通用户上传/自签入库接口。跨进程/换路径回退必须由部署系统保存最新锚防止，不能宣称本地哈希解决了身份与所有回滚问题。

## 核读分歧与原精度

旧语法分支支持 `reported_minute/nominal_hour/approximate/computed_same_service`，保持原有源语法与输出。新增显式 `canonical.verification_method=dual_review_curated_bounded_fact_v1` 在同一保护链调用 `CuratedCityDuration`，支持typed分钟/名义小时/约数/开闭界及有明确次日的同服务时钟。未知或单边method不回退旧parser；不为每篇文章加句式。语义角色由两次审读承担，代码不声称理解中文。

新分支输出typed原值/开闭界与精度，但不带原token、服务号或钟点引文；完整原文与角色仍在私有文档。内部政策调整数、精确时间分不输出。异站/城市概述混用单列告知；县域/未知范围不升级。详见 `CURATED_PLANNING_WIRE.md`。

运行时不重读整篇新闻推断未来/常态。两次核读必须对当前运营、回顾性公开参考或常态新图的范围达成一致。季度图 7 月 1 日生效、7 月 2 日转载可记 `regular_diagram_reference`，不因晚一天发文的“将”字自动判成未来；同文暑运临时加开不能被扩成永久服务。实际日期还会再次检查尚未生效/已结束。

核心事实分歧拒绝导入。纯摘录边界、未被采用的数学界值、直达明确性或准备状态分歧保留，不取更有利值。调用结果只返回分歧字段名、准备状态和必要限定；完整 A/B 原值留在私有证据档，不把一方声明的精确界值传播成公共结论。

下文原六对档案的A/B均为assistant，B未读本轮A结果后封存但有早期背景。新增受保护informed模式不更改这些原读记录；informed第二核读必须明确已看A，交通双读不等同模型盲测。

## 日期、复核和来源限制

- ISO 日期须为真实 `YYYY-MM-DD`；未知 registry ID、空 ID、城市/省份不对应不能配对。
- `reviewed_on` 绑定两次原页核读事件的最近北京时间日期；旧 A 记录没有可靠秒数时，保留 null，使用原来源包创建日，不伪造读取秒数。
- 到期日为 `min(来源发布日期 + 365 天, 本次核读日 + 90 天)`。新复核不能把旧报道的发布日期改成今天；没有新核读事件不能只改复核日期续期。
- 历史fact结构校验不比较今天；旧记录即使过期或结束也可核验事件并追加撤销。仅当前lookup按今天判断可用性，撤销head优先待核。
- 明确禁止最小事实提取/复用、外部研究专用、自动化/访问限制、原来源撤销或受限核读状态均拒绝。新政策行不能压过原文件上的限制。
- 旧 staging 的 `runtime_enabled=false`、`private_only=true` 表示当时未启用/私有整理，不自动等于来源禁止使用。新派生文件必须重新走以上逐项核对；不得修改旧封存件或借新文件改掉外部限制。
- `independently_curated_public_facts` 是本系统对自行公开核读的最小事实整理的分类，不是政府授权、开放数据许可或商业时刻表授权；`license_claimed=false`、`article_republication_allowed=false`。

本轮 8 个来源均再次核读。郑州市网站的《郑重声明》明确要求直接转载文字图片先征得作者同意，该限制原样单列为 `direct_article_republication_restricted=true`。本派生仅后台最小城市/时长事实与私有核对片段，不转载整篇或图片，不取得对外分发原文的许可。未观察到针对这种最小事实提取的明确禁止，不把“未见禁止”写成“获授权”。其它原页可见范围未见针对本次用途的明确禁止；后续发现限制须撤销受影响参考。

## 本轮实测覆盖

| 已核双向城市对 | 参考档 | 保留的主要限定 |
| --- | --- | --- |
| 苏州—上海 | 不超过 1 小时档 | 正常新图同车时钟实例，不是全城最快；C 字头不自动证明高铁属性 |
| 天门—武汉 | 不超过 1 小时档 | 正反原精度不同，保留准备状态分歧 |
| 襄阳—宜昌 | 1–2 小时档 | 两向均是约数，来源于 2025-09-29，2026-09-29 到期 |
| 武汉—长沙 | 1–2 小时档 | 去向小时概述，返向长沙南—武汉站分钟参考 |
| 武汉—郑州 | 1–2 小时档 | 去向小时概述，返向旧来源于 2026-10-14 到期 |
| 十堰—西安 | 1–2 小时档 | 返向直达明确性分歧；出向是一趟更快服务实例 |

实际测试使用 6 对 / 12 个独立方向，不是全国完成，不是城市间任意拼接。原 9 对严格库另行只读复核仍为 18 方向通过；两类数据不互换门禁。

`scripts/CityPlanningReferenceTest.java` 从私有 draft 制备新文件后实际加载，覆盖默认空、12 向输出、分歧、日期、未知城、撤销/版本/锚、源限制、无核读自签、摘要/时钟/方向/精度篡改和双向原参考的 4 小时边界。需要提供私有 draft 与全新输出目录；不会把研究证据放进 Git。编译方式与其它 Java 脚本测试相同，classpath 使用当前交通依赖；本地验收命令和路径另存私有交付目录。
