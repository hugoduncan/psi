🔁 The workflow-loader content-lock tests
(`task_209_workflow_definitions_test.clj`, e.g. `reduce-incidental-complexity-test`)
read `.psi/workflows/*.edn` via **cwd-relative** paths, so they must run from the **repo
root**, not the component dir. To run focused: build an absolute classpath with `-Spath`
from the component, then invoke with `-Scp` from root so the cwd-relative
`.psi/workflows/` reads resolve. Run from root gave 3 tests / 196 assertions green
(task 215).

❌ Pitfall observed (task 215, pre-existing — NOT caused by workflow edits): the full
kaocha `--focus` run fails to LOAD an unrelated namespace
(`psi.agent_session.tool_execution_test` → missing `psi/metrics/extension` on the
classpath). When that blocks a focused run, isolate the target suite (ad-hoc classpath
above) rather than chasing the unrelated load failure.
