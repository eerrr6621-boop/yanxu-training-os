# 批量账号导入：现有后台接入

2026-09-23。后台已经接入真实Main → Db.init/Api；总控已将入口接回原“用户与权限”，完成真实桌面及手机验证。本机预览现已配置经独立摘要校验的私有名单来源，仅核验184候选预览，未执行实际人员导入、账号激活或权限发布。线上未操作。

## 运行配置

可信服务器启动时同时提供两个Java系统属性：

- `account.import.manifest`：管理员已核定的固定来源清单绝对路径。
- `account.import.manifest.sha256`：该清单原字节的SHA-256，64位小写十六进制，由独立受控配置固定。

示意（路径和摘要均须替换为管理员核定值，不是可直接启动的真实配置）：

```sh
java '-Daccount.import.manifest=/absolute/private/manifest.json' \
  '-Daccount.import.manifest.sha256=<reviewed-64-lowercase-hex>' \
  -cp '<existing-classpath>' com.training.Main '<existing-port>'
```

继续沿用现有data.dir、bind.address、bootstrap等启动参数，本模块不配置它们。不要根据当前文件自动计算摘要并在同一次启动默许采用；摘要是明确核定的信任锚。两参数在Db.init时只捕获一次，进程内修改属性不热切换批次，换配置需由总控重启部署流程处理。模块交付时未添加；总控完成宿主验收后已为本机63882预览显式添加核定的两参数，当前状态以coordination/TASKS.json及私有preview.json为准。

本任务已核定清单仍为私有`outputs/M01_服务器导入固定来源_20260923.json`（600），没有复制到app、web或Git。清单顶层严格只允许schema、batchKey、usage、pins、originals五字段：schema必须为`M01-SERVER-PIN-MANIFEST-v1`；pins必须恰含candidates、candidatePolicy、roleSource、authorization、audit、regionReference、scopeDecision、preparedPreview八项；originals必须三项，每项仅path与sha256。usage为纯说明文字，不执行其中内容。要求标准绝对路径、固定小写摘要、无重复路径；拒绝未知字段、重复JSON键、非法UTF-8、符号链接清单、非普通文件及超过64KiB的清单。

每次导入接口请求重验清单摘要；首次可用请求再完整核验八JSON/三原件证据链，然后建立同一进程长期importer。后续预览和提交由既有核心重新核验所有原件及来源；账号ID查询重验清单和真实账号。清单核验失败清空长期实例，其恢复后旧核对标识不能复活，需重新预览。来源路径、摘要、政策不接受查询参数或POST覆盖。

缺任一配置、清单失配或损坏、首次材料核验失败，管理员得到503及通用“账号导入未启用或服务器来源配置无法核实”提示，不影响原系统启动/用户管理。已启用后预览/提交检测到原件变化仍沿用核心409，要求重新核对。错误不输出具体来源、路径、摘要、解析原文或底层异常，成功和失败导入响应均no-store。

## 最小宿主改动

`Db.init()`在`OrganizationAccessStore.init()`之后调用一次`OrganizationAccountImportHost.init()`。该方法只建立三张空导入表、版本计数行，捕获启动参数；不打开清单/真实原件，不生成任何候选用户。沿用原Db用户引导逻辑，本模块没有改bootstrap或示例数据开关。

`Api.handle()`仅对本功能前缀在读取POST前复核真实管理员，使未登录/已失效返回401、非管理员返回403，优先于配置/方法/请求体错误。随后仍沿用原Content-Type和1MiB读取限制，读取在共享锁外；认证后route调用唯一Host.handle，业务在同一MUTATION_LOCK内，最终flush仍在锁外。普通API处理顺序不改。

真实路由保持不变：GET `/api/organization/account-import/preview`，GET `/api/organization/account-import/accounts/{id}`，POST `/api/organization/account-import/commit`。精确输入、幂等、CAS、账号proof和UI合同见ACCOUNT_IMPORT_INTEGRATION.md。

`Api.delete()`在原users分支、同一共享锁中先调用`OrganizationAccountImport.hasImportedAccount(id)`，任何已导入新账号或明确关联的旧账号均返回409，保留历史；该保护在导入未配置时也一直生效。原当前账号自删、最后管理员、活动组织绑定及通知引用保护保留。普通未关联用户删除、用户修改/停用/角色变更及其会话撤销不变。导入记录的外键保留作第二层约束。

## 验证与范围

运行：

```sh
bash scripts/M01-import-host-check.sh
```

默认使用PATH中的java；可用`M01_IMPORT_JAVA`指定现有JRE17。脚本默认app为自身项目根，仅阶段验证可用`M01_IMPORT_APP_ROOT`指定共享源码与lib位置。使用已有ECJ/H2等库，不下载依赖；完整编译当前Java与本轮文件。测试始终创建自己独占的临时目录、合成7人/2历史来源、独立H2，并以真实`com.training.Main`子进程启动127.0.0.1随机端口，不接受外部数据库路径或真实名单参数；每个子进程结束、数据库连接释放后才清理自有临时目录。

**112项通过**：严格清单解析、未配/摘要失配/损坏/错误pins不影响主程序、无配置启动空表、鉴权先于配置/Content-Type/大小错误、普通角色/注销/停用/降权会话拒绝、完整来源预览、明确ID核对、来源注入拒绝、清单变动及恢复不能复活核对、账号变动409、单事务提交与回执重放、重启恢复同一人员账号映射、旧进程核对标识拒绝、已导入账号409禁止删除、普通未关联账号正常删除、原更新/禁用可用。合成数据库约束在批量插入中途制造SQL失败，真实HTTP返回通用500且用户/人员/批次/版本全部回滚。检查Main日志及错误不含合成私有来源身份或来源路径。

首次测试发现的是测试进程的H2读取连接沿用了持久DB_CLOSE_DELAY，阻挡下一子进程重启；已让检查连接显式DB_CLOSE_DELAY=0，结束即释放，随后整套通过。生产Db连接策略没改。

未改Main、Auth、OrganizationAccess、Store、web/app.js、index、CSS或公共check.sh；未创建子代理、通知或定时任务。模块交付时未接前端；总控后续已完成原users局部组件及真实页面联调（205项，1280/390/320）。当前开发库仍仅1个本机管理员，没有执行184人的实际导入，没有部署。邮箱/激活、正式编码/业务权限发布和多人路由仍沿用原责任划分；显式兼任审批接口已有其他轮次交付，不能视作本批已发布权限。
