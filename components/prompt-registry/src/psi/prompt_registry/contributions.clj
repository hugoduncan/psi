(ns psi.prompt-registry.contributions
  "Pure prompt-contribution collection ownership: normalization, registration,
   updates, removals, ordering, and lookups.")

(def ^:private canonical-priority 1000)

(defn normalize-identity
  "Return the canonical contribution identity.

   Canonical identity is string-coerced `id` alone. Nil and blank inputs remain
   accepted and normalize to the empty string in this first semantic
   simplification pass."
  [_ext-path id]
  {:id (str id)})

(defn normalize-contribution
  "Return the canonical stored contribution shape for `contribution`.

   Current preserved behavior intentionally does not reject blank or nil-like
   identity inputs after coercion because the pre-extraction handler contract
   accepted them via `str`."
  [ext-path id contribution]
  (let [now (java.time.Instant/now)
        c   (or contribution {})
        k   (normalize-identity ext-path id)]
    {:id         (:id k)
     :ext-path   (str ext-path)
     :section    (some-> (:section c) str)
     :content    (str (or (:content c) ""))
     :priority   (int (or (:priority c) canonical-priority))
     :enabled    (if (contains? c :enabled) (boolean (:enabled c)) true)
     :created-at now
     :updated-at now}))

(defn merge-contribution-patch
  "Apply the canonical first-cut patch contract to `existing`.

   Patchable fields are limited to :section, :content, :priority, and :enabled.
   Identity fields and :created-at are intentionally not patchable.
   Unknown patch keys are ignored."
  [existing patch]
  (let [p   (or patch {})
        now (java.time.Instant/now)]
    (cond-> (assoc existing :updated-at now)
      (contains? p :section)  (assoc :section (some-> (:section p) str))
      (contains? p :content)  (assoc :content (str (or (:content p) "")))
      (contains? p :priority) (assoc :priority (int (or (:priority p) canonical-priority)))
      (contains? p :enabled)  (assoc :enabled (boolean (:enabled p))))))

(defn all-contributions
  "Return the registered contributions as a vector, preserving stored order."
  [contributions]
  (->> (or contributions [])
       (filter map?)
       vec))

(defn sort-contributions
  "Return contributions in canonical prompt-contribution order.

   Ordering is stable by [priority id], with nil priority defaulting to 1000 and
   nil ids sorting as empty strings."
  [contributions]
  (->> (all-contributions contributions)
       (sort-by (fn [{:keys [priority id]}]
                  [(or priority canonical-priority)
                   (or id "")]))
       vec))

(defn contribution-count
  "Return the contribution count."
  [contributions]
  (count (all-contributions contributions)))

(defn- same-id?
  [{expected-id :id} contribution]
  (= expected-id (:id contribution)))

(defn find-contribution
  "Return the contribution identified by canonical `id`, or nil."
  [contributions _ext-path id]
  (let [identity (normalize-identity nil id)]
    (some #(when (same-id? identity %) %)
          (all-contributions contributions))))

(defn- ownership-conflict
  [existing ext-path id]
  (ex-info (format "prompt contribution id already owned by %s" (pr-str (:ext-path existing)))
           {:kind :prompt-contribution/ownership-conflict
            :id (str id)
            :owner (:ext-path existing)
            :requested-owner (str ext-path)
            :existing existing}))

(defn register-contribution
  "Register or replace a contribution by canonical id.

   Same-owner duplicate registration replaces the existing contribution.
   Cross-owner duplicate registration throws an explicit ownership conflict.

   Returns {:contributions [...] :contribution m :registered? true
            :replaced? boolean :changed? true :count n}."
  [contributions ext-path id contribution]
  (let [identity      (normalize-identity nil id)
        contribution* (normalize-contribution ext-path (:id identity) contribution)
        xs            (all-contributions contributions)
        existing      (find-contribution xs nil (:id identity))]
    (when (and existing
               (not= (str ext-path) (:ext-path existing)))
      (throw (ownership-conflict existing ext-path id)))
    (let [replaced?     (boolean existing)
          without-match (->> xs
                             (remove #(same-id? identity %))
                             vec)
          next*         (conj without-match contribution*)]
      {:contributions next*
       :contribution contribution*
       :registered? true
       :replaced? replaced?
       :changed? true
       :count (contribution-count next*)})))

(defn update-contribution
  "Patch an existing contribution by canonical id.

   When `ext-path` is supplied, it is treated as an ownership assertion rather
   than as part of identity.

   Returns {:contributions [...] :contribution m|nil :updated? boolean
            :changed? boolean :count n}."
  [contributions ext-path id patch]
  (let [identity (normalize-identity nil id)
        xs       (all-contributions contributions)
        found    (find-contribution xs nil (:id identity))]
    (cond
      (nil? found)
      {:contributions xs
       :contribution nil
       :updated? false
       :changed? false
       :count (contribution-count xs)}

      (and (some? ext-path)
           (not= (str ext-path) (:ext-path found)))
      {:contributions xs
       :contribution nil
       :updated? false
       :changed? false
       :count (contribution-count xs)}

      :else
      (let [{:keys [contributions updated]}
            (reduce (fn [{:keys [contributions] :as acc} contribution]
                      (if (same-id? identity contribution)
                        (let [next-contribution (merge-contribution-patch contribution patch)]
                          {:contributions (conj contributions next-contribution)
                           :updated next-contribution})
                        (update acc :contributions conj contribution)))
                    {:contributions []
                     :updated nil}
                    xs)]
        {:contributions contributions
         :contribution updated
         :updated? true
         :changed? true
         :count (contribution-count contributions)}))))

(defn unregister-contribution
  "Remove a contribution by canonical id.

   When `ext-path` is supplied, it is treated as an ownership assertion rather
   than as part of identity.

   Returns {:contributions [...] :contribution removed|nil :removed? boolean
            :changed? boolean :count n}."
  [contributions ext-path id]
  (let [identity (normalize-identity nil id)
        xs       (all-contributions contributions)
        removed  (find-contribution xs nil (:id identity))]
    (cond
      (nil? removed)
      {:contributions xs
       :contribution nil
       :removed? false
       :changed? false
       :count (contribution-count xs)}

      (and (some? ext-path)
           (not= (str ext-path) (:ext-path removed)))
      {:contributions xs
       :contribution nil
       :removed? false
       :changed? false
       :count (contribution-count xs)}

      :else
      (let [next* (->> xs
                       (remove #(same-id? identity %))
                       vec)]
        {:contributions next*
         :contribution removed
         :removed? true
         :changed? true
         :count (contribution-count next*)}))))