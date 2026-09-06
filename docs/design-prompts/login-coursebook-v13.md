# 研序登录 · 课程书页探索与交互实现

使用内置 ImageGen 生成；原稿 `design/login/v13-originals/coursebook-v13.png`。
压缩预览 `design/login/v13-originals/coursebook-preview-v13.webp`，等比缩小至 1200 × 900，WebP quality 86；未裁切、重绘或改色。
生成工具为内置 ImageGen，非 CLI。原稿保留供设计追溯，不作为当前登录页运行素材。

用户后续明确要求“自动翻页，显示培训运营等系统内容，最后研序停止”。因此最终改为
`web/login-motion.js` 有限序列与 `web/scene/login-book-three.js` 的真实 Three.js 场景；
主题顺序为培训运营 → 师资推荐 → 课程交付 → 研序。最终页停止，不自动循环。
按用户最新反馈，移除书内/书外页码与底部所有控件。中间的 DOM 切片方案已撤销；
当前为 48 × 12 网格的连续曲面，真实双面纸张、圆角精装封皮与页边；支持射线命中
翻页、拖动视角和悬停抬角。左页采用统一居中版心。Three.js r171 从本地延迟加载。
纸页印刷用可维护文字与现有 ImageGen 业务图标/品牌标识，每面 768 × 1024 纹理。
图形初始化失败或上下文丢失会回退到静态 HTML 书页，不影响账号密码登录。
未把单张书本图片缩放或摇晃宣称为翻页。线环方案也已撤销。

## 最终提示词

```text
Use case: stylized-concept
Asset type: hero visual for Yanxu (研序), a premium Chinese professional training and faculty management login page.
Primary request: one exceptionally refined open coursebook, immediately recognizable as learning and organized knowledge. A slim, elegant open book with three gently lifted pages forming a restrained ascending rhythm. Real book structure, NOT an abstract torus, NOT a trophy or sculpture. The open pages express learning and training; their precise ordered arrangement expresses Yanxu, 'research and order'.
Style: premium editorial product render, sophisticated material and lighting, quiet Apple-like restraint; realistic thin pearl-white paper, satin white covers, very fine blue-violet page edges with just a hint of cyan reflection. No glassy toy, no thick clay, no chunky icon styling. Do not turn the whole book purple. The page faces are blank, with no writing.
Composition: landscape 4:3 image, a single book centered, full object visible, three-quarter view from slightly above so the open spread and fine page layering are unmistakable. Object fills about 65% of image width. Seamless very light cool off-white background color #f6f8fc, diffused natural studio shadow, no horizon. Refined visual weight, detailed but simple silhouette. Large clean margins for subtle web perspective movement.
Constraints: one book only; no pen, no stars, no people, no floating UI cards, no platforms, no pedestal, no rings, no orbit lines, no wireframe, no network, no text, no logo, no watermark. This is an isolated website artwork, not a screenshot or UI mockup.
```
