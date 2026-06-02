(ns psi.agent-session.mutations.prompts
  (:require
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.agent-session.core :as core]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extension-runtime :as ext-rt]))

(defn- prompt-contribution-mutation-view
  [c]
  {:psi.extension.prompt-contribution/id         (:id c)
   :psi.extension.prompt-contribution/ext-path   (:ext-path c)
   :psi.extension.prompt-contribution/section    (:section c)
   :psi.extension.prompt-contribution/content    (:content c)
   :psi.extension.prompt-contribution/priority   (:priority c)
   :psi.extension.prompt-contribution/enabled    (:enabled c)
   :psi.extension.prompt-contribution/created-at (:created-at c)
   :psi.extension.prompt-contribution/updated-at (:updated-at c)})

(pco/defmutation add-prompt-template
  "Add a prompt template to the session if its :name is not already present."
  [_ {:keys [psi/agent-session-ctx session-id template]}]
  {::pco/op-name 'psi.extension/add-prompt-template
   ::pco/params  [:psi/agent-session-ctx :session-id :template]
   ::pco/output  [:psi.prompt-template/added?
                  :psi.prompt-template/count]}
  (let [{:keys [added? count]}
        (dispatch/dispatch! agent-session-ctx
                            :session/register-prompt-template
                            {:session-id session-id :template template}
                            {:origin :mutations})]
    {:psi.prompt-template/added? (boolean added?)
     :psi.prompt-template/count  (or count 0)}))

(pco/defmutation reload-prompts
  "Re-discover prompt templates from disk for the session worktree and replace
   the session's registered templates."
  [_ {:keys [psi/agent-session-ctx session-id]}]
  {::pco/op-name 'psi.extension/reload-prompts
   ::pco/params  [:psi/agent-session-ctx :session-id]
   ::pco/output  [:psi.prompt-template/reloaded?
                  :psi.prompt-template/count]}
  (let [{:keys [reloaded? count]}
        (core/reload-prompts-in! agent-session-ctx session-id)]
    {:psi.prompt-template/reloaded? (boolean reloaded?)
     :psi.prompt-template/count     (or count 0)}))

(pco/defmutation add-skill
  "Add a skill to the session if its :name is not already present."
  [_ {:keys [psi/agent-session-ctx session-id skill]}]
  {::pco/op-name 'psi.extension/add-skill
   ::pco/params  [:psi/agent-session-ctx :session-id :skill]
   ::pco/output  [:psi.skill/added?
                  :psi.skill/count]}
  (let [{:keys [added? count]}
        (dispatch/dispatch! agent-session-ctx
                            :session/register-skill
                            {:session-id session-id :skill skill}
                            {:origin :mutations})]
    {:psi.skill/added? (boolean added?)
     :psi.skill/count  (or count 0)}))

(pco/defmutation register-prompt-contribution
  "Register or replace an extension-owned prompt contribution by id."
  [_ {:keys [psi/agent-session-ctx session-id ext-path id contribution]}]
  {::pco/op-name 'psi.extension/register-prompt-contribution
   ::pco/params  [:psi/agent-session-ctx :session-id :ext-path :id :contribution]
   ::pco/output  [:psi.extension/path
                  :psi.extension.prompt-contribution/id
                  :psi.extension.prompt-contribution/registered?
                  :psi.extension.prompt-contribution/count
                  :psi.extension.prompt-contribution/ext-path
                  :psi.extension.prompt-contribution/section
                  :psi.extension.prompt-contribution/content
                  :psi.extension.prompt-contribution/priority
                  :psi.extension.prompt-contribution/enabled
                  :psi.extension.prompt-contribution/created-at
                  :psi.extension.prompt-contribution/updated-at]}
  (let [{:keys [registered? contribution count]}
        (dispatch/dispatch! agent-session-ctx
                            :session/register-prompt-contribution
                            {:session-id session-id :ext-path ext-path :id id :contribution contribution}
                            {:origin :mutations})]
    (merge {:psi.extension/path                            (str ext-path)
            :psi.extension.prompt-contribution/id          (str id)
            :psi.extension.prompt-contribution/registered? registered?
            :psi.extension.prompt-contribution/count       count}
           (prompt-contribution-mutation-view contribution))))

(pco/defmutation update-prompt-contribution
  "Patch an existing extension-owned prompt contribution."
  [_ {:keys [psi/agent-session-ctx session-id ext-path id patch]}]
  {::pco/op-name 'psi.extension/update-prompt-contribution
   ::pco/params  [:psi/agent-session-ctx :session-id :ext-path :id :patch]
   ::pco/output  [:psi.extension/path
                  :psi.extension.prompt-contribution/id
                  :psi.extension.prompt-contribution/updated?
                  :psi.extension.prompt-contribution/count
                  :psi.extension.prompt-contribution/ext-path
                  :psi.extension.prompt-contribution/section
                  :psi.extension.prompt-contribution/content
                  :psi.extension.prompt-contribution/priority
                  :psi.extension.prompt-contribution/enabled
                  :psi.extension.prompt-contribution/created-at
                  :psi.extension.prompt-contribution/updated-at]}
  (let [{:keys [updated? contribution count]}
        (dispatch/dispatch! agent-session-ctx
                            :session/update-prompt-contribution
                            {:session-id session-id :ext-path ext-path :id id :patch patch}
                            {:origin :mutations})]
    (merge {:psi.extension/path                         (str ext-path)
            :psi.extension.prompt-contribution/id       (str id)
            :psi.extension.prompt-contribution/updated? updated?
            :psi.extension.prompt-contribution/count    count}
           (when contribution
             (prompt-contribution-mutation-view contribution)))))

(pco/defmutation unregister-prompt-contribution
  "Remove an extension-owned prompt contribution by id."
  [_ {:keys [psi/agent-session-ctx session-id ext-path id]}]
  {::pco/op-name 'psi.extension/unregister-prompt-contribution
   ::pco/params  [:psi/agent-session-ctx :session-id :ext-path :id]
   ::pco/output  [:psi.extension/path
                  :psi.extension.prompt-contribution/id
                  :psi.extension.prompt-contribution/removed?
                  :psi.extension.prompt-contribution/count]}
  (let [{:keys [removed? count]}
        (dispatch/dispatch! agent-session-ctx
                            :session/unregister-prompt-contribution
                            {:session-id session-id :ext-path ext-path :id id}
                            {:origin :mutations})]
    {:psi.extension/path                         (str ext-path)
     :psi.extension.prompt-contribution/id       (str id)
     :psi.extension.prompt-contribution/removed? removed?
     :psi.extension.prompt-contribution/count    count}))

(pco/defmutation send-prompt
  "Send an extension prompt to the current session."
  [_ {:keys [psi/agent-session-ctx session-id content source]}]
  {::pco/op-name 'psi.extension/send-prompt
   ::pco/params  [:psi/agent-session-ctx :session-id :content]
   ::pco/output  [:psi.extension/prompt-accepted?
                  :psi.extension/prompt-delivery]}
  (let [{:keys [accepted delivery]}
        (ext-rt/send-extension-prompt-in! agent-session-ctx session-id (or content "") source)]
    {:psi.extension/prompt-accepted? accepted
     :psi.extension/prompt-delivery  delivery}))

(def all-mutations
  [add-prompt-template
   reload-prompts
   add-skill
   register-prompt-contribution
   update-prompt-contribution
   unregister-prompt-contribution
   send-prompt])
