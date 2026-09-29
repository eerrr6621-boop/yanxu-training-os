# M06 正式冻结账、支付统计与编码月报（2026-09-23）

本轮新增 `ManagementSettlementAccounting`、`ManagementSettlementBridge`、`ManagementSettlementWorkbook`。不修改既有ManagementReports、授课XLSX、M05/Api/Db/Auth/web；原电脑端经营洞察接线由总控负责，无手机专项、真实人员材料或工作库写入、生产操作。

## 服务器接口

由根挂载 `GET /api/management-settlement-reports` 与 `GET /api/management-settlement-reports/export`，交给 `ManagementSettlementBridge.handle(ex,session)`。独立接口 `read(session,query)`、`download(session,query)`；无M06初始化、DDL、定价或持久化。输出XLSX通过现有`Api.file`缓冲，宿主锁外flush。

筛选只接受 start/end、date_basis=TEACHING|PAYMENT、organizations=逗号分隔精确机构编码、rank_metric=HOURS|FEE、hour_basis=ESTIMATED|PLANNED|ACTUAL|PAYABLE。未提供两个日期时默认上海时区本月；只缺一个、非法/倒置日期、重复/未知参数均400。默认TEACHING+HOURS+ACTUAL；PAYMENT默认FEE且明确指定HOURS返回400，不能给分期付款重复分摊课时。导出额外需要当前`snapshot_version`，缺失400、不匹配409。

每次真实Auth/M01 `reports.read VIEW`；下载额外所有选中机构`reports.export EXPORT`，旧admin无旁路。允许机构按reports.read精确资源生成。浏览器不能输入事实、金额、身份、权限或来源。

同一Api业务锁和独立Db只读事务里生成完整结果，拒绝嵌套Db.transaction。下载重新取真实来源+CAS→生成文件→再次获取来源、真实会话和权限复核；生成时间不参与版本，账号、授权范围、来源版本、所有输出含显示资料参与版本。

## 已冻结的M05依赖

1. `List<Long> DeliverySettlementIntegration.financialProjectIds(Auth.Session,String organizationCode)`：M05真实reports.read核权，同锁返回机构当前受理项目与冻结财务链持久机构下项目的并集，10000上限。每个项目继续完整验证；已有冻结账的受理关联丢失或改动不能静默漏掉。
2. `Map<String,Object> DeliverySettlementIntegration.financialSource(Auth.Session,long projectId,boolean export)`：schema_version=`M05-FINANCIAL-1`，project_id、organization_code、source_version、accrual_entries、payment_entries、chain_heads。M05内部持相同业务锁，不另开Db.transaction，允许M06外层快照事务。每个输出重新核验真实受理项目/机构和read/export权限。

M06不直读财务表，不读取旧fees/当前费率/老师等级计算历史金额。M05逐笔人民币两位HALF_UP后冻结，M06只接受CNY两位十进制金额字符串并精确加总，不再舍入。

每笔新增并要求`hours_before/hours_after`四类完整十进制或null状态，`hours`仍为有符号差额（任一端未知则差额为null）。原始分录before全0、after原完整值；同日调整before上一完整值、after新完整值；REVERSAL after全0；REBOOK before全0、after新完整值。先逐类别核验before与前笔after完全一致（含null），已知差额必须等于after-before，未知差额不能补0。

完整账先校验，再按日期筛选：唯一分录编码；单根CONFIRMED或LEGACY_OPENING；previous_entry_code线性无分叉；同日ADJUSTMENT有符号差额；跨日相邻REVERSAL原日负当前净额+REBOOK新日新全额，同correction_group；head最新分录/日期/净额与全链相等；支付净额及余额对账。head只作控制总额，不第二次计入。

课程或正式编码更正后，每条旧分录保留旧码；讲师ID和业务类别不转移。teacher_codes在排名中列出该期实际使用的全部冻结码，不回填旧分录。支付身份仅由accrual_entry_codes在同链冻结条目中解析，不查当前申报或用显示名推定；引用的teacher/course/activity须一致。

取消按同日差额调至0。已付更正只改变应计账；补付/退款按其实际支付日期归集，PAYMENT为正、REFUND为负。开发含SOLO_DEVELOPMENT/JOINT_DEVELOPMENT，按M05冻结业务日期；旧制只含经单独核对批准的LEGACY_OPENING及明确历史支付证据，未迁移旧数据不能冒充完整正式账。

## DTO

