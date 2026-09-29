# M01 集成说明

## 2026-09-22 最新：审批岗位和范围已据现有名单准备

用户新决定已落实到独立准备层：原38名分公司负责人/牵头人仅对应本公司，5名BP按最新明确答复各负责一区，北区BP同一候选追加北京管理。分公司职责人员39、BP5、重叠1，44个岗位身份仍为43人，均在184候选中；其余141名候选明确无审批岗位。教师兼审批岗位保留两种身份；教师等级、旧来源ID及原管理员标识都不产生审批范围。

新增`approval-role-preview.js`导出`previewApprovalRoles(batch, candidatePolicy, roleSource, authorization, regionReference, scopeDecision = null)`，复用现有账号准备校验，按独立确认的源行→候选引用匹配岗位，新增范围按单独用户决定核验。离线入口`M01-approval-role-preview.mjs`校验七输入关系、三原件及审计摘要，独占创建新报告；当前确认模式八参数，旧待补模式七参数。详情及具体缺项见`APPROVAL_ROLE_INTAKE.md`。没有新增页面、上传接口、真实账号或配置写入；所有`canApprove`和执行开关固定false。

当前37/37家公司有明确角色证据。BP区域和北京管理不再是缺项；北京同人兼任流程交M03/总控，上海/西安各两人的路由及正式编码、既有账号对应继续落实，不默认选多人中的第一人。烟台仍按用户决定覆盖旧北京归属，兼任不搬动人员所属总部机构、不新增候选或批准存量单据。个人明细和证据仅在本任务outputs；共享模块/测试只有逻辑和合成数据。

## 2026-09-22 新材料：教师和牵头人账号准备

本次复核并承接总控184名候选：主教师181名，牵头人差集7名中4名重合、3名补入。历史9名明确不开通，lead-row-54机构采用烟台；不重新询问。范围和逐条来源缺项见新增`TEACHER_ACCOUNT_INTAKE.md`，完整个人明细仍只保留在原件与本任务私有outputs中，不放入模块或测试。

新增纯函数`web/modules/identity/account-import-preview.js`：`previewAccountImport(batch, policy, regionReference)`。batch是资料；policy由可信宿主独立提供教师/历史行段、额外牵头人行、双身份引用、机构确认及来源摘要。不能直接把上传文件内的policy或说明当授权。regionReference沿用M01已核对37家v3，只作显示名称到区域的参考，不能替代正式组织编码；总部未匹配区域不排除候选。

返回`scopeValidated`、`summary`、`rows`、`historicalExclusions`、`issues`。每行只含来源引用、机构/区域、来源字段有无状态与身份类别，不含人员姓名、来源ID值、岗位原文或账号凭据；sourceFields.name仅为固定状态枚举。来源重复/错位、范围缺行、历史混入、越界机构决定、借用编号或伪造正式账号/角色均标错。缺职务或旧ID仅保留待核对状态，不排除已确认候选，不按姓名自动去重。

**两个执行开关永远为false：`canCreateAccounts`、`canPublishConfiguration`。** 结果通过仅表示本轮来源范围一致，不表示已完成实际身份核实或账号对应，不输出可提交的users/Configuration写入对象。原有BP/负责人/培训团队账号范围不被184条替换。

离线适配`scripts/M01-account-import-preview.mjs`接受四个位置参数：候选JSON、独立确认范围JSON、区域参考JSON、新的输出路径。适配核对候选与区域文件摘要、两份不同来源原件及其摘要；输出独占创建，已有文件、符号/硬链接均不覆盖。解析/IO失败只输出固定说明，不打印可能带个人片段的原异常。测试分别为`M01-account-import-preview-check.mjs`（41场景322断言）和`M01-account-import-cli-check.mjs`（76项合成临时文件边界检查，含4次CLI子进程），均通过。不连接数据库、API、邮箱或凭据服务。

当前私有输出：`M01_账号候选确认范围_20260922.json`、`M01_账号导入准备_20260922.json`、`M01_账号准备说明_20260922.md`，均在本任务outputs。确认范围以行引用表达明确决定，不复制个人值；准备报告184条/历史9条，117条来源编号可核验，5条机构写法、1条同名异机构、61条旧表未找到继续核对，正式账号/编码映射仍为空。

