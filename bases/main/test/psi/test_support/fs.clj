(ns psi.test-support.fs
  "Shared filesystem helpers for tests (bases/main/test is on the :unit,
  :extensions, and :integration suite classpaths via the :test-paths alias —
  the same reachability psi.test-support.repo-root relies on).

  delete-recursively! consolidates the seven repo-wide local copies (tui,
  history ×2, agent-session ×2, work-on, shared-config) into a single
  definition site — the reusable-existing-pattern consolidation slice-22
  established for repo-root. The implementation is the behavioral superset of
  the copies: nil-safe (the tui copy's guard), String/File input via
  io/file conversion (the history/agent-session/work-on copies'
  `(File. (str path))` and the tui copy's `(io/file f)`), an .exists guard
  (every copy except tool-output's, where the path always exists — the guard
  is a harmless no-op there), and a recursive delete-children-first walk
  returning nil (the shared-config copy's explicit contract)."
  (:require [clojure.java.io :as io]))

(defn delete-recursively!
  "Recursively delete `path` (file or directory tree). No-op when `path` is
  nil or does not exist. Returns nil."
  [path]
  (when path
    (let [f (io/file path)]
      (when (.exists f)
        (when (.isDirectory f)
          (doseq [child (.listFiles f)]
            (delete-recursively! child)))
        (.delete f))))
  nil)
