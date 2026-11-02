# Building workflows and evaluating them

Start with an ordinary program that solves the user's task. Add an evaluation
pack when you need evidence about its quality. An optimized workflow remains
ordinary runnable code: it must not depend on an open experiment, leaderboard,
or speculative world to run or be scheduled.

This repository is source code loaded by a host. Available namespaces, tools,
models and grants depend on that host and on the current Run. Discover what is
installed with `(sandbox/doc 'dvergr.agent)` and `(clojure.repl/doc
 dvergr.agent/hire!)`; inspect a particular function rather than repeatedly
printing a whole namespace's schemas. Simmis's `workflow/*` authoring helpers
are host additions, not a namespace defined by this repository.

## From a request to a reusable workflow

1. Define inputs, expected outputs and the user's intended outcome. Keep small
   reusable Clojure helpers in source files and exercise them in the REPL.
2. Save a task under `workflows/<name>/workflow.edn`. On Simmis, a normal task
   needs no checker or gold answers. Keep an optional `diagram.mmd` beside it.
3. Make the task work on a small source-backed example before claiming it works
   generally. Preserve exact input revisions and source citations.
4. When evaluation is useful, add fixtures, evaluator-held references, a checker
   and calibration examples. Keep the task program independent of scoring.
5. Inspect, save and admit the exact source before running a benchmark. These
   are separate operations; none should be implied by another.
6. Review outputs and failures, then propose changes to the workflow or grader.
   A completion message, successful smoke test or passing self-authored checker
   does not establish customer usefulness.

A minimal Simmis task definition is ordinary data:

```clojure
{:title "Customer brief"
 :task "Read /inputs/request.md and write the requested brief to /out/brief.md."
 :capture ["/out"]}
```

Supply those inputs through the host's declared workflow input mechanism. Task
text naming a file does not collect live room chat, screens or other memories.
An ordinary Run, a scheduled Run and a benchmark may execute the same task with
different admitted inputs. Each must make those inputs explicit.

## Simmis authoring helpers

When the host installs the `workflow` namespace:

| Function | Purpose |
|---|---|
| `(workflow/starter "evidence-check")` | On hosts shipping this starter, returns editable `:files` for an ordinary task and a small synthetic benchmark. Reads data only. |
| `(workflow/check-files files)` | Inspects a relative path-to-text map. Does not save files, run the checker, certify results or grant execution authority. |
| `(workflow/action-links "customer-brief")` | Returns room-scoped Run once, Schedule and Benchmark confirmation links for canonical saved source. Does not execute or schedule. |
| `(workflow/schedules)` | Lists this room's legacy agent schedules, not saved workflow source. |
| `(workflow/set-shape! schedule-id mermaid)` | Updates an existing legacy schedule's diagram. For a saved workflow, edit `diagram.mmd` instead. |

Read signatures first. `starter` selects a named shipped program; it is not a
constructor that accepts an arbitrary task map. The evidence-check example is
one way to inspect and adapt a complete pack:

```clojure
(let [{:keys [files]} (workflow/starter "evidence-check")]
  (workflow/check-files files))
```

Inspect existing `workflows/<name>/` files before saving or replacing them. Save
the chosen source using the normal file tools. If it is in a proposal, adoption
comes before launching the canonical workflow. Include the resulting scoped
links in your reply so a person can review and confirm the next action. Report
separately what is authored, saved, runnable, benchmark-ready and scheduled.
Confirmation consumes existing allowance; it does not provision funding.

## Evaluation pack and grounding

A common file layout is:

```text
workflows/customer-brief/
  workflow.edn
  diagram.mmd
  checker.clj
  calibration.edn
  benchmark.edn
  cases/
    development-example/
      fixtures/inputs/request.md
      gold.edn
    held-out-example/
      fixtures/inputs/request.md
      gold.edn
```

