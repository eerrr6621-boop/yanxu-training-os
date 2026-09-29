# M08 已保存版本对照接口

2026-09-23。本轮固定以下契约，供总控并行接原项目总结预览及相邻版本对照。无新DDL/权限/写动作，现有handle由原Api分流，无须猜测或增加公共路由。

`GET /api/training-summaries/comparison?project_id=101&from_revision=1&to_revision=2`

只接受三个必填参数：project_id为正十进制JS安全整数；版本满足`0 <= from_revision < to_revision <= 9007199254740991`。from=0表示空起点，before=null。支持两个明确的已保存版本（不强制相邻，UI可默认相邻）；拒绝缺省、未知、重复或编码后重复参数、负数、小数、指数、空值及超安全范围。GET之外405。

```text
{
  project_id: 101,
  from_revision: 1,
  to_revision: 2,
  latest_version: 4,
  before: <原revisionView，from=0时为null>,
  after: <原revisionView>,
  read_only: true,
  synthetic: false,
  draft_only: true
}
```

before/after均沿用指定历史版本已有字段：revision、operation、actor_code、saved_at、status、project_id、content、sources、synthetic、read_only。正文包含旧三字段及可选publicity（标题、导语、章节、文字图注）；来源是各自当时冻结快照，不取今天的事实替换历史。

同一MUTATION_LOCK内执行noNestedTransaction、当前真实summary.read、完整head链恢复、stored、revalidate；输出两个版本时只取一次当前deliveryVisible，统一用原revisionView投影。无delivery.read时，两侧授课来源均UNAVAILABLE/DELIVERY_READ_REQUIRED/value=null、总体sources.source_version=null，不泄露隐藏授课计数/摘要。比较结果不增加source_changed、原始hash、差异数量或后端diff。

UI只对白名单中的已投影正文/来源计算变化；对不可见授课来源只显示权限提示，不能据隐藏摘要或metadata推断授课变更。latest_version仅作“还有更新版本”标注，不是编辑器的新CAS版本，不将before/after复制覆盖未保存编辑。原已保存版本统一预览可继续用现revision入口或本接口after，均保持只读。

当前授课遮蔽以外层 `sources.visibility=DELIVERY_HIDDEN` 为准。历史快照中的 `UNAVAILABLE/DELIVERY_READ_REQUIRED` 可以是保存时未带入来源的事实，不能单凭该原因把后来有权刷新出的另一版来源一并隐藏。无旧版表示尚无已保存总结，不代表当时零课时。

当前summary.read被撤销/跨机构403；会话失效401；缺版本/无总结404；顺序/参数400；历史链、正文或来源损坏409；嵌套事务409。归档可按当前READ只读对照。当前可信来源仍按既有context校验，不绕过来源损坏或机构变化检查。

正式复核、Word导出、照片媒体仍关闭，M07仍PREVIEW_ONLY。该分支不写业务表、请求幂等记录、历史或审计，不修改read/save/refresh/revision/history原语义。

验证结果：只读分支与方法已编译，M08ComparisonTest 的10组场景、277项检查通过。使用独立合成H2；比较前后逐表核对全部业务、权限、历史和幂等表均无写入。覆盖空起点、普通保存、来源刷新、相同正文、非相邻版本、旧正文、双侧权限投影、严格HTTP参数、撤权/停用、归档、全链损坏以及原read/history/revision/save/refresh/重放兼容。修正的两处失败均属于新测试夹具：token与Session配对、H2保留字SQL别名；产品候选分支未因此改变。

总控已于2026-09-23完成独立审核、共享Java/专项接入及原界面整合，覆盖原候选阶段状态。真实Main HTTP1489项通过，21阶段各47表内容不变；新UI64/旧UI211，最终构建的桌面1280及手机390/320真实原SPA共135项通过，截图已目视，临时服务已关闭。既有持久化298、授课来源279及旧HTTP178回归通过。详细证据见coordination/INTEGRATION_20260923.md第七轮；没有将真实app/data或185账号用于合成测试，也未开放正式复核或Word。