总控后续接线：在原“用户与权限”页已有表格/弹窗中展示准备结果，不另建页面或导航。本轮没有新UI入口或上传路由。真实文件只供管理员在可信宿主授权后处理；显示真实候选姓名时只能使用管理员当前获准读取的原资料，不从本报告猜还原。宿主应核验原件/候选摘要，来源变动后重新复核，并处理预览生命周期及离页清理。真正开户要先核对已有users与显式人员标识、确定用户名/激活政策；审批身份、分公司和BP范围已由最新用户决定及名单明确，详见上节。明确的岗位证据仍须形成正式编码、绑定和服务端验证后的grants；讲师等级本身不授予权限。

本次不改已接线`account-bindings.js`、app.js、Api/Db/Auth/OrganizationAccessStore或公共CSS。保留CONTRACTS第七轮：真实配置事务成功后在共享锁内按身份/范围变更撤会话，失败不撤；若响应`sessionInvalidated=true`，按保存成功后需重新登录处理，不能误当保存失败重提或绕过会话撤销。账号/配置正式写入仍由总控实现并走现有服务。

## 2026-09-22：原账号表内的人员绑定组件

最新状态：总控已提供真实会话、版本化配置及数据范围服务，见`../INTEGRATION_IDENTITY.md`；下方首轮“待存储/会话接入”说明保留为历史，不再作为当前待办。本次仅新增`web/modules/identity/account-bindings.js`及定向测试`scripts/M01-account-bindings-check.mjs`，更新本说明和M01 STATUS。未改app.js、index.html、CSS、Api/Auth/Db，不再实现第二个配置存储层，也未导入真实人员或发布生产配置。

总控第二轮已接原pageUsers，并完成真实API引用保护与桌面/390px手机检查。可选`bindingColumns`替换绑定展示列，默认仍为6列；原宿主用3列双行展示，基础账号字段合并为3列，保留全部数据。版本`20260922m01bindings2`。组件107、宿主100项检查通过。

### 宿主接入方式

组件导出同步函数`mountAccountBindings(root, host)`，root使用原`pageUsers(c)`中的`#tbl`。返回`{ready, refresh, destroy}`：`ready`为首次读取的Promise；`refresh()`重新读取账号和配置；`destroy()`取消读取/保存的客户端等待、清理本组件并使旧回调失效。取消客户端等待不表示服务端已撤销提交，重新进入时必须读取当前配置。

必传宿主能力：`api`、`getUser()`、`openModal`、`closeModal`、`renderForm`、`collectForm`、`renderTable`、`bindTableActions`、`toast`。可选：原`columns`、`bindingColumns`和`actions`、`onLoaded(users)`、`isCurrent()`、`signal`。传入的api就是当前app.js函数：路径自动加`/api`、成功返回拆包data、错误使用message/status/code；组件用quiet:true自行提示。不得传入绕过认证的fetch代理或客户端自报业务身份。

以下片段由总控放在原pageUsers建立表格容器、cols/actions之后，替代旧draw/bindTableActions，不追加第二张用户表或新导航：

```js
const { mountAccountBindings } = await import('/modules/identity/account-bindings.js');
if (!isRouteCurrent(epoch, c, 'users')) return;
const bindings = mountAccountBindings($('#tbl', c), {
  api, getUser: () => state.user,
  openModal, closeModal, renderForm, collectForm,
  renderTable, bindTableActions, toast,
  columns: cols, actions,
  isCurrent: () => isRouteCurrent(epoch, c, 'users'),
  // onLoaded: rows => 更新原有用户数量/启用数量摘要，按宿主现有方式实现。
});
addRouteCleanup(() => bindings.destroy(), epoch);
await bindings.ready;
```

首次/users读取由组件负责，宿主原先仅用于账号表和摘要的重复请求可收归`onLoaded`；`/organization/me`仍可用于原“当前账号业务身份”卡片。新增/编辑账号后的原renderPage继续生效。组件对root独占，宿主必须在替换它之前调用destroy；原routeCleanups与isRouteCurrent配合使用，不仅传静态user对象。

复用原五层样式、toolbar、renderTable的手机表格、原openModal/renderForm表单。原columns/actions传入后原样保留并补绑定列/动作；没有传columns时仅提供账号、姓名、账号状态三列。新增展示为人员/机构/负责人/BP编码及人员/机构、绑定状态；接口只有编码，不能虚构真实姓名或以城市名称充当编码。

