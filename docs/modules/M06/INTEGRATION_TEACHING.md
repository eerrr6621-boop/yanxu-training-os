# M06 可信授课统计：原宿主接入建议

2026-09-23更新：只读授课统计已由总控接原经营洞察；新增实际“下载授课统计”XLSX已经模块实现并通过483项专项及95项旧接口回归。当前新下载合同见 [TEACHING_EXPORT.md](TEACHING_EXPORT.md)。原 `/export` 正式金额/编码月报仍409；下文关于“导出始终阻止”的说明只适用于该原正式月报分支，不适用于新增 `/teaching-export`。

本文件是待总控实施的接线说明，不代表 API 已挂载、原页面已接线或已完成浏览器视觉验收。用户已授权使用默认月报格式，不再索取模板或手工对账样例。本轮已交付只读适配及专项测试；本说明列出待接的宿主位置，不改 `app.js`、原模块 UI 或宿主公共文件。

## 1. 依据与当前交付边界

核对日期：2026-09-22。主要依据：

- 新适配器：`<repository>/src/com/training/ManagementReportsIntegration.java`；`adapter_version = M06-REVIEWED-TEACHING-1`，`policy_version = M06-20260921-1`。
- 原页面：`<repository>/web/app.js`，当前 `pageReport(c)` 在约4419行；既有页面仍是“经营洞察 / 培训经营分析中心”。下文行号只作定位提示，以函数名和节点 ID 为准。
- 当前宿主：`app/src/com/training/IntegrationDeliveryHost.java`、`Api.java` 的已登录路由段。
- 最新 `coordination/MASTER.md`、`coordination/CONTRACTS.md` 的“沿用原系统界面与流程”“2026-09-22第三轮整合/后续边界”。

只接入真实、只读的授课统计投影，不增加导航，不用独立 demo 替换原页面。新适配器无 DDL、无种子数据、无数据修复写入，不重新定价，不从旧费用状态推测支付。

当前可用与不可用分开呈现：

| 能力 | 当前行为 |
| --- | --- |
| 授课日期查询 | 读取真实关联来源中已完成、已核对且依据有效的授课事实 |
| 四类课时 | 预计、计划、实际、计酬分别汇总，来自同一批入选记录；不相加 |
| 实际课时排名、机构汇总 | 可以只读显示，排名固定实际课时降序与竞争并列规则 |
| 支付日期查询 | `409`，真实支付登记未接通 |
| 正式课酬、币种、唯一课程数 | 当前为 `null`，显示“待接入”，不显示0 |
| 正式编码月报下载 | 通过参数/权限/快照检查后仍 `409`；课程、讲师正式编码及费用币种来源尚未接通 |

正式导出的401、403、400及版本变化409仍优先按真实原因返回；“当前始终409”指合法且授权、快照一致的下载也不存在成功文件分支。已有合成 XLSX 生成器不能用于绕过此分支。

## 2. 后端挂载位置与安全边界

建议总控把新源文件集成进 `app/src/com/training/` 后，在 `Api.route` 完成真实 `Auth.get(token(ex))` 登录检查、与现有适配器相邻的位置增加以下分派，放在 `WorkflowLegacyAccess.check` 和通用 CRUD 之前：

```java
if (IntegrationDeliveryHost.handle(ex, s)) return;
if (ManagementReportsIntegration.handle(ex, s)) return; // 待新增
WorkflowLegacyAccess.check(ex, path, s);
```

这里只是建议插入点，未修改 `Api.java`。不要改写 `IntegrationDeliveryHost` 的授课/结算门禁，也不需要在 `Db.init` 增加任何调用。

适配器仅处理精确路径：

- `GET /api/management-reports`
- `GET /api/management-reports/export`

未知路径返回 `false`；已知路径只接受 GET，其他方法405，包括 HEAD。`handle` 会重新读取真实会话，要求其与宿主传入的会话相同且账号仍有效；不要传入临时构造的 Session，也不要用旧角色判断替代 M01 授权。

M01 的逐机构授权要求：

- 查询：`reports.read` + `OrganizationAccess.Action.VIEW`。
- 下载：同时要求 `reports.read` + `VIEW` 与 `reports.export` + `EXPORT`。
- “全部机构”只表示当前账号可查看的机构集合。用户显式选择的机构只要有一项越权就403，不能静默裁剪后返回局部数据。
- 接口返回的 `permissions.export` 仅表示当前选中机构范围内的导出权限，不表示正式导出已具备资料条件；还必须检查 `availability.formal_export`。
- 不向管理员、负责人、BP等任何实际岗位自动授予上述资源。绑定和授权由已接入的 M01 决定。

