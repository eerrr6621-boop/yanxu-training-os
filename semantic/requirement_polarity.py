"""Literal postposed rejection scope, not enforcement or inferred requirements."""
import re

_PREDICATE = re.compile(r"^(?:不算|不计入|不计作|不作为|不满足|不能视为|不能算作|不能代替)\S.*", re.S)
_COMMA_GAP = re.compile(r"[\t \r\n]*[，,][\t \r\n]*")
_CLOSING = {"《": "》", "“": "”", "（": "）", "(": ")", '"': '"'}


def postposed_exclusion_spans(source):
    parts, closing, start = [], [], 0
    def add(left, right):
        while left < right and source[left].isspace():
            left += 1
        while right > left and source[right-1].isspace():
            right -= 1
        if right > left:
            parts.append((left, right))
    for i, char in enumerate(source):
        if closing and char == closing[-1]:
            closing.pop()
            continue
        if char in _CLOSING:
            closing.append(_CLOSING[char])
            continue
        if char in "》”）)":
            return []
        if not closing and char in "，,。；;！？!?\r\n":
            add(start, i)
            start = i + 1
    if closing:
        return []
    add(start, len(source))
    result = []
    for previous, current in zip(parts, parts[1:]):
        if (not _COMMA_GAP.fullmatch(source[previous[1]:current[0]])
                or not _PREDICATE.fullmatch(source[current[0]:current[1]])):
            continue
        left = previous[0]
        if result and result[-1][1] >= left:
            left = result.pop()[0]
        result.append((left, current[1]))
    return result
