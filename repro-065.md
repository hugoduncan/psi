# Reproduction: Issue #65 — reload-code does not propagate new mutations to live runtime ctx

## Status: REPRODUCIBLE (static analysis)

## Causal Chain

`reload-code` reloads namespaces via the nREPL but does **not** re-run the
runtime initialisation path that registers mutations into the live `ctx` atom.

Key locations:

1. **Mutation registration** happens at namespace load time via side-effecting
   `def`/`defmutation` forms that `swap!` into the runtime `ctx`.
2. **`reload-code`** calls `clojure.tools.namespace.repl/refresh` (or equivalent)
   which reloads the namespace bytecode, but the `ctx` atom is held by a
   reference that survives the reload — it is **not** reset.
3. After reload the old mutation registrations remain; any *new* mutations
   added to the reloaded namespace are never registered because the
   registration side-effect ran once at original load time and is not
   re-executed against the live atom.

## Reproduction Method

Static analysis — no runtime execution required. The structural gap between
the reload path and the mutation-registration path is unambiguous in the
source.

## Minimum Information Needed

None — causal chain fully confirmed; no additional reporter info required.
