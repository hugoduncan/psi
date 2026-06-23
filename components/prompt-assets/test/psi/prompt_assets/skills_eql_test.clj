(ns psi.prompt-assets.skills-eql-test
  "Tests for skill EQL introspection and system-prompt introspectability."
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.agent-session.resolvers :as resolvers]
   [psi.agent-session.core :as session-core]
   [com.wsscode.pathom3.connect.indexes :as pci]
   [com.wsscode.pathom3.interface.eql :as p.eql]))

(deftest skill-eql-introspection-test
  (let [all-skills [{:name "zeta"
                     :description "Zeta skill"
                     :file-path "/zeta/SKILL.md"
                     :base-dir "/zeta"
                     :source :user
                     :disable-model-invocation false}
                    {:name "gamma"
                     :description "Gamma skill"
                     :file-path "/gamma/SKILL.md"
                     :base-dir "/gamma"
                     :source :project
                     :disable-model-invocation true}
                    {:name "alpha"
                     :description "Alpha skill"
                     :file-path "/alpha/SKILL.md"
                     :base-dir "/alpha"
                     :source :user
                     :disable-model-invocation false}
                    {:name "beta"
                     :description "Beta skill"
                     :file-path "/beta/SKILL.md"
                     :base-dir "/beta"
                     :source :project
                     :disable-model-invocation true}]
        ctx     (session-core/create-context
                 {:session-defaults {:skills all-skills}})
        sd      (session-core/new-session-in! ctx nil {})
        session-id (:session-id sd)]

    (testing "query skill count via EQL"
      (let [result (session-core/query-in ctx session-id [:psi.skill/count])]
        (is (= 4 (:psi.skill/count result)))))

    (testing "query visible/hidden counts via EQL"
      (let [result (session-core/query-in ctx session-id [:psi.skill/visible-count
                                                          :psi.skill/hidden-count])]
        (is (= 2 (:psi.skill/visible-count result)))
        (is (= 2 (:psi.skill/hidden-count result)))))

    (testing "query skill names via EQL"
      (let [result (session-core/query-in ctx session-id [:psi.skill/names])]
        (is (= ["alpha" "beta" "gamma" "zeta"] (:psi.skill/names result)))))

    (testing "query skill summary via EQL"
      (let [result  (session-core/query-in ctx session-id [:psi.skill/summary])
            summary (:psi.skill/summary result)]
        (is (= 4 (:skill-count summary)))
        (is (= 2 (:visible-count summary)))
        (is (= 2 (:hidden-count summary)))))

    (testing "query skills by source via EQL"
      (let [result  (session-core/query-in ctx session-id [:psi.skill/by-source])
            grouped (:psi.skill/by-source result)]
        (is (= ["alpha" "zeta"] (mapv :name (:user grouped))))
        (is (= ["beta" "gamma"] (mapv :name (:project grouped))))))))

(deftest skill-detail-eql-test
  (let [all-skills [{:name "alpha"
                     :description "Alpha skill"
                     :file-path "/alpha/SKILL.md"
                     :base-dir "/alpha"
                     :source :user
                     :disable-model-invocation false}]
        ctx     (session-core/create-context
                 {:session-defaults {:skills all-skills}})
        sd      (session-core/new-session-in! ctx nil {})
        env     (pci/register resolvers/all-resolvers)
        result  (p.eql/process env
                               {:psi/agent-session-ctx ctx
                                :psi.agent-session/session-id (:session-id sd)
                                :psi.skill/name "alpha"}
                               [:psi.skill/detail])
        detail (:psi.skill/detail result)]

    (testing "detail includes enriched fields"
      (is (= "alpha" (:name detail)))
      (is (= "Alpha skill" (:description detail)))
      (is (true? (:is-available-to-model detail))))

    (testing "detail for unknown skill is nil"
      (let [r (p.eql/process env
                             {:psi/agent-session-ctx ctx
                              :psi.agent-session/session-id (:session-id sd)
                              :psi.skill/name "unknown"}
                             [:psi.skill/detail])]
        (is (nil? (:psi.skill/detail r)))))))

;; ============================================================
;; System prompt introspection
;; ============================================================

(deftest system-prompt-introspectable-test
  (testing "system prompt stored in session data is queryable"
    (let [ctx     (session-core/create-context
                   {:session-defaults {:system-prompt "Test system prompt with skills"}})
          sd      (session-core/new-session-in! ctx nil {})
          result  (session-core/query-in ctx (:session-id sd) [:psi.agent-session/system-prompt])]
      (is (= "Test system prompt with skills"
             (:psi.agent-session/system-prompt result))))))

;; ============================================================
;; Nested skill discovery (subdirectories)
;; ============================================================

