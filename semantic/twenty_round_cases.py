"""Frozen, author-labelled synthetic diagnostics: nine DIFFERENT test rounds.
Not business-approved labels, training data, or statistically independent users.
Topic variants are related: report per-round counts, never claim 72 independent
production observations. No expectations depend on predictions or runtime rules.
"""
import random

TOPICS = [
    ("服务礼仪", "柜面迎送、仪容举止与接待规范", "让网点新员工把迎接、引导和送别客户的举止做得更规范"),
    ("金融防骗反诈", "面向老年客户识别电信骗局、异常转账询问与受骗客户劝阻", "让长辈识破冒充熟人的转账骗局，保护养老积蓄"),
    ("劳动用工风险", "劳动合同签订、试用期解除流程与程序核对、劳动争议预防", "让人事专员避免试用期解除合同的程序错误"),
    ("财税实务", "账务处理、税务申报与费用凭证核对", "让财务人员核对费用票据后完成账务与纳税申报"),
    ("员工情绪管理", "识别职场压力、情绪调节与日常放松练习", "帮助总被工作中的负面感受困住的员工恢复平稳状态"),
    ("Excel数据透视", "清洗办公台账、建立数据透视表与制作汇总图表", "把每月零散表格整理起来，按部门汇总数字并画出统计图"),
    ("生成式人工智能办公", "提示词设计、材料初稿生成与人工事实核查", "用大语言模型辅助起草通知，让员工练习提问并检查生成内容"),
    ("课程开发", "面向企业内训师的教学目标、课程结构与练习设计", "帮助内训师把经验整理成一门有目标、有结构、有练习的课"),
]


