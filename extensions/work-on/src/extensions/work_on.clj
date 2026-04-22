(ns extensions.work-on
  (:require
   [clojure.string :as str]
   [psi.history.git :as git]))

(defonce ^:private state
  (atom {:query-fn nil
         :mutate-fn nil
         :create-session-fn nil
         :switch-session-fn nil
         :set-worktree-path-fn nil
         :notify-fn nil
         :append-message-fn nil
         :path nil
         :default-branch nil
         :default-branch-source nil}))

(def ^:private stopwords
  #{"a" "an" "the" "and" "or" "but" "in" "on" "at"
    "to" "for" "of" "with" "by" "from" "is" "are"
    "was" "were" "be" "been" "has" "have" "had"
    "do" "does" "did" "will" "would" "could" "should"
    "it" "its" "this" "that" "these" "those" "i" "we"})

(def ^:private session-query-attrs
  [:psi.agent-session/session-id
   :psi.agent-session/session-name
   :psi.agent-session/session-file
   :psi.agent-session/worktree-path
   :psi.agent-session/system-prompt
   :psi.agent-session/host-sessions
   :git.worktree/current
   :git.worktree/list])

(defn- query!
  [q]
  (when-let [qf (:query-fn @state)]
    (try
      (qf q)
      (catch Exception _ nil))))

(defn- mutate!
  [op params]
  (when-let [mf (:mutate-fn @state)]
    (try
      (mf op params)
      (catch Exception _ nil))))

(defn- squish
  [s]
  (some-> s
          str
          (str/replace #"\s+" " ")
          str/trim
          not-empty))

(defn mechanical-slug
  [description]
  (let [words       (->> (str/lower-case (or description ""))
                         (re-seq #"[a-z0-9]+"))
        significant (->> words
                         (remove #(or (contains? stopwords %)
                                      (<= (count %) 1)))
                         (take 4))
        fallback    (when (empty? significant) ["work"])
        terms       (vec (or (seq significant) fallback))]
    {:raw-description description
     :terms terms
     :slug (str/join "-" terms)
     :branch-name (str/join "-" terms)}))

(defn- current-session-query
  []
  (query! session-query-attrs))

(defn- current-main-worktree
  []
  (let [d        (current-session-query)
        worktrees (or (:git.worktree/list d) [])
        current  (:git.worktree/current d)]
    (or (some #(when (= (get % :git.worktree/path)
                        (get current :git.worktree/path))
                 %)
              (filter #(not (:git.worktree/current? % false)) worktrees))
        (some #(when (and (not= (get % :git.worktree/path)
                                (get current :git.worktree/path))
                          (or (= (get % :git.worktree/branch-name) "main")
                              (= (get % :git.worktree/branch-name) "master")))
                 %)
              worktrees)
        (first worktrees))))

(defn- find-session-for-worktree
  [worktree-path]
  (let [sessions (or (:psi.agent-session/host-sessions (current-session-query)) [])]
    (some (fn [s]
            (when (= worktree-path (:psi.session-info/worktree-path s))
              s))
          sessions)))

(defn- find-worktree-by-path
  [worktree-path]
  (let [worktrees (or (:git.worktree/list (current-session-query)) [])]
    (some (fn [wt]
            (when (= worktree-path (:git.worktree/path wt))
              wt))
          worktrees)))

(defn- switch-to-session!
  [session-id]
  (when-let [f (:switch-session-fn @state)]
    (try
      (f session-id)
      (catch Exception _ nil))))

(defn- canonical-path
  [path]
  (when (seq (str path))
    (try
      (.getCanonicalPath (java.io.File. (str path)))
      (catch Exception _
        (.getAbsolutePath (java.io.File. (str path)))))))

(defn- target-worktree-path
  [main-path current-path slug]
  (let [main-file    (java.io.File. (str main-path))
        current-file (java.io.File. (str current-path))
        main*        (canonical-path main-path)
        current-parent* (some-> current-file .getParentFile .getPath canonical-path)
        container    (if (= current-parent* main*)
                       main-file
                       (.getParentFile main-file))]
    (str container java.io.File/separator slug)))

