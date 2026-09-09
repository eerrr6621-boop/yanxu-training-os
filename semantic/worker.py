"""Offline, loopback-only semantic evidence worker. No ticket lookup or generation.

Does not promote candidates or modify teacher records. The persistent cache holds
only salted text hashes and vectors (never raw resumes). All limits fail closed:
an incomplete comparison must not be represented as a complete ranking.
"""
import argparse
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import math
import os
from pathlib import Path
import re
import socket
import sqlite3
import time
import unicodedata

MODEL = "BAAI/bge-small-zh-v1.5"
REVISION = "7999e1d3359715c523056ef9478215996d62a620"
MAX_BODY = 512 * 1024
MAX_DOCUMENTS = 200
MAX_CHUNKS = 512
MAX_CHARS = 220
QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章："
NEGATED_OR_UNCONFIRMED = re.compile(
    r"不擅长|不包含|不需要|无需|不涉及|不具备|不是|并非|不熟悉|不了解|"
    r"没有|暂无|尚无|缺乏|缺少|尚未|未曾|从未|未担任|未涉及|未具备|"
    r"未授课|未从事|未提供|未亲自(?:主讲|讲授|授课)|未实际(?:主讲|讲授|授课)|仅参加|仅参训|仅听过|待核实|待确认|待核验|[（(]筹[）)]|筹备中"
    r"|(?:不代表|不表示)(?:本人|我|个人|该讲师|此讲师)?(?:的)?(?:授课|教学|讲授|经历|经验)"
    r"|(?:课程|课堂|练习)(?:不要求|不安排|不开展|不进行)"
)
NO_EXPERIENCE = re.compile(r"无(?:相关|任何|实际|教学|授课|从业|主讲|行业|项目|工作|银行|金融|客户|课程|管理|培训){1,6}(?:经验|经历|背景)")
PERSONAL_FIELD = re.compile(r"(?:性别|年龄|出生日期|出生年月|民族|婚姻状况|宗教信仰|身份证|家庭住址|联系电话|手机号码|电子邮箱)\s*[:：]")
PARTICIPATION = re.compile(r"(?:参加|参与|参训|学习|听课|结业).{0,50}(?:课程|培训|认证|证书)")
EXCLUDED_HEADING = re.compile(r"(?:未授课课程|未讲授课程|尚未开设课程|仅参训课程|参训经历|参加过的培训|助教经历|筹备课程|未来规划|计划课程)[:：]?")
POSITIVE_HEADING = re.compile(r"(?:个人介绍|主讲课程|精品课程|课程列表|擅长领域|授课专长|授课领域|主讲方向|授课风采|教学经历|实际授课记录)[:：]?")
GROUP_HEADING = re.compile(r"(?:团队业绩|机构业绩|团队案例|机构案例|公司案例|团队授课记录)[:：]?")
SCORING_HEADING = re.compile(r"(?:主讲课程|精品课程|课程列表|擅长领域|授课专长|授课领域|主讲方向|授课风采|教学经历|实际授课记录)[:：]?")
OTHER_HEADING = re.compile(r"(?:教育背景|资质认证|认证经历|工作经历|职业经历|项目经历|服务客户|客户名单|联系方式|个人职责|个人案例|本人经历)[:：]?")
TEMPLATE_LINE = re.compile(r"(?:替换个人照片|请替换个人照片|点击(?:此处)?(?:添加|输入)(?:标题|文本)|[xX]{3,})[。.!！]?$")
PERSONAL_TEACHING = re.compile(
    r"(?:本人|我|该讲师|该教师|该老师)[^。；;，,！？!?]{0,48}?(?:主讲|讲授|授课|讲完|讲过|教授|带教)|"
    r"^(?:主讲|讲授|教授|亲自授课)(?!老师|讲师|教师|人员|专家|人|由)|(?:授课|讲授|主讲)(?:工作)?由(?:本人|我)(?:完成|承担)|"
    r"(?:主讲|讲授|授课)(?:老师|讲师|教师|人员)(?:是|为|[:：])(?:本人|我)"
)
# Explicit actor, optional recipient, and a completed teaching action. Recipient
# text is checked separately: arranging another teacher is not personal teaching.
PERSONAL_EVENT = re.compile(
    r"(?:本人|我|该讲师|该教师|该老师|同一讲师)"
    r"(?P<state>(?:已经|已|曾经|曾|实际|分别|先|独立|亲自){0,3})"
    r"(?:(?:为|给|向|面向)(?P<audience>[^。；;，,！？!?\r\n]{1,30}?))?"
    r"(?:独立|亲自)?(?P<action>主讲|讲授|授课|讲完|完成(?:了)?[^。；;，,！？!?\r\n]{0,40}?(?:教学|授课|讲授))"
)
EVENT_OBJECT_RISK = re.compile(r"参加|参训|旁听|准备|安排|筹备|报名|资格|认证|编写|审核|签收|登记|联络|协助|邀请|材料|申请|计划|拟|助教|助理|讲义|课件|教具|教材|老师|讲师|教师|教员|专家|他人|其他人|同事|未|不|没有")
EVENT_TAIL_RISK = re.compile(r"(?:的)?(?:报名|登记|准备|计划|认证|资格|证书|方案|材料|申请|审批|老师|讲师|教师|人选|名单|讲义|课件|教材|教具|助教|助理|记录|总结|报告|视频|档案)")
REVERSE_PERSONAL = re.compile(r"(?:独立|实际|亲自)?主讲(?:老师|讲师|教师|人员|教员|人)?(?:是|为|[:：])(?:本人|我|该讲师)(?=$|[，,。；;！？!?\r\n])|由(?:本人|我|该讲师)(?:独立|实际|亲自)?主讲(?=$|[，,。；;！？!?\r\n])")
ORGANIZATION_EVENT = re.compile(r"(?:本|所在|供职)?(?:单位|部门|机构|公司|团队)(?:已|曾|累计|负责|举办|开设|开展|交付|完成)")
SUPPORT_FACT = re.compile(r"(?:本人|我|该讲师)(?:已|已经|独立|仅|只|主要){0,3}(?:负责|承担|完成)[^。；;，,！？!?\r\n]{0,40}(?:讲义|教材|课件|教具|教学助理|授课助理|教学助教|安排其他人)")
CONTENT_EXCLUSION = re.compile(r"(?:本课程|该课程|这门课程|课程|课堂)?(?:不包含|不包括|不涉及)([^。；;，,！？!?\r\n]{2,40}?)(?:内容|模块|课程)")
RECIPIENT_RISK = re.compile(r"邀请|安排|协助|联络|让|计划|拟|未|不|没有|承担|参与|编写|整理|由|其他|另一|主讲|讲授|授课")
OTHER_NONTEACHING_CLAUSE = re.compile(r"(?:其他讲师|其他老师|其他教师)(?:均|都)?(?:没有|未)(?:承担|参与)(?:该课|这门课|该课程|本课|本课程)(?:的)?(?:主讲|讲授|授课)")
CLASSROOM_ACTION = r"(?:写代码|编写代码|编程|配置(?:检测)?系统|安装软件|连接生产系统|操作真实设备|带电操作)"
TASK_RESTRICTION_CLAUSE = re.compile(
    r"(?:本课程|该课程|这门课程|课程|课堂|本课堂|练习|本次练习)(?:均|全部)?"
    r"(?:无需|不需要|不要求|不安排)(?:学员)?" + CLASSROOM_ACTION + r"(?:(?:或|和|及|与|、)" + CLASSROOM_ACTION + r"){0,3}"
)
COURSE_REFERENCE = r"(?:这门课(?:程)?|这堂(?:面授|线上|远程|线下)?课|该课(?:程)?|本课(?:程)?|这一主题|该主题|这一班|本班|本次课程|这次课程)"
ORGANIZATION_WORK_EXCLUSION = re.compile(r"(?:这些|上述|该项|本项)?(?:组织|会务|后勤|保障)工作(?:均|都)?(?:不涉及|不承担)(?:课堂讲解|课堂教学|课程讲授)")
REFERENCED_PERSONAL_COMPLETION = re.compile(
    r"(?:" + COURSE_REFERENCE + r"由(?:本人|我|该讲师|该教师|该老师)(?:已经|已|实际|独立|亲自|全程){0,4}讲完(?:了)?|"
    r"(?:本人|我|该讲师|该教师|该老师)(?:已经|已|实际|独立|亲自|全程){0,4}"
    r"(?:(?:为|给|面向)[^，,。；;]{1,40})?讲完(?:了)?" + COURSE_REFERENCE + r"(?:的)?(?:全部(?:内容)?)?)")
