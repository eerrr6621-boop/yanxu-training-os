# M07 原问卷汇总导入 · 首轮集成说明

状态：模块已实现已统计汇总的 CSV 只读预览核心与合成演示；用户确认实际来源只有逐人答卷 XLSX，没有汇总导出。XLSX 后续用于生成汇总还是仅作项目附件，待用户选择；未接入共享路由、会话或数据库。2026-09-21。

## 实际行为与范围

现有实现遵循此前“只读取原平台已统计汇总”的范围，逐行保留原值、单位、样本数和修订标识。现有核心不会发问卷、计算满意度、从个人答卷聚合、按参与人数补样本数、在百分数和分数之间转换。文件内重复行不会被静默丢弃。它目前仅以合成 CSV 演示，不能处理用户提供的逐人答卷 XLSX。

本轮所有返回均为 `mode: PREVIEW_ONLY`、`canCommit: false`，没有提交、替换或删除方法。不同修订标识仅产生“修正版候选”，不猜测版本顺序或自动取最新。用户尚未选择由研序生成汇总或仅保存项目附件；统计口径、复核岗位和覆盖规则也未确认。在范围与规则确认前，现有实现保持此限制，不据实际文件自行扩展统计或附件存储能力。

## 文件

- `src/com/training/SurveySummaryImports.java`：Java 17 零外部依赖的适配边界、CSV解析、字段映射、校验、项目关联、重复检查与输出映射。
- `web/modules/survey-results/index.js`、`styles.css`：宿主前端模块。
- `web/modules/survey-results/preview.html`：独立合成演示。
- `scripts/M07SurveySummaryImportsTest.java`、`scripts/M07-check.sh`：独占定向验证，只编译核心与该测试到自己的临时目录。
- `scripts/M07-frontend-check.mjs`、`scripts/M07-build-preview.mjs`：使用已有浏览器运行独占页面验证；或从同版模块重建独立演示与合成CSV。

所有更改仅涉及 M07 独占文件；没有改 Main / Api / Db / Auth / Json 或前端主应用，没有改共享检查脚本、运行全套或写共享 out，没有 Git 暂存与提交。其他任务已有工作保留原状。

## Java 接点

入口：

```java
SurveySummaryImports.Preview result = SurveySummaryImports.preview(
    csvBytes,                     // 服务器限制大小后的原始字节
    new SurveySummaryImports.CsvAdapter(),
    sourceProfile,                // 服务端登记的字段/量表解释，禁止相信请求中的profile
    SurveySummaryImports.Mapping.fromNames(mapping),
    trustedContext               // 宿主从会话权限及可见项目范围派生
);
String response = Json.write(result.toMap());
```

`SummaryAdapter.parse(byte[]) -> ParsedTable` 是现有已统计汇总的格式适配边界；`Profile` 为来源和量表解释。当前 `CsvAdapter` 只支持 UTF-8 CSV（可带 BOM），不把 XLSX/GBK/Excel工作簿当CSV读取；其他格式尚未实现。用户提供的 XLSX 是逐人答卷，不能仅新增一个读取格式适配器就将其解释为已统计汇总。其后续处理需要先完成业务方向选择；若选择生成汇总，须另行确认统计口径和实现边界，不能在页面或既有适配层猜测规则。

`Profile(adapterId, sourceId, synthetic, sampleCountMeaning, metrics)` 和 `MetricSpec(key,label,unit,min,max)` 由服务端管理。SCORE 必须明确量表范围，PERCENT 使用 0..100 的百分数单位。92.5% 和 92.5 在该单位下可判数值相同；0.925 保留为 0.925%，绝不自动乘100。源文件若采用0..1比例、中文单位、宽表多个指标，必须在已确认的来源适配中明确处理，不套用演示定义。

`PreviewContext(mayPreview, visibleProjects, existingSummaries)` 中的权限布尔值、项目目录、既有结果不能从请求正文接收。无权限在解析前抛 `SecurityException`；宿主建议映射403。当前没有定义复核岗位的实际角色，请勿硬编码成现有某角色。

`Project(id, externalKey, label)` 关联现有 `projects.id` 数字记录；`externalKey` 是来源识别值的精确字符串。不会转数字、按姓名/标题猜测、创建项目、把组织编码当项目id。相同识别值匹配多条可见记录会报错；不可见和不存在统一“未匹配到当前可见项目”，不会返回隐蔽项目或历史记录id。

