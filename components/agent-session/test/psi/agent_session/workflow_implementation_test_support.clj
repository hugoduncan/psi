(ns psi.agent-session.workflow-implementation-test-support
  (:require
   [psi.agent-session.workflow.core :as workflow-core]
   [psi.deterministic-operation-registry.registry :as operation-registry]
   [psi.workflow-loader.compiler :as workflow-compiler]
   [psi.workflow-loader.parser :as workflow-parser]
   [psi.workflow-registry.registry :as workflow-registry]
   [psi.workflow-runtime.core :as workflow-runtime]))

(defn register-routing-ops!
  "Register the production workflow routing operations in a test context."
  [ctx]
  (workflow-core/init {:register-operation (fn [operation]
                                             (operation-registry/register-operation-in!
                                              (:deterministic-operation-registry ctx)
                                              operation))
                       :register-tool (fn [_] nil)
                       :register-command (fn [& _] nil)
                       :on (fn [& _] nil)
                       :query (fn [& _] nil)
                       :query-session (fn [& _] nil)
                       :mutate (fn [& _] nil)
                       :mutate-session (fn [& _] nil)}))

(defn session-step
  "Build a session step with one literal template contribution."
  [name prompt]
  {:name name
   :type :session
   :contributions [{:type :template :text prompt}]})

(defn terminal-session-step
  "Build a session step that terminates through constant DONE routing."
  [name prompt]
  (assoc (session-step name prompt)
         :judge {:type :invoke
                 :operation "workflow/constant-routing"
                 :args {:route "DONE"}}
         :on {"DONE" {:goto :done}}))

(defn child-definition
  "Build a one-session child workflow definition."
  [name prompt]
  {:definition-id name
   :name name
   :steps [(session-step "run" prompt)]})

(defn checked-in-workflow-definition
  "Compile a checked-in EDN workflow from `worktree` as production does."
  [worktree workflow-name]
  (let [path (str worktree "/.psi/workflows/" workflow-name ".edn")
        parsed (workflow-parser/parse-edn-workflow-file (slurp path))
        {:keys [definition error]} (workflow-compiler/compile-workflow-file
                                    (assoc parsed :source-path path))]
    (when error
      (throw (ex-info (str "Checked-in " workflow-name " definition did not compile")
                      {:error error})))
    definition))

(defn register-definitions!
  "Register workflow definitions in order in a test context."
  [ctx definitions]
  (swap! (:state* ctx)
         (fn [state]
           (reduce (fn [next-state definition]
                     (first (workflow-registry/register-definition next-state definition)))
                   state
                   definitions))))

(defn create-run!
  "Create a workflow run in a test context."
  [ctx definition run-id workflow-input]
  (swap! (:state* ctx)
         (fn [state]
           (first (workflow-runtime/create-run state
                                               {:definition definition
                                                :run-id run-id
                                                :workflow-input workflow-input})))))
