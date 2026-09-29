# 授课记录与核对组件挂载合同

交付位置：`app/web/modules/delivery-settlement/index.js`。这是可按需挂载的原宿主弹窗组件，不创建菜单、独立页面或演示框架，不修改公共 CSS、API、数据库。只依赖现有 `../settlement/conversion.js`，没有新增 CSS 或外部依赖。

```js
// 原app.js位于IIFE内，使用按需动态import。
const { mount } = await import('/modules/delivery-settlement/index.js');

const controller = new AbortController();
const delivery = mount(content, {
  api, renderForm, openModal, closeModal,
  signal: controller.signal,
  onSaved: (detail) => { /* 刷新原列表对应行或提示保存成功 */ },
  onVerified: (detail) => { /* 刷新原列表对应行或提示核对成功 */ },
});
addRouteCleanup(() => { controller.abort(); delivery.cleanup(); }, pageEpoch);

// 由原课程与排期中的记录操作调用，不新增导航入口。
await delivery.open(dispatch.id);
```

## 宿主函数

- `mount(root, context)` 同步返回 `{ open(dispatchId), cleanup() }`。`root` 必须属于当前页面，用来取得 `ownerDocument`，组件不替换其内容。`open()` 返回 Promise，初次读取结束后完成；同实例再次打开会关闭旧弹窗并取消旧请求。
- 必需 `api(path, { body?, signal, quiet })`、`renderForm(fields, data)`、`openModal(title, html, options)`，直接复用宿主原函数。`openModal` 必须返回本次弹窗节点，支持 `noFoot`、`wide` 和 `onClose`。
- `closeModal` 可选，但建议注入。未注入时组件点击自己弹窗内的原 `#modal-x`，使宿主同步移除键盘焦点监听、恢复焦点。不能用只删除 DOM 的假关闭替代它。
- `signal` 接到宿主切页和注销清理。`cleanup()` 可重复调用，会取消未完成请求、移除组件监听、通过宿主关闭弹窗；晚到响应不会重新写入页面。关闭单个弹窗后仍能再次 `open()`，`cleanup()` 后实例不可重用。
- `onSaved`、`onVerified` 可选，接收当前可用详情（旧幂等结果已尝试只读刷新）。回调只更新原列表或提示；不要在这些回调中重建整页或无条件关闭弹窗，否则会丢失用户保留的未提交输入。回调自身失败不会重试已完成写入。
- `onComplete({ dispatch_id, expected_version, signal })` 可选。未注入时完成按钮不可见；组件没有完成接口默认请求。只有宿主原完成入口已经使用同一套服务端权限与事实版本门槛时才应接入。组件不通过旧角色 `canWrite` 授权。
- 宿主 `api` 须保留 `ApiError.code/status` 并延续原 401 注销处理。组件本身在 401 时中止请求并停交互，403 时只重新读取能力，不继续写入。

## 数据与操作

- `GET /delivery-settlement?dispatch_id=…` 读取详情和当前能力。只有 `current_server === true` 且 `capabilities.fact_version` 与 `detail.version` 相等，才使用服务端 `permissions` 和 `can_save/can_verify/can_complete`。
- `POST /delivery-settlement/save` 仅提交 `dispatch_id`、`expected_version`、`request_id`、`estimated_hours`、`planned_hours`、`actual_minutes`、`payable_hours`。四项输入为十进制字符串或 `null`；空值不推成零，不填充其他课时，不上传身份、维度、实际课时或金额。
- `POST /delivery-settlement/verify` 仅提交 `dispatch_id`、`expected_version`、`request_id`、`evidence_code`。依据编号为 1–96 字符，首位字母或数字，其余允许字母、数字、点、下划线、冒号、短横线。核对人和时间由服务端返回。
- 实际分钟使用已有纯换算函数预览：`60 → 1.33`。实际课时只读；计酬课时单独填写。组件不显示正式规则、结算或支付。
- 有未保存事实输入时，核对与完成禁用；保存完成后再按最新能力启用核对。无核对授权的账号仅能按自身保存能力录入草稿。核对依据输入在正常保存、冲突刷新和幂等结果刷新时都保留；仅首次打开读取服务端已有依据。
- 400 保留输入和错误，相同被拒数据不能直接再次提交，须修改相关字段。409 保留输入且锁住写操作，用户须明确点击“读取最新记录”，对照已保存快照后手动保存，新请求使用最新版本。
- 网络未知或 5xx 不自动重试。组件保留原请求及编号，仅在用户明确点击“重试上次提交”时重发相同内容；未确认前禁用新写入。幂等返回历史业务版本但能力指向新版本时，只补一次 GET，保留全部输入，不自动写入。

## 测试与定位

字段：`[data-k="estimated_hours|planned_hours|actual_minutes|actual_hours|payable_hours|evidence_code"]`。按钮：`[data-m05-action="save|verify|complete|refresh|retry"]`。显示区域：`[data-m05-status]`、`[data-m05-saved]`、`[data-m05-capability]`、`[data-m05-verification]`。

`app/scripts/M05IntegrationUI.test.mjs` 可直接运行：

```sh
node --test app/scripts/M05IntegrationUI.test.mjs
```

测试默认按脚本位置查找 `app/web`；可设置 `M05_WEB_ROOT` 和 `M05_UI_MODULE` 检查暂存组件。16 项无外部依赖的宿主/DOM 桩交互测试覆盖字符串与空值、权限、脏表单、400/409、403 重读、未知结果显式幂等重试、历史响应刷新、依据保留、401、切页取消与晚响应隔离。另以 `M05IntegrationBrowser.mjs` 在真实Chromium中复用原宿主弹窗、表单、图标函数与原CSS，35项检查通过，已查看1280/390宽度截图，无横向溢出、运行错误0。该测试只用合成API，原页面正式接线后的端到端验收仍由总控执行。
