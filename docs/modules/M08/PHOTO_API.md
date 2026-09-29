# M08 实际照片接口候选

2026-09-23。实际照片私有候选及验证已完成，供总控整合原电脑界面。产品、测试与文档以本目录 MANIFEST.sha256 为准；宿主接点和实际界面验收由总控完成。

## 上传和读取

POST /api/training-summaries/photos/upload，application/json：

    {project_id,expected_version,request_id,content_type:"image/jpeg"|"image/png",data_base64:"纯Base64"}

不接受文件路径、外链、data URL、机构、人员、摘要或客户端指定图片编号。需要本项目 summary.read 与 summary.edit，当前版本可编辑，expected_version 包括新草稿 0。请求幂等。上传创建不可变项目图片，不自动保存或替换总结正文。返回正常 Api 信封 data：

    {photo_id,project_id,width,height,content_type,byte_size,uploaded_at,replayed}

GET /api/training-summaries/photos/content?project_id=101&photo_id=<服务端32位小写十六进制ID>&revision=3

校验当前 summary.read 和该版引用关系后返回 JPEG/PNG。revision=0 仅用于上传者自己的待保存预览，须当前仍有 summary.edit、人员绑定未变且当前总结可编辑。查看保存版本必须提供该固定版本号。图片不通过公开静态目录或 URL 服务。前端以原鉴权 fetch 获取 Blob 后展示，用完撤销 object URL。

## 保存总结与历史

现有 POST /api/training-summaries/save 增加可选顶层字段：

    photos:[{photo_id,caption:"图注"}]

数组顺序就是照片顺序。省略 photos 保留当前全部引用及图注；显式 [] 清空新版本照片，旧版本仍保留。caption 为必传字符串，最多 500 字符；最多 10 张且不能重复。客户端只提交图片编号和图注，其余元数据服务端查验后冻结。图片必须属于同项目同机构。

所有 current / revision / comparison 两侧版本 DTO 增加 photos 数组，每项为 {photo_id,caption,width,height,content_type,byte_size}。原 content 中的 publicity.photoCaptions 是兼容文字图位，不能当作实际图片。普通保存、刷新来源不得丢失照片。photo_policy 改为 PROJECT_UPLOADS_VERSIONED。

图片引用与正文同一修订、同一事务、同一版本摘要；复核中禁止变图，已批准后改图产生新草稿，重新复核。Word 只嵌当前已批准版照片，旧版字节不因新上传而变化。

## 宿主唯一新增接点

Api.readBodyLimited 仅对精确路径 /api/training-summaries/photos/upload 提高 JSON 限额到 7 MiB，其余路径保持原限额。初始化与路由继续通过 TrainingSummariesIntegration。

Api.file 在既有 office 白名单外增加严格成对的 image/jpeg + photo-<32位ID>.jpg 和 image/png + photo-<32位ID>.png，图片限制 5 MiB。继续缓存响应、在业务锁外发送、Cache-Control:no-store、nosniff。不放行通用二进制、不增加静态目录。UI 通过授权 fetch Blob 即可，不需要公共链接。

图片存储在新增空数据库表 m08_summary_photos 中，与项目有外键；不改 Db 或实际 data，不依赖文件路径。原 JPEG/PNG 解码、真实类型/像素检查后重新编码，仅保存规范化后的像素图，去除原文件 metadata；JPEG 方向信息先应用到像素。输入/规范化单张 5 MiB；每版 10 张且总计 12 MiB；项目历史 100 张且 100 MiB。

## 版本兼容

旧无图片版本的正文 JSON、版本摘要和导出 Word 字节保持兼容。新版本的 content_json 内部附照片引用元数据和字节摘要，外部 DTO 将正文 content 与 photos 分开。旧代码不能读取含照片的新修订；正式发布回退须按总控的代码与数据库备份方案一并处理，不把照片字段删除来适配旧代码。

上传、提交、通过复核和导出时校验图片内容摘要；写后复查权限、人员、版本与图片字节，失败回滚本次事务。图片字节及元数据没有替换或删除入口。客户端换图或修改图注只能保存为新总结版本。
