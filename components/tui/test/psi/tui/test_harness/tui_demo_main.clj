(ns psi.tui.test-harness.tui-demo-main
  "Standalone TUI demo entry-point for tmux integration tests.

   Starts the TUI with a scripted run-agent-fn! that injects pre-defined
   event sequences from the PSI_TUI_DEMO_SCRIPT env var.  No live LLM is
   needed.

   Script format (EDN string):
     A vector of scenario steps, each step being a map:
       {:trigger  <string to match against submitted text, or :any>
        :delay-ms <optional ms to sleep before first event, default 0>
        :events   <vector of event maps to inject, in order>
        :done     <optional result map for the :done event, default plain text>}

   Each event map is injected onto the queue as-is (must include :type and
   :event-kind for agent events, or :kind :done/:error for terminal events).

   Example env var value (JSON-escaped for shell):
     PSI_TUI_DEMO_SCRIPT='[{:trigger :any
                             :delay-ms 200
                             :events [{:type :agent-event :event-kind :thinking-delta
                                       :content-index 0 :text \"Thinking...\"}
                                      {:type :agent-event :event-kind :tool-call-assembly
                                       :phase :end :content-index 1
                                       :tool-id \"t1\" :tool-name \"read\"
                                       :arguments \"{\\\"path\\\":\\\"README.md\\\"}\"}]
                             :done {:role \"assistant\"
                                    :content [{:type :text :text \"Done.\"}]}}]'

   When PSI_TUI_DEMO_SCRIPT is absent or empty the TUI starts normally with a
   no-op agent (useful for basic boot/quit tests)."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [psi.tui.app :as app])
  (:import
   [java.util.concurrent LinkedBlockingQueue]))

(defn- read-script
  []
  (when-let [s (System/getenv "PSI_TUI_DEMO_SCRIPT")]
    (when-not (str/blank? s)
      (edn/read-string s))))

(defn- noop-agent-fn
  "Agent fn used when no script is configured."
  [_text ^LinkedBlockingQueue queue]
  (.put queue {:kind :done
               :result {:role "assistant"
                        :content [{:type :text :text "(no script configured)"}]}}))

(defn- scripted-agent-fn
  "Build a run-agent-fn! that plays back the first matching script step."
  [steps*]
  (fn [text ^LinkedBlockingQueue queue]
    (let [steps @steps*
          step  (or (first (filter (fn [s]
                                     (let [t (:trigger s)]
                                       (or (= t :any)
                                           (and (string? t) (str/includes? text t)))))
                                   steps))
                    ;; fallback: plain done
                    {:events [] :done {:role "assistant"
                                       :content [{:type :text :text "(no matching step)"}]}})]
      (if (and (string? text)
               (contains? #{"/quit" "/exit"} (str/trim text)))
        (.put queue {:kind :done
                     :result {:role "assistant"
                              :content [{:type :text :text "(quit)"}]}})
        (do
          ;; consume the step so subsequent submits advance through the script
          (swap! steps* (fn [ss] (if (seq ss) (rest ss) ss)))
          (future
            (when-let [d (:delay-ms step)]
              (Thread/sleep (long d)))
            (doseq [ev (:events step)]
              (.put queue ev)
              (Thread/sleep 40))
            (.put queue {:kind :done
                         :result (or (:done step)
                                     {:role "assistant"
                                      :content [{:type :text :text "(step complete)"}]})})))))))

(defn- dispatch-fn
  [text]
  (case (some-> text str/trim)
    ("/quit" "/exit") {:type :quit}
    "/resume"          {:type :resume}
    "/tree"            {:type :tree-open}
    nil))

(defn -main
  [& _args]
  (let [script     (read-script)
        agent-fn   (if (seq script)
                     (scripted-agent-fn (atom (vec script)))
                     noop-agent-fn)
        model-name (or (System/getenv "PSI_TUI_DEMO_MODEL") "demo-model")]
    (app/start! model-name agent-fn {:alt-screen false
                                     :dispatch-fn dispatch-fn})))
