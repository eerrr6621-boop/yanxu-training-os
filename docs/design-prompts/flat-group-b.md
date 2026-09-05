# V13 严格扁平业务图形 B 组 — ImageGen 记录

生成方式：内置 ImageGen，每个最终主题分别进行一次独立的全新生成；最终生成均省略参考图参数，不使用 CLI、不拼图、不裁切、不后处理、不修改仓库。

说明：最初一次 faculty 参考图编辑产生了烘焙棋盘格的 RGB 文件，因不含 alpha 已弃用。发现编辑模式问题后立即停止参考图编辑；下列四个最终候选均为无参考图的全新生成，并逐一确认 PNG 含真实 alpha 与透明像素。

## 结果概览

| 主题 | 最终资产路径 | PNG | Alpha | Alpha ≥ 16 主体包围盒 | SHA-256 | 状态备注 |
| --- | --- | --- | --- | --- | --- | --- |
| Faculty | `web/assets/icons/faculty-v13.png` | 1254 × 1254，RGBA | 真实透明；979,234 个全透明像素 | 90.75% × 64.83% | `ae762a20509a1fdc31a9774824c66ee32fab4ff9e950cdeb757afa07e1c40e5c` | 透明与语义通过；横向占幅高于 76%，需最终目视取舍 |
| Collection | `web/assets/icons/collection-v13.png` | 1254 × 1254，RGBA | 真实透明；1,147,349 个全透明像素 | 65.79% × 80.94% | `dd127448918af3319ffb033931a4b81140637cccce455ddbc0d1ceaff3301ccd` | 双横线 ¥ 为主轮廓，底托为次要结构 |
| Fees | `web/assets/icons/fees-v13.png` | 1254 × 1254，RGBA | 真实透明；798,867 个全透明像素 | 80.46% × 74.24% | `027936973df5e3d21e0895d3a8155f23f7cded8f4153e25be686f5b220d8ae8e` | 单钱包且无硬币；搭扣偏大、浅蓝比例需最终目视取舍 |
| Costs | `web/assets/icons/costs-v13.png` | 1254 × 1254，RGBA | 真实透明；1,177,130 个全透明像素 | 66.99% × 79.74% | `53adffed855ed0fbfe3582a5d63cbb8e7bcef80dd54db7971db27e8da21773fa` | 单凭证、锯齿底、三条粗线，无货币符号 |

## Faculty / 师资

最终资产路径：`web/assets/icons/faculty-v13.png`

实际完整提示词：

```text
Use case: stylized-concept
Asset type: strict flat 2D business pictogram PNG for a training operations interface, readable at 48 px
Primary request: Create a faculty pictogram made of exactly two minimal head-and-shoulder silhouettes: two simple circular heads and two clean arc-like shoulder outlines forming one compact unified symbol. No faces, hair, clothing, bodies, hands, or detailed people.
Scene/backdrop: true file-level transparency. The output PNG must have an RGBA alpha channel; every pixel outside the pictogram must be transparent alpha=0. Do not draw, simulate, or bake a transparency checkerboard. No visible background of any kind.
Style: rigorously flat 2D graphic design, substantial even stroke and shape weight, crisp bold silhouette, clean geometry, enterprise software visual language.
Composition: centered, front-facing, square canvas, complete symbol occupying about 76% of both canvas dimensions, balanced transparent margins, strong recognition at 48 px.
Color palette: solid ink/slate #43516B as at least 85% of all visible colored area; optional solid muted blue #8395B7 as no more than 15%. Use only these two flat colors.
Constraints: exactly two people; only circular heads and simple arc shoulders; solid fills; uniform substantial weight; no text.
Avoid: checkerboard pattern, white background, colored background, texture, grain, gradient, lighting, highlight, shading, shadow, glow, halo, bevel, depth, perspective, 3D, plastic, glass, clay, realistic people, cartoon people, facial features, clothes, thin fussy details, surrounding tile, container, circle, card, badge, watermark, logo, extra symbols.
```

## Collection / 回款

最终资产路径：`web/assets/icons/collection-v13.png`

实际完整提示词：

