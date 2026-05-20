(ns psi.command-registry.registry
  "Command-specific extension-registry ownership: validation, registration, and queries.

   Built-in commands are stored under the `:built-in-commands` key in registry
   state, keyed by provenance id then command name.  Extension commands continue
   to live under `:extensions`.  All public read paths merge both stores."
  (:require
   [clojure.string :as str]))

(defn valid-command-name?
  "Return true when `command-name` is a non-blank string."
  [command-name]
  (and (string? command-name)
       (not (str/blank? command-name))))

(defn- state-in
  [reg]
  @(:state reg))

(defn- ensure-registered-extension-path!
  [reg ext-path]
  (when-not (contains? (:extensions (state-in reg)) ext-path)
    (throw (ex-info (str "Cannot register command for unregistered extension path: " (pr-str ext-path))
                    {:ext-path ext-path
                     :reason :unregistered-extension-path}))))

(defn- ensure-valid-command!
  [ext-path cmd]
  (let [command-name (:name cmd)]
    (when-not (valid-command-name? command-name)
      (throw (ex-info (str "Invalid command name: " (pr-str command-name)
                           ". Expected a non-blank string.")
                      {:ext-path ext-path
                       :command cmd
                       :command-name command-name
                       :reason :invalid-command-name}))))
  cmd)

(defn register-command-in!
  "Register `cmd` for the extension at `ext-path`.

   Canonical first-cut policy:
   - `ext-path` must already be registered
   - `:name` must be a non-blank string
   - command identity is exact `:name` string equality
   - no slash-prefix or case normalization is applied
   - same-extension duplicate registration replaces the prior stored command"
  [reg ext-path cmd]
  (ensure-registered-extension-path! reg ext-path)
  (let [cmd* (ensure-valid-command! ext-path cmd)]
    (swap! (:state reg)
           assoc-in [:extensions ext-path :commands (:name cmd*)] cmd*)
    reg))

(defn- extension-item-maps
  [state item-key]
  (map #(or (get-in state [:extensions % item-key]) {})
       (:registration-order state)))

(defn command-names-in
  "Return set of all registered command names across built-ins and extensions."
  [reg]
  (let [state (state-in reg)
        built-in-names (into #{}
                             (for [[_ cmds] (:built-in-commands state)
                                   [name _] cmds]
                               name))
        ext-names (into #{}
                        (mapcat keys)
                        (extension-item-maps state :commands))]
    (into built-in-names ext-names)))

(defn all-commands-in
  "Return vector of all registered command maps across built-ins and extensions.
   Built-in commands are listed first; first registration per name wins."
  [reg]
  (let [state (state-in reg)
        seen  (volatile! #{})
        built-in-items (reduce
                        (fn [items [_ cmds]]
                          (reduce-kv
                           (fn [items name cmd]
                             (if (contains? @seen name)
                               items
                               (do (vswap! seen conj name)
                                   (conj items cmd))))
                           items
                           cmds))
                        []
                        (:built-in-commands state))]
    (reduce
     (fn [items path]
       (reduce-kv
        (fn [items name item]
          (if (contains? @seen name)
            items
            (do
              (vswap! seen conj name)
              (conj items (assoc item :extension-path path)))))
        items
        (or (get-in state [:extensions path :commands]) {})))
     built-in-items
     (:registration-order state))))

(defn get-command-in
  "Return the command map for `command-name`, or nil.
   Checks built-in commands first, then extension commands in registration order."
  [reg command-name]
  (let [state (state-in reg)]
    (or (some #(get-in state [:built-in-commands % command-name])
              (keys (:built-in-commands state)))
        (some #(get-in state [:extensions % :commands command-name])
              (:registration-order state)))))

;;; Built-in registration

(defn register-built-in-command-in!
  "Register `cmd` as a built-in command owned by `provenance-id`.
   Built-in commands do not require a prior extension registration.
   `provenance-id` is a stable identifier (e.g. `\"built-in:workflow\"`)."
  [reg provenance-id cmd]
  (let [command-name (:name cmd)]
    (when-not (valid-command-name? command-name)
      (throw (ex-info (str "Invalid built-in command name: " (pr-str command-name)
                           ". Expected a non-blank string.")
                      {:provenance-id provenance-id
                       :command       cmd
                       :command-name  command-name
                       :reason        :invalid-command-name})))
    (swap! (:state reg)
           assoc-in [:built-in-commands provenance-id command-name]
           (assoc cmd :source :built-in :ext-path provenance-id))
    reg))

(defn all-built-in-commands-in
  "Return vector of all registered built-in command maps, across all provenance ids."
  [reg]
  (let [state (state-in reg)]
    (vec (for [[_ cmds] (:built-in-commands state)
               [_ cmd]  cmds]
           cmd))))

(defn built-in-command-names-in
  "Return set of all registered built-in command names."
  [reg]
  (let [state (state-in reg)]
    (into #{}
          (for [[_ cmds] (:built-in-commands state)
                [name _] cmds]
            name))))
