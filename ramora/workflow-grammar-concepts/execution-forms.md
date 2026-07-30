# Execution Forms

The grammar has three step execution forms:

- `:type :invoke`
- `:type :session`
- `:type :delegate`

These are mutually exclusive.

All three step forms may author `:yields`. When omitted, the runtime applies the default yielded-value rule for that step type.

## Invoke

`:type :invoke` describes deterministic execution.

An invoke step names:

- `:operation`
- `:args`

It is intended for operations that are deterministic, code-backed, and structurally data-oriented.

## Session

`:type :session` describes inline child-session construction.

A session step names inline session-construction fields such as:

- `:model`
- `:tools`
- `:skills`
- `:contributions`

Its purpose is to explicitly describe the child session to be run and the conversation that will be assembled for that session.

## Delegate

`:type :delegate` describes delegation to an existing named workflow.

A delegate step names:

- `:target`
- `:prompt-string`
- optional `:context`

Its purpose is to call a reusable workflow while making the delegation boundary explicit.

## Session Construction

Session construction is the inline specification of a child session.

The grammar models session construction as:

- configuration fields such as model/tools/skills
- ordered `:contributions`

The assembled result of these contributions is the child-session conversation that will be executed.

The grammar intentionally does not use separate canonical fields such as:

- `:prompt`
- `:input`
- `:reference`
- `:preload`

Instead, these concerns are subsumed by ordered contribution assembly.
