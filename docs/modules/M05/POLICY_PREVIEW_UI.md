# M05 课酬只读预览组件

暂存组件：`web/modules/delivery-settlement/policy-preview.js`。交付到宿主时保持同一路径；测试脚本放在 `app/scripts`。本次只新增组件、测试与说明，没有改动共享 `app.js`、现有授课核对组件、公共样式或生产数据。

## 挂载接口

```js
const { mountPolicyPreview } = await import('/modules/delivery-settlement/policy-preview.js');
const route = new AbortController();
const preview = mountPolicyPreview(existingContainer, { api, signal: route.signal });
await preview.open(dispatch.id);

// 原弹窗关闭、切页或注销时调用。实例 cleanup 后不再复用。
route.abort();
preview.cleanup();
```

- `mountPolicyPreview(root, { api, signal })` 同步返回 `{ open(id), cleanup() }`；只向当前容器追加自有 `section`，保留已有内容。不会新建页面、导航或弹窗。
- `open(id)` 返回读取结束后的 Promise；同实例再次打开会先清除旧金额、旧课时和旧来源，取消旧请求，再读取新记录。格式错误的 ID 在清除旧预览后抛出 `TypeError`，不会请求接口。
- 请求严格为 `api('/delivery-settlement/policy-preview?dispatch_id=…', { method: 'GET', signal, quiet: true })`，没有 body，也不从前端提交课时、等级、日别、资格或金额。`api` 使用宿主原函数，返回已解包的数据对象。
- `signal` 应同时接入原弹窗 `onClose`、切页与注销清理；`cleanup()` 幂等，取消请求、清空旧数据、移除自有节点和监听，不关闭或替换其他宿主内容。清理或切换后的迟到成功/失败均不覆盖新记录。
- 401 清除内容并要求重新登录，当前实例不再请求；403 清除内容并禁用刷新。403 后宿主明确再次 `open(id)` 可重新请求当前服务端权限。网络失败保留空白金额区，允许用户手动重新读取，没有自动重试。

## 展示合同

- 只使用服务端实际字段；实际分钟、实际课时、计酬课时分别显示。`null`/缺失值显示“待核”，真实零值保持零；前端不换算计酬课时、不选费率、不乘金额、不舍入。
- 所有课时、费率和金额必须为非负十进制字符串或空值，保留完整精度。响应 ID、事实版本、状态和 `can_confirm:false` 必须有效，否则清空预览并提示重新读取。
- `raw_amount` 标注“未舍入计算值”。`final_amount` 标注“舍入后试算”，仍不是正式已确认金额；缺失时显示“待确定”，说明金额口径或核对条件尚未齐备。整个组件始终没有结算确认和付款按钮。
- `CONDITIONAL_PREVIEW` 或 `raw_amount_is_conditional:true` 明示条件预览；未核准和资格待核事项保留可读提示。`NOT_ELIGIBLE` 不显示主金额或条件场景试算金额。`HISTORY_PROTECTED` 不显示新费率、新金额或场景表，提示读取原冻结记录、按旧规核实补发。
- `amount_scope:DEVELOPMENT_POOL` / `rate.activity:JOINT_DEVELOPMENT` 将总开发课酬与个人份额分开。总额只读 `pool_*`，个人只读 `individual_*`；不会把 `raw_amount` 当个人金额、按人数平均分配或从总额反推份额。
- `scenarios` 使用真实结构：`reference_grade`、`activity`、`teaching_day_type`、`unit_rate`、`condition`、`conditional:true`，兼容 `payable_hours`、`raw_amount` 缺失。场景表直接展示后端给出的未舍入条件试算，不把实际课时代入。
- `execution_basis.adoption_decision/adoption_date` 展示本次执行依据；源文件状态、原文发布日期/生效日期、记录版本、执行版本和校验值放在默认折叠的“查看依据与记录来源”。不把用户确认日期当作正式发布日期或追溯生效日期。
- 服务端文字全部通过 `textContent` 设置，不能生成 HTML。沿用原 `panel`、`panel-head`、`table-wrap`、`tbl`、`inline-note`、`btn gray` 样式，无新增 CSS。单元格补齐原手机卡片所需的 `data-label`；长十进制值/来源标识只添加原生 `wbr` 换行点，不改复制出的文本。

## 验证结果

- `M05PolicyPreviewUI.test.mjs`：21 项无依赖交互测试通过，覆盖精确小数、金额舍入缺失、条件预览、历史保护、共同开发总额/个人份额分离、未知值、XSS、仅 ID GET、刷新先清旧数据、晚响应、401/403、切页取消与清理。
- `M05PolicyPreviewBrowser.mjs`：50 项真实 Chromium 检查通过。读取原系统五份 CSS 及原 `openModal/closeModal`，使用临时系统分配端口与合成 API；未连接生产或已有预览端口。
- 1280px / 390px 下项目容器、原弹窗、场景表及展开后的来源详情均无页面或面板横向溢出；手机的列标签和值均可读。已人工查看桌面、手机预览和条件场景截图。交付截图仅保留每个宽度一张预览，位于 `policy-preview-qa/`；捕获时关闭动画以免截到原界面入场过程。按总控要求移除的本轮额外产物清单：`m05-policy-scenarios-1280.png`、`m05-policy-scenarios-390.png`，移除前浏览器验收已结束，无进程写入。
- 这些测试验证组件与原宿主样式的兼容性；不替代正式页面接线后的端到端权限验收。

暂存目录运行：

```sh
M05_POLICY_UI_MODULE="$PWD/work/m05/web/modules/delivery-settlement/policy-preview.js" node --test work/m05/M05PolicyPreviewUI.test.mjs
M05_INTEGRATION_APP_ROOT='<repository>' M05_POLICY_UI_MODULE="$PWD/work/m05/web/modules/delivery-settlement/policy-preview.js" M05_POLICY_UI_SCREENSHOTS="$PWD/work/m05/policy-preview-qa" node work/m05/M05PolicyPreviewBrowser.mjs
```

交付进 `app/scripts` 后，可在 app 根目录直接执行：

```sh
node --test scripts/M05PolicyPreviewUI.test.mjs
node scripts/M05PolicyPreviewBrowser.mjs
```

浏览器测试依赖本机已有 Playwright 和 Chrome，沿用既有 M05 浏览器验收的运行路径；支持 `M05_CHROME` 覆盖 Chrome 路径。默认不写截图，只有显式设置 `M05_POLICY_UI_SCREENSHOTS` 时写入指定输出目录。
