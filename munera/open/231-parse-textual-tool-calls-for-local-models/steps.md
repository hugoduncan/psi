# 231 — Steps

## Slice 1 — Capability model surface

- [ ] Inspect existing model capability schemas/loading paths in `components/ai` and identify the lowest-level namespace for textual-tool-call capability helpers.
- [ ] Extend model capability schema/validation to accept `{:textual-tool-calls #{:xml}}` without requiring the key on existing/default models.
- [ ] Add a pure predicate such as `supports-textual-tool-calls-format?` that checks the resolved model map for a requested format.
- [ ] Add focused tests that custom/user model maps can declare `#{:xml}` and that omitted/empty capabilities return disabled.

## Slice 2 — Pure XML-like parser

- [ ] Implement a pure parser for exact lowercase `<tool_call>...</tool_call>` blocks containing exactly one `<function=TOOL_NAME>...</function>` block.
- [ ] Enforce `[A-Za-z0-9_-]+` for tool and parameter names and reject whitespace, attributes, namespaces, dots, slashes, quotes, and entity decoding.
- [ ] Parse one or more nested `<parameter=PARAM_NAME>...</parameter>` blocks, trimming only at tag boundaries and preserving internal text/newlines/metacharacters.
- [ ] Treat duplicate parameter names, missing/multiple function blocks, zero parameters, or parameter blocks outside the function as malformed block-level no-ops.
- [ ] Return enough information to remove only successfully parsed exact block spans while leaving malformed/partial markup unchanged.
- [ ] Add parser tests for nominal `bash`, multiple calls, multi-parameter calls, duplicate parameters, malformed cardinality/nesting, unsupported case/grammar variants, and surrounding-text preservation.

## Slice 3 — Canonical normalization

- [ ] Implement one pure normalizer that takes `turn-id`, resolved `ai-model`, assistant text/content, and existing provider tool calls and returns canonical assistant content.
- [ ] Gate parsing entirely on the resolved model capability containing `:xml`; when disabled, return text/content unchanged.
- [ ] Convert each parsed call to canonical `{:type :tool-call :id ... :name ... :arguments ...}` content using JSON object string arguments.
- [ ] Generate ids with the same per-turn canonical id convention used for provider tool calls when parsed markup has no id.
- [ ] Preserve response order for multiple parsed blocks and surrounding text while removing exact parsed blocks from assistant prose.
- [ ] Keep malformed or partial markup as ordinary text and continue converting other well-formed blocks in the same response.
- [ ] Add normalizer tests for enabled/disabled capability, canonical JSON arguments, multiple calls order, mixed valid/malformed markup, and text removal/preservation.

## Slice 4 — Turn-runtime integration

- [ ] Wire streaming final assembly to call the shared normalizer after text accumulation and provider tool-call completion but before delivering the final assistant message.
- [ ] Wire non-streaming assistant responses to call the same shared normalizer before returning the turn result.
- [ ] Ensure the integration uses the already-resolved runtime `ai-model` passed through the turn attempt and does not require `agent-session` from `turn-runtime`.
- [ ] Preserve existing thinking, error, logprob, usage, and structured-output behavior when no textual tool-call parsing occurs.
- [ ] Add/adjust turn-runtime tests for streaming and non-streaming normalization paths where practical.

## Slice 5 — Existing execution-path coverage

- [ ] Add an end-to-end or focused turn/session test proving a capability-enabled textual `bash` block reaches the existing tool execution path and records an ordinary tool result.
- [ ] Add coverage that unknown or unavailable parsed tool names follow the same failure/policy behavior as canonical provider-emitted tool calls.
- [ ] Add coverage that frontier/default models preserve textual `<tool_call>` content as assistant text and execute no tool.
- [ ] Run focused Scry suites for parser/model/turn-runtime/session tool-call behavior and fix regressions.
- [ ] Run `clj-kondo` on changed Clojure files and fix warnings rather than suppressing globally.

## Slice 6 — Docs and changelog

- [ ] Update custom/local model documentation to show `{:capabilities {:textual-tool-calls #{:xml}}}` opt-in.
- [ ] Document that frontier/provider-native models should not enable textual tool-call recovery and that malformed markup is left as text.
- [ ] Add a user-visible `CHANGELOG.md` entry under `[Unreleased]` for the new local-model compatibility capability.
- [ ] Re-run relevant docs/tests checks if available.
