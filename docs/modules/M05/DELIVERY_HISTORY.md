# M05 授课修订历史与相邻版本对照

2026-09-23。本轮只新增历史读取分支、私有核验帮助方法和专项测试。现有保存/核对、批准政策预览、结算、M08来源语义不变；无新表、无数据库写入。

## 稳定接口

```text
GET /api/delivery-settlement/history?dispatch_id=123&expected_version=5&page=1&page_size=20
```

- `dispatch_id`：必填正安全整数。
- `expected_version`：必填当前事实版本，0表示当前没有授课事实。
- `page`：默认为1，正整数，上限2147483647。
- `page_size`：默认为20，1至50。
- 只允许以上四项。先解码再检查重复和白名单；普通重复、编码同名重复、未知参数、空值、非法整数及无效编码均拒绝400；非GET为405。请求只接受query，不用body选择历史。
- Java直接入口为 `DeliverySettlementIntegration.history(Auth.Session,long dispatchId,long expectedVersion,int page,int pageSize)`。

返回API正常data内容：

```json
{
  "dispatch_id": 123,
  "project_id": 10,
  "teacher_id": 20,
  "organization_code": "001",
  "current_version": 5,
  "page": 1,
  "page_size": 20,
  "total": 5,
  "items": []
}
```

版本、系统ID、页码及总数为安全整数。`items`按版本降序，最多page_size条；page超过最后一页返回空items且total保持真实值。没有事实时expected_version=0返回current_version=0、total=0、items=[]。不会创建空事实或初始化表。

每条items含以下白名单字段：

| 字段 | 类型/含义 |
|---|---|
| version | 原修订版本整数 |
| event_type | `SAVE` 或 `VERIFY` |
| actor_code / account_id | 原修订保存的人员代码/系统账号ID，不查当前姓名或替换当前绑定，不冒充正式编号 |
| created_at | 原修订时间字符串 |
| estimated_hours / planned_hours / actual_hours / payable_hours | 原始精确十进制字符串或null，各自独立，null不作0 |
| actual_minutes | 原conversion.minutes字符串或null |
| verification | 原核对的 `{actor_code,checked_at,evidence_code}` 或null |
| changes | 相邻版本的 `{field,before,after}` 数组，before/after均字符串或null |
| verification_invalidated | 上一版已核对、本版清空核对时为true |

changes固定字段顺序为：`estimated_hours, planned_hours, actual_minutes, actual_hours, payable_hours, verification_status, verification_actor_code, verification_checked_at, verification_evidence_code`。核对状态使用`UNVERIFIED/VERIFIED`；初版对空值比较，因此初版verification_status为null→UNVERIFIED。数字按原字符串比较，`1.0`与`1.00`保留不同原值，不先数值归一化。changes始终对应版本n与n-1，分页首条也会包含前一版对照；不会拿当前page的相邻显示行代替真实前一版。

第一次全空SAVE合法；同值SAVE仍追加版本并清空旧核对。前一版本未核对且所有展示字段同值时changes可以为空。连续VERIFY合法，核对人/依据/时间各自展示变化；checked_at与created_at不要求相等，因为原实现分两次记录时间。

## 只读权限与完整性

每一页首先验证真实当前Auth及M01身份，从现有排课→项目→受理需求组织取得权限范围，要求独立`delivery.read/VIEW`。在可信机构锚点可读取时先作范围鉴权，避免越权用户获得该机构的历史诊断；无法取得可信锚点时仅返回固定来源错误，不读取或暴露历史payload。来源全量核验后再次按实际机构确认权限。没有提升旧管理员角色的业务权限，也不从客户端机构或等级推定。

已完成、已归档、当前师资退库不影响具备当前查看权限的历史读取；不调用saveReady/deliveryReady。历史操作者后来停用、解绑或权限撤销不会抹掉冻结的代码与账号ID。查看者自己的撤权、解绑、停用、过期或换会话仍按每次请求重新校验；旧分页结果不能充当新页授权。

同`Api.MUTATION_LOCK`内先读取行数及字符长度，再完整读取和验证链。校验从1连续到current_version、事实头与最新payload一致、每一行版本/排课/讲师/组织/日期/CONFIGURED标记、事件和操作者、严格字段集合和类型。SAVE的verification须为null，VERIFY须有上一版、实际分钟来源和matching actor，其余事实字段与前版原值一致。校验核对/保存时间格式和不晚于本次读取时间，created_at不早于该行checked_at；不要求全链时间单调。历史合法空dimensions及hour_unit=null保持可读。

课时/分钟必须是现有规则允许的非负十进制文本，最多24有效位、8位小数。conversion存在时须完整且READY、issues为空，原actual等于原class_hours，单位/位数是有效数字元数据、规则/依据编码完整。不执行历史分钟÷45，不用今天的规则版本重新换算，不改变历史显示精度。已有一致性校验不是密码学签名，无法检测数据库管理员同时一致改写全链；本接口不宣称具有外部取证效力。

历史缺失、损坏、混入SYNTHETIC_DEMO、来源关联漂移等统一409：

> 授课修订历史不完整或无法核实，请联系管理员核对

expected_version过期为409：

> 授课记录已更新，请刷新后查看修订历史

最多10000版，单版/事实头最多32000字符，历史与头合计最多8Mi字符。先检查长度再物化CLOB，超过返回明确409容量说明，绝不截断或返回伪造总数。未通过整条历史核验时不会返回部分items。上限是防止单次历史审阅无界占用资源的实现选择，不限制已有保存接口。

## 原页面衔接

接口由现有 `IntegrationDeliveryHost.handle` 转交M05 handle，不需改公共路由或权限模型。页面由总控其他代理负责挂到原授课弹窗。

前端根据刚读取的详情version请求第一页，翻页沿用同一current_version；遇409提示先刷新详情并重开历史，不把不同版本批次拼起来。保存/核对成功后清旧页并用新version重读。401/403、闭窗、切页、换身份要清除旧历史/请求，迟到结果不得复显。仅白名单展示授课课时及核对变化，禁止直接输出整个服务器对象。technical IDs须明确是系统记录标识，不说成正式人员编号。没有配置、费率、金额或冻结结算JSON可供此接口展示。

## 验证

专项 `scripts/M05DeliveryHistory-check.sh` 将实际共享源代码编译到唯一临时目录，仅建立空的合成H2数据目录并使用真实Auth/M01。脚本不调用真实app/data，不启动/替换当前预览服务。专项覆盖只读全表摘要、合法原保存/核对行为、空值/零/长精度、严格分页/query、当前权限、历史完整性及容量。新专项最终849项检查通过；现有M05集成685项及冻结恢复60项回归通过。检查数量包含字段/逐项错误断言，不是等量独立场景。本专项不是总控真实HTTP或原页面视觉验收的替代物。

最终后端源码SHA256：`5a229483679ee7a01bf3db9a1fbcfc9484326eb3499780ee5ba66bece3430957`。专项主类只需要一个参数：已存在、非符号链接、空的合成数据目录；总控可直接运行 `java com.training.M05DeliveryHistoryTest "$check_root/delivery-history-data"`，无需其他参数。
