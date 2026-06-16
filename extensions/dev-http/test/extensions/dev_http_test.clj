(ns extensions.dev-http-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.dev-http :as sut]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(deftest init-captures-api-test
  (testing "init captures the runtime ExtensionAPI map into the extension atom"
    (let [{:keys [api]} (nullable/create-nullable-extension-api
                         {:path "/test/dev_http.clj"})]
      (is (nil? (sut/init api)))
      (is (identical? api (:api @@#'sut/state))))))
