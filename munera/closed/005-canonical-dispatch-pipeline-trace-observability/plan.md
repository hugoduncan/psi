Approach:
- Extend the existing dispatch-owned trace model only where it adds clear diagnostic value.
- Prefer broadening real dispatch coverage over adding parallel debug channels.
- Make any new trace kinds explicit and queryable.

Likely steps:
1. inspect current gaps in dispatch-owned tracing
2. decide whether LSP findings become first-class dispatch events
3. decide whether service lifecycle transitions need explicit trace entries
4. broaden focused proof where new stages are added

Risks:
- trace bloat without diagnostic payoff
- duplicating data already visible in result-integrated forms
