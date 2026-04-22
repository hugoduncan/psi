(ns psi.agent-session.dispatch-handlers.prompt-handlers
  "Handlers for system-prompt and prompt-contribution events:
   set-system-prompt, refresh-system-prompt, set-prompt-mode,
   register/update/unregister-prompt-contribution,
   bootstrap-prompt-state, ensure-base-system-prompt,
   reset-prompt-contributions, register-prompt-template."
  (:require
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.session-state :as session]
   [psi.agent-session.system-prompt :as sys-prompt]))

;;; Prompt contribution pure helpers

(defn- effective-prompt
  [base contributions selection]
  (sys-prompt/apply-prompt-contributions
   (or base "")
   (-> contributions
       (sys-prompt/filter-prompt-contributions selection)
       session/sorted-prompt-contributions)))

(defn- normalize-prompt-contribution [ext-path id contribution]
  (let [now (java.time.Instant/now)
        c   (or contribution {})]
    {:id         (str id)
     :ext-path   (str ext-path)
     :section    (some-> (:section c) str)
     :content    (str (or (:content c) ""))
     :priority   (int (or (:priority c) 1000))
     :enabled    (if (contains? c :enabled) (boolean (:enabled c)) true)
     :created-at now
     :updated-at now}))

(defn- merge-prompt-contribution-patch [existing patch]
  (let [p   (or patch {})
        now (java.time.Instant/now)]
    (cond-> (assoc existing :updated-at now)
      (contains? p :section)  (assoc :section  (some-> (:section p) str))
      (contains? p :content)  (assoc :content  (str (or (:content p) "")))
      (contains? p :priority) (assoc :priority (int (or (:priority p) 1000)))
      (contains? p :enabled)  (assoc :enabled  (boolean (:enabled p))))))

;;; System-prompt rebuild helpers — imported lazily to avoid circular deps.
;;; sys-prompt ns is referenced via require at call time; callers pass it in.

(defn register!
  "Register all prompt and system-prompt handlers. Called once during context creation."
  [_ctx]
  (dispatch/register-handler!
   :session/set-system-prompt-build-opts
   (fn [_ctx {:keys [session-id opts]}]
     {:root-state-update (session/session-update session-id #(assoc % :system-prompt-build-opts opts))}))

  (dispatch/register-handler!
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

  (dispatch/register-handler!
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

  (dispatch/register-handler!
   :session/register-prompt-contribution
   (fn [ctx {:keys [session-id ext-path id contribution]}]
     (let [ext-path* (str ext-path)
           id*       (str id)
           norm      (normalize-prompt-contribution ext-path* id* contribution)
           sd        (session/get-session-data-in ctx session-id)
           xs        (or (:prompt-contributions sd) [])
           xs*       (vec (remove #(and (= ext-path* (:ext-path %))
                                        (= id* (:id %)))
                                  xs))
           next*     (conj xs* norm)
           base      (or (:base-system-prompt sd) (:system-prompt sd) "")
           prompt*   (effective-prompt base next* (:prompt-component-selection sd))]
       {:root-state-update
        (session/session-update session-id
                                #(assoc %
                                        :prompt-contributions next*
                                        :system-prompt prompt*))
        :effects [{:effect/type :runtime/agent-set-system-prompt
                   :prompt prompt*}]
        :return {:registered?  true
                 :contribution norm
                 :count (count (session/list-prompt-contributions-in ctx session-id))}})))

  (dispatch/register-handler!
   :session/update-prompt-contribution
   (fn [ctx {:keys [session-id ext-path id patch]}]
     (let [ext-path* (str ext-path)
           id*       (str id)
           sd        (session/get-session-data-in ctx session-id)
           xs        (vec (or (:prompt-contributions sd) []))
           found     (some #(and (= ext-path* (:ext-path %)) (= id* (:id %))) xs)]
       (if-not found
         {:return {:updated?     false
                   :contribution nil
                   :count (count (session/sorted-prompt-contributions xs))}}
         (let [updated (atom nil)
               next*   (mapv (fn [c]
                               (if (and (= ext-path* (:ext-path c))
                                        (= id* (:id c)))
                                 (let [n (merge-prompt-contribution-patch c patch)]
                                   (reset! updated n)
                                   n)
                                 c))
                             xs)
               base    (or (:base-system-prompt sd) (:system-prompt sd) "")
               prompt* (effective-prompt base next* (:prompt-component-selection sd))]
           {:root-state-update (session/session-update session-id #(assoc %
                                                                          :prompt-contributions next*
                                                                          :system-prompt prompt*))
            :effects [{:effect/type :runtime/agent-set-system-prompt
                       :prompt prompt*}]
            :return {:updated?     true
                     :contribution @updated
                     :count (count (session/sorted-prompt-contributions next*))}})))))

  (dispatch/register-handler!
   :session/unregister-prompt-contribution
   (fn [ctx {:keys [session-id ext-path id]}]
     (let [ext-path* (str ext-path)
           id*       (str id)
           sd        (session/get-session-data-in ctx session-id)
           xs        (vec (or (:prompt-contributions sd) []))
           next*     (vec (remove #(and (= ext-path* (:ext-path %))
                                        (= id* (:id %)))
                                  xs))
           removed?  (< (count next*) (count xs))]
       (if-not removed?
         {:return {:removed? false :count (count xs)}}
         (let [base    (or (:base-system-prompt sd) (:system-prompt sd) "")
               prompt* (effective-prompt base next* (:prompt-component-selection sd))]
           {:root-state-update (session/session-update session-id #(assoc %
                                                                          :prompt-contributions next*
                                                                          :system-prompt prompt*))
            :effects [{:effect/type :runtime/agent-set-system-prompt
                       :prompt prompt*}]
            :return {:removed? true :count (count next*)}})))))

  (dispatch/register-handler!
   :session/reset-prompt-contributions
   (fn [_ctx {:keys [session-id]}]
     {:root-state-update (session/session-update session-id #(assoc % :prompt-contributions []))}))

  (dispatch/register-handler!
   :session/bootstrap-prompt-state
   (fn [_ctx {:keys [session-id system-prompt developer-prompt developer-prompt-source]}]
     {:root-state-update (session/session-update session-id #(assoc %
                                                                    :base-system-prompt system-prompt
                                                                    :system-prompt system-prompt
                                                                    :developer-prompt developer-prompt
                                                                    :developer-prompt-source developer-prompt-source))}))

  (dispatch/register-handler!
   :session/ensure-base-system-prompt
   (fn [ctx {:keys [session-id]}]
     (let [sd (session/get-session-data-in ctx session-id)]
       (if (contains? sd :base-system-prompt)
         {:effects []}
         {:root-state-update (session/session-update session-id #(assoc % :base-system-prompt (or (:system-prompt sd) "")))}))))

  (dispatch/register-handler!
   :session/register-prompt-template
   (fn [ctx {:keys [session-id template]}]
     (let [templates  (vec (:prompt-templates (session/get-session-data-in ctx session-id)))
           existing?  (some #(= (:name %) (:name template)) templates)
           next-count (if existing? (count templates) (inc (count templates)))]
       (cond-> {:return {:added? (not existing?) :count next-count}}
         (not existing?)
         (assoc :root-state-update (session/session-update session-id #(update % :prompt-templates (fnil conj []) template))))))))
