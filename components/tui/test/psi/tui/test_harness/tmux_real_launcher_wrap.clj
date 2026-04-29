(ns psi.tui.test-harness.tmux-real-launcher-wrap
  "Deprecated experimental real-launcher resume-wrap scenario.

   This file is intentionally retained only as historical scaffolding while task
   066 converges on the startup-wrap proof as the stable launcher-bound width
   contract. The previous resume-driven scenario relied on interactive resume
   selection semantics that are not stable in CI and should not gate width
   policy verification.")

(defn write-wrap-fixture!
  [& _]
  (throw (ex-info "tmux-real-launcher-wrap fixture helper is deprecated"
                  {:deprecated true})))

(defn delete-wrap-fixture!
  [& _]
  nil)

(defn run-real-launcher-wrap-scenario!
  [& _]
  {:status :skipped
   :reason :deprecated-scenario
   :warning "Deprecated: use tmux-startup-wrap as the stable launcher-bound width proof."})
