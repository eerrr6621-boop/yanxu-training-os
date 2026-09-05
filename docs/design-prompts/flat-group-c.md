# V13 扁平业务图标 · C 组

## 生成与验收方式

本组沿用已确认日历样张的深蓝灰扁平视觉规范，使用内置 ImageGen 为每个主题独立生成。未使用 CLI、未裁切、未抠图、未改色、未做后处理。

最初四次参考图编辑输出为 RGB，棋盘格被烘焙进背景，因此全部弃用，不能进入站点资产。以下四张最终图改用全新生成，调用完全省略参考图参数，逐张通过 PNG alpha 检查。

四张最终图实际尺寸均为 1254 × 1254 像素，颜色模式 RGBA，alpha 最小值 0、最大值 255；均存在大量实际透明像素。图标是正视二维几何轮廓，无文字、投影、底座或立体材质。报告图仅以淡蓝基线作少量强调，其余为深蓝灰主体。生成器未严格保持相同的 76% 视觉占比，应以实际 24–48px 导航显示效果完成最终验收。

下文记录最终实际完整提示词与站点目标路径；路径表示接入目标，不代表此生成步骤已经部署。

## 效果评估

最终资产路径：`web/assets/icons/evaluation-v13.png`

实际 PNG：1254 × 1254，RGBA，alpha 0–255；完全透明像素 1180273；alpha ≥ 128 主体包围盒尺寸 846 × 1110 像素；原始文件 405847 字节。

实际完整提示词：

```text
Generate one standalone business icon on a REAL TRANSPARENT ALPHA background. Output a PNG with actual transparent pixels surrounding the icon and inside all negative-space openings. The background must be absent, not represented by white paint or a checkerboard.
Use case: logo-brand.
Subject: a single substantial clipboard outline with a recognizable top clip and one large check mark centered inside. No page lines, no folded paper corner, no additional symbols.
Style: STRICTLY FLAT 2D, refined restrained business navigation pictogram. Geometric, front-facing, substantial and even visual weight, softly rounded geometry, clean bold silhouette. A few large shapes only, legible at 24 to 48 pixels.
Palette: solid uniform ink/slate #43516B for the main shape. An optional tiny muted-blue #8395B7 accent must occupy no more than 15 percent of the visible icon; omit the accent if it would complicate the shape. No other colors.
Composition: square canvas. Single centered icon occupying approximately 76 percent of canvas width or height, with generous even transparent margin, no clipping. Transparent openings, not white filled areas.
Mandatory: actual PNG transparency/alpha channel, not a visual simulation of transparency. No checkerboard, no backdrop, no surrounding tile/circle/card.
Avoid completely: gradients, textures, shading, lighting, cast shadows, highlights, bevels, gloss, depth, perspective, 3D, porcelain, clay, realistic objects, detailed people, text, letters, numerals, watermark, floor, glow, or decoration. Exactly one isolated flat icon, not a set or a contact sheet.
```

## 培训资料

最终资产路径：`web/assets/icons/materials-v13.png`

实际 PNG：1254 × 1254，RGBA，alpha 0–255；完全透明像素 1219714；alpha ≥ 128 主体包围盒尺寸 1116 × 819 像素；原始文件 372858 字节。

实际完整提示词：

```text
Generate one standalone business icon on a REAL TRANSPARENT ALPHA background. Output a PNG with actual transparent pixels surrounding the icon and inside all negative-space openings. The background must be absent, not represented by white paint or a checkerboard.
Use case: logo-brand.
Subject: one open book outline, with two geometrically symmetrical pages and clean transparent negative space. No written content, no page ruling, no stacked books, no fine page-edge detail.
Style: STRICTLY FLAT 2D, refined restrained business navigation pictogram. Geometric, front-facing, substantial and even visual weight, softly rounded geometry, clean bold silhouette. A few large shapes only, legible at 24 to 48 pixels.
Palette: solid uniform ink/slate #43516B for the main shape. An optional tiny muted-blue #8395B7 accent must occupy no more than 15 percent of the visible icon; omit the accent if it would complicate the shape. No other colors.
Composition: square canvas. Single centered icon occupying approximately 76 percent of canvas width or height, with generous even transparent margin, no clipping. Transparent openings, not white filled areas.
Mandatory: actual PNG transparency/alpha channel, not a visual simulation of transparency. No checkerboard, no backdrop, no surrounding tile/circle/card.
Avoid completely: gradients, textures, shading, lighting, cast shadows, highlights, bevels, gloss, depth, perspective, 3D, porcelain, clay, realistic objects, detailed people, text, letters, numerals, watermark, floor, glow, or decoration. Exactly one isolated flat icon, not a set or a contact sheet.
```

