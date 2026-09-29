# 按分公司核对审批岗位：只读契约

2026-09-23，第八轮。扩展原 GET `/api/organization/account-import/preview`，不改commit协议、Host、路由或数据库结构。原preview字段及rows保持兼容，新增如下字段：

```json
{
  "branchCoverage": [
    {
      "branch": "合成分公司",
      "region": "合成区域",
      "leaderCandidates": [
        {"reference": "teacher-row-4", "roleKinds": ["BRANCH_RESPONSIBLE"], "accountId": null, "accountState": "NOT_RECEIVED"}
      ],
      "bpCandidates": [
        {"reference": "teacher-row-6", "roleKinds": ["BP"], "accountId": null, "accountState": "NOT_RECEIVED"}
      ],
      "combinedCandidates": [],
      "routingStatus": "ROLE_EVIDENCE_PREPARED",
      "defaultHandlerReference": null,
      "canApprove": false,
      "readOnly": true,
      "preparationOnly": true
    }
  ]
}
```

- branch/region是可信材料显示名，不是正式机构编码。覆盖行保持已核验来源的分公司顺序，候选保持原rows顺序；同一数组中reference唯一。
- leaderCandidates仅对应本分公司的BRANCH_RESPONSIBLE/BRANCH_LEAD；roleKinds保留本分公司对应的身份。bpCandidates的roleKinds固定为["BP"]，仅来自明确BP区域覆盖本分公司的职责。不能合并两个岗位的范围；普通教师不会进入这些列表。
- 候选姓名、所属机构从原rows按reference取。新投影不重复姓名，不返回路径、HR编号、原件格位、岗位证据全文、登录名或猜测正式编码。
- combinedCandidates为reference字符串数组：只包含本分公司负责人/牵头人与本区BP的明确同人兼任，仍需流程处理。仅同名不构成兼任。同一个人只管其明确分公司，BP其他分公司不会自动得到其负责人身份。
- routingStatus沿用可信覆盖状态：单人`ROLE_EVIDENCE_PREPARED`；多人`MULTIPLE_IDENTITIES_RETAINED_ROUTING_PENDING`。多人全部保留；defaultHandlerReference始终null，单人也不自动设置。兼任是否存在另看combinedCandidates，不覆盖多人待定状态。
- accountState为`NOT_RECEIVED`、`RECEIVED_DISABLED`或`RECEIVED_ENABLED`。未导入时accountId全部null；已接收按固定批次回执、接收记录和当前users交叉核验，accountId为正安全整数，状态只表示当前账号启停，不表示已验证邮箱/可审批。原rows的alreadyImported/personCode/accountId与投影一致。
- readOnly/preparationOnly固定true、canApprove固定false。这个机构页不提交动作，不改变原逐人decisions或核对进度，不发布配置/路由/邮箱/权限。
- 机构/候选覆盖、固定回执、接收关联或当前账号无法核实，整份预览409，不按姓名修补、不返回部分列表或默认成空已配置。缺失branchCoverage的旧响应应在UI明确显示“机构覆盖暂不可用”；不能当作零机构配置完成。

实现及核心专项验证已完成：M01BranchCoverageTest **288项**、原OrganizationAccountImportSourceTest **43项**、原OrganizationAccountImportTest **141项**全部通过，完整当前产品Java编译通过。原页面及真实Main/HTTP由总控并行接入，本模块不代称已完成页面验收。

运行`bash scripts/M01-branch-coverage-check.sh`，默认PATH中的JRE17，可用`M01_COVERAGE_JAVA`选择已安装Java。脚本默认app为自身根目录；私有候选验证可用`M01_COVERAGE_APP_ROOT`指定共享app/lib与其余现有源码。它只创建自己的临时目录，复用ECJ/H2及既有库编译当前完整产品源；测试结束和JVM退出后清理自有临时资料。

编译测试helper：`scripts/M01BranchCoverageTest.java`直接依赖既有`OrganizationAccountImportSourceTest.java`的7候选/2历史合成fixture，及所有现有产品Java；运行需要H2。check脚本还编译/运行`OrganizationAccountImportTest.java`兼容回归（其独立本机随机端口测试需允许回环监听）。本轮没有修改上述两份旧测试。

专项覆盖未接收全空账号、接收及当前启停变化、同名普通教师不串人、明确ID关联、多人保留、逐岗位分区及明确兼任、Source深层不可变、来源覆盖遗漏/重复/错区/默认办理人/擅自扩区/假兼任拒绝，回执/接收记录/当前账号失配和孤立记录整份409。全部公共业务表在有效GET及异常GET前后做一致快照，核验没有数据库写入。合成库模拟中途损坏后由测试恢复，不在产品中修补源或历史。

Source只在已核验的branchCoverage与逐人角色上新增不可变安全引用列表；来源fingerprint计算、safePreparation、intakePayload、commit/replay写入协议均未改。预览先核对完整固定回执与全部接收记录，按source namespace/reference及receipt人员/账号ID精确关联，不按姓名。接收payload与该固定来源应生成的原准备记录做结构比较；当前users只读取ID/启停用于展示。来源无法核实仍按原核心409，关联损坏统一409且不输出底层异常或证据。

本轮仅合成fixture与独立临时H2，不读取真实私人名册或当前app/data。根据总控当前记录，本机184人已在先前轮次接收为待启用账号；本轮未重复导入、激活、发布权限或改已有回执，不把准备覆盖当正式审批路由。
