(ns psi.agent-session.resolvers.discovery
  "Pathom3 resolvers for prompt template, skill, tool, and session introspection."
  (:require
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.prompt-assets.prompt-templates :as pt]
   [psi.session-journal.store :as journal-store]
   [psi.agent-session.resolvers.support :as support]
   [psi.prompt-assets.skills :as skills]))

;; ── Prompt template introspection ────────────────────────

(pco/defresolver prompt-template-summary-resolver
  "Resolve prompt template summary: count, names, per-source grouping."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.prompt-template/summary
                 :psi.prompt-template/names
                 :psi.prompt-template/count
                 :psi.prompt-template/by-source]}
  (let [templates (:prompt-templates (support/session-data agent-session-ctx session-id))]
    {:psi.prompt-template/summary   (pt/template-summary templates)
     :psi.prompt-template/names     (pt/template-names templates)
     :psi.prompt-template/count     (count templates)
     :psi.prompt-template/by-source (pt/templates-by-source templates)}))

(pco/defresolver prompt-template-detail-resolver
  "Resolve a single enriched prompt template by name.
   Seed input: {:psi.prompt-template/name \"template-name\"}"
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id psi.prompt-template/name]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id :psi.prompt-template/name]
   ::pco/output [:psi.prompt-template/detail]}
  (let [templates (:prompt-templates (support/session-data agent-session-ctx session-id))
        tpl       (pt/find-template templates name)]
    {:psi.prompt-template/detail
     (when tpl (pt/enrich-template tpl))}))

;; ── Skill introspection ──────────────────────────────────

(pco/defresolver skill-summary-resolver
  "Resolve skill summary: count, visible/hidden counts, per-source grouping."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.skill/summary
                 :psi.skill/names
                 :psi.skill/count
                 :psi.skill/visible-count
                 :psi.skill/hidden-count
                 :psi.skill/by-source]}
  (let [all-skills (:skills (support/session-data agent-session-ctx session-id))
        summary    (skills/skill-summary all-skills)]
    {:psi.skill/summary       summary
     :psi.skill/names         (skills/skill-names all-skills)
     :psi.skill/count         (:skill-count summary)
     :psi.skill/visible-count (:visible-count summary)
     :psi.skill/hidden-count  (:hidden-count summary)
     :psi.skill/by-source     (skills/skills-by-source all-skills)}))

(pco/defresolver skill-detail-resolver
  "Resolve a single enriched skill by name.
   Seed input: {:psi.skill/name \"skill-name\"}"
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id psi.skill/name]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id :psi.skill/name]
   ::pco/output [:psi.skill/detail]}
  (let [all-skills (:skills (support/session-data agent-session-ctx session-id))
        skill      (skills/find-skill all-skills name)]
    {:psi.skill/detail
     (when skill (skills/enrich-skill skill))}))

;; ── Tool introspection ───────────────────────────────────

(pco/defresolver tool-summary-resolver
  "Resolve active tool summary: count and names."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.tool/summary
                 :psi.tool/names
                 :psi.tool/count]}
  (let [tools (:tools (support/agent-data agent-session-ctx session-id))
        names (mapv :name tools)]
    {:psi.tool/summary {:tool-count (count tools)
                        :tools      (mapv #(select-keys % [:name :label :description]) tools)}
     :psi.tool/names   names
     :psi.tool/count   (count tools)}))

(pco/defresolver tool-detail-resolver
  "Resolve a single active tool by name.
   Seed input: {:psi.tool/name \"tool-name\"}"
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id psi.tool/name]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id :psi.tool/name]
   ::pco/output [:psi.tool/detail]}
  (let [tools (:tools (support/agent-data agent-session-ctx session-id))
        tool  (first (filter #(= (:name %) name) tools))]
    {:psi.tool/detail tool}))

;; ── Resolver introspection ───────────────────────────────

(pco/defresolver resolver-detail-resolver
  "Resolve I/O detail for a single resolver by sym.
   Pathom provides :psi.graph/resolver-index (from query-graph-bridge) and
   :psi.resolver/sym (from the seed entity).
   Seed input: {:psi.resolver/sym 'psi.agent-session.resolvers.session/agent-session-identity}
   Returns nil attrs when sym is not found."
  [{:keys [psi.graph/resolver-index psi.resolver/sym]}]
  {::pco/input  [:psi.graph/resolver-index :psi.resolver/sym]
   ::pco/output [:psi.resolver/input :psi.resolver/output]}
  (let [entry (first (filter #(= sym (:psi.resolver/sym %)) resolver-index))]
    {:psi.resolver/input  (:psi.resolver/input entry)
     :psi.resolver/output (:psi.resolver/output entry)}))

;; ── Session listing ─────────────────────────────────────

(defn- session-info->eql
  "Convert a SessionInfo map to :psi.session-info/* attributes."
  [info]
  (let [worktree-path (:worktree-path info)]
    {:psi.session-info/path                (:path info)
     :psi.session-info/id                  (:id info)
     :psi.session-info/worktree-path       worktree-path
     :psi.session-info/name                (:name info)
     :psi.session-info/parent-session-id   (:parent-session-id info)
     :psi.session-info/parent-session-path (:parent-session-path info)
     :psi.session-info/created             (:created info)
     :psi.session-info/modified            (:modified info)
     :psi.session-info/message-count       (:message-count info)
     :psi.session-info/first-message       (:first-message info)
     :psi.session-info/all-messages-text   (:all-messages-text info)}))

(pco/defresolver session-list-resolver
  "Resolve all sessions for the current session's worktree path, sorted by modified desc."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [{:psi.session/list
                  [:psi.session-info/path
                   :psi.session-info/id
                   :psi.session-info/worktree-path
                   :psi.session-info/name
                   :psi.session-info/parent-session-id
                   :psi.session-info/parent-session-path
                   :psi.session-info/created
                   :psi.session-info/modified
                   :psi.session-info/message-count
                   :psi.session-info/first-message
                   :psi.session-info/all-messages-text]}]}
  {:psi.session/list
   (mapv session-info->eql
         (journal-store/list-sessions
          (journal-store/session-dir-for
           (support/session-worktree-path agent-session-ctx session-id))))})

(pco/defresolver session-list-all-resolver
  "Resolve all sessions across all project directories, sorted by modified desc."
  [{_ctx :psi/agent-session-ctx}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [{:psi.session/list-all
                  [:psi.session-info/path
                   :psi.session-info/id
                   :psi.session-info/worktree-path
                   :psi.session-info/name
                   :psi.session-info/parent-session-id
                   :psi.session-info/parent-session-path
                   :psi.session-info/created
                   :psi.session-info/modified
                   :psi.session-info/message-count
                   :psi.session-info/first-message
                   :psi.session-info/all-messages-text]}]}
  {:psi.session/list-all
   (mapv session-info->eql (journal-store/list-all-sessions))})

;; ── Resolver collection ─────────────────────────────────

(def resolvers
  [prompt-template-summary-resolver
   prompt-template-detail-resolver
   skill-summary-resolver
   skill-detail-resolver
   tool-summary-resolver
   tool-detail-resolver
   resolver-detail-resolver
   session-list-resolver
   session-list-all-resolver])
