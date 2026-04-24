# Implementation Notes

## Step 1: footer-model-fn

- `footer-model-fn` closure injected from app-runtime, calling `footer/footer-model ctx @tui-focus*`
- Defaulted to `(constantly {})` in `build-init` so tests that don't care about footer content get a minimal empty footer
- Removed `footer-data`, `footer-query` re-export from render.clj — single code path through `footer-model-fn`
- Removed `psi.app-runtime.footer` require from render.clj since it's no longer used there
- Existing footer tests migrated from `query-fn` to constructing footer models via `footer/footer-model-from-data` — cleaner because tests control the model shape directly
- Old test asserted `"R1.4M"` which was a substring match on `"CR1.4M"` — new test uses the precise `"CR1.4M"` prefix

## Step 3: notification lifecycle — tick ordering discovery

- `update-tick-state` reads the ui-snapshot *before* calling dismiss-expired/dismiss-overflow
- This means dismiss mutates the atom, but the current tick's snapshot was already captured
- The dismissed state becomes visible on the *next* tick's snapshot read
- Test accounts for this with three ticks: (1) initial with fresh notification, (2) after backdating triggers dismiss, (3) picks up the post-dismiss snapshot
- This is not a bug — it's the natural consequence of snapshot-before-side-effect ordering, and the real TUI ticks at ~8Hz so the one-tick delay is imperceptible

## Test suite results

- Full unit suite: 1330 tests, 10202 assertions, 0 failures
- Extension suite: 141 tests, 560 assertions, 0 failures
