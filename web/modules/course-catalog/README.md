# 原师资库内的课程与认证

组件沿用 V13 原弹窗、表格、表单、请求和页面生命周期，不新增导航或样式壳。课程查看和资格查询要求 `catalog.read`；新增课程、改名和启停要求 `catalog.manage`，两者互不替代。空间列表由服务器提供，组件不创建空间、不从旧 admin/manager 角色推断权限。

## 宿主接口

`openCourseCatalog(context)` / `mountCourseCatalog(root, context)` 返回 `{ready, refresh, destroy}`（直接挂载另提供 `requestLeave`）。原宿主提供：

```js
openCourseCatalog({
  api, openModal, closeModal, renderTable, renderForm, collectForm,
  signal, isCurrent, getUser, getModal,
  onCatalogChanged: () => invalidateTeacherRecommendations(),
});
```

`api` 自动添加 `/api`，拆包返回 `data`，支持 `quiet` 和 `AbortSignal`。`getUser()` 返回当前同一个身份对象；身份对象替换、路由失效、关闭弹窗或切换空间都会使旧请求不可用。`getModal()` 用来核实弹窗归属；被原页未保存守卫拒绝打开时，不绑定其他弹窗。销毁只清理自己创建的组件和仍拥有的弹窗。

`onCatalogChanged({scopeId})` 在发出确认时使原推荐缓存失效，不代表保存成功。确认已提交但回执未知时，旧目录和资格结果也立即移除。已核实的成功会重新读取维护资料；有 read 能力时同步重读原只读目录。独立读取票据防止初次慢读覆盖后续已保存目录。

`refresh()` / 切空间 / 普通关闭遇到未确认修改时，使用原弹窗内的“继续编辑 / 放弃并继续”提醒；没有原生确认框。页面强制卸载直接取消并清除。管理组件按需动态加载：

`/modules/course-catalog/course-maintenance.js?v=20260923certmaintenance2`

## 权限与请求

先读取 `/organization/me`，明确区分未绑定、未配置和有效绑定；随后读 `/course-catalog/scopes`。用户显式选择空间后才读该空间资料。

- `can_read !== false`：沿用原只读 `GET /course-catalog/scopes/{id}` 和显式资格查询 `POST /{id}/qualify`。缺少新能力字段的旧协议继续只读兼容。
- `can_manage === true`：显示维护区，读取 `GET /{id}/course-management`。仅 manage 的用户不会请求原 read/qualify。
- version 0 可由已授权管理者建立首批课程。version 大于 0 即使 courses 为空，仍保留服务器返回的师资、认证和关联数量。

原只读课程、师资、认证及资格结果保持每页 20 条。课程、日期必须显式选择；等级和城市是精确值集合。资格结果需与服务器返回的 `qualification_context`、目录版本、机构、条件和完整档案绑定匹配；不计算交通条件或推荐排名，课程资格不等于整体派单准入。

## 课程维护

维护区每页 20 条，页间输入保留。课程编码、名称和启停组成业务表单；已有编码不能删除或换码，新行可以移除。仅变更版本名称、说明或行顺序不产生有效课程修改。

只有点击“保存预览”才请求 `POST /{id}/courses-preview`，内容严格为 `{expected_version,catalog_version,courses,change_comment}`。组件核对空间、版本、批次 UUID、课程内容、统计、课程差异以及 teacher/certification/binding 差异全部为空。`ready:false` 仅显示问题，不提供确认；合法 warning 可以随可确认预览展示。输入再变动会立即废弃旧确认。

预览显示新增、改名和启停的前后对照。用户再独立点击“确认保存”，发送 `{batch_id,expected_version,confirm:true}`。组件校验 CONFIRMED / ALREADY_CONFIRMED 回执。未知网络结果保留原批次并锁住编辑，只允许用户显式重试同一次幂等确认，或放弃后刷新核对；不自动另建预览。已确认成功但后续读取失败明确提示已保存。并发冲突要求显式刷新，不自动合并或覆盖；401/403 清除旧资料。

课程编辑区不提供教师、认证、绑定、历史还原或教师 lookup；认证编辑区见下文。服务端保留它们并继续完整校验；界面不显示 JSON、哈希、私有材料路径或配置字段。普通文本、校验提示与前后值均转义，不解释为 HTML。

## 验证

- `node scripts/M04ReadonlyUi_test.mjs`：原只读兼容。
- `node scripts/IntegrationDeliveryCatalogUI.test.cjs`：原宿主回归。
- `node scripts/IntegrationCourseMaintenanceUI.test.cjs`：原页面/弹窗/表单/请求的合成行为测试。
- `node scripts/IntegrationCourseMaintenanceBrowser.mjs --java <java> --classes <isolated-classes>`：真实原 SPA + Main + synthetic fixture，独立临时 H2，1280/390/320。脚本输出检查结果和截图，关闭自建服务与浏览器；不使用真实工作库。

教师主名单接收另见 `roster-import.js` 及 `docs/modules/M04/TEACHER_ROSTER_IMPORT.md`，本轮不改变其接收流程。

## 第十一轮：逐课认证维护

可管理空间提供“维护课程 / 维护认证”切换，默认仍为课程。切换复用当前编辑器的页内放弃提醒，一次只挂载一个编辑器；动态加载及读取分别绑定编辑器票据，迟到的认证响应不能重新覆盖课程表单。只读空间不显示维护切换，不加载认证模块或调用认证管理端点。

`certification-maintenance.js` 读取 `GET /{id}/certification-management`，只接收当前可信目录的 `teacher_codes` 与 `courses` 作为认证对象。精确编码可直接填写，或按需打开每页 20 条的筛选选择；不创建数千项 select，不调用教师 lookup。历史永久绑定已不在当前目录的编码不作为候选。没有可配对教师或课程时明确提示并禁新增。

认证表保留分页修改，现有教师/课程组合不可删除或换键；新增行可移除。状态默认 `unknown`，另有 `certified/not_certified/revoked`；来源和日期沿原精确字符串，空日期不填默认值、不表示永久有效。已认证仍受课程启用、教师在库和有效期规则限制。只在显式“保存预览”后发送 `{expected_version,catalog_version,certifications,change_comment}` 至 `POST /{id}/certifications-preview`。

界面核对完整认证数组、每个组合、状态、来源、日期、版本、计数及差异：课程/师资/绑定变化全部为空，认证不允许 removed，新增或更新至少一条；只有 ready 且已核实批次才能独立确认。确认采用原端点，同样支持同批幂等重试、已保存但刷新失败、权限清空、CAS 强制重读和旧结果取消。发出确认立即清除原资格及推荐依据，成功后重新读取当前原目录。课程与认证两编辑器在保存及其后续读取期间均锁住旧分页，避免已提交状态被旧行操作破坏。

本轮两管理子模块资源版本统一为 `20260923certmaintenance2`。新增检查：

- `node scripts/IntegrationCertificationMaintenanceUI.test.cjs`：认证行为、原编辑器互斥/放弃、当前绑定候选、20 条分页、两编辑器保存后读取中的分页锁等。
- `node scripts/IntegrationCertificationMaintenanceBrowser.mjs --java <java> --classes <isolated-classes>`：独立合成 Main/H2 的真实原 SPA，1280/390/320，包括认证修改后的原资格查询、仅管理/只读、历史教师移除、未知确认、并发及身份失效；不读取实际工作数据。

精确服务器契约见 `docs/modules/M04/CERTIFICATION_MAINTENANCE.md`。本轮不新增师资编码、绑定、认证权威校验或权限配置。
