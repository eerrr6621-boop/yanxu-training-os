# 初始岗位配置生成（R15-v1）

根已冻结执行政策。本模块从完整、已核验的现有账号导入回执及固定来源生成可审阅的初始 OrganizationAccess.Configuration，管理员明确确认后交给可信宿主。生成器不调用 Store.publish、不改变 users、不启用绑定、不写数据库。根统一负责发布和后续真实账号启用。

## 服务端接口

```java
public OrganizationAccountProvisioning(OrganizationAccountImport importer);
public Map<String,Object> preview(Auth.Session actor) throws Exception;
public PreparedConfiguration confirm(Auth.Session actor, Map<String,Object> body) throws Exception;
```

confirm 正文精确为 `{batch_key:string, review_token:string}`。batch_key 是当前服务器已配置批次，不能以它选择路径或导入器。核对标识还须与该批次匹配；错误会话或错误批次不能消费他人的合法记录。前端不能提交 Configuration、人员编码、机构映射、岗位、范围、grant、账号启用选择或源文件路径。

宿主为同一可信 importer 保持一个长期 Provisioning 实例；核对记录属于该实例，不能用新实例确认旧标识。服务器来源清单失效时连同实例一起销毁，恢复清单后必须重新预览；不能用进程全局静态 token 表绕过这项失效。

PreparedConfiguration 为只可由本类构造的公开 final 类型，提供 `configuration()`、`confirmation()`、`batchKey()`、`sourceFingerprint()`、`intakeRevision()`、`policyVersion()` 只读访问器。configuration 为完整不可变的服务器候选，confirmation 为可返回浏览器的白名单结果。此对象只能由可信宿主在本次核对后立即消费，不能充当可复用的权限凭据；宿主若继续发布，必须持共享 MUTATION_LOCK 包住 confirm 与唯一 Store.publish，不包装外层数据库事务。

Import 只增加包级不可变 ReceivedAccount(sourceReference,accountId,personCode,accountEnabled)、ProvisioningIntake(source,intakeRevision,accounts,receiptFingerprint) 和 verifiedProvisioningIntake(actor)。该入口复用既有 source、revision、existingBatch、verifiedIntake，完整接收缺失则 409；receiptFingerprint 固化批次完整当前回执与 decision_digest 等事实，避免仅核对人员映射而遗漏合法格式的回执变化。该摘要只供服务器核对，不是独立签名。不改原 preview/intake/commit/replay 的字段、来源指纹或持久内容。

## 冻结的浏览器 DTO

全部字段采用 snake_case。空关系引用为 null，数组总是存在。各计数按实际可信数据计算，不能以真实材料的 184/37 等数字硬编码校验合成批次。

preview：

```text
{
 policy_version, batch_key, source_fingerprint, intake_revision,
 proposed_version, review_token, expires_at,
 permissions_published:false, accounts_activated:false,
 can_operate:false, requires_account_activation:true,
 office_codes_status:"UNKNOWN",
 summary:{account_count, approval_people_count, approval_duties_count,
          ordinary_people_count, region_count, branch_count, home_organization_count,
          route_prepared_count, route_blocked_count, route_blocked_counts:{code:count},
          accounts_enabled, accounts_disabled, bindings_enabled:0},
 diff:{initial_configuration:true, organizations_added, people_added,
       bindings_added, enabled_bindings_added:0, grants_added, combined_assignments_added},
 validation:{configuration_valid:true},
 organizations:[{organization_code, display_name, kind, parent_organization_code,
                 enabled:true, office_code:null}],
 people:[{reference, account_id, name, organization_code, organization_name,
          account_enabled, binding_enabled:false, person_enabled:true, role_codes:[],
          role_scopes:[{role_code, organization_codes:[], organization_names:[]}],
          leader_reference, bp_reference, route_status}],
 grants:[{rule_id, role_code, resource, action, effect:"ALLOW", scope}],
 combined_assignments:[{organization_code, organization_name, reference, account_id,
                        leader_role_code, bp_role_code, evidence_status:"VERIFIED_SOURCE"}],
 issues:[{code, reference, organization_code, message}],
 warnings:[]
}
```

