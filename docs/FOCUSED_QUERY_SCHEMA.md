# Focused retrieval text and guided field compatibility

`LocalSemantic.focusedQuery` is a retrieval projection, not the complete admission requirement or a fact parser. The original/canonical requirement still enters the existing Coverage, unknown-condition and guided-field review gates. This change does not certify qualifications or loosen those gates.

The original guided label `参训对象` is accepted alongside the existing audience aliases. The valid `guided_requirement_v1` production path already canonicalized it to `培训对象`; the compatibility gap affected raw/text-only paths, not every guided request. `补充说明`, present in both original and canonical schemas, now keeps its entire value in retrieval, including negation and restrictions. Its existing guided pending status remains unchanged.

Date, money and expected-hours cleanup now applies only to a complete physical line with a recognized operational label and a complete numeric/date value. The method no longer globally removes date or budget-number substrings from a course, goal or plain professional sentence. A mixed operational value is retained literally; an unmatched following line cannot be swallowed by the preceding operation. Recognizing such a field does not validate its business value or convert scheduled hours into historical teaching experience.

Unknown labels are not added to a positive-content whitelist. For example, an unknown exclusion label is not stripped to turn its object into a requested course. The full hard-source text and existing unresolved treatment remain independent. Existing pre-focus personal-attribute checks remain in place. Original text, canonical text, field ledger and source offsets are never rewritten to the projected focus.

## Independent pure validation

`scripts/FocusedQuerySchemaTest.java` fixes 18 cases from the prior read-only plan and adds Unicode, physical-line, mixed-value, exclusion, source-span and actual rule-gate boundaries. It uses a capture-only Transport that deliberately returns unavailable; no worker, model, HTTP, database, real resume or accuracy measurement is involved. Standard guided examples are constructed through the actual RequirementInput contract, including original/canonical field provenance.

The original fixed plan and old implementation failures were preserved before editing the method. The first updated run had one test-author punctuation mismatch: NFKC already maps an inline fullwidth semicolon to ASCII, while case 18's expected string incorrectly retained fullwidth punctuation. After explicit approval, only that expectation character was corrected in a separately sealed v2. All 18 input values, old expectations, original failure and business normalization remain unchanged.

Final results: 138 new assertions, existing `LocalSemanticTest` 281 checks and `RequirementInputTest` 73 checks passed. The full hard-query/rule-candidate observations for all evaluable planned cases are identical before and after this projection-only change. These counts are software contract regressions, not recommender accuracy. No frozen model/evaluation archive or production service was modified.

Run with compiled current classes:

```text
java -cp <current-classes-and-libraries> com.training.FocusedQuerySchemaTest
java -cp <current-classes-and-libraries> com.training.LocalSemanticTest
java -cp <current-classes-and-libraries> com.training.RequirementInputTest
```

The test also supports `--fixtures` (print the fixed plan without exercising focus) and `--observe-baseline` (retain failures without claiming they passed). Private before/after evidence is kept separately from the public source and must never be overwritten by a future run.