### 读取、编辑和保存边界

- 管理员并行读取`/api/users`与`/api/organization/config`。普通用户只读`/api/organization/me`，绝不先拉取管理员配置再隐藏。旧admin只决定配置维护入口，不赋予审批、承接或导出能力；服务端仍每次重验真实会话和管理员身份。
- 无配置、人员/关系字段未齐或配置读取失败时保留可读取的账号列表，停止绑定操作并明确提示。读取失败/冲突后显示“未核实”，不把未知状态显示为“未绑定”。不自动生成组织、人员、岗位、关系、权限或演示配置。
- 编辑只选现有人员编码及绑定启停。所属机构、负责人/BP、人员/机构状态只读。下拉排除被其他账号占用的人员，包括停用绑定；保留当前绑定的停用人员选项，不能因筛选而默默换人。不提供原始JSON编辑器、姓名自动匹配或解除他人绑定动作。
- 人员编码从选择框原值读取，保留字母和前导零；账号ID必须为正安全整数；启停字符串精确转为布尔值。停用账号、人员或机构不能通过本组件启用绑定。不修改账号本身的状态、密码或角色。
- 每次管理员点击“保存绑定”时生成`binding-<UUID>`新版本，用读取时的`expectedVersion`提交整份配置。只替换该账号的accountBindings记录（无原记录时追加）和version，保留其余绑定、people、organizations、roleCodes、codeRules、relations及grants，不按表格可见行重建配置。版本不是用户手填技术字段，服务器永久唯一性约束仍为最终判定。
- 只有服务端成功返回且版本与完整候选配置一致，才显示保存成功。返回对象键顺序可不同，数组内容/顺序不变。客户端防重复点击，最终完整校验与并发控制由现有Store负责。

### 失败及生命周期

409保留弹窗选择并锁住旧草稿再次提交，需关窗→刷新→重新选择核对；不更新expectedVersion后直接重放旧配置。400等明确拒绝保留输入，可由管理员纠正后重试；不会擅自删掉其他绑定来通过校验。401/403清管理数据并禁编辑。网络、5xx、无法核实的成功返回都视为结果未确认，提示先刷新核实，绝不自动重试POST。

宿主openModal的onOk通常会关闭全局当前弹窗，组件始终返回false，仅在自己的弹窗仍然有效且保存成功时调用closeModal。用户关闭后迟到的结果不能关闭另一弹窗；仍在当前页面且没有新弹窗时，通过GET重新核实。离开页面、abort或destroy后不再更新页面、提示成功或重提；重复destroy安全。

### 账号删除接点与剩余限制

原“删除”动作传入组件后，有绑定（含停用绑定）的账号及绑定状态未核实时不展示删除，旧删除回调也重新检查当前绑定。未绑定账号保留原show条件和删除处理。已经删除账号但配置仍有遗留绑定时，组件显示具体问题并停止保存，保留现有资料，不擅自清除不相关关系。

总控已在账号删除服务端实现当前配置引用保护（含停用绑定）；前端显隐不能代替此保护。本次不越权改Api/Db，也不新增删除绑定功能。审批身份、BP分区及北京管理已确认；正式编码、账号绑定和流程接入继续落实，不再索取37家公司、人员名单或已答负责范围。

### 本次验证范围

运行`node scripts/M01-account-bindings-check.mjs`，26场景99断言通过，仅使用内存合成配置和最小DOM替身，不调用真实接口。另用当前app.js的原renderTable/openModal/renderForm等控件及五层原CSS做本机合成浏览器检查，验证选人详情、保存回显、409保留输入，桌面及320px、390px手机布局；五个样式表均实际加载。正式原页面尚待总控嵌入，不能据此宣称正式UI或生产已验收。

## 首轮核心与历史接入约定（2026-09-21）

状态：首轮权限核心完成；2026-09-21已按总控新决定补齐总结权限接口，收到最新名单，并按MASTER/CONTRACTS最后一节明确回归原系统页面。材料基线：MASTER、DECISIONS.md（M08/S01用户确认）、CONTRACTS v1及最新界面补充、M01 BRIEF。基础样例仍为`demo-m01-v1`，新增接口验证样例为`demo-summary-contract-v1`；合成演示仅供内部逻辑测试，正式账号对应与原页面接入待总控处理。