## 经营洞察

最终资产路径：`web/assets/icons/report-v13.png`

实际 PNG：1254 × 1254，RGBA，alpha 0–255；完全透明像素 1133453；alpha ≥ 128 主体包围盒尺寸 904 × 850 像素；原始文件 384755 字节。

实际完整提示词：

```text
Generate one standalone business icon on a REAL TRANSPARENT ALPHA background. Output a PNG with actual transparent pixels surrounding the icon and inside all negative-space openings. The background must be absent, not represented by white paint or a checkerboard.
Use case: logo-brand.
Subject: exactly three rounded vertical bars of different heights and one horizontal baseline. No ascending arrow, no axis labels, no grid, and no extra chart decoration. Only the slim baseline may use the muted-blue accent.
Style: STRICTLY FLAT 2D, refined restrained business navigation pictogram. Geometric, front-facing, substantial and even visual weight, softly rounded geometry, clean bold silhouette. A few large shapes only, legible at 24 to 48 pixels.
Palette: solid uniform ink/slate #43516B for the main shape. An optional tiny muted-blue #8395B7 accent must occupy no more than 15 percent of the visible icon; omit the accent if it would complicate the shape. No other colors.
Composition: square canvas. Single centered icon occupying approximately 76 percent of canvas width or height, with generous even transparent margin, no clipping. Transparent openings, not white filled areas.
Mandatory: actual PNG transparency/alpha channel, not a visual simulation of transparency. No checkerboard, no backdrop, no surrounding tile/circle/card.
Avoid completely: gradients, textures, shading, lighting, cast shadows, highlights, bevels, gloss, depth, perspective, 3D, porcelain, clay, realistic objects, detailed people, text, letters, numerals, watermark, floor, glow, or decoration. Exactly one isolated flat icon, not a set or a contact sheet.
```

## 用户与权限

最终资产路径：`web/assets/icons/access-v13.png`

实际 PNG：1254 × 1254，RGBA，alpha 0–255；完全透明像素 1219848；alpha ≥ 128 主体包围盒尺寸 917 × 1102 像素；原始文件 411451 字节。

实际完整提示词：

```text
Generate one standalone business icon on a REAL TRANSPARENT ALPHA background. Output a PNG with actual transparent pixels surrounding the icon and inside all negative-space openings. The background must be absent, not represented by white paint or a checkerboard.
Use case: logo-brand.
Subject: one substantial shield outline containing one small simple geometric keyhole at its center. Clearly a shield and keyhole, never a person or portrait. No added badge or separate lock.
Style: STRICTLY FLAT 2D, refined restrained business navigation pictogram. Geometric, front-facing, substantial and even visual weight, softly rounded geometry, clean bold silhouette. A few large shapes only, legible at 24 to 48 pixels.
Palette: solid uniform ink/slate #43516B for the main shape. An optional tiny muted-blue #8395B7 accent must occupy no more than 15 percent of the visible icon; omit the accent if it would complicate the shape. No other colors.
Composition: square canvas. Single centered icon occupying approximately 76 percent of canvas width or height, with generous even transparent margin, no clipping. Transparent openings, not white filled areas.
Mandatory: actual PNG transparency/alpha channel, not a visual simulation of transparency. No checkerboard, no backdrop, no surrounding tile/circle/card.
Avoid completely: gradients, textures, shading, lighting, cast shadows, highlights, bevels, gloss, depth, perspective, 3D, porcelain, clay, realistic objects, detailed people, text, letters, numerals, watermark, floor, glow, or decoration. Exactly one isolated flat icon, not a set or a contact sheet.
```
