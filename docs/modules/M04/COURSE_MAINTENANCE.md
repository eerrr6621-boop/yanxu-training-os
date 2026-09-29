# M04 第十轮：仅课程新增、改名及启停

2026-09-23。本轮仅改CourseCatalogIntegration，新增专属合成测试及本文/STATUS。181名教师已经总控接收，184账号保持原状，不重导、不读实际app/data。本轮不做讲师编码/认证编辑、教师lookup、权限配置、scope创建或教师基础资料修改。总控负责原界面及真实HTTP集成。

## 服务端能力与读取

原`GET /api/course-catalog/scopes`外层协议不变，每个scopes项变为：

```text
{scope_id:number,organization_code:string,version:number,can_read:boolean,can_manage:boolean}
```

分别实时查询M01在该scope所属机构上的`catalog.read/VIEW`与`catalog.manage/HANDLE`。有任一能力才列出该scope；manage不隐含read，旧admin不自动获权。原目录读取/资格查询仍要求read，历史/确认仍沿用原manage限制。

新增`GET /api/course-catalog/scopes/:id/course-management`（亦接受HEAD），只要求该机构manage，data精确为：

```text
{
 scope_id:number, organization_code:string, version:number,
 catalog_version:null|string,
 courses:[{course_code:string,course_name:string,active:boolean}],
 retained_counts:{teachers:number,certifications:number,bindings:number},
 can_manage:true
}
```

当前版由服务器完整校验目录payload/schema/材料版本及显式永久绑定，不能把损坏来源当空目录。0版为catalog_version=null、courses=[]、三个保留数全0；不会创建scope/目录/权限。courses只输出编码/名称/启用，不输出姓名、人员编码、路径或师资编码绑定内容。已有师资档案等级/城市变化不暗改已存snapshot；纯读取允许查看原课程，后续预览须先通过原资料一致性校验。

## 课程编辑预览

新增`POST /api/course-catalog/scopes/:id/courses-preview`。Content-Type须application/json，无查询参数。body必须且只能是：

```text
{
 expected_version:number, // 非负安全整数，必须等于服务端当前版
 catalog_version:string, // 新材料版本，1..120字符，不得复用该scope已发布的值
 courses:[{course_code:string,course_name:string,active:boolean}],
 change_comment:string  // 1..500字符明确修改说明
}
```

courses沿用原核心约束：最多5000行，course_code原样保留大小写/前导零，ASCII `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`；名称1..200字符；active严格布尔。课程行只能三个字段，重复/坏行等由原核心返回行级issues。版本/文本限制、不可见字符等沿用原校验。

本次提交完整课程列表。所有当前course_code必须仍在列表里，不允许删除或换码；不用的课程请设active=false。新增编码只新增课程，不生成任何讲师认证。新增/改名称/启停才是有效改动；仅重排或仅改版本/说明不生成待确认批次，409。四类需求“亲子财商、养老丰润、服务资格认证、其他类培训”仍不是四门默认课程，不自动填入。

服务器在同一Api.MUTATION_LOCK内取当前可信snapshot的teachers/certifications/bindings（首版三者为空），原样拼装whole catalog后调用原预览逻辑。不接受客户端teacher/cert/binding相关字段，拒绝全量catalog字段或人员参数；不从当前181教师自动配码/填认证，不按最新teacher档案刷新旧目录metadata。

响应复用原预览字段，并附scope_id/organization_code供页面比对：

```text
{
 schema_version:"m04_preview_v1",catalog_version:string,ready:boolean,
 issues:[原行级问题],counts:{courses,teachers,certifications},
 data:ready时的原完整编码目录对象或null,
 current_version:number,batch_id:string|null,confirmation_required:boolean,
 changes:ready时原差异对象或null,
 scope_id:number,organization_code:string
}
```

changes各项均为`{added:[],updated:[],removed:[]}`，包含courses/teachers/certifications/bindings。成功预览的后三项三组数组全部为空，courses.removed同样为空。服务端在原事务保存batch之前断言这些不变量；任何非课程变化均409且无批次写入。

ready=false只返回issues、batch_id=null/confirmation_required=false/changes=null，不保存批次。删除已有编码即使会导致认证引用错误也返回409，并明确提示停用；其他无效课程字段由原核心返回ready=false。ready=true仅写原m04_catalog_batches，不发布当前版本。

原validateBindings若因当前教师等级/城市与已存目录metadata不一致而409，纯课程入口明确提示先核对师资依据；不更改metadata或绕过资料保护。已保存目录/永久绑定损坏在新维护入口整体409。

## 显式确认与失效

仍用原`POST /api/course-catalog/scopes/:id/confirm`：

```text
{batch_id:string,expected_version:number,confirm:true}
```

不增加确认接口/字段。只有原预览账号及人员能确认；重新检查manage权限、M01版本、目录CAS、完整绑定与本次teacher_facts，原事务发布不可变revision/event及确认标记。其他人403、过期/资料变化409，重复同一批返回原ALREADY_CONFIRMED，不增版本/事件。课程停用后资格查询沿原规则不再列出该课合格候选，历史认证记录保留。

HTTP常见状态：会话失效401，无人员/机构授权403，scope不存在404，结构/类型/不允许字段400，方法405，非JSON415，旧版本/删除或换码/无有效改动/师资不一致/损坏快照409。原其他端点错误与响应协议保持兼容。

## 持久化与并行接点

无新DDL、不改旧表。原preview方法保留旧入口语义；仅课程入口增加服务器保留快照和课程差异断言。旧全量preview/confirm/history/versions/qualification不增本次“课程不可删除/无改动”限制，不改CourseCatalog纯核心或旧测试。

根UI在原课程与认证弹窗展示业务表单、版本名称及修改说明；有真实改动才保存预览，展示课程差异后用户明确确认。关闭、切空间、换版或输入变化后丢弃旧batch，不用前端can_manage代替服务器权限。manage-only账号通过新读取接口得到维护所需课程；read-only维持原查看。不得把本轮课程维护描述为完整师资编码/认证维护。

## 验证状态

新增M04CourseMaintenanceTest已用当前共享全部Java编译运行，**236项检查通过**。原scripts/M04Integration_check.sh未作修改，回归**416项通过**。测试均为真实Auth/M01、新建空H2及合成机构/师资/认证，不访问真实工作库、不启动服务。

新测试核验：manage/read分别授权及跨机构/旧admin边界；首版课程创建、版本CAS、原预览人确认及重复确认；旧全量preview/qualification通路；课程改名/启停后teachers、certifications、bindings不变；已有code不得删除/换码；重复code/坏输入/无实际改动/重排拒绝；原师资等级城市变化409；坏JSON/schema、认证引用损坏、永久绑定错配、材料版本错配整体409；停用后COURSE_INACTIVE、重新启用仍按原认证及有效期；version>0空courses的版本保留2名老师/2条绑定，随后添课不清空。

新测试入口为com.training.M04CourseMaintenanceTest，参数为一个新建空测试数据目录。编译共享src/com/training/*.java及scripts/M04CourseMaintenanceTest.java到独立临时classes，使用既有Java17/ECJ/H2等lib依赖；不得传实际app/data。旧回归入口仍为bash scripts/M04Integration_check.sh。

核心编译与新测试均无须修改冻结实现，API/DTO可freeze。总控继续独立HTTP/UI与原系统视觉验收；上述核心专项不替代该端到端验收。当前实际工作库M01仍null、scope仍未配置，不为验收写真实授权或课程；181教师接收回执及184账户未动。