## 已确认决定，不再重复询问

- 总结由培训项目的分公司对接人填写，分公司负责人和BP两方复核。先后未指定，M01不提供隐含顺序，不套用M03直接承接的负责人先审→BP后审。
- 总结由管理员账号导出，管理员为教学研发团队成员和领导。须在可信账号配置中明确列出管理员及数据范围；岗位名称、邮箱地址、旧Auth的admin字符串均不产生自动授权。
- 首次/新设备邮箱核验，后续记住设备，由S01/宿主负责；邮箱和设备核验只处理登录，M01另查账号对应及业务权限。
- 分公司/区域名单原由M03提供，共享图片位于`coordination/materials/分公司区域名单_20260921.png`。双人核对的`docs/modules/M03/ORG_INTAKE_V1.json`共5区36家，保留为历史证据，不改写；当前归属采用下述最新表。正式编码和负责人/BP账号仍为空，不能推断补齐。
- 本任务用户随后提供`群组名单.xlsx`并确认烟台是新分公司、与济南同区。当前组织对照更新为东8、南9、西7、北6、中7，共37家；源表37家全部对应。员工及一事通ID、5位BP成员均已接收；最新审批岗位、BP分区及北京管理决定见`APPROVAL_ROLE_INTAKE.md`，不再重复收集。正式研序账号对应及发布仍单独落实。
- 用户最新提供`20260803-v1-区域分公司对应表.xlsx`并说明“这是最新的”。已作为当前区域归属依据，版本`M01-ORG-DRAFT-20260921-v3`；37家公司与群组名单和已确认归属完全一致，烟台明确列在东区，无需再问。源路径、摘要及范围见`ORG_INTAKE.md`，该表未增加人员岗位或账号映射。

## 文件与边界

- `src/com/training/OrganizationAccess.java`：Java 17 纯配置校验与权限核心，无数据库、网络、Auth 静态调用或初始化副作用。
- `src/com/training/OrganizationAccessDemo.java`：独立合成样例，无真实用户、密码或会话。正式服务不得装载。
- `web/modules/identity/`：中文内存维护演示，入口 `async mount(root, context)`，返回 cleanup；只用于内部逻辑测试，`demo.html`不作为正式UI交付，不在正式导航挂载。正式页面复用下述原系统接点。
- `scripts/M01OrganizationAccessTest.java`、`scripts/M01-check.sh`：隔离编译、纯合成定向检查。
- `scripts/M01*` 中的前端检查：见本模块 STATUS 的实际验证记录。

未改 Main、Api、Db、Auth、Json、前端主入口、公共检查脚本或其他模块文件；未提交/暂存，未部署，未创建生产数据。

## 后端调用约定

`Configuration` 为不可变快照，字段见 Java record。编码均保持字符串；`AccountBinding.accountId` 是现有 users.id，必须通过显式一一绑定找到人员编码。`Organization` 的 parent 仅用于结构校验及停用检查，不产生父子机构权限继承。

`validate(configuration)` 返回 `Validation.issues`，包含机器可读 code、字段 path 和中文 message。缺规则记 `NOT_CONFIGURED`，重复编码、循环、悬空引用、关系目标岗位/负责范围不符等记具体错误。构造 `Engine` 后整个快照必须通过校验才可能允许访问，不能静默忽略错误行。

```java
OrganizationAccess.Engine engine = new OrganizationAccess.Engine(trustedConfiguration);
OrganizationAccess.SessionVerifier verifier = credential -> {
    Auth.Session session = Auth.get(credential); // 每个请求验真，不缓存会话对象
    return session == null ? java.util.OptionalLong.empty()
            : java.util.OptionalLong.of(session.uid);
};
OrganizationAccess.Decision decision = engine.authorize(
    tokenFromExistingRequest,
    verifier,
    new OrganizationAccess.Resource("training.record", storedRowOrganizationCode),
    OrganizationAccess.Action.VIEW
);
if (!decision.allowed()) {
    // 由宿主统一映射 HTTP 状态与中文错误；不继续执行业务动作。
}
// Json.write(decision.toMap()) 可序列化结果，不直接序列化 Java record。
```

此片段是总控的集成建议，未写入现有 Api/Auth。`SessionVerifier` 必须来自可信服务端代码，不能接受用户请求传入的 verifier、accountId、personCode 或“已认证”标记；没有认证替代方案/默认验证器。传入人员编码或账号数字本身不会被核心当作登录。

