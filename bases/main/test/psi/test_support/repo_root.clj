(ns psi.test-support.repo-root
  "Shared repo-root walk-up helper for tests that need to resolve committed
   repo files (doc/custom-providers.md, .psi/models.edn, .psi/project.edn,
   .psi/workflows) from the repo root regardless of the process cwd.

   Single home for this helper (review 41): user_models_test.clj and
   workflow_test_support.clj historically carried byte-similar private
   copies in two components; this namespace is the one place future fixes
   land. bases/main/test is on the unit test classpath, so both
   components' test files can require it."
  (:require [clojure.java.io :as io]))

(defn repo-root
  "Repo root: walk up from the process cwd until doc/custom-providers.md
   exists. Returns a java.io.File. Tests run from the repo root via bb, so
   this equals user.dir there; from a component-local cwd it resolves the
   repo root so committed-project-file lookups target the actual repo
   instead of silently missing (a missing file would otherwise read as
   empty/nil, degrading a durable lock to a no-op without failing loud)."
  []
  (loop [dir (.getCanonicalFile (io/file "."))]
    (if (or (.exists (io/file dir "doc" "custom-providers.md"))
            (= dir (.getParentFile dir)))
      dir
      (recur (.getParentFile dir)))))
