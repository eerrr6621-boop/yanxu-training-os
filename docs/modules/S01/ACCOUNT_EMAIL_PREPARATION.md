# 待启用导入账号的个人邮箱资料准备

日期：2026-09-23。实现文件 `NotificationChannelsAccountEmailPreparation.java`。本功能仅登记、修订或清空用户明确填写的候选个人邮箱；没有发信、验证码、本人验证、激活或密码操作。候选值不授予任何登录资格或业务权限。

## 宿主接口

- `public static void init() throws SQLException`：在 `OrganizationAccountImportHost.init()` 或 `OrganizationAccountImport.init()` 之后、接受请求之前调用。只创建三个空表及索引，不读取来源文件、不建立账号、不回填邮箱。进入时若已有事务则拒绝，避免H2 DDL提交宿主业务。
- `public static boolean matches(String path)`：匹配 `/api/account-email-preparation` 及其子路径，便于宿主在读取POST正文前鉴权。未知子路径仍在鉴权后404；不开放activate/verify等子接口。
- `public static boolean handle(HttpExchange ex, Auth.Session session)`：沿用Api的JSON正文上限、缓存正文与延迟响应出口；内部再次核对真实Auth.current、当前admin角色、请求token必须属于同一Session。全过程用现有Api.MUTATION_LOCK；保存自行开启Db.transaction，禁止宿主再嵌套事务。

- `public static Map<String,Object> parseBody(String text)`：总控在Api受限UTF-8正文读完后、空白快捷返回及原Json解析之前，针对matches路径调用。只解析本接口扁平四字段、标准JSON空白、字符串/null/精确BigDecimal；拒绝重复或转义后重名键，不受共享Double舍入影响。失败仅固定脱敏400。

- `public static void guardAccountDeletion(long id)`：由既有已鉴权users/delete入口调用。检查revisions与requests中的登记管理员引用，有记录则409固定提示“该账号已有邮箱登记记录，请停用并保留历史”；无引用不拦截，SQL错误仍脱敏503。仅空值请求、没有修订的管理员也由requests保护。目标账号本身继续受M01导入删除保护与外键约束，不删除历史或放宽FK。

总控已同步负责Api前置鉴权/路由、专用正文解析、删除保护和Db调用，原UI及真实HTTP另行联合验收；S01不覆盖共享文件。`Cache-Control: no-store` 在匹配请求上设置。没有静态actor缓存，不依赖OrganizationAccess配置是否发布，也不把M01业务角色当系统管理员。

## 精确HTTP契约

GET `/api/account-email-preparation?user_id=123` 只接受一个user_id；严格解码并拒绝未知键、重复键、编码后重复键、空片段或其他参数。值必须为安全范围内正整数字符串，不接受符号、小数或指数。

POST `/api/account-email-preparation` 不接受查询参数，必须是application/json。正文恰好四个字段：

```json
{"user_id":123,"expected_revision":0,"request_id":"15dfd2a7-c1a9-47c7-8f75-196a060d73f0","email":"candidate@example.invalid"}
```

上例为虚构格式示例。user_id/expected_revision是JSON整数，前者1..9007199254740991，后者0..9007199254740991；request_id为完整8-4-4-4-12十六进制UUID（规范为小写）。email可为字符串或null，空字符串/仅普通空格/null清除。拒绝verified、activate、password、status、actor等任何附加字段。

成功结果为Api原 `{code:0,data:...}` 包络，data如下：

```json
{"user_id":123,"revision":0,"email":null,"status":"MISSING","history":[],"can_save":true,"duplicate_email":false}
```

有值时status仅为PENDING_VERIFICATION；历史按revision升序，条目只有 `{revision,email,status,actor_user_id,recorded_at}`。recorded_at为服务端ISO时间。没有verified状态或任何验证能力标志。初始GET没有记录时返回revision=0但不创建数据；清空后status=MISSING且旧版本仍留在history。

## 账号范围、身份变化与幂等

每次查询、保存和重放，先核对当前管理员会话，再检查目标确实存在于 `organization_account_import_people`，并且users当前仍为status=0、role=viewer、password IS NULL。不导出/读取密码值，仅检查NULL；未导入普通账号或不存在账号404，已启用/改角色/有密码的账号409。管理员被撤会话401，当前角色降级403，不能依赖传入Session.role的旧值。

