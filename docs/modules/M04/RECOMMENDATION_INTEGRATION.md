# M04 推荐资格桥接集成

> 总控更新（2026-09-22 第八轮）：本文桥接已接原推荐宿主和页面并验证。完整候选池、真实会话隔离、异步原子保存及返回重验均已落实，旧前端结果直接缓存已去除。HTTP95/任务82/桥接195/UI80及真实桌面手机验收通过。下文未接入状态和宿主行号是交付时快照，最新事实以coordination/INTEGRATION_20260922.md第八轮为准。正式认证材料仍未配置。

## 本次交付与适用范围

本次新增 `src/com/training/RecommendationQualification.java`、`scripts/M04RecommendationQualificationTest.java` 和 `scripts/M04RecommendationQualification_check.sh`，配合本文。**桥接已实现，原推荐宿主尚未接入。**没有修改 `TeacherIntelligence`、`RecommendationJobs`、`Api`、`Db`、`Auth`、`OrganizationAccessStore`、`web/app.js` 或已有测试，没有新增路线、页面、目录空间或真实认证。

桥接只控制“哪些讲师可进入原推荐评分”。原评分、需求证据、交通预算、档期、前三及第三名并列规则由宿主原逻辑执行；具体课程合格不等于已通过派单所有条件。不得从师资等级、领域或课程开发经历推导认证。

## 最新分类及材料口径（2026-09-22）

用户确定的“亲子财商、养老丰润、服务资格认证、其他类培训”是需求分类，总控已在原需求表单接入并保留历史值；不是四门标准课程，不把类别名称放入标准课程选择来生成认证。分类不会自动构造standard_course、course_code、目录空间或teacher_code绑定。

具体课程和逐课资格材料可后补，暂不催交整套目录，不阻塞分类及原推荐推进。只有缺省standard_course时沿用NOT_SELECTED契约并说明未核验；用户已明确选定标准课程时，未知/缺失资格仍不合格，不因材料后补回退到全库。

现有名单等级/机构/岗位继续复用。管理办法已获用户“按这个执行”授权，清楚条款按来源执行；考核贡献×5/×3仍不是费用倍率或逐课认证。原评分/交通/并列规则不变。本次仅同步M04文档，保留上方总控第八轮记录，30分钟定时继续暂停。

## 请求契约

在原推荐请求内新增可选字段 `standard_course`。**只有字段完全缺省时**沿用原推荐、不声明课程认证；显式 `null`、`false`、空对象及字段不全都是 400，不能静默退出课程条件。

```json
{
  "standard_course": {
    "scope_id": 1,
    "expected_version": 2,
    "course_code": "001",
    "as_of": "2026-09-22",
    "accepted_levels": ["讲师"],
    "allowed_cities": []
  }
}
```

以上为结构示例，不是实际空间、目录或授权。`standard_course` 必须且只能有这六个字段；scope 为正安全整数，version 为非负安全整数。课程编码、严格日期、条件列表由现有可信 `CourseCatalogIntegration.qualification` 校验，空列表表示用户未附加该项限制，不代表自动取得认证。scope/version/course/date 必须由本次明确选择提供，不读取默认演示目录。

客户端不能提供候选数组/ID、资格结果、认证状态、目录/绑定、上下文摘要或内部版本字段。桥接拒绝已知服务端字段（包括 `recommendations`、`results`、`review_candidates`）；其余旧请求字段只交给原宿主处理，不作为可信候选或资格来源。归属、资格与摘要始终由服务端读取。

## 服务端接口和状态

类为包级 final，Capture/Pool/Result 构造器私有，没有 JSON 还原入口。它们应保存在原进程的任务/缓存状态内，不写入请求或响应。