快照在 `Api.MUTATION_LOCK` 内，通过独立 `Db.transaction` 一次读取来源与权限；当前 `Api.handle` 已持同一可重入锁，重复进入不是新锁。适配器会拒绝当前连接已处于外层事务的调用，因此宿主不要再在外层包一个 `Db.transaction`。完成构造后还会重新核对账号、各机构授权与 M01 配置版本。

## 3. 请求参数与下载版本

查询白名单只有以下4项。原 demo 的 `hourBasis`、`rankMetric`、`includedStatuses`、`tieRule` 等不是此适配器参数，不能直接透传。

| 参数 | 约定 |
| --- | --- |
| `start` | 必填，严格 `YYYY-MM-DD`，有效自然日期 |
| `end` | 必填，同上，不能早于开始日期；首尾日都包含 |
| `date_basis` | 建议前端显式传 `TEACHING`；省略也按授课日期。`PAYMENT` 返回409，其余值400 |
| `organizations` | 可省略，表示当前可查看范围；提供时是非空、不重复的机构编码，以英文逗号连接。不要传空字符串、展示名称或含空格列表 |

机构编码遵循 `[A-Za-z0-9][A-Za-z0-9._:-]{0,95}`，前端采用 `URLSearchParams` 编码查询，不手工拼接自由文本。

查询形态示例，机构编码仅用于说明参数格式：

```text
GET /api/management-reports?start=2026-09-01&end=2026-09-30&date_basis=TEACHING
GET /api/management-reports?start=2026-09-01&end=2026-09-30&date_basis=TEACHING&organizations=ORG-A%2CORG-B
```

下载增加必填 `snapshot_version`，必须为最近一次成功查询返回的64位小写十六进制版本。普通查询不接受此参数。下载时携带该快照的开始/结束日期、日期基准和实际 `organizations`，不能拿新筛选配旧版本。

下载必须重新读取会话、授权、来源、修订和全部汇总，重新构造快照后比对版本；浏览器缓存的结果、导出按钮状态和版本字符串都不是授权。`snapshot_version` 是本次输出投影的 SHA-256：包含账号、details、汇总、excluded、查询范围、实时权限与配置等输出上下文，不含最后附加的 `generated_at`。它不是持久化的原始历史快照，也不是可用于读取过去版本的历史ID；同一账号下投影输出和权限配置相同时版本一致，即使读取时间不同。未改变投影的底层历史变化不保证改变此版本，不能将它解释为所有原始记录的历史审计摘要。`generated_at` 只说明生成时间，不作为前端自造版本依据。权限被撤销返回403/401，范围或事实变化导致版本不一致返回409；合法一致的当前版本仍因正式导出资料不足返回409。当前下载永远不生成任何正式、空白或占位文件。

## 4. 返回结构与页面字段映射

`Api.ok` 使用原有 JSON 外壳；原前端 `api('/management-reports?...')` 会解包并返回 `data`。新响应没有名为 `overview` 的节点；页面概览由以下顶层计数、`totals` 与可用状态组成。不要把它混入旧 `/stats/overview` 的 `d`。

| 字段 | 页面用途 |
| --- | --- |
| `adapter_version`、`policy_version` | 规则说明/只读详情中记录所用适配和统计口径 |
| `date_basis`、`start`、`end`、`organizations` | 展示本次实际查询范围，以服务端回传范围为准 |
| `server_today` | 上海时区业务日期；用于页面解释未来授课尚未入选 |
| `population` | 当前为 `REVIEWED_COMPLETED_DISPATCHES`，页面名称用“已核对授课” |
| `coverage_notice` | 必须显示：仅覆盖已建立可信机构来源的排课，未迁移旧项目不在覆盖内 |
| `included_count` | “已核对授课记录数”，不是唯一课程数 |
| `excluded_count` | 未计入记录数量，可打开原因弹窗 |
| `undated_excluded_count` | 缺授课日期而无法归月的未计入数量，不能声称都属于所选月份 |
| `totals` | `estimated / planned / actual / payable` 四项独立课时结果 |
| `teacher_ranking` | 服务端完成排序的实际课时讲师排名 |
| `organization_summary` | 当前入选记录的逐机构汇总 |
| `details`、`excluded` | 本批入选明细与未计入原因，分别呈现 |
| `fee`、`currency`、`course_count` | 当前均为 `null`；分别显示“正式课酬待接入”“币种待接入”“课程编码待接入” |
| `permissions.read / export` | 当前机构范围的授权状态，不能替代服务端下载复核 |
| `available_organizations` | 当前可查看的机构编码列表，用于机构筛选候选项 |
| `access_version`、`snapshot_version`、`generated_at` | 只读核对与下载上下文，不要求用户输入 |
| `availability` | `teaching=true`，`payment/fees/formal_export=false`，并带 `reasons[{code,message}]` |

