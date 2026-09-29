# 查看账号业务权限：只读诊断契约（R13）

`OrganizationAccountAccessInspection`只读当前正式OrganizationAccess配置和指定账号，不能把名册/待启用接收/旧users.role当业务授权。没有DDL、发布、生成编码、激活或目标会话签发。真实184待启用账号、181教师和M01空配置不操作。

共享接点均为静态方法：

- `public static boolean matches(String path)`：精确主路径或其`/`子路径。
- `public static void preflight(HttpExchange ex)`：root在任何POST正文读取前调用。真实Auth.get(Api.token(ex))→Auth.current→admin；匿名/失效401、非admin403优先于路径/方法/参数/配置，未知子路径鉴权后404，主路径非GET/HEAD为405且Allow: GET, HEAD。完全不读正文。
- `public static boolean handle(HttpExchange ex, Auth.Session supplied)`：真实已认证route调用；同MUTATION_LOCK内再次确认请求token的Session与supplied是同一真实实例，再检查路径/方法、严格query并Api.ok。非本功能路径false；Api负责R11请求私有缓存和锁外flush。
- 包级`static Map<String,Object> inspect(Auth.Session actor, long accountId, String organizationCode)`供合成专项复用，仍要求真实当前管理员。

接口仅GET/HEAD `/api/organization/account-access?account_id=正安全整数[&organization_code=当前配置的精确编码]`。重复或解码后重复键、未知键、非法UTF-8/转义、非规范正安全整数、空/未知机构400；target不存在404。坏配置、无活动头却留历史、目标/配置数据库不能核实统一503，不携底层cause/数据内容。所有功能响应no-store。空配置不带org允许读取，传org为400；没有选中机构时decisions空，不自动选本机构。

DTO严格如下（null和数组保留）：

```json
{
  "read_only": true,
  "organization_gate_only": true,
  "configuration_status": "NOT_CONFIGURED",
  "configuration_version": null,
  "subject": {
    "account_id": 5,
    "username": "synthetic-account",
    "name": "合成账号",
    "account_enabled": false,
    "binding_status": "CONFIG_NOT_PUBLISHED",
    "person_code": null,
    "organization_code": null,
    "person_enabled": null,
    "home_organization_enabled": null,
    "role_codes": []
  },
  "organizations": [],
  "selected_organization": null,
  "decisions": []
}
```

有配置时configuration_status=CONFIGURED/version为当前版本；organizations为`{organization_code,enabled}`，enabled包含所有上级有效性，按正式配置顺序。binding_status四值：CONFIG_NOT_PUBLISHED/UNBOUND/BOUND_DISABLED/BOUND_ENABLED，后两项仅表达显式binding启停；person/home组织状态另列。role_codes仅既有person.roleCodes，稳定排序；不根据users.role增加角色。username/name来自选定账号，不按姓名寻找人员；subject.name可为旧账号的null。

选中机构后decisions恰为下列15项，按表顺序返回`{resource,action,label,allowed,status,reason,matched_rule_ids}`：

| resource | action | label |
| --- | --- | --- |
| demand.read | VIEW | 查看需求 |
| demand.write | HANDLE | 填写需求 |
| approval.review | HANDLE | 审批审核 |
| demand.accept | HANDLE | 承接需求 |
| bid.result | HANDLE | 登记投标结果 |
| catalog.read | VIEW | 查看课程目录 |
| catalog.manage | HANDLE | 维护课程目录 |
| delivery.read | VIEW | 查看授课 |
| delivery.write | HANDLE | 填写授课 |
| delivery.verify | HANDLE | 核对授课 |
| reports.read | VIEW | 查看报表 |
| reports.export | EXPORT | 导出报表 |
| survey.preview | HANDLE | 预览评价统计 |
| summary.read | VIEW | 查看总结 |
| summary.edit | HANDLE | 填写总结 |

目标账号停用时所有操作固定ACCOUNT_DISABLED/false/“账号已停用”/空matched_rule_ids；其他决策逐项调用原Engine，以服务器已查验账号ID作为诊断计算目标，不构造目标Auth.Session、不签token。返回原Engine status/reason/matchedRuleIds，显式DENY优先、分岗位负责范围和上级停用沿用同一Engine。没有新授权规则或旧admin例外。

**这是机构层许可诊断，不等于实际可办理。** 实际业务仍需检查单据归属、状态、审批分派、项目权限、目录资格等；不增加尚未接入的总结复核/Word/费用动作。

实现及核心专项已完成：**565项检查通过**，当前完整产品Java与新类编译通过。根负责Api前置guard/路由、原用户页和真实HTTP，M01仅自有类、专项和文档；本模块不代称实际原页面已验收。

运行`bash scripts/M01-account-access-inspection-check.sh`，默认使用PATH里的JRE17；可通过M01_INSPECTION_JAVA指定已安装Java。默认app为脚本所在项目根；仅私有候选验证可通过M01_INSPECTION_APP_ROOT指向共享源码/lib。脚本使用现有ECJ和H2等库，全量编译产品源及唯一专项M01AccountAccessInspectionTest.java，没有额外测试fixture依赖，不下载依赖、不监听端口。测试创建独占临时H2、合成账号及显式组织配置，JVM退出后清理自己的临时目录。

565项覆盖：空配置、无选中机构、绑定停用、人员停用、所属及目标上级机构停用、目标账号停用、原admin/manager不映射业务角色、普通教师无审批、OWN_ORG/RESPONSIBLE_ORGS/NAMED_ORGS、跨岗位独立范围、显式DENY、逐项与原Engine结果一致、发布撤权后即时重算。严格query覆盖重复/编码后重复/未知键/非法UTF8/不规范及超安全数字；preflight用禁止读正文的Exchange证明匿名/非admin/未知子路径/POST按约定拒绝且正文未读，同账号另一真实会话不能替换supplied。

损坏持久配置包括精确`{}`、`[]`、`null`、截断JSON、缺字段、坏人员编码、版本失配、遗失活动头及配置表丢失，均固定503且无cause/字段内容；不存在目标404和真实参数400仍保留。验证有效/失败读取前后全部业务表相同；不发布真实配置、不激活任何人员。坏配置的原Store可能抛ApiException400，故在读取Store.configuration()的小范围统一转503，没有改Store/Engine行为。

最终产品SHA-256：23774623dc6d4840fedfb57b7f78b35e4f9b75cf9c7bd62d5f0d37ca60bd2e46。该类不读import来源/私人名单，也不触实际app/data或邮箱凭据；真正单据能否办理仍留各原业务入口二次判断。
