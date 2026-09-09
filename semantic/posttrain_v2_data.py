"""Length-balanced, synthetic evidence-retrieval training/development cases.

No model outputs, production rules, former test cases, or personal resumes are
read. A negative is insufficient evidence for this particular request, NOT an
assertion that a person lacks professional ability. Labels below are authored
from explicit scenario facts, not produced by a classifier. Development topics
are disjoint, but share construction methods: this is not a business blind test.
"""
from collections import Counter
import hashlib
import random
import unicodedata


NOTICE = (
    "All examples and evidence labels are AI-authored synthetic scenarios, not "
    "business-expert-verified resumes. Negative means this passage does not "
    "establish the requested facts, not that a teacher is unqualified. Families "
    "and their paraphrases stay in one split. Train/dev topics are disjoint; "
    "construction styles are shared, so dev is diagnostic, not business-blind. "
    "Positive passages are strictly shortest/middle/longest equally often. "
    "No former tests, held-out files, real resumes, model predictions or "
    "production matching rules are imported or used to define labels."
)

# id, course, required audience, contrasting audience, first unit, second unit
TRAIN_TOPICS = (
    ("bank_etiquette", "银行网点服务礼仪", "网点柜面服务人员", "企业行政人员", "客户迎送", "仪容举止"),
    ("complaint_handling", "客户投诉处置", "客服一线班组", "采购经理", "争议梳理", "投诉回应"),
    ("team_leadership", "基层团队领导力", "新任基层主管", "在校学生", "目标沟通", "任务授权"),
    ("finance_tax", "财税业务实务", "企业财务经办人员", "物流司机", "凭证核对", "税务申报流程"),
    ("employee_emotions", "员工情绪与压力管理", "一线员工", "行业研究员", "压力信号识别", "情绪调节练习"),
    ("ai_office", "AI办公应用", "办公室文员", "小学家长", "提示词编写", "文档校验"),
    ("labor_employment", "劳动用工风险识别", "企业人事专员", "生产设备检修工", "劳动合同核对", "用工流程记录"),
    ("course_development", "内部课程开发", "企业内训师", "酒店前台人员", "教学目标编写", "课堂练习设计"),
)
DEV_TOPICS = (
    ("sales_negotiation", "销售谈判实务", "客户经理", "设备管理员", "需求澄清", "报价沟通"),
    ("warehouse_safety", "仓储作业安全", "仓库作业人员", "软件开发人员", "货架风险辨识", "通道巡检"),
)

KINDS = ("role_owner", "completed_duration", "teaching_years", "audience_modules")
DEV_KINDS = ("role_owner", "completed_duration", "audience_modules")

