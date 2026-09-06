# V13 界面维护规范

本次是在现有研序系统上更新展示层，不更换业务平台、后端框架或权限模型。
目标是清晰、实用、克制的科技感，参考 iOS 的原生字体、分组和触控习惯，
而不是给每个数字再套一个发光卡片。

## R8 当前书本设计

- 保留 R7 登录布局和现有功能。书固定展开，不再自动/手动翻页，也没有页码、重播栏。
- 左页第一行循环“培训运营 / 师资推荐 / 课程交付 / 项目管理”，第二行固定“就用 →”；
  右页品牌固定“研序”。两页第二行共用印刷基线，左页主题放大为视觉主语。
- 首次停留 2600ms，此后每词停留 3000ms，用 680ms 的裁切上移/淡入完成切换。
  只有左页印刷纹理更新；左右纸面曲率、页码状态和右页印刷均不参与轮播。
- 点击书面或按 Space/Enter 暂停与继续；拖动只轻微调整视角，松开回正。
  暂停或填写账号密码时收敛为完整文字；页面隐藏不补播，减少动态偏好下没有自动工作。
  计时器最多一个，RAF 最多一个，停留期不运行 RAF；加载失败不影响登录。
- 页型仍使用本地 Three.js 和 R7 纸张纹理，不增加库或远程资源。
- “就用 →”使用一张 ImageGen 定制透明字形与蓝色箭头，和上方轮播分离。
  保留高清原稿；网页稿仅等比缩小与 PNG 压缩。见 [字形记录](design-prompts/endorsement-v13r8.md)。
- 往期更新记录现在为 19 条，R8 标注预览，并非生产发布时间。

## R7 工作面设计（书本交互由 R8 取代）

- 登录与侧栏统一为银白色工作面，取消深色左右分割。透明层只用于登录面板，
  普通业务列表保持清晰实色背景。低幅光影响应减少动态偏好、输入焦点和暂停状态。
- 登录页保留品牌、资料入口与往期更新，不堆宣传文字。时间和天气为紧凑辅助信息；
  天气无手动选城，无定位弹窗，未配置时如实降级。详见 [天气接入](WEATHER.md)。
- Three.js 书页增加 ImageGen 细纤维纸张纹理与微弱凹凸，封面用银灰材质，
  保留真实翻页、拖动与有限停止。原图/提示词见 [材质记录](design-prompts/paper-v13r7.md)。
- 资料中心为左侧分类、右侧文件列表；文件格式、名称、大小和下载动作同一行。
  小屏分类横排、动作换行，简介截断两行，缺数据时不编造学习包或下载数。
- 往期更新按时间线展示 18 条项目记录，筛选“发布与历史 / 预览迭代”。
  默认展开最新条目。记录日期不冒充部署日期，未知日期不补造。

## R5–R6 历史设计（颜色以 R7 为准）

R6 延续此视觉，不新增巨大调度卡片：调度条件为输入区内连续的一行，桌面四列、
平板两列、手机一列；四列标签共用基线。候选只补常驻地事实和按需展开的调度参考。
缺少三人或旧常驻地未补齐时显示紧凑提示，不能用空白卡片占位或假候选填充。
省市联动同时适用于手填与带入需求，切换省份清理旧城市，导入需求保留已导入城市。

- 深墨蓝导航与登录展示区构成品牌框架；业务内容留在连续浅色工作面。
  输入保持明确标签，焦点使用单层清晰反馈，不叠加多层粗描边。
- 列表表头为白底、13px 中性文字，和数据列共用基线；桌面金额不拆行，
  师资表在不足 960px 时由表格容器横向滚动，手机仍使用原有字段列表。
- 师资匹配采用“培训需求简报 → 需求画像 → 推荐候选”。默认四个正式字段：
  培训主题、参训对象、客户单位 / 行业、培训目标；只有主题必填。
  日期、课时、讲师偏好及其他要求选填；可查看自动整理文本。
- 原话与填空草稿分别保留在当前登录会话内；只提交当前方式，不混稿、不推测空项。
  选择已有需求会预填两份草稿，修改后以当前输入为准；不会反写原需求。
  切换页面内页签可保留草稿，刷新或退出登录会清空，不向浏览器持久存储客户要求。
- 结果画像使用深色横向定义列表，不重复展示摘要和同义标签。候选标题、数量、
  排序说明保持同一基线；推荐理由与待确认项按内容纵向排列，不强行左右均分。
  评分、授课记录按需展开，分数明确为“匹配参考分 / 100”，不代表胜任概率。
- `book-binding.js` 为左右书芯各生成一个闭合压紧纸帖网格，总计 3264 三角形；
  端点书沟 relief 在翻页中段消退，不改变原核心积分曲面。字上方短线移除。
  继续保留有限翻页、降级、减少动态偏好与完整资源释放。

