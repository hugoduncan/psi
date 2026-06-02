# Design follow-up steps

- [x] Clarify the design/doc wording for absolute `:at` bounds in terms of the positive resolved millisecond delay: future instants resolving to 1–999ms are rejected by the minimum bound, while delay 0 (including past/now, and any sub-millisecond future instant that truncates to 0) fires immediately; avoid wording that implies every future instant below `min-delay-ms` is rejected.
