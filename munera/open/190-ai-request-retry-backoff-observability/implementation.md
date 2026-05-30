# Implementation notes

2026-05-30 — Design ambiguity review: found two actionable ambiguities. The design moves retry execution to the prepared provider-request boundary but leaves the authoritative completed retry-history storage/EQL projection source underspecified, and it requires preserving active TUI/Emacs/app-runtime retry status without defining how lower-boundary retry sleep updates and clears the existing session retry projection.
