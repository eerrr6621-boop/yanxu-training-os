# Bounded narrative course-entity binding

This local change fixes source parsing, not embedding weights. It has not been deployed. The previously sealed 32 examples were exposed after the V5 run and are development diagnostics now; their original inputs, labels, reports and frozen pipeline remain unchanged.

## Supported binding

- An explicit `《课程名》` with an opening or learning-content declaration establishes a source-spanned course entity, not the resume owner's teaching role.
- A course reference can bind only inside the same complete paragraph with one distinct named course. Explicit personal teaching/completion remains necessary. `这堂面授课由我独立讲完` and `我给对象独立讲完了这门课的全部内容` retain the title, actor, recipient, action and form from their actual source spans.
- `独立` after a recipient belongs to the teaching action, not the audience. An earlier audience can resolve a bounded deictic group reference, but conflicting or excluded groups remain pending.
- Organization logistics and classroom material preparation are context relations only. A narrowly identified organization-work disclaimer is not a personal teaching denial, and is never offered as positive evidence. Course graduation alone does not establish a teacher's completed event.
- Explicit future/conditional/reported declaration frames, contradictory actors or states, assistant/attendee roles, and excluded audiences do not yield a usable teaching event. Unusable event maps cannot independently support topic, feature or numeric checks.

Every output field and relationship retains exact contiguous normalized source text and offsets. Unknown clauses and prefaces retain source locations; no biography is regenerated. Existing full-source denial and cross-line role protections remain active. The protocol is unchanged: `source_teaching_events_v1` scopes remain at most 220 code points and are recomputed from the complete source by Java. A scope is retrieval input, not externally verified teaching history.

## Regression evidence

The new synthetic developer tests contain 269 Java assertions and five Python test methods across unrelated training subjects. They cover affirmative and negative active/passive forms, organizational support, material-only work, future/conditional statements, reported authorship, audience conflicts and multi-course references. These are regression tests, not a blind accuracy claim.

The final source-side run executed 132 Python tests: 131 passed and one pre-existing optional test was skipped. The 21 Java suites had 20 passes and one shared requirement-side regression at `EvidenceContextTest:51` (an explicitly stated classroom role-play method); this was reported to the requirement owner and is not hidden or relabelled. An earlier intermediate development run passed all 21 suites. The root's eventual whole-project run must resolve and recheck it.

An independent 12-case source review found four real mistakes before correction: future opening, hypothetical opening, attributed speech and excluded audience. Their original probes now reject those cases while retaining appropriate positives. Three-domain Java → actual worker source-unit → Java revalidation checks also rejected truncated scopes, changed actors, added document denial and a second-course mutation. No model was invoked by these checks.

In the exposed 32-case source-only diagnostic, the two representative previously unbound positive paragraphs now each form one completed personal event and one literal scope. This is not a final-admission or model-accuracy result; the rest still include unsupported event/reference structures.

## Remaining boundary

This remains a bounded grammar, not general natural-language understanding. Multi-event calendars, same-title offerings by multiple organizations, unknown quoted speech, nonliteral audience synonyms and indirect completion predicates may still need human review. A same-title paragraph is not proof of multiple delivered sessions or a particular venue. No additional classroom activities, duration, audience qualification or credential authenticity may be inferred from the existence of a course event.

Private diagnostics and all intermediate failures are retained under `.codex-tmp/yanxu-entity-binding.3D6yGK/`; no real resume text is included in this document.
