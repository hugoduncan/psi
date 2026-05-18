(ns psi.agent-session.dispatch-handlers.prompt-handlers
  "Handlers for system-prompt and prompt-contribution events:
   set-system-prompt, refresh-system-prompt, set-prompt-mode,
   register/update/unregister-prompt-contribution,
   bootstrap-prompt-state, ensure-base-system-prompt,
   reset-prompt-contributions, register-prompt-template."
  (:require
   [psi.prompt-assets.system-prompt :as sys-prompt]
   [psi.prompt-registry.contributions :as contributions]
   [psi.session-state.state :as session]
   [psi.state-kernel.dispatch :as kernel]))

;;; Prompt contribution pure helpers

(defn- effective-prompt
  [base contributions selection]
  (sys-prompt/apply-prompt-contributions
   (or base "")
   (-> contributions
       (sys-prompt/filter-prompt-contributions selection)
       session/sorted-prompt-contributions)))

;;; System-prompt rebuild helpers — imported lazily to avoid circular deps.
;;; sys-prompt ns is referenced via require at call time; callers pass it in.

(defn register!
  "Register all prompt and system-prompt handlers. Called once during context creation."
  [_ctx]
  (kernel/register-handler!
   :session/set-system-prompt-build-opts
   (fn [_ctx {:keys [session-id opts]}]
     {:root-state-update (session/session-update session-id #(assoc % :system-prompt-build-opts opts))}))

  (kernel/register-handler!
   :session/refresh-system-prompt
   (fn [ctx {:keys [session-id]}]
     (let [sd      (session/get-session-data-in ctx session-id)
           contrib (session/list-prompt-contributions-in ctx session-id)
           ;; When build opts are stored, rebuild the assembled base prompt first,
           ;; then apply extension contributions as a distinct final layer so
           ;; request preparation and introspection can observe the split.
           selection (:prompt-component-selection sd)
           base    (if-let [build-opts (:system-prompt-build-opts sd)]
                     (let [selected-tools (or (some->> (:tool-defs sd) seq (mapv :name))
                                              (:selected-tools build-opts))]
                       (sys-prompt/build-system-prompt
                        (assoc build-opts
                               :prompt-mode (:prompt-mode sd :lambda)
                               :tool-defs (vec (or (:tool-defs sd) []))
                               :selected-tools selected-tools
                               :skills (vec (or (:skills sd) []))
                               :prompt-contributions nil)))
                     (or (:base-system-prompt sd) (:system-prompt sd) ""))
           prompt  (effective-prompt base contrib selection)]
       {:root-state-update (session/session-update session-id #(assoc %
                                                                      :base-system-prompt base
                                                                      :system-prompt prompt))
        :effects [{:effect/type :runtime/agent-set-system-prompt
                   :prompt prompt}]})))

  (kernel/register-handler!
   :session/set-system-prompt
   (fn [ctx {:keys [session-id prompt]}]
     (let [base*     (or prompt "")
           contrib   (session/list-prompt-contributions-in ctx session-id)
           selection (:prompt-component-selection (session/get-session-data-in ctx session-id))
           prompt*   (effective-prompt base* contrib selection)]
       {:root-state-update (session/session-update session-id #(assoc %
                                                                      :base-system-prompt base*
                                                                      :system-prompt prompt*))
        :effects [{:effect/type :runtime/agent-set-system-prompt
                   :prompt prompt*}]})))

  (kernel/register-handler!
   :session/register-prompt-contribution
   (fn [ctx {:keys [session-id ext-path id contribution]}]
     (let [sd      (session/get-session-data-in ctx session-id)
           xs      (:prompt-contributions sd)
           result  (contributions/register-contribution xs ext-path id contribution)
           next*   (:contributions result)
           base    (or (:base-system-prompt sd) (:system-prompt sd) "")
           prompt* (effective-prompt base next* (:prompt-component-selection sd))]
       {:root-state-update
        (session/session-update session-id
                                #(assoc %
                                        :prompt-contributions next*
                                        :system-prompt prompt*))
        :effects [{:effect/type :runtime/agent-set-system-prompt
                   :prompt prompt*}]
        :return {:registered?  (:registered? result)
                 :contribution (:contribution result)
                 :count (:count result)}})))

  (kernel/register-handler!
   :session/update-prompt-contribution
   (fn [ctx {:keys [session-id ext-path id patch]}]
     (let [sd     (session/get-session-data-in ctx session-id)
           xs     (:prompt-contributions sd)
           result (contributions/update-contribution xs ext-path id patch)]
       (if-not (:updated? result)
         {:return {:updated? false
                   :contribution nil
                   :count (:count result)}}
         (let [next*   (:contributions result)
               base    (or (:base-system-prompt sd) (:system-prompt sd) "")
               prompt* (effective-prompt base next* (:prompt-component-selection sd))]
           {:root-state-update (session/session-update session-id #(assoc %
                                                                          :prompt-contributions next*
                                                                          :system-prompt prompt*))
            :effects [{:effect/type :runtime/agent-set-system-prompt
                       :prompt prompt*}]
            :return {:updated?     (:updated? result)
                     :contribution (:contribution result)
                     :count (:count result)}})))))

  (kernel/register-handler!
   :session/unregister-prompt-contribution
   (fn [ctx {:keys [session-id ext-path id]}]
     (let [sd     (session/get-session-data-in ctx session-id)
           xs     (:prompt-contributions sd)
           result (contributions/unregister-contribution xs ext-path id)]
       (if-not (:removed? result)
         {:return {:removed? false :count (:count result)}}
         (let [next*   (:contributions result)
               base    (or (:base-system-prompt sd) (:system-prompt sd) "")
               prompt* (effective-prompt base next* (:prompt-component-selection sd))]
           {:root-state-update (session/session-update session-id #(assoc %
                                                                          :prompt-contributions next*
                                                                          :system-prompt prompt*))
            :effects [{:effect/type :runtime/agent-set-system-prompt
                       :prompt prompt*}]
            :return {:removed? true :count (:count result)}})))))

  (kernel/register-handler!
   :session/reset-prompt-contributions
   (fn [_ctx {:keys [session-id]}]
     {:root-state-update (session/session-update session-id #(assoc % :prompt-contributions []))}))

  (kernel/register-handler!
   :session/bootstrap-prompt-state
   (fn [_ctx {:keys [session-id system-prompt developer-prompt developer-prompt-source]}]
     {:root-state-update (session/session-update session-id #(assoc %
                                                                    :base-system-prompt system-prompt
                                                                    :system-prompt system-prompt
                                                                    :developer-prompt developer-prompt
                                                                    :developer-prompt-source developer-prompt-source))}))

  (kernel/register-handler!
   :session/ensure-base-system-prompt
   (fn [ctx {:keys [session-id]}]
     (let [sd (session/get-session-data-in ctx session-id)]
       (if (contains? sd :base-system-prompt)
         {:effects []}
         {:root-state-update (session/session-update session-id #(assoc % :base-system-prompt (or (:system-prompt sd) "")))}))))

  (kernel/register-handler!
   :session/register-prompt-template
   (fn [ctx {:keys [session-id template]}]
     (let [templates  (vec (:prompt-templates (session/get-session-data-in ctx session-id)))
           existing?  (some #(= (:name %) (:name template)) templates)
           next-count (if existing? (count templates) (inc (count templates)))]
       (cond-> {:return {:added? (not existing?) :count next-count}}
         (not existing?)
         (assoc :root-state-update (session/session-update session-id #(update % :prompt-templates (fnil conj []) template))))))))