NON_TEACHING_ROLE = re.compile(
    r"(?:仅|只)(?:负责|承担|参与|协助|提供|整理|汇总|安排)|"
    r"(?:助教|会务|报名|会场安排|资料运营|课程库管理)|"
    r"(?:负责|承担|安排|整理|汇总)[^。；;！？!?]{0,24}(?:签到|签名|参训|报名)|"
    r"(?:邀请|协助|配合|联络)[^。；;！？!?]{0,16}(?:老师|讲师|教师|专家)|"
    r"(?:本人|我)(?:是|为|担任|作为)[^。；;，,！？!?]{0,12}(?:学员|听众|助教|组织者|会务人员)|"
    r"(?:本人|我)[^。；;，,！？!?]{0,16}(?:旁听|听课|参训)"
)
OTHER_TEACHER = re.compile(
    r"(?:他人|别人|同事|其他讲师|其他老师)[^。；;，,！？!?]{0,32}(?:主讲|讲授|授课|讲过|课程)|"
    r"(?:由)(?!(?:本人|我|该讲师))[^。；;，,！？!?]{1,12}(?:主讲|讲授|授课)|"
    r"(?:特邀|外聘|外部|合作方|其他|另一位)[^。；;，,！？!?]{0,24}(?:老师|讲师|教师|专家)[^。；;，,！？!?]{0,16}(?:主讲|讲授|授课)|"
    r"(?:主讲|讲授|授课)(?:老师|讲师|教师|人员)(?:是|为|[:：])(?!(?:本人|我|该讲师))[^。；;，,！？!?]{1,20}|"
    r"(?:独立|实际|亲自)?主讲(?:老师|讲师|教师|人员|教员|人)?(?:是|为|[:：])(?!(?:本人|我|该讲师)(?:$|[，,。；;！？!?\r\n]))[^。；;，,！？!?]{1,24}"
)
PLANNED = re.compile(r"xxx|替换个人照片|筹备|未主讲|未讲授|拟开设|计划开设|拟主讲|拟讲授|计划主讲|计划讲授|计划授课|拟任讲师", re.I)
EXCLUSION_START = re.compile(r"^(?:排除|不考虑|不要|无需|不需要|不包括|不包含|不涉及|不接受|而不是|而非)")
EXCLUSION_END = re.compile(
    r"(?:不计(?:入|作[^，,。；;]{0,16})?|不算(?:已完成|授课|主讲|教学|经历|经验|证据)?|"
    r"不满足(?:要求|条件)?|不作为(?:本题)?(?:证据|经历|经验)|不能作为(?:本题)?证据|"
    r"不是(?:本题|授课|教学|主讲)?证据)[。.!！?？]*$"
)
FACT_SCOPE_VERSION = "independent_facts_v2"
PROFILE_SCOPE_VERSION = "independent_profiles_v1"
PROFILE_KIND = "leading_profile_list_v1"
PROFILE_ITEM = re.compile(r"([✓✔☑•●▪])[\t ]*(\S(?:[^\r\n]{0,78}\S)?)")
PROFILE_REFERENCE = re.compile(r"以上|上述|上列|前述|前页|这些|此处|这里|本页|全文|所有|全部|以下|下列|后文|后页|开头|顶部|上面的?|前面的?|勾选|清单|列表|列项|模板|范文|样例|转述|引用|摘录")
PROFILE_STATEMENT = re.compile(r"^(?:本人|我|该讲师|该教师|他人|别人|同事)|主讲|讲授|授课|^(?:(?:已(?:经)?|曾(?:经)?|独立|亲自|计划|拟|待|未|准备|将|希望|打算)){0,3}(?:完成|参加|参与|参训|听课|取得|获得|持有|通过|颁发|授予|开设|开展|举办)|履历|经历|经验|简历|属于|来自|(?:本|所在|供职)(?:团队|机构|公司|单位)|(?:团队|机构|公司)(?:的|能力|专业|擅长|精通|领域)")
REFERENCE_QUALIFICATION = re.compile(
    r"(?:以下|下列|后文|后页|本页|所有|全部|上述|前述|这些)[^。；;！？!?]{0,30}(?:计划|拟开|待开|筹备|仅参训|仅参加|参训记录)|"
    r"(?:计划|拟开|筹备|参训|听课|他人|别人|其他老师|其他讲师)[^。；;！？!?]{0,30}(?:以下|下列|后文|后页|本页|所有|全部|上述|前述|这些)")
NAMED_TEACHER = re.compile(r"([\u4e00-\u9fff]{1,4})(?:老师|讲师)[^。；;，,！？!?]{0,12}(?:主讲|讲授|授课)")
ROLE_CONTEXT = re.compile(
    r"(?:担任|作为|任|负责)(?:培训|教学|授课|课堂|课程)?(?:助教|助理(?!讲师|教授)|会务|学员|听众)|"
    r"(?:本人|我|该讲师|该老师)[^。；;，,\r\n]{0,16}(?:(?:是|为)(?:培训|教学|授课|课堂|课程)?(?:助教|助理(?!讲师|教授))|旁听|参训|听课|未亲自(?:主讲|讲授|授课)|未实际(?:主讲|讲授|授课))|"
    r"(?:本人角色|授课角色|项目角色|担任角色|角色)[:：](?:助教|助理|旁听|学员|会务)"
)
INDEPENDENT_DATED_TEACHING = re.compile(
    r"^(?:(?:本人|我|该讲师)(?:另于|另在|于)?|另于|另在|另外于|另行于)?"
    r"(20\d{2})年(?:\d{1,2}月)?(?:本人|我|该讲师)?(?:独立|亲自)(?:主讲|讲授|授课)"
)
FACT_CONTEXT_RISK = re.compile(
    r"(?:团队|机构|公司)(?:业绩|案例|完成|授课|主讲|累计)|"
    r"(?:^|[\n，,。；;])(?:本)?(?:团队|机构|公司)(?:已|曾|取得|持有|完成)|"
    r"(?:以下|下列|上述|这些|全部|本页|本次|后续|所有)[^。；;！？!?]{0,24}(?:拟|计划|筹备|参训|听课|助教|他人|团队)|"
    r"(?:拟|计划|参训|听课|助教|他人|团队)[^。；;！？!?]{0,24}(?:以下|下列|上述|这些|全部|本页|所有|课程清单)|"
    r"(?:其中|前者|后者|该课程|此课程|该经历|上述|以上|前述)[^。；;！？!?]{0,60}(?:未|不|仅|只|待|他人|其他|团队)"
)


def _fact_context_safe(text):
    normalized = _normalized(text)
    return not (_global_qualification(text) or FACT_CONTEXT_RISK.search(normalized) or ORGANIZATION_EVENT.search(normalized)
                or _other_teacher(normalized))


def _other_teacher(text):
    text = _polarity_view(text)
    return bool(OTHER_TEACHER.search(text) or any(not re.sub(r"(?:担任|作为|是|为)$", "", m.group(1)).endswith(("该", "此", "本人", "我"))
                                                and m.group(1) != "同一" for m in NAMED_TEACHER.finditer(text)))


def _completed_personal_event(text):
    for match in PERSONAL_EVENT.finditer(text):
        if RECIPIENT_RISK.search(match.group("audience") or ""):
            continue
        if match.group("action").startswith("完成") and EVENT_OBJECT_RISK.search(match.group("action")):
            continue
        if EVENT_TAIL_RISK.match(text[match.end():]):
            continue
        if re.search(r"已|曾|实际", match.group("state")) or re.search(r"完成|讲完", match.group("action")):
            return True
    for match in REVERSE_PERSONAL.finditer(text):
        before = text[:match.start()].rstrip("，,。；;！？!?\r\n ")
        previous = re.split(r"[，,。；;！？!?\r\n]", before)[-1]
        if re.search(r"(?:已|已经)(?:结课|讲完)$|(?:课堂|课程)(?:已|已经)?(?:结课|结束)$", previous):
            return True
    return _referenced_personal_completion(text)


