# `query_eql` Request Contract

Canonical input contract for wire requests:
- `:params {:query <string>}` where `<string>` parses as EDN vector query.

Validation outcomes:
- not EDN => canonical request/protocol error
- EDN but non-vector => canonical request error
- vector => run via live runtime query path

Success response shape:
- `{:id ... :kind :response :op "query_eql" :ok true :data {:result <query-result>}}`

Parity expectation:
- queries containing `:psi.graph/*` and `:psi.memory/*` must return values when runtime context provides them.
