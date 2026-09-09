# 城市规划参考多库集合

本组件只把多个**已制备、各有完整性锚点的规划库**接到同一请求的 `CityPlanningReference.Index`。它不合并或改名子库内的 documents / reference IDs，不重新核读来源，不更新来源日期，不改变事实、精度、时效或选档规则。只用于公开城市旅时的规划初筛，不升级为实时高铁核验、航空依据、时刻表或交通分数。使用公开城市摘要不以用户提供时刻表/API 为前提。

## 文件合同

集合版本为 `city-planning-collection-v1`，旁边必须有 `<file>.integrity.json`，版本为 `city-planning-collection-integrity-v1`。

- 顶层只含 `version / collection_id / collection_revision / purpose / policy / revisions`；purpose 为 `prebid_city_planning_only`，各子库 purpose、policy 必须完全相同。
- 每次集合修订保存完整成员快照、`prepared_on`、policy 摘要、前事件摘要和本事件摘要。`prepared_on` 仅是制备日期，不是来源复核日期；历史快照不能改写，成员不能删除，policy 不能静默切换。
- 每个成员保存稳定 `member_id`、规范化绝对 `file`、原文件 SHA-256、规范化 `anchor_file`、原锚文件 SHA-256、原库完整 `expected_anchor`。不复制父库旧统计、原文或伪造新核读记录。
- 子库路径须为普通文件；拒绝文件本身是符号链接。父目录解析为真实路径。加载时实际解析路径、两份原字节摘要、原库锚点必须一致；同一集合不允许重复路径。
- 子库更新须提供全新不可变文件，版本不低于原版本；同版本锚点必须完全相同。最新子库逐一验证集合全部历史快照中的原子库锚点，保留历史 reference 链和撤销，不能用另一个库冒充原成员。
- 限制为 64 个成员、256 次集合修订、集合文件 4 MiB、锚点 1 MiB；子库沿用 16 MiB 上限。

摘要/锚点是完整性绑定，**不是签名、授权或来源真实性认证**。原有来源和双读验证仍由每个子库原逻辑执行。

## 离线制备与配置

先编译全部业务类及 `scripts/PrepareCityPlanningCollection.java`。命令中的日期是本次制备基准日期，输出目录必须已经存在：

```sh
java -cp "$CLASSPATH" com.training.PrepareCityPlanningCollection \
  /absolute/new-collection.json collection-name 2026-09-08 \
  batch_one=/absolute/prepared-one.json \
  batch_two=/absolute/prepared-two.json
```

下一版必须显式给 `--previous /absolute/previous-collection.json`，列出保留的全部成员及新增成员。旧集合、子库、锚点不改写；目标或目标锚点只要已存在便拒绝，包括悬空符号链接。使用 CREATE_NEW；若写入过程出现 I/O 失败，可能留下未完成的新文件，应人工核查，工具不删除或覆盖它们。

配置二选一：

- 新集合：系统属性 `dispatch.city.planning.collection.file`，或环境变量 `YANXU_CITY_PLANNING_COLLECTION_FILE`。
- 原单库：保留 `dispatch.city.planning.references.file` / `YANXU_CITY_PLANNING_REFERENCE_FILE`。

系统属性沿用优先于环境变量的规则。两个有效配置同时非空则返回 `planning_configuration_conflict`，不读任一库，不猜测优先级。本工具不会设置生产配置。既有 `index(Path, LocalDate)` 单库 API、lookup、选择 API 不变；显式集合入口为 package-private `collectionIndex(Path, LocalDate)`。

CLI 成功表示结构及成员完整加载；`indexed_directions` 包括待核方向，不能当作“全部可用方向数”。

## 请求内聚合与失败

每次请求读取集合/锚点和每个子库/锚点，完整校验所有父库。原始 documents 缓存按各库对象隔离，同键不同内容不会冲突或串证据。然后生成只读的方向结果快照；同请求多次 lookup 不再读文件，下次请求重新读取。

