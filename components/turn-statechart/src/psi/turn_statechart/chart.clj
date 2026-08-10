(ns psi.turn-statechart.chart
  "Turn-statechart chart definition only."
  (:require
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :as ele]))

(defn- dispatch! [data action-key]
  (when-let [af (:actions-fn data)]
    (af action-key data)))

(def turn-chart
  "Canonical per-turn stream-assembly statechart.

   All accumulation transitions use self-transitions (same target) rather than
   targetless transitions for simple-env compatibility."
  (chart/statechart {:id :turn-streaming}

                    (ele/state {:id :idle}
                               (ele/transition {:event  :turn/start
                                                :target :text-accumulating}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-stream-start))}))
                               ;; Accept terminal events from every state. Direct
                               ;; consumers may receive :done or :error before
                               ;; :start; both must deliver completion instead of
                               ;; waiting for the stream idle timeout.
                               (ele/transition {:event  :turn/done
                                                :target :done}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-done))}))
                               (ele/transition {:event  :turn/error
                                                :target :error}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-error))})))

                    (ele/state {:id :text-accumulating}
                               (ele/transition {:event  :turn/text-delta
                                                :target :text-accumulating}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-text-delta))}))
                               (ele/transition {:event  :turn/toolcall-start
                                                :target :tool-accumulating}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-toolcall-start))}))
                               (ele/transition {:event  :turn/done
                                                :target :done}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-done))}))
                               (ele/transition {:event  :turn/error
                                                :target :error}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-error))})))

                    (ele/state {:id :tool-accumulating}
                               (ele/transition {:event  :turn/toolcall-delta
                                                :target :tool-accumulating}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-toolcall-delta))}))
                               (ele/transition {:event  :turn/toolcall-end
                                                :target :text-accumulating}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-toolcall-end))}))
                               (ele/transition {:event  :turn/toolcall-start
                                                :target :tool-accumulating}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-toolcall-start))}))
                               (ele/transition {:event  :turn/text-delta
                                                :target :text-accumulating}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-text-delta))}))
                               (ele/transition {:event  :turn/done
                                                :target :done}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-done))}))
                               (ele/transition {:event  :turn/error
                                                :target :error}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-error))})))

                    (ele/state {:id :done}
                               (ele/transition {:event  :turn/reset
                                                :target :idle}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-reset))})))

                    (ele/state {:id :error}
                               (ele/transition {:event  :turn/reset
                                                :target :idle}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-reset))})))))