### 4.1 未知课时与已知小计

`totals` 每一类别以及排名/机构汇总的 `hours` 内每一类别，形态相同：

```json
{
  "value": null,
  "known_subtotal": "12.50",
  "missing_records": 2,
  "complete": false
}
```

这段仅为字段形态说明，并非真实统计样例。`value` 是完整合计；存在未知值时为 `null`。`known_subtotal` 只是有值记录的小计，不能替代完整合计或参与完整排名。建议显示“待补齐”，辅以“已知12.50课时，2条缺失”。完整时直接展示 `value` 的精确十进制字符串；四类课时永不相加。

原 `countTag` 使用 `Number(value)`，会把 `null` 转成0，并可能改变精确小数；原 `animateCounters` 还会动态转换数值。因此新课时卡片、表格不直接调用这两个函数，也不使用 `value || 0`、`Number(null)` 或以0补齐图表。记录数可以复用原整数展示。

建议独立的局部展示函数：

```javascript
function reviewedHoursHtml(total) {
  if (!total || total.complete !== true || total.value == null) {
    const note = total
      ? `已知 ${esc(total.known_subtotal)} 课时，${esc(total.missing_records)} 条待补齐`
      : '统计结果未提供';
    return `<span>待补齐</span><small>${note}</small>`;
  }
  return esc(total.value); // 保留精确字符串；不要转 Number 再累计
}
```

真正空结果下，`included_count=0`、`details=[]`，四类总量完整且为字符串零；可显示“该范围暂无已核对授课”。费用与课程数仍是 `null`，不能因无记录而变成正式零值。接口报错与空结果也必须区分。

### 4.2 入选来源与四类同批语义

适配器关联受理来源 `workflow_acceptances → workflow_demands.organization_code`，读取对应 `dispatches`、M05当前事实及其修订记录。主要入选条件：

- 授课日期有效且位于查询范围，排课状态为“已完成”，日期不晚于服务端上海业务日期。
- 项目、受理需求、讲师和机构关联完整，当前事实的记录、日期、机构、教师等与来源一致。
- `data_mode=CONFIGURED`，存在有效核对信息；当前修订事件为 `VERIFY`，核对人、修订内容与历史依据一致。
- 四类课时字段都存在，值为合法精确字符串或 `null`；实际课时必须非空。
- 实际分钟、45分钟单位、两位小数 HALF_UP、当前换算版本和依据一致。前端不再次换算或修改服务端结果。

因此 M06 的预计/计划课时也只来自上述已完成、已核对的同一批记录。它们与 `IntegrationDeliveryHost` 在项目页附加的 `delivery_hours` 不同：项目页预计/计划覆盖全部有效排课，而实际/计酬只计已完成且核对有效记录。两处总体不同，不能将项目卡片数字直接搬入 M06 对账。

来源上限为选中机构的合计10000条，超过返回409。当前 SOURCE 先读所选机构的历史来源，再按日期过滤；缩小日期不能保证解除此上限，当前应提示缩小机构范围。若单个机构历史来源已超过上限，则须由总控后续实现受控分页，不能声称缩日期就能解决。适配器不会截断后冒充完整结果。

### 4.3 入选明细、排名和机构行

`details[]` 字段：

- 关联：`dispatch_id`、`project_id`、`teacher_id`、`organization_code`。
- 授权界面显示：`project_title`、`teacher_name`、`subject`、`teaching_date`。
- 核对：`fact_revision`、`verified_at`。
- 课时：`hours.estimated / planned / actual / payable`，每项为精确字符串或 `null`。
- 未接入：`fee`、`currency`、`course_id`、`course_code`、`teacher_code` 当前均为 `null`。

