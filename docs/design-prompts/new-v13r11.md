# R11 NEW 导航字标

日期：2026-09-06。素材处理完成；本次资产任务未修改 CSS、JavaScript、HTML，未部署或提交。

## 来源与授权

- 初稿使用内置 ImageGen 生成一次，没有变体或重试。
- 原始绝对路径：`/Users/tongyuqing/.codex-account2/generated_images/01a076d2-eb6d-7e80-857b-05c2faf29947/exec-f077595a-18e4-4ca7-aedc-78e05fc077b7.png`
- 原稿按字节原样保存在 `design/login/v13r11-originals/new-wordmark.png`，2048×768，1,131,830 bytes，RGBA。
- 原稿 SHA-256：`f49112b9e37105e3ee16bebe7e5651c0bfa07b40651407db1d13cfccfba1b404`
- 用户随后明确授权：本地去掉 NEW 多余光晕，保留生成字形，整理透明裁边、压缩小图后接入导航。本资产任务仅执行这些处理；不重绘字形、不新增设计、不再调用生成工具。

## 本地处理方法

使用 Node.js / sharp，维护脚本：`scripts/prepare_new_wordmark_v13r11.cjs`。

1. 原稿 alpha 范围为 0–254。大多数字形核心为 254；低透明度杂散像素扩展到正文外。原图 RGB 预览中的大面积蓝晕多数位于透明像素内。
2. 对每个源像素使用 `a' = clamp(round((a - 8) × 255 / (254 - 8)), 0, 255)`。清除 13,511 个原 alpha 为 1–8 的低透明度像素，并保留连续的抗锯齿覆盖变化，未二值化核心轮廓。
3. alpha 清零的像素同时清零其隐藏 RGB，防止透明区残留蓝色光晕数据；保留像素的原 RGB 不变。没有重新着色核心笔画，原图蓝色轻微变化如实保留。
4. 按处理后的真实墨迹裁切，源边界为 `left=155, top=136, width=1735, height=448`。
5. Lanczos3 等比缩至正文宽 156 px，然后四周各留 2 px 透明边距，得到 160×44 px。适合约 38 px CSS 宽度，提供约 4.2 倍像素密度。
6. 在内存中比较无损 WebP 与无损 PNG：5,656 bytes 对 8,139 bytes；选择更小的无损 WebP。原稿单独保存，不由导航请求。

## 最终网页素材与核验

- 文件：`web/assets/new-wordmark-v13r11.webp`
- 尺寸：160×44 px
- 大小：5,656 bytes（约 5.52 KiB）
- SHA-256：`926bd3e5af908c29dd00bc688209184536117475fb691c3556b4716d2127618e`
- Alpha 范围：0–255；全透明像素 2,371，半透明像素 2,041，完全不透明像素 2,628。
- 使用 `view_image` 检查原图、处理结果及白底/深色底的并排对照，并检查约 38 px 实际显示尺寸。NEW 拼写完整、字孔与背景透明，没有外围蓝晕，没有修改原字形或增加装饰。
- QA 对照：`.codex-tmp/new-wordmark-v13r11-qa.png`。左侧为按原 alpha 合成的原稿；中间为网页稿；右侧为约 38 px 显示宽度。上排白底、下排深色底。

## 完整精确生成提示词

```text
Use case: logo-brand
Asset type: one tiny navigation NEW wordmark PNG for the YANXU research platform; displayed at approximately 34–40 px wide next to 17 px navigation text.
Primary request: Generate exactly ONE polished, beautifully restrained typographic wordmark containing only the uppercase English letters "NEW", in that order, spelled N-E-W. Small-size legibility is the highest priority.
Scene/backdrop: genuinely transparent background with a real PNG alpha channel. All pixels outside the letters must be transparent; do not draw a checkerboard.
Style/medium: clean flat front-facing 2D typography, modern compact geometric semibold sans-serif, subtly rounded corners, natural balanced letter spacing, precise crisp silhouette. Premium minimal technology-platform feeling, not a promotional sticker.
Composition/framing: horizontal canvas approximately 3:1; centered wordmark, all three letters equal cap height on one common baseline. The wordmark should occupy approximately 80% of the canvas width, with safe transparent margins on all sides. Keep the entire wordmark visible.
Color palette: letters in one single solid cobalt blue, exactly #425be2; no other visible colors.
Text (verbatim): "NEW"
Constraints: only these three letters, rendered exactly once; upright, not italic; no perspective; no surrounding UI or navigation; no YANXU or Chinese logo.
Avoid: frames, borders, colored backgrounds, capsules, badges, red, dots, stars, sparkles, arrows, fine decorative lines, glass, 3D, gradients, shadows, textures, extra text, watermarks, mockups, checkerboard patterns.
```