(defn- trim-arg
  [s]
  (some-> s str/trim not-empty))

(defn- print-line
  [text]
  (println (str text)))

(defn- append-assistant-message!
  [text]
  (if-let [f (:append-message-fn @state)]
    (try
      (f "assistant" (str text))
      (catch Exception _ nil))
    (if-let [f (:notify-fn @state)]
      (try
        (f (str text) {:role "assistant"})
        (catch Exception _ nil))
      (print-line text))))

(defn- set-current-session-worktree-path!
  [session-id worktree-path]
  (when-let [f (:set-worktree-path-fn @state)]
    (try
      (f session-id worktree-path)
      (catch Exception _ nil))))

(defn- create-worktree-session!
  [description worktree-path _parent-session-id]
  (when-let [f (:create-session-fn @state)]
    (try
      (f {:session-name description
          :worktree-path worktree-path})
      (catch Exception _ nil))))

(defn- created-session-id
  [session-data]
  (or (:session-id session-data)
      (:psi.agent-session/session-id session-data)
      (:psi.session-info/id session-data)))

(defn- resolve-default-branch
  [main-wt current-wt]
  (let [main-path (:git.worktree/path main-wt)
        fallback  (or (:git.worktree/branch-name main-wt)
                      (:git.worktree/branch-name current-wt)
                      "main")
        queried   (:git.branch/default-branch
                   (query! [:git.branch/default-branch]))
        result    (or queried
                      (when (seq main-path)
                        (mutate! 'git.branch/default
                                 {:git/context {:cwd main-path}})))]
    {:branch (or (:branch result) fallback)
     :source (or (:source result) :fallback)}))

(defn- cache-default-branch!
  [main-wt current-wt]
  (let [{:keys [branch source]} (resolve-default-branch main-wt current-wt)]
    (swap! state assoc
           :default-branch branch
           :default-branch-source source)
    branch))

(defn- refresh-default-branch-cache!
  []
  (let [session    (current-session-query)
        current-wt (:git.worktree/current session)
        main-wt    (or (current-main-worktree) current-wt)]
    (if (or (nil? current-wt) (nil? main-wt))
      (do
        (swap! state assoc
               :default-branch nil
               :default-branch-source nil)
        nil)
      (cache-default-branch! main-wt current-wt))))

(defn- default-branch!
  [main-wt current-wt]
  (or (some-> @state :default-branch not-empty)
      (cache-default-branch! main-wt current-wt)))

(defn- branch-ready-for-ff?
  [feature-path default-branch]
  (when (and (seq feature-path) (seq default-branch))
    (git/branch-tip-merged-into-current? (git/create-context feature-path)
                                         default-branch)))

(defn- work-done-rebase-task
  [current-branch default-branch]
  (str "Rebase the current worktree branch `" current-branch "` onto `" default-branch "`.\n"
       "\n"
       "Requirements:\n"
       "- Work only in the current git worktree.\n"
       "- Verify whether the branch can be rebased cleanly onto `" default-branch "`.\n"
       "- If the working tree is dirty or the rebase fails, stop immediately and explain why.\n"
       "- Do not merge, delete the branch, or remove the worktree.\n"
       "- In the final response, clearly state whether the rebase succeeded.\n"))

(defn- run-rebase-agent!
  [current-branch default-branch]
  (let [result      (mutate! 'psi.extension.tool/chain
                             {:steps [{:id "work-done-rebase"
                                       :tool "agent"
                                       :args {"action" "create"
                                              "task" (work-done-rebase-task current-branch default-branch)
                                              "mode" "sync"
                                              "fork_session" true
                                              "timeout_ms" 600000}}]
                              :stop-on-error? true})
        step-result (-> result :psi.extension.tool-plan/results first :result)
        content     (squish (:content step-result))
        ok?         (and (true? (:psi.extension.tool-plan/succeeded? result))
                         (not (true? (:is-error step-result))))]
    {:ok? ok?
     :content content
     :error (or (when-not ok?
                  (or (squish (:psi.extension.tool-plan/error result))
                      content
                      "agent did not complete"))
                content)}))

