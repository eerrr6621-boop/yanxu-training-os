# 可信配置中的机构显示名称

## 目的与边界

初始岗位配置的预览已有可信机构名称，但原 Organization 只持机构编码、上级编码和启停，保存后报表只能得到系统编号。本轮在机构配置内保留可选显示名称，供报表在已经核定的机构范围内显示。名称不参与人员对应、机构身份、父子关系、岗位范围或授权判断；不得按同名合并机构，也不得用名称扩大可见范围。

只修改 OrganizationAccess.java、OrganizationAccessStore.java 的字段解析/序列化、OrganizationAccountProvisioning.java 的机构构造，另交专项检查、本说明及 M01 STATUS。只产私有候选，根负责共享整合及发布。本轮不写真实配置、不补写旧历史、不启用账号，不修改审批政策、HTTP 接口或前端。

## 冻结接口

```java
new OrganizationAccess.Organization(code, parentCode, enabled, displayName);
new OrganizationAccess.Organization(code, parentCode, enabled); // displayName == null
organization.displayName(); // String，可为 null
```

M06 先用原权限机制获得允许查询的机构编码，再在同一可信 Configuration 的 organizations 中按编码取得 displayName。缺名返回 null，由报表明确显示缺失或保留编码；不能调用管理员名册导入器，也不能把全部目录自动当作可见机构。名称是纯文本，展示方仍按原文本转义规则输出。

名称为可选元数据。Java null 或旧三参数构造器表示旧机构未提供名称。提供时最多 200 个 UTF-16 单元，不得为空白、含 ISO 控制字符（U+0000–001F、U+007F–009F）或 U+2028/U+2029。保留合法原字符串，不 trim、不归一化、不猜简称。名称相同但编码不同仍是不同机构。

## JSON 与历史兼容

原机构 JSON 的字段和次序仍为 organizationCode、parentOrganizationCode、enabled。displayName 为 null 时完全省略新字段，旧配置经 parseConfiguration/toMap 后保持原有序列化字节和摘要。已有版本、历史 payload 不重写，不默认补名称。

非 null 名称仅在以上三个字段后增加 displayName。解析允许缺字段；若显式携带，必须是符合上述约束的字符串，null、数值、数组和对象均拒绝。Configuration 的统一校验也检查程序构建的非法名称。新名称进入完整候选摘要，确保核对后显示内容变化会使旧确认失效。

初始 Provisioning 使用原 Directory.add 接收的 displayName，和已有可信预览 DTO 的 display_name 完全相同。分公司、区域、其他所属单位名称来自已校验 source 原串；ROOT 沿用预览原有的“系统组织目录”。不改变机构代码生成、原名单指纹、导入回执或职责配置。

## 复制与会话

Store 的解析、序列化及发布往返保留名称。现有人员关系维护直接保留 Configuration.organizations()，原账号绑定界面完整深复制配置，不需额外改写。原三参数调用继续编译并保持未命名行为。

按根明确确认，本轮保持 publish 事务、CAS、鉴权及 changedSessionAccounts 原逻辑。该逻辑按完整机构 record 判断共享配置变化，因此显式发布纯改名也会保守撤销已绑定账号会话；名称不会增加或减少 Engine 许可。改名仍经过原配置发布和会话刷新，不修改全局 record equality 或会话策略。

## 验证

完整当前产品加三类私有候选编译通过；新 M01OrganizationDisplayNameTest **394 项**、旧 M01OrganizationAccessTest **108 项**、旧 M01AccountProvisioningTest **1360 项**全部通过。

新专项用扩展前固定 JSON 常量验证字节和摘要精确保持，包含新旧混合机构、全 ISO 控制字符及长度边界、原串保留、同名编码独立、Engine 完整 Decision 不变。实际调用 Store.publish、完整绑定配置复制和 Relationships.preview/confirm，验证名称保留、无变更原 payload 保持、同版本仅名称变化使旧确认失效。明确断言纯改名发布后原绑定管理员会话撤销，符合根确认的既有保守策略。

合成 Provisioning 对经过全链核验的改名源生成候选，逐机构与预览 DTO 核对名称，确认完整摘要包含名称；预览与确认全数据库内容快照不变，测试宿主发布后名称仍保留且所有绑定停用。全部只用独立临时合成数据，进程结束后清理，不读取真实 app/data 或私人名册。

运行 `M01_ORGANIZATION_DISPLAY_NAME_APP_ROOT='<repository>' bash work/organization-display-name/scripts/M01-organization-display-name-check.sh`。合入 app 后可直接运行 `bash app/scripts/M01-organization-display-name-check.sh`；M01_ORGANIZATION_DISPLAY_NAME_JAVA 可指定 Java17。独立编译测试需现有 OrganizationAccountImportSourceTest.java helper；test 仅接受一个已创建且前缀为 `yanxu-m01-organization-display-name.` 的空临时根目录，无端口或真实源材料依赖。
