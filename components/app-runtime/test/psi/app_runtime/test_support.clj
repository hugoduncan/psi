(ns psi.app-runtime.test-support
  "Test helper wrapping create-runtime-session-context + bootstrap-runtime-session!
   for the common 'create everything from ai-model + opts' pattern."
  (:require
   [psi.app-runtime :as app-runtime]
   [psi.introspection.core :as introspection]
   [psi.memory.runtime :as memory-runtime]
   [psi.prompt-assets.prompt-templates :as pt]
   [psi.prompt-assets.skills :as skills]
   [psi.prompt-assets.system-prompt :as sys-prompt]
   [psi.provider-auth.oauth.core :as oauth]))

(defn bootstrap-stub-bindings
  "Returns a map of var→fn bindings that null out infrastructure side effects
   (oauth, templates, skills, system-prompt, introspection, memory-runtime).
   Use with `with-redefs-fn` in bootstrap tests."
  []
  {#'oauth/create-context              (fn [] nil)
   #'pt/discover-templates             (fn [] [])
   #'skills/discover-skills            (fn [] {:skills [] :diagnostics []})
   #'sys-prompt/discover-context-files (fn [_] [])
   #'sys-prompt/build-system-prompt    (fn [_] "")
   #'introspection/register-resolvers! (fn [] nil)
   #'memory-runtime/sync-memory-layer! (fn [_] {:ok? true})})

(def test-ai-model
  "Shared ai-model map for bootstrap tests.  Use this as the default;
   override individual keys only when the test behaviour depends on them
   (e.g. project-preferences tests that intentionally supply a different model)."
  {:provider           :anthropic
   :id                 "test-model"
   :name               "Test Model"
   :supports-reasoning false
   :context-window     200000})

(defn with-session-state-restore
  "Saves `app-runtime/session-state`, runs `(f)`, and restores the original
   value in a finally block.  Use as a HOF wrapper around test bodies that
   mutate session-state."
  [f]
  (let [orig @app-runtime/session-state]
    (try
      (f)
      (finally
        (reset! app-runtime/session-state orig)))))

(def ^:private ctx-keys
  "Keys extracted from opts and forwarded to create-runtime-session-context."
  [:cwd :session-config :ui-type :persist? :session-root :thinking-level-override])

(defn bootstrap-fresh-session!
  "Create a fresh runtime session context and bootstrap it in one call.

   Extracts :cwd, :session-config, :ui-type, :persist?, :session-root, and
   :thinking-level-override from opts for create-runtime-session-context, then
   forwards the full opts to bootstrap-runtime-session!.

   Returns the bootstrap result map merged with {:ctx ctx :oauth-ctx oauth-ctx :cwd cwd}."
  [ai-model opts]
  (let [ctx-opts (-> (select-keys opts ctx-keys)
                     (update :ui-type #(or % :console)))
        {:keys [ctx oauth-ctx cwd]} (app-runtime/create-runtime-session-context ai-model ctx-opts)
        result (app-runtime/bootstrap-runtime-session! ctx ai-model (assoc opts :cwd cwd))]
    (assoc result :ctx ctx :oauth-ctx oauth-ctx :cwd cwd)))
