# Plan

1. Confirm the precise adapter boundary:
   - session state remains a `:skills` vector
   - `root-registry` is used as an internal transformation substrate
   - no root-registry entries or lower result maps leak through `skill-registry`

2. Implement a local skill adapter over `root-registry`:
   - declare a dedicated skill registry id in temporary root state
   - map skill `:name` to root-registry entry `:id`
   - use a stable artificial owner such as `:session`
   - load existing vectors through lower insert semantics so first occurrence wins
   - use `root-registry/insert` for new registration
   - project entries back to canonical exact-name-sorted skill vectors

3. Preserve public `skill-registry` behavior exactly:
   - validation and invalid-name exceptions
   - exact lookup and nil miss
   - duplicate-ignore / first-write-wins
   - `:added?` / `:changed?` result metadata
   - canonical vector results even for duplicate/no-change paths

4. Audit and update affected tests:
   - focused `skill-registry` unit tests for add, duplicate, unsorted existing input, exact lookup, and count
   - dispatch proof that duplicate/no-change canonicalization persists without prompt refresh
   - representative higher ordered surfaces from task `173` to ensure canonical skill-name ordering still holds

5. Update task `164`:
   - reclassify `skill-registry` from helper-only candidate to root-registry-aligned adapter-backed session-local collection
   - preserve the note that public duplicate-ignore and change-reporting remain adapter-owned semantics

6. Verify:
   - focused lower and higher tests
   - full `bb test` before close
