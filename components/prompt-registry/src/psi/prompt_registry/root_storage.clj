(ns psi.prompt-registry.root-storage
  "Root-registry-backed prompt contribution definition storage plus session
   membership helpers. Prompt-specific normalization, patch semantics, result
   projection, and canonical ordering remain adapter-owned here."
  (:require
   [psi.prompt-registry.contributions :as contributions]
   [psi.root-registry.registry :as root-registry]))

(def registry-id :prompt-contributions)
(def session-prompt-ids-key :prompt-contribution-ids)

(defn- session-data-path [session-id]
  [:agent-session :sessions session-id :data])

(defn ensure-prompt-registry
  [root-state]
  (root-registry/declare-registry root-state registry-id))

(defn- prompt-entry
  [contribution]
  {:id (:id contribution)
   :extension-id (:ext-path contribution)
   :value contribution})

(defn- lookup-entry
  [root-state prompt-id]
  (some-> (root-registry/lookup root-state registry-id prompt-id)
          :result
          :value))

(defn find-definition
  [root-state prompt-id]
  (some-> (lookup-entry root-state prompt-id)
          :value))

(defn prompt-ids
  [session-data]
  (vec (or (get session-data session-prompt-ids-key) [])))

(defn list-contributions
  [root-state session-data]
  (->> (prompt-ids session-data)
       (keep #(find-definition root-state %))
       contributions/sort-contributions))

(defn contribution-count
  [_root-state session-data]
  (count (prompt-ids session-data)))

(defn register-contribution-in-root-state
  [root-state session-id ext-path id contribution]
  (let [root-state      (ensure-prompt-registry root-state)
        session-data    (get-in root-state (session-data-path session-id))
        current-ids     (prompt-ids session-data)
        prompt-id       (:id (contributions/normalize-identity ext-path id))
        existing        (find-definition root-state prompt-id)
        _               (when (and existing
                                   (not= (str ext-path) (:ext-path existing)))
                          (throw (ex-info (format "prompt contribution id already owned by %s" (pr-str (:ext-path existing)))
                                          {:kind :prompt-contribution/ownership-conflict
                                           :id prompt-id
                                           :owner (:ext-path existing)
                                           :requested-owner (str ext-path)
                                           :existing existing})))
        contribution*   (contributions/normalize-contribution ext-path prompt-id contribution)
        register-result (root-registry/register root-state registry-id (prompt-entry contribution*))
        root-state*     (:root-state register-result)
        already-member? (some #(= prompt-id %) current-ids)
        next-ids        (if already-member? current-ids (conj current-ids prompt-id))
        root-state*     (if (= next-ids current-ids)
                          root-state*
                          (assoc-in root-state*
                                    (conj (session-data-path session-id) session-prompt-ids-key)
                                    next-ids))]
    {:root-state root-state*
     :contribution contribution*
     :registered? true
     :replaced? (some? existing)
     :changed? true
     :count (count next-ids)}))

(defn update-contribution-in-root-state
  [root-state session-id ext-path id patch]
  (let [root-state   (ensure-prompt-registry root-state)
        session-data (get-in root-state (session-data-path session-id))
        current-ids  (prompt-ids session-data)
        prompt-id    (:id (contributions/normalize-identity ext-path id))
        found        (find-definition root-state prompt-id)]
    (cond
      (nil? found)
      {:root-state root-state
       :contribution nil
       :updated? false
       :changed? false
       :count (count current-ids)}

      (and (some? ext-path)
           (not= (str ext-path) (:ext-path found)))
      {:root-state root-state
       :contribution nil
       :updated? false
       :changed? false
       :count (count current-ids)}

      :else
      (let [updated         (contributions/merge-contribution-patch found patch)
            register-result (root-registry/register root-state registry-id (prompt-entry updated))]
        {:root-state (:root-state register-result)
         :contribution updated
         :updated? true
         :changed? true
         :count (count current-ids)}))))

(defn unregister-contribution-in-root-state
  [root-state session-id ext-path id]
  (let [root-state   (ensure-prompt-registry root-state)
        session-data (get-in root-state (session-data-path session-id))
        current-ids  (prompt-ids session-data)
        prompt-id    (:id (contributions/normalize-identity ext-path id))
        removed      (find-definition root-state prompt-id)]
    (cond
      (nil? removed)
      {:root-state root-state
       :contribution nil
       :removed? false
       :changed? false
       :count (count current-ids)}

      (and (some? ext-path)
           (not= (str ext-path) (:ext-path removed)))
      {:root-state root-state
       :contribution nil
       :removed? false
       :changed? false
       :count (count current-ids)}

      :else
      (let [unregister-result (root-registry/unregister root-state registry-id prompt-id)
            next-ids          (->> current-ids
                                   (remove #(= prompt-id %))
                                   vec)
            root-state*       (assoc-in (:root-state unregister-result)
                                        (conj (session-data-path session-id) session-prompt-ids-key)
                                        next-ids)]
        {:root-state root-state*
         :contribution removed
         :removed? true
         :changed? true
         :count (count next-ids)}))))

(defn reset-prompt-contributions-in-root-state
  [root-state session-id]
  (let [root-state   (ensure-prompt-registry root-state)
        session-data (get-in root-state (session-data-path session-id))
        ids          (prompt-ids session-data)
        root-state*  (reduce (fn [state prompt-id]
                               (:root-state (root-registry/unregister state registry-id prompt-id)))
                             root-state
                             ids)]
    {:root-state (assoc-in root-state*
                           (conj (session-data-path session-id) session-prompt-ids-key)
                           [])
     :removed-ids (vec ids)
     :count 0}))
