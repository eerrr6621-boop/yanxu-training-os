# Typed 双读事实接入受保护规划链

本次只接通已有纯组件，不新增数据授权、普通用户上传、来源身份认证、模型或生产部署。

`canonical.verification_method=dual_review_curated_bounded_fact_v1` 是唯一显式分支。字段名沿现有Curated契约，不新造method别名。缺失/未知/单边方法不得借此降级放行。A/B整个typed canonical须相同；各自span和rationale独立核验。旧grammar分支无新字段和输出变化；同一个城市对的新旧方向协议混合暂待核。

执行顺序是原doc字节hash、manifest绑定、source policy、原限制、review身份/原页/日期，再做typed方向结构校验。随后heads验证所有原revision、anchor与防回退，prepare只写新路径。当前lookup在完整链之后检查撤销head与两向日期，最后才包装 `planning_reference`。纯组件的 `curated_planning_values` 从来不是CityPlanningSelection可接收的资格。

## 历史和当日判断分离

`directionAfterEnvelopeValidation` 只核原结构、日期合法性/先后、同意的范围类型、原span和算术，不读today。它允许保存一个曾经有效但今天已过期或已结束的事实。否则在历史事件上使用today会使整库不能装载、不能追加合法撤销，这不是保守策略。

`lookup`维持现有 `timing`：未来发布/核读、复核到期、尚未生效、已结束均待核；已撤销head先待核。源的明确外部禁用仍由原保护层拒绝，不因结构校验分离而放宽。纯pair API继续自行做日期判定以保留独立调用原契约。

## Informed第二核读

旧false模式原样兼容。只有B可同时声明：

```text
review_mode = informed_original_source_crosscheck
prior_A_result_access = true
fresh_blind_claimed = false
```

仍须不同agent/run、真实original_read事件和来源核对；不得出现其它已有fresh-blind真标志。true无mode、盲核自称、同agent/run或无原页事件均拒绝。输出保留原reader字段并加informed提示。此模式不认证身份，也不把同一页两次阅读写成两个独立来源；不将AIactor伪装成人类。

## 输出和选择限制

新typed输出保留kind、原数值/单位、开闭界、日偏移等，不把approx/名义小时改成精确事实；源token、服务号和钟点原引用仅保留在私有原件。所有internal政策数在caller结果移除，严格交通、航空、完整铁路排除、时间评分标志继续false。

同城不同已审读主城区站可使用城市级参考并列出差异；城市概述/站点混合有单独specificity告知。原站名不能改写凑城市前缀。汉口→武汉的非字面城市别名、county、unknown等仍是当前实现范围之外，不等于交通事实不存在；不估算接驳。

后续政策修复将 `CityPlanningReference`、纯Curated pair、`CityPlanningSelection.underConfiguredLimit` 统一为双向城际原参考 `<240`，取消隐藏的30分钟加数。`selection_reference` 明确 `basis=unbuffered_city_reference_v1`、无缓冲、非已确认行程；每向保留原值、单位、精度与上界开闭。较小用户maximum也直接比较该结构，损坏时不回退粗档放行。

历史输入/政策元数据与锚不变，旧无typed比较结构的调用快照仍按粗档保守处理。approx与真实界值保留不同含义，不能因数值相同抹掉精度。此修复不改变严格交通库、不把规划约数升级成铁路排除证明。

## 验证

新增 `CuratedPlanningIntegrationTest` 不依赖私有文件，生成全新合成包并实际prepare/load，覆盖类型/异站/时钟、双向阈值、较小maximum、篡改、A/B分歧、原页/政策/锚、informed负例、过期历史撤销、防回退和Index。原 `CuratedCityDurationTest` 保留。源基线对同一新输入的失败另存私有审计，不覆盖。

真实旧规划9对与strict19对只读回归、实际数据适配结果在各自私有交付目录，不能用合成断言数充当真实交通覆盖。check.sh只增加新无私有依赖测试；本子任务不运行全工程或模型。