(defn- merge-and-cleanup!
  [current-branch current-path main-wt default-branch auto-rebased?]
  (let [main-path      (:git.worktree/path main-wt)
        main-git-ctx   (git/create-context main-path)
        before-branch  (git/current-branch main-git-ctx)
        before-head    (git/current-commit main-git-ctx)
        merge-result   (mutate! 'git.branch/merge!
                                {:git/context {:cwd main-path}
                                 :input {:branch current-branch
                                         :strategy :ff_only}})
        after-branch   (git/current-branch main-git-ctx)
        after-head     (git/current-commit main-git-ctx)
        merged?        (and (:merged merge-result)
                            (git/branch-tip-merged-into-current? main-git-ctx current-branch))]
    (cond
      (not (:merged merge-result))
      {:ok? false
       :error (if (and (:error merge-result)
                       (str/includes? (:error merge-result) "not fast-forwardable"))
                (str "branch is not fast-forwardable onto " default-branch
                     (when auto-rebased?
                       " after automatic rebase")
                     "; worktree preserved for safety")
                (or (:error merge-result) "merge failed"))}

      (not merged?)
      {:ok? false
       :error (str "merge did not update " default-branch
                   "; worktree preserved for safety"
                   " (source=" current-branch
                   ", merge-reported=" (boolean (:merged merge-result))
                   ", merge-error=" (pr-str (:error merge-result))
                   ", before-branch=" before-branch
                   ", after-branch=" after-branch
                   ", before-head=" before-head
                   ", after-head=" after-head
                   ", head-changed=" (not= before-head after-head)
                   ", verification=branch tip not ancestor of target HEAD)")}

      :else
      (let [remove-result (mutate! 'git.worktree/remove!
                                   {:input {:path current-path
                                            :force false}})
            delete-result (mutate! 'git.branch/delete!
                                   {:input {:branch current-branch
                                            :force false}})
            main-session  (or (find-session-for-worktree main-path)
                              (when main-path
                                {:psi.session-info/id
                                 (:psi.agent-session/session-id
                                  (create-worktree-session!
                                   (or (:git.worktree/branch-name main-wt) default-branch "main")
                                   main-path
                                   nil))}))
            _             (switch-to-session! (:psi.session-info/id main-session))]
        {:ok? true
         :branch current-branch
         :into-branch default-branch
         :auto-rebased? auto-rebased?
         :worktree-removed (:success remove-result)
         :branch-deleted (:deleted delete-result)
         :main-session-id (:psi.session-info/id main-session)
         :cleanup-error (cond
                          (not (:success remove-result)) (str "worktree remove failed: " (:error remove-result))
                          (not (:deleted delete-result)) (str "branch delete failed: " (:error delete-result))
                          :else nil)}))))

(defn- work-on-result
  [ok? & kvs]
  (assoc (apply hash-map kvs) :ok? ok? :action :work-on))

(defn- work-on-error
  [message]
  (work-on-result false :error message))

(defn- work-on-result-message
  [result]
  (if (:ok? result)
    (str "Working in `" (:worktree-path result)
         "` on branch `" (:branch-name result) "`")
    (:error result)))

(defn- work-on-tool-result
  [result]
  {:content  (work-on-result-message result)
   :is-error (not (:ok? result))
   :details  result})

(defn- work-on-target
  [description current-wt main-wt]
  (let [{:keys [slug branch-name]} (mechanical-slug description)]
    {:branch-name branch-name
     :worktree-path (target-worktree-path (:git.worktree/path main-wt)
                                          (:git.worktree/path current-wt)
                                          slug)}))

(defn- add-worktree!
  [worktree-path branch-name]
  (let [create-result (mutate! 'git.worktree/add!
                               {:input {:path worktree-path
                                        :branch branch-name
                                        :base_ref nil
                                        :create-branch true}})]
    (if (= "branch already exists" (:error create-result))
      (mutate! 'git.worktree/add!
               {:input {:path worktree-path
                        :branch branch-name
                        :base_ref nil
                        :create-branch false}})
      create-result)))

