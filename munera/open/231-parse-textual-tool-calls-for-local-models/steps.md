# 231 — Steps

## Slice 1 — Capability model surface

- [x] Inspect existing model capability schemas/loading paths in `components/ai` and identify the lowest-level namespace for textual-tool-call capability helpers.
- [x] Extend model capability schema/validation to accept `{:textual-tool-calls #{:xml}}` without requiring the key on existing/default models.
- [x] Add a pure predicate such as `supports-textual-tool-calls-format?` that checks the resolved model map for a requested format.
- [x] Add focused tests that custom/user model maps can declare `#{:xml}` and that omitted/empty capabilities return disabled.

## Slice 2 — Pure XML-like parser

- [x] Implement a pure parser for exact lowercase `<tool_call>...</tool_call>` blocks containing exactly one `<function=TOOL_NAME>...</function>` block.
- [x] Enforce `[A-Za-z0-9_-]+` for tool and parameter names and reject whitespace, attributes, namespaces, dots, slashes, quotes, and entity decoding.
- [x] Parse one or more nested `<parameter=PARAM_NAME>...</parameter>` blocks, trimming only at tag boundaries and preserving internal text/newlines/metacharacters.
- [x] Treat duplicate parameter names, missing/multiple function blocks, zero parameters, or parameter blocks outside the function as malformed block-level no-ops.
- [x] Return enough information to remove only successfully parsed exact block spans while leaving malformed/partial markup unchanged.
- [x] Add parser tests for nominal `bash`, multiple calls, multi-parameter calls, duplicate parameters, malformed cardinality/nesting, unsupported case/grammar variants, and surrounding-text preservation.

## Slice 3 — Canonical normalization

- [x] Implement one pure normalizer that takes `turn-id`, resolved `ai-model`, assistant text/content, and existing provider tool calls and returns canonical assistant content.
- [x] Gate parsing entirely on the resolved model capability containing `:xml`; when disabled, return text/content unchanged.
- [x] Convert each parsed call to canonical `{:type :tool-call :id ... :name ... :arguments ...}` content using JSON object string arguments.
- [x] Preserve provider-emitted tool-call ids and indexes when present, and generate recovered-call ids with the same per-turn canonical id convention using final-order, non-colliding content indexes.
- [x] Preserve response order for residual text, provider-emitted tool calls, and recovered textual tool calls while removing exact parsed blocks from assistant prose.
- [x] Keep malformed or partial markup as ordinary text and continue converting other well-formed blocks in the same response.
- [x] Add normalizer tests for enabled/disabled capability, canonical JSON arguments, multiple calls order, mixed valid/malformed markup, provider/recovered/text interleaving, id/index non-collision, and text removal/preservation.

## Slice 4 — Turn-runtime integration

- [x] Wire streaming final assembly to call the shared normalizer after text accumulation and provider tool-call completion but before delivering the final assistant message.
- [x] Wire non-streaming assistant responses to call the same shared normalizer before returning the turn result.
- [x] Ensure the integration uses the already-resolved runtime `ai-model` passed through the turn attempt and does not require `agent-session` from `turn-runtime`.
- [x] Preserve existing thinking, error, logprob, usage, and structured-output behavior when no textual tool-call parsing occurs.
- [x] Add/adjust turn-runtime tests for streaming and non-streaming normalization paths where practical.

## Slice 5 — Existing execution-path coverage

- [x] Add an end-to-end or focused turn/session test proving a capability-enabled textual `bash` block reaches the existing tool execution path and records an ordinary tool result.
- [x] Add coverage that unknown or unavailable parsed tool names follow the same failure/policy behavior as canonical provider-emitted tool calls.
- [x] Add coverage that frontier/default models preserve textual `<tool_call>` content as assistant text and execute no tool.
- [x] Run focused Scry suites for parser/model/turn-runtime/session tool-call behavior and fix regressions.
- [x] Run `clj-kondo` on changed Clojure files and fix warnings rather than suppressing globally.

## Slice 6 — Docs and changelog

- [x] Update custom/local model documentation to show `{:capabilities {:textual-tool-calls #{:xml}}}` opt-in.
- [x] Document that frontier/provider-native models should not enable textual tool-call recovery and that malformed markup is left as text.
- [x] Add a user-visible `CHANGELOG.md` entry under `[Unreleased]` for the new local-model compatibility capability.
- [x] Re-run relevant docs/tests checks if available.

## Implementation review follow-ups

- [x] Preserve original streaming content-index order when provider-emitted tool calls, recovered textual tool calls, and residual text coexist; the current streaming assembly still builds one text block before all provider tool calls, so the normalizer cannot satisfy the design requirement for content-position ordering in mixed responses.
- [x] Preserve provider-surface content-index information through normalization/id allocation so recovered textual tool-call ids are generated after considering provider tool-call content indexes even when provider calls have non-canonical provider ids.
- [x] Allow literal `<function=...>` text inside parameter values; the parser currently rejects any function body containing that substring, which can incorrectly make otherwise valid command text malformed instead of preserving parameter text.
- [x] Preserve streaming interleaving when text arrives in multiple provider content indexes around provider tool calls; the current accumulator still merges all text deltas into one `:text-buffer` at the first text index, so `text(index 0) → provider tool(index 1) → text/recovered call(index 2)` is emitted before the provider tool instead of in source order.
- [x] Recover later well-formed textual tool-call blocks when an earlier malformed or partial `<tool_call>` prefix is present; the current non-overlapping regex scan can consume `broken <tool_call> ... <tool_call>valid...</tool_call>` as one malformed block and prevent conversion of the valid inner/later exact block, contrary to mixed malformed/well-formed response semantics.
- [x] Allocate recovered textual tool-call ids/content indexes in final content order after preceding provider-emitted blocks; the current allocator starts at `0`, so a recovered call in text at provider content-index `2` after a provider tool at index `1` can receive `turn-id/toolcall/0`, which conflicts with the content-position convention even though it avoids id collision.
- [x] Preserve streaming provider content-index metadata until after textual-tool-call normalization; `build-final-content` currently strips `:content-index` before `normalize-assistant-message`, so a recovered call after a provider tool at content-index `1` can still be assigned `turn-id/toolcall/1` instead of an index allocated after the provider/text positions. Add a streaming regression that fails on the current `provider index 1 → text/recovered index 2` case.
- [x] Preserve literal `<tool_call>` / `</tool_call>` text inside parameter values; the parser currently rejects any function body containing those substrings, which can make otherwise valid command text malformed instead of preserving parameter text.
- [x] Do not reserve a removed source text block's content index when the block is fully replaced by a recovered textual tool call; a provider-indexed text block at content-index `2` containing only `<tool_call>...</tool_call>` should yield recovered id `turn-id/toolcall/2`, not skip to `/3` because the removed text block's index was treated as already used.
- [ ] Preserve non-collision with existing canonical tool-call ids when reusing a fully replaced text block's source content index; if existing content already has `turn-id/toolcall/2` and a fully replaced text block also has `:content-index 2`, the recovered call must not also receive `turn-id/toolcall/2`.
