# Implementation notes

## 2026-05-22 — ambiguity review
Reviewed `design.md` plus referenced Emacs hot-path code/tests (`psi-events.el`, `psi-assistant-render.el`, `psi-tool-rows.el`, `psi-widget-projection.el`, `psi-projection.el`, streaming/tool/widget/projection tests). `plan.md` and `steps.md` are absent. New actionable ambiguities: missing execution artifacts; no concrete allocation/CPU success threshold; assistant stream payload contract/delta-vs-snapshot fallback examples are underspecified; mandatory vs optional optimization targets across assistant/thinking/tool/widget/projection paths are unclear.
