Implementation notes:
- Created from live investigation of `gh-bug-triage` workflow failure on 2026-04-24.
- Initial diagnosis:
  - session auto-retry was enabled but did not trigger because retry classification did not recognize `Premature end of chunk coded message body: closing chunk expected`
  - workflow execution converted the errored child assistant message into `{:outcome :ok :outputs {:text ""}}` because it extracted only `:text` blocks and ignored `:error` blocks / error outcome
