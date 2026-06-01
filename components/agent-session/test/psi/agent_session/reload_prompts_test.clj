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
   [psi.session-state.state :as ss]
   [psi.state-kernel.dispatch :as kernel]))

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

(deftest reload-prompts-handler-replaces-templates-test
  (testing "dispatch :session/reload-prompts replaces :prompt-templates with the freshly discovered set"
    (let [wt (worktree-with-prompts! {"foo" "foo body v1"
                                      "bar" "bar body"})]
      (try
        (let [[ctx session-id] (session-at-worktree wt)]
          ;; Seed a stale template that should be replaced (not appended to).
          (ss/update-state-value-in! ctx (ss/session-data-path session-id)
                                     assoc :prompt-templates
                                     [{:name "stale" :content "old"}])
          (let [result (session/dispatch-in! ctx :session/reload-prompts
                                             {:session-id session-id})
                templates (:prompt-templates (ss/get-session-data-in ctx session-id))
                names (set (map :name templates))]
            ;; AC7: replace, not append — "stale" is gone, discovered set present.
            (is (= #{"foo" "bar"} names))
            (is (= "foo body v1" (:content (first (filter #(= "foo" (:name %)) templates)))))
            ;; Return shape carries :reloaded?/:count/:worktree.
            (is (true? (:reloaded? result)))
            (is (= 2 (:count result)))
            (is (= wt (:worktree result)))))
        (finally (delete-tree! wt))))))

(deftest reload-prompts-handler-emits-no-effects-test
  (testing "the :session/reload-prompts handler returns a pure result with no effects"
    (let [wt (worktree-with-prompts! {"foo" "foo body"})]
      (try
        (let [[ctx session-id] (session-at-worktree wt)
              handler (:fn (kernel/handler-entry :session/reload-prompts))
              result  (handler ctx {:session-id session-id})]
          ;; No :runtime/refresh-system-prompt (or any) effect.
          (is (nil? (:effects result)))
          (is (some? (:root-state-update result)))
          (is (true? (:reloaded? (:return result)))))
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
              tool   (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
              result ((:execute tool) {"action" "mutate"
                                       "mutation" "psi.extension/reload-prompts"
                                       "params" {"session-id" session-id}})
              parsed (read-string (:content result))]
          (is (false? (:is-error result)))
          (is (= :ok (:psi-tool/overall-status parsed)))
          (is (= {:psi.prompt-template/reloaded? true
                  :psi.prompt-template/count     2}
                 (:psi-tool/result parsed)))
          ;; :worktree is NOT surfaced by the mutation.
          (is (not (contains? (:psi-tool/result parsed) :worktree)))
          ;; Replace semantics applied through dispatch.
          (is (= #{"foo" "bar"}
                 (set (map :name (:prompt-templates (ss/get-session-data-in ctx session-id)))))))
        (finally (delete-tree! wt))))))

(defn- template-by-name
  [ctx session-id name]
  (->> (ss/get-session-data-in ctx session-id)
       :prompt-templates
       (filter #(= name (:name %)))
       first))

(deftest reload-prompts-end-to-end-edit-add-delete-test
  (testing "edit/add/delete .md against the worktree, then reload reflects each change (AC1-AC3, AC6)"
    (let [wt (worktree-with-prompts! {"foo" "foo body v1"
                                      "baz" "baz body"})
          prompts (io/file wt ".psi/prompts")]
      (try
        (let [[ctx session-id] (session-at-worktree wt)]
          ;; Initial reload picks up foo + baz from the worktree.
          (session/reload-prompts-in! ctx session-id)
          (is (= "foo body v1" (:content (template-by-name ctx session-id "foo"))))
          (is (some? (template-by-name ctx session-id "baz")))
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
