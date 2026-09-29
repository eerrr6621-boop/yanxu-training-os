# Personal actor and completed-event evidence repair

This is a new local rule/worker revision after the V4 evaluation was archived.
It is not a model-weight change, deployment, or a revision of any benchmark label.
No private resume or sealed question text is reproduced here.

## Changes

- The Python worker and Java `ProfessionalEvidence` recognize a bounded personal completed-teaching action with an explicit course object, including `我已独立完成[主题]教学` and `本人独立讲完[课程]`.
- An inverse actor statement such as `独立主讲是本人` is distinguished from an external actor. The completed-course clause must be the nearest preceding clause, so another course's completion cannot be borrowed.
- Unit/department/institution events do not become personal history. Inverse other-person teaching roles and explicit personal attendance/support are withheld from affirmative evidence.
- Completed handouts, assistant work, teaching applications, preparation, records, and reports are not completed teaching. The bounded event object's words and its immediate suffix are checked.
- Explicit `仅/只` material-support duties override a conflicting completion claim. Ordinary material preparation alongside an unambiguous personal teaching event remains possible; one unrelated `仅` cannot reclassify a different support statement.
- Existing cross-line assistant/support context remains active. An independently dated personal event may still be restored under the existing restricted different-event rules.

`completedPersonalEvent` is only an event predicate. The caller must still use the complete source/context through `records` / `isTeachingRecord`; it is neither a qualification certification nor a substitute for the complete request's conditions. Requirement parsing remains owned by `RequirementConstraints` / `RequirementCoverage`, not by this predicate alone.

## Limited course-content exclusions

A whole, explicit exclusion such as a separate clause saying a completed quoted course excludes another content topic may be omitted from **positive quotes** only when:

1. The text has a single unambiguous quoted course title before the exclusion.
2. That title's own sentence contains the personal completed event; completion of an unrelated sentence is insufficient.
3. The excluded noun phrase is bounded and contains no unresolved reference, personal actor, teaching-history restriction, or overlapping title words.

The complete original context and source offsets remain available. Excluded text cannot be reinserted through the independent-fact path. Java also includes the excluded clause in requirement conflict checks, so a broad title cannot prove a specifically excluded requirement.

This is deliberately incomplete: unquoted or multiple-title cases, unknown references, unfamiliar negation scope, and ambiguous roles stay unscored/pending review. No broad rule ignores every `不包含` or every negative phrase.

## Protocol and unaffected boundaries

Scope versions/field contracts are unchanged. Exact contiguous quotations and source-offset validation are preserved. No production model allowlist, authentication, HTTP behavior, cache, capacity limit, deadline, training helper, archived V4 class, checkpoint, evaluation label, or historical output was modified by this repair.

## Validation

New synthetic regression sources:

- `semantic/test_evidence_actor_event.py`
- `semantic/test_evidence_actor_protocol.py`
- `scripts/EvidenceActorEventTest.java`

The new actor/event expectations were written before the implementation. The first Python run exposed 20 failed subcases in six methods. Additional paired tests cover exclusive versus incidental support and completed-document versus completed-teaching objects.

Final Python: **121 methods passed**, comprising worker 50 plus evidence suites 71. The cross-language method executed **11 synthetic fixed-vector cases** through actual worker preprocessing and the unchanged Java offline probe. It used an explicitly synthetic model identity and invoked no model.

Final Java: **1,159 checks passed**:

| Suite | Checks |
| --- | ---: |
| Actor/event (new) | 73 |
| Grammar | 58 |
| Context | 41 |
| Adversarial | 30 |
| Scope | 10 |
| Partition | 30 |
| Facts | 44 |
| Role context | 49 |
| Requirement atomic | 82 |
| Requirement audience | 27 |
| Requirement numeric/action | 174 |
| Requirement generic topics | 73 |
| Requirement contracts | 169 |
| Local semantic/dispatch | 281 |
| Offline probe self-test | 18 |

The grammar suite's dynamic quote-check count changed from 59 to 58 because a negative-role record is no longer retained; its fixed history expectations and source fixtures were not edited. Compilation succeeded using the existing H2, PDFBox, and ip2region jars. Four existing unchecked-cast warnings remain. An initial local compile omitted the existing ip2region jar; it was rerun with the normal dependency, without source workarounds.

Build output: `/private/tmp/yanxu-actor-event.2PqFII/out` (not an archived evaluation out).

Final source hashes:

```
3c135b822210a3082abff9901e26080fea2840e7a369db4d7ab436cd40bee1ba semantic/worker.py
79f1227ae54f37491efca0400a0268504096436bc55261a04e5b977d2ccc0b0e src/com/training/EvidenceSections.java
434755d3af3c5d0b35f60064c77a5ef53df99c3d0b82f1d9670f950358e6fa65 src/com/training/ProfessionalEvidence.java
```

These are implementation/protocol regressions, not real-world accuracy estimates.
The root task will run a separately frozen paired model/history replay; improvements from this rule revision must not be attributed to post-training weights.
