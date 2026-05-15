(ns extensions.hello-ext
  "Example extension used in docs/tests.

   Demonstrates:
   - command and event-handler registration via `(:mutate api)`
   - extension tool registration
   - programmatic tool chaining via `psi.extension/run-tool-plan`"
  (:require
   [clojure.string :as str]
   [psi.tool-runtime.call-summary :as call-summary]))

(defn- run-tool-plan!
  "Canonical helper for programmatic tool composition from an extension.

   `steps` is a vector of plan step maps, where later steps can reference
   prior results via [:from <step-id> <path...>] references."
  [mutate-fn steps]
  (mutate-fn 'psi.extension/run-tool-plan
             {:steps          steps
              :stop-on-error? true}))

(defn- upper-tool
  []
  {:name           "hello-upper"
   :label          "Hello Upper"
   :description    "Upper-case a text value"
   :parameters     (pr-str {:type       "object"
                            :properties {"text" {:type "string"}}
                            :required   ["text"]})
   :format-request (call-summary/text-key-format-request "hello-upper" "text")
   :execute        (fn [args _opts]
                     {:content  (str/upper-case (str (get args "text" "")))
                      :is-error false})})

(defn- wrap-tool
  []
  {:name           "hello-wrap"
   :label          "Hello Wrap"
   :description    "Wrap text with prefix/suffix"
   :parameters     (pr-str {:type       "object"
                            :properties {"text"   {:type "string"}
                                         "prefix" {:type "string"}
                                         "suffix" {:type "string"}}
                            :required   ["text"]})
   :format-request (call-summary/text-key-format-request "hello-wrap" "text")
   :execute        (fn [args _opts]
                     (let [text   (str (get args "text" ""))
                           prefix (str (get args "prefix" ""))
                           suffix (str (get args "suffix" ""))]
                       {:content  (str prefix text suffix)
                        :is-error false}))})

(defn init [api]
  (let [mutate-fn (:mutate api)
        log!      (:log api)]
    ;; Register a slash command
    (mutate-fn 'psi.extension/register-command
               {:name "hello"
                :opts {:description "Say hello"
                       :handler     (fn [_args] (log! "Hello from extension!"))}})

    ;; Listen to events
    (mutate-fn 'psi.extension/register-handler
               {:event-name "session_switch"
                :handler-fn (fn [ev] (log! (str "Session switched: " (:reason ev))))})

    ;; Register two tiny tools so we can demonstrate a chained tool plan.
    (mutate-fn 'psi.extension/register-tool {:tool (upper-tool)})
    (mutate-fn 'psi.extension/register-tool {:tool (wrap-tool)})

    ;; Demonstrate mutation-driven tool chaining from extension code.
    (mutate-fn 'psi.extension/register-command
               {:name "hello-plan"
                :opts {:description "Run a demo tool plan (hello-upper -> hello-wrap)"
                       :handler     (fn [_args]
                                      (let [result (run-tool-plan!
                                                    mutate-fn
                                                    [{:id :s1
                                                      :tool "hello-upper"
                                                      :args {:text "hello from plan"}}
                                                     {:id :s2
                                                      :tool "hello-wrap"
                                                      :args {:text   [:from :s1 :content]
                                                             :prefix "["
                                                             :suffix "]"}}])
                                            ok?    (:psi.extension.tool-plan/succeeded? result)
                                            final  (get-in result
                                                           [:psi.extension.tool-plan/result-by-id
                                                            :s2
                                                            :content])]
                                        (if ok?
                                          (log! (str "hello-plan result: " final))
                                          (log! (str "hello-plan failed: "
                                                     (:psi.extension.tool-plan/error result))))))}})

    ;; Show a status line in the TUI footer
    #_(when-let [ui (:ui api)]
        ((:set-status ui) "hello-ext loaded"))))