Fixtures become candidate inputs; `gold.edn` is evaluator-held reference data.
A checker defines `check` over captured output files and host-supplied reference
material. On Simmis's current offline path, it returns bounded boolean `:checks`
and a finite `:reward` in `[0,1]`. Extra fields are not a supported assessment
record. Check the installed host contract before relying on richer output.

A missing domain-specific built-in evaluator does not prevent **authoring** a
workflow and deterministic checker. It also does not permit the agent to
self-authorize that checker. The exact saved revision still needs admission by
the authorized host/operator. Current Simmis benchmark controls are deliberately
small, offline and discard-settled; do not assume they support web or model judges.

A useful evaluation pack contains:

- Source-backed cases with explicit task instructions and acceptable answers.
- Reference outputs that pass and deliberately damaged outputs that fail the
  intended checks. Calibration tests the checker, not the solver.
- A declared development/held-out partition. Tune on development; do not use
  repeated held-out results to tune and still describe them as independent.
- Tests for invalid output, insufficient evidence and valid alternatives.
- A rubric stating what the automated score measures and what needs human review.

The starter's synthetic success demonstrates the harness, not domain competence.
For a real task, establish expected answers from sources and independent review;
agent-generated cases, gold and graders can share the same mistake. Preserve
exact messages, document passages or screenshot references, not only summaries.

Forking isolates changes; it does not hide inherited files, chat, history or
attached data. Do not benchmark blind solvers by simply hiring them in a room
that contains the full dataset answers. Use the admitted clean-fixture path and
verify that gold, calibration answers and author history are absent from the
candidate's accessible inputs. Merely naming a file `gold.edn` is no access policy.

## Delegation and recursive evaluation

Where `dvergr.agent` is installed, `hire!` starts a durable child Run and returns
a handle. `result-spin` observes its result; `owned-result-spin` also cancels that
owned child when the observer is cancelled. Use `await` inside `spin`; a cancel
request is not proof that physical execution has exited.

`offered` describes the evaluator/setup/invocation capabilities actually granted
to this scope. `run-experiment!` accepts portable experiment definitions but
resolves offered evaluator/setup references. `invoke-experiment!` uses an exact
host-admitted invocation and requires an ambient Run. Neither accepts arbitrary
agent code as trusted host verification authority. If the required capability
is absent, explain the exact admission needed rather than inventing results.

Child work needs explicit resource grants and cannot widen its parent's tools,
sandbox or providers. Subscription-backed calls still require the host's
execution admissions. Creating or nesting rooms does not mint resources or
permissions. Organizational room parentage, fork ancestry and Run ownership are
separate relationships. Isolated workflow workers may intentionally lack the
room coordinator's delegation surface.

A grader can itself be evaluated as an ordinary candidate over frozen answers
and independently reviewed expected assessments. A blind grader reads fixed
evidence. An investigator that commissions additional solver Runs is a different
protocol; retain its interventions, provenance and cost. General human scoring
forms and posthoc assessment records are separate host features, not provided by
this source repository or implied by proposal accept/reject controls.

## Reports and next actions

Keep results navigable through files rather than inventing another dashboard.
Simmis's standard report publication uses `reports/index.md` and
`reports/benchmarks/<scorecard>.md`; structured Markdown views query retained
benchmark records. Link the standard report when it has actually been published.
Do not claim that writing a static Markdown table creates a certified Scorecard,
or assume that executing a benchmark automatically publishes a report file.

An overview is a projection of retained Attempts, exact workflow revisions,
case selection and evaluator identity. Preserve links to individual outputs and
traces so reviewers can distinguish a bad answer, a grading error, a task
ambiguity and an infrastructure failure. Do not silently count missing or faulted
attempts as successful, or exclude them to improve a reported score.

After comparison, propose the selected workflow changes through the normal
adoption path. Run or schedule the adopted ordinary workflow with its existing
permission and allowance controls. Evaluation evidence explains the choice; it
is not another execution authority or a requirement to keep the experiment live.
