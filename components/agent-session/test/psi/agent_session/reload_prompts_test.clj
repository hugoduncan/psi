(ns psi.agent-session.reload-prompts-test
  "Tests for the :session/reload-prompts dispatch handler and the
   reload-prompts-in! core entry fn: replace semantics, return shape, and
   no system-prompt refresh effect."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch]
   [psi.agent-session.dispatch-handlers.session-mutations]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.tools :as tools]
   [psi.session-state.state :as ss]))

(defn- delete-tree! [path]
  (when path
    (doseq [f (reverse (file-seq (io/file path)))]
      (.delete ^java.io.File f))))

(defn- worktree-with-prompts!
  "Create a temp worktree dir with a `.psi/prompts` dir and write each
   {name -> body} entry as `<name>.md`. Returns the worktree path."
  [name->body]
  (let [wt      (str (java.nio.file.Files/createTempDirectory
                      "psi-reload-prompts-test-"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        prompts (io/file wt ".psi/prompts")]
    (.mkdirs prompts)
    (doseq [[name body] name->body]
      (spit (io/file prompts (str name ".md")) body))
    wt))

(defn- worktree-without-prompts!
  "Create a temp worktree dir with no `.psi/prompts` dir at all. Returns the
   worktree path."
  []
  (str (java.nio.file.Files/createTempDirectory
        "psi-reload-prompts-test-"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- session-at-worktree
  "Create a [ctx session-id] whose worktree path is `wt`."
  ([wt] (session-at-worktree wt {}))
  ([wt ctx-opts]
   (let [ctx (session/create-context
              (merge (test-support/safe-context-opts
                      {:cwd wt :persist? false})
                     ctx-opts))
         sd  (session/new-session-in! ctx nil {:worktree-path wt})]
     [ctx (:session-id sd)])))

(defn- seed-stale!
  "Seed a single stale template so a subsequent reload's replace semantics
   (not append) are observable."
  [ctx session-id]
  (ss/update-state-value-in! ctx (ss/session-data-path session-id)
                             assoc :prompt-templates
                             [{:name "stale" :content "old"}]))

(defn- template-names
  "Set of :name values of the session's current :prompt-templates."
  [ctx session-id]
  (set (map :name (:prompt-templates (ss/get-session-data-in ctx session-id)))))

(defn- template-by-name
  [ctx session-id name]
  (->> (ss/get-session-data-in ctx session-id)
       :prompt-templates
       (filter #(= name (:name %)))
       first))

(defn- invoke-reload-mutation
  "Invoke the psi.extension/reload-prompts mutation through a live psi-tool
   `mutate` action and return the parsed psi-tool result map."
  [ctx session-id]
  (let [tool   (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
        result ((:execute tool) {"action" "mutate"
                                 "mutation" "psi.extension/reload-prompts"
                                 "params" {"session-id" session-id}})]
    {:result result
     :parsed (read-string (:content result))}))

(deftest reload-prompts-handler-replaces-templates-test
  (testing "dispatch :session/reload-prompts replaces :prompt-templates with the freshly discovered set"
    (let [wt (worktree-with-prompts! {"foo" "foo body v1"
                                      "bar" "bar body"})]
      (try
        (let [[ctx session-id] (session-at-worktree wt)]
          ;; Seed a stale template that should be replaced (not appended to).
          (seed-stale! ctx session-id)
          (let [result (session/dispatch-in! ctx :session/reload-prompts
                                             {:session-id session-id})]
            ;; AC7: replace, not append — "stale" is gone, discovered set present.
            (is (= #{"foo" "bar"} (template-names ctx session-id)))
            (is (= "foo body v1" (:content (template-by-name ctx session-id "foo"))))
            ;; Return shape carries :reloaded?/:count/:worktree.
            (is (true? (:reloaded? result)))
            (is (= 2 (:count result)))
            (is (= wt (:worktree result)))))
        (finally (delete-tree! wt))))))

(deftest reload-prompts-does-not-refresh-system-prompt-test
  (testing "dispatching :session/reload-prompts never triggers a system-prompt refresh (observable: the refresh callback is the boundary a :runtime/refresh-system-prompt effect would cross)"
    (let [wt (worktree-with-prompts! {"foo" "foo body"})]
      (try
        (let [refreshed? (atom false)
              [ctx0 session-id] (session-at-worktree wt)
              ;; The :runtime/refresh-system-prompt effect is executed through
              ;; ctx's :refresh-system-prompt-fn. Rebinding it to a recorder
              ;; lets us assert the absence of the refresh observably, without
              ;; reaching into the handler-registry pure-result internals.
              ctx        (assoc ctx0 :refresh-system-prompt-fn
                                (fn [& _] (reset! refreshed? true)))]
          ;; Positive control: an event known to emit
          ;; :runtime/refresh-system-prompt must flip the recorder, proving the
          ;; rebound callback is live in this ctx and the effect path runs — so
          ;; the absence assertion below cannot pass vacuously.
          (session/dispatch-in! ctx :session/set-prompt-component-selection
                                {:session-id session-id :selection {}})
          (is (true? @refreshed?)
              "recorder fires when a refresh-emitting event is dispatched")
          ;; Reset and assert reload leaves the recorder untouched.
          (reset! refreshed? false)
          (let [result (session/dispatch-in! ctx :session/reload-prompts
                                             {:session-id session-id})]
            (is (true? (:reloaded? result)))
            ;; No system-prompt refresh side effect crossed the boundary.
            (is (false? @refreshed?))))
        (finally (delete-tree! wt))))))

(deftest reload-prompts-in-core-fn-surfaces-return-test
  (testing "reload-prompts-in! returns the handler :return map (not an effect result)"
    (let [wt (worktree-with-prompts! {"foo" "foo body" "bar" "bar body"})]
      (try
        (let [[ctx session-id] (session-at-worktree wt)
              result (session/reload-prompts-in! ctx session-id)]
          (is (true? (:reloaded? result)))
          (is (= 2 (:count result)))
          (is (= wt (:worktree result))))
        (finally (delete-tree! wt))))))

(deftest reload-prompts-mutation-output-and-replace-test
  (testing "psi.extension/reload-prompts mutation replaces templates via dispatch and returns output keys"
    (let [wt (worktree-with-prompts! {"foo" "foo body" "bar" "bar body"})]
      (try
        (let [[ctx session-id] (session-at-worktree wt {:mutations mutations/all-mutations})
              {:keys [result parsed]} (invoke-reload-mutation ctx session-id)]
          (is (false? (:is-error result)))
          (is (= :ok (:psi-tool/overall-status parsed)))
          (is (= {:psi.prompt-template/reloaded? true
                  :psi.prompt-template/count     2}
                 (:psi-tool/result parsed)))
          ;; :worktree is NOT surfaced by the mutation.
          (is (not (contains? (:psi-tool/result parsed) :worktree)))
          ;; Replace semantics applied through dispatch.
          (is (= #{"foo" "bar"} (template-names ctx session-id))))
        (finally (delete-tree! wt))))))

(deftest reload-prompts-handler-empty-dir-replaces-with-empty-test
  (testing "reloading a worktree whose .psi/prompts is absent or empty replaces :prompt-templates with [] (T1 boundary, handler)"
    (doseq [[label wt] [["absent" (worktree-without-prompts!)]
                        ["empty"  (worktree-with-prompts! {})]]]
      (testing label
        (try
          (let [[ctx session-id] (session-at-worktree wt)]
            ;; Seed a stale template that must be cleared by the replace.
            (seed-stale! ctx session-id)
            (let [result    (session/dispatch-in! ctx :session/reload-prompts
                                                  {:session-id session-id})
                  templates (:prompt-templates (ss/get-session-data-in ctx session-id))]
              (is (true? (:reloaded? result)))
              (is (= 0 (:count result)))
              ;; Replace, not append — stale template gone, empty vector left.
              (is (= [] templates))))
          (finally (delete-tree! wt)))))))

(deftest reload-prompts-mutation-empty-dir-count-zero-test
  (testing "the reload-prompts mutation surfaces :count 0 for an empty/absent prompts dir and replaces templates with [] (T1 boundary, mutation (or count 0) path)"
    (doseq [[label wt] [["absent" (worktree-without-prompts!)]
                        ["empty"  (worktree-with-prompts! {})]]]
      (testing label
        (try
          (let [[ctx session-id] (session-at-worktree wt {:mutations mutations/all-mutations})]
            ;; Seed a stale template that must be cleared by the replace.
            (seed-stale! ctx session-id)
            (let [{:keys [result parsed]} (invoke-reload-mutation ctx session-id)]
              (is (false? (:is-error result)))
              (is (= :ok (:psi-tool/overall-status parsed)))
              (is (= {:psi.prompt-template/reloaded? true
                      :psi.prompt-template/count     0}
                     (:psi-tool/result parsed)))
              ;; Replace semantics applied through dispatch — stale gone.
              (is (= [] (:prompt-templates (ss/get-session-data-in ctx session-id))))))
          (finally (delete-tree! wt)))))))

(deftest reload-prompts-end-to-end-edit-add-delete-test
  (testing "edit/add/delete .md against the worktree, then reload reflects each change (AC1-AC3, AC6)"
    (let [wt (worktree-with-prompts! {"foo" "foo body v1"
                                      "baz" "baz body"})
          prompts (io/file wt ".psi/prompts")]
      (try
        (let [[ctx session-id] (session-at-worktree wt)]
          ;; Initial reload establishes the pre-edit baseline. (Full
          ;; discover→replace + return-shape proofs live in the handler/core-fn
          ;; tests; here we only need a baseline to delta against.)
          (session/reload-prompts-in! ctx session-id)
          (is (= #{"foo" "baz"} (template-names ctx session-id)))
          ;; AC1: edit foo body, AC2: add bar, AC3: delete baz.
          (spit (io/file prompts "foo.md") "foo body v2")
          (spit (io/file prompts "bar.md") "bar body")
          (.delete (io/file prompts "baz.md"))
          (session/reload-prompts-in! ctx session-id)
          ;; AC1: foo expands with new content.
          (is (= "foo body v2" (:content (template-by-name ctx session-id "foo"))))
          ;; AC2: bar is now discoverable.
          (is (some? (template-by-name ctx session-id "bar")))
          ;; AC3: baz removed.
          (is (nil? (template-by-name ctx session-id "baz"))))
        (finally (delete-tree! wt))))))

(deftest reload-prompts-mutation-psi-tool-visible-test
  (testing "psi.extension/reload-prompts is in the registered mutation set (psi-tool visible)"
    (letfn [(op-name [m]
              (or (some-> m :config :com.wsscode.pathom3.connect.operation/op-name)
                  (some-> m meta :com.wsscode.pathom3.connect.operation/op-name)))]
      (is (some #(= 'psi.extension/reload-prompts (op-name %))
                mutations/all-mutations)))))
