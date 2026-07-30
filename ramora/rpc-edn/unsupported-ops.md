# Unsupported-Op Policy

If `:op` is unknown or not implemented in current runtime:
- return canonical error frame with:
  - `:kind :error`
  - `:id` echoed when present
  - `:op` echoed when present
  - `:error-code "request/op-not-supported"`
  - `:error-message` stable, human-readable
  - optional `:data {:supported-ops [...]}`
- do not crash/disconnect transport solely for unsupported op.

Ops currently expected to start as unsupported until later implementation tasks land:
- `abort_retry`
- `bash`
- `abort_bash`
- `export_html`
- `get_fork_messages`
- `get_last_assistant_text`
- `get_commands`
- `get_available_models`
