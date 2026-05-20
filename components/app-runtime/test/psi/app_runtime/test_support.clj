(ns psi.app-runtime.test-support
  "Test helper wrapping create-runtime-session-context + bootstrap-runtime-session!
   for the common 'create everything from ai-model + opts' pattern."
  (:require
   [psi.app-runtime :as app-runtime]))

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
