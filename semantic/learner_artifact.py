"""Source-only scope for learner materials, not personal teaching evidence."""
import re

_TIME = r"(?:课堂|课程|培训|整期)(?:结束|结课)后"
_MATERIAL = r"[\u4e00-\u9fff]{0,20}(?:模板|步骤卡|练习卡|练习册|练习表|记录表|讲义|工作表|答题纸|操作卡|检查表|提示卡)"
_INFORMATION = r"[\u4e00-\u9fff]{0,20}(?:信息|资料|数据|档案|记录|名单)"
_JOIN = r"[，,][\t ]*(?:\r?\n[\t ]*)?"
_SUBJECT = r"(?:(?:" + _TIME + _JOIN + r")?(?:学员|学生|参训者)(?:带走|领取|拿到|收到|保留)的是|" + _TIME + r"(?:留下|保存)的是)"
_SENTENCE = re.compile(_SUBJECT + r"(?P<material>" + _MATERIAL + r")" + _JOIN + r"(?:而不是|不是|而非|并非)(?P<information>" + _INFORMATION + r")")
_FACT_RISK = re.compile(r"本人|我|他人|同事|讲师|老师|教师|主讲|讲授|授课|教学(?:记录|经历|证明)|履历|简历|经历|资格|证明|证据|证书|认证|完成|未|不|非|没有|计划|拟|假设|虚构|引用|转述")
_PRIOR_RISK = re.compile(r"假设|假如|如果|虚构|转述|引用|摘录|原话|(?:以下|上述|以上|这是|此为|仅为|只是)[^。；;\r\n]{0,30}(?:模板|样例|示例)")


def artifact_context_spans(text, completed_personal_event):
    """Return complete, contiguous source sentences to withhold from proof.

    The prior teaching predicate is supplied by the caller and does not call
    this function. Denials elsewhere still apply; no inferred actor or course
    fields are produced. Keep offsets in the unmodified input's code points.
    """
    result = []
    for sentence in re.finditer(r"(?:[^。；;！？!?\r\n]|\r?\n(?![\t ]*\r?\n))+", text):
        raw = sentence.group()
        clause = raw.strip()
        found = _SENTENCE.fullmatch(clause)
        if not found or _FACT_RISK.search(found.group('material') + found.group('information')):
            continue
        at = sentence.start() + len(raw) - len(raw.lstrip())
        paragraph_start = max((m.end() for m in re.finditer(r"\r?\n[\t ]*\r?\n", text)
                               if m.end() <= at), default=0)
        prior = text[paragraph_start:sentence.start()]
        if not completed_personal_event(prior) or _PRIOR_RISK.search(prior):
            continue
        result.append((at, at + len(clause)))
    return result