参考原则（非照抄页面）：[Linear 的界面层级复盘](https://linear.app/now/how-we-redesigned-the-linear-ui)
用于导航与工作内容分层；[Geist Materials](https://vercel.com/geist/materials)
用于区分工作面与真正浮起的菜单、弹窗；[Raycast Action Panel](https://manual.raycast.com/action-panel)
用于保持主操作与上下文反馈可见。具体布局、颜色与尺寸为研序的设计选择。

## 页面与信息层级

- 顶部保留页面定位、搜索和账户入口；R7 桌面补充紧凑时间天气，小屏隐藏辅助信息。
  账户姓名、角色和快捷键帮助在账户浮层中查看。
- 项目详情使用连续白色工作区：标题 → 四项进度 → 五阶段路径 → 优先事项和课程。
  结算是低对比的辅助侧栏；项目资料按需展开。不要恢复卡片套卡片或重复主按钮。
- 财务必须区分合同、已登记应收、实收、待回款和估算余额。未排课程和未录成本
  不会自动计入，不能把估算余额写成最终利润。零回款目标显示“无需回款”。
- 师资页保留“师资库 / 简历管理 / 智能推荐”三个明确入口。简历自述和系统实绩
  继续分开，不因视觉改版削弱证据、空记录说明或访问限制。
- 师资统计与页签合并为紧凑工具栏。推荐页初始为居中单栏输入区，不预留空结果卡；
  课酬和人数按需展开，校验失败自动展开。提交后在下方显示结果，零候选、错误和
  有效缓存也必须显示。结果节点始终保留在 DOM，用 `hidden` 收起，避免破坏异步保护。
- 公共学习包和私密讲师简历保持独立。公开下载只针对已上架学习包。

## 视觉和交互规则

- 登录页以独立书页对应培训和学习，依次自动翻过“培训运营 / 师资推荐 / 课程交付”，
  最后一页“研序”永久停止；仅手动重播才重新开始。不要恢复无业务含义的线环或粒子。
  顶部公开资料入口保留；页内页码、底部计数/重播/按钮全部移除，不留占位。
  书本支持拖动调整视角，右页悬停轻抬页角，点击左页回看、右页继续；最终页点击
  右页才重播。键盘左右箭头/Enter 翻页，Space 暂停/继续，Home 重播，保留焦点描边。
  删除口号段落、卖点徽章和底部流程；保持轻量表单。
  `login-motion.js` 同步返回生命周期控制器，延迟加载本地 Three.js r171；不依赖 CDN。
  等待期至多一个 timeout（首等 1900ms、后等 1300ms），翻页时长 1450ms，视角、
  页角与翻页共用至多一个 RAF。`book-surface.js` 以 48 × 12 网格按连续曲率积分；
  正背面使用独立高清印刷纹理，书脊固定，翻页抬升与静态叠放连续衔接。
  `login-book-three.js` 负责纸张、圆角封皮、页边、照明、柔和接触阴影和射线命中；
  不用硬板转动或摆动照片冒充翻页，左页两行文案统一居中版心。
  聚焦表单、隐藏页、登录请求会暂停并保留等待余时/弯曲进度；减少动态偏好直接最终页。
  完成且视角稳定后没有 RAF/timeout。离开登录页必须销毁监听、调度、观察器、
  几何体、材质、纹理与 WebGL 上下文；过期回调和延迟加载有销毁保护。
  WebGL 不可用、上下文丢失或动效异常以静态研序页降级，不阻断登录。
  渲染分辨率限制为 DPR ≤ 1.7 且不超过 150 万像素，避免高分屏无上限开销。
  手机不自动弹键盘。`orbit-brand`/`orbit-panel` 与历史 `.login-brand`/`.login-panel`
  隔离，避免旧的满屏高度、flex 方向污染。ImageGen 书页探索及最终取舍见
  [登录主视觉记录](design-prompts/login-coursebook-v13.md)。
- 主字体优先使用系统字体，配合中性灰、白色、少量蓝紫强调。科技展示首屏仍保留，
  不在业务界面运行多余的粒子、跟随光标效果或 WebGL 背景。
- 业务导航、项目流程和待办统一使用 14 枚 ImageGen 扁平图标；不再使用立体材质、
  玻璃高光或圆形底托。搜索、加号、关闭、箭头等小操作保留固定版本的 Lucide，
  新名称必须通过离线校验。图标由相邻文字说明，使用空 alt 与 `aria-hidden`，
  按钮仍提供真实名称。见 [业务图标维护说明](BUSINESS_ICONS_V13.md)。
- `style.css` 的 `.sr-only` 用于隐藏辅助标签，不能改成 `display:none` 或删除。
  搜索父容器与输入框用明确尺寸、`min-width:0` 和合适间距，避免文字覆盖图标。
- 移动端主要操作区至少 44 × 44 像素。减少字体和隐藏信息不能代替布局适配。
- 表格更多操作弹出到独立浮层，不撑高行、不被滚动容器裁剪。保留箭头导航、
  Home/End、Escape、Tab 和关闭后的焦点返回。
- 弹窗需限制在可视高度内，底部操作可达；小屏不能出现需要左右拖动才能关闭的窗口。
- 减少动画偏好必须生效。焦点样式清晰但不使用强烈光晕。财务数值保持等宽数字。
- `v13.css` 是当前视觉覆盖层，旧样式仍承担结构兼容。后续维护优先在现有组件规则
  内修改并跑回归，不继续新增 v14/v15 全局覆盖文件。

## 品牌素材与生成记录

当前品牌：`web/assets/yx-mark-v13.png`，1254 × 1254，透明 PNG。
由 ImageGen 生成，用两个有留白的蓝/青色交错笔画构成紧凑标记。已替换登录页、
应用导航、公开资料页、公开答卷和 favicon。不是正式商标检索或注册意见。

原始生成提示词保留如下，便于后续有来源地迭代：

```text
Use case: logo-brand
Asset type: final transparent raster brand mark for the Chinese training-operations SaaS 研序 (Yanxu Training OS), used in a minimalist premium application interface.
Scene/backdrop: genuinely transparent background with preserved alpha channel; no visible backdrop of any color.
Primary request: create ONE original compact abstract Y/X monogram, using intelligent open negative space to suggest open learning and ordered progression. Build the mark from two interlocking diagonal ribbon strokes, flat and graphic rather than 3D. The silhouette should feel precise, bold, simple, balanced, and technologically refined.
Style/medium: clean vector-like graphic delivered as a PNG raster image, crisp edges, flat solid-color fills, no outlines. Each stroke is substantial enough for the entire mark to remain legible at 24 px.
Composition/framing: one centered standalone mark, square canvas, the mark occupies approximately 80% of the canvas width and height with balanced margins. Deliberate simple geometry and generous internal negative space.
Color palette: dominant flat indigo #4653E5 with one restrained cyan #00B7C4 accent.
Constraints: output only the mark with actual background transparency. No wordmark and no text or lettering outside the abstract monogram. No background square, no app tile, no border, no mockup, no presentation board, no watermark.
Avoid: caterpillar or worm shapes, circles in a chain, block stacks, cubes, hexagons, stacked chevrons, excessive repeated elements, gradient, glow, shadows, textures, bevels, 3D rendering, glossy effects, fine hairlines, decorative flourishes.
```

## 发布前验收

1. 执行 `node scripts/check_frontend.cjs`、`node scripts/test_frontend.cjs` 和
   `node scripts/check_business_art.cjs`。分别检查页面资源、95 项财务/业务映射/阶段断言、
   14 枚图标的真实映射、HTML 与 PNG 完整解码；都不运行 SPA、不联网。
   另执行 `node scripts/test_login_book.cjs` 与 `node scripts/test_book_geometry.mjs`：
   有限翻页、拖动/点按、暂停、重播、减少动态、异步加载与销毁，以及曲面几何的
   端点、连续性、局部应变和不自交断言。`check.sh` 已包含这些套件。
2. 执行 `bash scripts/check.sh`，在三套新临时库中运行完整后端回归，禁止指向正式库。
3. 在 390 / 740 / 1280 / 1440 / 1920 宽度检查：登录、所有业务模块、项目详情、
   师资三模式、公开资料及问卷。确认真实视口尺寸后再记录结果。
4. 检查没有页面横向溢出、辅助标签外露、丢失图标/图片、竖排摘要、被裁剪菜单。
   检查搜索、筛选、编辑弹窗、键盘关闭/焦点返回、手机菜单和表单按钮。
   师资推荐另测：空输入 → 导入需求清错、严格课酬无金额自动展开、返回页签恢复
   有效缓存、编辑输入清除旧结果、真实候选、零候选、只读权限和结果聚焦。
5. 隔离演示库中测试批量学习包上传 → 匿名下载 → 文件完整性；测试问卷评分、
   刷新后草稿恢复、提交成功。不要把测试学习包、问卷或老师写入正式数据。
6. 正式发布仅替换 `web`，部署前备份，校验前后资源 SHA。再次只读核对正式业务
   数量、财务概览、后端源码 SHA、服务 PID 和重启次数；不得回写数据库来对齐快照。

`node scripts/check_frontend.cjs --base-url https://example.com` 只读取静态资源并核对
状态、MIME 和哈希；不登录、不上传、不调用业务写接口，不是压力测试。
