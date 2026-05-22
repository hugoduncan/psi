# Plan

Implement the mandatory assistant/thinking streaming optimization first. Treat tool rows, widget subscription lookup, projection rendering, RPC parsing, and backend accumulator changes as conditional follow-up only if the mandatory slice exposes a covered need.

## Approach

### 1. Establish focused proof and instrumentation
Add focused ERT coverage in `components/emacs-ui/test/psi-streaming-transcript-test.el` before changing render behavior.

The tests should instrument narrow local helpers rather than wall-clock time:

- count full redraw helper invocations for assistant and thinking live blocks;
- record the ranges receiving stream-time verbatim/default text properties;
- count prefix overlay creation for each live block;
- assert buffer content and marker/range correctness after each event.

The expected pre-change observation is that append-only extension events still use the full redraw path and whole-content property range. Keep the behavior assertions stable while making the optimization-specific assertions drive the code change.

### 2. Split rendering into explicit append vs redraw helpers
In `components/emacs-ui/psi-assistant-render.el`, factor assistant/thinking line updates so the public event handlers have one obvious rule:

- compute the effective next text using existing semantics;
- if current rendered text is a prefix of the effective next text, append only the suffix;
- otherwise use the existing full redraw behavior.

Keep the initial block creation path separate from append and redraw paths. Preserve all existing marker registration, read-only marking, draft-anchor following, and finalization behavior.

Suggested helper shape:

- assistant:
  - create live assistant line when no live range exists;
  - append suffix inside the live assistant content before the trailing newline;
  - redraw whole live assistant line for fallback replacement;
  - apply stream-verbatim properties only to inserted suffix range on append;
  - create/apply the prefix overlay only on create or full redraw.
- thinking:
  - create live thinking line when no live range exists;
  - append suffix inside the live thinking content before the trailing newline;
  - redraw whole live thinking line for divergent/shrinking snapshots;
  - create/apply the prefix overlay only on create or full redraw.

### 3. Preserve stream contracts
For `assistant/delta`, preserve existing `psi-emacs--merge-assistant-stream-text` semantics. The append/redraw decision happens after calculating the effective next text.

For `assistant/thinking-delta`, preserve cumulative snapshot semantics: the incoming payload is the effective next text. Append only when the new snapshot extends the current rendered thinking text; redraw when it diverges or shrinks.

### 4. Verify finalization and marker safety
Ensure optimized append paths keep ranges valid for:

- `assistant/message` finalization after streamed assistant text;
- thinking archive/clear behavior;
- tool events inserted near assistant/thinking ranges;
- draft/input anchor following.

Use existing streaming marker-corruption tests as regression coverage and add targeted assertions only where current tests do not prove the optimized path.

### 5. Decide on conditional hotspots
After the mandatory assistant/thinking slice is green, inspect whether this task touched or needs to touch conditional hotspots:

- If tool rows are touched, add/maintain focused coverage in `psi-tool-output-mode-test.el` and implement only covered local changes.
- If widget subscription dispatch is touched, add/maintain focused coverage in `psi-widget-projection-events-test.el`.
- If projection/footer rendering is touched, add/maintain focused projection/session-tree coverage.

If none are touched, record in `implementation.md` that they were left unchanged because the task's mandatory success threshold was met without broadening scope.

## Verification

Focused mandatory run:

```sh
emacs -Q --batch -L components/emacs-ui \
  -l components/emacs-ui/test/psi-test-support.el \
  -l components/emacs-ui/test/psi-streaming-transcript-test.el \
  -f ert-run-tests-batch-and-exit
```

If conditional hotspots are touched, extend the focused run with the relevant suites:

```sh
emacs -Q --batch -L components/emacs-ui \
  -l components/emacs-ui/test/psi-test-support.el \
  -l components/emacs-ui/test/psi-streaming-transcript-test.el \
  -l components/emacs-ui/test/psi-tool-output-mode-test.el \
  -l components/emacs-ui/test/psi-widget-projection-events-test.el \
  -l components/emacs-ui/test/psi-session-tree-test.el \
  -f ert-run-tests-batch-and-exit
```

Full Emacs frontend regression run before closing:

```sh
bb emacs:test
```

Record exact commands and results in `implementation.md`.

## Risks

- Marker drift around assistant, thinking, and tool rows is historically fragile. Prefer small helper extraction and preserve existing marker insertion types.
- Read-only text and undo suppression must remain equivalent; streaming append paths should bind `buffer-undo-list` consistently with current stream behavior.
- Prefix overlay optimization must not leak faces to neighboring inserted text.
- Markdown/font-lock finalization should still happen only when assistant text is finalized, not during streaming append.
