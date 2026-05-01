(ns extensions.auto-session-name-runtime-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.auto-session-name :as sut]
   [psi.ai.model-registry :as model-registry]
   [psi.agent-session.core :as session]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.extensions.runtime-fns :as runtime-fns]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.session-state :as ss]))

(defn- create-runtime-session []
  (let [ctx (session/create-context {:persist? false
                                     :mutations mutations/all-mutations})
        sd  (session/new-session-in! ctx nil {:session-name "initial"})]
    [ctx (:session-id sd)]))

(defn- create-ext-api [ctx session-id ext-path]
  (let [reg              (:extension-registry ctx)
        runtime-fs       (runtime-fns/make-extension-runtime-fns ctx session-id ext-path)
        base-mutate      (:mutate-fn runtime-fs)
        last-run-params* (atom nil)
        runtime-fs'      (assoc runtime-fs
                                :mutate-fn
                                (fn [op params]
                                  (case op
                                    psi.extension/create-child-session
                                    {:psi.agent-session/session-id "child-1"}

                                    psi.extension/run-agent-loop-in-session
                                    (do (reset! last-run-params* params)
                                        {:psi.agent-session/agent-run-ok? true
                                         :psi.agent-session/agent-run-text "Fix footer rendering"})

                                    ;; Stub close-session so it doesn't try to resolve worktree-path
                                    ;; for the fake "child-1" session that was never actually created.
                                    psi.extension/close-session
                                    {:psi.agent-session/close-session-closed? true
                                     :psi.agent-session/close-session-id (:session-id params)}

                                    (base-mutate op params))))]
    (ext/register-extension-in! reg ext-path)
    {:api              (ext/create-extension-api reg ext-path runtime-fs')
     :last-run-params* last-run-params*}))

(defn- seed-conversation! [ctx session-id]
  (ss/journal-append-in! ctx session-id
                         {:id "m1"
                          :kind :message
                          :timestamp (java.time.Instant/now)
                          :data {:message {:role "user"
                                           :content [{:type :text :text "Fix footer rendering"}]}}})
  (ss/journal-append-in! ctx session-id
                         {:id "m2"
                          :kind :message
                          :timestamp (java.time.Instant/now)
                          :data {:message {:role "assistant"
                                           :content [{:type :text :text "I will inspect the selector path."}]}}}))

(defn- checkpoint-handler [ctx ext-path]
  (-> (get-in @(:state (:extension-registry ctx)) [:extensions ext-path :handlers "auto_session_name/rename_checkpoint"])
      first
      :handler))

(deftest checkpoint-runtime-path-renames-source-session-test
  (testing "real runtime api path renames source session from checkpoint handler"
    (let [[ctx session-id]   (create-runtime-session)
          ext-path           "/test/auto_session_name.clj"
          {:keys [api last-run-params*]} (create-ext-api ctx session-id ext-path)]
      (reset! @#'sut/state {:turn-counts {}
                            :helper-session-ids #{}
                            :last-auto-name-by-session {}
                            :turn-interval 2
                            :delay-ms 250
                            :log-fn nil
                            :ui nil})
      (model-registry/init! {})
      (model-registry/init! {})
      (model-registry/init! {})
      (seed-conversation! ctx session-id)
      (sut/init api)
      ((checkpoint-handler ctx ext-path) {:session-id session-id :turn-count 2})
      (is (= "Fix footer rendering"
             (:session-name (ss/get-session-data-in ctx session-id))))
      (is (= "Conversation excerpt:\n\nUser: Fix footer rendering\nAssistant: I will inspect the selector path."
             (:prompt @last-run-params*)))
      (is (= {:provider :openai
              :id "gpt-5.3-codex-spark"}
             (some-> @last-run-params*
                     :model
                     (select-keys [:provider :id])))))))

(deftest stale-checkpoint-runtime-path-preserves-current-name-test
  (testing "stale checkpoint does not rename source session"
    (let [[ctx session-id]   (create-runtime-session)
          ext-path           "/test/auto_session_name.clj"
          {:keys [api]}      (create-ext-api ctx session-id ext-path)]
      (reset! @#'sut/state {:turn-counts {}
                            :helper-session-ids #{}
                            :last-auto-name-by-session {}
                            :turn-interval 2
                            :delay-ms 250
                            :log-fn nil
                            :ui nil})
      (seed-conversation! ctx session-id)
      (sut/init api)
      (swap! @#'sut/state assoc :turn-counts {session-id 4})
      ((checkpoint-handler ctx ext-path) {:session-id session-id :turn-count 2})
      (is (= "initial"
             (:session-name (ss/get-session-data-in ctx session-id)))))))

(deftest manual-override-runtime-path-preserves-current-name-test
  (testing "manual current name blocks auto overwrite"
    (let [[ctx session-id]   (create-runtime-session)
          ext-path           "/test/auto_session_name.clj"
          {:keys [api]}      (create-ext-api ctx session-id ext-path)]
      (reset! @#'sut/state {:turn-counts {}
                            :helper-session-ids #{}
                            :last-auto-name-by-session {}
                            :turn-interval 2
                            :delay-ms 250
                            :log-fn nil
                            :ui nil})
      (seed-conversation! ctx session-id)
      (session/set-session-name-in! ctx session-id "Manual name")
      (sut/init api)
      (swap! @#'sut/state assoc :last-auto-name-by-session {session-id "Old auto name"})
      ((checkpoint-handler ctx ext-path) {:session-id session-id :turn-count 2})
      (is (= "Manual name"
             (:session-name (ss/get-session-data-in ctx session-id)))))))

(deftest close-session-fires-even-when-agent-loop-throws-test
  (testing "helper session is closed and removed from helper-session-ids even when run-agent-loop-in-session throws"
    (let [[ctx session-id] (create-runtime-session)
          ext-path         "/test/auto_session_name_throw.clj"
          close-calls*     (atom [])
          reg              (:extension-registry ctx)
          runtime-fs       (runtime-fns/make-extension-runtime-fns ctx session-id ext-path)
          base-mutate      (:mutate-fn runtime-fs)
          runtime-fs'      (assoc runtime-fs
                                  :mutate-fn
                                  (fn [op params]
                                    (case op
                                      psi.extension/create-child-session
                                      {:psi.agent-session/session-id "child-throw"}

                                      psi.extension/run-agent-loop-in-session
                                      (throw (ex-info "Connection refused" {:cause :network}))

                                      psi.extension/close-session
                                      (do (swap! close-calls* conj (:session-id params))
                                          {:psi.agent-session/close-session-closed? true
                                           :psi.agent-session/close-session-id      (:session-id params)})

                                      (base-mutate op params))))
          api              (do (ext/register-extension-in! reg ext-path)
                               (ext/create-extension-api reg ext-path runtime-fs'))]
      (reset! @#'sut/state {:turn-counts {}
                            :helper-session-ids #{}
                            :last-auto-name-by-session {}
                            :turn-interval 2
                            :delay-ms 250
                            :log-fn nil
                            :ui nil})
      (seed-conversation! ctx session-id)
      (sut/init api)
      ;; Checkpoint fires; agent loop throws — close must still be called and state cleaned up.
      ((checkpoint-handler ctx ext-path) {:session-id session-id :turn-count 2})
      (is (= ["child-throw"] @close-calls*)
          "close-session was called despite agent-loop throwing")
      (is (not (contains? (:helper-session-ids (deref @#'sut/state)) "child-throw"))
          "helper-session-ids cleaned up despite agent-loop throwing"))))