- `schema_version,start,end,date_basis,rank_metric,hour_basis,currency,hours_applicable`。
- `totals`：`amount,positive_amount,negative_amount,entry_count,hours`；hours的四个大写键各含`total,known_subtotal,missing_count,applicable`。金额/课时均十进制文本或null；未知不填0，四类不能相加。授课课时不逐笔加delta，而按每条账链每个业务日期的最后hours_after完整状态重建后求和；missing_count为仍未知的账链/日期状态数，已被后续补齐的历史null不继续污染合计，撤回确定值也不会留下旧已知小计。PAYMENT各课时total/known_subtotal/missing_count均null、applicable=false，空月份也如此。
- `activity_totals[]`：activity与相同totals字段；所有类别分别列示。
- `teachers[]`：teacher_id、teacher_code（本期唯一正式码才给出）、teacher_codes、rank、rank_value与同totals。默认实际课时降序，竞争并列1、2、2、4；未知不排名，同值用内部身份稳定排序。
- `organizations[]`：organization_code与同totals。
- `details[]`：entry_code、chain_code、previous_entry_code、kind、activity、date、organization_code、project_id、teacher_id、teacher_code、course_id、course_code、amount、四类hours差额、hours_before/hours_after完整状态、hours_counted（每账链/日期末条true，历史修订及支付false）、accrual_entry_codes。均来自冻结账，不带证据正文或源自由文本。
- `code_gaps[]`：entry_code、organization_code、missing_fields。当前选择任何条目缺正式teacher_code或course_code，仍能核对金额，export_available=false，下载409。0金额条目也必须有正式编码；不从ID/姓名/系统教师码造码。
- `source_versions[]`、`selected_organizations`、`available_organizations`、`permissions:{read,export}`、`can_export`、`export_available`、`access_version`、`snapshot_version`、`generated_at`、`source_coverage=APPROVED_FROZEN_LEDGER_ONLY`、`coverage_note`。
- UI专用显示：`teachers/details[].teacher_display_name`只按已授权选中事实内的teacher_id查teachers.name，不扩大教师范围；姓名缺失显示“讲师记录#id（待完善）”。`available_organization_options:[{organization_code,display_name}]`只含reports.read许可机构，并完整覆盖available_organizations；display_name直接取当前已发布M01 Organization.displayName()，旧三参数机构无名时明确“机构资料待完善”。organizations/details也附organization_display_name；不调用管理员预览，不按中文名称推算身份。显示资料只加入view，不进入不可变Accounting.Report或编码Workbook。

## XLSX

`ManagementSettlementWorkbook`只接受由纯核算私有构造的不可变Report及快照版本；返回map或源map被调用者修改均不改变Report。文件五表：月度概览、讲师排名、机构汇总、对账明细、说明；白名单仅机构/正式讲师/正式课程编码、不可变财务引用、日期、业务类别、课时和金额。对账明细保留四类课时增减，新增调整后四类完整课时与“本日期净课时/历史修订/支付不归集”标记。未知差额仍标未知，汇总取标记为本日期净课时的调整后值。无姓名、系统人员ID、标题、联系方式、证据正文、公式、宏、外链。金额与长课时超过Excel15位有效数字以精确文本保存，编码始终文本。

MIME为`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`，固定ASCII文件名`settlement-teaching-YYYYMMDD-YYYYMMDD.xlsx`或`settlement-payment-...xlsx`。空月份可导出零金额空明细；缺编码或权限不可下载。

## 验证与交付状态

原冻结候选证据为纯核算/工作簿127项、Bridge72项；根复核发现课时已知性变化需要完整前后状态，本次仅修该联动。定向红例已复现unknown→2仍null，修复后纯核算与工作簿专项138项已通过；独立openpyxl读取正常月报、长金额月报、支付、空月四文件及本次课时补录定向文件通过，检查长金额、前导零、五表、冻结表头、无公式/外链/私人字段。五表Artifact渲染已查看；其数字形文本预览会移除前导零，真实XML与openpyxl仍完整，不把渲染器作编码/精度证据。未进行Excel/LibreOffice原生显示验收。

真实Auth/M01/M05 Bridge专项最终82项通过，使用M01显示名与M05完整私有候选真实实现，非stub。新增unknown→2→unknown→3→4、跨日移出归0、新日补录、移回只计最终1；包括真实来源、跨日更正、支付日期、孤账不漏计、编码缺项、当前姓名和机构名且XLSX不含这些显示字段、伪造会话、admin不旁路、跨机构、撤权/显式DENY、CAS、严格重复参数、退出、嵌套事务不提交及全表内容不变。M05本身仍在并行收口，根须在冻结组合上再验；这些是适配器及HTTP参数边界测试，不是已接通宿主路由的真实HTTP/原页面验收。

按总控最新要求全部交付保持私有候选，由根统一落源，未覆盖共享文件。根负责路由、原页面、最终组合编译、运行装载与发布，本模块不自行上线。最终文件及测试命令见本目录DELIVERY_MANIFEST.json；独立源类、2专项和2脚本共7个实现/检查文件加本合同与STATUS。

复验：`bash scripts/M06Settlement-check.sh`；`bash scripts/M06SettlementBridge-check.sh`。开发阶段可用M06_SETTLEMENT_APP_ROOT、M06_SETTLEMENT_SOURCE_DIR、M06_SETTLEMENT_M05_SOURCE_DIR、M06_SETTLEMENT_M01_SOURCE_DIR覆写源码位置。测试只用本次创建的独立临时合成H2目录，不调用Db.init/seed，不动app/data；完成后清理仅本次测试临时文件。
