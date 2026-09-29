# M05 用户批准执行依据与课酬预览（2026-09-22）

## 本轮交付

用户已确认按《金尊公司内部培训师管理办法2.docx》执行。`DeliverySettlementApprovedPolicy` 单独保存批准执行版本与来源快照，沿用原文件 SHA256、原件“征求意见稿”标注及未知的正式文号/原发布日期。2026-09-22 是本次用户批准日期，不是制度原始发布日期，也不自动成为历史业务新旧制分界。旧候选抽取 API 保持兼容，旧候选的正式阻断项不会带入新预览。

明确费率可直接查询和试算，不再因为原件标注而整体停用。金额按明确计酬课时与单价用精确十进制相乘；缺少金额位数、舍入方式和范围时，显示未舍入计算值、最终金额为空。实际分钟÷45保留两位课时独立处理，60分钟=1.33课时；不能把实际课时自动填入计酬课时，也不能据此推定金额舍入口径。面授开发×5/在线开发×3不进入任何金额公式。

仅预览，不新建费用、配置、政策数据库行、结算快照或支付记录；没有迁移或重算历史。正式配置、确认、更正仍受原宿主封闭边界保护。开发部分提供纯计算契约，排课接口仅接受授课活动，避免将一次性开发酬金误挂到每次授课。

## 原系统接线点

`DeliverySettlementIntegration.handle` 新增：

`GET /api/delivery-settlement/policy-preview?dispatch_id=…`

只接受一个排课ID查询参数；不接受前端提交等级、日类型、已聘任/已批准等布尔值。现有 `IntegrationDeliveryHost.handle` 转交已涵盖此只读地址；本轮没有修改公共路由、初始化、原页面、权限表或数据库结构。新类编译后可使用此地址，正在运行的旧预览进程不会自动装载新代码。

默认地址使用当前登录和 M01 `delivery.read/VIEW` 机构权限，核验真实排课→项目→需求受理→组织以及事实版本。它提供批准依据、已存实际分钟/课时、独立计酬课时、条件费率表及缺项。现有 `dimensions` 的等级/时段是用户填写字段，不作为已经核实的计酬事实。M04当前等级、在库日、课程认证不能替代授课当日聘任或历史等级；项目合同金额不能替代客户付费项目性质；日类型不按星期推断。

如宿主已有核实后的证据存储，可以逐次调用：

```java
DeliverySettlementIntegration.policyPreview(session, dispatchId, context -> {
    // Read current, versioned, verified evidence from the host's own store here.
    // Do not rebuild these facts directly from browser JSON or stale cached booleans.
    return new DeliverySettlementIntegration.PolicyEvidence(
        context, evidenceVersion, evidenceReferences, request);
});
```

- `PolicyPreviewContext` 固定六项：`dispatchId/projectId/teacherId/organizationCode/factRevision/serviceDate`，必须全部相同。
- `PolicyEvidenceProvider.read` 在真实登录、机构授权及来源检查后、同一锁内调用，每次读取最新证据。没有全局注册器或缓存凭证；`version` 不是授权令牌。
- 宿主提供 `DeliverySettlementApprovedPolicy.Request` 中经核实的等级、日类型、研发身份、聘任、项目范围、个案制度适用、申报核准及金额规则。当前服务端事实会覆盖传入的计酬课时、授课核对；数据库中的冻结/旧费记录总会触发历史保护，不能用传入的 `false` 绕过。
- `references` 的来源类型为 `grade/day_type/research_team/appointment/annual_plan/customer_paid/applicability/organizer_application/shared_delivery_approval/development/money_rounding`。相应事实只要明确（包括明确NO）就需来源编号；版本/编号采用已有96字符以内技术编码规范。未知留空，不能用文件名或一个总批准布尔值代替逐项来源。`development` 不适用于本排课接口的计算。
- 读证据后再次检查会话、机构、排课状态、关联与当前事实正文，发生变化返回409。宿主证据存储也应使用同一锁/事务并维护不可变修订；此桥本身不能证明外部材料真实。
- 原 `GET /preview` 仍是完整正式配置的计算预览，契约不变；新 `policy-preview` 明确为本批准文件的只读预览。

返回字段采用金额十进制字符串或null：`status/rate/payable_hours/raw_amount/final_amount/pool_raw_amount/pool_final_amount/individual_raw_amount/individual_final_amount/scenarios/issues/formal_missing_items/execution_basis`，另有 `dispatch_id/project_id/teacher_id/organization_code/service_date/fact_version/actual_minutes/actual_hours/teaching_verified/evidence_version/evidence_references`。`can_confirm` 永远false，不能从有数值推定可以确认。`issues/formal_missing_items` 为 `{code,message}` 数组。计酬条件未知的金额须标条件试算；审批流程未完成不抹掉可算值，实体不合格与历史保护不输出新金额。

## 原页面只读组件

`web/modules/delivery-settlement/policy-preview.js` 导出 `mountPolicyPreview(root,{api,signal})`，返回 `{open(dispatchId),cleanup()}`。原排课弹窗或项目页可提供专用容器和原API适配器。沿用原样式，无新导航或替代页面；本轮不改公共 `app.js`，由总控在原页面挂载。

刷新/切换先清旧结果；撤权、登录失效、失败不保留旧金额，退出或取消后忽略迟到结果。全部业务文本用文本节点显示。界面区分实际/计酬课时、条件试算、未舍入值和待确定正式金额，不提供支付或正式确认按钮。

## 明确边界

- 研发成员授课和独立开发按讲师档；合作开发按负责人档形成总池，不能当每名成员单独费率。份额及共享交付部核准缺失只限制个人金额；研发负责人合作开发交叉条款不猜定。个人份额精确预览不替代全部成员分配、尾差规则。
- 开发重复率50%包含；开发版本已支付不重复支付。客户开发的书面付费和验收、自主开发的公司需要和年末评审、公司核准分别检查；单纯上传材料不等于核准。
- 旧制补发或已有快照返回 `HISTORY_PROTECTED`，读取原快照或旧规则；不拿当前表重算。支付状态及真实支付日期不推断。
- 金额舍入尚未由用户指定；部分/取消授课、已付更正和跨日冲回未开放。缺项针对具体操作，不再成为全部预览的前置材料清单。
- 本轮不连接生产、不支付、不外发消息、不修改真实名册/授权，也不恢复暂停的定时跟进。

## 验证

合成政策事实278项、实际共享源码/真实Auth隔离H2集成685项、冻结恢复60项、UI交互21项及真实原CSS浏览器50项均通过。原核心/分钟/跨端换算及M06桥回归通过。完整验证与当前接入边界见 [APPROVED_POLICY_STATUS.md](APPROVED_POLICY_STATUS.md)。测试材料不作为实际业务聘任、日历或财务批准依据。
