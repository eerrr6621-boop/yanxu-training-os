# M07 真实会话评分预览适配合同

2026-09-22。本轮只做无持久化统计草案，未挂公共Api或原页面，未部署。核心和组件的统计定义继续见`INTEGRATION.md`。用户已授权从现有评分Excel生成草案，无须重新索取原始答卷。

## 本轮新增

`SurveySummaryImportsIntegration.java`提供独立HTTP入口、真实会话及M01权限、可信项目目录、请求大小及并发控制、最终权限复核。没有建表、迁移、保存、确认、覆盖、导出或正式结果查询；不向M06讲师考核或M08正式总结提供结果。`policyConfirmed:false`、`canCommit:false`、`synthetic:false`固定由服务器生成。

`SurveySummaryImportsResponses`保留原四参API，新增五参`preview(bytes, projectId, rules, context, Runnable checkpoint)`；主循环、ZIP读取块和XML事件可协作取消。取消异常向调用方传播，不伪装成文件格式错误，线程中断标记不清除。

## 公共宿主必须采用的挂载位置

当前`Api.handle`会在登录检查前读最多1MiB正文，再持有`MUTATION_LOCK`运行整个route。**不能只在旧route中加一行新handle，也不能提高所有接口的JSON上限。** 新接口要在原统一正文读取及整段锁之前分流，只对本模块路径使用独立读取器。示意替换`Api.handle`中try主体的分发部分：

```java
if (SurveySummaryImportsIntegration.matches(path)) {
    Auth.Session supplied;
    synchronized (MUTATION_LOCK) {
        supplied = Auth.get(token(ex));
    }
    SurveySummaryImportsIntegration.handle(ex, supplied);
} else {
    // 原POST类型检查、1MiB正文读取及普通route逻辑原样保留。
    // ...
    synchronized (MUTATION_LOCK) { route(ex, path); }
}
// 保留现有catch ApiException / catch异常和最终flushResponse(ex)。
```

`handle`自己再次验证请求Cookie/X-Token与传入会话为同一真实会话。成功通过`Api.ok`暂存统一`{code:0,data:...}`响应，宿主随后执行原`flushResponse`；不能提前return跳过刷新。认证、来源快照及最终响应生成在短锁内；上传、Base64解码和Excel解析在锁外。若在持有`MUTATION_LOCK`时调用，适配器直接503，避免悄悄阻塞撤权或系统其他业务。

请求错误只产生通用`ApiException`，适配器不输出日志或把请求正文缓存到exchange属性。宿主、反向代理和诊断中间件也不得记录此路径正文、文件名、姓名、单元格或Base64；原通用异常处理不得绕过适配器的安全异常映射。响应沿用`Cache-Control: no-store`。

## 接口

### GET `/api/survey-response-imports/config`

只接受GET，不接收任何查询参数。要求真实有效会话、启用人员绑定，以及至少一个机构的显式`survey.preview / HANDLE`授权。没有授权返回403；有授权但还没有有效受理项目返回`ready:true,projects:[]`，不造假项目。

```text
ready:true, synthetic:false, adapterId:"internal-survey-xlsx-v1",
maxBytes:5242880,
projects:[{id:现有数字项目id,key:"该id的十进制文本",name:服务器项目标题}],
rulesDraft:{id:"draft-per-question-v1",minScore:"0",maxScore:"10",confirmed:false,...},
policyVersion:"M07-DRAFT-20260922-1",
canCommit:false, policyConfirmed:false, historyAvailable:false
```

`key`只是兼容局部组件的技术选择值，不是正式项目编码。配置不会返回机构授权明细、人员绑定、其他项目名称或个人答卷。当前项目默认选择可由宿主请求桥接在配置结果上增加`defaultProjectId`；组件只接受目录内id。

### POST `/api/survey-response-imports/preview`

只接受`application/json`、UTF-8、未压缩正文，无查询参数。正文必须恰好包含以下三个字段，不允许客户端传入规则、组织、权限、历史、synthetic或确认标记：

```json
{"fileName":"评分.xlsx","xlsxBase64":"...标准带补位Base64...","projectId":123}
```

`projectId`是正的JS安全整数（JSON数字）；文件名最多255字符、扩展名`.xlsx`，仅作格式检查，不作为项目、讲师、批次或版本识别依据，也不会回显。