QUERY_FORMS = {
    "role_owner": (
        "寻找已亲自给{audience}讲授过《{course}》的讲师；听课记录和岗位操作记录不能充当本人授课证据。",
        "需要《{course}》的实际主讲人，授课学员为{audience}。请核对讲授者是否就是该讲师，而非团队另一位成员。",
        "这次面向{audience}的{course}培训，要有讲师本人已完成讲授的记录。",
        "材料能确认该讲师亲自主讲{course}、听课对象是{audience}吗？",
        "请找给{audience}实际讲过{course}的个人记录，不以组织课程或参与学习代替讲授。",
        "为{audience}安排{course}培训。人选本人须有对应授课经历，岗位经验本身不满足要求。",
    ),
    "completed_duration": (
        "需要已给{audience}完成{course}授课的讲师，本人实际教学至少90分钟，组织活动的时间不计。",
        "找面向{audience}讲过{course}的人，讲师本人已完成的课堂讲授须达到九十分钟，未来排期不算。",
        "核对{course}的交付记录：听课对象为{audience}，本人已经讲授不少于90分钟。",
        "这次推荐要看到给{audience}讲{course}的已完成记录，净教学时间至少一个半小时。",
        "{audience}需要{course}培训。讲师应已有本人完成讲授的经历，实际授课达到一小时三十分钟。",
        "能否找到已向{audience}讲完{course}、本人累计课堂讲授满九十分钟的资料？不能把活动总长直接当授课时长。",
    ),
    "teaching_years": (
        "找给{audience}讲授{course}的讲师，至少连续三个年度都有本人实际授课记录，不按从业年数计算。",
        "需要{course}的持续教学经历：面向{audience}，至少三个相邻年度每年都由本人讲授。",
        "核对该讲师是否连续三年都亲自给{audience}讲过{course}，一年内多场不能代替三个年度。",
        "推荐人选应有{course}的三年连续年度授课记录，三个年度的学员都是{audience}，团队业绩不代替本人经历。",
        "我们要给{audience}做{course}培训。要求资料至少覆盖连续三个自然年度的本人授课，而非个人入职年限。",
        "哪些个人教学记录能证明：{course}已连续三个年度面向{audience}讲授，且每年主讲人都是本人？",
    ),
    "audience_modules": (
        "需要已为{audience}亲自讲完{course}中{unit_a}、{unit_b}两个单元的老师，两个单元都要有本人授课依据。",
        "查找{course}的实际授课者：学员是{audience}，{unit_a}和{unit_b}均由同一名讲师亲自讲授。",
        "这次要面向{audience}培训{unit_a}与{unit_b}。讲师本人须已有两个单元的实际教学记录。",
        "谁已亲自给{audience}讲授{course}的两个部分——{unit_a}及{unit_b}？只编写讲义不能代替讲授。",
        "找{course}的讲师，要求已由本人向{audience}完成{unit_a}、{unit_b}两个模块，不能只覆盖其一。",
        "请核对面向{audience}的{course}经历，{unit_a}与{unit_b}两项内容都必须是该讲师本人已讲完的。",
    ),
}

# Shared, label-neutral editorial context. These sentences make no new claim
# about a person's teaching role, completion, audience, duration, or seniority.
# They are assigned independently of truth labels and are never cut mid-sentence.
NEUTRAL_CONTEXT = (
    "该摘录保留原有标题。", "条目沿用目录顺序。", "资料页设有统一页码。",
    "课程名称保持原样。", "附件使用同一编号体系。", "页眉注明材料名称。",
    "文字按原目录编排。", "文件另附目录与索引。", "记录采用统一版式。",
    "条目可按页码查找。", "栏目名称保持不变。", "本段位于同一份文件中。",
    "摘录页附有栏目编号。", "正文与目录分列存放。", "目录保留原有层级。",
    "材料分项记录在案。", "文档另设内容索引。", "条目按材料类别编排。",
)


def _topic_dict(topic):
    return dict(zip(("key", "course", "audience", "wrong_audience", "unit_a", "unit_b"), topic))


