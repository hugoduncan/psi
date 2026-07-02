(ns extensions.context-manager
  "Context manager extension scaffold.

   Subscribes to `session_turn_finished` events and registers a minimal
   pre-turn project-context augmenter when the runtime exposes that API."
  (:require
   [clojure.string :as str]))

(defn- on-turn-finished
  [log-fn payload]
  (try
    (let [session-id (get payload :session-id "nil")
          turn-id (get payload :turn-id "nil")]
      (log-fn (str "context-manager: session_turn_finished "
                   "session-id=" session-id
                   " turn-id=" turn-id)))
    (catch Exception e
      (try
        (log-fn (str "context-manager: handler error: " (.getMessage e)))
        (catch Exception _ nil))
      nil)))

(defonce helper-session-ids (atom #{}))

(defn- blank? [x]
  (or (nil? x)
      (and (string? x) (str/blank? x))))

(defn project-context-augmentation
  "Return the minimal v1 context-manager turn augmentation envelope."
  [turn-projection]
  (let [session-id (:turn-augmentation/session-id turn-projection)
        cwd        (:turn-augmentation/effective-cwd turn-projection)]
    (cond
      (contains? @helper-session-ids session-id)
      {:turn-augmentation/status :no-op
       :turn-augmentation/operations []
       :turn-augmentation/child-session-ids []}

      (blank? cwd)
      {:turn-augmentation/status :no-op
       :turn-augmentation/operations []
       :turn-augmentation/child-session-ids []
       :turn-augmentation/diagnostic "no effective cwd"}

      :else
      {:turn-augmentation/status :success
       :turn-augmentation/operations
       [{:op :append-context-block
         :id "project-context"
         :title "Project context"
         :content (str "Working directory: " cwd)}]
       :turn-augmentation/child-session-ids []})))

(defn- register-turn-augmenter!
  [api]
  (when-let [register (:register-turn-augmenter api)]
    (register {:augmenter-id "project-context"
               :description "Minimal working-directory project context"
               :version "1"
               :handler project-context-augmentation})))

(defonce initialized? (atom nil))

(defn init
  "Initialize the context-manager extension.

   Subscribes to `session_turn_finished` events via the extension API.
   Idempotent — repeated calls (e.g. on reload) are no-ops."
  [api]
  (if (and (map? api)
           (:on api)
           (compare-and-set! initialized? nil true))
    (do
      (register-turn-augmenter! api)
      ((:on api) "session_turn_finished"
                 (fn [payload]
                   (when (:log api)
                     (on-turn-finished (:log api) payload))
                   nil))
      true)
    (if (and (map? api) (:on api))
      nil ; already initialized
      (do
        (reset! initialized? nil) ; ensure we don't block future attempts if this one failed
        nil))))
