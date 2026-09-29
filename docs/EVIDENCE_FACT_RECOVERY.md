# 独立事实恢复：本地冻结对照与安全审查

日期：2026-09-08。状态：**跨行角色风险已修复并重新冻结；纯回归通过，等待同权重历史复测，尚未批准部署。** 本文保留前一冻结基线及其风险诊断，不覆盖旧对照结果。

本轮改变的是资料证据处理，不是模型权重。旧 V3 模型、题目、历史 relevant/backup 标签及已封存评估输出均未修改；真实简历未用于训练。后续应以相同权重、相同标签分别报告处理链变更，不能写成后训练带来的提升。

## 已实施范围

- `worker.py` 与 `EvidenceSections` 增加独立事实范围：完整介绍行通过所在章节上下文检查后可供评分；整篇 incomplete 状态保留。
- 老的完整专业章节仍使用 `independent_sections_v1`；新独立事实使用 `independent_facts_v2`。新 scope 增加 `kind`、`context_start`、`context_end`，原 `heading_*` / `source_*` 继续为原始归一化请求的 Unicode code-point 偏移。
- Java 根据原完整文本独立重算 scope，逐个核对范围、类型、版本和引用原文。v1 不接受 v2 fact 范围；未知版本拒绝。最多 64 个范围，超限明确容量失败，不任意截断。
- mask 只保留核实范围的正文与标题本身，不再从标题到正文末尾恢复中间全部文字。标题晚于条目时也不会抹掉条目正文。
- 补充识别真实存在的正向栏目：擅长领域、授课专长、授课领域、主讲方向。另仅对紧邻课程栏目、至少两个连续独立书名号条目的反向列表做有界归属检查；空行/跨页、孤立单题名、计划/参训/他人语境不推定课程归属。
- `ProfessionalEvidence.records` 保留安全完整原行；当认证与实际教学事实同在一行时，另保留独立已完成事件的连续子串及 `context_text` / `context_source_offset`。不得把证书主题借给通用课堂记录，亦不把整行改成已教学。
- 授课风采中的明确“授课”事件标题可以作为教学记录线索；资格、计划、外部老师标题仍排除。明确讲师面向对象已完成工作坊的事件得到教学角色。
- 同行机构/团队认证与活动不能转成本人教学，出现明确他人主体、前后指代否认或计划指代仍保留拦截。
- `LocalSemantic` 与离线 Probe 仅在 scope 验证通过后透传实际版本；Probe 允许新事实上下文状态。生产模型身份白名单、授权、缓存、容量、deadline 未放宽。

没有改动 Api、Db、网页、交通、训练器或旧评估 helper。硬条件处理由另一独立子任务负责；本文不冒称那些修改属于本文件作者。

## 已核对的真实历史材料（匿名）

仅使用旧已提取 JSON，不重新打开 PPTX、不写数据库、不重新上传。

- F04 的介绍中老年对象及金融财产安全相关完整行，已成为可独立验证的 fact 范围；整篇状态仍 incomplete。
- F11 的指定完整课程题名已恢复。进一步检查发现，既有段落中题名前已有“擅长领域”栏目，只是此前未被识别；因此优先依据该真实栏目恢复，不猜测 PPTX 的视觉坐标或把整个简介放行。
- F04 的亲子财商“授课”事件标题可以进入历史角色识别。
- F12 的同段认证与已开展课堂可以保留为不同事实，未自动把宽泛财商历史认定为特定亲子课堂，更没有替用户验证真实资格。
- 19 份既有 JSON 的 Python→Java scope/quote **无模型协议握手为 19/19**。这不是匹配准确率，也不是完整上传/API 重放。

## 初始回归结果

- Python：旧 worker 50；旧 section 17；新 fact 20，均通过。
- Java：新 facts/source/protocol 44；context 41；adversarial 30；scope 10；partition 30；LocalSemantic 281；Probe 18；另一子任务 Atomic 82，均通过。
- context 的 41 项包含 root 明确授权的一个受众要求预期修正及相应防注入正例；旧 holdout、真实 relevant 标签均未修改。
- 编译目录为新建 `/private/tmp/yanxu-evidence-facts.tvkG5I/out`；旧 out 与旧 pipeline 归档未修改。
- 编译只有原有 LocalSemanticTest 未检查类型转换 warning，无编译错误。

**上述测试通过不代表边界已完备：下面的新增独立审查发现了未被覆盖的安全漏洞。**

## 阻断项：跨行助教身份被 mask 丢失

独立构造的合成资料在同一介绍章节中依次声明：

1. 本人担任助教。
2. 某年开展服务礼仪课堂。

当前 factContextSafe 对他人、团队和指代风险有检查，但没有把前一行的助教主体状态绑定到后一行的无明确主讲主体事件。于是仅恢复第二行，mask 删除助教身份，历史角色函数把“日期 + 开展课堂”视为本人教学。

用现有 Probe 完整走 profile、scope 验证、RequirementCoverage、NearbySelection，固定 0.75 的**明确标记为合成 stub** 的评分、没有调用任何模型，得到 candidate 1 被准入：

- whole incomplete / scoped complete；
- 服务礼仪检查 source_supported；
- 证据角色 teaching_history_statement。

这是一项真实的新处理链漏洞，不能通过调分、训练或改标签消除。当前冻结源码只用于保留三模型同管线对照，**不可发布、不可宣称所有证据问题已解决**。

root 已要求先保持源码不动，等待正在进行的三模型历史重放及源/class 哈希核验结束，再修跨行角色绑定并创建新的冻结基线。下一修复需覆盖本人助教、助理、旁听与未亲自授课；明确另一个时间、独立本人主讲的事件可有限恢复，不应一律封锁整页。歧义角色仍待核。

