"""Literal teaching-content scope; never an assertion about a teacher's record.

Verification-status words can be nominal objects of a positive course objective.
Recognize the entire instruction, not a substring, so an adjacent qualification,
negated directive or unknown predicate cannot inherit this narrow exception.
"""
import re


_STATUS = r"(?:已经|已|尚未|未|待)(?:核实|确认|核验)"
_OBJECT = r"(?:内容|说法|信息|事实|材料|资料|数据|记录|陈述|表述|事项|结论|证据|消息|报告|单据)"
_NOMINAL = _STATUS + r"的?" + _OBJECT
_DIRECTIVE = (
    r"(?:本次课程|这门课程|该课程|本课程|课程|本课堂|课堂)"
    r"(?:需要|必须|要|需|须)(?:教|讲解|讲清)"
    r"(?:如何|怎样|怎么)?(?:区分|分清|辨别|核对)"
)
_COMPARISON = re.compile(
    _DIRECTIVE
    + r"(?:" + _NOMINAL + r"|" + _STATUS + r")"
    + r"(?:和|与|及|、)" + _NOMINAL
)


def is_instructional_status_comparison(normalized_clause):
    """Accept only a complete affirmative instruction with two status objects.

    A shared nominal head (已核实和待确认的信息) is explicit, not inferred
    from a different clause. Callers retain the original text for retrieval and
    must still independently check all requirements against resume evidence.
    """
    # Horizontal spacing can occur in pasted Chinese text. Never join clauses
    # across line breaks or punctuation, and never return the compacted string.
    compact = re.sub(r"[\t ]+", "", normalized_clause)
    return _COMPARISON.fullmatch(compact) is not None
