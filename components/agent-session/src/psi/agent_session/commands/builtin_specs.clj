(ns psi.agent-session.commands.builtin-specs
  "Single source of truth for the built-in slash-command surface.

   One ordered map (`builtin-command-specs`) is the *sole* place built-in
   command names exist; every other name surface — the routing maps
   (`exact-command-handlers`, `prefixed-command-prefixes`), the bare-name set
   (`builtin-command-names`), `format-help`'s built-in block, and the EQL
   resolver — is a pure projection of these keys, so name divergence between
   routing and the exposed/help surfaces is structurally unrepresentable
   (task 205, Option B: `unreachable > forbidden`).

   This is a leaf namespace (only `clojure.string`) so both `commands` and the
   `resolvers.extensions` resolver can depend on it without a load cycle.

   Keys are leading-slash-prefixed (matching the routing-projection key form,
   zero transform); only `builtin-command-names` and the resolver `strip-slash`.
   The table is authored in the current `format-help` display order so the help
   listing is reproduced unchanged in order and membership.

   Per-entry fields:
     :kinds         — required set ⊆ #{:exact :prefixed}, non-empty.
     :handler       — required iff :exact ∈ :kinds (dispatch keyword).
     :description   — required short string (resolver-exposed).
     :usage         — optional help-only arg-hint string (not resolver-exposed).
     :hide-in-help? — optional boolean, help-only suppression (not exposed,
                      not UI-consulted) for routed-but-help-absent entries."
  (:require
   [clojure.string :as str]
   [malli.core :as m]))

(def entry-schema
  "Malli schema for a single `builtin-command-specs` entry value — the primary
   per-entry invariant every projection silently assumes. Promotes the entry
   shape from a runtime-test (forbidden) to a load-time (unreachable) guard,
   matching the `unreachable > forbidden` ethos and the sibling load-time
   `case`-seam asserts in `commands.clj`.

   Captures: `:kinds` non-empty ⊆ #{:exact :prefixed}; `:exact ∈ :kinds ⇒
   :handler present; `:description` non-blank string; optional `:usage`
   string; optional `:hide-in-help?` boolean. The `:exact ⇒ :handler` cross-key
   constraint is expressed as an entry-level `:fn` predicate (a malli `:map`
   cannot make one key's requiredness depend on another's value)."
  [:and
   [:map
    [:kinds [:and
             [:set [:enum :exact :prefixed]]
             [:fn {:error/message "must be non-empty"} seq]]]
    [:handler {:optional true} :keyword]
    [:description [:and :string [:fn {:error/message "must be non-blank"}
                                 (complement str/blank?)]]]
    [:usage {:optional true} :string]
    [:hide-in-help? {:optional true} :boolean]]
   [:fn {:error/message ":exact entries must carry a :handler"}
    (fn [{:keys [kinds handler]}]
      (or (not (contains? kinds :exact)) (some? handler)))]])

