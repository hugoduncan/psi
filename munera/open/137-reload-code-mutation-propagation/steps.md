# Steps — 137 reload-code mutation propagation

## Checklist

- [ ] 1. Add `all-mutations-atom` and `all-mutations-in` to `context.clj`
- [ ] 2. Migrate callers: `psi_tool.clj`, `tool_plan.clj`, `runtime_eql.clj`
- [ ] 3. Implement real `:mutation-registration-refresh` step in `execute-psi-tool-reload-report`
- [ ] 4. Remove/fix throwaway `qctx` in `refresh-query-runtime!`
- [ ] 5. Add test: reload propagates new mutations to `all-mutations-in`
- [ ] 6. Run existing reload tests; verify no regressions
- [ ] 7. Lint: `clj-kondo --lint src`
- [ ] 8. Commit and push
