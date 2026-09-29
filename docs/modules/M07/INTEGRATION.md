# M07 原平台评分 Excel 汇总：原系统接入说明

更新至2026-09-22。真实会话/显式项目授权的无持久化HTTP适配器已实现，准确挂载和限额合同见`HTTP_PREVIEW.md`；公共Api及正式页面仍由总控接入。旧CSV说明移至`LEGACY_CSV.md`，只作历史核心参考。

## 已确认的方向

用户已确认原平台没有汇总导出，只有其提供的逐人评分 XLSX；随后明确选择：**“由研序生成汇总：读取评分，统计人数和各题平均分；统计口径再确认。”** 因此原先“不从答卷计算”的范围已被本次用户选择更新，不再要求不存在的汇总导出。收集问卷仍使用原平台，研序不发问卷。

另外，用户要求在原系统完善，沿用原界面、导航、表格、弹窗和样式，不交付另一套模块演示系统。独立合成预览仅作内部逻辑验证，不作为正式页面或新增M07导航。

本轮实现评分 XLSX 的安全解析、逐题统计草案、项目权限边界、重复检查、局部预览组件。统计口径、复核岗位、修订版本的业务唯一性和正式覆盖规则尚未确认，因此 `policyConfirmed:false`、`canCommit:false` 固定，未保存或覆盖业务数据。

MASTER已在2026-09-22同步用户允许生成统计草案的授权；正式统计口径仍需另行确认。

## 原页面与局部接点

基于当前 `web/app.js` 的函数定位（行号可能随其他集成变化）：

- `pageQuestionnaires(c)`（约2205行）：复用已有“效果评估”导航、项目上下文、`card data-card`、`card-heading`、现有列表及操作栏。在现有工具栏增加“导入评分Excel”，由既有 `openModal(..., {noFoot:true, wide:true})` 提供弹窗，在弹窗内容根节点挂载本模块 `mount(root, context)`。
- `pageProjectDetail(c)`（约1732行，效果评估面板约1831行）：复用现有“管理评估/查看评估”进入方式。以当前项目作为配置的 `defaultProjectId`；不新增分工编号导航。后续已确认结果只在原面板内加记录数、口径状态及“查看汇总”局部内容。
- `showQStats(r, pageContext)`（约2397行）：沿用现有统计弹窗外壳和操作习惯；新文件不能直接套旧1–5分分布、旧 `avg_score` 综合均值或 `responses` 作答人明细。新数据展示10分制各题汇总及原问卷“总体满意度”题，身份字段不展示。
- 复用原 `style.css → studio.css → ledger.css → v10.css → v13.css` 样式加载顺序、现有按钮/表单/表格类与 `renderTable` 的布局习惯；本模块CSS只补根节点内的必要排版，不加全局主题、侧栏或大标题。
- 宿主的 `routeEpoch/isRouteCurrent`、弹窗关闭及页面离开都应使传入signal失效，并调用mount返回的cleanup；旧响应不能覆盖新弹窗或另一项目结果。

必要业务差异：新流程入口导入原平台文件，不走研序新建/发布问卷、发送作答链接或收集个人答卷页面。原有历史记录按现有权限保留查看，不由本模块删除。上传记录数不能填进 `send_total`、回收率或“60%回收要求”，因为文件没有邀请人数分母；不能把10分制转成百分数或旧5分制。

共享 `app.js/index.html/Api/Db/Auth` 均未修改。总控实际接入后需在原系统浏览器验证样式真实加载、弹窗操作和移动表格，而非以内部演示替代正式视觉验收。

### 挂载与请求适配

正式组件文件为 `web/modules/survey-results/index.js`、`styles.css`，CSS放在现有五份宿主CSS之后加载。组件不提供页面导航或自建弹窗，导出 `async mount(root, context)`，返回cleanup函数。

```javascript
const { mount } = await import('/modules/survey-results/index.js');
const release = await mount(modalContentRoot, {
  mode: 'live',
  signal: modalAbortController.signal,
  request: (url, options) => api(url.slice('/api'.length), {
    method: options.method,
    body: options.body === undefined ? undefined : JSON.parse(options.body),
    signal: options.signal,
  }),
});
// 宿主关闭弹窗或路由失效时：modalAbortController.abort(); release();
```

