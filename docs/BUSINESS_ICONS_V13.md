# V13 业务图标维护说明

## 资产范围与路径

整套共 14 个名称：

`dashboard`、`projects`、`documents`、`contract`、`calendar`、`evaluation`、`faculty`、`collection`、`fees`、`costs`、`report`、`access`、`materials`、`recommend`。

统一采用扁平蓝灰风格、透明背景，无底托。最终 `dashboard` 为 2 × 2 排列的四个纯色圆角方块，不含块内图案。

| 用途 | 路径 | 每张规格 |
| --- | --- | --- |
| 高清设计原稿 | [design/icons/v13-originals/](../design/icons/v13-originals/) | 1254 × 1254 PNG |
| 网页实际资源 | [web/assets/icons/](../web/assets/icons/) | 160 × 160 RGBA PNG |

同名文件在两处对应，命名为 `<name>-v13.png`。网页应引用 160px 资源；高清原稿供后续维护、重新导出使用。

最终高清原稿总计 **6,114,875 B**，网页资源总计 **169,438 B**，体积降低约 **97.2%**。

## 生成与导出

图标由内置 ImageGen 生成，未使用 CLI。

用户显式允许等比缩小与压缩。网页版本仅使用 Sharp 等比 resize 和 PNG 压缩，不裁切、不改色、不重绘。流程由 [scripts/prepare_icons.cjs](../scripts/prepare_icons.cjs) 复现；Sharp 是可选的资源处理依赖，不是网页运行依赖。

更新时先保留高清原稿，再通过该脚本导出对应网页资源；不要把高清文件直接覆盖到网页资源目录。

## 页面使用规范

源码中的 `BUSINESS_ART` 负责名称与资源的映射，CSS 控制显示尺寸：

| 场景 | 显示尺寸 |
| --- | --- |
| 导航 | 24px |
| 流程 | 32px / 34px |
| 任务 | 36px / 40px |
| 常规操作 | 保留 16px Lucide 图标 |

业务语义图标使用本套资源；常规操作继续使用 Lucide，不作整套替换。

## 提示词记录

生成阶段的完整原稿记录：

- [flat-group-a.md](design-prompts/flat-group-a.md)
- [flat-group-b.md](design-prompts/flat-group-b.md)
- [flat-group-c.md](design-prompts/flat-group-c.md)
- [flat-recommend.md](design-prompts/flat-recommend.md)

A 组记录中的旧 `dashboard` 已弃用；最终四方块版本以 [flat-dashboard-final.md](design-prompts/flat-dashboard-final.md) 为准。

这些记录描述的是生成阶段的高清 PNG。即使历史表格将 1254px 元信息列在网页目标路径旁，也不代表当前网页目录仍保存 1254px 文件：当前以“资产范围与路径”表为准，高清原稿 1254px，网页资源 160px RGBA。

## 离线验收

使用离线检查脚本 `check_business_art.cjs` 验证真实 `BUSINESS_ART` 映射与 PNG 解码，不仅检查文件是否存在。更新后同时核对名称映射、透明背景和实际页面显示效果，避免引入未采用的原稿或错误尺寸。
