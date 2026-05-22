# Steps

- [ ] Audit `skill-registry` callers/tests and record whether any true registration-order dependency exists.
- [ ] Decide whether canonical name-sorted listing is the narrowest replacement contract.
- [ ] Update `skill-registry` implementation and any affected higher projection/prompt/display code.
- [ ] Add or update focused tests to prove canonical deterministic listing while preserving duplicate-ignore and `:added?` / `:changed?`.
- [ ] Update `munera/open/164-registry-semantics-unification-audit/` to reflect the result.
