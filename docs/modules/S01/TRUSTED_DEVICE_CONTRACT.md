# R25 私有记住设备候选：接线与运行合同

## 安装边界

以冻结 R24 source-manifest/web-manifest 逐SHA核验6份既有Java与原app.js，再生成私有候选；未复制完整项目、数据库或runtime。`scripts/prepare_candidate.py` 只读共享基线，重建本目录受影响候选与 `patches/*.patch`，锚点或SHA不匹配则拒绝。

产品新增 `TrustedDevices.java`、`TrustedDeviceHost.java`、`web/modules/notifications/trusted-device.js`；既有Java改动仅 Auth/Api/Db/LoginVerificationHost/NotificationChannelsAccountEmailMaintenance/OrganizationAccessStore，另原 app.js 启动局部patch。Main不需要改动，原可信Main.start邮件测试注入保持。**全部接点必须作为一组审查安装**，尤其不能只接设备恢复而遗漏业务事务内的设备撤销。

最终受测候选8个Java编译目录为 `/private/tmp/yanxu-trusted-device-compile-43fp7kty`；在classpath放于R24 `<private-workspace>/2026-09-24/yanxu-release-checkpoint/build-r24/out` 之前。此目录只含候选产品类，HTTP测试类独立在 `/private/tmp/yanxu-trusted-device-fixture-out-LoVipc`。最终SHA见 candidate-manifest.json；总控应自行整合编译，不把测试类打入产品。

## 配置和固定界限

- `login.email.mode=required` 才能登记或恢复可信设备；legacy不签设备凭证。
- `login.device.maxAgeSeconds` 与 `login.device.origin` 均须显式提供。任一缺失或空白不启用，绝不隐含正式期限；同时存在但非法时报503。
- maxAge只接受十进制正整数，工程上界31,536,000秒，下界1秒（用于合成测试）。该上界不是批准的正式保存期限。首次核验生成绝对截止，不滑动延期；配置缩短时旧设备受更短期限约束，调长也不延长其原截止。
- origin必须明确HTTPS站点；HTTP只允许精确localhost/127.0.0.1/::1且请求来自回环，用于本机测试。正式站点已知但本候选不写入正式配置。
- 每账号最多10个未撤销且未到期设备，设备记录总上限100,000；达到上限拒绝新登记，仍可维护或退出现有设备。本轮没有后台清理、设备列表管理页或自动删除审计材料。

## HTTP与Cookie

`POST /api/login/device/restore` 只接受空JSON、无query；同源Origin与Host必须精确对应配置，Sec-Fetch-Site如提供必须same-origin。缺/错误Origin为403且不清cookie。有效原短会话沿用、不轮换设备；否则验证设备，事务内轮换秘密，签原短会话。无设备/过期/已撤销/身份变化401；重复或畸形适用cookie400；存储/配置故障503。401/坏cookie清浏览器旧状态，不自动重发。

正式HTTPS只消费 `__Host-yx_device`（Path=/、Secure、HttpOnly、SameSite=Strict，无Domain）；HTTP回环只消费 `yx_device_local`。另一种名字不认作身份或撤销证据，不能把有效秘密换名来绕过host-only约束。设备随机秘密只在Cookie中返回，不进入JSON；原短会话API响应合同仍保留。

原密码+一次性邮箱码合法通过后，精确同源浏览器请求才登记设备；非浏览器或不符同源的旧客户端仍可走原已核验短会话，但不获设备信任。合法新OTP可自动清理坏/重复旧cookie并登记新设备，不让坏浏览器状态永久阻断登录。没有任何从已知username或邮箱跳过密码/OTP的新入口。

原 `/api/logout` 支持短会话失效后凭当前/前值device secret撤设备；无需先恢复身份。同源但cookie坏/重复时也能清浏览器状态；若有效短会话关联设备，则撤其真实设备，不把坏值或异名cookie认作别人的身份。无device时保留原有效短会话退出要求。退出清当前浏览器设备和原会话cookie，不撤同一人的其他设备。

## 撤销、事务与重启

设备摘要之外绑定当前username/password/name/role及完整邮箱快照、持久用户代次；每次恢复都重读原服务端身份并用Auth.issueVerified，业务授权链不变。随机selector与256位秘密分离，仅存SHA-256摘要。恢复严轮换：前值再次出现会持久撤该设备；其他错误秘密不准登录且不能凭猜测撤别人的设备。

原 `Auth.revokeUserSessions` 仍只撤内存会话和原代次。新增 `revokeUserInTransaction` 必须持业务锁且处于原事务；改密/重置、账号编辑删除、邮箱变化、正式授权变更同事务推进设备代次，提交后继续旧内存撤销。name-only使用与最终UPDATE一致的规范化存储值，独立于原revokeSessions；旧邮箱码/旧短会话的name行为不重写。CAS失败或业务回滚不能撤设备；同值操作不额外撤销。两表外键ON DELETE CASCADE，不增加普通用户删除障碍。

原邮箱维护/组织发布的提交结果不确定、或单账号mutation提交后恢复连接失败，会保守撤受影响短会话并在本进程拒绝该用户设备恢复。禁止集不因后续登录或读取自动清除；由总控在确认连接/事务状态后执行受控重启，经H2恢复确认持久状态。本轮不自动修复数据库。设备自然到期单独持久标记，挡后续恢复并防时钟回退复活，但不缩短原已签发短会话。

原Auth.Session新增volatile设备关联以保证跨请求线程可见；设备派生会话每次Auth.get检查设备撤销状态。只对设备会话新增此约束，原12小时滑动闲置与cookie时限不改。

## 原界面与验证

仅原boot动态加载新小模块。先GET原/me，只有明确401才进入同源Web Lock；锁内重读/me，再必要时只POST一次restore。每请求含正文读取8秒限时，锁等待12秒；取消/迟到结果不能再次发起恢复或抢占已返回的原登录页。没有localStorage/device fingerprint/设备令牌JS持久化。原密码、邮箱核验和业务界面不重画。

最终合成验证：核心528+冷JVM39；UI113场景600项；真实HTTP1187项、80表11组只读核对；真实Chrome1440电脑95项，4截图。核心覆盖存储失败回滚/嵌套拒绝/删除约束/name A→B→A跨重启；HTTP验证所有实际变更接点、双向Cookie换名反例、坏状态恢复；Chrome验证重开浏览器、服务冷重启、双tab仅1次restore、默认关闭和原退出。

所有数据库和邮箱均为独立合成；真实SMTP/QWeather等外部请求关闭，服务和浏览器已停止。不代表正式期限已批准、功能已发布、真实邮箱已送达或首次自助绑定已完成。
