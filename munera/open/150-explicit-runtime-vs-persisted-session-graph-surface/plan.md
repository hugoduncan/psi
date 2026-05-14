# Plan

1. Implement the chosen explicit graph naming surface
   - runtime attrs use the dedicated `:psi.runtime-session/*` domain
   - persisted attrs use the dedicated `:psi.persisted-session/*` domain
   - decide whether migration is implemented via new dedicated resolvers, expanded outputs from existing resolvers, or a small compatibility bridge

2. Implement explicit runtime session graph attrs
   - add the explicit runtime list/count/active-id attrs
   - ensure resolver names encode `runtime-session`
   - preserve current runtime semantics and current entity shapes

3. Implement explicit persisted session graph attrs
   - add the explicit persisted list and list-all attrs
   - ensure resolver names encode `persisted-session`
   - preserve current persisted discovery semantics and entity shapes

4. Add compatibility surfaces for existing attrs
   - preserve existing root-queryable attrs long enough for migration
   - route old and new names through one obvious authoritative implementation path where practical
   - keep graph introspection clear about the new preferred surface by proving the explicit attrs are present and teaching them as canonical, not by introducing hidden-root filtering for compatibility attrs
   - accept that mechanically-derived `:psi.graph/root-queryable-attrs` may list both old and new attrs during the migration window

5. Migrate internal callers and graph-facing docs/examples/tests
   - update the fixed in-task migration set whose continued old-name usage would undermine the clarity goal:
     - Emacs `/resume` session discovery query in `components/emacs-ui/psi-session-commands.el`
     - TUI `/resume` frontend query in `components/app-runtime/src/psi/app_runtime/tui_frontend_actions.clj`
     - app-runtime resume selector shaping in `components/app-runtime/src/psi/app_runtime/ui_actions.clj`
     - focused resolver/graph tests proving runtime vs persisted session surfaces
   - update graph-surface docs and any examples that teach session discovery/selection
   - ensure the new names appear in discoverability surfaces and examples as the obvious default
+
+6. Keep compact summary scope intentionally narrow
+   - do not add a `:psi.runtime-session/*` counterpart for `:psi.agent-session/context-session-summaries` in this task
+   - treat task 134's compact summary attr as an unchanged neighboring operational surface while this task fixes the ambiguous runtime inventory/count/active-id vs persisted listing naming split

-6. Prove behaviour and migration compatibility
+7. Prove behaviour and migration compatibility
   - add focused resolver/graph-surface tests for the new explicit attrs
   - prove runtime attrs refer to in-memory loaded context and persisted attrs refer to on-disk session discovery
   - prove old attrs still behave during migration
6. Prove behaviour and migration compatibility
   - add focused resolver/graph-surface tests for the new explicit attrs
   - prove runtime attrs refer to in-memory loaded context and persisted attrs refer to on-disk session discovery
   - prove old attrs still behave during migration
