# 批量账号导入接线说明

2026-09-23。服务端核心及真实Main/Api/Db宿主接入已完成，原用户页组件待总控挂载。服务器固定清单参数与112项实际HTTP/重启验证见ACCOUNT_IMPORT_HOST.md。原核心交付如下：真实名单仍不进入当前工作库；本轮只在独立临时合成库验证。账号导入与组织权限发布分别处理，不能把导入成功当作业务审批授权或已激活登录。

## 已实现行为

管理员从服务器配置的可信批次读取预览，逐人选择“新建待启用账号”或“关联已有账号”。关联必须按明确的现有账号ID查询并核对服务器返回的账号信息，不能按姓名或旧HR编号自动合并。全批每个候选均需确认；筛选只是展示，不缩减提交范围。缺旧来源ID的67人仍在184候选中；历史九条不进入候选，也无法放进提交。

新账号`status=0`、`role=viewer`、`password=NULL`，用户名为服务器随机生成的`pending_...`内部占位标识，没有共用密码、可发送初始密码或默认验证邮箱。内部人员标识为服务器随机生成的`imp_p_...`，持久映射后重试不变；它不是办公正式编号。已有账号只建立导入接收层的明确关联，不更改其密码、启停或原角色。

每条接收记录保存原候选引用、摘要证据、各岗位分别的机构范围及明确兼任依据。北京负责人仅北京、BP仅其明确北区，分别存为`preparedOrganizationsByRole`；`combinedApprovalsPrepared`仅表达已确认兼任及后续流程复核要求。它们是准备记录中的显示名称范围，**不是**可直接提交的`OrganizationAccess.Person`、`Configuration`或`combinedApprovals`。本模块不调用Store.publish、不写活动组织配置、不激活绑定或业务grants。

同批同源、相同核对动作的提交重放返回原回执，不新建账号或人员。相同批次换来源或换核对结果拒绝；同一候选政策摘要＋来源引用已被其他批次接收时拒绝克隆，要求核对原批次。所有新账号、人员记录、批次回执和版本更新在同一事务内完成，SQL失败或版本冲突不留下半批。已提交后网络回包丢失时，先重新读取预览即可取得持久回执。

## 文件与启动接点

- `src/com/training/OrganizationAccountImportSource.java`：固定服务器路径及摘要的可信来源加载器，不接受浏览器指定来源或确认依据。
- `src/com/training/OrganizationAccountImport.java`：真实Auth管理员适配、空表初始化、预览、按ID核对、原子导入及HTTP处理。
- `web/modules/identity/account-import.js`：`mountAccountImport(root, host)`，返回`{ready, refresh, destroy}`，复用原页表格/弹窗/表单控件。
- `scripts/M01-import-*`和`OrganizationAccountImport*Test.java`：独立临时合成数据检查，禁止指向当前预览或生产库。

现已由`Db.init()`在`OrganizationAccessStore.init()`之后调用`OrganizationAccountImportHost.init()`，只建空表和捕获启动参数。Host在真实管理员首次请求时核验已固定摘要的服务器清单并构造长期`OrganizationAccountImport`，不再要求总控重复添加init或route。服务器属性`account.import.manifest`与`account.import.manifest.sha256`须显式配置；未配置仅此功能503，详细合同见ACCOUNT_IMPORT_HOST.md。

`init()`只建立三张空业务元表及版本计数行，不加载名单、不新建用户，且拒绝嵌在外部事务内运行。构造器所需`OrganizationAccountImportSource.Config`由服务器运维配置显式提供：`batchKey`，八个`FilePin(Path, sha256)`（候选、候选政策、岗位来源、v2审批政策、审计、区域参考、BP/北京用户决定、v2准备报告），以及三份原始Excel的`originals`固定pins。