def build_suite():
    cases = []

    def add(round_no, category, query, documents, relevant, rationale, transform=None):
        index = sum(c["round"] == round_no for c in cases)
        seed = 2026090700 + round_no * 10 + index
        ids = random.Random(seed).sample(range(10001, 99999), len(documents))
        rows = [dict(text=value) if isinstance(value, str) else dict(value) for value in documents]
        for row, ident in zip(rows, ids):
            row["id"] = ident
        expected = [ids[i] for i in relevant]
        random.Random(seed + 1000).shuffle(rows)
        cases.append(dict(id=f"S{round_no:02d}-{index+1:02d}", round=round_no,
                          category=category, query=query, documents=rows,
                          relevant=expected, rationale=rationale, seed=seed,
                          transform=transform))

    for i, (topic, content, paraphrase) in enumerate(TOPICS):
        other, detail, _ = TOPICS[(i+3) % len(TOPICS)]
        positive = f"主讲《{topic}》课程，内容包括{content}，带领学员进行场景练习。"
        unrelated = f"主讲《{other}》课程，带领学员练习{detail}。"
        add(1, "直接主题与课程证据", f"培训主题：{topic}", [
            positive, unrelated, "主讲餐饮门店产品陈列与库存管理。", "主讲工业设备维护与机械装配。"
        ], [0], "仅明确列出该专业课程及内容者相关；不凭泛培训经验凑三人。")
        add(2, "同义与口语需求", paraphrase, [
            positive, unrelated, "主讲采购议价与供应商交付管理。", "主讲短视频剪辑与账号运营。"
        ], [0], "需求虽不照抄课程名，具体培训动作与正例一致。")
        add(3, "实际授课与其他角色", f"培训主题：{topic}\n讲师要求：必须有实际授课记录", [
            f"实际授课记录：2025年为客户主讲《{topic}》课程，组织{content}的练习。",
            f"参加《{topic}》课程，学习了{content}并取得结业证明。",
            f"担任《{topic}》课程助教，协助讲师发放材料与记录考勤。",
            f"供职机构推出《{topic}》培训产品，本人负责报名和会务。",
            f"在岗位工作中处理{content}；个人课程方向是{topic}。",
        ], [0], "只有个人已发生的对应主讲记录满足实际授课条件。")
        add(4, "多项要求必须同时满足", f"培训主题：{topic}；{other}\n讲师要求：两项都由同一位讲师讲授", [
            positive, unrelated, positive + "\n" + unrelated,
            f"参加过《{topic}》培训。\n{unrelated}",
        ], [2], "两个独立模块缺一不可，参训不补足授课模块。")
        negation = ["没有", "尚未具备", "不具备", "从未有过", "缺乏", "暂时没有", "未曾拥有", "尚无"][i]
        add(5, "否定及段落归属", f"培训主题：{topic}", [
            positive,
            f"主讲其他课程，但{negation}{topic}的授课经验。",
            f"未授课课程：\n{topic}\n仅参训课程：\n{content}",
            f"拟开设《{topic}》，正在筹备{content}的课程材料。",
        ], [0], "否定、未授课栏目、筹备课程不能作为现有授课证据。")

        if i < 3:
            filler = "\n".join(f"第{j}项：围绕企业流程梳理与协作改进介绍现场案例和操作方法。" for j in range(80 + i*40))
            long_text = filler + "\n" + positive if i != 1 else positive + "\n" + filler
            add(6, "长文与人工画像", f"培训主题：{topic}", [long_text, unrelated,
                "主讲仓储物流管理。"], [0], "正向证据在长文开头或尾部，位置不应造成漏荐。")
        elif i < 5:
            add(6, "长文与人工画像", f"培训主题：{topic}", [
                dict(text=positive, manual_text=f"人工核对：{unrelated}"),
                dict(text=unrelated, manual_text=f"人工核对：{positive}"), unrelated,
            ], [1], "人工有效画像优先；不能恢复已删除专业，也不能忽略新增专业。")
        else:
            title = f"{topic}——场景实操进阶工作坊"
            add(6, "长文与人工画像", f"培训主题：指定课程《{title}》", [
                f"主讲《{title}》，课程内容包括{content}。", positive,
                f"未讲授《{title}》，仅参加过相应培训。",
            ], [0], "指定课程需完整标题证据，宽泛领域不能自动等同。")

    constraints = [
        ("课程开发；现场完成教学目标、课程结构和练习设计，并逐项点评", "主讲课程开发工作坊，学员现场完成教学目标、课程结构与练习设计，并对三项成果逐项点评。", "主讲课程开发理念讲座，介绍教学目标、课程结构和练习设计的原则。"),
        ("Excel数据透视；面向零基础行政人员，从清洗台账讲起", "主讲零基础行政办公Excel数据透视课，从台账清洗讲起并带领学员完成汇总。", "主讲面向资深数据工程师的Excel数据透视优化，要求学员熟练使用SQL与复杂函数。"),
        ("金融防骗反诈；面向老年客户，练习识别养老诈骗", "主讲老年客户金融防骗反诈，通过养老诈骗案例和慢速演示组织练习。", "主讲银行合规人员金融防骗反诈技术课程，内容是风险模型与后台交易监测。"),
        ("服务礼仪；只能中文授课，必须包含现场角色演练", "主讲中文服务礼仪工作坊，带领学员现场进行接待角色演练。", "主讲英文服务礼仪理论讲座，以教师单向讲解和阅读材料为主要形式。"),
        ("劳动用工风险；需要讲师本人主讲案例，机构客户名单不算个人经历", "2025年本人为客户主讲劳动用工风险课程，课堂包含劳动争议案例讨论。", "本机构客户名单：多家企业采购过劳动用工风险培训；本人主要负责活动统筹。"),
        ("生成式人工智能办公；需要线上实时演示和学员互动练习", "主讲生成式人工智能办公线上直播工作坊，实时演示提示词并辅导学员互动练习。", "主讲生成式人工智能办公，提供预录视频讲解，课程形式为独立观看。"),
        ("员工情绪管理；必须有至少三年对应授课经历", "实际授课记录：2021年至2025年持续主讲员工情绪管理，累计五年组织压力调节工作坊。", "实际授课记录：2025年首次主讲员工情绪管理，已完成一场压力调节课。"),
        ("财税实务；必须能用零售门店的费用凭证与账务案例授课", "主讲零售门店财税实务，使用门店费用凭证、账务与税务申报案例授课。", "主讲大型工业集团财税实务，案例主要为跨境并购与合并报表。"),
    ]
    for requirement, positive, negative in constraints:
        add(7, "对象方式与经历硬要求", "培训主题：" + requirement, [positive, negative,
            "主讲机械加工与设备保养。", "主讲文案创意与海报排版。"], [0],
            "主题重合不代表对象、教学方式、个人经历或年限条件均满足。")

    no_matches = [
        ("金融防骗反诈", ["尚未讲授金融防骗反诈。", "仅参加金融防骗反诈认证培训。", "负责反诈培训会务。"]),
        ("Kubernetes容器网络故障排查", ["主讲服务礼仪与客户接待。", "主讲Excel数据透视分析。", "主讲银行金融防骗反诈。"]),
        ("劳动用工风险；财税实务", ["主讲劳动用工风险。", "主讲财税实务。", "主讲服务礼仪。"]),
        ("课程开发", ["负责课程开发培训报名和签到。", "参加过课程开发培训。", "担任课程开发助教。"]),
        ("员工情绪管理", ["性别：女。年龄：35。", "个人简介待补充。", "联系电话：13800000000。"]),
        ("亲子财商；必须有实际授课记录", ["亲子财商讲师资格筹备中。", "参加过亲子财商课程。", "计划开设亲子财商课。"]),
        ("生成式人工智能办公", [dict(text="主讲生成式人工智能办公。", manual_text="人工核对：主讲服务礼仪。"), "主讲劳动用工风险。", "主讲财税实务。"]),
        ("指定课程《办公室零基础自动化三日营》", ["主讲办公室自动化。", "拟开设《办公室零基础自动化三日营》。", "主讲Excel数据透视。"]),
    ]
    for query, docs in no_matches:
        add(8, "没有合适人选时拒荐", "培训主题：" + query, docs, [], "全部缺少完整正向证据，正式推荐应为空，不能凑三人。")
    for i, (topic, content, _) in enumerate(TOPICS):
        positive = f"主讲{topic}，课程包括{content}。"
        add(9, "顺序属性及非专业字段稳定性", f"培训主题：{topic}", [
            positive, positive, f"主讲{TOPICS[(i+3)%8][0]}。", "主讲机械加工。"
        ], [0,1], "两个专业文本相同的候选应同分；姓名之外的性别年龄不参与专业能力排序。",
            transform="personal_fields_and_query_metadata")
    assert len(cases) == 72 and all(sum(c["round"] == r for c in cases) == 8 for r in range(1,10))
    return {"notice": __doc__, "cases": cases}
