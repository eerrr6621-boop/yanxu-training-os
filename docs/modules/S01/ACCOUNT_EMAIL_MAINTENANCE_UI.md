# 原用户管理页：核验邮箱维护候选

本目录仅为私有候选。共享 app.js、Api、Auth、Main 由总控接入；本候选不配置正式邮箱、读取 SMTP 凭据或发送真实邮件。

## 接入

- 新组件放到 `web/modules/notifications/account-email-maintenance.js`。它与原 `account-email-preparation.js` 并列，旧组件完全不改。
- `scripts/pageUsersPatch.cjs` 导出纯函数 `patch(originalSource)`：只在原 pageUsers 加动态导入、清理和一个“维护核验邮箱”行操作。任一锚点缺失/重复或已安装时拒绝。可用 `node scripts/pageUsersPatch.cjs <原app.js> <不同的新候选路径>` 生成供审查文件；不会覆盖输入。
- 原“登记邮箱”、新增、编辑、改密、业务身份及岗位入口保留。新操作仅在系统管理员的 status=0/1 用户行出现，实际资格以服务端为准；已有、已启用和管理员本人可进入。
- 注入原 api / modal / form / table / route cleanup。自改成功回调执行原 invalidateSession，再提示“邮箱已保存，请重新登录完成核验。”；不额外请求 logout，也不另造登录页面。

## 用户流程与请求约束

打开后只 GET 当前目标。姓名、系统用户名、账号号均来自服务端最新响应；不把用户列表旧行当确认依据。管理员手工输入完全一致的用户名，并勾选登录身份核验用途后才能保存。更改输入、读取新资料或完成保存会要求重新确认。

POST 恰好为 `user_id / expected_revision / request_id / email / confirmed_username / purpose` 六字段，其中用途固定 `LOGIN_VERIFICATION`，版本来自最近有效 GET/回执。清空先只改填写，确认保存才提交。保存后仍显示“待本人验证”，修改历史保留原地址、时间和操作账号。

409 不自动重发：清除旧填写/确认，重读最新资料，说明可能为重复邮箱或资料变化；用户重新核对后才创建新请求。重复提示不暴露其他账号身份。

网络、5xx 或不符合合同的成功回执视为结果不明：保留同一不可变六字段请求，冻结新填写并阻止普通关闭/导航。可以只重读当前记录，但这不能证明原请求的结果，仍保留原请求；只能由明确点击“重试上次保存”重发相同 UUID 和全部字段。不会定时或自动重发。401/403、会话/路由强制清理时取消请求并清除私有窗口，迟到回执不可写入下一窗口。

自改回执的 `reauthentication_required=true` 只接受当前登录账号自己的写操作。服务端已撤销原会话；组件清空私有状态后让原宿主回登录，验证码发送仅发生在之后正常登录流程。

## 专项验证方式

最终候选：原 UI 合成 **95 场景 / 538 检查**、真实 Main/HTTP **1011 检查**、真实 Chrome **169 检查**均通过。HTTP 对72张表做14组只读阶段摘要核对，两个服务进程均停止；Chrome 覆盖1440与1024，6张截图已抽查原模态、历史换行、结果不明与滚动，未发现页面溢出。唯一独立审查发现为 revision=0 却非空邮箱/无历史的异常回执已补拒绝，最终 UI 和浏览器复验通过。

固定产品构建 `/private/tmp/yanxu-formal-hosts-orx2ph51/out`，测试夹具单独编译到 `/private/tmp/yanxu-email-maintenance-test-out-CVK3cP`，未向产品 class 目录写入测试类。真实 HTTP 结果在 `/private/tmp/yanxu-account-email-maintenance-http-qJ3xou/result.json`；最终浏览器结果在 `/private/tmp/yanxu-account-email-maintenance-http-browser-0zqwtP/result.json`。

- `S01AccountEmailMaintenanceUI.test.cjs`：原 pageUsers、modal/form/table/api 与候选组件，合成 DOM 和传输；不启动服务。
- `S01AccountEmailMaintenanceHttpFixture.java` 和 `S01AccountEmailMaintenanceHttp.cjs`：独立临时 H2 + 真实 Main/Api/Auth，仅注入内存合成发件器。`--classes` 为总控不可变产品构建，`--overlay` 为独立测试 class 目录，`--app` 为只读共享应用。
- `S01AccountEmailMaintenanceBrowser.mjs`：真实 Chrome + 原 SPA + 上述真实服务；浏览器仅覆盖私有 app.js 局部 patch 和新静态组件。业务接口不伪造；丢回执场景先实际提交，再丢弃响应。1440 和 1024 两种电脑宽度检查显示与溢出，不扩大到已取消的手机办理。

所有测试使用 `.invalid` 合成邮箱、临时随机密码和本机回环。测试进程显式 `YANXU_LOGIN_MAIL_TRANSPORT=disabled`，移除全部 `YANXU_LOGIN_SMTP_*` 并清空 JVM 外部选项；不访问共享 app/data、不读真实发件凭据、不外发。浏览器阻断外部来源及 visitor-context 请求。测试结束关闭该次临时服务和浏览器。

已有账号绑定 UI 专项若穷举固定行操作数组，需要在总控接入后将新增“维护核验邮箱”计入期望；这属于本次新增入口，不应为保持旧断言而删除功能。本候选不改共享既有专项。

## 正式剩余项

总控审查并接入候选后再做正式部署。管理员 manager01 的本人收件地址已询问一次但未获答复，不能把系统发件邮箱猜作其收件邮箱。本轮不改变184个待用账号，不决定设备记忆期限，也不宣称 SMTP 实际送达已验证。
