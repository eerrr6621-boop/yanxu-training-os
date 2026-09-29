# Local requirement-topic integration

2026-09-08. Rules/source parsing change, not post-training, not deployed. Existing tests, exposed case labels and previous reports are retained unchanged.

## Changes

- RequirementCoverage gives the bounded RequirementTopic parser exclusive ownership of consumed course/history spans. The old generic object parser sees only residual source text. It no longer independently invents a course from `这门课的经历` or flags a fully carried history predicate as an unknown second module.
- Established FACETS and their existing literal/evidence behavior remain. Residual course syntax and hard conditions still go to the existing extractors. Unhandled mandatory follow-ups remain pending; the full topic ledger, including delegated audience/goals, is returned for inspection.
- A recognized history condition is checked on its own course, not imposed globally on another requested course. `personal/completed/independent` requirements and an explicit audience reference must be supported together by the matching event/record. A completed event with the wrong audience cannot borrow the audience of another not-yet-completed event.
- RequirementConstraints retains its public `teachingHistory` summary. Its Coverage-specific construction also distinguishes global legacy history requirements from course-bound history. The original direct constructor retains conservative global behavior. Numeric, object, role-exclusion, qualification and other existing checks remain independent.
- Common source-preserving request wrappers (`补充要求`, providing/having completed classroom records) are parsed structurally. A reference to `该对象/上述对象` binds only a unique preceding audience field; its reference and target spans are included in the history condition. No audience antecedent or conflicting audience fields remains unresolved. No course-name dictionary was added.
- History conditions exported by Coverage use `requirement_matching_text`, because the constructor may receive canonical or legacy-composed text. The new root-level criterion also exposes its span for RequirementInput's existing canonical/field annotation; nested metadata does not falsely claim to be raw user text.
- Narrative TeachingEvents are additive to existing guarded legacy records. Even a usable narrative event may omit an older recognized mode/time modifier, so it is not automatically safe to replace all legacy evidence. Explicit field blocks retain their original strict anti-detachment range suppression. Full-source qualification/role guards and semantic scope validation are not removed.

The root agent separately implemented narrative event/worker source scopes and corrected the source parser's future-state predicate boundary. These are distinct code changes, not an effect of the fixed embedding weights.

## Verification

New `RequirementTopicIntegrationTest`: **93 fixed synthetic candidate cases / 99 assertions**, all passing. It covers three unrelated domains, named versus plural history, no history spillover to a second course, wrong actor, assistant role, future events, wrong audiences, cross-event borrowing, independence/completion split across records, request wrappers, audience references and existing legacy facet/mode positives.

The original standalone `RequirementTopicTest`: **61 inputs / 809 assertions**, all passing; many assertions validate source spans and complete character accounting, not independent business questions.

Seventeen relevant requirement/evidence/event suites passed in the final private run: **1,915 assertions/checks** including the two new suites. The other fifteen suites account for **1,007** existing checks. An additional **49 NearbySelection** and **281 LocalSemantic** checks passed. Existing test expectations were not changed. ECJ produced four existing unchecked-cast warnings in the latter two test files; no compilation error.

The actual offline `PosttrainingV2Probe` **prepare** path was replayed on the previously exposed, unchanged 16-case set:

| Metric | Previous frozen TeachingEvents pipeline | Current local integrated source |
|---|---:|---:|
| Positive cases admitted | 2/8 | 8/8 |
| Negative cases rejected | 8/8 | 8/8 |
| False-admission cases | 0 | 0 |
| Execution errors | 0 | 0 |
| Prepared text equals original NFKC text | 16/16 | 16/16 |

Current class fingerprints were identical before/after this execution. Prepared documents now may contain extra server-owned event scopes, but their actual `text` strings remain exact. This run did **not** call a model or worker, use a real resume, change a database, or inspect the new 32-case set. It is an exposed development regression, not blind accuracy and not proof of worker/model end-to-end improvement.

Private outputs: `.codex-tmp/yanxu-topic-integration.BaBHSU/{validate.cjs,development-result.json,development-second.json,development-final.json}`. The first report records one old Atomic regression and 7/8 positive admissions; the second records all relevant unit suites passing but still 7/8. The final report records 8/8 after the root's separate source-event repair. None of these intermediate failures was overwritten. The original exposed fixture SHA remains `f61ee5473e7327949ad5c5d275ffa4714f347eea9294975f95b692bdee8fda23`.

## Limits

This is a bounded course/history/object-reference contract, not full natural-language understanding. Unknown compound requirements, unsupported numerical phrasing, temporal ambiguity, ambiguous coordination/coreference and qualifications remain subject to existing review. It does not establish external credential truth or guaranteed teaching ability. The subsequent fixed-weight model/worker replay and fresh-set acceptance are separate gates; do not label the exposed improvement as post-training benefit.