(defn- create-work-on-session-result
  [session description worktree-path branch-name reused?]
  (let [origin-session-id (:psi.agent-session/session-id session)
        _                 (set-current-session-worktree-path! origin-session-id worktree-path)
        sd                (create-worktree-session! description worktree-path origin-session-id)]
    (work-on-result true
                    :reused? reused?
                    :worktree-path worktree-path
                    :branch-name branch-name
                    :session-id (created-session-id sd)
                    :session-name description)))

(defn- reuse-existing-worktree-result
  [session description worktree-path branch-name]
  (if-let [existing-wt (find-worktree-by-path worktree-path)]
    (let [origin-session-id (:psi.agent-session/session-id session)
          _                 (set-current-session-worktree-path! origin-session-id worktree-path)
          resolved-branch   (or (:git.worktree/branch-name existing-wt) branch-name)
          existing-session  (find-session-for-worktree worktree-path)]
      (if existing-session
        (do
          (switch-to-session! (:psi.session-info/id existing-session))
          (work-on-result true
                          :reused? true
                          :worktree-path worktree-path
                          :branch-name resolved-branch
                          :session-id (:psi.session-info/id existing-session)
                          :session-name (:psi.session-info/name existing-session)))
        (create-work-on-session-result session description worktree-path resolved-branch true)))
    (work-on-error
     (str "worktree path already exists but is not a registered git worktree: "
          worktree-path))))

(defn- execute-work-on!
  [description]
  (let [description* (trim-arg description)]
    (if (nil? description*)
      (work-on-error "usage: /work-on <description>")
      (let [session    (current-session-query)
            current-wt (:git.worktree/current session)
            main-wt    (or (current-main-worktree) current-wt)]
        (cond
          (nil? current-wt)
          (work-on-error "not inside a git repository")

          (nil? main-wt)
          (work-on-error "main worktree not found")

          :else
          (let [{:keys [worktree-path branch-name]} (work-on-target description* current-wt main-wt)
                add-result                          (add-worktree! worktree-path branch-name)]
            (cond
              (:success add-result)
              (create-work-on-session-result session description* worktree-path branch-name false)

              (= "worktree path already exists" (:error add-result))
              (reuse-existing-worktree-result session description* worktree-path branch-name)

              :else
              (work-on-error
               (str "worktree creation failed: "
                    (or (:error add-result)
                        "missing git mutation payload"))))))))))

(defn work-on!
  [description]
  (execute-work-on! description))

(defn work-done!
  []
  (let [session        (current-session-query)
        current-wt     (:git.worktree/current session)
        current-path   (:git.worktree/path current-wt)
        current-branch (:git.worktree/branch-name current-wt)
        main-wt        (current-main-worktree)]
    (cond
      (or (nil? current-wt) (nil? current-path))
      {:ok? false :error "not inside a git repository"}

      (or (nil? main-wt)
          (= current-path (:git.worktree/path main-wt)))
      {:ok? false :error "already on main worktree; nothing to do"}

      :else
      (let [default-branch (default-branch! main-wt current-wt)
            ff-ready?      (branch-ready-for-ff? current-path default-branch)]
        (if ff-ready?
          (merge-and-cleanup! current-branch current-path main-wt default-branch false)
          (let [rebase-result   (run-rebase-agent! current-branch default-branch)
                ff-ready-after? (branch-ready-for-ff? current-path default-branch)]
            (cond
              (not (:ok? rebase-result))
              {:ok? false
               :error (str "automatic rebase onto `" default-branch "` failed"
                           (when-let [detail (squish (:error rebase-result))]
                             (str ": " detail)))}

              (not ff-ready-after?)
              {:ok? false
               :error (str "automatic rebase onto `" default-branch
                           "` did not make `" current-branch
                           "` fast-forwardable; worktree preserved for safety")}

              :else
              (merge-and-cleanup! current-branch current-path main-wt default-branch true))))))))