- 唯一城市对调用原库原 lookup，附加 `collection_member_id / collection_id / collection_revision`，保留原 reference ID。
- 跨库相同城市对，无论分钟是否相同，都双向待核 `collection_duplicate_city_pair`，不选较短值、不静默覆盖。
- 重复城市对中有 revoked / superseded，则双向待核 `collection_cross_library_revocation_conflict`；单库本身的撤销仍由原规则返回 `reference_revoked`。
- 重复冲突只影响相关城市对，其余不冲突城市对仍可查询；但任一成员文件、锚点或原库验证失败会使**整个集合待核**，不会丢掉失败成员后返回不完整“成功”。
- 来源过期、生效日期或严格 `<4h` 等仍走原规则。重新制备集合不会刷新其有效期；规划参考不触发 strict / 航空准入。

## 回退边界

当前进程按稳定 collection ID **及规范化配置路径**记住已成功验证的最新锚点，拒绝降版、同版变更、同路径换身份及历史改写。显式 `--previous` 制备也验证连续性。

这不是持久化高水位服务：进程重启后，若有人同时替换整个集合及其锚点为过去自洽版本，单靠本地哈希不能发现；全新 ID + 全新配置路径也是新的显式管理选择。需要跨重启防回滚时，应由部署方额外保存并验证受信任的最新集合锚点，本次不实现签名服务，也不宣称已解决该边界。子库原进程内锚点保护保持。

## 本次验证

仅用自包含合成数据，没有读取或制备真实 13 对库，没有网络、模型、数据库或生产写入，未改 `check.sh`。

新 `CityPlanningCollectionTest`：269 断言通过；合成 9+3+1 库共 13 对 / 26 方向，含跨库重复 document / reference IDs、方向与原 ID 保留、配置冲突、坏成员全集合失败、重复对与撤销、过期不续期、修订/删除/改写/降版、路径和已有目标保护、实际 CLI、源文件原字节不变。请求建立仅 8 次文件读取/解析、3 次完整子库验证、65 个原文档解析、26 次方向事实验证；100 次后续 lookup 零额外 I/O。

同次独立编译还运行旧 Merge 67、CuratedCityDuration 393、CuratedPlanningIntegration 350、CityPlanningSelection 77 断言，合计 1,156 断言通过。这是定向回归，不是新一轮全量 `check.sh` 或真实 13 对验收。

首轮失败原样保留：测试想验证历史改写，却沿用子库 revision 2，正确先触发 `collection_same_child_version_changed`，与测试期待 `reference_history_rewritten` 不同。后续仅将该改写 fixture 的外层 revision 提至 3，使它到达旧历史链拒绝；没有放宽业务保护。首轮及二轮快照/失败/成功日志分别保留在私有 `yanxu-planning-collection-tests.TiDP09Iv` 和 `yanxu-planning-collection-tests-v2.mWwxR9op`，原失败字节未改。

独立运行测试（`CLASSPATH` 指向编译后的当前业务类、两个新 scripts 类和现有依赖）：

```sh
java -cp "$CLASSPATH" com.training.CityPlanningCollectionTest
```

测试默认创建全新系统临时目录；可传入一个尚不存在的新输出目录。所有带来源读取结构的 fixture 均明确标为 synthetic，不表示真实来源已核读。

### 根任务后续真实资料验收

2026-09-08 根任务另行制备了原9对、乌海3对、南充遂宁1对组成的真实集合，共13对/26方向，642条比较断言通过。除集合身份字段外，各向输出与原子库逐字段相同，原字节/锚点、日期、精度、较小筛选门槛与过期待核行为均保留。它是既有32对（另19对在严格参考层）的一部分，不是新增13对或全国完成。

随后将合并及集合的自包含测试接入 `scripts/check.sh`，在独立临时数据库和随机本机端口运行完整回归，退出0。真实资料验证与完整回归记录保存在私有 `yanxu-city-reference-collection.G5spEDv9`，未设置生产配置。实际集合使用本机绝对路径；部署时需明确服务器路径并另行制备，不能把本机路径直接作为生产配置。

同日根任务在独立目录`yanxu-city-collection-fifteen.fijjcALn`通过`--previous`追加集合第2版：原13对全部保留，另加北京通州—唐山及包头—银川，共15对/30方向。实际871条原字段、历史、门槛、过期及原文件完整性对照通过；此版本仍未修改生产配置。新增资料分别保留城市副中心端点范围和名义半小时精度，不升级为实测行程或触发航空。
