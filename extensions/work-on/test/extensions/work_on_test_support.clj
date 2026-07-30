(ns extensions.work-on-test-support
  (:require
   [psi.agent-session.core :as session]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.extensions.runtime-fns :as runtime-fns]
   [psi.agent-session.mutations :as mutations]
   [psi.tool-registry.registry :as tool-registry]))

(def ^:private session-query
  [:psi.agent-session/session-id
   :psi.agent-session/session-name
   :psi.agent-session/session-file
   :psi.agent-session/worktree-path
   :psi.agent-session/system-prompt
   :psi.agent-session/host-sessions
   :git.worktree/current
   :git.worktree/list])

(defn with-session-query
  [result-map]
  (fn [q]
    (cond
      (= session-query q) result-map
      (= [:git.branch/default-branch] q)
      {:git.branch/default-branch {:branch "main" :source :fallback}}
      :else {})))

(defn worktree-ff-state
  [state]
  (fn [ctx branch]
    (cond
      (= "/repo/feature-x" (:repo-dir ctx))
      (if (instance? clojure.lang.IDeref state)
        (and (= branch "main") (= :after @state))
        (= branch "main"))

      (= "/repo/main" (:repo-dir ctx))
      (= branch "feature-x")

      :else false)))

(defn run-work-command! [state command]
  ((get-in @state [:commands command :handler]) ""))

(defn appended-message-texts [calls]
  (->> calls
       (filter #(= 'psi.extension/append-message (first %)))
       (map (comp :content second))
       vec))

(defn create-two-session-context []
  (let [ctx (session/create-context {:persist? false
                                     :mutations mutations/all-mutations})
        s1  (session/new-session-in! ctx nil {:session-name "one"})
        s2  (session/new-session-in! ctx (:session-id s1) {:session-name "two"})]
    [ctx (:session-id s1) (:session-id s2)]))

(defn make-runtime-work-on-api
  [ctx load-session-id active-session-id ext-path mutate-calls]
  (let [reg (:extension-registry ctx)
        _   (ext/register-extension-in! reg ext-path)
        runtime-fns* (assoc (runtime-fns/make-extension-runtime-fns ctx load-session-id ext-path)
                            :query-fn
                            (fn [req]
                              (let [sid0  (or runtime-fns/*active-extension-session-id* load-session-id)
                                    sid   (if (and (map? req) (contains? req :query))
                                            (or (:session-id req) sid0)
                                            sid0)
                                    query (if (and (map? req) (contains? req :query))
                                            (:query req)
                                            req)]
                                (cond
                                  (= query session-query)
                                  {:psi.agent-session/session-id sid
                                   :psi.agent-session/worktree-path (if (= sid active-session-id) "/repo/two" "/repo/one")
                                   :psi.agent-session/system-prompt "prompt"
                                   :psi.agent-session/host-sessions []
                                   :git.worktree/current {:git.worktree/path (if (= sid active-session-id) "/repo/two" "/repo/one")
                                                          :git.worktree/branch-name "main"
                                                          :git.worktree/current? true}
                                   :git.worktree/list [{:git.worktree/path (if (= sid active-session-id) "/repo/two" "/repo/one")
                                                        :git.worktree/branch-name "main"
                                                        :git.worktree/current? true}]}

                                  (= query [:git.branch/default-branch])
                                  {:git.branch/default-branch {:branch "main" :source :fallback}}

                                  :else {})))
                            :mutate-fn
                            (fn [op params]
                              (swap! mutate-calls conj [op params])
                              (cond
                                (= op 'psi.extension/register-command)
                                (do
                                  (ext/register-command-in! reg ext-path (assoc (:opts params) :name (:name params)))
                                  {:psi.extension/command-names (vec (ext/command-names-in reg))})

                                (= op 'psi.extension/register-tool)
                                (do
                                  (tool-registry/register-tool-in! reg ext-path (:tool params))
                                  {:psi.extension/registered-tool? true})

                                (= op 'git.worktree/add!)
                                {:success true
                                 :path (get-in params [:input :path])
                                 :branch (get-in params [:input :branch])
                                 :head "abc123"}

                                (= op 'psi.extension/set-worktree-path)
                                {:psi.agent-session/worktree-path (:worktree-path params)}

                                (= op 'psi.extension/create-session)
                                {:psi.agent-session/session-id "s-created"
                                 :psi.agent-session/session-name (:session-name params)
                                 :psi.agent-session/worktree-path (:worktree-path params)}

                                (= op 'psi.extension/append-message)
                                {:psi.extension/message params}

                                :else nil))
                            :notify-fn nil)]
    {:reg reg
     :api (ext/create-extension-api reg ext-path runtime-fns*)}))
