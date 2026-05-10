(ns psi.app-runtime.projections
  "Adapter-neutral public projections shared across interactive UIs."
  (:require
   [psi.session-state.state :as ss]
   [psi.ui.state :as ui-state]))

(def ^:private preserve-fn-keys
  #{:render-call-fn :render-result-fn :render-fn})

(def ^:private ui-state-path (ss/state-path :ui-state))

(defn- sortable-key
  [x]
  (cond
    (keyword? x) (name x)
    (string? x)  x
    (nil? x)     ""
    :else        (str x)))

(defn sort-widgets
  [widgets]
  (->> (or widgets [])
       (sort-by (juxt #(sortable-key (:placement %))
                      #(sortable-key (:extension-id %))
                      #(sortable-key (:widget-id %))))
       vec))

(defn sort-statuses
  [statuses]
  (->> (or statuses [])
       (sort-by #(sortable-key (:extension-id %)))
       vec))

(defn- public-renderers-by
  [items k]
  (into {}
        (map (fn [item]
               [(k item) (select-keys item preserve-fn-keys)]))
        (or items [])))

(defn extension-ui-snapshot-from-state
  "Build a canonical public extension-UI snapshot from raw UI state."
  [ui-state]
  (when-let [snapshot (ui-state/snapshot-state ui-state)]
    (cond-> snapshot
      true (update :widgets sort-widgets)
      true (update :statuses sort-statuses)
      true (assoc :tools-expanded? (boolean (:tools-expanded? ui-state)))
      true (assoc :tool-renderers (public-renderers-by (ui-state/all-tool-renderers (atom ui-state)) :tool-name))
      true (assoc :message-renderers (public-renderers-by (ui-state/all-message-renderers (atom ui-state)) :custom-type)))))

(defn extension-ui-snapshot
  "Build a canonical public extension-UI snapshot from runtime ctx."
  [ctx]
  (some-> (ss/get-state-value-in ctx ui-state-path)
          extension-ui-snapshot-from-state))
