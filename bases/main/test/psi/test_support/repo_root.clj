(ns psi.test-support.repo-root
  "Shared repo-root walk-up helper for tests that need to resolve committed
   repo files (doc/custom-providers.md, .psi/models.edn, .psi/project.edn,
   .psi/workflows) from the repo root regardless of the process cwd.

   This shared home replaces component-local copies so future fixes land in
   one place. bases/main/test is on the unit test classpath, so tests in both
   components can require it.

   The walk is configurable (see repo-root's opts): the marker set that
   identifies the repo root (:markers), an optional system-property override
   for runners whose cwd is not under the repo (:prop — injectability per the
   skill infra-dep criterion), and an optional fail-loud mode (:required?)
   for callers that must not silently degrade to the filesystem root."
  (:require [clojure.java.io :as io]))

(defn repo-root
  "Repo root: walk up from the process cwd until the configured marker(s)
   exist. Returns a java.io.File. Tests run from the repo root via bb, so
   this equals user.dir there; from a component-local cwd it resolves the
   repo root so committed-project-file lookups target the actual repo
   instead of silently missing (a missing file would otherwise read as
   empty/nil, degrading a durable lock to a no-op without failing loud).

   Options (all optional; the no-arg call preserves the historical behavior):
   :markers   — coll of repo-relative marker paths identifying the root
                (each a path string or a coll of path segments); the root is
                the first ancestor dir containing ALL of them. Default
                [[\"doc\" \"custom-providers.md\"]].
   :prop      — system property name that, when set (non-empty), overrides
                the walk entirely: its value is used as the root without any
                marker check (e.g. an editor/nrepl runner whose cwd is not
                under the repo). Default nil (no override).
   :required? — when true and no :prop override is set, throw a clear ex-info
                if the walk exhausts without finding all markers (instead of
                silently returning the filesystem root). Default false."
  ([] (repo-root {}))
  ([{:keys [markers prop required?]
     :or   {markers [["doc" "custom-providers.md"]]}}]
   (if-let [override (and prop (not-empty (System/getProperty prop)))]
     (io/file override)
     (let [marker-exists? (fn [dir marker]
                            (.exists (apply io/file dir
                                            (if (sequential? marker)
                                              marker
                                              [marker]))))
           all-markers?   (fn [dir]
                            (every? (partial marker-exists? dir) markers))
           found          (loop [dir (.getCanonicalFile (io/file "."))]
                            (if (all-markers? dir)
                              dir
                              (if-let [parent (.getParentFile dir)]
                                (recur parent)
                                dir)))]
       (when (and required? (not (all-markers? found)))
         (throw (ex-info (str "Could not locate the repo root: no directory "
                              "containing all markers " (pr-str markers)
                              " found walking up from cwd"
                              (when prop
                                (str "; set the " prop
                                     " system property to override"))
                              ".")
                         {:markers markers :prop prop})))
       found))))