def _declaration_qualification(text):
    if not re.search(r"(?:开设|开办|举办)(?:了)?《|(?:学习内容|培训主题|训练内容|课程内容)(?:定为|确定为|为|是)《", text):
        return False
    return bool(re.search(r"(?:如果|假如|假使|假设|若|倘若|转述|引用|摘录|据说|原话)[^。；;！？!?\r\n]{0,90}《|(?:明[天日年]|下(?:周|月|年)|即将|将要|(?:将|拟|计划|打算|准备)(?=为|给|面向|开设|开办|举办))[^。；;！？!?\r\n]{0,70}(?:开设|开办|举办)|(?:学员|学生|对象|人员)[^。；;！？!?\r\n]{0,45}(?:而非|而不是|并非)|(?:为|给|面向)非[^《》。；;]{1,40}(?:开设|开办|举办)", text))


def _referenced_personal_completion(text):
    """A paragraph-local entity reference, not proof of eligibility.

    The source declarations and completed personal predicate must coexist.
    All other role/negation checks still inspect their original full context.
    """
    for paragraph in re.split(r"\r?\n[\t ]*\r?\n", text):
        if _declaration_qualification(paragraph):
            continue
        titles = set(re.findall(r"《([^《》\r\n]{2,90})》", paragraph))
        if len(titles) != 1 or not re.search(r"(?:开设|开办|举办)(?:了)?《|(?:学习内容|培训主题|训练内容|课程内容)(?:定为|确定为|为|是)《", paragraph):
            continue
        if any(REFERENCED_PERSONAL_COMPLETION.fullmatch(clause.strip()) for clause in re.split(r"[，,。；;！？!?\r\n]+", paragraph)):
            return True
    return False


def _support_only(text):
    facts = list(SUPPORT_FACT.finditer(text))
    return bool(facts and (not _completed_personal_event(text) or any(
        re.match(r"(?:本人|我|该讲师)(?:已经|已)?(?:仅|只)(?:负责|承担|完成)", fact.group())
        for fact in facts)))


def _content_exclusion_spans(text):
    """A quoted, completed course may exclude an explicit different content noun.

    Do not understand arbitrary negation: unresolved references, actor/record
    restrictions, or overlapping course names remain blocked. The entire clause
    is withheld from every positive quote; Java separately checks query conflicts.
    """
    if not _completed_personal_event(_normalized(text)):
        return []
    result = []
    for clause in re.finditer(r"[^\r\n，,。；;！？!?]+", text):
        raw = clause.group().strip()
        found = CONTENT_EXCLUSION.fullmatch(_normalized(raw))
        if not found or re.search(r"上述|以上|以下|该|这|本课|所有|全部|本人|他人|主讲|讲授|授课|教学|记录|经历|未|不|没有", found.group(1)):
            continue
        title_matches = list(re.finditer(r"《([^》\r\n]{2,80})》", text[:clause.start()]))
        titles = [m.group(1) for m in title_matches]
        omitted = found.group(1)
        if len(set(titles)) != 1 or any(titles[0][i:i+2] in omitted for i in range(len(titles[0])-1)):
            continue
        title = title_matches[-1]
        sentence_start = max((text.rfind(ch, 0, title.start()) for ch in "。；;\r\n"), default=-1) + 1
        ends = [at for ch in "。；;\r\n" if (at := text.find(ch, title.end(), clause.start())) >= 0]
        sentence_end = min(ends) if ends else clause.start()
        if not _completed_personal_event(_normalized(text[sentence_start:sentence_end])):
            continue
        left = clause.start() + len(clause.group()) - len(clause.group().lstrip())
        result.append((left, left + len(raw)))
    return result


def _same_teacher_context_safe(unit, source):
    # '同一' is a reference, not a person's name. An explicit other actor in
    # the surrounding source makes that reference unresolved, not personal.
    return "同一讲师" not in unit or not _other_teacher(_normalized(source))


def _method_contrast_spans(text):
    """Omit a bounded rejected feedback utterance, never its source context.

    This is only method content following explicit completed personal teaching.
    Quoted role/history claims and ambiguous continuations cannot use this path.
    """
    if '而不是' not in text and '而非' not in text:
        return []
    result = []
    quote = r'(?:“[^“”。；;！？!?\r\n]{1,24}”|「[^「」。；;！？!?\r\n]{1,24}」|"[^"。；;！？!?\r\n]{1,24}")'
    contrast = re.compile(r'而(?:不是|非)(?:仅仅|只是|仅|只)?(?:评价|说|回答|反馈)' + quote)
    for match in re.finditer(r'[^\r\n，,。；;！？!?]+', text):
        raw = match.group()
        clause = raw.strip()
        if not contrast.fullmatch(clause):
            continue
        if re.search(r'本人|我|他|她|讲师|老师|教师|授课|主讲|讲授|教学|经历|记录|完成|证明|否认|否定|没有|未曾|尚未|计划|拟|模板|引用|转述', clause):
            continue
        at = match.start() + len(raw) - len(raw.lstrip())
        sentence_start = max((text.rfind(c, 0, at) for c in '。；;！？!?\r\n'), default=-1) + 1
        before = text[sentence_start:at].strip()
        if not re.fullmatch(r'(?:教学|授课|课堂|培训|带教)?(?:方法|步骤|流程)(?:说明)?[^。；;！？!?\r\n]{2,140}[，,]', before):
            continue
        if not re.search(r'(?:指出|说明|解释|反馈)[^，,。；;！？!?]{2,50}', before) or re.search(
                r'本人|我|同事|他人|别人|讲师|老师|教师|没有|尚未|未曾|不是|并非|否认|否定|计划|筹备|假设|假如|如果|模板|示例|原话|转述|引用|[“”"「」]', before):
            continue
        paragraph_start = max((m.end() for m in re.finditer(r'\r?\n[\t ]*\r?\n', text)
                               if m.end() <= sentence_start), default=0)
        prior = text[paragraph_start:sentence_start]
        if not _completed_personal_event(prior) or re.search(r'假设|假如|如果|模板|原话|转述|引用', prior):
            continue
        end = at + len(clause)
        following = text[end:].lstrip()
        if following and following[0] not in '。；;！？!?':
            continue
        result.append((at, end))
    return result


def _nonproof_spans(text):
    """Only whole, unambiguous clauses beside explicit completed teaching.

    These spans remain in source context but cannot be positive retrieval/topic
    proof. No personal denial, double negation or unknown task is rewritten.
    """
    if not _completed_personal_event(_normalized(text)):
        return []
    result = _content_exclusion_spans(text)
    for match in re.finditer(r"[^\r\n，,。；;！？!?]+", text):
        raw = match.group().strip()
        breaks = list(re.finditer(r"\r?\n[\t ]*\r?\n", text))
        left_context = max((m.end() for m in breaks if m.end() <= match.start()), default=0)
        right_context = min((m.start() for m in breaks if m.start() >= match.end()), default=len(text))
        organization = ORGANIZATION_WORK_EXCLUSION.fullmatch(_normalized(raw)) and _referenced_personal_completion(text[left_context:right_context])
        if OTHER_NONTEACHING_CLAUSE.fullmatch(_normalized(raw)) or TASK_RESTRICTION_CLAUSE.fullmatch(_normalized(raw)) or organization:
            left = match.start() + len(match.group()) - len(match.group().lstrip())
            result.append((left, left + len(raw)))
    result.extend(_method_contrast_spans(text))
    from learner_artifact import artifact_context_spans
    result.extend(artifact_context_spans(text, _completed_personal_event))
    return sorted(set(result))


def _polarity_view(text):
    chars = list(text)
    for left, right in _nonproof_spans(text):
        chars[left:right] = " " * (right - left)
    return "".join(chars)


def _affirmative_spans(text):
    omitted = _nonproof_spans(text)
    if not omitted:
        return [(0, len(text))]
    result, begin = [], 0
    for left, right in omitted + [(len(text), len(text))]:
        while begin < left and (text[begin].isspace() or text[begin] in "，,。；;！？!?"):
            begin += 1
        end = left
        # A known non-proof cut can leave its comma separator dangling. Trim
        # that separator without reconstructing or changing the retained quote.
        while end > begin and (text[end - 1].isspace() or (left < len(text) and text[end - 1] in "，,")):
            end -= 1
        if end - begin >= 2:
            result.append((begin, end))
        begin = right
    return result