people.name、organization_name 取可信来源，account_id 取已核验回执；不按当前账号姓名反推对应。people 不输出 imp_p、密码、旧办公编号或原件明细。organization_code 是技术关联标识，界面应优先展示 display_name。grants 的 scope 仅 OWN_ORG 或 RESPONSIBLE_ORGS，须结合对应人的 role_scopes 展示职责范围；机构授权无隐式父子继承。

route_status 为 `ROUTE_PREPARED` 或以下阻断代码之一：`HOME_NOT_BRANCH`、`NO_LEADER`、`MULTIPLE_LEADERS`、`NO_BP`、`MULTIPLE_BPS`、`SELF_REVIEW`、`COMBINED_EVIDENCE_UNAVAILABLE`。依上述列出顺序判定第一个适用原因，未准备路线的两项 reference 均为 null，不展示部分路线为已完成。issues 对每个未准备路线人员一项，summary.route_blocked_counts 按该代码统计。ROUTE_PREPARED 只表示来源与政策支持生成该路线，不表示账号已可登录或审批。

confirm 的 confirmation()：

```text
{prepared:true, policy_version, batch_key, source_fingerprint, intake_revision,
 proposed_version, configuration_valid:true, permissions_published:false,
 accounts_activated:false, can_operate:false, account_count, route_prepared_count,
 route_blocked_count, requires_account_activation:true}
```

原始 Configuration 只给服务端宿主，不作为可由客户端编辑后交回的 JSON。confirm 本身没有配置发布结果，不得用 prepared=true 表示权限已开通。

## 岗位、关系及动作政策

固定 policy_version=`M01-INITIAL-PROVISIONING-v1`。所有人员有 MEMBER 身份且该岗位无业务 grant。来源 BRANCH_RESPONSIBLE、BRANCH_LEAD、BP 按原 kind 保留，逐岗位明确列出负责分公司；普通教师不自动有审批职责，也不能给 MEMBER 配审批 DENY，以免否决兼岗人员的明确审批职责。

审批三种岗位各配置 approval.review HANDLE 和 demand.read VIEW，scope=RESPONSIBLE_ORGS。SUBMITTER 只加入来源所属机构为已确认分公司、恰好一个负责人及一个 BP、不等于本人、同人两职责有明确兼任证据的人员；只授 demand.read VIEW、demand.write HANDLE 的 OWN_ORG。没有 demand.accept、bid.result、课程、授课、导出、报表或总结 grant，不推定培训团队人员。上述为落实已获批内部填报/职责审批的系统实现，不另造用户制度。

每个岗位都有完整 leader/bp RelationRule。SUBMITTER 两关系 required=true，其他岗位 required=false；统一 allowSelf=false、targetMustCoverOrganization=true；leader 允许 BRANCH_RESPONSIBLE/BRANCH_LEAD，bp 只允许 BP。全部人的每岗范围显式存在：MEMBER 空集，SUBMITTER 为本人分公司，审批岗为来源逐岗分公司；responsibleOrganizationCodes 仅作为上述集合的校验并集，不以并集给不同岗位共同授权。

已确认同一分公司仅一负责人和一 BP 可据根冻结政策准备员工路线；上海、西安等多人保留全部岗位但不选第一人，不给该人员 SUBMITTER。本人恰为拟审批人则 SELF_REVIEW；没有所属分公司路线证据则 HOME_NOT_BRANCH。每人仅一对 leader/bp，不把 BP 负责多个公司的审批职责自动复制成跨机构填报权。

北京兼任及合成同类情况仅从已核验 combinedReferences、两个明确岗位各自覆盖范围及来源证明生成。负责人岗位必须在本机构唯一可确定，BP 岗位必须为 BP；evidenceRef 使用服务器可复算的证明摘要引用，不能暴露私有路径/证明全文或声称已有审批完成。目标职责范围分别保存，不改变兼任人的原所属单位。

## 机构与初始启停

统一技术命名空间 `org_sys_v1_`。代码为 UTF-8 文本 `kind + "\n" + exactDisplayName` 的 SHA-256 前 32 位十六进制，加该前缀；ROOT 的 exactDisplayName 固定为 `SYSTEM_ROOT`。kind 为 ROOT、REGION、BRANCH、HOME，全量检测碰撞。不得把不同精确来源名称自动合并或猜别名。

