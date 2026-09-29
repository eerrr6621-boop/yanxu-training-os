# M08 授课来源 JSON 接口（2026-09-23）

本轮来源适配已实现并通过独立H2验证；现有草稿路由和原项目页已由总控接通，本轮不重新搭建。原页新增授课展示及联合浏览器验证由总控承担。

所有总结响应的 `sources.delivery`（含顶层、`current.sources`、指定历史 revision、幂等重放）统一使用当前 `delivery.read / VIEW` 权限投影。无此权限时固定返回：

```json
{"status":"UNAVAILABLE","reason":"DELIVERY_READ_REQUIRED","value":null}
```

不会返回冻结授课数量、日期、版本摘要或其他授课事实。权限隐藏不是没有授课；仍允许已有 summary.read/edit 用户填写总结。响应脱敏不修改数据库快照或历史hash；普通保存保留服务器原始冻结来源，不能把脱敏view写回。

有权限且来源可用的新快照：

```json
{
  "status":"AVAILABLE",
  "reason":null,
  "policy_version":"M08-M05-SOURCE-20260923-1",
  "source_version":"服务器来源摘要",
  "as_of_date":"2026-09-23",
  "value":{
    "estimated":"8","planned":"6","actual":"4","payable":null,
    "dispatch_count":2,"reviewed_completed_count":1,"pending_count":1,
    "unverified_completed_count":0,"rejected_count":1,"total_dispatch_count":3,
    "fact_count":2,"missing_fact_count":0,"verified_count":1
  },
  "coverage":{
    "estimated_planned":"ALL_EFFECTIVE_DISPATCHES",
    "actual_payable":"REVIEWED_COMPLETED_AS_OF_DATE",
    "unknown":"NULL_PROPAGATES",
    "hour_unit":"CLASS45"
  }
}
```

数字示例仅说明类型，不是实际汇总。四类课时是精确十进制字符串或null，分别展示、不能相加；null显示“待补齐”，不能转0。预计/计划覆盖未拒绝排课；实际/计酬只计已完成、当前核对历史可信、授课日期不晚于上海时区当日的记录。完成但未核对/缺来源会使实际及计酬未知，不拿未完成排课或旧hours推算。

无任何排课返回同结构 `status:"AVAILABLE",reason:"NO_DISPATCHES"`，数量和四类合计为0；dispatch_count为有效未拒绝排课数，total_dispatch_count含拒绝记录。全部已拒绝是AVAILABLE、dispatch_count为0，不能冒充无记录。合法未核对事实可以贡献预计/计划，但不得贡献已核对实际；损坏/合成/关联或核对历史不一致明确409，不包装为有效事实。

旧快照中的 `UNAVAILABLE/M05_SNAPSHOT_NOT_CONNECTED/value:null` 原样兼容，不静默重算。有授课权限时 `source_changed` 会发现新增/拒绝/修改/核对撤销或修订变化；首次保存及显式刷新获取当前来源，普通正文保存保留原冻结数据。无授课权限的source_changed仅比较可见项目来源，不用布尔值泄露授课变化；响应 `sources.source_version` 为null，并增加 `sources.visibility:"DELIVERY_HIDDEN"`，提示这是响应视图而非可提交的来源快照。有权限的响应保持原始source_version。

无权限显式刷新时仍刷新可见项目来源：已有授课冻结快照保留原值（响应继续隐藏），不存在时保存 `UNAVAILABLE/DELIVERY_READ_REQUIRED/value:null`。将来授权后须显式刷新方可带入当前授课来源；没有后台补录。

M07仍为UNAVAILABLE/M07_PREVIEW_ONLY，三项确认标志false。无金额、费率、教师姓名/身份、核对证据原文、照片或正式编码。正式复核、Word和媒体不在本轮。

## 实现与可信校验

