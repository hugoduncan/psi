Approach:
- Treat warning cleanup as a separate backlog burn-down now that hard errors are gone.
- Work in area-based slices so each pass is understandable and low-risk.
- Start with the highest-volume / lowest-risk categories first: unused requires, unused bindings, and unused private vars.
- Re-run `bb lint` after each slice and record the warning count trend.

Suggested slice order:
1. TUI split-file warning cleanup
2. RPC test warning cleanup
3. agent-session unused require/binding cleanup
4. extension warning cleanup
5. remaining scattered warnings (redundant lets, duplicate requires, inline defs, unresolved aliases)

Risks / watchpoints:
- turning warning cleanup into behavior-changing refactors
- removing vars or requires that are only indirectly referenced through unusual loading patterns
- losing reviewability by mixing too many subsystems into one commit
