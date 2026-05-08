(ns psi.prompt-registry.contributions
  "Pure prompt-contribution collection ownership: normalization, registration,
   updates, removals, ordering, and lookups.")

(def ^:private canonical-priority 1000)

(defn normalize-identity
  "Return the canonical first-cut contribution identity map.

   Current preserved behavior intentionally coerces both identity parts with
   `str`, so nil becomes the empty string and blank strings remain allowed."
  [ext-path id]
  {:ext-path (str ext-path)
   :id (str id)})

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
     :ext-path   (:ext-path k)
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

   Ordering is stable by [priority ext-path id], with nil priority defaulting to
   1000 and nil identity fragments sorting as empty strings."
  [contributions]
  (->> (all-contributions contributions)
       (sort-by (fn [{:keys [priority ext-path id]}]
                  [(or priority canonical-priority)
                   (or ext-path "")
                   (or id "")]))
       vec))

(defn contribution-count
  "Return the contribution count."
  [contributions]
  (count (all-contributions contributions)))

(defn find-contribution
  "Return the contribution identified by `ext-path` + `id`, or nil."
  [contributions ext-path id]
  (let [{ext-path* :ext-path id* :id} (normalize-identity ext-path id)]
    (some #(when (and (= ext-path* (:ext-path %))
                      (= id* (:id %)))
             %)
          (all-contributions contributions))))

(defn register-contribution
  "Register or replace a contribution by canonical identity.

   Returns {:contributions [...] :contribution m :registered? true
            :replaced? boolean :changed? true :count n}."
  [contributions ext-path id contribution]
  (let [{ext-path* :ext-path id* :id} (normalize-identity ext-path id)
        contribution* (normalize-contribution ext-path* id* contribution)
        xs            (all-contributions contributions)
        replaced?     (boolean (find-contribution xs ext-path* id*))
        next*         (let [without-match (->> xs
                                               (remove #(and (= ext-path* (:ext-path %))
                                                             (= id* (:id %))))
                                               vec)]
                        (conj without-match contribution*))]
    {:contributions next*
     :contribution contribution*
     :registered? true
     :replaced? replaced?
     :changed? true
     :count (count next*)}))

(defn update-contribution
  "Patch an existing contribution by canonical identity.

   Returns {:contributions [...] :contribution m|nil :updated? boolean
            :changed? boolean :count n}."
  [contributions ext-path id patch]
  (let [{ext-path* :ext-path id* :id} (normalize-identity ext-path id)
        xs       (all-contributions contributions)
        found    (find-contribution xs ext-path* id*)]
    (if-not found
      {:contributions xs
       :contribution nil
       :updated? false
       :changed? false
       :count (count (sort-contributions xs))}
      (let [updated (atom nil)
            next*   (mapv (fn [c]
                            (if (and (= ext-path* (:ext-path c))
                                     (= id* (:id c)))
                              (let [n (merge-contribution-patch c patch)]
                                (reset! updated n)
                                n)
                              c))
                          xs)]
        {:contributions next*
         :contribution @updated
         :updated? true
         :changed? true
         :count (count (sort-contributions next*))}))))

(defn unregister-contribution
  "Remove a contribution by canonical identity.

   Returns {:contributions [...] :contribution removed|nil :removed? boolean
            :changed? boolean :count n}."
  [contributions ext-path id]
  (let [{ext-path* :ext-path id* :id} (normalize-identity ext-path id)
        xs       (all-contributions contributions)
        removed  (find-contribution xs ext-path* id*)
        next*    (->> xs
                      (remove #(and (= ext-path* (:ext-path %))
                                    (= id* (:id %))))
                      vec)
        removed? (some? removed)]
    {:contributions next*
     :contribution removed
     :removed? removed?
     :changed? removed?
     :count (if removed?
              (count next*)
              (count xs))}))
