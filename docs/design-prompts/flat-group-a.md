# V13 扁平业务图标 A 组：完整生成记录

最终方式：内置 ImageGen（`image_gen.imagegen`），全新生成；所有最终请求均省略 `referenced_image_paths` 与 `num_last_images_to_include`。每个最终图标为独立调用，未采用 CLI，未裁切或后处理图片。

日历作为已确认的风格样张。A 组最初两次参考图编辑输出了烘焙棋盘格，元信息为 `hasAlpha: no`，故弃用，不纳入项目；其后依指示改为全新生成。

| 资产 | 最终项目路径 | 尺寸 | 格式 | Alpha 元信息 |
| --- | --- | --- | --- | --- |
| calendar | `web/assets/icons/calendar-v13.png` | 1254 × 1254 | PNG | `hasAlpha: yes` |
| dashboard | `web/assets/icons/dashboard-v13.png` | 1254 × 1254 | PNG | `hasAlpha: yes` |
| projects | `web/assets/icons/projects-v13.png` | 1254 × 1254 | PNG | `hasAlpha: yes` |
| documents | `web/assets/icons/documents-v13.png` | 1254 × 1254 | PNG | `hasAlpha: yes` |
| contract | `web/assets/icons/contract-v13.png` | 1254 × 1254 | PNG | `hasAlpha: yes` |

## 视觉核验说明

所有最终图均已目视检查并使用原生元信息检查尺寸与 alpha。色面放大后存在轻微不均、边缘少量像素毛边；没有额外后处理。图标占比为生成提示约束，不保证像素级精确 76%。dashboard 输出在四块面板内添加了图表负空间，较仅四个空面板更丰富，已告知主代理；projects、documents、contract 的主题结构与要求基本符合。

## calendar：最终原样完整提示词

最终项目路径：`web/assets/icons/calendar-v13.png`

```text
Use case: logo-brand.
One original precision business calendar glyph for a premium minimalist native-feeling desktop interface.
REAL transparent PNG.
Strictly flat 2D vector-like duotone geometry: dominant ink/slate #43516B plus a single restrained slate-blue #8395B7 detail (max15% area).
Strong compact icon silhouette with generous negative space, consistent substantial stroke/fill weight, softly squared corners.
Calendar consists of one simple outer frame/top band, two short binding tabs and ONLY FOUR large date marks.
At 24px it must remain clean and recognizable.
Square canvas, centered glyph occupies exactly76% width and height, balanced transparent margins.
NO 3D, no perspective, no gradients, no shading, no lighting, no bevel, no gloss, no shadows, no white porcelain fill, no surrounding tile/card/circle, no characters/people, no words/numbers, no watermark.
Premium quality comes from proportion and negative space, not decoration.
```

## dashboard：最终原样完整提示词

最终项目路径：`web/assets/icons/dashboard-v13.png`

```text
Use case: logo-brand.
One original precision business dashboard glyph for a premium minimalist native-feeling desktop interface.
REAL transparent PNG with an actual alpha channel and fully transparent background pixels; do not draw any checkerboard or transparency grid.
Strictly flat 2D vector-like duotone geometry: dominant ink/slate #43516B plus a single restrained slate-blue #8395B7 detail (max15% of colored area).
Strong compact icon silhouette with generous negative space, consistent substantial stroke/fill weight, softly squared corners.
Dashboard consists of ONLY FOUR large simple operation-panel shapes with generous clear separating space. One panel is slightly longer than the other three. Only one small panel portion uses the restrained slate-blue accent. No inset controls or tiny details.
At 24px it must remain clean and recognizable.
Square canvas, centered glyph occupies exactly76% width and height, balanced transparent margins.
NO 3D, no perspective, no gradients, no texture, no shading, no lighting, no bevel, no gloss, no shadows, no white porcelain fill, no plastic, no surrounding tile/card/circle, no characters/people, no words/numbers, no watermark, no visible background.
Premium quality comes from proportion and negative space, not decoration.
```

## projects：最终原样完整提示词

最终项目路径：`web/assets/icons/projects-v13.png`

```text
Use case: logo-brand.
One original precision business project-folder glyph for a premium minimalist native-feeling desktop interface.
REAL transparent PNG with an actual alpha channel and fully transparent background pixels; do not draw any checkerboard or transparency grid.
Strictly flat 2D vector-like duotone geometry: dominant ink/slate #43516B plus a single restrained slate-blue #8395B7 detail (max15% of colored area).
Strong compact icon silhouette with generous negative space, consistent substantial stroke/fill weight, softly squared corners.
The glyph consists of ONE simple project-folder outline with one short folder tab, open transparent interior and ONE broad negative-space compartment division. A small portion of the folder tab is the single muted-blue detail. No extra documents or additional symbols, no inset controls, no fine details.
At 24px it must remain clean and recognizable.
Square canvas, centered glyph occupies exactly76% width and height, balanced transparent margins.
NO 3D, no perspective, no gradients, no texture, no shading, no lighting, no bevel, no gloss, no shadows, no white porcelain fill, no plastic, no surrounding tile/card/circle, no characters/people, no words/numbers, no watermark, no visible background.
Premium quality comes from proportion and negative space, not decoration.
```

## documents：最终原样完整提示词

最终项目路径：`web/assets/icons/documents-v13.png`