## 本次待对照冻结指纹

| 文件 | SHA-256 |
| --- | --- |
| worker.py | `1321b338cd3a3d72cb251a93d725214f00e1554e2a2c2fdb4828a95324c07a80` |
| EvidenceSections.java | `a447693e9574915222ed99823a2ae06f055820a433e8c0b9928bade9bd4e2e94` |
| ProfessionalEvidence.java | `47da503bb5942e0fb9dabf91e556d888d70b9f212c897e1100dcb0fa3e105357` |
| LocalSemantic.java | `4a7e2f6e9b4be62bff00decf786dc017fb90df029f1f2eb8c7f3c6405d70c26b` |
| PosttrainingV2Probe.java | `e8d89fe964519cd312d7a8b62a10a68732412b4a042cf3f5b13e12b513df32ea` |
| test_evidence_facts.py | `ab68f143a1f2ba0f33747545f416de79e00ff277da5b55f945d2d393bd9ce3eb` |
| EvidenceFactsTest.java | `7af781a142e0fcd370614a6064cab3319192d01e19bcb91523ebd110c52f480f` |

安全审查之后尚未修改这些源码。新的边界修复需要另记指纹与结果，不覆盖本次冻结的历史对照输出。

## 第二次冻结：跨行角色绑定修复

上述第一次冻结基线的三固定模型历史重放全部结束，执行前后 source/helper/model/bundle/class 指纹一致后，root 才授权实施本节修复。没有中断或污染正在运行的模型比较。

### 角色边界

- 本人担任助教、课程/教学/授课助理、会务、学员，或本人旁听/参训/未亲自授课等限制，被保留为整个源上下文的角色约束，不再只检查即将恢复的那一行。
- 普通日期加“开展课堂”、换行或换一个正向栏目，不能清除已有的模糊角色。旧整篇路径中的 records 历史角色也接受同一源上下文检查，防止只修 scoped 路径。
- 不一律删除其他时间的真实主讲：只有明确年份、明确本人独立/亲自主讲，且限制角色的年份明确不同，或前述未知时间角色之后使用“另于/另行”明确开启新的个人事件，才可恢复。
- 限制角色与教学事件同年、缺少时间、角色限制在事件之后且无法归属、在同一行无法独立切分，仍待核。当前时间绑定精度只到明确年份，不能据此声称同一年两个项目已经区分。
- 全局本人否认、前后指代否认、他人/团队主讲等旧保护保持。资格不会因此被外部认证；带教别人不能代替本人主讲，明确本人主讲同时带教助理的陈述仍可保留。
- 新角色恢复仍用 v2 的范围协议，Java 从完整源文档重算；无新模型、无新训练、无标签变更。

### 最小漏洞复验及正例

现有 Probe 的无模型、明确命名为 test stub 的完整处理链再次核对：

- 前述助教身份 + 无明确个人主讲事件：`admitted_ids=[]`。
- 助教身份后明确另于 2025 年独立主讲的新事件：`admitted_ids=[1]`。

分值固定为合成协议测试的 0.75，未调用任何模型；这证明该具体边界不再误准入，并非真实业务准确率。

### 第二次冻结验证

- Python：worker 50 + section 17 + facts 20 + 新 role 14，共 101 个测试方法通过。
- Java：新 role 49 + facts 44 + context 41 + adversarial 30 + scope 10 + partition 30 + LocalSemantic 281 + Probe 18 + Atomic 82，共 585 项检查通过。
- 合计 686 项纯测试/检查；角色测试含 23 个成对检查整篇与 scoped 路径的案例，包含真正满足条件的主讲正例，拒绝全部不能通过。
- 新建编译目录：`/private/tmp/yanxu-evidence-roles.BTTmtu/out`；前一次 facts out 与全部历史模型报告未改。
- 同一批既有 19 份 JSON 的无模型 scope/quote 握手继续用于协议一致性核验，不把它写成上传、训练或匹配准确率。
- root 已安排同一组固定模型和冻结历史标签的新一轮重放。结果应单独保存，不替换第一次冻结的结果，更不得包装成后训练增益。

### 第二次冻结指纹

| 文件 | SHA-256 |
| --- | --- |
| worker.py | `5726e84ccab81f813a46271bf65545098c26cbd81e8d2fdbbd244f8873369e66` |
| EvidenceSections.java | `3c2d629683be72ed9506e3bf36eb3cda1bedc4b7fb4c14592e4d362d2e2d750a` |
| ProfessionalEvidence.java | `21d963a7fe4ade399c8232010b9bdf244a9957a23396d724443fa8c139437b1e` |
| LocalSemantic.java（未再改变） | `4a7e2f6e9b4be62bff00decf786dc017fb90df029f1f2eb8c7f3c6405d70c26b` |
| PosttrainingV2Probe.java（未再改变） | `e8d89fe964519cd312d7a8b62a10a68732412b4a042cf3f5b13e12b513df32ea` |
| test_evidence_role_context.py | `22e9eb81d0c4029f211a7eb1c462e97cc6e113058ec17b6e183eaaa2252111a0` |
| EvidenceRoleContextTest.java | `ff3e8f1937733c43d088301f914a107571a57557a045458b317a94ee304c112e` |

仍然存在边界：复杂跨页指代、非标准角色表述、同年不同事件、未明确本人归属或未绑定到指定课程的经历需人工核对。文档与合成测试不能证明所有语言组合均已覆盖。
