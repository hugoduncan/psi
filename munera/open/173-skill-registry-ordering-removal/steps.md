# Steps

- [ ] Audit `skill-registry` callers/tests and record whether any true registration-order dependency exists.
- [ ] Decide whether canonical name-sorted listing is the narrowest replacement contract or whether a real insertion-order dependency requires the keep-order branch.
- [ ] If using the removal branch, update `skill-registry` implementation and any affected higher projection/prompt/display code.
- [ ] If using the keep-order branch, make no ordering-removal code change; document the confirmed dependency and ensure it is test-backed.
- [ ] Add or update focused tests to prove the selected ordering contract while preserving duplicate-ignore and `:added?` / `:changed?`.
- [ ] Update `munera/open/164-registry-semantics-unification-audit/` to reflect the selected branch.