(defn work-rebase!
  []
  (let [session        (current-session-query)
        current-wt     (:git.worktree/current session)
        current-path   (:git.worktree/path current-wt)
        current-branch (:git.worktree/branch-name current-wt)
        main-wt        (current-main-worktree)
        default-branch (default-branch! main-wt current-wt)]
    (cond
      (or (nil? current-wt) (nil? current-path))
      {:ok? false :error "not inside a git repository"}

      (or (nil? main-wt)
          (= current-path (:git.worktree/path main-wt)))
      {:ok? false :error "already on main worktree; nothing to rebase"}

      :else
      (let [result (mutate! 'git.branch/rebase!
                            {:git/context {:cwd current-path}
                             :input {:onto default-branch
                                     :branch current-branch}})]
        {:ok? (:success result)
         :branch current-branch
         :onto default-branch
         :error (:error result)}))))

(defn work-status-text
  []
  (let [session   (current-session-query)
        current   (:git.worktree/current session)
        worktrees (or (:git.worktree/list session) [])
        linked    (remove #(= (:git.worktree/path %) (:git.worktree/path (current-main-worktree))) worktrees)]
    (str "Active worktrees:\n"
         (if (seq linked)
           (str/join "\n"
                     (map (fn [wt]
                            (str "- " (:git.worktree/path wt)
                                 " [" (or (:git.worktree/branch-name wt) "detached") "]"
                                 (when (= (:git.worktree/path wt) (:git.worktree/path current))
                                   " (current)")))
                          linked))
           "(none)"))))

(defn- handle-work-on-command
  [args]
  (append-assistant-message! (work-on-result-message (execute-work-on! args))))

(defn- handle-work-done-command
  [_args]
  (let [result (work-done!)]
    (append-assistant-message!
     (if (:ok? result)
       (str "Fast-forwarded `" (:branch result)
            "` into `" (:into-branch result) "`"
            (when (:auto-rebased? result)
              " after automatic rebase")
            (if-let [cleanup-error (:cleanup-error result)]
              (str ", but cleanup incomplete: " cleanup-error)
              ", removed worktree, kept session transcript"))
       (:error result)))))

(defn- handle-work-rebase-command
  [_args]
  (let [result (work-rebase!)]
    (append-assistant-message!
     (if (:ok? result)
       (str "Rebased `" (:branch result) "` onto `" (:onto result) "`")
       (:error result)))))

(defn- handle-work-status-command
  [_args]
  (append-assistant-message! (work-status-text)))

(defn init
  [api]
  (swap! state assoc
         :query-fn (:query api)
         :mutate-fn (:mutate api)
         :create-session-fn (:create-session api)
         :switch-session-fn (:switch-session api)
         :set-worktree-path-fn (:set-worktree-path api)
         :notify-fn (:notify api)
         :append-message-fn (:append-message api)
         :path (:path api))
  ((:on api) "session_switch"
             (fn [_]
               (refresh-default-branch-cache!)
               nil))
  ((:register-tool api)
   {:name        "work-on"
    :label       "Work On"
    :description "Create a layout-derived git worktree + branch and continue there"
    :parameters  {:type       "object"
                  :properties {"description" {:type "string"
                                              :description "Description of the work to create a branch/worktree for"}}
                  :required   ["description"]}
    :execute     (fn
                   ([args] (work-on-tool-result (execute-work-on! (get args "description"))))
                   ([args _opts] (work-on-tool-result (execute-work-on! (get args "description")))))})
  ((:register-command api) "work-on"
                           {:description "Create a layout-derived git worktree + branch and continue there"
                            :handler handle-work-on-command})
  ((:register-command api) "work-done"
                           {:description "Finish current worktree onto the default branch and clean up"
                            :handler handle-work-done-command})
  ((:register-command api) "work-rebase"
                           {:description "Rebase current worktree branch onto main"
                            :handler handle-work-rebase-command})
  ((:register-command api) "work-status"
                           {:description "Show active worktree overview"
                            :handler handle-work-status-command})
  (refresh-default-branch-cache!))