# Implementation notes

2026-05-30 — Design ambiguity review: found two actionable ambiguities. The design moves retry execution to the prepared provider-request boundary but leaves the authoritative completed retry-history storage/EQL projection source underspecified, and it requires preserving active TUI/Emacs/app-runtime retry status without defining how lower-boundary retry sleep updates and clears the existing session retry projection.


2026-05-30 — Ambiguity follow-up complete: clarified that completed provider retry history is authoritative in session-owned provider telemetry captures, keyed by session/turn/provider-request/attempt and projected through EQL/`psi-tool` from telemetry rather than UI state. Also clarified active retry/backoff visibility: provider-boundary retry publishes pending delay metadata into the existing session retry projection before sleeping, preserves `:retrying`-style app-runtime/TUI/Emacs visibility, and clears active fields after retry resume or terminal outcome while telemetry remains the completed-history source.