层级为 ROOT→来源区域→来源分公司。非分公司的原所属机构按精确名生成 HOME 叶节点直属 ROOT，不推断其业务层级或挪至本人负责区。显示根节点为“系统组织目录”，系统技术ID与 source fingerprint/来源引用关联，不等于正式办公编码；office_code 始终 null。

保留原回执 imp_p 人员码与 account_id。组织和 Person.enabled=true 表示配置中的目录条目有效，所有 AccountBinding.enabled=false，包含本来已启用的 LINK_EXISTING 账号；不暗中授予后者业务权。用户账号启停完全不变。初始配置可以通过完整校验和 Store 停用绑定约束，但 Engine 实际操作仍拒绝。根后续明确协调账号和绑定启用，不能只改一处就称业务可用。

## 核对、安全与错误

只接受真实当前管理员，所有生成/确认在共享 MUTATION_LOCK 内。当前正式配置非 null 返回 409，初始生成不得覆盖已有配置；有历史而活动头缺失或持久配置损坏固定 503。来源、完整回执或关联无法核验 409，不返回个人原值、私有路径或底层错误。

preview 与 confirm 都重新调用可信投影、读取完整 current account facts、管理员凭据事实及当前配置。5 分钟、最多 128 条随机 64 hex token，绑定同一 Session 对象、批次、候选完整配置摘要、来源/回执/版本及相关账号事实。源、回执、凭据、账号或配置变化拒绝；合法拥有者在确认中观察到任何失败后永久撤 token，字段恢复不能使其复活；错误会话/批次不消费他人记录。成功确认消费 token，重复确认 409；配置保持未发布。

所有 Configuration 必须经过 OrganizationAccess.validate；无循环、角色及逐岗范围完整、兼任证据结构有效。issues 只隔离已知路线未决部分；结构或可信数据损坏不得用 issues 降级后继续交付。GET/preview、确认及失败路径均不写数据库，也不撤销已有会话。

## 验证与接入状态

R15-v1 核心已完成，独立合成专项 1360 项断言通过，完整当前产品 Java17 编译通过。复用现有 SourceTest 合成 fixture，并以重新核验全来源的变体覆盖同人兼任正路线。检查包括普通人员 SUBMITTER 正路线、多负责人/自审/HOME 隔离、岗位独立范围、唯一既有人员/账号关联、完整 DTO 白名单、不可变返回值、所有公共业务表 BEFORE INSERT/UPDATE/DELETE 写入拦截及内容快照、旧导入/重放兼容。

两项确认边界已验证：不同 Provisioning 实例不能确认旧实例标识；合法格式的回执事实变化也必须使旧标识失效。后者分别用 decision_digest 改为另一有效 64hex，以及同步修改 action、summary、disposition、payload 为内部一致的另一合法接收状态复现；修复后确认 409、恢复原字段仍不能复活、新预览可继续。还覆盖源/原件摘要、接收来源关联、凭据、账号、版本及配置变化、过期/容量、错误会话和错误批次不能消费合法记录。

专项由可信测试宿主显式调用 Store.publish，证明候选可按原约束保存，随后全部业务 Engine 操作仍因停用绑定被拒绝；生成器本身及确认路径始终零数据库写入、不撤销会话。没有读取真实 app/data 或私人原件，临时 H2 和资料已在测试进程退出后清理。Host/Api/UI、最终真实 Store 发布和账号启用由根另行统一接入，不能以核心完成代称已正式启用。

运行 `M01_PROVISIONING_JAVA=$HOME/.local/bin/java bash app/scripts/M01-account-provisioning-check.sh`；可用 M01_PROVISIONING_APP_ROOT 指定 app 根目录。直接调用 M01AccountProvisioningTest 时传入一个已创建、文件名以 `yanxu-m01-account-provisioning.` 开头的临时目录。编译需现有 OrganizationAccountImportSourceTest.java 作为合成来源 helper，无额外私有 fixture、端口、测试 HTTP 路由或真实数据依赖。
