(ns psi.tui.app-api-cleanup-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.tui.app :as app]
   [psi.tui.ansi :as ansi]))

(deftest make-init-rejects-legacy-launch-model-shape-with-explicit-contract-error-test
  (testing "make-init rejects the old launch-model argument shape at the boundary"
    (let [ex (is (thrown? clojure.lang.ExceptionInfo
                          ((app/make-init "legacy-model") {})))]
      (is (= :invalid-query-fn (:error (ex-data ex))))
      (is (= :psi.tui.app/make-init (:api (ex-data ex))))
      (is (re-find #"old launch-model argument shape" (ex-message ex))))))

(deftest start-rejects-legacy-launch-model-shape-with-explicit-contract-error-test
  (testing "start! rejects the old launch-model argument shape at the boundary"
    (let [ex (is (thrown? clojure.lang.ExceptionInfo
                          (app/start! "legacy-model" (fn [_ _]))))]
      (is (= :invalid-run-agent-fn (:error (ex-data ex))))
      (is (= :psi.tui.app/start! (:api (ex-data ex))))
      (is (re-find #"old launch-model argument shape" (ex-message ex))))))

(deftest start-rejects-non-map-opts-with-explicit-contract-error-test
  (testing "start! rejects non-map opts explicitly"
    (let [ex (is (thrown? clojure.lang.ExceptionInfo
                          (app/start! (fn [_ _]) :not-a-map)))]
      (is (= :invalid-opts (:error (ex-data ex))))
      (is (= :psi.tui.app/start! (:api (ex-data ex)))))))

(deftest make-init-empty-footer-model-omits-banner-model-line-test
  (testing "without canonical footer model text, the banner omits the model line"
    (let [[state _] ((app/make-init nil nil nil {:dispatch-fn (constantly nil)}))
          plain     (ansi/strip-ansi (app/view state))]
      (is (not (.contains plain "  Model: "))))))