| 方法 | 调用时机与保证 |
| --- | --- |
| `capture(session, body)` → `Capture` | 在同步开始或异步提交时调用。绑定原 `Auth.Session` 对象、账号、M01版本、明确课程条件、目录/认证快照及当前绑定师资事实上下文。条件及摘要保存为私有快照，不持有可变请求列表。 |
| `filterBeforeScoring(session, capture, fullDatabasePool)` → `Pool` | 紧接原完整在库 SQL，在任何打分、预算/交通裁切、前三选择之前。再次核验资格；核对池 ID 与当前全部在库 ID 完全相等且无重复，并核对每行 `teacher_level`、`base_city`。输入必须来自原服务器查询，至少含 `id`、`teacher_level`、`base_city`；不能传客户端名单或已过滤名单。 |
| `Pool.teachers()` / `totalInLibrary()` / `exclusions()` | 分别提供递归只读评分池、过滤前完整人数及资格缺口。保留原 SQL 行序和评分字段，不排序、不截断。缺口包括明确未认证及目录未绑定的在库讲师。summary/exclusions 返回独立副本。 |
| `revalidate(session, capture)` | queued/running/failed/completed 状态读取及取消之前均调用。核验同一原会话、M01版本，并对已选课程重新调用可信 qualification；不能只比较客户端摘要。 |
| `complete(session, pool, scoredResult)` → `Result` | 原评分与最终选择之后、同步发布或异步保存 completed **之前**。重新核验资格，拒绝候选/待核候选中不属于本次合格池的 ID，保留合法排序及并列。固定 `total_in_library` 为过滤前数量，附上服务端 `course_qualification`。 |
| `deliver(session, result)` → `Map` | 同步返回、异步结果获取、每次服务端缓存命中返回之前，再次重调可信资格，返回独立结果副本。旧结果不能转移给同 UID 的新登录会话。 |

Capture 的 `selected()`、`status()`、`eligibleTeacherIds()`、`summary()` 用于内部状态和明确反馈。资格状态：

| status | 处理 |
| --- | --- |
| `NOT_SELECTED` | 未选择标准课。保留原完整候选池，summary 明示“未核验课程认证”，资格人数为 null，不能说全员已认证。 |
| `READY` | 有明确合格 ID；仅这些 ID 进入原评分。最终可能仍因证据、档期、预算或交通无法推荐。 |
| `NO_ELIGIBLE` | 请求有效但无人满足具体课程及明确条件。空评分池、明确缺口，不能退回全库凑人数。 |
| `NOT_CONFIGURED` | 所选范围尚无已确认目录。空评分池、明确未配置。 |
| `INVALID_SELECTION` | 课程不存在或已停用等 helper 非 READY 结果。空评分池、明确所选课程不能核验。 |

后三种状态可完成并交付空结果，`recommendations/results/review_candidates` 为空，相关人数归零并附明确 notice；任何试图把候选塞回空池的操作均拒绝。输入结构错误为 400，原会话失效为 401，跨账号/跨会话及无目录权限为 403，目录/M01/师资事实变化或池已截断为 409，缺失范围等沿用可信 helper 状态码。事务尚未提交则抛 IllegalStateException，不能捕获或发布未提交状态。

## 宿主接入顺序与原子边界

1. 继续执行原 `Auth.canWrite`、`requireWorkflowDemandVisibility` 与 `_access_version` 检查。桥接只增加具体课程资格，不授予原推荐或需求访问权限。异步提交时就捕获原会话和明确条件；不要等 worker 开始时才选一个“最新”上下文。
2. 同一 `Api.MUTATION_LOCK` 内读取原全量 SQL，并调用 `filterBeforeScoring`。将 `pool.teachers()` 送入原流程，把原人数指标改取 `pool.totalInLibrary()`。输入为只读行，宿主需要增添临时字段时复制行，不修改资格快照。异步 worker 使用提交时的 Capture，不能重新 capture 来悄然接受排队期间的变化。
3. 原评分结束，在同一个**外层** `Api.MUTATION_LOCK` 内重新核验原推荐权限、调用 `complete`，并保存 Result 及 completed/cache 状态。`complete` 内部锁在方法返回后会释放，不能在锁外随后保存。保持业务锁→任务锁的统一顺序；不得在任务锁内反向等待业务锁。
4. 同步返回、任务读取和缓存命中同样在外层业务边界内先执行原推荐访问检查、`deliver`，再将本次响应交给现有发送路径。任务状态读取和取消先调用 `revalidate`；跨会话和失效上下文必须在读取细节/取消状态变更之前拒绝。失败后移除/标记失效旧结果，不允许“最后一次成功名单”兜底。
5. `RecommendationJobs.Work` 和 `Job.result` 现为 Map。总控需为任务保留类型化 Capture 与 Result（保留旧接口的其他用途），完成时仍持有 Result，而不是先 deliver 成 Map 再沿用旧 JSON 存储；JSON 复制不能还原原会话授权。终态/过期清理同时释放桥接对象；保留原任务数量、大小、时限和账号配额。
6. 新类抛 `Api.ApiException`，原推荐目前只转换 TalentException 等。同步、提交、获取/取消及 worker 失败路径必须保留桥接的 400/401/403/409 等状态和原因；不能让正常失效被通用异常分支变成 500。可转换为宿主已有 TalentException / RecommendationJobs.Failure，按原错误封装返回。

