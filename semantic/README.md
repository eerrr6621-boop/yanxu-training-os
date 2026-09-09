# 本地语义辅助与调度核对

这是可选组件，**默认关闭**。应用仍可仅用 Java 运行；启用语义辅助才需要额外的 Python CPU 进程。它不是聊天模型，不生成简历资历、车次、机票、票价或通勤耗时。

**当前状态（2026-09-08）：**19份原件已经独立完成生产上传验收，但本目录的实验语义、证据和交通改进尚未上线。[V3后训练](../docs/POSTTRAINING_V3.md)及[V4续训与独立验收](../docs/POSTTRAINING_V4.md)已实际完成，V4没有最终推荐收益且安全门槛未通过。正在另一个处理链版本修复需求字段、课程语法与个人授课事实归属，不改旧模型和失败记录。详细事实与未完成事项见[持续检查点](../docs/GOAL_CHECKPOINT_20260907.md)。不得沿用早期测试的全绿结论宣称当前准确率达标。

**早期上下文修复（历史结果）：**[证据上下文修复](../docs/EVIDENCE_CONTEXT_FIX.md)当时在72条复用合成题完整链路上通过，另一组32题纯检索首选仅16/32；该阶段没有训练或部署。这些不是当前新规则验收成绩。全国交通资料仍未齐，使用范围未确认的研究底稿已禁止业务导入并隔离。

**此前实验（历史结果）：**已完成一次[离线后训练小试](../docs/POSTTRAINING_PILOT.md)，只使用新建合成资料，不使用真实简历或既有二十轮题目训练。INT8 模型大小不变；当时新合成封存集的原段落首选由 11/32 提升至 25/32，旧 worker 预处理后由 9/32 提升至 13/32，并存在训练资料长度偏差。实验版本不满足上线条件，本轮没有加载或发布该权重。

**此前评测：**[二十轮验证](../docs/TWENTY_ROUND_EVALUATION.md)已完成。交通规则测试通过，专业匹配仍有同义准入漏荐和额外条件漏识别；真实 19 份简历的 8 条历史需求回归，按冻结严格标签为 12/15 正式人选符合。该轮没有训练，不能宣称业务精准。训练前判断见[后训练方案](../docs/SEMANTIC_POSTTRAINING_PLAN.md)。

**当前代码为 v7，枚举371城；同城和全部铁路小于4小时候选统一比较，航空小于3小时作为经核实的后备。模型单独计分、同分按人工等级。全量时刻源未取得，城市清单齐全不代表时刻核对完整。** 未发布生产或同步GitHub；没有新的真实简历盲测。见[v7当前记录](../docs/TRANSPORT_COVERAGE_V7.md)；[v6记录](../docs/RECOMMENDATION_V6.md)、[v5真实简历验证](../docs/NEARBY_GRAY_VALIDATION.md)和[v4诊断](../docs/SEMANTIC_CHALLENGE_REVIEW.md)保留为历史基线。

## 本轮实际能力

1. 先执行在库状态、硬性预算和排期筛选，不为凑足三位推荐而放宽条件。
2. 对所有通过硬筛选的候选，以当前有效专业文字做语义比较，不再用旧的专业标签门槛提前截断召回；人工画像优先，不并入已删除的旧简历内容。已识别的课程模块必须逐项有文字依据，不能用高综合相似度补齐缺项。
3. 内容符合且在同城或全部铁路小于4小时范围内的候选统一比较；取消2/3小时人数截断。铁路不足才补经完整核对的直飞小于3小时航空候选，各池按整数模型分与等级选取，第三名同分全留。见[师资指南](../docs/FACULTY_GUIDE.md)。既有实绩、资质和预算不被模型更改。
4. 展示最多两段连续原文，管理员可对照核验。明显否定、待核实及明确个人属性字段所在的整段不作为语义证据。规则不能穷尽自然语言的所有否定或偏差，不能替代人工审核。
5. 服务未配置、繁忙、超限、超时或返回无效证据时，整批显示模型未评分，不混用半批模型分；仅以文字依据提供参考顺序。语义相关但内容证据不足者列待核对，不用相似度补齐缺项。
6. 可选填写授课开始、结束时间。同日重叠或既有时段不明的安排仍排除；未填写起止时间时保留整日冲突检查。前后一天的课程用于核对衔接，不视为实时位置或可达证明。
7. 不以实时余票为前提；城市参考库只接收有明确使用范围及双向证据的数据，当前默认空库。未录、授权不明或待复核路线不参与跨城推荐，不以0、直线距离或假班次占位。部分候选模型未评分时独立列出，不与模型分混排。未接通实时车次、航班、高德或订票。

### 管理员交通参考