以上`modalContentRoot`和`modalAbortController`由宿主在原`openModal`内建立并接入关闭/路由生命周期。原`api`函数自动加`/api`前缀、JSON编码并解包`{code,msg,data}`，所以不能将它未经适配直接传给组件，否则会重复前缀和正文编码。可见项目配置可附`defaultProjectId`；组件只在它属于可见项目目录时预选。

文件选择先保留在当前内存，只有用户点击“生成汇总草案”才向同源预览接口发送；切换项目/选择新文件/关闭弹窗使旧请求失效。没有浏览器持久存储、个人答卷表或保存按钮。服务端错误正文必须保持通用，不回传原始行；现有`api`会弹出`msg`，不能依赖组件再次过滤来补救接口隐私问题。

`scripts/M07-build-preview.mjs`仅生成原弹窗结构的内部合成测试壳；旧`outputs/问卷汇总导入演示.html`为之前阶段的历史演示，不是当前功能或正式UI入口。

## Java 新核心

新增 `src/com/training/SurveySummaryImportsResponses.java`，Java17标准库，复用 `SurveySummaryImports.Project`。旧CSV核心保留，不能用于接收答卷。

```java
var rules = SurveySummaryImportsResponses.DraftRules.proposed();
var access = new SurveySummaryImportsResponses.PreviewContext(
    mayPreview, visibleProjects, previousImports, historyAvailable, false
);
Map<String, Object> result = SurveySummaryImportsResponses.preview(
    xlsxBytes, selectedProjectId, rules, access
);
// Json.write(result) 可复用现有JSON工具。
```

权限、可见项目、规则、历史记录及synthetic标记全部由服务端提供，禁止直接信任上传JSON。无权限、不可见或不唯一的项目在解压前抛 `SecurityException`（宿主映射403）；不会读取文件内容或泄露其他项目历史。数字项目id与来源字符串编码保持分开，关联只能通过当前可见项目选择，不按姓名或文件名猜项目/讲师。

格式版本 `internal-survey-xlsx-v1`：按工作簿关系定位名为“表格题1-培训满意度调研”的工作表，识别已确认的10个“满分10分”题头。题目列允许调序，重复/缺失/不一致题头返回文件错误；忽略姓名、工号、机构等列的值，只用行是否有内容识别空白行。身份值不出现在返回对象、错误、日志或演示中，不把真实文件或答案放入源码/Git。

## 明示的统计草案

- 默认接受0–10分的数值，**0是否有效及正式下限待确认**；可由服务端 `DraftRules(minScore,maxScore,displayScale)` 配置更严格下限，上限不超过题头已注明的10分。
- 各题独立统计，分母为该题有效评分数；空白不按0，非数字/越界/错误/公式/百分数/日期分别列为异常，未纳入该题平均分。
- 精确十进制累加，最后显示均值时默认两位 `HALF_UP`；无有效分数时 `averageText:null`，不能显示0。
- `responseRowCount` 是“答卷记录数（未去重）”，不是独立人数或已确认有效答卷数。十题全空但行内有记录标记的行保留为空白；完全空白/只有格式的行跳过。尾部文字不会被默默当有效评分。
- 暂不按状态、隐藏/筛选行删除记录，也不按个人身份去重；这些口径待确认。未知状态文本不输出。
- “总体满意度”对应原题 `q10`，不把十题相加、加权、再平均或转换为百分比。其余九题逐项显示。

上述均为可复核的预览草案，不是已经确认的评价制度，也不自动用于讲师考核或排名。

## 安全与资源边界

原始XLSX上限5MiB，实际ZIP解压累计32MiB、256条目、10000数据行（含表头最多物理行10001）、64列、每格4096字符、共享字符串5万项且总文本16MiB、XML深度32。无用ZIP条目同样计入解压预算。重复条目/关系/题头/行号/单元格坐标拒绝；不落盘解压、不访问网络、不读取外部关系、拒绝宏/二进制部件、DTD和外部实体。

评分格包含公式时不用缓存值；百分数格式/日期格式不解释成评分。限制数字长度、指数、precision/scale，防止极端指数耗尽内存。文件级结构错误不给部分成功结果；返回通用原因，不含解析器原始异常或单元格原文。

