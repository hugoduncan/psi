# Wire Envelope Rules

All wire keys are EDN kebab-case keywords.

## Request Frame (stdin)
Required keys: `:id :kind :op`
Allowed keys: `:id :kind :op :params`
Constraints:
- `:kind` MUST be `:request`
- `:id` MUST be non-empty string
- `:op` MUST be non-empty string

## Response Frame (stdout)
Required keys: `:id :kind :op :ok`
Allowed keys: `:id :kind :op :ok :data`
Constraints:
- `:kind` MUST be `:response`
- `:ok` is boolean
- no extra envelope keys

## Error Frame (stdout)
Required keys: `:kind :error-code :error-message`
Allowed keys: `:kind :id :op :error-code :error-message :retryable :data`
Constraints:
- `:kind` MUST be `:error`
- no extra envelope keys

## Event Frame (stdout)
Required keys: `:kind :event :data`
Allowed keys: `:kind :event :data :id :seq :ts`
Constraints:
- `:kind` MUST be `:event`
- `:seq` (when present) MUST be monotonic increasing
- no extra envelope keys

## Transport Discipline
- Exactly one top-level EDN map per line.
- stdout in RPC mode is protocol-only (`:response | :event | :error`).
- diagnostics/logging go to stderr only.