- `DeliverySettlementIntegration.summaryDeliverySource(Auth.Session,long projectId)`：仅新增此只读方法及 summary 前缀 private 帮助方法，没有改变旧 projectHours/保存/核对/计酬逻辑。自身复验真实人员、独立delivery.read、唯一受理链、机构及前后配置；不创建事务、不写表，可在宿主业务锁和已有M08事务中调用。不能用reports接口替代，也不能靠客户端传facts。
- `TrainingSummariesDeliverySource`：严格校验新/旧授课来源schema，统一构造缺权限状态并投影响应；调用均在MUTATION_LOCK内。未扩展真实角色授权。
- `TrainingSummariesIntegration`：首次保存和refresh接服务器来源；context/revalidate仍前后核对当前会话、M01配置和源数据。全部来源响应走统一投影，保存仍操作原始sources_json；无权限refresh保留历史授课部分，重新计算新修订的总体摘要，不改旧修订。

校验当前排课/项目/讲师引用、可信受理机构、当前fact主键/版本/日期/系统关联、CONFIGURED状态和CLASS45单位。当前fact必须与m05_fact_revisions同版payload一致，修订条数/最大号与当前版本一致；已核对事实必须为VERIFY事件，操作者、核对时间与修订相符。核对不存在时只接受SAVE事件，不能把残留VERIFY当未核对草稿。核对时间未来、SAVE伪装VERIFY、头/修订缺失、合成fact、关联错配和实际分钟换算不一致均409。

实际课时独立核验45分钟换算、当前规则版本和舍入依据，不引用旧dispatches.hours、fees、讲师费率或M06汇总。拒绝排课不进合计，但其事实/修订仍参与校验和来源版本；来源版本包含排课状态/日期/关联、事实修订和核对依据的私有摘要。仅输出项目级摘要，不返回逐人/逐课证据、姓名、金额或费率。

每次捕获最多1000条排课，每条事实及修订payload各不超过32,000字符、累计8Mi字符；先限长后解析。最后的sources_json仍不超过原20,000字符；历史JSON原16Mi字符/1000修订门槛不变。摘要只有汇总、数量、口径和日期，未塞入明细列表。上限为技术容量，不触发删历史或自动切分。来源版本按上海日期冻结；日期推进也可能提示可刷新。

计数说明：fact_count、missing_fact_count、verified_count仅针对未拒绝排课；verified_count表示有效核对事实，reviewed_completed_count另要求完成且授课日已到。unverified_completed_count含完成但缺事实/缺核对/授课日在未来的记录，表示实际/计酬口径尚不完整。对应课时null传播，未知不得显示0。计酬课时不自动等于实际课时；本轮不提供金额或结算确认。

## 验证与兼容交接

新增 `scripts/M08DeliverySource-check.sh` + `M08DeliverySourceTest.java`，使用真实Auth/M01/M05/M08和独立临时H2，**15组场景、278项检查通过**。覆盖两种统计人口、缺失/未来/已拒绝/核对撤销、显式刷新、无授权仍可写、历史/重放/所有来源位置隐藏、隐藏保存/refresh不覆盖原冻结来源、写中会话失效回滚、损坏/合成/关联/VERIFY/换算拒绝、旧格式bytes/hash兼容、无监听端口HTTP边界、容量和数据库重开。

没有运行生产服务、app/data、真实184人、公共全套或浏览器；本轮未改UI。独立HttpExchange检查不替代总控真实HTTP/原页面验收。没有新DDL、主路由改动或需重新初始化的内容。

旧 `M08IntegrationTest.sourceStatus` 曾固定断言所有M05均为M05_SNAPSHOT_NOT_CONNECTED。此断言对新捕获已不成立：有授权可AVAILABLE，无授权应DELIVERY_READ_REQUIRED；旧持久快照仍兼容。本轮独占新M08DeliverySource测试，没有越界修改旧测试；总控更新旧断言时应按当前权限分支检查，不能为保持旧测试而混淆不可用原因。