当前新增独立的长期城市参考层，默认空库；只有使用范围明确且完成双向审核的记录才可进入业务推荐，见[城市参考结构](../docs/CITY_TRAVEL_REFERENCE_SCHEMA.md)。代码兼容 `config/rail-timetable.json` 和 `YANXU_RAIL_TIMETABLE_FILE`，但本地历史研究文件已明确禁止业务导入并排除公开同步，不能随代码部署。旧样本与实验仅见[历史铁路参考记录](../docs/RAIL_TIMETABLE.md)，不是当前可用覆盖。站到站时长不含接驳和候乘，不能命名为门到门时间；以下旧私有接入是兼容结构，不代表已有可用或已授权数据。

`YANXU_ROUTE_REFERENCES_FILE` 指向非公开目录中的 JSON 数组（最多 1 MiB），不接受浏览器提交的路线作为可信数据。每条记录包含 `from_province`、`from_city`、`to_province`、`to_city`、`kind: reviewed_reference`、可追溯 HTTPS 来源 `source`、`checked_on`、`valid_from`、`valid_until`、双向 `outbound_door_to_door_upper_minutes` / `return_door_to_door_upper_minutes` 及 `includes_transfers_and_local_connections: true`。核对至有效期末不得超过 90 天，实际出行日期须在有效期内；上午/全天还检查提前一天的出发日期。历史估算不能直接标为核对有效。

旧私有门到门分支另要求 `transport_mode: high_speed_rail`、`rail_priority_checked: true`、`from_station`、`to_station`、`outbound_rail_minutes` / `return_rail_minutes` 与 `outbound_connection_minutes` / `return_connection_minutes`。双向总时间须不小于对应高铁运行时间加接驳候乘时间；缺少真实依据不能补 0 或设为已核对。即便走此兼容分支，城市初选也仅按独立的铁路运行分钟数分层。默认私有文件 `config/dispatch-route-references.local.json` 已被 Git 忽略；不要把公共时刻样本复制进去伪装为门到门核验。机构参考文件独立且私有，机构城市不覆盖实际授课城市。

`YANXU_NEARBY_MAX_MINUTES` 默认240，铁路两向都必须严格小于上限；可降低比较范围，设0则连航空一起暂停跨城自动推荐。更低的管理员上限不是“高铁超过4小时”的证明，不据此转航空。私有全量数据可按目的城市分片，见[v7接入字段](../docs/TRANSPORT_COVERAGE_V7.md)。所有参考均保持 `transport_verified=false`，不证明授课日可用或实际到場时间。

## 模型与费用边界

