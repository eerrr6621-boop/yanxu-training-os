# 已审核城市交通参考层（v1）

本功能把“原始时刻观测”与“经过审核、定期维护的城市交通参考”分开。它已经具备本地加载、验证、更新审计和推荐接入能力，但默认没有配置任何长期参考数据，也未将已有研究资料自动转为已审核记录。全国交通资料的授权与逐条核查仍未完成。

## 接入与隔离

- `YANXU_CITY_TRAVEL_REFERENCE_FILE` / Java 属性 `dispatch.city.references.file`：单个私有参考库文件；默认空，表示未配置。
- `YANXU_CITY_TRAVEL_REFERENCE_DIR` / `dispatch.city.references.dir`：可选的按授课城市分片目录，文件名为 `省份-城市.json`。启用后，不会因某城市文件缺失而借用其他城市或通用文件。
- 参考文件旁的 `原文件名.integrity.json` 保存独立的已接受版本和审核链头。加载器需要对该私有目录有写权限，采用临时文件和原子替换更新此文件。支持 POSIX 的主机将其权限设为 `0600`。
- 配置文件、完整证据内容和完整性记录必须放在私有部署目录；不要放入公开 GitHub。没有修改任何实际配置或填入真实路线数据。

`TravelMatrix.resolve` 增加了显式传入城市参考库的纯函数重载；原重载不读取新库。生产的 `NearbySelection.route` 与 `DispatchPriority.catalog` 才按配置加载。城市目录仍遍历 `RegionDirectory` 全部条目，不以已经录入的老师或机构名单限制范围。

## 数据结构

顶层为对象，包含：

| 字段 | 约束 |
|---|---|
| `version` | 固定 `city-travel-reference-v1` |
| `library_revision` | 从 1 开始的正整数；每次发布变更递增 |
| `usage_authorized`、`business_import_allowed` | 均必须明确为 `true` |
| `authorization_ref` | 非空的内部授权依据编号，不放密钥或敏感合同原文 |
| `evidence_snapshots` | 独立的原始证据快照数组 |
| `references` | 城市对参考及其完整审核修订历史 |

新库中的库、参考、证据均要求明确授权。旧版近期时刻数据的兼容规则并不自动授予新长期库授权。任何 `research_only=true`、`business_import_allowed=false` 或 `usage_authorized=false` 都不能得到长期自动推荐资格。

### 原始证据快照

每项包含 `evidence_id`、`sha256`、`content`。`sha256` 为整个 `content` 的确定性 SHA-256，不能只校验一段说明文字而漏掉日期或授权元数据。`content` 包含：

- `evidence_type=authorized_schedule_research`，不能是直线估算、模型生成时间或仅余票状态。
- `from_province`、`from_city`、`to_province`、`to_city`、`mode`（`rail` / `air`），明确有向关系。
- `source_url`（HTTPS、无 URL 用户凭据）、`source_provider`、`original_content`，均不得空缺；`original_content` 保存实际核对的来源内容而不是自动生成的授权证明。
- `research_on`：实际核查日期。不得来自未来，不可把重新导入日期当成研究日期。
- `usage_authorized=true`、`business_import_allowed=true`、非空 `authorization_ref`、`city_mapping_checked=true`。
- 普通铁路参考：`scope=city_station_to_station`、`reference_minutes`。
- 直飞参考：`scope=city_airport_to_airport_nonstop`、`reference_minutes`、`nonstop=true`、`service_type=passenger`。
- 用于航空前置判定的完整铁路核查：`scope=all_city_stations_and_rail_itineraries`、`coverage_complete=true`、`minimum_minutes`、`no_service=false`。单个慢车、未查询到和查询失败均不能声明完整核查。

这些是经过真实来源研究后整理的证据，不依赖某次授课日期或某班次是否可售票。缺少对应来源内容、方向、时长或授权时保持待核实；系统不会补写。

### 城市对参考与修订记录

每个 `references` 项包含 `reference_id` 和 `revisions` 数组。一条城市对记录已含两个方向，可反向使用，不能将两个方向的分钟数互相复制。

每个修订项包含：

- `snapshot`：该版本完整的参考记录。
- `previous_hash`：前一修订项的 `event_sha256`；第一个版本为空字符串。
- `change_reason`：非空的真实变更理由。
- `event_sha256`：对 `snapshot`、`previous_hash`、`change_reason` 三个字段共同计算的 SHA-256。

`snapshot` 字段：