Auth/宿主须在首次/新设备邮箱核验完成，或服务端确认已记住设备仍有效后建立可信会话；S01负责渠道协作，不创建第二套认证。M01每请求核实会话，再查绑定、人员、岗位、机构状态；邮箱已核验但未对应正式账号/人员，继续返回未配置或未认证。不能把邮箱当personCode、设备ID当accountId，或由前端emailVerified/trustedDevice跳过检查。S01已确认163发件资源和五步岗位通知，邮箱归属方式、记住期限及撤销等仍待宿主/S01梳理；M01未实现发邮件、验证码或设备凭据，不索取邮箱密码。

S01通知收件人的userId沿用账号ID十进制字符串，由宿主经显式AccountBinding把M03人员编码对应到唯一启用账号。通知的active/canView仍需检查当前人员、机构范围和业务记录状态；事件收件人名单不反向授予权限。S01目前通知目标只有M02/M03，新增总结权限接口并不表示M08通知已接入。

## M08 总结权限接口（待总控统一接入）

已实现`SummaryPermission`和`Engine.authorizeSummary(credential, verifier, permission, storedOrganizationCode)`，复用现有安全失败及范围检查。每个操作采用独立资源键，避免一次HANDLE授予全部填写/复核能力。

| M08 Capability / M01 SummaryPermission | 资源键 | Action | 已确认责任 |
| --- | --- | --- | --- |
| READ | summary.read | VIEW | 项目可见范围仍须明确配置 |
| EDIT | summary.edit | HANDLE | 对应项目的分公司对接人 |
| REVIEW_BRANCH | summary.review.branch | HANDLE | 对应分公司负责人 |
| REVIEW_BP | summary.review.bp | HANDLE | 对应项目/分公司的BP |
| EXPORT | summary.export | EXPORT | 已登记管理员账号（教学研发团队成员和领导） |

资源键是M01提供的接口键，尚非上线公共路由；宿主按枚举接入或由总控统一调整。`training.record`等原合成Grant不转成总结Grant；正式映射/范围未配置时真实总结权限仍拒绝。管理员无全机构旁路，邮箱核验不生成上述任何能力。

M01 Decision只返回组织级判断，不返回M08 Actor或项目ID集合。宿主先在可信配置中用认证账号查AccountBinding和Person，再从业务记录读projectId、机构归属、项目对接人及负责人/BP关系；逐项目逐能力调用authorizeSummary，与项目关系、在岗及业务状态相交后构造M08 Actor。不能把同机构全部项目自动放入范围，也不能接受客户端自报actorCode/capabilities/projectIds。M03 actorId同样经可信绑定解析，不用姓名或数字uid代替人员编码。

M08可展示缺失branchCode，但正式填写/复核/导出须在服务端解析可信所属机构；无法解析时返回NOT_CONFIGURED，管理员也不能绕过。M08维护两方复核版本和证据；M01不设顺序，也不把权限通过当作“两方已通过”。兼任、自审、代理、退回等未确认规则不能从M03复制到总结。

目标机构取自服务端业务记录；请求参数中的机构值只能用于查找候选数据，不能作为已核实归属。对于列表/批量处理/导出，应逐记录应用相同 scope 或等价受约束查询，防止仅校验请求声称的机构。不存在 admin 角色旁路，旧 `Auth.isAdmin/canWrite` 不能替代新权限检查。

状态：`ALLOWED`、`DENIED`、`NOT_CONFIGURED`、`INVALID_CONFIGURATION`、`UNAUTHENTICATED`、`AUTHENTICATION_UNAVAILABLE`。所有非 ALLOWED 状态均不允许。返回的 `configuration_version`、`matched_rule_ids` 可供服务端审计；凭据及用户验证器异常内容不会返回。

## 显式规则语义