- 模型：[BAAI/bge-small-zh-v1.5](https://huggingface.co/BAAI/bge-small-zh-v1.5)，固定 revision `7999e1d3359715c523056ef9478215996d62a620`，模型卡标注 MIT。
- 512 维，最长 512 token；按官方中文检索前缀处理需求，CLS 池化并 L2 归一化。
- 本仓库脚本从固定的 safetensors 导出 ONNX，再量化 MatMul/Gemm 为 INT8。实测量化模型 57,239,135 字节（约 57.2 MB / 54.6 MiB），不是整个进程的内存占用。
- 本地推理不依赖限时 API 免费额度，不产生外部模型调用费；服务器计算、磁盘、运维和日后交通供应商费用另计。
- 下载、构建只在准备阶段联网。运行时只读本地模型、tokenizer 和缓存，不下载依赖、不上传需求或简历。

## 一次性准备（开发机）

需要 Python 3.11 / 3.12，网络可访问官方 Hugging Face 模型源和 Python 包源。使用独立虚拟环境，模型不进入 Git 或 `web/`。导出还需要 PyTorch，但正式运行无需安装它。

```bash
python3 -m venv semantic/.venv-build
semantic/.venv-build/bin/python -m pip install -r semantic/requirements-build.txt
semantic/.venv-build/bin/python semantic/prepare_model.py semantic/model
semantic/.venv-build/bin/python semantic/evaluate.py --model-dir semantic/model --precision fp32
semantic/.venv-build/bin/python semantic/evaluate.py --model-dir semantic/model --precision int8
semantic/.venv-build/bin/python semantic/test_live.py --model-dir semantic/model
```

`prepare_model.py` 要求新的输出目录，保留原模型卡、源文件散列、FP32 基线与 INT8；不会覆盖已有模型。更换模型、tokenizer 或预处理规则后须重新评估。正式运行只需 `model-int8.onnx`、`tokenizer.json`、`manifest.json`，并保留模型来源、许可说明及可追溯的原稿归档。

## 启用（由管理员操作）

1. 把模型放在非公开目录；新建仅安装 `semantic/requirements.txt` 的运行环境。
2. 在服务管理器的私有环境配置中生成并保存随机 `YANXU_SEMANTIC_TOKEN`（至少 32 字符），Java 与 worker 使用同一个值。不要放入命令参数、聊天、公开目录、Git 或日志。
3. 为 Java 和 worker 配置同一个 `YANXU_SEMANTIC_PORT`，例如 `18091`。仅监听 `127.0.0.1`，**不开放公网端口、不配置 nginx 代理**。Java 使用固定 loopback，忽略系统代理，不接受任意上游 URL。
4. 以非 root 服务用户启动 worker；缓存目录仅该用户可读写：

```bash
# 环境变量由私有服务配置注入。路径按实际部署位置设置。
/path/to/runtime/bin/python /path/to/semantic/worker.py \
  --model-dir /path/to/private-model \
  --cache /path/to/private-cache/vectors.sqlite \
  --port 18091
```

5. 重启配置了上述环境变量的 Java 应用后，以隔离数据核对 `analysis.semantic_matching.status=ready`。只看到界面入口不表示模型已启用。
6. 小服务器建议独立服务设置 CPU、内存上限并关闭外网访问；先验证目标机器的依赖架构、峰值内存、并发和长简历，不直接套用开发机性能。建议从单并发和 384–512 MiB 服务内存上限试验；这是预算建议，不是已验证的生产配置。

关闭时移除 Java 的端口配置并重启应用，或停止 worker；推荐显示模型未评分，提供文字依据参考顺序。语义组件不写回老师或排课。v6 另增的可空人工讲师等级字段与模型独立，不应因关闭模型而删除。

## 隐私、容量与故障边界

- 原应用先脱敏常见手机号、邮箱、证件号；只发送候选编号和有效专业文字。标准化后返回的证据必须是本次有效文本的连续子串，整批校验后才加入响应。
- SQLite 仅持久保存随机盐、HMAC 文本摘要、向量和时间，不保存明文；向量仍属于内部派生资料，**不是匿名数据**，应限制访问、不得公开备份。
- 缓存按模型权重摘要、文字、查询/文档角色区分，最多 5,000 条；7 天后不再复用，过期行在后续成功写入向量时清理，空闲服务的磁盘保留时间可能更长。修改文字会立即产生不同键；删除简历后旧向量不会被该简历再次引用，但不会立即从缓存磁盘清除。需要立即清除派生数据时，停止 worker 后由管理员移除其专用缓存，再启动。
- 单请求最多 512 KiB、200 位候选、512 段证据和 8 段需求；超限整批降级，不静默截掉后面的老师。
- 单并发推理；Python 在每次编码前后检查 7 秒预算，Java 连接超时 0.5 秒、读取超时 8.5 秒。原生单次推理无法被 Python 的轮询硬中断，服务级资源限制仍然必要。模型阻塞不得占用业务数据库全局锁。
- 状态和短证据沿用已有师资推荐权限，只对已登录管理员及业务管理员开放。原始简历、模型、缓存与本机密钥不得进入公共资料下载目录。
- 12306 只提供普通官网链接，不自动传递老师资料、不订票、不尝试未公开接口或绕过验证码。

## 质量门禁

`bash scripts/check.sh` 包含无模型的契约、排序、否定分段、时间边界及前端输出检查，不依赖模型下载。运行完整回归需要 Java 17、Node 18+、Python 3。

`semantic/test_live.py` 用构造文字、一次性本机 token 和临时缓存验证真实模型；可加 `--java-classpath /path/to/test-out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar --java /path/to/java` 检查 Java 调用。测试只绑定回环地址，不使用生产库。

在仓库根目录运行时再加 `--faculty-suite`，并在 classpath 中包含 `lib/ip2region-3.3.7.jar`，可启动一次性业务库，把整套师资 API 回归跑在真实量化模型上；退出会停止测试服务、清理其临时缓存及测试库。量化对照原始结果见 [INT8](reports/smoke-int8.json) / [FP32](reports/smoke-fp32.json)，平台为开发机 Darwin arm64，不是生产 Linux。

把 `--faculty-suite` 换成 `--rail-suite` 可在另一份全新库中，使用真实公共铁路表和标有“【灰度测试】”的构造讲师验证铁路分圈、量化模型、等级与同分保留。样本过期后该 API 测试检查不再冒充可用；固定核对日的数据行为由 `RailTimetableTest` 覆盖。

`evaluation_cases.json` 的 12 组构造案例用于验证导出和量化流程，**不能代表业务准确率**。本轮开发机 FP32、INT8 均选中 12 组预设首选，但存在接近的分数，应保留硬规则。上线前需用获授权、脱敏且由业务人员标注的真实样本独立验收，覆盖同义表达、长简历、跨行业、缺失经历、否定条件和模糊时间；不能以这 12 组调参后宣称准确率达标。
