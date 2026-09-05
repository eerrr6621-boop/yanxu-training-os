# V13 界面维护规范

本次是在现有研序系统上更新展示层，不更换业务平台、后端框架或权限模型。
目标是清晰、实用、克制的科技感，参考 iOS 的原生字体、分组和触控习惯，
而不是给每个数字再套一个发光卡片。

## 页面与信息层级

- 顶部只保留页面定位、搜索和账户入口。账户姓名、角色和快捷键帮助在账户浮层中查看。
- 项目详情使用连续白色工作区：标题 → 四项进度 → 五阶段路径 → 优先事项和课程。
  结算是低对比的辅助侧栏；项目资料按需展开。不要恢复卡片套卡片或重复主按钮。
- 财务必须区分合同、已登记应收、实收、待回款和估算余额。未排课程和未录成本
  不会自动计入，不能把估算余额写成最终利润。零回款目标显示“无需回款”。
- 师资页保留“师资库 / 简历管理 / 智能推荐”三个明确入口。简历自述和系统实绩
  继续分开，不因视觉改版削弱证据、空记录说明或访问限制。
- 公共学习包和私密讲师简历保持独立。公开下载只针对已上架学习包。

## 视觉和交互规则

- 主字体优先使用系统字体，配合中性灰、白色、少量蓝紫强调。科技展示首屏仍保留，
  不在业务界面运行多余的粒子、跟随光标效果或 WebGL 背景。
- 功能图标统一使用随仓库固定的 Lucide 图标；新增名称必须通过离线校验。
  不生成一堆不一致的位图按钮。装饰性图标使用 `aria-hidden`，按钮提供真实名称。
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

1. 执行 `node scripts/check_frontend.cjs` 和 `node scripts/test_frontend.cjs`。
   前者检查图标、脚本、页面挂载和资源；后者隔离测试财务展示函数，不运行 SPA。
2. 执行 `bash scripts/check.sh`，在三套新临时库中运行完整后端回归，禁止指向正式库。
3. 在 390 / 740 / 1280 / 1440 / 1920 宽度检查：登录、所有业务模块、项目详情、
   师资三模式、公开资料及问卷。确认真实视口尺寸后再记录结果。
4. 检查没有页面横向溢出、辅助标签外露、丢失图标/图片、竖排摘要、被裁剪菜单。
   检查搜索、筛选、编辑弹窗、键盘关闭/焦点返回、手机菜单和表单按钮。
5. 隔离演示库中测试批量学习包上传 → 匿名下载 → 文件完整性；测试问卷评分、
   刷新后草稿恢复、提交成功。不要把测试学习包、问卷或老师写入正式数据。
6. 正式发布仅替换 `web`，部署前备份，校验前后资源 SHA。再次只读核对正式业务
   数量、财务概览、后端源码 SHA、服务 PID 和重启次数；不得回写数据库来对齐快照。

`node scripts/check_frontend.cjs --base-url https://example.com` 只读取静态资源并核对
状态、MIME 和哈希；不登录、不上传、不调用业务写接口，不是压力测试。