def role_safe_at(source, start, end, context_start=0, context_end=None):
    """A later date/explicit new personal event may reset an earlier role.

    Unknown or same-event role limitations cannot be dropped with a line break.
    All restrictions in the context are checked, including later statements.
    """
    context_end = len(source) if context_end is None else context_end
    unit = _normalized(source[start:end].strip())
    if not _same_teacher_context_safe(unit, source):
        return False
    restrictions = [(context_start+m.start(), context_start+m.end(), m.group())
                    for m in re.finditer(r"[^\r\n]+", source[context_start:context_end])
                    if ROLE_CONTEXT.search(_normalized(m.group())) or _support_only(_normalized(m.group()))]
    if not restrictions:
        return True
    personal = INDEPENDENT_DATED_TEACHING.match(unit)
    if not personal:
        return False
    year = personal.group(1)
    new_event = bool(re.match(r"^(?:本人|我|该讲师)?(?:另于|另在|另外于|另行于)", unit))
    for left, right, raw in restrictions:
        if left < end and right > start:
            return False
        years = set(re.findall(r"20\d{2}(?=年)", _normalized(raw)))
        if len(years) == 1 and year not in years:
            continue
        if right <= start and new_event and not years:
            continue
        return False
    return True


def _independent_fact_line(text, ignore_participation=False):
    """A complete affirmative physical line, not an extracted positive clause."""
    normalized = _normalized(text)
    if not 2 <= len(text) <= MAX_CHARS:
        return False
    if _nonproof_spans(text):
        return False  # The whole line would reinsert a deliberately excluded task.
    if (NEGATED_OR_UNCONFIRMED.search(normalized) or NO_EXPERIENCE.search(normalized)
            or PLANNED.search(normalized) or PERSONAL_FIELD.search(normalized)
            or _other_teacher(normalized) or (not ignore_participation and PARTICIPATION.search(normalized))
            or NON_TEACHING_ROLE.search(normalized) or FACT_CONTEXT_RISK.search(normalized)
            or ORGANIZATION_EVENT.search(normalized) or _support_only(normalized)):
        return False
    return bool(_completed_personal_event(normalized) or re.search(r"主讲|讲授|授课|宣讲|课堂|擅长|精通|熟悉", normalized))


def _capability_sentence_spans(text):
    """Complete capability assertions only; full line and section remain context."""
    padding = len(text) - len(text.lstrip())
    text = text.strip()
    normalized = _normalized(text)
    if (not PARTICIPATION.search(normalized) or _independent_fact_line(text)
            or not _independent_fact_line(text, ignore_participation=True)
            or not _fact_context_safe(text)
            or re.search(r'假设|假如|如果|倘若|转述|引用|摘录|据说|示例|样例|范例|模板|原话|同事|他人|别人|他们|她|(?:^|[，,。；;])他|[“”"]', normalized)):
        return []
    spans = []
    for match in re.finditer(r"[^。；;！？!?\r\n]+[。；;！？!?]?", text):
        raw = match.group()
        sentence = raw.strip()
        if (sentence == text.strip() or not re.match(
                r"^(?:同时|此外|另外|并且)?(?:本人|我|该讲师|该教师|该老师)?(?:作为[^，,。；;！？!?]{2,40}讲师[，,])?(?:擅长|精通|熟悉)", _normalized(sentence))
                or not _independent_fact_line(sentence) or _personal_teaching(_normalized(sentence))):
            continue
        left = match.start() + len(raw) - len(raw.lstrip())
        spans.append((padding+left, padding+left+len(sentence)))
    return spans


def independent_fact_sections(text):
    """Recover bounded facts, never a whole introduction or unordered page.

    Every line is checked against its complete containing section. A backward
    course list is accepted only as an adjacent run of standalone quoted titles
    immediately followed by a catalog heading, not by arbitrary title search.
    """
    if _global_qualification(text):
        return []
    lines = list(re.finditer(r"[^\r\n]*(?:\r\n|\r|\n|$)", text))
    boundaries = [(i, m) for i, m in enumerate(lines)
                  if any(p.fullmatch(_normalized(m.group().strip())) for p in
                         (POSITIVE_HEADING, GROUP_HEADING, EXCLUDED_HEADING, OTHER_HEADING))]
    result = []

    def append(line, heading_line, begin, end, kind):
        raw = line.group()
        left = line.start() + len(raw) - len(raw.lstrip())
        right = line.start() + len(raw.rstrip())
        h = heading_line.group()
        result.append(dict(heading=h.strip(), heading_start=heading_line.start()+len(h)-len(h.lstrip()),
                           heading_end=heading_line.start()+len(h.rstrip()), source_start=left,
                           source_end=right, context_start=begin, context_end=end, kind=kind,
                           offset_unit="unicode_code_point"))

    for index, (line_index, heading_line) in enumerate(boundaries):
        heading = _normalized(heading_line.group().strip()).rstrip(":：")
        end = boundaries[index+1][1].start() if index+1 < len(boundaries) else len(text)
        end_index = boundaries[index+1][0] if index+1 < len(boundaries) else len(lines)
        start = heading_line.end()
        context = text[start:end]
        role_restricted = bool(ROLE_CONTEXT.search(_normalized(text)))
        eligible = heading in ("个人介绍", "个人职责", "本人经历") or (role_restricted and SCORING_HEADING.fullmatch(heading))
        if not eligible or not _fact_context_safe(context):
            continue
        for line in lines[line_index+1:end_index]:
            if (_independent_fact_line(line.group().strip())
                    and role_safe_at(text, line.start(), line.end())):
                append(line, heading_line, start, end, "professional_fact_v2")
            elif (not role_restricted and not NEGATED_OR_UNCONFIRMED.search(_normalized(context))
                    and not NO_EXPERIENCE.search(_normalized(context))
                    and not PROFILE_REFERENCE.search(_normalized(context))
                    and role_safe_at(text, line.start(), line.end())):
                for a, b in _capability_sentence_spans(line.group()):
                    append(line, heading_line, start, end, "capability_sentence_v1")
                    result[-1]["source_start"] = line.start()+a
                    result[-1]["source_end"] = line.start()+b
        # XML shape order sometimes places an entire title run immediately
        # before its heading. No geometry is inferred beyond that bounded run.
        if role_restricted or index+1 >= len(boundaries):
            continue
        next_heading = boundaries[index+1][1]
        if not re.fullmatch(r"(?:主讲课程|精品课程|课程列表)[:：]?", _normalized(next_heading.group().strip())):
            continue
        if (re.search(r"拟|计划|筹备|参训|听课|他人|别人|助教|助理", _normalized(context))
                or re.search(r"[（(]筹[）)]", _normalized(context))
                or NEGATED_OR_UNCONFIRMED.search(_normalized(context)) or PARTICIPATION.search(_normalized(context))):
            continue
        run = []
        for line in reversed(lines[line_index+1:end_index]):
            raw = line.group().strip()
            if not raw:
                break  # a page/paragraph gap is not a safe visual association
            if not re.fullmatch(r"《[^》\r\n]{2,80}》", _normalized(raw)):
                break
            run.append(line)
        if len(run) >= 2:
            for line in reversed(run):
                append(line, next_heading, start, end, "course_catalog_entry_v2")
    return result


def _normalized(text):
    """Normalization is for classification/embedding, never for source quotes."""
    return unicodedata.normalize("NFKC", text)


def _personal_teaching(text):
    if _arranged_teaching(text):
        return False
    if _completed_personal_event(text):
        return True
    for match in PERSONAL_TEACHING.finditer(text):
        if re.fullmatch(r"(?:主讲|讲授|授课)(?:老师|讲师|教师|人员)(?:是|为|[:：])(?:本人|我)", match.group()):
            return True
        statement = re.sub(r"^(?:本人|我|该讲师|该教师|该老师)", "", match.group())
        if not re.search(r"邀请|协助|配合|联络|(?:其他|另一|其他团队)|老师|讲师|教师|专家|顾问|同事|团队|机构|公司", statement):
            return True
    return False


def _arranged_teaching(text):
    return bool(re.search(r"(?:本人|我|该讲师|该教师)(?:(?:已(?:经)?|曾(?:经)?|实际|亲自)){0,5}"
                          r"(?:(?:为|给|面向)[^，,。；;！？!?《》\r\n]{1,40})?"
                          r"(?:安排|委托|聘请|邀请|联络|协调|请人)(?:他人|别人|同事|讲师|老师|专家)?"
                          r"(?:独立)?(?:主讲|讲授|授课)", text))


