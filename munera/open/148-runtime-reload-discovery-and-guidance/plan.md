Approach:
- add a narrow runtime introspection attr for the reload source root
- keep semantics explicit: this is the runtime source root when running from source, nil otherwise
- update psi-tool docs and system prompt examples to show query-first reload discovery
- add focused tests for the resolver and prompt guidance

