(ns psi.tool-runtime.call-summary-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.tool-runtime.call-summary :as sut]))

(deftest built-in-formatters-test
  (testing "read"
    (is (= "read src/foo.clj:10:29"
           (sut/read-format-request {:arguments "{\"path\":\"src/foo.clj\",\"offset\":10,\"limit\":20}"}))))
  (testing "edit"
    (is (= "edit src/bar.clj:20:22"
           (sut/edit-format-request {:arguments "{\"path\":\"src/bar.clj\",\"oldText\":\"a\\nb\\nc\"}"
                                     :details {:firstChangedLine 20}}))))
  (testing "bash"
    (is (= "$ git status"
           (sut/bash-format-request {:arguments "{\"command\":\"git status\"}"}))))
  (testing "write"
    (is (= "write README.md"
           (sut/write-format-request {:arguments "{\"path\":\"README.md\"}"}))))
  (testing "edit-clj"
    (is (= "edit-clj src/core.clj:5:6"
           (sut/edit-clj-format-request {:arguments "{\"filename\":\"src/core.clj\",\"old-string\":\"a\\nb\"}"
                                         :details {:firstChangedLine 5}})))))

(deftest psi-tool-format-test
  (is (= "psi-tool query [:x]"
         (sut/psi-tool-format-request {:arguments "{\"query\":\"[:x]\"}"})))
  (is (= "psi-tool eval clojure.core"
         (sut/psi-tool-format-request {:arguments "{\"action\":\"eval\",\"ns\":\"clojure.core\"}"})))
  (is (= "psi-tool workflow op=execute-run"
         (sut/psi-tool-format-request {:arguments "{\"action\":\"workflow\",\"op\":\"execute-run\"}"}))))

(deftest delegate-format-test
  (is (= "delegate list"
         (sut/delegate-format-request {:arguments "{}"})))
  (is (= "delegate run lambda-build"
         (sut/delegate-format-request {:arguments "{\"workflow\":\"lambda-build\"}"})))
  (is (= "delegate continue run-123"
         (sut/delegate-format-request {:arguments "{\"action\":\"continue\",\"id\":\"run-123\"}"}))))

(deftest work-on-format-test
  (is (= "work-on fix flaky workflow test"
         (sut/work-on-format-request {:arguments "{\"description\":\"fix flaky workflow test\"}"})))
  (is (= "work-on github issue 27 triage from origin/master"
         (sut/work-on-format-request {:arguments "{\"description\":\"github issue 27 triage\",\"base_branch\":\"origin/master\"}"}))))

(deftest format-call-summary-fallback-test
  (is (= "x"
         (sut/format-call-summary {:tool-name "x"})))
  (is (= "bad"
         (sut/format-call-summary {:tool-name "bad"
                                   :tool {:name "bad"
                                          :format-request (fn [_] (throw (ex-info "boom" {})))}})))
  (is (= "ok value"
         (sut/format-call-summary {:tool-name "ok"
                                   :tool {:name "ok"
                                          :format-request (fn [_] "ok value")}}))))
