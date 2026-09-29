# M04 教师主名单接收契约

2026-09-23 第九轮。核心由M04独占实现，总控负责Host/API/原界面。只接收已固定服务器pins、已完整接收账号的主名单教师；不读写实际app/data，不改原来源文件或真实名单，不以姓名合并，不创建课程资格、正式teacher_code、账号/角色/权限或激活登录。

## 固定入口与public API

- `static TeacherRosterImport.init()`：在users/teachers及OrganizationAccountImport表已初始化后、事务外，仅建下述两个空表。
- `new TeacherRosterImport(OrganizationAccountImportSource.Config)`：配置仅由可信Host构造，客户端不得传路径或pins。
- `Map<String,Object> preview(Auth.Session)`：Host `GET /api/teacher-roster/preview`。
- `Map<String,Object> commit(Auth.Session,Map<String,Object>)`：Host `POST /api/teacher-roster/import`。
- `static boolean hasImportedTeacher(long)`：旧教师删除入口同业务锁/事务内用作409提示；外键亦保护关联。

Host负责未启用503、HTTP方法/JSON限额/统一响应；核心不增路由。核心在Api.MUTATION_LOCK内重新调用真实Auth.current及Auth.isAdmin：失效401、非管理员403、请求结构/格式400、来源/账号接收/回执/预览变化或冲突409。内部来源错误不输出路径、HR编号、原始JSON或底层异常。拒绝嵌套事务。数据库执行失败回滚全批后409，不部分修复。

## 精确DTO

预览data恰含：

```text
{
  batchKey: string,
  sourceFingerprint: string, // 原Source.load的fingerprint不变
  imported: boolean,
  reviewToken: string|null,
  rows: [{reference,name,organization,job,teacherLevel,accountId,teacherId,status}],
  summary: {teachers,excludedLeads,historicalExcluded}
}
```

未接收时teacherId=null、status="待接收"、reviewToken为64位随机十六进制。已接收时teacherId是生成的数据库ID，status取当前teacher.status，reviewToken=null。name/organization/job/teacherLevel始终展示冻结来源值；合法编辑后的当前档案不反写来源。rows顺序沿用可信主名单。summary均为非负整数：仅精确“兼职教师”主引用计teachers，额外仅牵头人计excludedLeads，历史排除引用计historicalExcluded。正式181/3/9由源pin约束推导，测试使用较小合成范围，不在核心硬编码181人或读取真实名册。

POST必须且只能有三个键：

```text
{batchKey:string,sourceFingerprint:64位小写SHA256,reviewToken:64位小写hex|null}
```

客户端不能传rows、关联ID、等级、姓名、状态、路径或teacher_code。首写reviewToken必须存在、同一原Session、未超过2小时，且预览的完整来源、账号回执/全部users/全部teachers状态未改变。令牌只在进程内保存（至多256份），不输出/持久化会话引用。首写前进程重启须重新preview。

导入/重放data恰含：

```text
{
  batchKey:string, sourceFingerprint:string, imported:true, replayed:boolean,
  rows:[{reference,accountId,teacherId}],
  summary:{teachers,excludedLeads,historicalExcluded}
}
```

首次成功replayed=false；已导入后同批重放replayed=true，允许旧格式正确token或null，不要求原预览仍在内存。重放仍要求当前真实管理员、固定pins、完整账号接收和所有本模块回执/关联通过核验，不再次写库。未知批次/来源不一致409。过期/跨Session token不能用于首次写入。

## 唯一写入与DDL

教师一次全批插入 `name=source.name,org=source.organization,title=source_job,teacher_level=source_teacher_level,status='待完善'`。gender/field/phone/email/fee_rate/intro/in_date/out_date/base_province/base_city明确NULL，created_at沿用数据库默认；不生成正式teacher_code。source_job允许null或空串，保留原值；name64/org200/job64/level32限长，等级严格保留总控确认的名册三枚举“讲师/高级讲师/特级讲师”，不转换或按职务/账号推导。

```sql
CREATE TABLE IF NOT EXISTS teacher_roster_import_batches (
  batch_key VARCHAR(128) PRIMARY KEY,
  source_fingerprint CHAR(64) NOT NULL,
  profiles_digest CHAR(64) NOT NULL,
  accounts_digest CHAR(64) NOT NULL,
  receipt CLOB NOT NULL,
  created_by BIGINT NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS teacher_roster_import_people (
  batch_key VARCHAR(128) NOT NULL REFERENCES teacher_roster_import_batches(batch_key),
  source_reference VARCHAR(128) NOT NULL,
  source_fingerprint CHAR(64) NOT NULL,
  account_id BIGINT NOT NULL UNIQUE REFERENCES users(id),
  person_code VARCHAR(64) NOT NULL UNIQUE REFERENCES organization_account_import_people(person_code),
  teacher_id BIGINT NOT NULL UNIQUE REFERENCES teachers(id),
  initial_facts CLOB NOT NULL,
  initial_digest CHAR(64) NOT NULL,
  created_by BIGINT NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(batch_key,source_reference)
);
```