现有 `projects` 表未见正式项目识别编码列，宿主应由 M02 已确认的项目登记/外部识别关联提供目录；本模块没有自行添加字段。当前 `DEMO-P001/002 -> 9001/9002` 全部为合成，不能当正式编码分配。

## 字段映射与校验

映射形状为“规范目标字段 -> CSV原列名”，例如 `{"project_key":"项目编码","value":"满意度"}`。所有目标字段如下：

| 字段 | 当前演示解释 | 要求 |
| --- | --- | --- |
| project_key | 外部项目识别列 | 必填；精确匹配可见项目目录 |
| summary_key | 来源汇总批次标识 | 必填；正式唯一键语义待确认 |
| metric_key | 来源指标识别值 | 必填；必须已有服务端量表定义 |
| value | 来源已统计值 | 必填；保留原文本，仅解析比较/范围校验 |
| unit | PERCENT / SCORE | 必填；须与指标定义一致 |
| sample_count | 来源该指标样本数 | 必填；非负整数，最大2147483647；0的业务含义待确认 |
| revision | 来源修订标识 | 可选；缺失不推断版本 |

`Mapping.canonical()` 对应带七列表头的合成样例；`Mapping.suggested(headers)` 仅建议实际存在的同名列，可省缺失的可选revision；显式关联到不存在的列仍报错。缺必填映射、重复用列、重复/空表头都阻止行解释。多余非映射列不参与指标运算；原始字段仍在预览中保留，上传前须保证整份文件只有汇总内容。

CSV支持逗号、标准双引号转义、引号内换行、CRLF/LF以及末行无换行。拒绝不闭合引号、引号后异常文字、非UTF-8、NUL、列数不齐。语法无法可靠恢复时整表给出错误；普通行错误保留行和原始字段继续预览。

上限：2 MiB、5000数据行、64列、单格4096字符。文件大小检查也应在HTTP读取和浏览器选择阶段执行。小数不接受科学计数、千分位、范围、NaN或缺失符号；不猜测本地化数字。

## 重复与修正提示

当前演示比较键为 `(projectId, sourceId, summaryKey, metricKey)`。此键只服务合成演示，不视为正式业务唯一性决定；须确认原平台问卷/期次/项目/指标如何唯一识别后调整。

- `IN_FILE_DUPLICATE`：同键、数值、单位、样本数、修订标识相同；保留所有行。
- `IN_FILE_CONFLICT`：同键但上述内容至少一项不同；同组每行提示。
- `EXISTING_SAME`：既有一条同键且内容相同。
- `EXISTING_CONFLICT`：同键内容不同但不能判为修订候选，或已有多条历史记录。
- `REVISION_CANDIDATE`：既有一条同键，双方修订标识非空且不同；不代表新版本。

文件内和既有记录问题可能同时出现。行 `duplicateStatus` 优先显示文件内状态，完整既有状态仍在 `issues` 内；`existingRecordIds` 只含当前可见范围内同来源的记录。

只有完整解释且无行错误的记录才执行重复检查。错误行 `duplicateChecked:false`，其 `duplicateStatus:NONE` 不代表“未见重复”；应显示“待修正后检查重复”。

## 输出契约

`Preview.toMap()` 可交给已有 Json 序列化，含：

```text
mode="PREVIEW_ONLY", canCommit=false, synthetic, adapterId, sampleCountMeaning,
headers, rows, issues, errorCount, warningCount
rows[]: line, raw, rawCells, projectId, projectName, summaryKey, metricKey,
        valueText, unit, sampleCount, revision, duplicateStatus,
        duplicateChecked, existingRecordIds, issues
issues[]: line, field, code, message, severity="ERROR"|"WARNING"
```

`line` 为CSV记录起始的实际物理行号；全局文件错误为0。`raw` 保留合法表头对应的单元格，`rawCells` 按顺序保留完整行（包含越界列，不与用户表头共用键空间）。数值展示必须用 `valueText` 和单位，不以JavaScript浮点替换原文。对外读结果时仍保留指标维度，M06/M08不得将PERCENT/SCORE相加或二次平均。