已核定的固定来源清单保存在本任务私有`outputs/M01_服务器导入固定来源_20260923.json`（权限600），batchKey为`teacher-lead-20260922-v2`；这是供总控显式构造服务器Config的接线资料，不是浏览器上传入口，也不自动启用导入。

只能选用原非Git证据链中的已验收文件，不能把任意上传JSON里的path、sha256或授权声明当成服务器配置。每次预览/提交重验固定输入和原件摘要及互相关系，报告自身也必须固定摘要；来源路径与原件不允许由请求覆盖。确认范围v2不是预览结构版本v1，两者不能混为一谈。源码和测试不嵌真实路径、姓名、HR编号或真实名单内容。

## HTTP接线

`Api.route`已在真实登录检查后、通用CRUD之前调用`OrganizationAccountImportHost.handle(ex, s)`，Host持有长期importer。导入前缀另在POST读取前做真实管理员复核，防止未授权探查配置或输入错误。原`Api.handle`继续负责JSON内容类型、请求大小、JSON解析、异常响应和锁外写回。本适配器使用真实`Auth.current`逐请求重验管理员权限，拒绝伪造Session、已注销/停用/降权账号。所有路由拒绝查询参数，读结果禁止缓存。

| 方法及路径 | 输入 | 返回 |
| --- | --- | --- |
| GET `/api/organization/account-import/preview` | 无 | 批次、来源指纹、revision、reviewToken、完整候选行、历史排除数量、已导入时的receipt |
| GET `/api/organization/account-import/accounts/{id}` | 正安全整数账号ID | accountId、username、name、status、role、available、proof；不返回密码/邮箱/来源HR编号 |
| POST `/api/organization/account-import/commit` | 下述四字段 | 持久批次回执；首次replayed=false，完全相同重放为true |

```json
{
  "expectedRevision": 0,
  "sourceFingerprint": "服务器预览返回的指纹",
  "reviewToken": "服务器预览返回的核对标识",
  "decisions": [
    { "reference": "来源引用", "action": "CREATE_PENDING", "accountId": null, "accountProof": null, "reviewed": true }
  ]
}
```

关联现有账号的action为`LINK_EXISTING`，accountId和accountProof必须来自本次按ID核对结果。所有候选引用必须各出现一次；历史引用、额外/缺失字段、姓名/组织/权限/路径/邮箱/密码、重复账号ID、未reviewed记录均拒绝。proof绑定实例密钥和当前账号字段，不能只填ID略过核对。

回执含`batchKey/sourceFingerprint/revision/imported/replayed/summary/rows`，rows仅含`reference/personCode/accountId/action`，并固定`permissionsPublished=false`、`accountsActivated=false`。GET预览仅向当前管理员展示必要姓名/机构/岗位范围，不将原件路径、HR编号或全部来源证据发到浏览器。详细证据在服务器接收表中保留。

每份核对标识限其真实会话、来源指纹、导入版本及当时users/活动组织配置快照，最长两小时。来源、任一相关账号/配置状态变化、并发导入或核对过期返回409；须刷新后重新核对。会话失效返回401，非管理员403，坏输入400，明确事务失败500。浏览器不得自动重放POST；网络结果未知只GET核实回执。

## 数据库接点

| 表 | 职责及唯一约束 |
| --- | --- |
| organization_account_import_state | singleton=1的CAS版本；初始化为0 |
| organization_account_import_batches | batch_key唯一；存source_fingerprint、decision_digest、回执及操作者 |
| organization_account_import_people | 内部person_code主键；account_id唯一并外键引用users；source_namespace＋source_reference及batch_key＋source_reference唯一；payload保存分岗位范围、出处和未发布状态 |

导入接收层与当前发布的组织权限绑定分开。已在Store活动配置中有绑定的账号（包含停用绑定）或已被其他导入人员占用的账号不可关联；不悄悄替换其他人员。`OrganizationAccountImport.hasImportedAccount(id)`已在原账号删除分支/共享锁中检查并返回明确409，独立于导入来源是否启用。外键也阻止绕过删除；不能通过清理导入接收记录解除关联。

