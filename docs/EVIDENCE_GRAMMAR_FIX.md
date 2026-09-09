# 明确授课事实与有限否定作用域修复

2026-09-08。本轮为规则/证据处理修复，不是后训练，不是盲测；未部署、未训练、未修改旧训练数据、旧基准标签、历史报告或已封存输出。

## 本轮边界

仅修改 `semantic/worker.py`、`EvidenceSections.java`、`ProfessionalEvidence.java`，新增 `semantic/test_evidence_grammar.py` 与 `scripts/EvidenceGrammarTest.java`。Requirements 两类、API、DB、前端、模型身份白名单、认证、缓存、容量和超时逻辑均未由本轮修改。

### 已修复

- 对明确主语、完成动作和受众做有界语法识别：本人已为/给/向某对象主讲、该讲师实际主讲、同一讲师完成一场明确课程授课等，不再仅因插入了受众或第三人称而丢失教学历史。
- 受众不是授课主体：“维修团队”可作为本人教学的受众；邀请、安排、让他人授课、编写教材、报名/准备/认证不属于完成教学。数值是否满足需求仍交给条件核验，不能从该语法标志推导。
- “同一讲师”不当作一个两字姓名。但若完整来源中存在明确其他教师，该指代仍未知；不能越过章节把它改归简历本人。
- 同一个完整原文单元已有明确本人完成教学时，有限识别完整的“其他讲师没有/未承担该课授课”子句。它不否定本人的肯定记录，但也不会单独产生本人授课证据。
- 有限识别完整课程/课堂/练习操作限制：无需/不需要/不要求/不安排学员写代码、编程、配置系统、安装软件、连接生产系统、操作真实设备、带电操作等具体受控动作。其含义是课堂操作限制，不是未授课。
- 上述有限否定片段仅在分类视图中被遮蔽；不会成为正面主题或能力证明。worker 返回与 Java record 保留其余原文的连续区间，原文完整段落上下文和 offset 仍保留。独立事实路径也不能重新插入已排除的否定操作子句。

### 保留的保护

- 本人未讲、没有承担、尚未授课、助教/助理/旁听、他人肯定授课、未知指代、双重否定仍待核。
- 跨行/跨标题的本人角色限制不被简单断句抹掉；已存在的“不同年份明确独立主讲”有限恢复规则不变。
- 授课报名、准备材料、安排老师不能因为出现“已完成”而成为授课历史。
- 未支持的课程操作否定仍待核，不能把任意“不……”都忽略。
- 课程操作限制不会成为“会讲该操作”的证据。证据文本的正面主题核验不能借 `context_text` 中的被否定内容。
- 相似度仍是检索信号；self-reported 授课仍 `verified=false`，不是外部资质认证。

## 验证

先固定跨领域的正反例，再运行旧实现复现失败，之后修改实现。新增题覆盖园艺、档案、文物、仓储、包装等不同主题，没有复写历史题目/标签。

最终：

- Python：worker 50 + section 17 + facts 20 + role 14 + grammar 12，共 **113 个 test methods 通过**（参数化子例另计，不当作独立业务样本）。
- Java：Grammar 59、Context 41、Adversarial 30、Scope 10、Partition 30、Facts 44、Role 49、Atomic 82、Audience 27、LocalSemantic 281、Probe protocol 18，共 **671 个 checks 通过**。
- 额外 6 个固定向量跨语言协议/准入用例：3 个明确肯定、3 个本人否认/助教/其他教师指代负例，**6/6**；经过现有 prepare/profile → worker metadata → PosttrainingV2Probe 校验 → RequirementCoverage → NearbySelection。使用明确的 `offline/synthetic-grammar-fixture` 身份和固定向量，不加载模型，不将其计入模型准确率。
- 编译输出在新 `/private/tmp/yanxu-evidence-grammar.OB4IE5/out`，不覆盖任何旧实验 out。仅存在旧测试代码的 4 个 unchecked-cast warning，无编译错误。

## 剩余边界

- 查询 `requirement_units` 未擅自放宽。普通对象/教学任务排除需与 Java 条件结构化共同验证，尚未支持的 query guard 不应被宣称已解决。
- 不识别的自由课程主题、复杂事件数值/年度集合、虚拟教室与实体教室等属于后续独立条件解析任务，本轮没有用语义分直接放行。
- 本轮语法覆盖仍有限。没有声称所有语法、真实简历准确率或全部 96 个合成正例已解决；后续应同权重、同标签、同执行契约重放，分开归因规则收益与模型收益。
- 仅有有限否定的介绍行可以保留完整段落检索；需要恢复不完整文档中无安全章节的局部片段时，不会为了评分强行标记整个来源完整。
- 本輪不读取真实简历、不更改真实档案；完整模型回归由根任务在各部分统一冻结后进行。

## 冻结哈希

```
f17519df4ce6695e3b8d82d529ba50a886a3238352656666a1099c6f2e828a57  semantic/worker.py
8d10448a5c955c748cba3b0e01b0181c164d8942d1f80a754ec614bba9fdf19f  src/com/training/EvidenceSections.java
6ed865d4417cd171a8e6f9ef6c5c3f51abbf47ab62f30e93b030b69f554e7d53  src/com/training/ProfessionalEvidence.java
92b8ad081f22a74737779695df80966e2fd3fd2398e23deb5b04e1f607259c65  semantic/test_evidence_grammar.py
79e3aa665cd39a878cbb9dc4bdf6a1da127c0eb45d795f9a1778c15163bbfb22  scripts/EvidenceGrammarTest.java
```