`Capture.cacheKey()` / `Result.cacheKey()` 仅是**资格缓存分区**：原登录会话的进程内随机分区加可信 context_id（未选择时为 M01 版本），不含需求文字、需求 ID、预算和交通条件。完整缓存键必须再结合服务端规范化的原推荐请求键；不能直接把它当完整推荐键。它不是 token 或授权证明，知道该字符串不能构造 Result。任何缓存命中仍必须 `deliver`；client 提供的摘要只可关联，不可鉴权。

现有前端内存缓存直接 render 不经服务端，必须由总控取消该直接复用，或在现有推荐链路中完成服务端复核后再显示。本次不新增旁路校验接口。仅扩展前端缓存键或比较两个客户端摘要不能解决撤权与教师事实变化。

M01 发布后的会话政策按现有实现执行：人员/绑定变化撤销受影响账号；共享组织/权限等变化撤销已绑定账号；仅版本/顺序变化不撤会话，但本桥接仍因 M01 版本变化拒绝旧上下文。旧 Session 失效返回 401；同账号重登后用新 Session 读取旧 Result 返回 403。重新登录只能发起全新分析，不能替旧结果换绑会话。

## 验证与未覆盖边界

`bash scripts/M04RecommendationQualification_check.sh`：195项检查通过（2026-09-22）。

专属脚本使用实际共享全部 Java 源码（只替入本次新桥接），真实 Auth.login/M01 发布/M04 预览及显式确认、新建空 H2 和合成账号、机构、师资及认证。原池有 8 名在库讲师，前三名不合格，后续 4 名合格且有 1 名未绑定；检查完整筛选、保序、禁止截短池、无认证/无目录无回退、注入、不可变快照、账号/同账号不同 Session 隔离、登出、目录与 M01 版本、撤权/重新绑定以及当前等级/城市/在库状态变化。

异步检查使用真实 RecommendationJobs 执行器和阻塞栅栏，在评分期间修改师资城市，再执行完成复核，验证任务失败且未保留旧结果。它验证桥接接到 worker 的完成检查，不表示原 Job 已改为持有 Result，亦不覆盖宿主尚未实现的“重验到 completed 保存”原子接线。缓存检查复用真实 Result 调用 deliver，未将原前端直接 render 的缓存路径视为已覆盖。

脚本不启动 HTTP 服务、不调用 Db.init、不访问外网或真实业务数据库；只清理自身新建临时测试目录。没有重跑或覆盖既有 M04IntegrationTest。原系统页面、推荐性能、宿主异常映射/任务储存/前端缓存以及跨模块派单须在总控接入后另作回归。

## 已核对宿主接点

核查日期：2026-09-22。以下行号对应当次只读核查的共享宿主 `<repository>`；后续宿主改动后须重新定位。这里只记录现状与待接入点，不表示这些宿主文件已接入新桥接。本次未修改宿主、启动服务、创建目录范围或导入真实材料。

### 实际路由与同步链路

