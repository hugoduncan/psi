(ns psi.command-registry.registry
  "Command-specific extension-registry ownership: validation, registration, and queries."
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
  "Return set of all registered command names across all extensions in `reg`."
  [reg]
  (let [state (state-in reg)]
    (into #{}
          (mapcat keys)
          (extension-item-maps state :commands))))

(defn all-commands-in
  "Return vector of all registered command maps across all extensions.
   First registration per name wins across extensions and the returned vector
   preserves first-encounter order while scanning extension registration order."
  [reg]
  (let [state (state-in reg)
        seen  (volatile! #{})]
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
     []
     (:registration-order state))))

(defn get-command-in
  "Return the command map for `command-name`, or nil.
   Across extensions, first registration by extension registration order wins."
  [reg command-name]
  (let [state (state-in reg)]
    (some #(get-in state [:extensions % :commands command-name])
          (:registration-order state))))
