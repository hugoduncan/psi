🔁 structural-payload-gate-classifies-by-key-presence

A policy gate that classifies events/data *structurally* by the presence of a key (task 241: RPC focus gate treats an event as session-scoped iff its emitted payload carries `:session-id`) avoids a second hand-curated set — but creates two hazards, both caught only after many review passes:

1. **Read the actual runtime payload, not the declared schema.** `required-event-payload-keys` did NOT list `:session-id` for `assistant/*`, `tool/*`, `session/rehydrated`, yet those payloads ARE stamped with it at emission. The gate must inspect what is really emitted; declared key sets and runtime shape diverge silently.

2. **Any payload that gains the classifying key silently flips classification.** Cross-session events (`command-result`, `error`, `context/updated`) stay "not gated" ONLY while free of a bare `:session-id`. `session_switch`'s command-result and legacy-prompt `assistant/message` were latent suppressions. Fix by keeping the classifying key out of payloads that must not be classified (rename to `:target-session-id`, use `:active-session-id`), NOT by event-string special-casing the gate. Pin every branch with a characterization test asserting the intended emit/suppress under a non-focused session, so a future stamp is a loud behavioural change, not silent drift.
