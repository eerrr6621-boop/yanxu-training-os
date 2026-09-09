# 高铁优先 · 城市初选参考表

> **历史工程验证记录，不是当前可用交通库。** 2026-09-08 已将原始研究文件标记为禁止业务导入并排除公开同步，原因是长期业务使用权限尚未确认。下文“可作初选”、覆盖数量和旧分圈规则仅记录当时实验，不能作为现在的推荐能力或交通结论；当前规则见[交通参考政策](TRANSPORT_REFERENCE_POLICY.md)。

> 本文保留 v6 首批抽样核对基线，不代表当前全量覆盖。v7 已取消就近人数截断、枚举371城并增加航空条件；南京↔北京已补入204/204分钟反例，旧表的“超出4小时圈”结论已撤回。以[v7说明](TRANSPORT_COVERAGE_V7.md)及当前数据文件为准。

核对日：2026-09-07（北京时间）。本轮仅本地接入，未上线、未同步 GitHub。

## 已完成什么

已读取公开供应商页面并核对车站、车次、到发时刻与日偏移，重新计算运行分钟数；双向分别取证，不用反向镜像或城市坐标估时。

[数据文件](../config/rail-timetable.json)保留 70 条来源记录、67 个列车样本，涉及 39 个城市。其中 2 个已知过期样本不参与初选，3 条仍未取得合格或日期完整参考。按核对日规则可用的单向参考有 65 条，其中 9 条源页面完全未标明查询日期，已保留空值和显著提醒。构成 32 组完整双向参考，30 组在 4 小时就近圈内，涉及 36 个城市。

这些是“已查路线子集”，不是这些城市之间的完整矩阵，也不是全国最快班次。没有收录某条路线，不表示两地不通车；当前不会把多个已知区间相加伪装成可行中转。机构名单保持私有，实际授课城市优先。

## 历史实验如何使用（已被后续规则替代）

1. 填写实际授课城市，展开“查看机构与高铁时间优先级”，自动读取对应城市层。
2. 同城 100 分。否则取有来源的去返程站到站样本中较长值，≤120 分钟 85 分、≤180 分钟 70 分、≤240 分钟 55 分。此分数只定范围，不与专业模型分相加。
3. 同城合格老师不足三位，纳入下一完整时间层；范围足够就停止扩圈。圈内按模型分，模型同分再按人工等级降序，第三名模型同分全部保留。
4. 已查但超过 4 小时的路线单列；未知、过期、实际出发地不明的老师列“交通待核实”。部分老师模型未评分时另列“模型待评分”，不混用另一套分数，也不跳过这些近处老师向远处凑数。

在已核对样本中 G 优先、再 D、C，同种类型取较短样本。南通与合肥的已读页面没有 G 样本，采用 D；不能由此宣称全网没有 G。

## 首批双向参考

以下去程按左城到右城，返程相反。每个链接支持对应的列车样本；实际车站、网页查询日期和观察日期在数据文件及系统详情中保留。

