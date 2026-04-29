(ns psi.tui.app-api-cleanup-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.tui.app :as app]
   [psi.tui.ansi :as ansi]))

(deftest make-init-no-longer-accepts-launch-model-parameter-test
  (testing "make-init now takes query/ui wiring only, not a launch model string"
    (is (thrown? clojure.lang.ArityException
                 ((app/make-init "legacy-model") {})))))

(deftest start-no-longer-accepts-launch-model-parameter-test
  (testing "start! now takes run-agent-fn and opts, not a launch model string"
    (is (thrown? IllegalArgumentException
                 (app/start! "legacy-model" (fn [_ _]))))))

(deftest make-init-empty-footer-model-omits-banner-model-line-test
  (testing "without canonical footer model text, the banner omits the model line"
    (let [[state _] ((app/make-init nil nil nil {:dispatch-fn (constantly nil)}))
          plain     (ansi/strip-ansi (app/view state))]
      (is (not (.contains plain "  Model: "))))))
