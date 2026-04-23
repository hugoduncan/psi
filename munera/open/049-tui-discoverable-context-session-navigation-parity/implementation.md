Implementation notes

Use this file as append-only local memory while executing task 049.

Initial expectations
- backend-projected `session-tree-widget` remains authoritative for visible session/context navigation in the normal TUI view
- TUI should own only storage, rendering, interaction, and cleanup for that projected widget
- `/tree` may remain as direct command plumbing, but it must not drift into a competing tree model

Execution log

- Pending implementation.

Decisions

- None yet.

Discoveries

- None yet.

Risks / snags

- None yet.
