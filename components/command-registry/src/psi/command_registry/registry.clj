(ns psi.command-registry.registry
  "Command-specific registry adapter over the shared root-registry substrate.

   Command-registry owns command-name validation and the public merged built-in
   plus extension command surface. Shared storage, ownership, and lower-level
   lookup mechanics live in `psi.root-registry.registry`."
  (:require
   [clojure.string :as str]
   [psi.root-registry.registry :as root-registry]))

(def ^:private registry-id
  :commands)

(def ^:private built-in-extension-id
  :built-in)

(defn valid-command-name?
  "Return true when `command-name` is a non-blank string."
  [command-name]
  (and (string? command-name)
       (not (str/blank? command-name))))

(defn- state-in
  [reg]
  @(:state reg))

(defn- ensure-root-registry-declared
  [state]
  (if (root-registry/declared-registry? state registry-id)
    state
    (root-registry/declare-registry state registry-id)))

(defn- ensure-valid-command!
  [owner-id cmd]
  (let [command-name (:name cmd)]
    (when-not (valid-command-name? command-name)
      (throw (ex-info (str "Invalid command name: " (pr-str command-name)
                           ". Expected a non-blank string.")
                      {:owner-id owner-id
                       :command cmd
                       :command-name command-name
                       :reason :invalid-command-name}))))
  cmd)

(defn- ensure-valid-built-in-command!
  [provenance-id cmd]
  (let [command-name (:name cmd)]
    (when-not (valid-command-name? command-name)
      (throw (ex-info (str "Invalid built-in command name: " (pr-str command-name)
                           ". Expected a non-blank string.")
                      {:provenance-id provenance-id
                       :command cmd
                       :command-name command-name
                       :reason :invalid-command-name}))))
  cmd)

(defn- registered-extension-entry
  [reg ext-path]
  (-> (root-registry/lookup (state-in reg) registry-id ext-path)
      :result
      :value))

(defn- ensure-registered-extension-path!
  [reg ext-path]
  (when-not (contains? (:extensions (state-in reg)) ext-path)
    (throw (ex-info (str "Cannot register command for unregistered extension path: " (pr-str ext-path))
                    {:ext-path ext-path
                     :reason :unregistered-extension-path}))))

(defn- extension-command-map
  [reg ext-path]
  (or (get-in (registered-extension-entry reg ext-path) [:value :commands]) {}))

(defn- register-root-entry!
  [reg entry]
  (swap! (:state reg)
         (fn [state]
           (:root-state (root-registry/register (ensure-root-registry-declared state)
                                                registry-id
                                                entry))))
  reg)

(defn- extension-command-entry
  [ext-path commands]
  {:id ext-path
   :extension-id ext-path
   :value {:commands commands}})

(defn- built-in-command-entry
  [provenance-id commands]
  {:id provenance-id
   :extension-id built-in-extension-id
   :provenance-id provenance-id
   :value {:commands commands}})

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
  (let [cmd* (ensure-valid-command! ext-path cmd)
        commands (assoc (extension-command-map reg ext-path)
                        (:name cmd*)
                        cmd*)]
    (register-root-entry! reg (extension-command-entry ext-path commands))))

(defn- all-root-entries
  [reg]
  (let [result (:result (root-registry/list-entries (state-in reg)
                                                    registry-id))]
    (:entries result)))

(defn- built-in-command-entries
  [reg]
  (->> (all-root-entries reg)
       (filter #(= built-in-extension-id (:extension-id %)))))

(defn- extension-registration-order
  [reg]
  (:registration-order (state-in reg)))

(defn- extension-command-entries
  [reg]
  (keep (fn [extension-path]
          (when-let [entry (registered-extension-entry reg extension-path)]
            {:extension-path extension-path
             :commands (get-in entry [:value :commands])}))
        (extension-registration-order reg)))

(defn command-names-in
  "Return set of all registered command names across built-ins and extensions."
  [reg]
  (let [built-in-names (into #{}
                             (mapcat (comp keys :commands :value))
                             (built-in-command-entries reg))
        ext-names (into #{}
                        (mapcat (comp keys :commands))
                        (extension-command-entries reg))]
    (into built-in-names ext-names)))

(defn- built-in-command-map
  [entry]
  (assoc (:value entry)
         :source :built-in
         :ext-path (:provenance-id entry)))

(defn all-commands-in
  "Return vector of all registered command maps across built-ins and extensions.
   Built-in commands are listed first; first registration per name wins."
  [reg]
  (let [seen (volatile! #{})
        built-in-items (reduce
                        (fn [items entry]
                          (reduce-kv
                           (fn [items name cmd]
                             (if (contains? @seen name)
                               items
                               (do
                                 (vswap! seen conj name)
                                 (conj items (built-in-command-map (assoc entry :value cmd))))))
                           items
                           (:commands (:value entry))))
                        []
                        (built-in-command-entries reg))]
    (reduce
     (fn [items {:keys [extension-path commands]}]
       (reduce-kv
        (fn [items name cmd]
          (if (contains? @seen name)
            items
            (do
              (vswap! seen conj name)
              (conj items (assoc cmd :extension-path extension-path)))))
        items
        commands))
     built-in-items
     (extension-command-entries reg))))

(defn get-command-in
  "Return the command map for `command-name`, or nil.
   Checks built-in commands first, then extension commands in registration order."
  [reg command-name]
  (or (some (fn [entry]
              (some-> (get-in entry [:value :commands command-name])
                      (assoc :source :built-in
                             :ext-path (:provenance-id entry))))
            (built-in-command-entries reg))
      (some (fn [{:keys [extension-path commands]}]
              (some-> (get commands command-name)
                      (assoc :extension-path extension-path)))
            (extension-command-entries reg))))

(defn register-built-in-command-in!
  "Register `cmd` as a built-in command owned by `provenance-id`.
   Built-in commands do not require a prior extension registration.
   `provenance-id` is a stable identifier (e.g. `\"built-in:workflow\"`)."
  [reg provenance-id cmd]
  (let [cmd* (ensure-valid-built-in-command! provenance-id cmd)
        existing-commands (or (get-in (registered-extension-entry reg provenance-id) [:value :commands]) {})
        commands (assoc existing-commands
                        (:name cmd*)
                        (assoc cmd* :source :built-in :ext-path provenance-id))]
    (register-root-entry! reg (built-in-command-entry provenance-id commands))))

(defn all-built-in-commands-in
  "Return vector of all registered built-in command maps, across all provenance ids."
  [reg]
  (vec (mapcat (fn [entry]
                 (map (fn [[_ cmd]]
                        (assoc cmd :source :built-in :ext-path (:provenance-id entry)))
                      (:commands (:value entry))))
               (built-in-command-entries reg))))

(defn built-in-command-names-in
  "Return set of all registered built-in command names."
  [reg]
  (into #{}
        (mapcat (comp keys :commands :value))
        (built-in-command-entries reg)))
