# Implementation

Task created to plan a root-registry-backed adapter migration for `skill-registry`.

Initial design decision:

- do not move skills into long-lived global root-state storage
- keep session `:skills` as the public and persisted compatibility shape
- use `root-registry` internally as the keyed storage/deduplication/listing substrate for skill collection transformations
- map lower duplicate-id results to the current public duplicate-ignore/no-change skill result
- preserve task `173` canonical exact-name ordering across registry and model-visible skill-list surfaces

Rationale:

`skill-registry` is now semantically close to `root-registry` after task `173`, but it remains session-local and has domain-specific result metadata. The safest alignment is therefore adapter-backed shared mechanics, analogous in spirit to task `171`, rather than direct global storage ownership.