| 城市对 | 去程样本 | 返程样本 | 初选状态 |
|---|---|---|---|
| 合肥 ↔ 南京 | [G454 · 50 分](https://trains.ctrip.com/trainbooking/hefeinan-nanjingnan/) | [G3591 · 48 分](https://trains.ctrip.com/trainbooking/nanjingnan-hefeinan) | 可作初选 |
| 合肥 ↔ 武汉 | [G461 · 97 分](https://trains.ctrip.com/trainbooking/hefeinan-wuhan) | [G458 · 99 分](https://trains.ctrip.com/trainbooking/wuhan-hefeinan/) | 可作初选 |
| 合肥 ↔ 苏州 | [G7598 · 112 分](https://trains.ctrip.com/TrainBooking/hefeinan-suzhou) | [G7432 · 108 分](https://trains.ctrip.com/trainbooking/suzhou-hefeinan/) | 可作初选 |
| 合肥 ↔ 南通 | [D3066 · 175 分](https://trains.ctrip.com/trainbooking/hefeinan-nantong) | [D2206 · 179 分](https://trains.ctrip.com/TrainBooking/nantong-hefei/gaotie/) | 可作初选 |
| 杭州 ↔ 合肥 | [G3118 · 110 分](https://trains.ctrip.com/TrainBooking/hangzhoudong-hefeinan/gaotie) | [G3135 · 112 分](https://trains.ctrip.com/trainbooking/hefeinan-hangzhoudong/) | 可作初选 |
| 合肥 ↔ 上海 | [G458 · 115 分](https://trains.ctrip.com/trainbooking/hefeinan-shanghai) | [G452 · 111 分](https://trains.ctrip.com/trainbooking/shanghaihongqiao-hefeinan/) | 可作初选 |
| 北京 ↔ 天津 | [C2615 · 32 分](https://trains.ctrip.com/TrainSchedule/C2615) | [C2134 · 36 分](https://m.ctrip.com/html5/trains/tianjinxi-beijing/) | 可作初选 |
| 北京 ↔ 济南 | [G21 · 83 分](https://trains.ctrip.com/TrainSchedule/G21) | [G798 · 106 分](https://trains.ctrip.com/TrainBooking/jinanxi-beijingnan/gaotie/) | 可作初选 |
| 北京 ↔ 太原 | [G611 · 165 分](https://trains.ctrip.com/TrainSchedule/G611) | [G618 · 175 分](https://trains.ctrip.com/TrainSchedule/G618) | 可作初选 |
| 北京 ↔ 郑州 | [G505 · 190 分](https://trains.ctrip.com/trainschedule/G505/) | [G1044 · 202 分](https://trains.ctrip.com/TrainSchedule/G1044) | 可作初选 |
| 北京 ↔ 南京 | [G795 · 276 分](https://trains.ctrip.com/trainbooking/beijingnan-nanjingnan/) | [G806 · 248 分](https://trains.ctrip.com/TrainSchedule/G806) | 超出 4 小时圈 |
| 北京 ↔ 石家庄 | [G655 · 71 分](https://trains.ctrip.com/trainbooking/beijing-shijiazhuang/) | [G6736 · 108 分](https://trains.ctrip.com/TrainSchedule/G6736) | 可作初选 |
| 兰州 ↔ 乌鲁木齐 | [D2711 · 669 分](https://trains.ctrip.com/trainbooking/lanzhou-wulumuqi/gaotie) | [D2708 · 633 分](https://trains.ctrip.com/trainbooking/wulumuqi-lanzhou/dongche) | 超出 4 小时圈 |
| 兰州 ↔ 银川 | [D1209 · 186 分](https://trains.ctrip.com/trainbooking/lanzhou-yinchuan/gaotie) | [D2767 · 191 分](https://trains.ctrip.com/TrainBooking/yinchuan-lanzhouxi/gaotie) | 可作初选 |
| 太原 ↔ 西安 | [G3209 · 201 分](https://trains.ctrip.com/trainbooking/taiyuannan-xianbei/gaotie/) | [G3210 · 203 分](https://trains.ctrip.com/TrainBooking/xian-taiyuan/gaotie/) | 可作初选 |
| 成都 ↔ 重庆 | [G8609 · 62 分](https://trains.ctrip.com/TrainBooking/chengdu-chongqing/gaotie/) | [G8612 · 62 分](https://trains.ctrip.com/TrainBooking/chongqing-chengdu/gaotie/) | 可作初选 |
| 贵阳 ↔ 昆明 | [G307 · 144 分](https://trains.ctrip.com/TrainBooking/guiyang-kunming/gaotie/) | [G308 · 145 分](https://trains.ctrip.com/TrainBooking/kunming-guiyang/gaotie/) | 可作初选 |
| 广州 ↔ 南宁 | [G2918 · 182 分](https://trains.ctrip.com/TrainBooking/guangzhou-nanning/gaotie/) | [G2911 · 171 分](https://trains.ctrip.com/TrainBooking/nanning-guangzhou/gaotie/) | 可作初选 |
| 福州 ↔ 厦门 | [G5315 · 63 分](https://trains.ctrip.com/TrainBooking/fuzhou-xiamen/gaotie/) | [G3048 · 57 分](https://trains.ctrip.com/TrainBooking/xiamen-fuzhou/gaotie/) | 可作初选 |
| 哈尔滨 ↔ 长春 | [G106 · 55 分](https://trains.ctrip.com/TrainBooking/haerbin-changchun/gaotie/) | [G517 · 55 分](https://trains.ctrip.com/TrainBooking/changchun-haerbin/gaotie/) | 可作初选 |
| 沈阳 ↔ 长春 | [G517 · 64 分](https://trains.ctrip.com/trainbooking/shenyang3-changchun/gaotie) | [G106 · 64 分](https://trains.ctrip.com/TrainBooking/changchun-shenyang/gaotie/) | 可作初选 |
| 兰州 ↔ 西宁 | [G671 · 57 分](https://trains.ctrip.com/TrainBooking/lanzhou-xining/gaotie/) | [G3178 · 57 分](https://trains.ctrip.com/TrainBooking/xining-lanzhou/gaotie/) | 可作初选 |
| 西安 ↔ 银川 | [G1909 · 180 分](https://trains.ctrip.com/TrainBooking/xian-yinchuan/gaotie/) | [G355 · 184 分](https://trains.ctrip.com/TrainBooking/yinchuan-xian/gaotie/) | 可作初选 |
| 广州 ↔ 深圳 | [G3017 · 29 分](https://trains.ctrip.com/trainbooking/guangzhounan-shenzhenbei/) | [G1102 · 29 分](https://trains.ctrip.com/trainbooking/shenzhenbei-guangzhounan/) | 可作初选 |
| 东莞 ↔ 广州 | [G1026 · 17 分](https://trains.ctrip.com/trainbooking/humen-guangzhounan/) | [G6245 · 17 分](https://trains.ctrip.com/trainbooking/guangzhounan-humen/) | 可作初选 |
| 东莞 ↔ 深圳 | [G6245 · 18 分](https://trains.ctrip.com/trainbooking/humen-shenzhenbei/) | [G6058 · 17 分](https://trains.ctrip.com/trainbooking/shenzhenbei-humen/) | 可作初选 |
| 杭州 ↔ 宁波 | [G7541 · 54 分](https://trains.ctrip.com/trainbooking/hangzhoudong-ningbo/) | [G7658 · 54 分](https://trains.ctrip.com/trainbooking/ningbo-hangzhoudong/) | 可作初选 |
| 宁波 ↔ 温州 | [G7543 · 107 分](https://trains.ctrip.com/trainbooking/ningbo-wenzhounan/) | [G7506 · 99 分](https://trains.ctrip.com/trainbooking/wenzhounan-ningbo/) | 可作初选 |
| 济南 ↔ 青岛 | [G6905 · 89 分](https://trains.ctrip.com/trainbooking/jinan-qingdao/) | [G6902 · 90 分](https://trains.ctrip.com/trainbooking/qingdao-jinan/) | 可作初选 |
| 南昌 ↔ 长沙 | [G1759 · 118 分](https://trains.ctrip.com/trainbooking/nanchang-changsha/gaotie) | [G224 · 70 分](https://trains.ctrip.com/trainbooking/changshanan-nanchangxi/) | 可作初选 |
| 南昌 ↔ 武汉 | [G1860 · 137 分](https://trains.ctrip.com/TrainBooking/nanchang-wuhan/gaotie/) | [G2035 · 124 分](https://trains.ctrip.com/TrainBooking/wuhan-nanchangxi/gaotie) | 可作初选 |
| 佛山 ↔ 广州 | [G2861 · 21 分](https://trains.ctrip.com/trainbooking/foshanxi-guangzhounan/gaotie) | [G3750 · 20 分](https://trains.ctrip.com/trainbooking/guangzhou-foshan/gaotie/) | 可作初选 |

北京—石家庄旧 G6713 页面日期为 2026-02-28，已过期；采用新找到的 G655 71 分钟样本，不说 G6713 已重新核实。南昌—长沙旧 G205 页面为 2026-08-03，已过期；采用新查页面 G1759 118 分钟样本，不把旧 71 分钟刷新日期后继续使用。

佛山西→广州的 21 分钟样本到的是广州站，**不是广州南**；相反方向样本从广州南出发。两站都属广州但接驳不同，所以原站名必须保留，不把站到站样本当成一套可直接订票的往返行程。

## 明确待补的部分

- 海口↔广州：本次仅取得 K/Z 或包含普速前段的混合中转，未用这些凑高铁参考，不估轮渡时间。
- 广州→中山：已见 30 分钟样本，但源页日期只有月日、没有年份，暂列日期待核；中山→广州南已有 27 分钟的明确日期样本。双向未齐，不自动进入初选。
- 部分样本近期就会到复核期限，例如广州南→虎门的已知页面日期为 2026-08-08，9 月 7 日仍在 30 天边界，9 月 8 日起会自动转为待补查，不能只更新观察日续期。
- 未收录的城市对、换乘方案、授课日开行、门到门接驳、票价与余票不在本次已验证范围。

## 维护方式和边界

- 代码兼容 `config/rail-timetable.json` 路径或 `YANXU_RAIL_TIMETABLE_FILE` 配置；当前研究底稿已禁止业务读取，不能随代码部署。仅可配置使用范围明确且完成核验的资料。文件最多 1 MiB、固定 `rail-timetable-v1` 结构，来源记录只读，不接收浏览器或模型提交的“时长”作为可信数据。文件变更会按路径、大小和修改时间重新加载。
- `observed_on` 是实际读取核对日，`source_service_date` 是源页明示查询日，不是列车运行有效期。分别保存，任一已知日期超过 30 天则停用该样本。计算日期统一北京时间。
- 页面完全未标查询日期的静态参考，保留 `null`、`source_date_status=not_shown`，界面提示“网页未标查询日期，仅作路线参考”，按观察日 30 天复核。已知过期或不完整日期不能清空以伪装为新参考。
- 30 天是保守的资料维护规则，不是铁路方承诺。不自动联网刷新；维护者应重新打开合法公开来源，核对实际正文、车站与时刻后更新。搜索摘要日期不能覆盖正文日期。
- 不下载/公开供应商整页快照。原始研究数据、私有机构名单、历史工作簿和核实底稿不进入公共文件；没有明确使用范围的研究记录不能因为只提取了时刻事实就自动作为业务库部署。
- 当前来源是携程公开时刻页面，**不是 12306 官方运行核验**。本次 12306 查询入口需要交互/验证码时停止，没有绕过，也没有接入未公开票务接口。
- 所有站到站结果保持 `transport_verified=false`、`travel_date_verified=false`，市内接驳和到场可行性待确认。即使已有时间参考，也不保证授课日开行、有票或按时到场，不自动派课、订票或创建项目。

## 实际验证

- 新增 `RailTimetableTest`：251 项通过。覆盖全部样本到发算术、已知省市、过期/未来/空日期、双向缺失、非 G/D/C、不安全链接、跨日、同城、超限和实际表进入分圈。
- 真实本地 INT8 模型 + 全新隔离业务库：铁路推荐 API 12 项通过；含真实南京↔合肥表、四位“【灰度测试】”构造讲师、等级同分顺序、第三名同分全留、无自动立项。
- 完整隔离回归通过，另有真实模型师资业务 90 项通过。详见[v6 记录](RECOMMENDATION_V6.md)。

这是工程与时刻事实验证，不是全国路网完整度、实际调度成功率或新一轮真实简历盲测的成绩。