def _personal_metadata_line(text):
    """Ignore a standalone personal-only metadata line, never mixed evidence.

    Layout extractors commonly put demographic fields immediately above course
    text without a blank line. Those fields neither score nor make otherwise
    complete professional evidence incomplete. A mixed statement stays guarded.
    """
    if not PERSONAL_FIELD.search(text):
        return False
    if (NEGATED_OR_UNCONFIRMED.search(text) or NO_EXPERIENCE.search(text)
            or re.search(r"主讲|讲授|授课|课程|教学|培训|教授|专业|经验|经历|擅长|精通|熟练|客户|银行|机构|团队|未承担|未实施|未开展", text)):
        return False
    for part in re.split(r"[，,。;；]+", text):
        part = part.strip()
        if not part:
            continue
        field = PERSONAL_FIELD.match(part)
        if field is None:
            return False
        value = part[field.end():].strip()
        if not value or len(value) > 80 or re.search(r"[:：\r\n]", value):
            return False
    return True


NAMED_COURSE_DENIAL = re.compile(
    r"(?:本人|我)(?:没有|从未|未曾|尚未|未)(?:亲自)?(?:主讲|讲授)(?:过)?"
    r"《([^《》。；;，,！？!?\r\n]{2,80})》(?:这门课(?:程)?|课(?:程)?)?[。.!！]?"
)


def _global_qualification_view(normalized):
    """Classification-only view; original evidence and offsets stay untouched."""
    excluded, view = False, []
    for raw in normalized.splitlines():
        line = raw.strip()
        if any(p.fullmatch(line) for p in (POSITIVE_HEADING, GROUP_HEADING, EXCLUDED_HEADING, OTHER_HEADING)):
            excluded = bool(re.fullmatch(r"(?:未授课课程|未讲授课程)[:：]?", line))
        denial = NAMED_COURSE_DENIAL.fullmatch(line)
        if excluded and denial and not PROFILE_REFERENCE.search(denial.group(1)):
            view.append("")
        else:
            view.append(raw)
    for line in view:
        if (not any(p.fullmatch(line.strip()) for p in (POSITIVE_HEADING, GROUP_HEADING, EXCLUDED_HEADING, OTHER_HEADING))
                and PROFILE_REFERENCE.search(line)):
            return normalized
    return "\n".join(view)


def _global_qualification(text):
    """A later disclaimer must not be hidden by a new heading or blank page.

    Explicit personal denials and backward references are intentionally broad:
    resolving contradictory claims requires review, not a similarity score.
    Merely planning or attending a different section is not a global denial.
    """
    normalized = _global_qualification_view(_polarity_view(_normalized(text)))
    if re.search(r"(?:本人|我|该讲师)(?:仍|还)?(?:未完成|没有完成)(?:这门课(?:程)?|该课(?:程)?|本课(?:程)?)"
                 r"(?:的)?(?:教学|讲授|授课)(?:[。；;！？!?\r\n]|$)|"
                 r"(?:以下|下列|后文|后页|上述)(?:为|是)?(?:引用|转述|摘录)[^。；;\r\n]{1,60}"
                 r"(?:教学记录|授课经历|授课记录|履历)", normalized):
        return True
    return bool(REFERENCE_QUALIFICATION.search(normalized) or re.search(
        r"(?:以上|上述|上行|前述|以下|下列|后文|后页|全文|所有|全部|这些|这些记录|该课程|本次|同一项目|前页)[^。；;！？!?]{0,80}"
        r"(?:不代表|不表示|不是|并非|非本人|没有|未曾|从未|尚未|未承担|未主讲|未讲授|未由|均未|都未|待核|待确认|(?:只是|仅为|均为|均属)(?:团队|机构|宣传|样例))|"
        r"(?:本人|我|该讲师)[^。；;，,！？!?]{0,20}(?:没有|从未|未曾|未承担|未主讲|未讲授|并非|不是)[^。；;！？!?]{0,40}"
        r"(?:授课|主讲|讲授|教学|经历|完成)|"
        r"(?:不代表|不表示)[^。；;！？!?]{0,20}(?:本人|个人|我)[^。；;！？!?]{0,20}(?:授课|教学|经历)", normalized))


def leading_profile_sections(text):
    """Recover one complete initial noun-label list with no invented heading.

    This is content self-report only. Full-source roles and unresolved reference
    operators still apply, and the rest of an incomplete resume stays incomplete.
    """
    inspected = _polarity_view(_normalized(text))
    references = "\n".join(line for line in inspected.splitlines()
                           if not any(p.fullmatch(line.strip()) for p in
                                      (POSITIVE_HEADING, GROUP_HEADING, EXCLUDED_HEADING, OTHER_HEADING)))
    if (_global_qualification(text) or not _fact_context_safe(text)
            or NEGATED_OR_UNCONFIRMED.search(inspected) or NO_EXPERIENCE.search(inspected)
            or re.search(r"未主讲|未讲授|未承担|未实施|未开展", inspected) or _declaration_qualification(inspected)
            or PROFILE_REFERENCE.search(references)):
        return []
    items, marker, closed, gap = [], None, False, False
    for line in re.finditer(r"[^\r\n]*(?:\r\n|\r|\n|$)", text):
        raw = line.group().strip()
        normalized = _normalized(raw)
        if not raw:
            if items:
                gap = True
            continue
        if not items and _personal_metadata_line(normalized):
            continue
        if any(p.fullmatch(normalized) for p in (POSITIVE_HEADING, GROUP_HEADING, EXCLUDED_HEADING, OTHER_HEADING)):
            closed = len(items) >= 2 and not (GROUP_HEADING.fullmatch(normalized) or EXCLUDED_HEADING.fullmatch(normalized))
            break
        item = PROFILE_ITEM.fullmatch(normalized)
        if gap or not item or len(items) >= 12:
            return []
        value = item.group(2)
        if (not 2 <= len(value) <= 80 or re.search(r"[:：，,。；;！？!?]", value)
                or PROFILE_STATEMENT.search(value) or PERSONAL_FIELD.search(normalized)
                or _personal_teaching(normalized) or _completed_personal_event(normalized)
                or NON_TEACHING_ROLE.search(normalized) or _other_teacher(normalized)):
            return []
        if marker is not None and marker != item.group(1):
            return []
        marker = item.group(1)
        items.append((line.start()+len(line.group())-len(line.group().lstrip()),
                      line.start()+len(line.group().rstrip())))
    if not closed:
        return []
    start, end = items[0][0], items[-1][1]
    if end-start > MAX_CHARS or not role_safe_at(text,start,end):
        return []
    return [dict(heading="", heading_start=start, heading_end=start, source_start=start, source_end=end,
                 kind=PROFILE_KIND, context_start=0, context_end=len(text), offset_unit="unicode_code_point")]


def scoring_sections(text):
    """Return independently bounded professional sections, not whole resumes.

    Every returned body is scanned with its complete subject/qualification
    context. A section with any ambiguous role is omitted as a whole. Returned
    offsets refer to the original text; no paraphrase or fabricated evidence.
    """
    if _global_qualification(text):
        return []
    sections, active = [], None

    def close(end):
        if active is None:
            return
        if ROLE_CONTEXT.search(_global_qualification_view(_normalized(text))):
            return  # Only a verified distinct personal event may reset a role.
        raw = text[active["source_start"]:end]
        # Only an entire standalone template instruction may be ignored.
        inspected = "\n".join(line for line in raw.splitlines()
                              if not TEMPLATE_LINE.fullmatch(_normalized(line.strip()))
                              and not _personal_metadata_line(_normalized(line.strip())))
        normalized = _polarity_view(_normalized(inspected))
        if not normalized.strip():
            return
        if not _same_teacher_context_safe(normalized, text):
            return
        if (NEGATED_OR_UNCONFIRMED.search(normalized) or NO_EXPERIENCE.search(normalized)
                or PLANNED.search(normalized) or PERSONAL_FIELD.search(normalized)
                or _other_teacher(normalized) or ROLE_CONTEXT.search(normalized) or _support_only(normalized)
                or ORGANIZATION_EVENT.search(normalized) and not _completed_personal_event(normalized)):
            return
        if ((PARTICIPATION.search(normalized) or NON_TEACHING_ROLE.search(normalized))
                and not _personal_teaching(normalized)):
            return
        # Shared actor/measurement clauses cannot become independent records.
        if re.search(r"团队|机构业绩|公司案例|其中|上述|以上|前述|同一项目|本次|部分|剩余|前者|后者|其他.{0,12}(?:主讲|讲授|授课)", normalized):
            return
        sections.append(dict(active, source_end=end, offset_unit="unicode_code_point"))

    for line in re.finditer(r"[^\r\n]*(?:\r\n|\r|\n|$)", text):
        heading = line.group().strip()
        normalized = _normalized(heading)
        if (POSITIVE_HEADING.fullmatch(normalized) or GROUP_HEADING.fullmatch(normalized)
                or EXCLUDED_HEADING.fullmatch(normalized) or OTHER_HEADING.fullmatch(normalized)):
            close(line.start())
            active = None
            if SCORING_HEADING.fullmatch(normalized):
                active = dict(heading=heading, heading_start=line.start() + len(line.group()) - len(line.group().lstrip()),
                              heading_end=line.start() + len(line.group().rstrip()), source_start=line.end())
    close(len(text))
    return sections + independent_fact_sections(text) + leading_profile_sections(text)