def _facts(topic, kind):
    """Author-defined facts: one supporting passage and four hard alternatives."""
    t = _topic_dict(topic)
    c, a, wrong, x, y = (t[key] for key in ("course", "audience", "wrong_audience", "unit_a", "unit_b"))
    if kind == "role_owner":
        positive = f"我给{a}讲过{c}，负责讲解{x}与{y}。个人授课单的角色栏记为主讲人。"
        negatives = [
            f"我以学员身份参加{c}培训，与{a}一起听课并完成{x}和{y}的学习作业。角色栏记为参训者。",
            f"所在团队已为{a}交付{c}。主讲人是另一位顾问，我负责整理{x}与{y}的学习资料。",
            f"本人在岗位上处理{x}和{y}，并为{a}整理{c}相关操作记录。该条记载的是日常业务职责。",
            f"我已独立为{wrong}完成{c}授课，负责讲解{x}与{y}；该班招生对象全部为{wrong}。",
        ]
        rationale = "正例明确本人主讲、课程与学员相符。四个难负例依次为本人参训、团队其他人主讲、岗位业务记录、本人授课但对象不符。"
    elif kind == "completed_duration":
        positive = f"本人已给{a}讲完{c}，实际课堂讲授120分钟。这个时长仅记本人的教学，另列的活动组织时间没有计入。"
        negatives = [
            f"本人为{a}主讲{c}，活动共90分钟。其中实际教学50分钟，其余40分钟用于开场说明与资料发放。",
            f"已为{a}拟定{c}方案，计划由本人讲授120分钟。方案状态为等待开课，当前交付物是课前计划。",
            f"本人已给{a}讲完{c}，实际课堂讲授80分钟。该数字仅记本人的教学，另列活动组织时间。",
            f"{a}的{c}课堂已完成120分钟教学，由合作讲师主讲。我承担资料整理和活动组织，角色栏注明组织人员。",
        ]
        rationale = "正例本人已完成净教学120分钟，满足至少90分钟。负例依次是活动90但教学50、120分钟未来计划、仅完成80分钟、120分钟由他人讲授。"
    elif kind == "teaching_years":
        positive = f"本人在2023、2024、2025年都给{a}主讲{c}，三个年度各有个人授课记录。这是连续三年的本人教学。"
        negatives = [
            f"本人自2020年从业，2025年首次给{a}主讲{c}。个人授课记录只列2025年，其他年份属于岗位履历。",
            f"团队在2023、2024、2025年给{a}讲授{c}。前两年由其他顾问主讲，本人仅主讲了2025年度。",
            f"本人在2024、2025年已给{a}主讲{c}，并列出2027年拟开课计划。已完成记录覆盖两个年度。",
            f"本人2025年三次给{a}主讲{c}，三份记录均在同一个年度；资料中列明这是开始授课的第一年。",
        ]
        rationale = "正例三个相邻自然年度均为本人实际授课。负例依次混用从业年数、团队年数、未来计划，或将同年三场误作三年。"
    elif kind == "audience_modules":
        positive = f"{a}参加的{c}已结课，{x}、{y}均由本人亲自讲完，两个单元的讲授人签注相同。"
        negatives = [
            f"{a}参加的{c}已结课，我亲自讲完{x}，{y}由另一位老师讲授。两个单元的讲授人分别签注。",
            f"本人已亲自给{wrong}讲完{c}的{x}和{y}，两个单元均已结课，学员名册只列{wrong}。",
            f"我编写了{c}中{x}、{y}的讲义，供{a}课前阅读。该班的课堂教学由合作讲师完成，我交付的是教材。",
            f"本人给{a}完成{c}专题课，讲授范围只包括{y}一个单元，课程单把{x}列在另一位讲师名下。",
        ]
        rationale = "正例同一名讲师已向指定对象讲完两个单元。负例依次为单元分属他人、学员不符、仅编教材、本人只覆盖另一单元。"
    else:
        raise ValueError("unknown_family_kind")
    return positive, negatives, rationale


def _seed(value):
    return int(hashlib.sha256(value.encode("utf-8")).hexdigest()[:16], 16)


def _style(text, style):
    # Each formatting choice is used for positives and negatives alike. Style
    # assignment is separately seeded; it is not tied to positive length rank.
    return (
        text,
        "讲师档案摘录：" + text,
        "项目材料记录\n" + text,
        text + "\n以上为本条摘录。",
        "经历栏目：" + text,
        "资料核对节选\n" + text,
    )[style]


def _pad(text, target, rng):
    pool = list(NEUTRAL_CONTEXT)
    rng.shuffle(pool)
    index = 0
    while len(text) < target:
        text += pool[index % len(pool)]
        index += 1
    return text


