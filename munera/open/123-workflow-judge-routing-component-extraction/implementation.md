2026-05-07

Task created from workflow component-extraction review.

Creation rationale:
- workflow judge/routing is one of the cleanest remaining below-dispatch workflow seams
- it is primarily domain logic rather than session orchestration
- extracting it should reduce the size and ambiguity of a later workflow runtime-core extraction
- it provides a focused proof that workflow behavior can move downward cleanly when the ownership line is drawn at projection/normalization/routing rather than at public entrypoints

Initial boundary notes:
- authoritative extracted namespace family is expected to live under `psi.workflow-judge.*`
- canonical judge projection, verdict normalization, and routing evaluation should move downward
- judge-session creation and prompt submission should remain outside the extracted component
- mutations, resolvers, and `psi-tool` stay above the boundary
- do not leave compatibility shims unless implementation proves a very small temporary seam is necessary