错误定位最多返回2000条，`issueCount`为总数、`issuesTruncated`说明截断；全部行的题目计数和均值仍按同一草案完整计算。宿主应限定预览并发，避免多个压缩包同时解压占用过多内存。

## 宿主接口（适配器已实现，待公共入口接入）

以下为组件原始协议说明。2026-09-22适配器已对该协议实现真实权限保护；实际返回额外包含`historyAvailable:false`与服务器`policyVersion`，并移除无客户端用途的指纹。以`HTTP_PREVIEW.md`为准确接口和挂载合同。

`GET /api/survey-response-imports/config`

未接入时 `ready:false,synthetic:false,adapterId:"internal-survey-xlsx-v1"`。宿主接入只读预览后，可返回：

```text
ready:true, synthetic:false, adapterId:"internal-survey-xlsx-v1",
maxBytes:5242880, defaultProjectId?:现有可见项目id,
projects:[{id,key,name}],
rulesDraft:{id:"draft-per-question-v1",label:"统计口径草案",
            minScore:"0",maxScore:"10",confirmed:false,description:...}
```

`POST /api/survey-response-imports/preview`

请求 `{fileName, xlsxBase64, projectId}`。仅用于无持久化预览；文件名不作项目/人员或修正版识别凭据。不接受客户端rules、permission、synthetic、历史结果等授权数据。宿主JSON正文限制建议7MiB，base64字符数在解码前限制为5MiB对应的编码长度（6,990,508），拒绝非法base64/数据URI，解码后再次检查5MiB。不得将原上传正文、文件名中的姓名或base64写日志。

返回白名单：

```text
mode:"RESPONSE_SUMMARY_PREVIEW", state:"PREVIEW"|"ERROR",
adapterId, synthetic:false, canCommit:false, policyConfirmed:false,
rulesDraft, projectId, projectName, responseRowCount, overallQuestionKey:"q10",
questions:[{key,label,column,validCount,blankCount,invalidCount,sumText,averageText}],
issues:[{row,column,code,message,severity}], issueCount, issuesTruncated,
duplicateCheck:{status,recordIds,message}, fileFingerprint?,scoreFingerprint?,warnings
```

不返回答卷数组、姓名、工号、原始格内容或旧CSV的raw/rawCells。页面直接展示 `averageText`，不在前端重算。`sumText`为验证所用精确合计，不应当成综合评价分展示。错误state时questions为空、记录数0，不显示“统计成功”。

## 重复与修正版

`PreviousImport(id,projectId,fileFingerprint,scoreFingerprint)` 由宿主当前可见历史提供；`historyAvailable:false` 明确返回 `NOT_CHECKED`，不以空列表冒充已查重。

- `EXACT_FILE`：同项目历史文件SHA-256一致。
- `SAME_SCORES_CANDIDATE`：同项目评分部分（不含身份、按题目/行顺序）一致，仅候选，不能断言同一批答卷。
- `NONE`：已查询当前可见历史但未见以上匹配。
- 任一评分异常时不生成评分指纹，仅可检查完整文件指纹，避免把不同异常文本视为相同评分。

不依文件名/日期/任意版本文字决定新旧；修订替换尚未实现。正式保存需要先确认记录唯一性、复核权限、口径版本和修订规则，再由总控在 `Api.MUTATION_LOCK` 内重新检查并用 `Db.transaction` 原子写入。本轮无DDL/迁移/数据库连接。

## 已验证与仍未接入

Java合成正常/异常/越权/资源测试以及前端定向验证见 `VALIDATION.md`。用户样例已在本机只读解析，全部10题的计数、精确和、草案均值与独立工作簿读取结果一致；真实值未写入演示或代码库。

已补全服务端真实会话、M01显式授权、可信受理项目目录及预览适配器。待总控：公共Api独立分发、原效果评估页面/弹窗挂载、真实岗位授权及原系统实际视觉验收。已有导入历史未持久化，本轮明确未查重。待业务：正式统计口径、复核岗位、保存与修正版规则。模块没有部署、没有操作生产、没有提交其他任务的改动。
