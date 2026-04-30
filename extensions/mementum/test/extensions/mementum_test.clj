(ns extensions.mementum-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [extensions.mementum :as sut]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(defn- read-resource-text [resource-path]
  (slurp (io/resource resource-path) :encoding "UTF-8"))

(defn- registered-contribution [state]
  (first (vals (:prompt-contributions @state))))

(deftest init-registers-lambda-mode-prompt-contribution-test
  (let [{:keys [api state]} (nullable/create-nullable-extension-api
                             {:path "/test/mementum.clj"
                              :query-fn (fn [_]
                                          {:psi.agent-session/prompt-mode :lambda})})
        protocol-body (read-resource-text "extensions/mementum/protocol.txt")]
    (sut/init api)
    (is (= {:id "mementum-protocol"
            :ext-path "/test/mementum.clj"
            :section "Mementum Protocol"
            :priority 50
            :enabled true
            :content protocol-body}
           (registered-contribution state)))))

(deftest init-registers-prose-mode-prompt-contribution-test
  (let [{:keys [api state]} (nullable/create-nullable-extension-api
                             {:path "/test/mementum.clj"
                              :query-fn (fn [_]
                                          {:psi.agent-session/prompt-mode :prose})})
        protocol-body (read-resource-text "extensions/mementum/protocol.txt")
        engage-prefix (str (var-get #'sut/engage-prefix))]
    (sut/init api)
    (is (= {:id "mementum-protocol"
            :ext-path "/test/mementum.clj"
            :section "Mementum Protocol"
            :priority 50
            :enabled true
            :content (str engage-prefix protocol-body)}
           (registered-contribution state)))))

(deftest missing-resource-load-fails-fast-test
  (testing "missing required resource throws ex-info naming the resource path"
    (let [resource-path "extensions/mementum/missing-protocol.txt"
          ex (try
               (#'sut/read-protocol-resource resource-path)
               nil
               (catch clojure.lang.ExceptionInfo e
                 e))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (= :mementum (:extension (ex-data ex))))
      (is (= resource-path (:resource-path (ex-data ex))))
      (is (.contains (.getMessage ex) resource-path)))))