| 字段 | 约束 |
|---|---|
| `reference_id`、`version` | ID 与外层相同；版本连续从 1 递增，不得跳号或删去旧版本 |
| 双端省市 | `from_province/from_city/to_province/to_city`；同一审核链不得更换城市对 |
| `mode` | `rail` 或 `air` |
| `review_status` | 只有 `approved` 可自动使用；`pending` / `revoked` 等保留待核实，不回退到旧批准版本 |
| `reviewer_type` | 必须如实记录；当前自动推荐入口只接受 `human`，AI 核查不得标成人工 |
| `reviewer_id`、`review_method` | 非空、实际审核人的内部编号及实际审核方法；没有真实审核不能虚构 |
| `research_on` | 本条所引用全部方向与铁路排除证据中，实际研究日期的最新值 |
| `reviewed_on` | 实际审核日期，不得来自未来；各项研究不得晚于审核，也不得早于审核超过 30 天 |
| `review_due_on` | 可省略，默认审核后 90 天；显式设置不得超过审核后 180 天 |
| `applicable_scope` | 固定 `pre_bid_city_catchment`，不授权作为具体行程保证 |
| 授权字段 | `usage_authorized=true`、`business_import_allowed=true`、非空 `authorization_ref` |
| `outbound`、`return` | 各含 `evidence_id`、`source_sha256`、`minutes`，必须与所指证据方向和分钟数一致 |
| `rail_exclusion` | 航空必填，含 `outbound`、`return` 两项证据引用；两向都必须完整核查，至少一向铁路最短时间不满足 4 小时条件 |

撤销或重新审核采用追加版本，不能删除原批准记录或静默改写。全库文件版本不能回退、同版本内容不能变化；更高版本也不能改写完整性记录已经保存的审核历史。移除一条记录须追加撤销状态，不得直接删掉已记录的 ID。

### 哈希规范与权限边界

`CityTravelReference.sha256` 会递归按键名排序 JSON 对象、保留数组顺序、将整数数值规范为整数，以 UTF-8 JSON 字节计算小写十六进制 SHA-256。`eventHash` 按上述三项审核字段计算。证据摘要和审核链分别校验，不能互相代替。

**哈希链、旁路完整性文件不是数字签名，也不能证明授权合法性或审核人身份。**它们用于发现误改和对照已保留的版本识别回退。能控制参考库及完整性文件的人员仍可能重建全部内容；需要部署文件权限、备份、实际授权审查和真实审核流程。当前进程会记住已见到的完整性版本，删除或回滚该文件会被拒绝；同时删除文件并重启无法凭无签名本地数据证明历史未被改写。不要将此功能宣传为防恶意篡改认证系统。

## 自动推荐规则

1. 已有近期时刻证据保持原来的 30 天规则；没有把原始资料改为永久有效。
2. 只有新库资料全部通过明确授权、人工审核、来源哈希、双向事实和维护期限检查，才可补充到城市推荐层。
3. 铁路两向严格小于 240 分钟；航空须先有完整铁路前置依据，再满足两向直飞严格小于 180 分钟。铁路候选优先，航空只补不足；池内按匹配分、同分讲师等级排序，末位分数并列全留。
4. 长期参考处于阈值前 10 分钟内（铁路 230–239、航空 170–179）时，仅标待复核，不判超限、不排除路线。铁路排除证据距 240 分钟不超过 10 分钟时也需复核。更近期且符合原严格规则的双向 239/179 分钟证据仍可准入。
5. 90 天默认/180 天上限和 10 分钟临界保护是明确维护政策，**不是对物理时间精度或交通稳定性的保证**。
6. 更新的单向观测、变化、否定、来源撤销以及无法判断日期的新资料会阻止旧长期参考恢复准入。近期快样本也不能掩盖同方向更新的变化。资料矛盾后先复核，不挑较好看的旧值继续推荐。
7. 已过复核期、审核撤销、元数据不完整或相互冲突时保留历史、返回 `eligibility=unknown`。不会自动转成航空或推断没有交通可达。
8. 当前策略仍不把“没有铁路服务”转换为一个虚构的长铁路时间；该情况单独待政策和依据核实。

## 返回值与维护

可用长期结果使用独立的 `status=city_transport_reference`、`reference_basis=reviewed_city_reference`，不是包含实际车次的 `rail_time_reference`。保留 `eligibility=rail/air`、双向参考分钟、原研究日期、审核日期、复核日期、来源 URL/摘要、参考版本、库版本和审核链头。

待复核结果为 `status=city_reference_pending`、`eligibility=unknown`，携带 `city_reference_history` 和具体 `review_reason`。所有长期结果都不表示授课日开行、有票、老师有空、零接驳或能准时到场。

资料可长期保留用于追溯，但自动推荐资格有独立维护期限。新增城市时目录会扩展，未有合格资料的方向继续待核实。不能以有一部分批准参考、完整城市名单或程序已接入，就宣称全国时刻核查已经完成。

## 本地合成回归

`scripts/CityTravelReferenceTest.java` 仅使用带“灰度测试”标识的虚构来源、分钟数、审核人及授权编号。它隔离所有真实配置、没有外网访问，在专属临时目录测试加载和完整性文件并结束后清理。

测试涵盖双向、长期与近期严格边界、AI/未知审核人、空来源、未来日期、过期、更新与撤销、元数据与内容篡改、版本回退、审核历史改写、私有目录分片、全国目录迭代及候选池排序。合成记录不构成任何真实路线授权或人工核查凭据。