限额：整个JSON正文7MiB，Base64最多6,990,508字符，解码后XLSX最多5MiB。请求正文读取前先检查真实会话、人员绑定和显式预览范围；正文解析后检查具体项目的可信来源及权限，**通过后才解码/解压Excel**。正文预扫描禁止嵌套对象、数组和超过三个字段，避免借较大上传限额制造大量JSON容器。

结果保持原组件的`RESPONSE_SUMMARY_PREVIEW`合同，包含逐题有效/空白/异常数、精确和及草案均值。新增`historyAvailable:false`和`policyVersion`；不返回原始答卷、身份字段、评分原文、文件名、文件/评分指纹。无持久化历史，`duplicateCheck.status`明确`NOT_CHECKED`，不能冒充已查重或生成跨批记录。

坏Excel仍可能返回成功HTTP封套，但`data.state:"ERROR"`、题目数组空且含通用文件问题；它不是成功的统计结果。接口错误状态：400参数/编码，401会话，403授权/可信来源不可核实，405方法，408期限届满，409处理中配置/来源变化或嵌套事务，413限额，415类型/压缩，429并发占用，499取消/上传断开，503宿主挂载或服务不可用。断开或超时已关闭连接时不保证客户端还能收到JSON错误。

## 权限与可信来源

复用`Auth.current`、`OrganizationAccessStore.person`、`OrganizationAccessStore.authorize(s,"survey.preview",HANDLE,org)`。不使用`Auth.isAdmin/canWrite`，不把需求查看权限、旧管理员/经理角色当作调查预览授权，不新增真实岗位默认授权。

项目来源只查询`projects → workflow_acceptances → workflow_demands`。必须存在唯一受理关系，`projects.demand_id`等于受理需求，需求不是草稿，受理资料版本与需求当前资料版本一致，组织编码可由现有M01授权核实。旧未迁移项目、关系不一致、被撤回的资料或缺失来源不进入配置，也不能通过直接提交id绕过。

解析前冻结：账号、人员绑定、权限配置版本/授权机构、项目id/标题/状态、受理需求/资料版本/团队/办理人/时间、工作流版本、规则版本。解析后重新读取真实会话、当前权限配置、当前源记录并逐项比较；HTTP生成成功响应前再做一次相同复核。撤权、停用、注销或来源变化时丢弃结果。

所有数据库读取在短暂`MUTATION_LOCK`内，适配器不创建事务，不提交外层事务，遇到未完成事务直接409。它不修改业务数据或配置。实际网络写回沿用宿主锁外刷新；保证的是成功响应生成前完成复核，不能保证响应传输开始后发生的撤权能收回已经发出的字节。

## 运行上限与取消

全进程最多2个在途上传/解析，同一账号最多1个；满额立即429，不排队、不创建后台解析任务。许可覆盖上传、解析和最终检查，只有调用真实退出后才释放；超时不会先释放再放入新任务。

每次处理预算15秒（含读取和解析）。ZIP/XML/统计循环检查线程中断和期限；HTTP定时器到期关闭该exchange以中断阻塞读取。15秒为协作预算：等待共享业务锁或数据库调用时，要等该调用返回后检查，不能承诺任何情况下都严格在15秒内返回；许可仍保留到实际退出。客户端上传中断返回通用失败；已完成上传后，JDK HttpExchange不能即时可靠感知浏览器关闭，解析仍受期限、文件结构限额及并发槽约束，不能无限继续。宿主还应维持现有服务器/代理请求读写超时，避免底层套接字读取无限等待。

没有原文件落盘、临时解压、浏览器持久化或统计结果缓存。请求对象与不可变字符串由运行时回收，不能承诺内存绝对零残留；适配器清零可控的上传字节缓冲。取消定时器及时移除，不保留exchange引用。

## 内部调用与现有页面

`config(Auth.Session)`、`preview(Auth.Session, Map<String,?>)`也会自行核验真实会话和权限，不允许伪造Session或仅传uid；不应从主业务锁或未完成事务里调用preview。package内取消重载用于宿主取消信号和隔离验证，不是浏览器参数。

前端无需新协议或新样式。按`INTEGRATION.md`原`openModal`接点挂载已有`mount`，将其`/api/...`路径与已编码JSON适配给现有`api`函数，弹窗关闭/路由变化时调用cleanup和AbortController。入口显示条件使用本接口真实配置，不能因旧admin/manager角色或“有项目”就展示可用导入能力。

正式主Api与原页面浏览器接入、真实岗位授权配置由总控完成；本轮只提供可独立验证的适配器。统计下限、正式分母、去重、舍入、复核及修正版规则仍待确认，不开放任何保存入口。