```text
Use case: logo-brand.
One original precision business document glyph for a premium minimalist native-feeling desktop interface.
REAL transparent PNG with an actual alpha channel and fully transparent background pixels; do not draw any checkerboard or transparency grid.
Strictly flat 2D vector-like duotone geometry: dominant ink/slate #43516B plus a single restrained slate-blue #8395B7 detail (max15% of colored area).
Strong compact icon silhouette with generous negative space, consistent substantial stroke/fill weight, softly squared corners.
The glyph consists of ONE single-page document outline with a simple folded corner and EXACTLY THREE thick short content lines in its open transparent interior. The folded corner alone uses the small muted-blue accent. The page and all three lines have substantial clear weight, generous spacing and no extra detail.
At 24px it must remain clean and recognizable.
Square canvas, centered glyph occupies exactly76% width and height, balanced transparent margins.
NO 3D, no perspective, no gradients, no texture, no shading, no lighting, no bevel, no gloss, no shadows, no white porcelain fill, no plastic, no surrounding tile/card/circle, no characters/people, no words/numbers, no watermark, no visible background, no pen, no seal.
Premium quality comes from proportion and negative space, not decoration.
```

## contract：最终原样完整提示词

最终项目路径：`web/assets/icons/contract-v13.png`

```text
Use case: logo-brand.
One original precision business contract-approval glyph for a premium minimalist native-feeling desktop interface.
REAL transparent PNG with an actual alpha channel and fully transparent background pixels; do not draw any checkerboard or transparency grid.
Strictly flat 2D vector-like duotone geometry: dominant ink/slate #43516B plus a single restrained slate-blue #8395B7 detail (max15% of colored area).
Strong compact icon silhouette with generous negative space, consistent substantial stroke/fill weight, softly squared corners.
The glyph consists of ONE simple single-page approval-document outline and ONE bold simple diagonal pen overlapping the lower-right portion. The pen is the unmistakable main differentiating feature from a plain document glyph. Only two short broad content lines on the upper page, with open transparent interior. The pen has a simple pointed nib and just one small muted-blue end cap; no realistic pen details, no signature scribble, no seal.
At 24px it must remain clean and recognizable.
Square canvas, centered glyph occupies exactly76% width and height, balanced transparent margins.
NO 3D, no perspective, no gradients, no texture, no shading, no lighting, no bevel, no gloss, no shadows, no white porcelain fill, no plastic, no surrounding tile/card/circle, no characters/people, no words/numbers, no watermark, no visible background, no fine details.
Premium quality comes from proportion and negative space, not decoration.
```

## 弃用的参考图编辑调用

以下两次调用均使用 `web/assets/icons/calendar-v13.png` 对应的日历样张作为唯一编辑参考。两张均为 1254 × 1254 PNG，`hasAlpha: no`，棋盘格烘焙在像素中；未接入项目，未后处理。下列为实际原样提示词，保留用于过程审计。

### 弃用 dashboard 编辑稿

```text
Use case: precise-object-edit.
Input image: the attached calendar glyph is the edit target and exact style reference.
Replace only the calendar subject with four simple operation-dashboard panels, one panel slightly longer than the others. Use only four large softly squared panel shapes with generous separating negative space; no tiny controls or inset details.
Preserve the exact ink/slate #43516B dominant color plus a single restrained muted-blue #8395B7 detail covering at most 15% of the colored area. Preserve the reference's flat 2D vector-like geometry, substantial even stroke/fill weight, softly squared corners, and strong compact silhouette. Keep the original square canvas size and a centered 76% width-and-height footprint with balanced transparent margins. Keep genuine transparent alpha background, including all internal negative space.
The glyph must remain clean and recognizable at 24 pixels. Premium quality comes from proportion and negative space, not decoration.
Change only the subject. No texture, no gradient, no shading, no lighting, no 3D, no perspective, no bevel, no gloss, no shadow, no plastic, no porcelain, no white fill, no people, no fine details, no text, no numbers, no watermark, no surrounding tile/card/circle.
```

### 弃用 projects 编辑稿

```text
Use case: precise-object-edit.
Input image: the attached calendar glyph is the edit target and exact style reference.
Replace only the calendar subject with one simple project folder with a negative-space interior divider. Use one strong outer folder silhouette with its short tab and a clean open internal compartment division; no sheets, no tiny details, no secondary symbols.
Preserve the exact ink/slate #43516B dominant color plus a single restrained muted-blue #8395B7 detail covering at most 15% of the colored area. Preserve the reference's flat 2D vector-like geometry, substantial even stroke/fill weight, softly squared corners, and strong compact silhouette. Keep the original square canvas size and a centered 76% width-and-height footprint with balanced transparent margins. Keep genuine transparent alpha background, including all internal negative space.
Output must have an ACTUAL ALPHA CHANNEL with fully transparent background pixels. Do not draw a checkerboard, transparency grid, white background, gray background, or any other visible background.
The glyph must remain clean and recognizable at 24 pixels. Premium quality comes from proportion and negative space, not decoration.
Change only the subject. No texture, no gradient, no shading, no lighting, no 3D, no perspective, no bevel, no gloss, no shadow, no plastic, no porcelain, no white fill, no people, no fine details, no text, no numbers, no watermark, no surrounding tile/card/circle.
```
