# Bounded course and history requirement nodes

Status: originally a standalone parser; now integrated locally with course-scoped Coverage/Constraints checks. See `REQUIREMENT_TOPIC_INTEGRATION.md` for the later integration and exposed regression results. Not a model change and not deployed. Existing training/holdout labels and historical reports are unchanged.

## Purpose and API

`RequirementTopic.parse(String)` preserves the exact input as `requirement_original`. `parse(RequirementInput)` instead uses that input's matching text, marked `requirement_canonical` for guided input or `legacy_composed_requirement` for legacy composed text, and retains `RequirementInput.toMap()` as the original/canonical field provenance ledger. Canonical positions must never be relabelled as positions in the user's original rendered string or raw field values.

Both return `Result` with schema version `requirement_topic_v1` through `toMap()`:

- `courses`: stable within-result IDs, NFKC canonical anchors, exact original anchor spans, reference spans, and linked history conditions.
- `consumed_spans`: source text carried by this module as a field boundary, course declaration or teaching-history condition. This is not evidence that a candidate meets it.
- `unparsed_clauses`: every remaining nonempty clause, with reason and source span. Audience, goals, schedule and client fields explicitly remain `delegated_field` entries for downstream handling; they are not silently discarded.
- `input_provenance`: full existing input ledger for guided/canonical-to-field mapping.
- `eligibility_decision=false`: this parser never certifies a teacher, scores a resume or admits a candidate.

Java `Span.start/end` are UTF-16 indices into `Result.source`. Exported `source_start/end` are Unicode code-point offsets, with explicit `offset_unit` and source kind. Every exported span validates its exact contiguous source substring. The source is not NFKC rewritten by the raw entry point; only `canonical_anchor` is normalized.

## Supported structural scope

Course declarations come from existing labelled topic/content fields, a standalone quoted course title outside supplemental fields, or the object of a completely parsed past-teaching condition. There is no course-name dictionary or embedding lookup. Conjunctions outside quoted titles preserve separate required nodes; a conjunction inside `《市场与价格》` remains part of one title. OR alternatives remain unresolved, rather than being changed into AND.

An explicit named history condition binds only its named node. A singular reference such as `这门课` or `该课程` binds only a single prior active course. It never creates a course titled `这门课的经历`, chooses one of two antecedents or resolves a forward reference. A clearly distributive plural reference with `均/都/分别` can bind the active course group; an explicit cardinality must match. Blank paragraphs reset this bounded reference context.

Examples:

```text
培训主题：陶瓷釉料调配；必须有这门课的经历
=> one course; one linked past-teaching condition

培训主题：《陶瓷釉料调配》和《冷藏药品入库复核》
上述两门课程均须有实际授课记录
=> two course nodes; an explicit condition on each

培训主题：《陶瓷釉料调配》和《冷藏药品入库复核》
必须有这门课的经历
=> both course nodes retained; ambiguous singular condition remains unparsed
```

`HistoryCondition.pastTeaching` is the condition type. `personal`, `completed` and `independent` record only explicit supported predicate wording, not words inside a course title. False flags mean that modifier was not explicitly carried, not that the opposite was requested. A personal-teaching eligibility policy must still be enforced by the evidence/coverage layer. Source evidence must satisfy conditions on the same course/event; this module provides no teacher evidence.

Past aspect (`教过/主讲过/...`), explicit already-completed teaching, and a complete-teaching predicate plus a required classroom/history record are bounded recognized forms. `必须本人完成这门课的教学` without a past/time/history marker remains temporally ambiguous. Completion of lecture notes is not teaching completion. Planned future training, learner prerequisites, other-person actors, negated conditions and compound classroom activities outside the recognized grammar remain visible for downstream review. Goals fields never create past-teaching obligations.

## Integration contract

The initial standalone change did not edit existing callers. The subsequent integration edits RequirementCoverage/RequirementConstraints only; RequirementInput, API, worker and the check script remain unchanged by this agent. Course nodes replace re-parsing their consumed references as generic topics. Existing evidence validation and factual admission rules remain separate.

All explicit requirements must still be accounted for by some checker. Consuming a topic/history span does not consume a neighboring audience, activity, quantity, exclusion or other clause. A downstream parser may explicitly take ownership of a delegated/unparsed span and prove it; otherwise it remains pending. Do not discard every unparsed span merely because a recognized course exists, and do not treat every delegated field as an irreducible failure when a dedicated checker already handles it.

Canonical span-to-field mapping remains available in `input_provenance.field_ledger`; canonical text and raw original field offsets are different coordinate systems. The current guided input's independent review policy has not been relaxed by this standalone module.

## Regression and limitations

`scripts/RequirementTopicTest.java` fixes 61 synthetic development inputs spanning three unrelated course topics and additional paired cases. It contains 809 assertions including exact-source, code-point and per-character accounting checks. Those assertions are not 809 independent business examples. All pass against the standalone parser. The first version failed a later same-line field and a subsequent independently added title-vs-actor test; the parser was corrected without changing those expectations.

Tests include valid single/plural bindings, course-specific history, two explicit courses, unquoted completed objects, preservation of a conjunction inside a title, wrong actors, future goals, ambiguous completion, completed materials, orphan/forward/paragraph-separated references, mismatched plural counts, OR, hidden numeric/activity conditions, arbitrary supplemental documents, NFKC titles and supplementary Unicode before source spans.

These are exposed development regressions, not newly sealed questions or post-training accuracy. No model, real resume, database, HTTP production request or new sealed set was used. No improvement in end-to-end eligibility can be claimed until the root integrates and replays the fixed-weight pipeline with unchanged labels.

The grammar is deliberately bounded. It does not solve arbitrary noun coordination, ellipsis, nested conditionals, temporal reasoning, unknown grammatical variants, global discourse coreference, numeric constraints or classroom-activity parsing. Such residuals must remain in the ledger instead of becoming invented course nodes or disappearing.