原页面可显示服务端已授权返回的项目/讲师名称，但必须 `esc`，不能按姓名关联、推导正式编码或将这些名称纳入编码导出。`teacher_id`、`dispatch_id` 是内部关联键；不要把 `TEACHER-<id>`、`DISPATCH-<id>` 等技术关联值当作正式教师/课程编码。

`teacher_ranking[]` 包含 `teacher_id`、`teacher_code:null`、`teacher_name`、`dispatch_count`、`hours`、`rank`。当前按 `hours.actual.value` 降序，竞争排名1、2、2、4；同值按内部 `teacher_id` 升序保持稳定。前端使用服务端名次，不按名称重排。正式编码排序、其他课时排名与课酬排名没有在此适配器开放，当前不要画成可用切换项。

`organization_summary[]` 包含 `organization_code`、`dispatch_count`、`hours`。当前是“机构授课汇总”，不要把 `dispatch_count` 命名为课程数。正式唯一课程统计待课程映射接通。

复用 `renderTable` 时，它要求行有 `id`：对展示数组分别映射 `details/excluded → id:dispatch_id`、`teacher_ranking → id:teacher_id`、`organization_summary → id:organization_code` 即可。此 `id` 只用于现有行渲染，不改变响应、授权或业务关联。

### 4.4 未计入记录

`excluded[]` 仅有 `dispatch_id`、`organization_code`、`date_unknown`、`code`、`message`。不要从旧列表自行拼补姓名、费用或权限外数据。弹窗显示“排课记录 / 机构 / 未计入原因”，优先展示已转义的服务端 `message`。

当前原因编码：

| 编码 | 含义 |
| --- | --- |
| `TEACHING_DATE_UNKNOWN` | 授课日期缺失或无效，无法判断月份 |
| `REJECTED` | 排课已拒绝 |
| `NOT_COMPLETED` | 尚未完成 |
| `FUTURE_TEACHING_DATE` | 授课日期尚未到达 |
| `SOURCE_ASSOCIATION_CHANGED` | 项目、需求、讲师、机构、事实或日期关联不一致 |
| `FACT_MISSING` | 未登记可信事实 |
| `NON_BUSINESS_FACT` | 演示或未配置事实 |
| `NOT_VERIFIED` | 尚未核对 |
| `VERIFICATION_SOURCE_INVALID` | 核对修订与历史依据不一致 |
| `ACTUAL_EVIDENCE_INVALID` | 实际分钟/课时换算依据无效 |
| `FACT_INVALID` | 事实格式或核对依据无效 |

当前日期与已存事实日期均已知且均在范围外的记录不会进入 `excluded`；已存日期在范围内但当前排课日期漂移到范围外时，仍以 `SOURCE_ASSOCIATION_CHANGED` 说明，避免记录静默消失。缺日期的记录因为无法归月会列入未计入清单，并另计 `undated_excluded_count`。页面分开标注“所选日期范围未计入”与“日期未知、无法归月”，不将总 `excluded_count` 描述为所选月全部待办。

## 5. 原 pageReport 的具体局部接点

现有结构是：

1. 读取 `/stats/report`、`/stats/overview`；校验 `routeEpoch`。
2. `.report-hero`、`.module-summary.four`。
3. `.charts.analysis-charts` 的 `an1…an4` 四幅经营图表。
4. `#rp-box` 文字报告；`#rp-refresh` 刷新，`#rp-print` 打印。

建议只增加一个“已核对授课”卡片，放在原经营概览卡片之后、`.charts.analysis-charts` 之前，并给旧图表加“原经营统计（独立口径）”说明。保留原 hero、导航、图表和文字报告，不将新结果赋给旧 `d`。

新卡片的建议节点与原控件复用：

