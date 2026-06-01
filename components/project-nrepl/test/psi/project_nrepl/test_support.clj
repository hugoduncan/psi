(ns psi.project-nrepl.test-support
  "Shared component-local test helpers for the project-nrepl component tests.

   Consolidates the per-file copies of `make-ctx`, `session-ctx-at`,
   `install-instance!`, `seed-connector!`, `temp-dir`, `delete-tree!`,
   `session-fn-with-id`, and `fake-connector` so the seeded `:runtime-handle`
   shape, session-construction shape, and temp-dir lifecycle stay identical
   across the eval/commands/ops/attach/started/client tests."
  (:require
   [clojure.java.io :as io]
   [psi.project-nrepl.runtime :as project-nrepl-runtime]
   [psi.agent-session.test-support :as test-support]))

(defn session-ctx-at
  "A non-persisting test ctx + session-id whose `ss/session-worktree-path-in`
   resolves to `worktree-path`. Single-sources the
   `{:persist? false :session-defaults {:worktree-path …}}` session-construction
   shape shared by `make-ctx` and the commands_test dispatch tests. Returns
   `[ctx session-id]`."
  [worktree-path]
  (test-support/create-test-session
   {:persist? false
    :session-defaults {:worktree-path worktree-path}}))

(defn make-ctx
  "A non-persisting test ctx from a fresh test session (no session-id needed)."
  []
  (let [[ctx _] (session-ctx-at (System/getProperty "user.dir"))]
    ctx))

(defn install-instance!
  "Install a real managed attached instance at `worktree-path` with an in-memory
   `[:runtime-handle :client-session]` fn (the eval_test seam pattern)."
  [ctx worktree-path client-session]
  (project-nrepl-runtime/ensure-instance-in!
   ctx
   {:worktree-path worktree-path
    :acquisition-mode :attached
    :endpoint {:host "127.0.0.1" :port 7888 :port-source :explicit}})
  (project-nrepl-runtime/update-instance-in!
   ctx worktree-path
   #(assoc %
           :lifecycle-state :ready
           :readiness true
           :active-session-id "nrepl-session-1"
           :runtime-handle {:client-session client-session
                            :session-id "nrepl-session-1"})))

(defn seed-connector!
  "Ensure a managed attached instance at `worktree-path` and seed `connector`
   under `[:runtime-handle :nrepl-connector]` (the `connect-instance-in!` seam).
   Single-sources the ensure ceremony and the endpoint map shared by the
   client_test connector-seam tests."
  [ctx worktree-path connector]
  (project-nrepl-runtime/ensure-instance-in!
   ctx
   {:worktree-path worktree-path
    :acquisition-mode :attached
    :endpoint {:host "127.0.0.1" :port 7888 :port-source :explicit}})
  (project-nrepl-runtime/update-instance-in!
   ctx worktree-path
   #(assoc-in % [:runtime-handle :nrepl-connector] connector)))

(defn temp-dir
  "Create a fresh temp directory and return its absolute path string."
  [prefix]
  (str (java.nio.file.Files/createTempDirectory
        prefix
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn delete-tree!
  "Recursively delete the file tree rooted at `path` (no-op when absent)."
  [path]
  (when path
    (let [f (io/file path)]
      (when (.exists f)
        (doseq [x (reverse (file-seq f))]
          (.delete x))))))

(defn session-fn-with-id
  "A fake nREPL session fn carrying the `:nrepl.core/taking-until {:session id}`
   metadata used to derive the managed session-id."
  [session-id]
  (with-meta (fn [_] nil)
    {(keyword "nrepl.core" "taking-until") {:session session-id}}))

(defn fake-connector
  "The canonical happy `:nrepl-connector` seam value: a fn of `_endpoint`
   returning a deterministic `{:transport :client :client-session}` map whose
   session fn derives `session-id` (default `\"nrepl-session-1\"`). Single-sources
   the nullable transport/client/session-fn shape used by the attach/client/
   started happy-path tests. The returned map is constant per call, so callers
   may invoke the connector once to obtain the expected runtime-handle values."
  ([] (fake-connector "nrepl-session-1"))
  ([session-id]
   (let [handle {:transport {:transport :fake}
                 :client (fn ([] nil) ([_] nil))
                 :client-session (session-fn-with-id session-id)}]
     (fn [_endpoint] handle))))