(def builtin-command-specs
  (array-map
   "/quit"    {:kinds #{:exact} :handler :quit :description "exit the session"}
   "/status"  {:kinds #{:exact} :handler :status :description "show session diagnostics"}
   "/history" {:kinds #{:exact} :handler :history :description "show message history"}
   "/prompts" {:kinds #{:exact} :handler :prompts :description "list available prompt templates"}
   "/skills"  {:kinds #{:exact} :handler :skills :description "list available skills"}
   "/new"     {:kinds #{:exact} :handler :new :description "start a fresh session"}
   "/resume"  {:kinds #{:exact} :handler :resume :description "resume a previous session"}
   "/tree"    {:kinds #{:prefixed} :description "open/switch live session tree (TUI)"
               :usage "[session-id]"}
   "/login"   {:kinds #{:prefixed} :description "login with an OAuth provider"
               :usage "[provider]"}
   "/logout"  {:kinds #{:exact} :handler :logout :description "logout from an OAuth provider"}
   "/model"   {:kinds #{:prefixed} :description "show current model or set model"
               :usage "[provider model-id [session|project|user]]"}
   "/thinking" {:kinds #{:prefixed} :description "show current thinking level or set level"
                :usage "[level]"}
   "/speed"   {:kinds #{:prefixed} :description "show or set speed mode"
               :usage "[normal|fast [session|project|user]]"}
   "/effort"  {:kinds #{:prefixed} :description "show or set effort override"
               :usage "[low|medium|high|xhigh|none [session|project|user]]"}
   "/remember" {:kinds #{:prefixed} :description "capture a memory note for future ψ"
                :usage "[text]"}
   "/worktree" {:kinds #{:exact} :handler :worktree :description "show git worktree context"}
   "/reload-models" {:kinds #{:exact} :handler :reload-models
                     :description "reload custom model definitions from ~/.psi/agent/models.edn and .psi/models.edn"}
   "/reload-prompts" {:kinds #{:exact} :handler :reload-prompts
                      :description "re-discover prompt templates from ~/.psi/agent/prompts and <worktree>/.psi/prompts"}
   "/reload-extension-installs" {:kinds #{:exact} :handler :reload-extension-installs
                                 :description "reload/apply extension installs from extensions.edn"}
   "/operations" {:kinds #{:exact} :handler :operations
                  :description "list deterministic operations available in this session"}
   "/operation" {:kinds #{:prefixed}
                 :description "invoke a deterministic operation by id"
                 :usage "<id> {edn-args}"}
   "/jobs"    {:kinds #{:prefixed} :description "list background jobs (default: running,pending-cancel)"
               :usage "[status ...]"}
   "/job"     {:kinds #{:prefixed} :description "inspect a background job"
               :usage "<job-id>"}
   "/cancel-job" {:kinds #{:prefixed} :description "request background job cancellation"
                  :usage "<job-id>"}
   "/help"    {:kinds #{:exact} :handler :help :description "show this help"}
   ;; Routed but help-absent (autocomplete only): aliases + dual-kind command.
   "/?"       {:kinds #{:exact} :handler :help :description "show this help"
               :hide-in-help? true}
   "/exit"    {:kinds #{:exact} :handler :quit :description "exit the session"
               :hide-in-help? true}
   "/project-repl" {:kinds #{:exact :prefixed} :handler :project-repl
                    :description "open/manage the project nREPL"
                    :hide-in-help? true}))

(assert (every? #(m/validate entry-schema (val %)) builtin-command-specs)
        (str "builtin-command-specs has malformed entries: "
             (->> builtin-command-specs
                  (remove #(m/validate entry-schema (val %)))
                  (map (fn [[k spec]]
                         (str k " → " (m/explain entry-schema spec))))
                  (str/join "; "))))

(defn strip-slash
  "Strip a single leading slash from a command name."
  [s]
  (str/replace s #"^/" ""))

(def exact-command-handlers
  "Projection: `{\"/name\" → handler-keyword}` for entries with `:exact` kind."
  (into {}
        (for [[k spec] builtin-command-specs
              :when (contains? (:kinds spec) :exact)]
          [k (:handler spec)])))

(def prefixed-command-prefixes
  "Projection: vector of `/`-prefixed names for entries with `:prefixed` kind,
   in table order. Order is not load-bearing under the dispatch matcher (no
   prefix shadows another)."
  (vec (for [[k spec] builtin-command-specs
             :when (contains? (:kinds spec) :prefixed)]
         k)))

(def builtin-command-names
  "Projection: set of bare built-in command names (no leading slash)."
  (set (map strip-slash (keys builtin-command-specs))))

(defn render-help-line
  "Render a single built-in command help line:
   \"  /name [usage ]— description\" (usage inserted before the em-dash when
   present). The sole renderer of the help-line shape — `builtin-help-block`
   maps it over the filtered table, and tests assert against it so a
   spacing/em-dash/usage-placement regression is caught, not mirror-confirmed."
  [k {:keys [description usage]}]
  (str "  " k " " (when usage (str usage " ")) "— " description))

(defn builtin-help-block
  "Render the built-in command help lines from the single spec table, in table
   order, skipping `:hide-in-help?` entries. Each line is rendered by
   `render-help-line`. Returns a newline-joined string with no trailing
   newline."
  []
  (str/join "\n"
            (for [[k spec] builtin-command-specs
                  :when (not (:hide-in-help? spec))]
              (render-help-line k spec))))

(defn builtin-command-specs-for-resolver
  "Return built-in command specs as a vector of `{:name :description}` maps
   (bare names, no leading slash) in spec-table order, for the EQL resolver.

   Internal fields (`:kinds`/`:handler`/`:usage`/`:hide-in-help?`) are not
   exposed — the resolver surface mirrors `:psi.extension/command-names`."
  []
  (vec (for [[k {:keys [description]}] builtin-command-specs]
         {:name (strip-slash k) :description description})))
