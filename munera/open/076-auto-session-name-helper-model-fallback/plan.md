Plan:
1. inspect the current auto-session-name helper selection and helper-run flow to identify the narrowest extension-local seam for ordered fallback
2. shape the implementation so the extension consumes the existing ranked helper candidates without changing default selection policy or generic session runtime fallback semantics
3. add focused tests for exact ranked-order attempts, fallback success, invalid-title fallback, empty/exhausted fallback, and stale/manual guard termination
4. implement the extension-local retry loop so one sanitized checkpoint snapshot is reused across attempts, each attempt uses a fresh helper child session, and notification/no-op behavior remains coherent
5. run focused auto-session-name tests plus any directly impacted shared model-selection or helper-runtime tests, then record implementation notes
