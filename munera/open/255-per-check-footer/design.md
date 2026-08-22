# Per-Check Footer for Commit Checks

## Goal

Allow each command in `.psi/commit-checks.edn` to specify its own `:footer` string, so failure-specific instructions can be injected into the prompt for that particular check.

## Context

Currently `build-prompt` in `extensions/commit-checks/src/extensions/commit_checks.clj` appends a single hardcoded footer after all failure sections:

```
"Please inspect these failures and make the minimal necessary fixes."
```

This is too generic. Different checks (lint, test, format) benefit from different follow-up instructions.

## Design

Each entry in `:commands` may optionally include a `:footer` key (string). When a check fails and has a `:footer`, that text is appended to that specific failure section (after the output block). The global footer remains as a fallback for commands that do not specify one.

### Config shape

```edn
{:enabled true
 :prompt-header "Commit checks failed."
 :commands [
   {:id "lint"
    :cmd ["clj-kondo" "--lint" "src"]
    :footer "Fix the lint violations. Do not suppress warnings."}
   {:id "test"
    :cmd ["bb" "test"]
    :footer "Fix the failing tests. Do not modify test expectations."}
   {:id "format"
    :cmd ["bb" "fmt"]}
 ]}
```

### Prompt output (when lint and test fail)

```
Commit checks failed.

workspace-dir: /path/to/project
session-id: sid
commit: abc123

Failed checks:

## lint
command: clj-kondo --lint src
exit: 1
output:
...
Fix the lint violations. Do not suppress warnings.

## test
command: bb test
exit: 1
output:
...
Fix the failing tests. Do not modify test expectations.

Please inspect these failures and make the minimal necessary fixes.
```

### Rules

- `:footer` is optional per command; when absent, no per-section footer is emitted for that check.
- The global footer (`"Please inspect these failures and make the minimal necessary fixes."`) is always appended at the end of the combined prompt.
- `:footer` is a plain string, no interpolation or templating.

## Acceptance

- [ ] A command with `:footer` renders that text after its output section in the injected prompt.
- [ ] A command without `:footer` renders no per-section footer.
- [ ] The global footer is still present at the end of the combined prompt.
- [ ] Existing behaviour (no `:footer` on any command) is unchanged.