- CodeRules 配置机构、人员、角色正则；完整匹配、区分大小写，不自动去空格或把字符串转成数字。正则只允许可信管理员配置，不能取自普通业务请求。
- 每个角色必须明确给出 leader 和 BP 的 RelationRule：是否必需、允许的目标岗位、是否须明确负责源人员所属机构、是否允许关联本人。可选不等于未配置；缺少规则无法推断。
- 关系校验不生成审批顺序，也不赋予权限。多角色人员须同时满足各岗位关系规则。启用人员引用的负责人/BP 须启用且机构有效。非自引用的负责人循环始终无效。
- Grant 精确指定 resource、VIEW/HANDLE/EXPORT、ALLOW/DENY、scope。OWN_ORG=本人所属机构；RESPONSIBLE_ORGS=本人明确负责机构；NAMED_ORGS=该规则明确列出的机构。无全局通配或自动层级扩张。
- 多角色命中范围的 ALLOW 可合并，但任一命中同资源/动作/目标机构的 DENY 优先；规则顺序无影响。缺动作或资源规则返回未配置；有规则但范围不符返回拒绝。
- 账号绑定停用、人员停用、所属或目标机构及其祖先停用均拒绝。本轮账号绑定为一一映射；多账号绑定需求必须先明确合同再修改。
- 核心只决定该动作的组织权限。审批状态、字段权限、记录所有权等细粒度业务条件仍由所属模块检查。EXPORT 允许也不提供实名解码、原始简历或额外字段的导出许可，须遵守总控“仅编码化导出”。

## 配置更新与持久化

本轮无数据库 DDL、迁移、导入接口或永久保存。总控可先从可信应用配置构造 record；JSON/Excel 接入器必须明确类型转换和重复行规则，不可去重后掩盖冲突。正式编码与未分配编码不得用合成编码填补。

后续保存时先校验整个候选快照，校验通过后在统一锁内原子替换；与业务变更相关的校验/更新统一进入 `Api.MUTATION_LOCK`，多步写入使用 `Db.transaction`。账号撤销仍依赖每次 Auth.get；组织/岗位变更必须及时发布新快照。正式来源丢失/失效时，改用无效/空快照拒绝请求，不延用旧快照继续放行。DDL 由总控在持久化方式确定后统一接入。

## 原系统正式页面接点（总控实施）

已读取MASTER/CONTRACTS“沿用原系统界面与流程”补充。M01是开发分工，不是新增导航。正式界面继续使用`web/index.html`和`web/app.js`的“系统 → 用户与权限”：`NAV`中的`users`（当前app.js:928）→`renderPage`→`pageUsers(c)`（当前app.js:3990）。该页已有账号表格、新增/编辑/重置密码弹窗及启停字段，没有独立组织入口，也尚未提供筛选。不要另开M01落地页、替换配色或将identity demo挂为正式页面。

| 原控件/接点 | 复用方式及必要变化 |
| --- | --- |
| index.html原样式链 | 保留style.css、studio.css、ledger.css、v10.css、v13.css及原加载顺序，沿用摘要卡、card data-card、card-heading、按钮和手机适配；不加载demo样式替代它们 |
| pageUsers账号表格 | 复用renderTable/bindTableActions及tag状态标识；局部显示人员绑定、所属机构和业务岗位/授权状态。未绑定显示“待配置”，不以姓名或一事通ID代填账号绑定 |
| 新增/编辑弹窗 | 复用openModal、renderForm、collectForm；在已有账号字段旁补充必要人员编码、机构、负责人/BP和负责范围字段。业务关系仍保存到显式配置，不把旧role字符串改解释为新岗位 |
| 必要的名单筛选 | users页目前无筛选，可复用pageCrud现有搜索/清除、状态筛选、防抖及取消请求方式，局部补充关键词和机构筛选；不引入第二套筛选风格 |
| 多岗位/多负责机构 | 原renderForm仅支持单选，若正式维护需编辑集合，局部增加同风格多选采集；不能以逗号拼接字符串代替结构化校验 |
| 页面权限说明 | 当前“管理员拥有完整权限”需改为明确账号角色与业务岗位/机构范围分别配置；系统admin、manager、viewer不自动产生总结导出或全公司业务授权 |

公共`collectForm`目前会统一trim；编码必须按已确认规则保留字符串，不能静默改写后再冒称来源值，尤其保留前导零和字母。必要的局部采集由总控处理，不在模块内改公共函数。账号编辑不跳过服务端整个配置校验；绑定、岗位关系和机构范围保存成功后再刷新原列表。维持原有账户操作习惯，新业务越权/未配置原因用现有toast和表单提示呈现。

