(ns psi.agent-session.extension-targeting-runtime-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.core :as session]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.extensions.runtime-fns :as runtime-fns]
   [psi.agent-session.mutations :as mutations]))

(defn- create-two-session-context []
  (let [ctx (session/create-context {:persist? false
                                     :mutations mutations/all-mutations})
        s1  (session/new-session-in! ctx nil {:session-name "one"})
        s2  (session/new-session-in! ctx (:session-id s1) {:session-name "two"})]
    [ctx (:session-id s1) (:session-id s2)]))

(deftest runtime-fns-support-explicit-session-targeting-test
  (testing "runtime query/mutate fns can target a session explicitly"
    (let [[ctx s1 s2] (create-two-session-context)
          reg        (:extension-registry ctx)
          ext-path   "/ext/test"
          _          (ext/register-extension-in! reg ext-path)
          api        (ext/create-extension-api reg ext-path (runtime-fns/make-extension-runtime-fns ctx s1 ext-path))]
      ((:mutate-session api) s2 'psi.extension/set-session-name {:name "renamed-two"})
      (is (= "renamed-two"
             (:psi.agent-session/session-name
              ((:query-session api) s2 [:psi.agent-session/session-name])))))))

(deftest extension-command-implicit-targeting-follows-active-session-after-new-test
  (testing "desired behavior: extension command implicit query path follows the active session after /new"
    (let [[ctx s1 s2] (create-two-session-context)
          reg        (:extension-registry ctx)
          ext-path   "/ext/test"
          captured   (atom nil)
          _          (ext/register-extension-in! reg ext-path)
          api        (ext/create-extension-api reg ext-path (runtime-fns/make-extension-runtime-fns ctx s1 ext-path))
          _          ((:register-command api)
                      "which-session"
                      {:description "Report implicit extension session id"
                       :handler     (fn [_args]
                                      (reset! captured
                                              (:psi.agent-session/session-id
                                               ((:query api) [:psi.agent-session/session-id]))))})
          result     (commands/dispatch-in ctx s2 "/which-session" {:supports-session-tree? false})]
      (is (= :extension-cmd (:type result)))
      ((:handler result) (:args result))
      (is (= s2 @captured)))))
