# Context Management — Overview

## Intent

Replace the current monolithic journal-as-context model with an extension-owned
IR pipeline. The core session stores structured IR representations of each turn
and of session/project state. An extension (the *context manager*) projects
those IRs into the provider-neutral conversation, controls what is retained, and
processes replies out-of-band to maintain derived state.

This gives the extension full authority over context shape, compaction strategy,
and memory extraction — while the core owns only lifecycle hooks, opaque
extension IR storage, and the journal as the rebuildable source of truth.

## Why

Current problems:

- **Journal is the context**: raw messages accumulate; compaction is a blunt
  cut-and-summarise that loses structure.
- **Compaction is core-owned**: the summarisation prompt, cut-point logic, and
  rebuild are baked into `psi.agent-session.compaction`. Extensions can only
  override the final summary via `session_before_compact`.
- **No structured turn semantics**: user input, assistant reply, tool calls,
  and tool results are all the same `:message` journal entry kind. Nothing
  distinguishes intent from observation from side-effect.
- **No project state projection**: the LLM sees conversation history but has no
  structured, up-to-date view of the project (open tasks, git state, file
  changes, mementum state).
- **Reply processing is inline**: anything that wants to react to a reply
  (memory extraction, state updates) must happen synchronously in the turn
  loop or via extension event handlers that see raw messages.

IR-based context management solves these by:

1. Giving each turn a **structured IR** that the extension can query, project,
   and summarise at will.
2. Giving the session and project **state IRs** that the extension can
   materialise into the conversation at the right time.
3. Making the **context projection** (which provider-neutral conversation
   entries are retained, summarised, or added before provider adaptation) an
   extension concern, not a provider-specific concern.
4. Providing **out-of-band reply processing** so memory extraction and state
   updates don't block the user.
