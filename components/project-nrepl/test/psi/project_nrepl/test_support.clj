(ns psi.project-nrepl.test-support
  "Shared component-local test helpers for the project-nrepl component tests.

   Consolidates the per-file copies of `make-ctx`, `install-instance!`,
   `temp-dir`, `delete-tree!`, and `session-fn-with-id` so the seeded
   `:runtime-handle` shape and temp-dir lifecycle stay identical across the
   eval/commands/ops/attach/started/client tests."
  (:require
   [clojure.java.io :as io]
   [psi.project-nrepl.runtime :as project-nrepl-runtime]
   [psi.agent-session.test-support :as test-support]))

(defn make-ctx
  "A non-persisting test ctx from a fresh test session (no session-id needed)."
  []
  (let [[ctx _] (test-support/create-test-session {:persist? false})]
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