首次POST冻结该账号的导入person_code、来源namespace/reference、batch、来源fingerprint及disposition摘要；以后导入关联变化409，不能把前一人的邮箱历史交给新关联。引用导入账号和导入人员的外键防止先删除映射再接管资料。邮箱准备不创建或修改M01绑定，配置发布对会话的撤销继续沿用总控已实现的统一策略。

expected_revision先与当前修订CAS比较（匹配旧请求重放除外）。同UUID绑定真实管理员、目标、expected_revision及规范化候选值；任一改变409，其他管理员也不能借该UUID读取原回执。同一请求合法重放重新检查身份/目标/导入摘要，随后返回**当前最新资料与历史**，不重写旧候选值、不创建新修订，duplicate_email也按当前候选重新计算。前端不应以旧请求内容替代此响应或覆盖最新编辑。

相同当前候选值且expected_revision正确，只记请求去重记录，不增加修订；相同值但过期expected_revision仍409。初始空值保存保持revision=0，仅形成空head与去重记录。实际改值/清空才追加不可变修订、更新head，再记录request，全部同事务提交或回滚。模块没有编辑/删除历史入口。

## 邮箱格式与重复提示

仅支持常见ASCII dot-atom地址：总长≤254、local-part≤64、域名至少两标签，每标签≤63，拒绝前后点、连续点、域标签边缘连字符、空白/控制字符/CRLF、显示名/尖括号/多地址。原始输入上限512字符；先检查控制字符再修剪两端普通空格，保留local-part大小写，仅域名小写。国际化地址应由后续明确需求扩展，当前不默默改写为其他地址。

格式合格不代表邮箱存在、属于本人或可收信，不尝试DNS/SMTP/HTTP验证。重复提示对已保存的当前候选按完整地址不区分大小写比较，仅返回duplicate_email布尔值，不返回其他人的ID/姓名/数量；历史被替换的邮箱不参与。其他账号即使后来退出本准备范围，其仍保留的当前候选也参与提示。重复值允许保存，这不是一邮一人的正式身份规则；local-part大小写不同仍可成为本账号的新资料修订。

## 持久化和错误隐私

三表 `s01_account_email_heads/revisions/requests` 只关联已有users及导入映射。heads保存当前候选，revisions只INSERT、保留修改管理员与时间，requests只保存候选摘要而不重复保存明文。当前值与完整连续修订历史会重新校验，历史缺失/不一致拒绝返回。对users、导入表、OrganizationAccess、通知、业务表均无INSERT/UPDATE/DELETE。

响应错误均为固定通用说明，不回显邮箱、姓名、路径、来源标识或请求参数。SQL及意外异常转换为无cause的503，避免进入Api通用异常日志打印SQL绑定值。400非法字段/格式/分页式参数；401无效会话；403非管理员；404非本范围账号或未知接口；405方法；409修订/请求/关联/账号状态冲突；415媒体类型；503存储不可用。本接口专用JSON解析拒绝重复键及转义同名键、非法空白；精确整数包括1e0或1.0，非整数即使接近整数仍拒绝。模块不覆盖全局解析器；其他路由维持原实现。

相关历史引用用户不能通过级联删除清除资料；本表不提供清理、导出或后台任务。初次使用需要当前版空表，不能把CREATE IF NOT EXISTS当升级旧表的自动迁移。

## 验证与交付范围

专项 `scripts/S01AccountEmailPreparation-check.sh` 使用自己创建的临时目录、真实Auth和合成users/import表、独立H2；不调用主Db.init，不读实际app/data、真实名册或安全存储，不启动HTTP服务器。HTTP边界以HttpExchange夹具调用真实handle；原系统浏览器与真实Main/HTTP由总控另行验收。

脚本还会在主测试SHUTDOWN退出后启动第二个独立JVM，以新Auth会话读取原合成H2、核对当前/历史并重放最初请求，验证冷启动后的持久恢复而非仅连接重开。最终结果：主测试616项、独立冷JVM31项，共647项通过；细节见S01 STATUS。仅交付本类、两份S01专项文件、本说明及S01 STATUS；不修改Api/Db/Auth/原前端/check.sh，不重新导入184人，也不推断个人邮箱或生成默认密码。
