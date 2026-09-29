# M04 第十一轮：当前已绑定讲师的逐课认证维护

2026-09-23。本轮仅修改CourseCatalogIntegration，新增本说明、M04CertificationMaintenanceTest及本模块STATUS。只维护当前目录已固定绑定讲师的逐课认证，不新建讲师编码或绑定、不读取真实app/data、不重导181名教师。总控负责原界面及真实HTTP接入。以下接口字段冻结供并行集成。

## 读取当前维护材料

新增`GET /api/course-catalog/scopes/:id/certification-management`，亦接受HEAD，无查询参数。仅要求该机构M01 `catalog.manage/HANDLE`；manage/read互不隐含，旧admin不自动获得权限。成功响应沿用Api.ok外层，data精确为：

```text
{
 scope_id:number, organization_code:string, version:number,
 catalog_version:null|string,
 courses:[{course_code:string,course_name:string,active:boolean}],
 teacher_codes:[string],
 certifications:[{
   teacher_code:string,course_code:string,status:string,
   source_ref:string,valid_from:string,valid_to:string
 }],
 retained_counts:{courses:number,teachers:number,bindings:number},
 can_manage:true
}
```

`teacher_codes`明确为字符串数组，来源仅是当前目录的teachers，并用当前snapshot.bindings与永久绑定逐项验证，不包含已从当前版本移除的历史绑定编码。机构字段统一为`organization_code`。不返回教师姓名、人员编码、teacher_id、当前档案或完整绑定内容。

仅version==0视为尚无snapshot：catalog_version=null，三个列表为空，三个计数均0；读取不创建scope。version>0即使courses=[]、原status为NOT_CONFIGURED，仍保留已存材料版本、教师编码和绑定数。完整校验已存payload/schema/认证引用/材料版本/固定绑定，损坏整体409，不当成空目录。当前teacher等级/城市漂移不影响读取旧快照，不自动刷新metadata；后续预览沿原validateBindings返回409。

## 编辑认证预览

新增`POST /api/course-catalog/scopes/:id/certifications-preview`，Content-Type须application/json，无查询参数。body必须且只能为：

```text
{
 expected_version:number,
 catalog_version:string,
 certifications:[{
   teacher_code:string,course_code:string,status:string,
   source_ref:string,valid_from:string,valid_to:string
 }],
 change_comment:string
}
```

expected_version为非负安全整数且等于当前版；catalog_version为1..120字符的新材料版本，不得复用该scope已发布值；change_comment为1..500字符。各认证行必须且只能有上述六字段。完整提交认证列表，最多5000行；讲师、课程编码沿原ASCII格式且必须引用当前目录teachers/courses，历史永久绑定不能作为新增认证依据。重复讲师—课程对无效。

所有当前认证的teacher_code/course_code组合必须保留，不允许删除或换键；撤销使用status=`revoked`。可新增当前已绑定讲师与当前课程的组合，也可更正原认证字段。停用课程的认证允许保留或更正，资格仍由原核心判为课程不可用。status精确为`certified`、`not_certified`、`unknown`、`revoked`；后三者均不合格。

source_ref最长240字符，certified必须有非空依据；valid_from/valid_to为空字符串或原严格YYYY-MM-DD日期，起止顺序按原规则。缺少有效期沿原规则给出警告，不填默认日期或永久有效。文本/日期/格式/不可见字符等由原核心校验，不弱化、不推定认证。

服务器在同一Api.MUTATION_LOCK内取当前可信snapshot的courses/teachers/bindings，拼装完整目录并调用原preview事务。客户端不能提供这些保留字段、完整catalog或teacher_id。保存batch之前，在原事务内再次断言：courses/teachers/bindings的added/updated/removed全部为空；certifications.removed为空；certifications.added或updated至少一项非空。仅重排、仅修改材料版本/说明、无认证字段实际变化均409且不存批次。

响应与courses-preview一致：

```text
{
 schema_version:"m04_preview_v1",catalog_version:string,ready:boolean,
 issues:[原行级问题],counts:{courses,teachers,certifications},
 data:ready时的原完整编码目录对象或null,
 current_version:number,batch_id:string|null,confirmation_required:boolean,
 changes:ready时的原差异对象或null,
 scope_id:number,organization_code:string
}
```

changes为原courses/teachers/certifications/bindings四项，每项包含added/updated/removed数组。ready=false时batch_id=null、confirmation_required=false、changes=null，不保存批次。删除/换键已有认证优先409，即使会引发引用错误；其他无效认证字段沿原核心返回ready=false。成功预览只保存待确认batch，不发布。

## 确认、权限与兼容

沿用原`POST /api/course-catalog/scopes/:id/confirm`，body `{batch_id,expected_version,confirm:true}`。确认仍检查同一预览账号及人员、当前manage权限、M01版本、目录CAS、绑定及teacher_facts；在原事务发布revision/event并标记batch。同批重放ALREADY_CONFIRMED，不增加版本或事件。历史与资格查询继续原协议。

会话失效401、无人员/机构授权403、scope不存在404、顶层请求结构/字段集合及expected_version/change_comment格式错误400、方法405、非JSON415、过期版本/删除换键/无实改/当前师资不一致/损坏快照409。catalog_version及认证列表/行字段错误沿原核心返回200、ready=false和行级issues；已有认证对缺失或换键优先409。原全量preview与courses-preview行为保持；本轮不把新增删除限制施加到旧全量入口，不改CourseCatalog核心、不增加DDL。

原界面可展示当前讲师编码和课程选项，维护六字段，保存差异预览后显式确认；编辑、切换空间或版本后丢弃旧batch。未有当前固定绑定讲师时展示缺少维护条件，不自动给实际教师配码或建绑定。真实工作库保持原状，本轮合成测试不替代总控HTTP/UI验收。

## 验证状态

契约已先冻结；CourseCatalogIntegration与当前共享全部Java编译通过。新`M04CertificationMaintenanceTest`独立专项**375项通过**；未修改的`M04CourseMaintenanceTest` **236项通过**，原`M04IntegrationTest` **416项通过**。编译及测试均针对本轮冻结后的适配器，未因测试修改核心。独立只读审查未发现明确功能缺陷。

新专项覆盖真实Auth/M01管理与读取独立授权、跨机构/旧admin拒绝；版本0和已发布空课程；当前教师子集与历史永久绑定隔离；严格顶层及六字段认证行、未知引用/重复对、来源与日期校验、5000接受及5001明确LIMIT；未知/未认证/撤销不合格、停用课程、有效期边界及依据日期更正后的资格；服务器保留非认证三项；删除/换键/重排/无实改拒绝；坏JSON/schema/认证引用/材料版本/快照及永久绑定损坏整体409；读取不因档案漂移刷新、预览/确认仍拒绝漂移；原预览账号及人员、M01版本、CAS和重复确认幂等。人员重新映射由真实M01撤销旧会话，旧会话401，重新登录后同账号不同人员或同人员不同账号确认403。

新测试入口`com.training.M04CertificationMaintenanceTest`，参数为一个新建空测试数据目录。用既有Java17/ECJ及app/lib依赖编译共享全部Java和该测试到独立临时classes，再以H2运行；不得传实际app/data。所有数据均为合成机构、账户、教师及证据，无服务启动、无真实工作库/名册读取、无实际授权或认证创建。总控的原页面及真实HTTP集成验收仍需单独完成，本轮核心专项不替代该验收。