| 位置/节点建议 | 复用及绑定 |
| --- | --- |
| `#rp-reviewed` | 原 `.card.data-card` 与 `.card-heading`，标题“已核对授课”，显示覆盖说明 |
| `#rp-reviewed-toolbar` | 原 `.toolbar`，月份/开始/结束日期输入，`.select-filter` 日期基准与机构选择，原 `.btn` 查询按钮 |
| `#rp-reviewed-summary` | 原概览布局，显示记录数与四类课时；课时用专用精确/未知展示，不用 `countTag` |
| `#rp-reviewed-ranking` | `renderTable` 显示名次、讲师、授课记录数、实际课时；可附其他三类独立课时 |
| `#rp-reviewed-organizations` | `renderTable` 显示机构、记录数、四类课时 |
| “查看明细”按钮 | `openModal('已核对授课明细', ..., {wide:true,noFoot:true})`，复用 `renderTable` |
| “查看未计入原因”按钮 | 独立 `openModal`，明确日期未知记录；当前数量为0时可禁用 |
| 正式导出按钮 | 当前禁用，并显示正式编码、费用币种未接入；禁止调用浏览器 demo 导出器 |

月份只是把查询日期填为自然月的便捷控件，不增加后台 `month` 参数。沿用已授权默认月报结构；原宿主可保存筛选到 `state.filters.report.reviewed`，刷新时恢复，不覆盖其他 report 筛选。手改日期为非整月就显示“自定义日期范围”。该筛选只作用于“已核对授课”卡片，不让用户误以为旧经营图表也被按月筛选。

机构候选项只用响应 `available_organizations`；如已有可信组织名称映射可用于显示，提交仍只用原始编码。“全部可查看机构”请求时省略 `organizations`。不拿前端 `canWrite()`、旧 admin/manager 角色、全部公司名单来生成授权机构。

若先保留旧 `Promise.all`，新卡片请求不要加入同一个全失败阻断组：M06的403/409应只影响新卡片，不能使原经营报告整页消失。更稳妥的局部调整是先创建原页面各占位容器，分别加载旧报告与新卡片；每一组只更新自己的节点，并各自显示错误。

## 6. routeEpoch、刷新竞态与弹窗清理

复用现有 `routeEpoch`（约173行）、`addRouteCleanup` / `isRouteCurrent`（约205行）与 `api` 的 `signal`、`quiet` 选项（约360行）。`api` 已携带同源凭据、在401时失效当前会话；新分支不要恢复旧 token 或绕过该处理。

以下是给 `pageReport` 内新增局部加载器的接线骨架，`readReviewedControls`、`renderReviewedView`、`renderReviewedError`、`setReviewedLoading` 为需在宿主实现的局部函数，不是已存在 API：

```javascript
const epoch = routeEpoch;
let requestSequence = 0;
let disposed = false;
let requestController = null;
let currentSnapshot = null;
const current = () => !disposed && isRouteCurrent(epoch, c, 'report');
addRouteCleanup(() => {
  disposed = true;
  requestSequence += 1;
  requestController?.abort();
  currentSnapshot = null;
  // 仅关闭本卡片拥有的弹窗，撤销本卡片生成的对象URL和事件监听。
}, epoch);

async function reloadReviewed() {
  if (!current()) return;
  const ticket = ++requestSequence;
  requestController?.abort();
  const controller = new AbortController();
  requestController = controller;
  currentSnapshot = null; // 开始新查询即停用旧快照下载
  setReviewedLoading();
  try {
    const query = readReviewedControls(); // 严格构造4项白名单，日期必填
    const view = await api('/management-reports?' + query.toString(), {
      quiet: true, signal: controller.signal,
    });
    if (!current() || controller.signal.aborted || ticket !== requestSequence) return;
    currentSnapshot = view;
    renderReviewedView(view);
  } catch (error) {
    if (!current() || ticket !== requestSequence || error?.name === 'AbortError') return;
    currentSnapshot = null;
    renderReviewedError(error); // 不以0或上次快照伪装本次成功
  }
}
```

`readReviewedControls` 应在发请求前校验日期；校验失败也应清除旧快照并显示本次条件错误，不能保持旧数据可下载。若局部选择器异步加载、弹窗内另有请求，仍同时检查路由 epoch、请求序号与容器存活。弹窗使用 `openModal` 的 `onClose` 处理其专属请求，路由清理只关闭自己创建的弹窗，避免关掉后来的别处弹窗。

旧 `#rp-refresh` 当前调用 `renderPage()`；可保留它刷新整页并通过 `state.filters.report.reviewed` 恢复新范围。新增卡片内“查询”只调用 `reloadReviewed()`，不要为一次机构/日期变动重建整个页面或重新加载旧图表。所有新文本先 `esc`，图标在局部更新后 `refreshIcons(localRoot)`。

## 7. 不同数据口径、不可用分支与正式下载

