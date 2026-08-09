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
                               ;; Review 51: terminal transitions from the
                               ;; initial state. :idle previously accepted only
                               ;; :turn/start — :turn/error and :turn/done were
                               ;; silently DROPPED there (enabled transitions
                               ;; => #{}), so a direct create-turn-context
                               ;; consumer feeding a provider :error/:done as
                               ;; the FIRST event got a silent drop, done-p
                               ;; never delivered, and only the 20-minute
                               ;; llm-stream-idle-timeout-ms ended the turn
                               ;; (whose own :turn/error send was dropped too).
                               ;; Not reachable through the live-turn path
                               ;; (create-live-turn-context sends the
                               ;; turn-level :turn/start first) but a latent
                               ;; structural gap in the "exactly one terminal
                               ;; event per turn" invariant. Mirror the
                               ;; :text-accumulating / :tool-accumulating
                               ;; terminal transitions so terminal events are
                               ;; accepted from ANY state.
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
