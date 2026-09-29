# M06 已核对授课 XLSX 下载合同（2026-09-23）

本轮增量已实现并安装：仅导出当前已核对授课统计。此前只读统计已接原经营洞察；本轮新下载的模块验证已完成，宿主原UI/最终运行版本验收由总控记录。

## 根可先接的接口

- `GET /api/management-reports/teaching-export`，仅 GET；未知路径继续返回 false。
- 参数：原 `start/end/date_basis/organizations` 加 `snapshot_version`；仅 TEACHING（省略按 TEACHING），PAYMENT 仍 409。重复解码后的参数名、未知参数、缺少/无效版本拒绝；不接受客户端明细、身份、权限或机构归属断言。
- `public static byte[] ManagementReportsIntegration.downloadTeaching(Auth.Session, Map<String,?>)`：重建服务器可信授课快照，逐机构复核 reports.read/VIEW 与 reports.export/EXPORT，CAS 比对 snapshot_version；同一业务锁内生成 XLSX，返回前再次复核会话、权限和当前投影。只读，无数据库写入。
- `handle` 调用根提供的 `Api.file(ex, bytes, contentType, filename)`；该方法只缓冲响应，根原锁外 flush 输出。
- MIME 固定 `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`。
- 文件名固定安全 ASCII：`reviewed-teaching-YYYYMMDD-YYYYMMDD.xlsx`，从已验证日期生成。
- `read` 顶层增加 `teaching_export_available`（布尔），仅由选中全部机构的真实 EXPORT 权限决定；已有 VIEW 前提仍保留。不受正式金额/币种缺失阻挡。该能力纳入投影版本，升级前页面需重新查询。
- 原 `permissions.export`、`availability.formal_export=false` 及 `/export` 409 的正式月报边界不改。页面按钮应命名“下载授课统计”，不要复用“正式结算月报”。

## 工作簿内容与隐私

新 `ManagementTeachingWorkbook.java`：说明、四类汇总、讲师排名、机构汇总、已核对明细、未计入原因，共六表。所有汇总/排序沿用同一可信快照，四类课时分别表达。

只白名单导出机构 organization_code 与必要系统 ID。讲师/项目/排课 ID 标明“系统记录ID（非正式编码）”，按文本保存；不造正式课程码/人员码。绝不包含姓名、项目标题、授课自由文本、备注、联系方式、核对人或证据原文。不直接输出来源 message，而由固定原因码映射固定文案。

课时保留精确十进制（15有效位内普通数值为数值，超过为文本），null 明确“未知”，汇总完整值、已知小计、缺失数量分别列出。空月份保留范围和说明，合法0课时保留0。日期未知单独标记“日期未知、无法归月”；不得把该数量当成本月未完成记录。

说明固定写明：当前授课事实统计，不是结算/正式编码月报；不含金额或支付；四类不相加；预计/计划同样仅取已完成已核对记录；未迁移旧项目不在覆盖范围。无公式、宏、外链或隐藏个人映射。所有字符串为显式文本单元格，不能触发公式。

## 验证与范围

新增 `scripts/M06TeachingExportTest.java` 和 `scripts/M06TeachingExport-check.sh`。运行 `bash scripts/M06TeachingExport-check.sh`：最终483项通过，独立临时合成H2、真实Auth/M01/M05/Api.handle，覆盖实际成功下载、二进制缓冲与锁外flush、撤权/显式DENY/CAS/跨机构/损坏及未核事实、精度/未知/零值、ZIP/XML隐私哨兵排除和公式安全、导出前后全表摘要一致。原 `bash scripts/M06Integration-check.sh` 95项回归通过。

仅修改 M06 指定文件，不改 Api/Db/Auth/M01/M05/web 主应用/样式/check.sh，不读实际 app/data/人员材料/生产，不创建任务或代理。


## 聚合精度修复与独立读取

单条M05数量允许24位有效数字、8位小数，当前最多10000条来源。聚合可能保留28位整数加8位小数，工作簿数量校验允许36位，不沿用单条精度限制。大于Excel 15有效位的值始终为精确文本，不经double。

真实M05回归包含三条已保存并核对记录：`999999999999999999999999 + 999999999999999999999999 + 0.00000001`，合计为 `1999999999999999999999998.00000001`（33有效位）。总量、讲师排名和机构汇总均原样保留，明细原24位/8小数不变，下载前后全库摘要一致。该用例修复了read成功但工作簿原32位限制拒绝的问题。

独立openpyxl读取实际Java生成的有记录/空月份工作簿：六表、前导零机构编码、24位文本数量、系统ID文本、未知/零、冻结表头以及无公式/超链接均核验。Artifact Tool逐表渲染核对布局；其渲染器对数字形文本显示科学计数/去前导零，但inspect值、实际XML和openpyxl值均正确，因此不将该渲染当精度证据，也没有改写真实XLSX来迁就预览器。未做Excel/LibreOffice原生应用显示验收。

## 交付文件

- `app/src/com/training/ManagementReportsIntegration.java`：新下载/严格参数/读能力相关分支；原可信读取逻辑不变。
- `app/src/com/training/ManagementTeachingWorkbook.java`：新增六表白名单工作簿，无第三方运行依赖。
- `app/scripts/M06TeachingExportTest.java`、`app/scripts/M06TeachingExport-check.sh`：新增可重复的隔离验证。
- 本文、原INTEGRATION与INTEGRATION_TEACHING的最新状态提示、coordination/modules/M06/STATUS.md。

代码已交出，无并写。Api.file与原经营洞察下载按钮由总控实现。真实费用/支付/正式编码结算月报继续不开放；当前10000条历史来源上限沿用，不截断生成部分报表。