旧 `/stats/overview` 的 `d.project_amount`、`received`、`profit`、`eval_avg`、`by_unit`、`by_teacher`、`cost_type`、`teacher_score` 继续服务原经营分析，不能作为新正式授课快照的补值来源。

尤其原 `an2` 的单位课酬使用旧 `by_teacher.fee / by_teacher.hours`，并有缺值转0的旧展示逻辑。它不能改名为“正式课酬”，不能与新 `totals.actual` 拼出单价，也不能用旧 `fees` 的金额、已发状态或支付日期填新响应。建议将现有图表说明明确标注“原经营口径”，与“已核对授课”分区；保持旧功能本身的边界，不在此次文档中宣称其已升级为新制度。

新板块推荐文案：

- 课时完整：“已核对实际课时 12.50”；其他三类各自显示。
- 课时不完整：“计酬课时待补齐 · 已知小计…，…条缺失”。
- 费用/币种：“正式课酬及币种尚未接入”。
- 课程数：“正式课程编码尚未接入，暂不统计唯一课程数”。
- 支付日期：“支付统计暂不可用：真实支付登记尚未接通”。可展示禁用选项与原因；若允许点选发请求，应呈现409且不回落授课日期结果。
- 导出：“正式编码、课酬及币种来源尚未接通，暂不可导出”。

导出按钮的必要条件是：

```javascript
const canDownload = currentSnapshot?.permissions?.export === true
  && currentSnapshot?.availability?.formal_export === true;
```

当前第二项恒为false，所以不触发下载，也不提供“仍然导出”路径。总控仍应挂载 `/export`，保证直接访问或陈旧页面请求也由服务端校验并拒绝。

今后正式成功分支到位时，下载必须使用带同源会话的真实 GET，携带原快照条件与 `snapshot_version`，检查状态和响应类型后才读二进制。当前原 `api` 只读 JSON，不能直接拿它读取未来成功 XLSX；未来另加受路由取消控制的下载 helper，并在401/403/409时读取原 JSON错误、停止文件保存。不要把错误 JSON 作为 `.xlsx` 保存，不在客户端用 `details` 重新生成正式文件。

已接入的 `IntegrationDeliveryHost` 会把正式 configure/confirm/correct 明确拒绝为409，并保护旧课酬、完成与归档入口。接 M06 不得放松这些限制，不新增或推定结算规则，也不能将统计读接口做成事实补录入口。

## 8. 错误与验证交接

| 响应 | 新板块动作 |
| --- | --- |
| 400 | 显示参数问题，保留用户输入；不发送未知参数或自动纠正成别的统计口径 |
| 401 | 沿用原 `api` 的会话失效处理，停止局部后续更新和下载 |
| 403 | 显示无统计查看/导出授权；清除当前快照，不显示缓存结果或扩大机构范围 |
| 409 支付未接入 | 显示原因，不回落到授课日期 |
| 409 来源上限/来源重复/权限或事实变化 | 显示服务端原因；建议缩小机构范围或刷新，不截断结果 |
| 409 正式导出未就绪 | 保持正式导出不可用，不使用旧费用或 demo 文件替代 |
| 网络/格式/服务错误 | 显示重试状态；不把异常当作空结果 |

**M06适配器集成验证95项通过**。运行 `bash scripts/M06Integration-check.sh`：真实Auth登录、真实M01配置、真实M05保存/核对与完成门禁，使用唯一临时目录中的合成H2，不调用Db.init/seed。覆盖空月份、合法零课时、竞争并列排名、跨机构/撤权/退出/显式DENY、来源与跨月日期漂移、转换依据、输出版本、下载阻断、HTTP适配包装及查询前后全表内容不变；不连接生产，不运行全套检查。

**共享宿主路由接线及原页面桌面/手机视觉验收仍待总控实施后记录。** 上述适配器测试通过不代表接口已挂载、页面已接线或视觉已验收。

建议总控接线后覆盖：实际会话和M01越权机构、权限撤销后下载、PAYMENT409、缺预计/计划/计酬值的未知传播、缺日期未计入说明、同批四类总量/明细/排名/机构汇总、旧图表与新范围分隔、过滤快速切换/离开页面/关闭弹窗后的迟到响应，以及桌面和手机样式加载。该清单用于宿主接线后的回归与视觉验收，不能用适配器专项测试结果代替。