def _rows(topics, kinds, split):
    rows = []
    family_ordinal = 0
    for topic in topics:
        t = _topic_dict(topic)
        for kind in kinds:
            family = f"{split}:{t['key']}:{kind}"
            positive, negatives, rationale = _facts(topic, kind)
            for variant, query_form in enumerate(QUERY_FORMS[kind]):
                ident = f"v2_{split}_{t['key']}_{kind}_{variant + 1:02d}"
                rng = random.Random(_seed(ident))
                # Equal shortest/middle/longest frequency per family. Rotate
                # across families so a wording variant does not identify rank.
                positive_rank = (0, 2, 4)[(variant + family_ordinal) % 3]
                other_ranks = [rank for rank in range(5) if rank != positive_rank]
                rng.shuffle(other_ranks)
                ranks = [positive_rank] + other_ranks
                cores = [positive] + negatives
                styled = [_style(core, rng.randrange(6)) for core in cores]
                base = max(map(len, styled)) + 1
                # The whole-sentence overshoot is smaller than a rank gap, so
                # each row has strict, unambiguous character-length ordering.
                passages = [_pad(text, base + 18 * rank, rng) for text, rank in zip(styled, ranks)]
                if sorted(range(5), key=lambda i: len(passages[i])).index(0) != positive_rank:
                    raise AssertionError("length_rank_construction_failed")
                rows.append({
                    "id": ident,
                    "group": t["key"],
                    "family": family,
                    "query": query_form.format(**t),
                    "pos": [passages[0]],
                    "neg": passages[1:],
                    "rationale": rationale,
                    "variant": f"wording_{variant + 1}",
                })
            family_ordinal += 1
    return rows


def build_dataset():
    return {"train": _rows(TRAIN_TOPICS, KINDS, "train"),
            "dev": _rows(DEV_TOPICS, DEV_KINDS, "dev"), "notice": NOTICE}


def normalized(text):
    return "".join(unicodedata.normalize("NFKC", text).casefold().split())


def audit_dataset(dataset):
    """Pure construction audit, not a model evaluation or label certification."""
    report = {}
    all_queries, all_passages = {}, {}
    cross_split_duplicates, cross_family_duplicates = [], []
    repeated_within_family = 0
    for split in ("train", "dev"):
        rows = dataset[split]
        positions, families = Counter(), Counter()
        same_length = 0
        lengths = []
        duplicate_queries = []
        for row in rows:
            key = normalized(row["query"])
            if key in all_queries:
                duplicate_queries.append((all_queries[key], row["id"]))
            all_queries[key] = row["id"]
            families[row["family"]] += 1
            documents = row["pos"] + row["neg"]
            sizes = [len(text) for text in documents]
            lengths.extend(sizes)
            if len(set(sizes)) != len(sizes):
                same_length += 1
            positions[sorted(range(len(sizes)), key=lambda i: sizes[i]).index(0)] += 1
            for text in documents:
                key = normalized(text)
                if key in all_passages:
                    old_split, old_family, old_id = all_passages[key]
                    if old_split != split:
                        cross_split_duplicates.append((old_id, row["id"]))
                    elif old_family != row["family"]:
                        cross_family_duplicates.append((old_id, row["id"]))
                    else:
                        repeated_within_family += 1
                all_passages[key] = (split, row["family"], row["id"])
        report[split] = {
            "queries": len(rows), "families": len(families), "passages": len(lengths),
            "variants_per_family": sorted(set(families.values())),
            "positive_length_ranks": dict(sorted(positions.items())),
            "longest_passage_heuristic_correct": positions[4],
            "shortest_passage_heuristic_correct": positions[0],
            "middle_passage_heuristic_correct": positions[2],
            "length_tie_rows": same_length, "min_passage_chars": min(lengths),
            "max_passage_chars": max(lengths), "duplicate_queries": duplicate_queries,
        }
    report["cross_split_duplicate_passages"] = cross_split_duplicates
    report["cross_family_duplicate_passages"] = cross_family_duplicates
    report["within_family_duplicate_passages"] = repeated_within_family
    report["shared_groups"] = sorted({row["group"] for row in dataset["train"]} & {row["group"] for row in dataset["dev"]})
    report["shared_families"] = sorted({row["family"] for row in dataset["train"]} & {row["family"] for row in dataset["dev"]})
    report["label_source"] = "AI-authored explicit scenario facts; not model or production-rule predictions"
    return report


if __name__ == "__main__":
    import json
    print(json.dumps(audit_dataset(build_dataset()), ensure_ascii=False, indent=2))
