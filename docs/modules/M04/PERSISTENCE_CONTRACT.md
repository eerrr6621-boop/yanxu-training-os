# M04 持久化接口与组织归属方案 2026-09-22

本方案先于适配实现提供给总控。目录不默认全局可读写：每个目录空间保存唯一管理机构编码；跨单位共用同一目录通过M01对该管理机构的显式catalog.read / catalog.manage授权实现，不复制一套全局权限。真实管理机构尚未指定，因此init只建空表，不创建任何真实目录空间或授权。

首轮不开放客户端创建或变更目录归属的HTTP接口。总控确认管理机构后，在可信后台显式调用包级registerScope(Auth.Session, organizationCode)；该方法从M01已保存配置查到真实启用机构，再要求catalog.manage(HANDLE)，不会按admin身份放行。该接点仅提供给总控配置流程，测试使用隔离合成机构。机构归属一经保存不提供迁移接口；所有业务请求只传scope_id，授权机构取自服务端scope记录，拒绝请求中的organization_code/personCode/uid等冒充字段。

这解决整体公共目录的归属问题且不猜实际机构代码。若总控另选单一公共目录托管方式，只需明确批准的管理机构后注册一个空间，给相关业务岗位配置对该机构的资源授权。本轮不发布任何生产配置，不把各分公司自动变成独立目录。

## 接点及已实现路由

`public static void init() throws SQLException`；`public static boolean handle(HttpExchange,Auth.Session) throws Exception`。总控在users/teachers/M01表初始化后、事务外调用init；在Api登录检查后、通用CRUD前调用handle。复用真实Auth.current、OrganizationAccessStore、Api.MUTATION_LOCK、Db.transaction；不改公共文件或前端。

- GET/HEAD `/api/course-catalog/scopes`：只返回调用者已有read或manage授权的已保存目录空间，无配置/无空间不返回假数据；不泄露其他机构/师资。
- GET/HEAD `/api/course-catalog/scopes/{id}`：catalog.read(VIEW)，返回当前版本及编码目录/绑定，版本0为NOT_CONFIGURED。
- POST `/api/course-catalog/scopes/{id}/preview`：catalog.manage(HANDLE)，精确请求 `{expected_version,catalog,bindings,change_comment}`。catalog沿用m04_catalog_v1；bindings为每名目录讲师显式`{teacher_code,teacher_id}`，teacher_id必须为现有teachers.id，不接受显示编号/名字替代。仅完整校验通过时保存待确认批次，不修改当前目录；失败整批无业务写入。
- POST `/api/course-catalog/scopes/{id}/confirm`：catalog.manage(HANDLE)，请求 `{batch_id,expected_version,confirm:true}`。仅原预览账号及相同绑定人员可确认。依据服务端已存批次复验M01配置、授权、版本、绑定及讲师事实后原子发布新快照；不能随确认附加/替换目录。batch_id为幂等标识；重复确认同一已确认批次不重复追加版本/事件。
- POST `/api/course-catalog/scopes/{id}/qualify`：catalog.read(VIEW)，请求 `{expected_version,request}`；request沿用course_code/as_of/accepted_levels/allowed_cities。只读取服务端当前目录，决不接受客户端certified或eligible。无目录返回NOT_CONFIGURED，无该课认证按核心返回缺口。
- GET/HEAD `/api/course-catalog/scopes/{id}/history`：catalog.manage(HANDLE)，分页返回确认批次/修改摘要与操作者编码。历史具体快照按version精确读取并使用同一管理权限。查询参数仅允许limit（默认20，上限100）及before_version（正安全整数、不含该版本），返回events及next_before_version；GET/HEAD `/api/course-catalog/scopes/{id}/versions/{version}`精确读取历史快照，仍要求manage。禁止重复/未知查询字段。

## 数据与明确限制

采用完整目录版本快照，保留认证来源/有效期、每次修改说明、确认者账号及人员编码、组织配置版本和时间。编码绑定在空间内一对一且不可静默改绑；目录删行不删除历史绑定/认证版本，纠错改绑需后续明确流程。映射只用于当前目录对既有共享teachers档案的引用，不修改老师所属机构或基础资料，也不据此开放全局师资查询。

导入讲师等级/城市须与显式绑定的现有teachers记录一致；资格判定再次使用当前teacher_level/base_city与在库状态，防止客户端提高等级或用旧快照绕过出库。认证本身仍需维护人员对来源负责；办法已获用户“按这个执行”采用授权，但仍不自动颁发逐课认证或晋级，原文未给的认证事实不能推断。