## 现有 CSV 演示的宿主页面/接口建议（尚未接入）

宿主路由挂载 `web/modules/survey-results/index.js`，调用 `await mount(root, context)`，卸载调用cleanup并abort旧signal。CSS仅 `.yx-survey-results` 范围。合成模式不发送业务网络请求；live模式未就绪明确显示未接入，不回落到演示。

以下拟议接口只描述现有已统计汇总 CSV 核心，须由总控接入，不是现有可调用路由，也不作为新 XLSX 统计或附件存储方向的接口承诺。用户选择后须复核其适用性：

1. `GET /api/survey-summary-imports/config`：当前正式配置应返回 `ready:false, synthetic:false, adapterId:"pending"` 及 `reason` 未就绪说明，说明实际来源为逐人答卷 XLSX、处理方向尚待用户选择，不使用 syntheticProfile 冒充正式配置。
2. 如未来继续采用已统计汇总导入，其来源适配、权限和项目关联确认后，配置才可返回 `ready:true, synthetic:false, adapterId, fields:[{key,label,required}], projects:[{id,key,name}]`。
3. `POST /api/survey-summary-imports/preview`，请求 `{csv,mapping}`，宿主验证会话、限制正文、选择服务端profile、载入当前可见项目与既有汇总后调用上述Java方法。前端响应仅接受 `mode:PREVIEW_ONLY, canCommit:false, synthetic:false`。

当前只读，无需DDL或迁移。未来真正保存/替换需要用户规则确认、权限检查、复核人/时间、来源文件指纹和适配版本、旧新记录关系及审计。必须由总控在 `Api.MUTATION_LOCK` 内重新校验重复和预期旧版本，再用 `Db.transaction` 完成多步写入，防止将预览结果直接当写入授权。这里没有提供未经确认的表结构或创建生产连接。

## 合成规则版本、实际来源与待办

规则版本 `synthetic-csv-v1`，来源 `DEMO-SURVEY`；指标满意度百分数0..100、总体分数1..5，样本数解释“合成示例的该指标有效样本数”。这些只是既有演示规则，不是实际问卷的统计规则。

此前按“把原平台算好的结果挂到研序项目”的范围向用户说明材料用途。用户随后提供的 XLSX 已做只读结构检查：两表为“数据列表”“表格题1-培训满意度调研”；首行含录入人姓名/员工编号/时间等个人答卷字段，评分表含10道满分10分的题目。这是答卷明细，不能当已汇总结果导入。未修改原文件、未展开、输出或另存个人答卷值、未聚合/重算、未将实际文件放入模块目录或 Git。

用户最新确认原平台没有汇总导出，导出的就是该逐人答卷 XLSX。不再要求用户寻找不存在的原平台汇总文件，也不把收到该文件作为下一步前提。

已向用户说明：从此来源得到项目满意度，需要由研序生成汇总，会调整旧的“不重算”范围。当前正在等待用户选择“由研序生成汇总”或“仅作为项目附件”，尚未取得选择。本次说明更新不改变已授权业务范围；现有 CSV 核心仍为合成只读演示，新 XLSX 统计与附件存储能力均未实现。

后续按用户选择推进：

- 若选择由研序生成汇总，先确认题目最小值、统计算法、有效样本与缺失项分母、零样本处理、项目关联及结果唯一性，再明确复核岗位、修正版识别和覆盖规则。仅凭表头“满分10分”不能决定这些规则；不得沿用演示1–5量表，也不得把个人行数直接当有效样本数。
- 若选择仅作为项目附件，另行明确项目关联、访问权限和保存方式；这不代表已授权生成统计结果。现有模块不含附件上传存储接口。

在选择及相应规则确认前，对实际 XLSX 保持未接入状态；不计算个人答卷，不以适配格式为由越过范围决定，也不把合成结果混入正式配置或业务数据。

## 验证

运行 `bash scripts/M07-check.sh`，只使用独占临时目录，不写共享out；覆盖正常/异常、引号与物理行号、单位/范围/样本数、项目不可见/歧义、权限拒绝、文件和既有重复、修订候选、行列/单格/文件限制和原始字段保留。

前端验证及最后通过数量见同目录 `VALIDATION.md`。共享路由、真实来源、复核权限、持久化和跨模块验收未执行，不能据此认定正式上线完成。