| 接点 | 当前行为 | 接入要求 |
| --- | --- | --- |
| `src/com/training/Main.java:51–61`，启动时注册的根处理器 | 第 56–57 行将 `/api/teacher-resumes*`、`/api/teacher-recommendations*` 直接交给 `TeacherIntelligence.handle`，其他 `/api/*` 才交给 `Api.handle`。 | 推荐桥接必须覆盖现有 `TeacherIntelligence` 路径。只改 `Api` 不能覆盖推荐请求；无需新增路由。 |
| `src/com/training/Api.java:81–150`，`route` | 第 98 行委托 `CourseCatalogIntegration.handle`；没有将推荐路由委托给 `TeacherIntelligence`。 | 不应在文档或验收中把 `Api.route` 误认为推荐宿主。 |
| `src/com/training/TeacherIntelligence.java:137–199`，`handle` | 第 140 行取当前登录会话，检查业务写权限；第 169–171 行处理同步推荐。 | 沿用原入口、权限与响应方式，在服务端识别本次课程资格条件并创建内部桥接上下文；不得信任客户端候选 ID、资格结果或摘要。 |
| `TeacherIntelligence.java:589–592`，`recommend` | 读取请求后，执行 `buildRecommendation`，再调用 `visibleRecommendationResult` 后返回。 | 同步返回必须重调可信 `qualification`；复核失败或上下文变化，不得返回刚评分的旧名单。 |
| `TeacherIntelligence.java:570–580`，`visibleRecommendationResult` | 重新核验需求可见性及 `_access_version`，然后剥离内部版本字段。 | 保留现有检查，并补上课程资格与原会话票据复核。不能仅比较客户端传入的 `context_id`，不能只把内部摘要字段剥掉就视为安全。 |

### 完整候选池、评分与最终裁切

`TeacherIntelligence.buildRecommendation` 从第 595 行开始。第 613–629 行在 `Api.MUTATION_LOCK` 内重新核验需求、记录权限版本、读取需求及**全部在库讲师**。第 622–629 行的查询使用 `WHERE t.status='在库' ORDER BY t.id`，没有 `LIMIT`；关联当前简历，并使用按操作者范围限定的评价与已完成授课统计。这里是完整池接点。

资格过滤必须置于这份完整在库池读取之后、首次评分之前，并以可信 `qualification` 返回的 `teacher_id` 集合求交。必须记录过滤前原池数量，避免第 769 行 `total_in_library = teachers.size()` 在接入后悄然变成“通过课程资格的人数”。资格未通过或待核原因应保留为资格缺口，不得用于凑足推荐人数。

| 原有步骤 | 精确位置 | 必须保留的行为 |
| --- | --- | --- |
| 档期冲突排除 | `TeacherIntelligence.java:666–675`，`buildRecommendation` 的讲师循环 | 原有冲突规则及可见排期细节限制保持不变。 |
| 规则评分开始 | 第 677 行 `profileForMatching`，第 678 行 `scoreTeacher` | 课程资格应在此之前完成；不得让无资格候选进入评分再以最终名单代替完整池。 |
| 硬预算、交通与城市参考 | 第 679–721 行 | 不改变预算规则、常驻地、相邻排期、提前到达、交通待核或路线参考规则。 |
| 本地语义 | 第 725 行 `LocalSemantic.augment` | 沿用原评分和降级行为，不用课程资格分数替换其结果。 |
| 原文证据准入 | 第 729–739 行，`coverage.assess` / `input.annotate` | 保留原文证据准入、待补证与排除分类。 |
| 附近范围、前三及第三名并列 | 第 744–750 行，`NearbySelection.selectWithContextCatalogs` / `NearbySelection.select` | 此处才作最终选择。`topK` 固定为 3（第 601–605 行）；严禁先执行此选择再对前三做资格过滤，否则会漏掉完整池中可补位的合格讲师。 |
| 原比较器 | 第 793–827 行，`compareCandidates` | 不修改内容排序、语义比较、专业分段和稳定排序。 |

原交通选择实现位于 `src/com/training/NearbySelection.java` 的 `select`（第 97、102 行）、`selectWithContextCatalogs`（第 107 行）及 `selectInternal`（第 112 行）；桥接只约束输入资格，不改其交通扩围或并列保留规则。

### 异步任务、完成保存与读取