所有层级精确白名单、safe JSON integer、布尔类型和编码字符串保真。预览/确认分离；事务内重验并以UPDATE计数做版本CAS；强制失败时批次/快照/绑定/事件全部回滚。整轮操作用共享锁，不允许嵌套事务。当前M04旧模型和原推荐/交通/并列规则保持，正式UI仍由总控在原页面局部接入。

## 响应与冲突约定

所有成功响应使用宿主 Api.ok，即外层code=0/data。拒绝使用宿主ApiException：401会话无效，403无绑定或无机构授权，400输入形状/类型无效，404空间/批次/历史版本不存在，405方法无效，415非JSON，409预览已过期或绑定/材料版本冲突。存储完整性异常失败关闭，不回落合成数据。

- scopes响应：status（READY/NOT_CONFIGURED）、scopes[{scope_id,organization_code,version}]；仅可管理但不能读目录的人员也可见其空间概要，读完整目录仍需read。
- 当前/历史快照：status、scope_id、organization_code、version、catalog、bindings。version=0时catalog=null、bindings=[]。已确认空课程目录也标NOT_CONFIGURED。
- preview响应：沿用核心schema_version/ready/issues/counts/data/catalog_version，并补current_version、batch_id、confirmation_required；只有ready=true才有batch_id/changes并持久化待确认批次。核心数据校验失败返回ready=false及逐行问题，整批不入库；绑定或外层格式错误按400/409拒绝。
- confirm响应：status=CONFIRMED或ALREADY_CONFIRMED、scope_id、version、current_version、batch_id。首次确认含changes。重放必须携带原expected_version，必须还是同账号、同人员且当前仍有manage权限；无需重复发布，也不覆盖之后的版本。未确认批次在M01配置版本、目录版本或绑定师资事实变化后返回409，必须重新预览。
- qualify响应：沿用核心schema_version/ready/issues/catalog_version/course_code/as_of/eligible/gaps/ranking_status，补scope_id、version、status、metadata_source=current_teacher_record；每名结果补teacher_id，只能来自持久化显式映射。无目录为NOT_CONFIGURED、ready=false、空eligible/gaps；参数语法不合法为400；具体课程不存在/停用时没有候选，已配置目录标INVALID_REQUEST并返回问题。缺失/未知/撤销/未认证/过期分别给缺口；已出库给TEACHER_NOT_ACTIVE。不返回姓名、电话或履历。
- history响应：scope_id、current_version、events及next_before_version；事件含确认账号/人员编码、M01版本、时间、修改说明和changes。changes按courses/teachers/certifications/bindings列出added/updated/removed编码，认证以teacher_code/course_code表示。完整旧值可按版本快照还原。

## 存储和宿主接线

新增五张以m04_开头的表：catalog_scopes保存归属和当前版本；catalog_batches保存通过校验的预览、原操作者和当时师资事实；catalog_revisions保存不可复用的材料版本及完整历史；teacher_bindings保存稳定的一对一外键；catalog_events保存确认审计。实际表名依次为m04_catalog_scopes、m04_catalog_batches、m04_catalog_revisions、m04_teacher_bindings、m04_catalog_events。不插入真实目录或授权，不复制既有师资档案。

读取已发布快照还会核对永久编码映射；历史JSON里的合法teacher_id若被误改，不能将旧认证套给另一讲师。teachers外键阻止删除仍有目录绑定的档案；已只读确认总控在Api删除入口加入M04引用检查并提示办理出库、保留档案。本模块未修改公共删除接口。M04写入口拒绝嵌套Db.transaction，避免共享单连接提前提交。

总控需在现有Db.init完成users/teachers与M01建表后、事务外调用CourseCatalogIntegration.init；Api已验证登录后、通用CRUD前调用CourseCatalogIntegration.handle(ex,s)。Api继续负责1 MiB请求限额、严格JSON解析/重复键拒绝、Content-Type和响应发送。模块直接handle测试不能冒称已覆盖宿主前置请求解析。

原系统UI仍按INTEGRATION增量接入。标准课程资格必须在完整师资候选池中先筛，再进入原TeacherIntelligence交通/评分/前三及同池同分保留流程。总控负责公共init/handle挂载，当前已可在共享源码看到接点；原师资查看按钮和推荐链路仍由总控接入，不把资格结果当作外部授课授权、课程安排或派单结果。

验证状态以M04 STATUS为准。真实管理机构及M01授权仍按实际配置处理，不猜定；具体课程与认证材料可后补，暂不催整套目录。已提供管理办法的明确条款已获用户“按这个执行”授权，不再等待同一确认；确认时间不冒充原始发布日期，不追溯历史费用。需求分类不构成课程或认证，未选标准课的原推荐可继续，明确选课后的未知资格仍不合格。

## 2026-09-22 可信推荐调用与结果复核