元表不提供编辑/删除入口。initial_facts冻结reference/account/person/teacher关联和初始档案全部业务字段；摘要仅检查完整性，不能单独授权。批次receipt采用上面精确receipt结构、replayed=false。存储的source fingerprint、profiles/account映射摘要、人数、每条reference/账号/person/teacher唯一对应、初始事实及其摘要必须与当前可信来源和完整账号回执一致。

首批要求teachers、两个新元表均为空；已有教师且缺本批完整回执一律409，不合并/覆盖。本模块仅支持这一首批，存在其他批次元记录同样409。事务内先再次读取pins和完整账号回执、会话及快照，再插所有teachers、batch、people；最终复核一致后提交，任何异常整批回滚。额外账号候选必须也已完整接收，不能只拿已接收教师子集继续。

## 来源投影和核验

OrganizationAccountImportSource只新增TeacherProfile record及teacherProfiles(Config)，不改旧load/Snapshot/Candidate/intakePayload/fingerprint。新投影先走原完整pins/mainRows/身份核验，再从同一固定候选pin取source_job/source_teacher_level；按精确reference和“兼职教师”身份对照，不解析姓名匹配。source姓名/机构/岗位/等级原样保留，不输出其他原始字段。

每次preview/commit调用现有 `new OrganizationAccountImport(config).preview(actor)` 的完整回执校验，要求imported=true且所有候选已接收；只按reference取得accountId/personCode，逐项核对主名单引用，不按姓名借用账号。账号启停可后续合法变化，但首次preview与commit之间任何users/账号接收记录变化使token过期。

已导入后逐条验证元表与初始来源、账号关联、teacher存在及唯一对应；**不要求当前teacher的name/org/title/teacher_level/status等永远等于初始值**。合法手工完善、改名、改等级、入库/出库不会破坏历史回执；当前status用于预览展示。历史来源与关联不能静默重绑或删除；损坏时整批409，不能自动补人、重建回执或覆盖当前档案。

## 并行交付与验证状态

核心与实际共享全部Java源码（含总控新Host接点）编译通过。新增M04TeacherRosterImportTest合成集成测试541项通过；同一编译产物运行既有OrganizationAccountImportSourceTest，43项通过。两轮测试的有效结果分别核对了真实Auth、完整M01账号接收、来源身份/等级/字段边界、同名不同机构不合并、全部账号前置要求、原Session/票据期限与快照、首批非空拒绝、教师中途和回执写入失败整批回滚、精确DTO与私密字段不外露、全量receipt/关联/初始事实损坏拒绝、同实例/新实例/数据库重连的无写重放、合法档案编辑、唯一/FK及删除保护。

测试在接收前后比较所有PUBLIC表内容；写集合严格为teachers及本模块两张元表，账号启停/角色、M01配置、课程认证和其他表不改变。旧Source移除新增TeacherProfile/API/helper块后与原文件逐字节一致，未改旧load/Snapshot/Candidate/intakePayload/fingerprint。

合成测试复用旧SourceTest.Fixture并仅在新测试内加入等级/岗位来源，不修改旧测试。仅新建临时H2与合成pin文件，不访问真实app/data或固定来源JSON，不跑服务器，不创建生产数据。实际181教师/3牵头人/9历史由总控源统计确认，未放入合成测试。

运行入口：将共享src/com/training/*.java、scripts/OrganizationAccountImportSourceTest.java及scripts/M04TeacherRosterImportTest.java编译至独立临时classes（Java17/ECJ及既有lib依赖），然后以两个新建且为空、不互相包含的临时目录作为参数运行com.training.M04TeacherRosterImportTest；第二个目录用于合成pins。旧来源检查入口com.training.OrganizationAccountImportSourceTest无参数。不得把实际app/data传给测试。

核心已交付freeze，后续Host/API/页面/真实HTTP由总控并行验证；以上专项通过不替代总控端到端与正式数据接收验收。本模块未改公共宿主或旧测试，未部署、未Git暂存/提交。