师资推荐中的“机构城市参考”（app.js约3629行）仅提供授课城市参考，不是组织权限管理入口，不复用为授权依据。原用户页的角色导航门槛只控制显示，后端每次调用M01并追加业务校验；不能用前端显隐代替授权。

上述公共文件修改由总控统一接入。完成后须在原系统真实浏览器检查五层样式实际加载、桌面/手机筛选和弹窗布局，以及保存失败保留输入、停用/未配置拒绝业务操作；内部demo测试不代表正式UI已验收。本次未改原页面或正式账号。

## 内部演示维护约定

内部测试页可加载`web/modules/identity/styles.css`和`index.js`并调用mount；这不是正式前端接入方案。context严格采用公共合同：mode、signal、request、user、notify、navigate。销毁时调用返回的cleanup或abort signal。

- demo 只用合成数据，编辑、筛选、启停、权限模拟均留在页面内存，刷新恢复初始数据；没有业务 request、localStorage 或生产提交。
- live 显示“正式服务尚未接入”，不回落合成数据，不从 context.user/personCode 推断认证。
- UI 的前五个人与 Java 基础样例对齐，额外第六人和未配置权限岗位为前端边界演示；UI 限单角色、无真实会话验证，不能复用为后端鉴权。
- 未接主应用路由、菜单、正式API、现有用户编辑页或移动渠道；正式UI按上一节回到原“用户与权限”页，公共文件由总控接入。

## 剩余材料与沟通方式

用户反馈上一版提问太复杂。以后一次一件事，优先复用现有表，使用普通业务语言；已收的分公司/区域表、总结分工、登录方式均不重问。

人员名册已由`群组名单.xlsx`提供，不再让用户重述A/B/C。审批身份、五位BP负责区域及北京管理均已明确；后续仅按具体事项衔接多人/兼任流程、项目对接人、管理员范围及研序登录账号对应。当前烟台归区问题已由用户回答，不重问；原表D264编号空白及邮箱页额外公司仅留单项差异，不自动补数据。

内部保留但不一次性催问的缺口：正式编码已有规则、项目可见范围、账号启停归属、旧简历/公开资料处置。总结复核先后本轮仍未指定；设备期限/撤销归S01，不把这些技术问题一起抛给用户。

M03历史核对结果已承接，当前区域→分公司结构以用户最新Excel的37家为准，人员账号仍单独核实；M02/M03读取负责人/BP及所属机构，M08读取明确总结能力并追加项目范围，S01/宿主提供可信会话。材料更新后在STATUS登记来源、版本、变更字段、跨模块影响及复验结果。


## 2026-09-23 分岗位机构范围及显式兼任公共接线

总控新增OrganizationAccess.Person.responsibleOrganizationsByRole，每个岗位分别列出负责机构；任一映射存在时须完整覆盖本人roleCodes，允许明确空集合，总体responsibleOrganizationCodes须为分岗位范围并集，仅作关系展示兼容。RESPONSIBLE_ORGS权限与关系覆盖实际按匹配岗位取范围，不回退并集。旧七参数Person和完全省略新JSON字段保留旧历史语义；新增八参数构造器的null映射/成员以及JSON显式null、空对象、半缺映射均不降级旧语义。

Configuration新增combinedApprovals，每条明确organizationCode/personCode/leaderRoleCode/bpRoleCode/evidenceRef。同一机构不得重复，两个职责对应本人不同岗位且各自负责该机构。不从相同人员ID推定。旧八参数Configuration及省略字段保持无兼任依据；新增九参数null拒绝。组织配置仍只经原管理员/唯一Store/全量校验/CAS/事务发布，新字段变更撤会话，版本单独变化不撤。

OrganizationAccessStore.combinedAssignment(org,approver)只返回当前可信配置中匹配记录，检查启用人员/所属组织/唯一启用绑定/真实账号状态，两个岗位各自approval.review HANDLE明确允许，任意命中禁止优先。返回冻结机构、人员、目录版本及依据引用供M03使用；依据字段不能来自审批HTTP请求。M03策略保留最初版本，当前相关职责改变时只读暂停，不改历史或自动换人；配置版本单独变动但职责依据相同仍可用。

原用户绑定弹窗增加只读各岗位机构与兼任机构，保存绑定完整保留这些扩展字段。当前仅实现能力，不代表184真实人员已开户或授权。M01名册准备仍以9月22日v2已验收文件为准，不再索名单/分区/归属。