def _course_line_spans(text, heading):
    """Complete short catalog lines may split after reviewing the whole section.
    A wrapped sentence, role qualification, or unbounded long line stays review.
    A course catalog is retrieval evidence, never completed teaching history.
    """
    if not heading or not SCORING_HEADING.fullmatch(_normalized(heading)):
        return []
    spans = []
    for match in re.finditer(r"[^\r\n]+", text):
        raw = match.group().strip()
        normalized = _polarity_view(_normalized(raw))
        if (len(raw) > MAX_CHARS or re.search(r"其中|上述|以上|前述|本次|该课程|同一|部分|剩余|但是|然而|团队|机构|公司", normalized)
                or (re.search(r"[，,；;。！？!?]", normalized) and not _personal_teaching(normalized))):
            return []
        if len(raw) < 2:
            continue
        left = match.start() + len(match.group()) - len(match.group().lstrip())
        spans.append((left, left + len(raw)))
    return spans if len(spans) > 1 else []


def _safe_sentence_spans(text):
    """Split a long paragraph only when every sentence stands on its own.

    Paragraph-wide guards have already run. Sentences need a direct teaching
    subject or an explicit numbered-item boundary; items remain retrieval, not
    personal teaching proof. Cross-references and contrasting/partial claims
    stay intact and require review instead of becoming separate facts.
    """
    normalized = _normalized(text)
    if (re.search(r"该课程|本次|上述|其中|剩余|然而|但是|前者|后者|其他|部分|团队|机构|公司", normalized)
            or NON_TEACHING_ROLE.search(normalized) or PARTICIPATION.search(normalized)):
        return []
    spans = []
    for match in re.finditer(r"[^。！？!?；;\r\n]+[。！？!?；;]?", text):
        left, right = match.start(), match.end()
        while left < right and text[left].isspace():
            left += 1
        while right > left and text[right - 1].isspace():
            right -= 1
        if right == left:
            continue
        sentence = text[left:right]
        normalized_sentence = _normalized(sentence)
        numbered_item = re.match(r"^(?:第\d+(?:项|条|讲|章|节)[:：]|\d+[、.]|[（(]\d+[)）])", normalized_sentence)
        if len(sentence) > MAX_CHARS or not (_personal_teaching(normalized_sentence) or numbered_item):
            return []
        if spans and right - spans[-1][0] <= MAX_CHARS:
            spans[-1] = (spans[-1][0], right)
        else:
            spans.append((left, right))
    return spans if len(spans) > 1 else []


def requirement_units(text):
    """Separate explicit exclusions without pretending to enforce their meaning.

    Only syntactically explicit exclusion clauses are removed from retrieval
    targets. Ambiguous negation, including a double negative, still fails closed.
    The caller must verify exclusions before treating a retrieval as a match.
    """
    if PERSONAL_FIELD.search(_normalized(text)):
        raise ValueError("personal_requirement_requires_review")
    from requirement_content import is_instructional_status_comparison
    from requirement_polarity import postposed_exclusion_spans
    postposed = postposed_exclusion_spans(text)
    positive, exclusions = [], [(left, text[left:right]) for left, right in postposed]
    for match in re.finditer(r"[^\r\n。！？!?;；，,]+", text):
        if any(left < match.end() and right > match.start() for left, right in postposed):
            continue
        raw = match.group().strip()
        if len(raw) < 2:
            continue
        normalized = _normalized(raw)
        start = EXCLUSION_START.search(normalized)
        end = EXCLUSION_END.search(normalized)
        # A second negative in a prefix exclusion changes its meaning. Never
        # rewrite '不需要没有经验' as '需要有经验' by guessing.
        if start and (NEGATED_OR_UNCONFIRMED.search(normalized[start.end():]) or NO_EXPERIENCE.search(normalized[start.end():])):
            if start.group() not in ("而不是", "而非"):
                raise ValueError("complex_requirement_requires_review")
        if start or end:
            exclusions.append((match.start(), raw))
            continue
        # A complete positive teaching objective may compare verified and
        # unverified information. Its nominal status objects are not a claim
        # that the teacher is unverified. Keep the original clause unchanged;
        # this exception is never used by resume evidence extraction.
        if (NEGATED_OR_UNCONFIRMED.search(normalized) or NO_EXPERIENCE.search(normalized)) and not is_instructional_status_comparison(normalized):
            raise ValueError("complex_requirement_requires_review")
        if len(raw) > MAX_CHARS:
            raise ValueError("context_unit_too_long_requires_review")
        left = match.start() + len(match.group()) - len(match.group().lstrip())
        right = left + len(raw)
        # Commas are useful for detecting an exclusion, not automatically new
        # semantic facets. Keep adjacent affirmative clauses and their subject
        # together unless there is a real sentence/line/semicolon boundary.
        if positive and re.fullmatch(r"[，,\t ]+", text[positive[-1][1]:left]) and right - positive[-1][0] <= MAX_CHARS:
            positive[-1] = (positive[-1][0], right)
        else:
            positive.append((left, right))
    if not positive:
        raise ValueError("positive_requirement_required")
    return {"positive_fragments": list(dict.fromkeys(text[left:right] for left, right in positive)),
            "exclusions": list(dict.fromkeys(raw for _, raw in sorted(exclusions))),
            "requires_review": bool(exclusions), "exclusions_enforced": False,
            "review_reasons": ["exclusions_require_hard_verification"] if exclusions else []}


