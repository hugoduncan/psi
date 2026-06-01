(ns psi.agent-session.extensions-api-follow-on-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.state-kernel.dispatch :as kernel]
   [psi.agent-session.extension-runtime :as ext-rt]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.test-support :as test-support]))

(deftest extension-ui-origin-authorization-test
  (testing "extension UI dispatch includes ext-id so extension-origin events are authorized"
    (let [[ctx session-id] (test-support/make-session-ctx {})
          ext-path         "/ext/test"
          _                (ext/register-extension-in! (:extension-registry ctx) ext-path)
          ui               (#'ext-rt/extension-ui-context ctx session-id (fn [] {:ui-type :emacs}) ext-path)]
      ((:set-widget ui) "w1" :below-editor ["hello"])
      (let [entry (last (kernel/event-log-entries))]
        (is (= :session/ui-set-widget (:event-type entry)))
        (is (= :extension (:origin entry)))
        (is (= ext-path (:ext-id entry)))
        (is (not (:blocked? entry)))))))

(deftest extension-api-follow-on-helper-test
  (testing "API :get-api-key delegates to runtime get-api-key fn"
    (let [reg         (ext/create-registry)
          _           (ext/register-extension-in! reg "/ext/test")
          runtime-fns {:get-api-key-fn (fn [provider] (str "key-for-" provider))}
          api         (ext/create-extension-api reg "/ext/test" runtime-fns)]
      (is (= "key-for-openai" ((:get-api-key api) "openai")))))

  (testing "API schedule-event delegates to runtime mutate fn"
    (let [reg         (ext/create-registry)
          _           (ext/register-extension-in! reg "/ext/test")
          runtime-fns {:mutate-fn (fn [op params] {:op op :params params})}
          api         (ext/create-extension-api reg "/ext/test" runtime-fns)]
      (is (= {:op 'psi.extension/schedule-event
              :params {:delay-ms 250
                       :event-name "rename-checkpoint"
                       :payload {:session-id "s1"}
                       :ext-path "/ext/test"}}
             ((:mutate api) 'psi.extension/schedule-event
                            {:delay-ms 250
                             :event-name "rename-checkpoint"
                             :payload {:session-id "s1"}})))))

  (testing "API query-session and mutate-session target explicit sessions"
    (let [reg         (ext/create-registry)
          _           (ext/register-extension-in! reg "/ext/test")
          runtime-fns {:query-fn  (fn [req] {:req req})
                       :mutate-fn (fn [op params] {:op op :params params})}
          api         (ext/create-extension-api reg "/ext/test" runtime-fns)]
      (is (= {:req {:session-id "s2"
                    :query [:psi.agent-session/message-history]}}
             ((:query-session api) "s2" [:psi.agent-session/message-history])))
      (is (= {:op 'psi.extension/set-session-name
              :params {:session-id "s2" :name "New name"}}
             ((:mutate-session api) "s2" 'psi.extension/set-session-name {:name "New name"})))))

  (testing "API prompt contribution helpers delegate to runtime"
    (let [reg   (ext/create-registry)
          _     (ext/register-extension-in! reg "/ext/test")
          calls (atom [])
          runtime-fns {:mutate-fn (fn [op params]
                                    (swap! calls conj {:op op :params params})
                                    {:ok true})
                       :query-fn  (fn [_]
                                    {:psi.extension/prompt-contributions
                                     [{:id "a" :ext-path "/ext/test" :content "x"}
                                      {:id "b" :ext-path "/ext/other" :content "y"}]})}
          api   (ext/create-extension-api reg "/ext/test" runtime-fns)]
      ((:register-prompt-contribution api) "a" {:content "x"})
      ((:update-prompt-contribution api) "a" {:enabled false})
      ((:unregister-prompt-contribution api) "a")
      (is (= [{:id "a" :ext-path "/ext/test" :content "x"
               :section nil :priority nil :enabled nil
               :created-at nil :updated-at nil}]
             ((:list-prompt-contributions api))))
      (is (= ['psi.extension/register-prompt-contribution
              'psi.extension/update-prompt-contribution
              'psi.extension/unregister-prompt-contribution]
             (mapv :op @calls))))))
