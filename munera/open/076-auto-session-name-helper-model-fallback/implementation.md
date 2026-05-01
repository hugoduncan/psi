Implementation:
- auto-session-name now consumes the ranked helper candidates from `resolve-selection` via `[:ranking :ranked]` and treats that ordered vector as its fallback sequence
- one sanitized bounded conversation snapshot is still computed once per checkpoint attempt and reused across all helper model attempts
- each helper attempt creates a fresh child session, runs the same prompt contract with a different helper model, and always closes/forgets the helper session after the attempt
- fallback continues on thrown helper execution, unsuccessful helper run, or invalid normalized title
- fallback terminates immediately when stale-checkpoint or manual-override guards become true before a later attempt or before rename application
- rename still happens at most once and still uses the existing validation/manual/stale guards
- when the ranked candidate list is empty or all candidates fail, the extension preserves the existing single no-op notification outcome after exhaustion

Tests added/updated:
- failed first candidate falls back to succeeding second candidate in exact ranked order
- invalid-title first candidate falls back to succeeding later candidate
- empty ranked candidate list behaves as exhaustion without rename
- stale checkpoint during fallback stops later attempts
- manual override during fallback stops later attempts
- runtime throw path now asserts cleanup across multiple fallback attempts