总控已接受固定管理机构归属方案，并负责挂载空表init/handle和教师删除引用提示。实际机构仍未确认，不生成空间、不自动授予M01权限。本轮只交付目录/资格查看组件，不向用户开放正式导入确认操作。

新增包级可信入口，HTTP `/qualify` 也调用此方法：

```java
static Map<String,Object> qualification(
    Auth.Session session, long scopeId, long expectedVersion,
    Map<String,Object> request) throws Exception;
```

每次在 `Api.MUTATION_LOCK` 内通过 `Auth.current` 和 M01重验真实会话/人员，再从已保存scope取机构校验 `catalog.read` / VIEW，要求当前目录版本等于expectedVersion；身份、机构和资格数据均不能由请求覆盖。request仅允许course_code、as_of、accepted_levels、allowed_cities。无可注入候选列表/eligible/目录参数；并不按旧管理员身份放行。方法是只读操作，不创建或确认导入批次。宿主若从推荐请求JSON提取scopeId/expectedVersion，须先按安全整数校验原值，再转long；不能用Json.lng等自动截断小数或把字符串转数字。M04自己的HTTP入口已执行该校验。

请求语法/日期/类型在所有目录版本统一校验，错误为400。课程不在已配置目录/已停用为ready=false、INVALID_REQUEST且没有候选；未配置为NOT_CONFIGURED、ready=false、空候选。老师状态、等级、城市从当前teachers重读；绑定永久映射和完整目录快照仍校验。失败或空结果不得回落整个原候选池。

成功完成核验（包括明确未配置/课程不可核验）的结果增加 `qualification_context`：

| 字段 | 含义 |
| --- | --- |
| schema_version | 固定m04_qualification_context_v1；本计算语义变更应升级此标识 |
| scope_id / organization_code / version / catalog_version | 保存的空间、所属机构、当前数字版本和材料版本；未配置材料版本为null |
| identity_version / account_id / person_code | 本次真实会话对应的当前M01配置、账号ID和人员编码；没有密码或会话令牌 |
| course_code / as_of / accepted_levels / allowed_cities | 本次具体课程和日期、精确允许值；两个数组排序归一，顺序不同不影响含义 |
| criteria_id | 对规范课程条件的SHA256；不单独代表空间、版本或授权 |
| catalog_snapshot_id | 存储目录完整快照与绑定的摘要，覆盖认证来源/状态/有效期，避免同版本底层数据错误变动遗漏 |
| teacher_facts_id | 当前本目录绑定讲师的存在性、ID、等级、城市和在库状态摘要；按teacher_code排序 |
| context_id | 对上述完整上下文的摘要，用于比较两次核验是否仍一致 |

哈希为64位小写十六进制，是变化标识，不是授权凭据。返回对象可供宿主保存结果上下文，但不能信任客户端发回的context_id/eligible作为资格结论。新会话即使同一账号也必须重新验证；权限变更、解绑、注销或停用会在重新调用时被拒绝。只比较旧哈希而不重新调用帮助方法无效。

总控推荐流程接法：

1. 显式选定标准课程时，在开始评分前调用qualification。只有ready=true、status=READY时，将返回eligible中的teacher_id与原**完整在库师资池**取交集；不增加其他候选，不使用目录顺序取前三。
2. 在这个集合上继续既有评分、交通、人数和同池同分保留规则。保存本次qualification_context，并将context_id纳入现有缓存键。它只覆盖M04语义，原画像、评分、交通、授课冲突等缓存条件仍须保留。
3. 异步评分完成、缓存命中准备返回时，在共享锁内再次以**真实当前会话、原scopeId、原expectedVersion、原条件**调用qualification。401/403/409或context_id变化，都丢弃旧候选并要求重新核验。不要把旧认证结果带进新版本。
4. 通过后在同一次受锁保护的返回装配中附上新qualification_context与必要缺口；继续原宿主其他状态复核。不要持有共享锁等待模型/交通网络请求，也不要在业务事务提交前将未提交状态生成的结果作为已发布缓存。

目录没升级但当前师资出库/等级/城市变化，会改变teacher_facts_id及context_id；换条件、材料快照、M01版本或人员会改变对应上下文。讲师资料改回相同语义后哈希可恢复原值，这只表示当前资格输入相同，不表示不需重新认证。资格仍不代替外部授课授权或最终排课准入。

只读组件入口为 `web/modules/course-catalog/index.js` 的openCourseCatalog / mountCourseCatalog；按同目录README向原弹窗注入宿主依赖。ready资格响应必须有context v1且条件/版本/机构/绑定/完整师资集合一致；没有本地核验或旧协议回退。此轮Java413项、只读组件47项通过；原按钮及推荐链路的实际挂载、浏览器与异步返回联调由总控继续。