def evidence_units(text):
    """Keep complete paragraphs, their subjects, and adjacent qualifications.

    Punctuation and single PDF line wraps are not evidence boundaries. Splitting
    at either can launder 'course delivered. I did not teach it' into a positive
    claim. Blank lines and explicit section headings establish boundaries; an
    ambiguous or oversized paragraph is withheld with a visible review reason.
    No slices are fabricated by joining non-contiguous source text.
    """
    units, reasons, omissions = [], [], []
    excluded_section = False
    heading = None
    start = end = None

    def flush():
        nonlocal start, end
        if start is None:
            return
        left, right = start, end
        start = end = None
        while left < right and text[left].isspace():
            left += 1
        while right > left and text[right - 1].isspace():
            right -= 1
        raw = text[left:right]
        normalized = _polarity_view(_normalized(raw))
        if len(raw) < 2 or excluded_section:
            return
        if PERSONAL_FIELD.search(normalized):
            reasons.append("mixed_personal_fields")
            omissions.append((left, right))
            return
        if NEGATED_OR_UNCONFIRMED.search(normalized) or NO_EXPERIENCE.search(normalized) or PLANNED.search(normalized) or _declaration_qualification(normalized):
            reasons.append("qualified_or_unconfirmed_paragraph")
            omissions.append((left, right))
            return
        personal_teaching = _personal_teaching(normalized)
        if (_arranged_teaching(normalized) or _other_teacher(normalized) or ROLE_CONTEXT.search(normalized) or _support_only(normalized)
                or ORGANIZATION_EVENT.search(normalized) and not _completed_personal_event(normalized)
                or not _same_teacher_context_safe(normalized, text)):
            reasons.append("non_teaching_or_ambiguous_role")
            omissions.append((left, right))
            return
        # Attendance records can corroborate an explicit personal teaching
        # statement. The word '签到' alone does not make the person an organizer.
        if not personal_teaching and (PARTICIPATION.search(normalized) or NON_TEACHING_ROLE.search(normalized)):
            reasons.append("non_teaching_or_ambiguous_role")
            omissions.append((left, right))
            return
        # A personal mention may precede another person's teaching. Keep it out
        # of retrieval instead of crediting the nearest teaching verb to '我'.
        if re.search(r"(?:本人|我)[^。；;！？!?]{0,32}(?:邀请|协助|配合|联络)[^。；;！？!?]{0,16}(?:老师|讲师|教师|专家)[^。；;！？!?]{0,16}(?:主讲|讲授|授课)", normalized):
            reasons.append("non_teaching_or_ambiguous_role")
            omissions.append((left, right))
            return
        spans = _affirmative_spans(raw)
        context_status = "paragraph_preserved" if spans == [(0, len(raw))] else "self_contained_sentences_after_paragraph_review"
        bounded = []
        for span_start, span_end in spans:
            if span_end - span_start <= MAX_CHARS:
                bounded.append((span_start, span_end))
                continue
            split = _safe_sentence_spans(raw[span_start:span_end]) or _course_line_spans(raw[span_start:span_end], heading)
            if not split:
                reasons.append("context_unit_too_long")
                omissions.append((left, right))
                return
            bounded.extend((span_start + a, span_start + b) for a, b in split)
            context_status = "self_contained_sentences_after_paragraph_review"
        spans = bounded
        for local_start, local_end in spans:
            units.append({"text": raw[local_start:local_end],
                          "source_start": left + local_start, "source_end": left + local_end,
                          "paragraph_start": left, "paragraph_end": right,
                          "offset_unit": "unicode_code_point", "kind": "retrieval_only",
                          "context_status": context_status, "section_heading": heading})

    for line in re.finditer(r"[^\r\n]*(?:\r\n|\r|\n|$)", text):
        raw = line.group().rstrip("\r\n")
        stripped = raw.strip()
        normalized = _normalized(stripped)
        if not stripped:
            flush()
        elif _personal_metadata_line(normalized) or TEMPLATE_LINE.fullmatch(normalized):
            flush()
        elif EXCLUDED_HEADING.fullmatch(normalized) or GROUP_HEADING.fullmatch(normalized):
            flush()
            excluded_section = True
            heading = stripped
        elif POSITIVE_HEADING.fullmatch(normalized):
            flush()
            excluded_section = False
            heading = stripped
        elif OTHER_HEADING.fullmatch(normalized):
            flush()
            excluded_section = False
            heading = stripped
        else:
            if start is None:
                start = line.start()
            end = line.start() + len(raw)
    flush()
    deduped, seen = [], set()
    for unit in units:
        key = _normalized(unit["text"])
        if key not in seen:
            deduped.append(unit)
            seen.add(key)
    available_sections = scoring_sections(text)
    if len(available_sections) > 64:
        raise ValueError("capacity_limit")
    # Independent facts are rechecked using the entire original section, not
    # obtained by cutting a positive prefix out of an omitted paragraph.
    for section in available_sections:
        if "kind" not in section:
            continue
        left, right = section["source_start"], section["source_end"]
        if not any(u["source_start"] == left and u["source_end"] == right for u in deduped):
            deduped.append(dict(text=text[left:right], source_start=left, source_end=right,
                                paragraph_start=section["context_start"], paragraph_end=section["context_end"],
                                offset_unit="unicode_code_point", kind="retrieval_only",
                                context_status="independent_fact_after_section_review", section_heading=section["heading"]))
    sections = [section for section in available_sections
                if ("kind" in section or not any(left < section["source_end"] and right > section["source_start"] for left, right in omissions))
                and any(section["source_start"] <= unit["source_start"] and unit["source_end"] <= section["source_end"] for unit in deduped)]
    scoped = [unit for unit in deduped if any(section["source_start"] <= unit["source_start"]
              and unit["source_end"] <= section["source_end"] for section in sections)]
    if _global_qualification(text) and not reasons:
        reasons.append("qualified_or_unconfirmed_paragraph")
    result = {"units": deduped, "review_reasons": list(dict.fromkeys(reasons)),
            "complete": not reasons, "scoped_units": scoped,
            "scoped_complete": bool(scoped), "scoring_sections": sections if scoped else []}
    if not result["complete"] and any(section.get("kind") == PROFILE_KIND for section in result["scoring_sections"]):
        result["scoring_scope_override"] = PROFILE_SCOPE_VERSION
    return result


def chunks(text, query=False):
    """Compatibility view; compare() also returns completeness and source spans."""
    return requirement_units(text)["positive_fragments"] if query else [unit["text"] for unit in evidence_units(text)["units"]]


EVENT_SCOPE = "source_teaching_events_v1"
MIXED_SCOPE = "mixed_source_scopes_v1"


def server_event_units(document, analysis):
    """Consume literal, server-generated event ranges, not model-written facts.

    Java validates every returned event range again from the complete resume.
    This worker only embeds text; it cannot turn these ranges into eligibility.
    Absent ranges retain the established worker path unchanged.
    """
    scopes = document.get("server_teaching_event_scopes")
    if scopes is None:
        return analysis
    if not isinstance(scopes, list) or not 1 <= len(scopes) <= 64:
        raise ValueError("invalid_source_event_scopes")
    text, seen, units = document["text"], set(), []
    if _global_qualification(text):
        return analysis
    for scope in scopes:
        if not isinstance(scope, dict) or scope.get("kind") != EVENT_SCOPE or scope.get("offset_unit") != "unicode_code_point":
            raise ValueError("invalid_source_event_scope")
        start, end = scope.get("source_start"), scope.get("source_end")
        if (type(start) is not int or type(end) is not int or not 0 <= start < end <= len(text)
                or not 2 <= end-start <= MAX_CHARS or (start, end) in seen
                or scope.get("heading") != "" or scope.get("heading_start") != start or scope.get("heading_end") != start
                or not isinstance(scope.get("event_id"), str)):
            raise ValueError("invalid_source_event_range")
        seen.add((start, end))
        units.append(dict(text=text[start:end], source_start=start, source_end=end,
                          paragraph_start=start, paragraph_end=end, offset_unit="unicode_code_point",
                          kind="retrieval_only", context_status="server_source_event", section_heading=None))
    result = dict(analysis)
    if not analysis["complete"]:
        # Scoring this scope does not call the unparsed remainder complete.
        independent = analysis["scoring_sections"] if analysis["scoped_complete"] else []
        if independent:
            merged_scopes = list(independent) + list(scopes)
            if len(merged_scopes) > 64:
                raise ValueError("capacity_limit")
            # Exact source intervals are one embedding, even if both parsers
            # found them. Text-identical distinct occurrences keep their own
            # provenance; no event count is inferred from the number of units.
            combined = {}
            for unit in list(analysis["scoped_units"]) + units:
                left, right = unit["source_start"], unit["source_end"]
                # Parsers may include/exclude the same closing full stop. This
                # dedup key never changes the stored literal source unit.
                while left < right and (text[left].isspace() or text[left] in "，,。；;！？!?"):
                    left += 1
                while right > left and (text[right-1].isspace() or text[right-1] in "，,。；;！？!?"):
                    right -= 1
                combined[(left, right)] = unit
            result.update(scoped_units=list(combined.values()), scoped_complete=True,
                          scoring_sections=merged_scopes, scoring_scope_override=MIXED_SCOPE)
        else:
            result.update(scoped_units=units, scoped_complete=True, scoring_sections=scopes,
                          scoring_scope_override=EVENT_SCOPE)
    else:
        result["units"] = list(analysis["units"])
        for unit in units:
            if not any(old["source_start"] <= unit["source_start"] and old["source_end"] >= unit["source_end"] for old in result["units"]):
                result["units"].append(unit)
    return result