| 接点 | 当前行为 | 必须修改的边界 |
| --- | --- | --- |
| `TeacherIntelligence.java:172–179`，`handle` 的 jobs POST | 第 176 行 `submit(session.uid, work)`；工作闭包在第 177 行捕获原 `session` 并调用 `buildRecommendation`。 | 提交时应创建可信资格上下文，并使任务持有原 `Auth.Session` 引用及内部资格票据。仅由闭包捕获会话，不等于任务读取/取消已绑定该会话。 |
| `TeacherIntelligence.java:181–183`，jobs GET/HEAD | `get(session.uid, id)` 后交给 `visibleRecommendationJob`。 | queued/running/failed/completed 等状态都须先核验原会话和资格上下文；不能只有带结果的任务才检查。 |
| `TeacherIntelligence.java:185–187`，jobs cancel | `cancel(session.uid, job_id)` 已先执行取消，再调用 `visibleRecommendationJob`。 | 应在取消状态变更前鉴别任务归属、原会话与可信资格，禁止同账号新会话或其他账号使用旧任务票据。 |
| `TeacherIntelligence.java:583–586`，`visibleRecommendationJob` | 只有 `job.result` 是 Map 时才调用 `visibleRecommendationResult`；未完成状态没有该复核。 | 任务级复核不能依赖结果存在；完成结果还应经过返回边界复核。 |
| `src/com/training/RecommendationJobs.java:23–30`，`Job` / `jobs` | 任务仅记录 `long owner`；所有任务保存在进程内 `LinkedHashMap<String,Job>`。 | 必须增加原会话/资格票据的内部承载，且不把 `Auth.Session` 序列化给客户端。 |
| `RecommendationJobs.java:41–50`，`submit` | 每账号最多两个活动任务，容量限制后排队，返回快照。 | 保留原配额；在提交成功前核验资格与原会话，不以客户端提供的归属 ID 建任务。 |
| `RecommendationJobs.java:52–65`，`execute` | 第 55 行工作完成；第 57–59 行 JSON 复制；第 60 行直接赋值 `job.result` 并标记 completed。 | **完成保存也是单独安全边界**：评分结束后、保存 completed 前须重调可信 `qualification`，复核原会话和上下文；不得先保存旧结果再寄希望于读取时拦截。 |
| `RecommendationJobs.java:74–83`，`get` / `cancel` / `owned` | `owned` 只比较任务 `owner` 与调用者数值 UID；同账号的新登录会话可以找到旧任务。 | 同 UID 不足以证明本次会话拥有旧票据。获取与取消必须拒绝跨账号、跨会话重用，并复核资格变化。 |
| `RecommendationJobs.java:94–101`，`snapshot` | 复制任务元信息及结果；没有课程资格检查。 | 不将快照复制当作授权。输出前必须完成上述可信复核。 |

`RecommendationJobs.INSTANCE`（第 18 行）当前为 60 秒截止、终态保留 900 秒、容量 32、两个工作线程；这是任务结果留存，不是按资格上下文分区的推荐缓存。第 90–92 行清理到期任务。宿主接入须保持 `Api.MUTATION_LOCK` 与任务锁顺序一致；最终复核和结果保存之间不能留出权限或目录变更可穿透的空隙，也不能新增“任务锁内等业务锁 / 业务锁内等任务锁”的反向锁序。

### 现存缓存及用户隔离

前端缓存位于 `web/app.js` 的 `state.cache.teacherRecommendations`，仅在当前页面进程内：

- 第 3624–3626 行 `teacherRecommendationKey()` 只包含需求文字、需求 ID、推荐数量、预算与交通表单；没有账号、登录会话、M01 权限版本、M04 范围/目录版本或资格上下文。
- 第 4567 行 `renderTeacherRecommendation` 按此 key 取缓存；第 4745 行直接 `renderRecommendationResults`，不会访问服务端。这条缓存命中路径目前不能发现服务端撤会话、目录更新、认证变化或讲师档案变化。
- 第 4806 行仍调用同步推荐；第 4807 行只检查本地请求序号、表单 key、路由与连接状态；第 4808–4809 行保存缓存。现有前端没有接入 jobs 轮询链路。
- 第 3618–3622 行 `invalidateTeacherRecommendations` 中止本地请求并删除缓存；第 4644 行 `persistForm`、第 4648 行 `markRecommendationDirty` 是原草稿/变更接点。第 226–239 行 `invalidateSession` 清空全部缓存，但前提是前端已经发现会话失效。
- `TeacherIntelligence.sendJson` 第 1740–1741 行发送 `no-store` / `no-cache`，能限制 HTTP 缓存，不能使 JavaScript 的 `state.cache` 自动失效。

