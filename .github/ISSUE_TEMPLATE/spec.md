---
name: Spec (feature or bug)
about: Work item with the acceptance tests that must fail before the change and pass after it
---

## Problem

<!-- What is wrong or missing and who sees it. For a bug: exact steps, the input that triggers it, what happens and what should happen. -->

## Proposal

<!-- The change, at the level of behaviour. Name files only when the location is already known. -->

## Acceptance tests

<!--
Write these BEFORE any code. Each one becomes a test that is run and seen failing
for the right reason, then made green by the change. One line per behaviour:

- [ ] Given <state>, when <action>, then <result>. Test: `<path to test file>`

For a bug, the first line reproduces the report with the real input.

If a test written first adds nothing here (pure layout or styling, a dependency upgrade,
native build config), replace the list with one line:
No test-first: <reason>
-->

- [ ] Given ..., when ..., then .... Test: `...`

## Out of scope

<!-- Optional: related things this issue deliberately leaves alone. -->