class Encoder:
    def __init__(self, root, precision="int8", cache_path=None):
        import numpy as np
        import onnxruntime as ort
        from tokenizers import Tokenizer
        self.np = np
        root = Path(root).resolve()
        manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
        if manifest.get("model") != MODEL or manifest.get("revision") != REVISION or manifest.get("pooling") != "CLS+L2":
            raise ValueError("Unexpected model manifest")
        filename = f"model-{precision}.onnx"
        for name in (filename, "tokenizer.json"):
            if hashlib.sha256((root / name).read_bytes()).hexdigest() != manifest["sha256"].get(name):
                raise ValueError("Model checksum mismatch")
        options = ort.SessionOptions()
        options.intra_op_num_threads = 1
        options.inter_op_num_threads = 1
        options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        options.enable_cpu_mem_arena = False
        self.session = ort.InferenceSession(str(root / filename), sess_options=options,
                                           providers=["CPUExecutionProvider"])
        self.tokenizer = Tokenizer.from_file(str(root / "tokenizer.json"))
        self.tokenizer.no_truncation()
        self.tokenizer.no_padding()
        self.version = manifest["sha256"][filename]
        self.precision = precision
        self.cache = sqlite3.connect(str(cache_path) if cache_path else ":memory:")
        self.cache.execute("CREATE TABLE IF NOT EXISTS config(k TEXT PRIMARY KEY,v BLOB NOT NULL)")
        self.cache.execute("CREATE TABLE IF NOT EXISTS vectors(k TEXT PRIMARY KEY,v BLOB NOT NULL,t INTEGER NOT NULL)")
        row = self.cache.execute("SELECT v FROM config WHERE k='salt'").fetchone()
        self.salt = row[0] if row else os.urandom(32)
        if not row:
            self.cache.execute("INSERT INTO config VALUES('salt',?)", (self.salt,))
            self.cache.commit()

    def encode(self, text, query=False):
        key = hmac.new(self.salt, (self.version + ("Q" if query else "D") + text).encode(), hashlib.sha256).hexdigest()
        row = self.cache.execute("SELECT v FROM vectors WHERE k=? AND t>?", (key, int(time.time()) - 604800)).fetchone()
        if row:
            vector = self.np.frombuffer(row[0], dtype="<f4")
            if vector.shape == (512,) and self.np.isfinite(vector).all():
                return vector
        tokens = self.tokenizer.encode((QUERY_PREFIX if query else "") + _normalized(text))
        if len(tokens.ids) > 512:
            raise ValueError("text_too_long")
        inputs = {"input_ids": self.np.asarray([tokens.ids], dtype="int64"),
                  "attention_mask": self.np.asarray([tokens.attention_mask], dtype="int64"),
                  "token_type_ids": self.np.asarray([tokens.type_ids], dtype="int64")}
        vector = self.session.run(["embeddings"], inputs)[0][0].astype("<f4")
        if vector.shape != (512,) or not self.np.isfinite(vector).all():
            raise ValueError("invalid_embedding")
        vector /= max(float(self.np.linalg.norm(vector)), 1e-12)
        self.cache.execute("INSERT OR REPLACE INTO vectors VALUES(?,?,?)", (key, vector.tobytes(), int(time.time())))
        self.cache.execute("DELETE FROM vectors WHERE t<?", (int(time.time()) - 604800,))
        self.cache.execute("DELETE FROM vectors WHERE k IN (SELECT k FROM vectors ORDER BY t DESC,k LIMIT -1 OFFSET 5000)")
        self.cache.commit()
        return vector

    def compare(self, payload):
        started = time.monotonic()
        query = payload.get("query")
        documents = payload.get("documents")
        if not isinstance(query, str) or not query.strip() or len(query) > 10000:
            raise ValueError("invalid_query")
        if not isinstance(documents, list) or len(documents) > MAX_DOCUMENTS:
            raise ValueError("capacity_limit")
        prepared, identifiers, text_size = [], set(), 0
        for document in documents:
            if not isinstance(document, dict) or not isinstance(document.get("text"), str):
                raise ValueError("invalid_document")
            ident = document.get("id")
            if not isinstance(ident, int) or isinstance(ident, bool) or ident < 1 or ident in identifiers:
                raise ValueError("invalid_identifier")
            identifiers.add(ident)
            text_size += len(document["text"])
            if text_size > MAX_BODY:
                raise ValueError("capacity_limit")
            prepared.append((ident, server_event_units(document, evidence_units(document["text"]))))
        query_analysis = requirement_units(query)
        query_parts = query_analysis["positive_fragments"]
        if not query_parts or len(query_parts) > 8 or sum(len(analysis["units"]) for _, analysis in prepared) > MAX_CHUNKS or sum(len(analysis["scoped_units"] if not analysis["complete"] and analysis["scoped_complete"]
                else analysis["units"]) for _, analysis in prepared) > MAX_CHUNKS:
            raise ValueError("capacity_limit")
        def check_deadline():
            if time.monotonic() - started > 7:
                raise TimeoutError("comparison_timeout")
        query_vectors = []
        for part in query_parts:
            check_deadline()
            query_vectors.append(self.encode(part, query=True))
            check_deadline()
        results = []
        for ident, analysis in prepared:
            scored = []
            facet_best = [-1.0] * len(query_vectors)
            scoped = not analysis["complete"] and analysis["scoped_complete"]
            for unit in analysis["scoped_units"] if scoped else analysis["units"]:
                part = unit["text"]
                check_deadline()
                vector = self.encode(part)
                check_deadline()
                similarities = [float(self.np.dot(q, vector)) for q in query_vectors]
                facet_best = [max(old, new) for old, new in zip(facet_best, similarities)]
                score = max(similarities)
                if not math.isfinite(score):
                    raise ValueError("invalid_similarity")
                scored.append((max(-1.0, min(1.0, score)), unit))
            scored.sort(key=lambda entry: (-entry[0], entry[1]["text"]))
            # Every requested unit contributes; one topic cannot mask a missing one.
            # Similarity remains a retrieval signal, never proof of competence.
            aggregate = min(facet_best) if facet_best else -1.0
            results.append({"id": ident,
                            "similarity": round(aggregate, 6) if scored and (analysis["complete"] or scoped) else None,
                            "evidence": [entry[1]["text"] for entry in scored[:2]],
                            "evidence_units": [entry[1] for entry in scored[:2]],
                            "evidence_complete": analysis["complete"],
                            "scoped_evidence_complete": scoped,
                            "scoring_scope": (analysis.get("scoring_scope_override") or (FACT_SCOPE_VERSION if any("kind" in s for s in analysis["scoring_sections"])
                                              else "independent_sections_v1")) if scoped else "document",
                            "scoring_sections": analysis["scoring_sections"] if scoped else [],
                            "evidence_review_reasons": analysis["review_reasons"]})
        check_deadline()
        return {"model": MODEL, "revision": REVISION, "precision": self.precision,
                "status": "ready", "complete": True, "results": results,
                "requirement_analysis": query_analysis,
                "elapsed_ms": round((time.monotonic() - started) * 1000)}


class Handler(BaseHTTPRequestHandler):
    server_version = "YanxuLocalSemantic"

    def setup(self):
        super().setup()
        self.connection.settimeout(10)

    def log_message(self, *_):
        pass  # No request paths or user text in logs.

    def send_json(self, code, payload):
        body = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_POST(self):
        expected = "Bearer " + self.server.secret
        if not hmac.compare_digest(self.headers.get("Authorization", ""), expected):
            return self.send_json(401, {"error": "unauthorized"})
        if self.path != "/compare":
            return self.send_json(404, {"error": "not_found"})
        try:
            if self.headers.get("Transfer-Encoding") or self.headers.get_content_type() != "application/json":
                return self.send_json(400, {"error": "invalid_content_type"})
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= MAX_BODY:
                return self.send_json(413, {"error": "capacity_limit"})
            raw = self.rfile.read(size)
            if len(raw) != size:
                raise ValueError("incomplete_request")
            payload = json.loads(raw)
            if not isinstance(payload, dict):
                raise ValueError("invalid_request")
            return self.send_json(200, self.server.encoder.compare(payload))
        except (TimeoutError, socket.timeout):
            return self.send_json(503, {"error": "comparison_timeout"})
        except (ValueError, TypeError, KeyError):
            return self.send_json(422, {"error": "invalid_or_oversized_input"})
        except Exception:
            return self.send_json(503, {"error": "inference_unavailable"})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", required=True, type=Path)
    parser.add_argument("--cache", required=True, type=Path)
    parser.add_argument("--port", type=int, default=18091)
    args = parser.parse_args()
    secret = os.environ.get("YANXU_SEMANTIC_TOKEN", "")
    if len(secret) < 32:
        raise ValueError("Configure a local worker token of at least 32 characters")
    if "web" in args.cache.resolve().parts:
        raise ValueError("Cache must not be publicly served")
    os.umask(0o077)
    args.cache.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    server = HTTPServer(("127.0.0.1", args.port), Handler)
    server.secret = secret
    server.encoder = Encoder(args.model_dir, cache_path=args.cache)
    print("Local semantic worker ready (CPU, single task, offline)", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