后续接入必须使缓存命中先经过现有服务端推荐链路的可信资格复核，或直接使未经复核的缓存失效并重新分析；不得新增一个仅返回“摘要相等”的旁路接口，也不得凭本地 key 直接复显旧资格结果。账号、原会话身份、课程条件与资格上下文必须参与内部隔离；客户端缓存字段或摘要仅用于关联，不能授权。任何新建的服务端推荐缓存同样需要每次命中重调 `qualification`，不能只凭缓存 key 放行。本任务不新增 UI 或路由。

### 已有 M01 保护与可信资格入口

现有推荐保护的实际字段名是 **`_access_version`**，不是字面 `M01_access_version`：`recommendationAccessVersion()`（`TeacherIntelligence.java:565–567`）读取 `OrganizationAccessStore.configuration().version()`；第 615 行在加载时记录，第 782 行附入内部结果；第 574–575 行在可见结果输出时比对，不一致返回 409；第 577–578 行剥离内部字段。它已经保护 M01 配置版本变化，必须保留，但不覆盖目录、认证、课程条件或当前师资事实变化。

`requireWorkflowDemandVisibility`（`TeacherIntelligence.java:550–562`）通过 `Auth.current(candidate)` 重新认证、检查业务写权限和需求可见性；`buildRecommendation` 第 597、614、645 行已有调用。该保护也须保留。

可信课程入口是 `CourseCatalogIntegration.qualification(Auth.Session supplied, long scopeId, long expectedVersion, Map<String,Object> input)`（`src/com/training/CourseCatalogIntegration.java:192–218`）：

1. 第 193–198 行在业务锁内调用 `Auth.current(supplied)`，查当前人员、目录范围访问权限及预期目录版本；`scope` / `permission` / `version` 的实现为第 327–337 行。
2. 第 199–205 行只接受 `course_code`、`as_of`、`accepted_levels`、`allowed_cities`，规范化明确条件；不接受调用方候选池。空等级/城市条件不应被桥接推断为额外门槛。
3. `qualifySnapshot`（第 223–263 行）读取范围版本的已确认目录、绑定及当前讲师档案；第 238–243 行覆盖当前等级和城市，第 252–256 行剔除档案不存在或当前不在库的绑定讲师。第 224–228 行无目录版本时返回 `NOT_CONFIGURED`，不能回退到“默认全员合格”。
4. 第 208–216 行生成包括范围、目录版本、M01 `identity_version`、账号/人员、课程/日期/明确条件、目录快照及当前师资事实摘要的 `qualification_context`。`context_id` 是变化标识，不是权限票据，也不含可替代原会话引用的会话授权证明。

因此，同步返回、异步提交/执行完成保存/读取/取消、缓存命中都要重新调用该可信入口；用旧上下文与新可信上下文比较，只能发生在当次认证和权限核验成功之后。摘要相同不能替代调用，摘要变化不能静默继承旧候选或降级到原全库推荐。

最新 M01 宿主已处理发布后撤会话：`OrganizationAccessStore` 发布流程第 139–144 行先事务提交，再在仍持有 `Api.MUTATION_LOCK` 时调用 `Auth.revokeUserSessions`；`changedSessionAccounts`（第 151–172 行）按政策/绑定/人员变化选择受影响账号。第 149–150 行明确仅版本号或顺序变化保留会话，因此不能写成“每次发布一律撤销所有会话”。

`Auth.current`（`src/com/training/Auth.java:180–186`）按 **同一 `Session` 对象引用**查找活跃会话并重新执行 `get`；`Auth.get` 第 152–154 行还核验 `issuedUid`。`revokeUserSessions` 位于第 202–204 行。桥接票据必须保留发起请求的原 `Auth.Session` 引用；原会话被撤销后，即使同账号重新登录、重新绑定同一人员、或得到相同业务条件，也不得将旧票据重新绑定到新会话。只存 UID、person_code 或可序列化的 context_id 无法满足这一约束。