```text
Use case: stylized-concept
Asset type: strict flat 2D business pictogram PNG for a training operations interface, readable at 48 px
Primary request: Create a payment-collection pictogram whose unmistakable dominant silhouette is one large, exact Chinese yuan sign “¥” with two horizontal crossbars. Add only a minimal low receipt ledge or collection tray corner beneath it as a small supporting shape. The ¥ sign must remain the primary outline; this must not look like a wallet, purse, coin, banknote, shopping bag, or cash register.
Scene/backdrop: true file-level transparency. The output PNG must have an RGBA alpha channel; every pixel outside the pictogram must be transparent alpha=0. Do not draw, simulate, or bake a transparency checkerboard. No visible background of any kind.
Style: rigorously flat 2D graphic design, substantial even stroke and shape weight, crisp bold silhouette, limited large geometry, clean enterprise software visual language.
Composition: centered, front-facing, square canvas; the complete visible pictogram bounding box should occupy 74–78% of both canvas width and height, with at least 11% transparent margin on every side; fully contained and strongly recognizable at 48 px.
Color palette: solid ink/slate #43516B as at least 85% of all visible colored area; optional solid muted blue #8395B7 as no more than 15%, used only for the tiny supporting ledge or receipt corner. Use only these two flat colors.
Constraints: one exact ¥ symbol with two horizontal bars; one small integrated bottom ledge/receipt corner; solid fills; uniform substantial weight; no other text or numerals.
Avoid: checkerboard pattern, white background, colored background, texture, grain, gradient, lighting, highlight, shading, shadow, glow, halo, bevel, depth, perspective, 3D, plastic, glass, clay, wallet, purse, coins, banknotes, dollar sign, euro sign, shopping bag, cash register, detailed receipt, extra symbols, thin fussy lines, surrounding tile, container, circle, card, badge, watermark, logo.
```

## Fees / 课酬

最终资产路径：`web/assets/icons/fees-v13.png`

实际完整提示词：

```text
Use case: stylized-concept
Asset type: strict flat 2D business pictogram PNG for a training operations interface, readable at 48 px
Primary request: Create one single minimal wallet pictogram representing fee payment: a substantial clean wallet outline/body with one small integrated clasp tab. The wallet and clasp are the entire subject.
Scene/backdrop: true file-level transparency. The output PNG must have an RGBA alpha channel; every pixel outside the pictogram must be transparent alpha=0. Do not draw, simulate, or bake a transparency checkerboard. No visible background of any kind.
Style: rigorously flat 2D graphic design, substantial even stroke and shape weight, crisp bold silhouette, limited large geometry, clean enterprise software visual language.
Composition: centered, front-facing, square canvas; the complete visible pictogram bounding box should occupy 74–78% of both canvas width and height, with at least 11% transparent margin on every side; fully contained and strongly recognizable at 48 px.
Color palette: solid ink/slate #43516B as at least 85% of all visible colored area; optional solid muted blue #8395B7 as no more than 15%, used only on the small clasp. Use only these two flat colors.
Constraints: exactly one wallet; one small clasp; solid fills or one uniform thick outline; substantial even weight; no text or numerals.
Avoid: checkerboard pattern, white background, colored background, texture, grain, gradient, lighting, highlight, shading, shadow, glow, halo, bevel, depth, perspective, 3D, plastic, glass, clay, coin, banknote, currency symbol, credit card, receipt, second wallet, zipper detail, stitching detail, thin fussy lines, surrounding tile, container, circle, card-shaped backdrop, badge, watermark, logo.
```

## Costs / 成本

最终资产路径：`web/assets/icons/costs-v13.png`

实际完整提示词：

```text
Use case: stylized-concept
Asset type: strict flat 2D business pictogram PNG for a training operations interface, readable at 48 px
Primary request: Create one single minimal expense-receipt pictogram: a substantial receipt outline/body with a clearly recognizable serrated zigzag bottom edge and exactly three thick short horizontal content bars. The bars are abstract shapes, not text. Do not include any currency symbol.
Scene/backdrop: true file-level transparency. The output PNG must have an RGBA alpha channel; every pixel outside the pictogram must be transparent alpha=0. Do not draw, simulate, or bake a transparency checkerboard. No visible background of any kind.
Style: rigorously flat 2D graphic design, substantial even stroke and shape weight, crisp bold silhouette, limited large geometry, clean enterprise software visual language.
Composition: centered, front-facing, square canvas; the complete visible pictogram bounding box should occupy 74–78% of both canvas width and height, with at least 11% transparent margin on every side; fully contained and strongly recognizable at 48 px.
Color palette: solid ink/slate #43516B as at least 85% of all visible colored area; optional solid muted blue #8395B7 as no more than 15%, used only for one of the three short bars or a tiny edge accent. Use only these two flat colors.
Constraints: exactly one receipt; one serrated zigzag bottom edge; exactly three thick short horizontal content bars; solid fills or one uniform thick outline; substantial even weight; no text, numerals, or currency signs.
Avoid: checkerboard pattern, white background, colored background, texture, grain, gradient, lighting, highlight, shading, shadow, glow, halo, bevel, depth, perspective, 3D, plastic, glass, clay, yuan sign, dollar sign, euro sign, coins, banknotes, wallet, calculator, additional paper, thin fussy lines, surrounding tile, container, circle, card backdrop, badge, watermark, logo.
```
