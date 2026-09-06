# 自动城市天气（R7，待服务配置）

## 已实现的行为

登录页及桌面工作台显示设备本地时间。天气只按可信访问 IP 自动识别；没有
浏览器定位权限请求、地址输入或手动选城。城市级位置来自本机离线 XDB，
访客 IP 不传给和风，不写数据库、文件、天气日志或缓存。
免费库没有区县数据：不把网络出口位置说成用户的实际住址，也不猜测区。
VPN、运营商出口可能偏离实际位置；无法可靠匹配的地区直接显示不可用。
当前匹配使用系统省市目录。库中的部分英文、县级或港澳分区名称不在目录内，
会安全降级，并非所有国内 IPv6 地址都一定能显示天气。

读取流程：可信访客 IP → 本地省市 → 和风即时 GeoAPI → 当前天气。
仅省市查询参数和城市级坐标离开服务器；不携带用户名、账号或访客 IP。
GeoAPI 原始数据、坐标、Location ID 不缓存、不建索引。
仅按本地省市缓存天气 20 分钟；同城请求合并，后台单线程与 16 个等待槽。
接口立即返回 loading，浏览器有限重试，不占用业务数据库锁等待网络。

公共登录页也使用此接口，不要求登录会话。客户端不能指定 IP 或城市；
默认每个进程最多 40 次供应商调用/小时（一次冷启动城市约需两次）。
这限制了匿名访问对额度的消耗，但不是供应商账户的费用承诺：重启、多实例
及其他应用共用凭据都会影响总额。必须同时在供应商控制台设限和预算提醒。
超限、超时或故障时只降级天气，不能阻断登录。浏览器响应 private,no-store。

## 配置（不要把凭据提交到 Git）

选择和风开发者项目的 JWT / Ed25519 凭据，不依赖旧版 API KEY。
操作员确认商业使用条款、来源标注及本系统隐私说明后，在服务端受限的
环境配置中设置：

```text
YANXU_WEATHER_ENABLED=true
QWEATHER_API_HOST=<控制台给出的完整专属域名>.qweatherapi.com
QWEATHER_DEVELOPER_ID=<开发者 ID>
QWEATHER_PROJECT_ID=<项目 ID>
QWEATHER_KEY_ID=<凭据 ID>
QWEATHER_PRIVATE_KEY_PATH=<服务器上 PKCS8 Ed25519 PEM 私钥的绝对路径>
YANXU_WEATHER_TRUST_LOOPBACK_PROXY=true
```

私钥文件应只允许服务账户读取，例如权限 0600；放在代码、web 和公开备份目录
之外。不要粘贴到聊天里。凭据范围限制为城市查询与实时天气，并在控制台配置
固定服务器出口 IP。没有有效配置时不调用第三方，显示“天气服务待配置”。
当前迭代没有创建供应商账号、购买服务或执行真实天气请求。

可选 `YANXU_IP_DATABASE_DIR` 指向已核验 XDB 文件夹，默认 `lib/ip2region`。
库的固定版本与摘要见同目录 `README.md`；不在启动时自动下载或更新数据库。

## 反向代理：不能把服务器城市当作用户城市

Java 必须绑定 `-Dbind.address=127.0.0.1`（或 `::1`），且仅在核验生产代理后
开启 TRUST_LOOPBACK_PROXY。Nginx 直接面对公网时，**覆盖**客户端头：

```nginx
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $remote_addr;
```

不读取任意 X-Forwarded-For 的第一项。Java 只在 TCP 对端为 loopback 且
显式启用时接受单个 X-Real-IP；拒绝多值、多头、主机名及保留/私网地址。
若 Nginx 前还有 ALB/CDN，先依官方 CIDR 严格配置 realip 信任，再启用此功能。
本地 127.0.0.1 预览无法得到真实公网出口位置，不使用服务端出口替代。
此次没有修改生产 Nginx，也没有启用生产天气。

## 供应商与隐私说明

- [和风开发者许可](https://staticpages.qweather.com/legal/developers-eula.html?head=0)：商业用途、GeoAPI 不缓存等以账号实际协议为准。
- [当前计费](https://dev.qweather.com/docs/finance/pricing/)：按量额度会变动，请在开通时核验。
- [身份认证](https://dev.qweather.com/docs/configuration/authentication/) 与 [专属 Host](https://dev.qweather.com/docs/configuration/api-host/)。
- [GeoAPI](https://dev.qweather.com/docs/api/geoapi/city-lookup/) 与 [当前天气 v1](https://dev.qweather.com/docs/api/weather/weather-current/)。v1 没有观测时间字段，因此界面标记的是“获取于”，不是伪造的实况更新时间。
- [来源标注](https://dev.qweather.com/docs/terms/attribution/)：天气展示旁附和风来源与返回的来源声明链接。
- [缓存建议](https://dev.qweather.com/docs/best-practices/cache/)。

网站隐私说明应写明：本机使用访问 IP 估算城市、用途为展示天气，访客 IP 不提供
给天气商，仅按城市查询天气；定位可能与实际位置有偏差。静默交互不是绕过
网站运营方的隐私告知责任。已有高德 Key 不用于此方案。

## 验证

`bash scripts/check.sh` 包含 `WeatherTest.java`：地址/可信代理边界、双栈库读取、
供应商数据校验、城市歧义、来源链接、同城 80 请求合并和有界队列。
全部使用本地数据与注入的假供应商，不消耗真实天气配额。
真实连通性需要配置后另行验收；测试结果不代表第三方可用率或生产压测结果。
