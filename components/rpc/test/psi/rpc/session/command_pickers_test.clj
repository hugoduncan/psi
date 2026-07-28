(ns psi.rpc.session.command-pickers-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.rpc.session.command-pickers :as command-pickers]))

(defn- capture-model-picker-items
  "Invoke the /model picker command with a capturing emit! and return the
  picker item id pairs [provider id] offered at the picker boundary."
  []
  (let [captured (atom nil)
        emit!    (fn [_event-type payload] (reset! captured payload))]
    (command-pickers/handle-picker-command! "req-1" emit! "/model")
    (->> (get-in @captured [:ui/action :ui/items])
         (mapv :ui.item/id)
         (into #{}))))

(deftest model-picker-offers-gpt-5-6-codex-variants-test
  ;; Surface-level acceptance coverage: the /model picker (one of the named
  ;; model-selection surfaces) must actually offer each OAuth/Codex GPT-5.6
  ;; variant. The picker derives unconditionally from the shared catalog join
  ;; point, so this also guards against a future picker filter dropping them.
  (let [offered (capture-model-picker-items)]
    (doseq [id ["gpt-5.6-sol" "gpt-5.6-terra" "gpt-5.6-luna"]]
      (is (contains? offered ["openai" id])
          (str "/model picker should offer " id)))))
