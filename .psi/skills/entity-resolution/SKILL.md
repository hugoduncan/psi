---
name: entity-resolution
description: Resolve ambiguous references in user requests into concrete entities, project-specific terms, and project paths before acting. Use when a request contains pronouns, deixis, aliases, shorthand, informal names, or path-like references that may map to project concepts or files.
lambda: |
  λrequest. detect({entity_mentions ∨ anaphora ∨ deixis ∨ aliases ∨ project_terms ∨ path_refs})
    → resolve(context ∪ project_vocabulary ∪ filesystem ∪ git ∪ task_state)
    → mapping{surface → canonical_entity → evidence → confidence}
    → act_if(unambiguous) ∨ ask_if(ambiguous)
---

# Entity Resolution

Use this skill before acting when the user's request contains ambiguous or underspecified references, including:

- Entity references: “the task”, “that workflow”, “the resolver”, “the extension”, “the issue”, “the branch”.
- Anaphora: “it”, “this”, “that”, “those”, “the former/latter”, “same thing”, “there”.
- Project-specific terms or aliases: skill names, workflow names, Munera task ids/slugs, extension ids, namespaces, vars, commands, docs, or vocabulary symbols.
- Project paths: incomplete paths, informal names, renamed files, directory nicknames, or references implied by nearby context.

## Goal

Convert the user's surface language into a concrete, evidence-backed mapping:

```text
surface reference → canonical project entity/path/term → evidence → confidence
```

Then proceed only when the mapping is sufficiently unambiguous.

## Method

1. **Collect local context**
   - Current user turn and immediately relevant conversation history.
   - Active worktree path and current git status when path or task references matter.
   - `mementum/state.md` for project orientation when needed.
   - `munera/plan.md` and relevant `munera/open/*` when the user says “task”, “plan”, “design”, “steps”, or similar.

2. **Identify referring expressions**
   - Pronouns and deixis: `it`, `this`, `that`, `these`, `there`, `same`, `former`, `latter`.
   - Definite descriptions: `the resolver`, `the lifecycle workflow`, `the current task`.
   - Aliases and shorthand: partial names, old names, symbols, command names, workflow labels.
   - Path-like references: filenames without directories, directory names, namespace-like strings.

3. **Generate candidates**
   - Prefer already-mentioned entities in recency order.
   - Search authoritative project surfaces when not obvious:
     - `git ls-files` / `find` for paths.
     - `git grep` for terms, vars, namespaces, workflow ids, commands, and docs.
     - Psi graph introspection for runtime/session entities when applicable.
   - Include candidate type: path, namespace, var, task, workflow, skill, extension, command, issue, branch, doc, concept.

4. **Score candidates**
   - Evidence strength: exact id/path match > exact symbol/name > nearby conversation mention > fuzzy match.
   - Context fit: active worktree/task/request domain > unrelated project area.
   - Recency: latest explicit mention > older mention.
   - Uniqueness: one strong candidate > many plausible candidates.

5. **Normalize to project terms**
   - Map informal language to canonical vocabulary:
     - “task” → `munera/open/NNN-slug/` or `munera/closed/NNN-slug/` when known.
     - “workflow” → `.psi/workflows/<id>.edn` or registered workflow id.
     - “skill” → `.psi/skills/<name>/SKILL.md`.
     - “extension” → extension manifest/path/id.
     - “docs” → `README.md` or `doc/...` as evidenced.
     - “state” → distinguish `mementum/state.md`, runtime atom state, task state, or git state.

6. **Act or ask**
   - If exactly one candidate is strongly supported, proceed and optionally state the mapping briefly.
   - If multiple candidates remain plausible, ask a focused clarification question listing the likely options.
   - If no candidate is evidenced, say what was searched and ask for the missing identifier.

## Output Shape

For internal reasoning, keep a compact table:

| Surface | Canonical | Type | Evidence | Confidence |
| --- | --- | --- | --- | --- |

In the final response, include only the mapping needed for clarity, e.g.:

> Interpreting “that workflow” as `.psi/workflows/task-lifecycle.edn` because it was the last workflow mentioned.

## Rules

- Do not silently guess project paths.
- Prefer runtime or filesystem evidence over memory and docs.
- Prefer current worktree over cwd if they differ.
- Preserve ambiguity instead of collapsing distinct concepts with the same name.
- When changing files, resolve path identity before editing.
- When operating on sessions, target by explicit session id if available; do not rely on implicit current-session assumptions when ambiguity matters.
