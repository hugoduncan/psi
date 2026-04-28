Approach:
- Continue converging any remaining prompt semantics into request preparation.
- Use the agent skill-prelude follow-on (`006`) as the concrete driver for cache-breakpoint shaping and any associated prelude-contract decisions.
- Keep this task (`003`) as the broader umbrella for prompt-lifecycle ownership/convergence rather than duplicating the detailed implementation checklist from `006`.
- Keep shared-session lifecycle paths canonical; leave isolated workflow runtimes alone.

Likely steps:
1. inspect current request preparation and skill-prelude composition
2. shape the intended reusable-prelude vs variable-tail cache breakpoints
3. remove or simplify residual seams/comments/hooks
4. add or tighten focused proof

Risks:
- changing cache behavior without good observability
- accidentally breaking non-agent prompt flows while refining agent prelude logic
