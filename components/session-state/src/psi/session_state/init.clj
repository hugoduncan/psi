(ns psi.session-state.init
  "Pure session initialization/state transforms and lower-level runtime
   projection updates. Child-session prompt derivation remains above this
   boundary in agent-session for now."
  (:require
   [clojure.java.io :as io]
   [psi.session-persistence.core :as persistence]
   [psi.session-state.model :as model]
   [psi.session-state.state :as state]))

(def initial-telemetry
  {:tool-output-stats {:calls      []
                       :aggregates {:total-context-bytes  0
                                    :by-tool              {}
                                    :limit-hits-by-tool   {}}}
   :tool-call-attempts    []
   :tool-lifecycle-events []
   :provider-requests     []
   :provider-replies      []})

(defn bounded-append
  [limit coll item]
  (let [v (conj (vec (or coll [])) item)
        n (count v)]
    (if (> n limit) (subvec v (- n limit)) v)))

(defn initialize-session-slots
  [state* sid journal-entries]
  (let [existing-persistence (get-in state* [:agent-session :sessions sid :persistence])
        session-file         (get-in existing-persistence [:flush-state :session-file])
        flushed?             (boolean (get-in existing-persistence [:flush-state :flushed?]))]
    (-> state*
        (persistence/initialize-persistence-state sid {:journal journal-entries
                                                       :session-file session-file
                                                       :flushed? flushed?})
        (assoc-in [:agent-session :sessions sid :telemetry] initial-telemetry)
        (assoc-in [:agent-session :sessions sid :turn] {:ctx nil}))))

(defn update-runtime-rpc-trace-state [state* enabled? file]
  (assoc-in state* [:runtime :rpc-trace] {:enabled? enabled? :file file}))

(defn update-nrepl-runtime-state [state* runtime]
  (assoc-in state* [:runtime :nrepl] runtime))

(defn update-oauth-projection-state [state* oauth]
  (assoc-in state* [:oauth] oauth))

(defn update-recursion-projection-state [state* recursion-state]
  (assoc-in state* [:recursion] recursion-state))

(defn update-background-jobs-store-state [state* update-fn]
  (if (fn? update-fn)
    (update-in state* [:background-jobs :store] update-fn)
    state*))

(defn initialize-resume-missing-state
  [state* current-sd session-path]
  (let [next-sd (assoc current-sd :session-file session-path)
        sid     (:session-id next-sd)]
    (-> state*
        (assoc-in (state/session-data-path sid) next-sd)
        (persistence/initialize-persistence-state sid {:session-file (io/file session-path)})
        (initialize-session-slots sid []))))

(defn carry-runtime-handles
  [state* source-session-id new-session-id]
  (let [agent-ctx (get-in state* [:agent-session :sessions source-session-id :agent-ctx])
        sc-sid    (get-in state* [:agent-session :sessions source-session-id :sc-session-id])]
    (-> state*
        (assoc-in [:agent-session :sessions new-session-id :agent-ctx] agent-ctx)
        (assoc-in [:agent-session :sessions new-session-id :sc-session-id] sc-sid))))

(defn initialize-new-session-state
  [state* current-sd {:keys [new-session-id worktree-path session-name spawn-mode session-file scheduled-origin-session-id scheduled-from-schedule-id scheduled-from-label]}]
  (let [baseline (merge (model/initial-session)
                        (select-keys current-sd [:model
                                                 :thinking-level
                                                 :base-system-prompt
                                                 :system-prompt
                                                 :prompt-mode
                                                 :nucleus-prelude-override
                                                 :cache-breakpoints
                                                 :system-prompt-build-opts
                                                 :prompt-component-selection
                                                 :developer-prompt
                                                 :developer-prompt-source
                                                 :auto-retry-enabled
                                                 :auto-compaction-enabled
                                                 :scoped-models
                                                 :skills
                                                 :prompt-templates
                                                 :prompt-contributions
                                                 :tool-defs
                                                 :extensions
                                                 :context-tokens
                                                 :context-window
                                                 :ui-type
                                                 :tool-output-overrides]))
        next-sd  (assoc baseline
                        :session-id new-session-id
                        :session-file session-file
                        :session-name session-name
                        :worktree-path worktree-path
                        :scheduled-origin-session-id scheduled-origin-session-id
                        :scheduled-from-schedule-id scheduled-from-schedule-id
                        :scheduled-from-label scheduled-from-label
                        :parent-session-id nil
                        :parent-session-path nil
                        :spawn-mode (or spawn-mode :new-root)
                        :model (:model baseline)
                        :thinking-level (or (:thinking-level baseline) :off)
                        :interrupt-pending false
                        :interrupt-requested-at nil
                        :steering-messages []
                        :follow-up-messages []
                        :retry-attempt 0
                        :created-at (java.time.Instant/now))]
    (cond-> (-> state*
                (assoc-in (state/session-data-path new-session-id) next-sd)
                (initialize-session-slots new-session-id []))
      session-file
      (persistence/initialize-persistence-state new-session-id {:session-file (io/file session-file)}))))