事务使用现有`Api.MUTATION_LOCK`与`Db.transaction`，拒绝嵌套外部事务；全局导入CAS之外还核对users全快照和现有Store完整配置（包括新增分岗位范围/兼任字段）。本模块不重写配置，不触发既有账号的权限扩大或会话撤销；将来真正发布仍由唯一Store执行完整校验、CAS及成功后的会话撤销。

## 原用户页挂载

在原“用户与权限”页添加一个局部容器，不替换现有账号表或已接入的绑定组件：

```js
const { mountAccountImport } = await import('/modules/identity/account-import.js');
if (!isRouteCurrent(epoch, c, 'users')) return;
const batchImport = mountAccountImport(importContainer, {
  api, getUser: () => state.user,
  openModal, closeModal, renderForm, collectForm, renderTable, bindTableActions, toast,
  isCurrent: () => isRouteCurrent(epoch, c, 'users')
});
addRouteCleanup(() => batchImport.destroy(), epoch);
await batchImport.ready;
```

api沿用原宿主返回的data对象和错误status；组件不接触任何来源文件，不提供上传或原始配置编辑器。搜索/筛选、核对进度和逐人核对只在当前生命周期内保存。普通用户不请求管理接口；401/403、销毁及迟到响应不复显候选信息。409和结果不明禁止继续提交旧快照，刷新后全部重新核对或展示服务器已提交回执。

## 尚未接入的范围

HTTP路由、空表初始化、显式服务器配置适配和删除409保护已接入真实宿主，并通过112项真实Main/Api合成HTTP检查。总控尚需按运维计划显式采用固定来源参数、挂原用户页组件并完成页面联调。此后才可由管理员逐人确认真实名单导入。本轮不导入真实184人，不改当前空业务开发库，不把测试样例回灌预览，不部署或操作生产。

真实登录名、邮箱归属、首次/新设备验证和激活由S01衔接；正式机构/人员编码、显式组织权限绑定与grants仍通过现有Store单独发布。上海/西安多人路由和北京同人兼任流程继续由总控/M03处理，不阻塞待启用账号导入实现，也不在导入时假造审批完成。已有人员材料、BP分区和烟台/北京归属不再询问。

## 已完成验证

- 可信来源加载器43项合成检查通过，包含文件摘要/关联链、历史排除、来源缺ID仍保留及每个岗位独立范围；当前真实v2材料仅做只读加载兼容核验，结果为184候选、9历史、43名审批岗位人员、44个岗位身份、1项明确兼任，未连接数据库。
- 服务端141项检查通过：使用完整当前共享源码编译，在独立临时H2和随机本机HTTP端口运行。覆盖真实管理员会话、按ID核对、事务中途和末尾失败全回滚、重放不重复、新账号待启用、已有账号字段不变、来源/账号/配置变化、并发CAS、跨批克隆拒绝、角色范围独立及HTTP输入边界。
- 前端25场景107断言通过，覆盖逐人确认、筛选不能少导、明确账号ID及proof、409全量重新核对、网络结果未知只查回执、401/403清数据、重复点击和过期生命周期回调。
- 原app.js表格/表单/弹窗与五层原CSS的合成浏览器验证完成：1280px桌面五列展示，390px/320px手机无页面横向溢出；兼任岗位逐项展示，按ID关联、全批回执和409刷新保护可操作。409弹窗已移除过期确认按钮，保留关闭/取消；宿主不会重新启用旧提交。

上述UI检查为复用真实控件的临时合成环境，实际原用户页的挂载和联调仍由总控完成，不将内部演示称为正式功能验收；随后完成的真实后台接入见ACCOUNT_IMPORT_HOST.md。没有向当前63882开发库或生产库加载样例/真实名单。公共文件和其他模块没有本轮改动，临时浏览器及测试服务已关闭。