(defn initialize-resumed-session-state
  [state* current-sd {:keys [session-id _source-session-id session-path header entries model thinking-level]}]
  (let [session-name (some #(when (= :session-info (:kind %))
                              (get-in % [:data :name]))
                           (rseq (vec entries)))
        header-ts     (:timestamp header)
        updated-at    (or (some-> entries last :timestamp)
                          header-ts)
        baseline     (merge (model/initial-session)
                            (select-keys current-sd [:base-system-prompt
                                                     :system-prompt
                                                     :prompt-mode
                                                     :nucleus-prelude-override
                                                     :cache-breakpoints
                                                     :system-prompt-build-opts
                                                     :prompt-component-selection
                                                     :developer-prompt
                                                     :developer-prompt-source
                                                     :auto-retry-enabled
                                                     :auto-compaction-enabled
                                                     :scoped-models
                                                     :skills
                                                     :prompt-templates
                                                     :prompt-contributions
                                                     :tool-defs
                                                     :extensions
                                                     :context-tokens
                                                     :context-window
                                                     :ui-type
                                                     :tool-output-overrides]))
        next-sd      (assoc baseline
                            :session-id session-id
                            :session-file session-path
                            :session-name session-name
                            :worktree-path (:worktree-path header)
                            :parent-session-id (:parent-session-id header)
                            :parent-session-path (:parent-session header)
                            :interrupt-pending false
                            :interrupt-requested-at nil
                            :model model
                            :thinking-level thinking-level
                            :created-at header-ts
                            :updated-at updated-at)]
    (-> state*
        (assoc-in (state/session-data-path session-id) next-sd)
        (initialize-session-slots session-id (vec entries))
        (persistence/initialize-persistence-state session-id
                                                  {:journal (vec entries)
                                                   :session-file (io/file session-path)
                                                   :flushed? true}))))

(defn initialize-forked-session-state
  [state* parent-sd {:keys [new-session-id branch-entries session-file]}]
  (let [parent-session-id   (:session-id parent-sd)
        parent-session-file (:session-file parent-sd)
        baseline            (merge (model/initial-session)
                                   (select-keys parent-sd [:model
                                                           :thinking-level
                                                           :prompt-mode
                                                           :nucleus-prelude-override
                                                           :cache-breakpoints
                                                           :developer-prompt
                                                           :developer-prompt-source
                                                           :auto-retry-enabled
                                                           :auto-compaction-enabled
                                                           :scoped-models
                                                           :skills
                                                           :prompt-templates
                                                           :prompt-contributions
                                                           :tool-defs
                                                           :extensions
                                                           :context-tokens
                                                           :context-window
                                                           :ui-type
                                                           :tool-output-overrides]))
        next-sd             (assoc baseline
                                   :session-id new-session-id
                                   :worktree-path (:worktree-path parent-sd)
                                   :parent-session-id parent-session-id
                                   :parent-session-path parent-session-file
                                   :session-file session-file
                                   :spawn-mode :fork-head)]
    (cond-> (-> state*
                (carry-runtime-handles parent-session-id new-session-id)
                (assoc-in (state/session-data-path new-session-id) next-sd)
                (initialize-session-slots new-session-id branch-entries))
      session-file
      (persistence/initialize-persistence-state new-session-id
                                                {:journal branch-entries
                                                 :session-file (io/file session-file)
                                                 :flushed? true}))))
