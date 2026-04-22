(ns psi.ai.model-selection
  "Shared model-selection surfaces.

   v1 establishes:
   - the resolver-facing catalog view
   - request shapes and role defaults
   - policy layering and effective-request composition
   - resolver stage 1 filtering

   Later steps will add ranking and trace production on top of these data
   structures."
  (:require
   [psi.ai.model-registry :as model-registry]))

(def ^:private default-role
  :interactive)

(def ^:private policy-scope-order
  [:role-defaults :system :user :project :request])

(def ^:private role-defaults-map
  {:interactive
   {:role                :interactive
    :required            []
    :strong-preferences  []
    :weak-preferences    []}

   :helper
   {:role                :helper
    :required            [{:criterion :supports-text
                           :match     :true}]
    :strong-preferences  [{:criterion :input-cost
                           :prefer    :lower}
                          {:criterion :output-cost
                           :prefer    :lower}]
    :weak-preferences    []}

   :auto-session-name
   {:role                :auto-session-name
    :required            [{:criterion :supports-text
                           :match     :true}]
    :strong-preferences  [{:criterion :input-cost
                           :prefer    :lower}
                          {:criterion :output-cost
                           :prefer    :lower}]
    :weak-preferences    [{:criterion :same-provider-as-session
                           :prefer    :context-match}]}})

(defn role-defaults
  "Return role defaults for `role`, or nil when unknown."
  [role]
  (get role-defaults-map role))

(defn catalog-view
  "Return the merged resolver-facing catalog view.

   Result shape:
   {:candidates [{:provider kw
                  :id string
                  :name string?
                  :api keyword?
                  :base-url string?
                  :facts {...}
                  :estimates {...}
                  :reference {:configured? boolean?}}
                 ...]}

   v1 intentionally exposes only queryable metadata that already exists:
   - facts: provider/api/capability/context/token attrs
   - estimates: currently raw cost attributes
   - reference: provider auth/config availability

   No implicit locality or policy labels are invented here."
  []
  {:candidates
   (->> (model-registry/all-models)
        (map (fn [[[provider id] model]]
               (let [auth (model-registry/get-auth provider)]
                 {:provider  provider
                  :id        id
                  :name      (:name model)
                  :api       (:api model)
                  :base-url  (:base-url model)
                  :facts     {:provider            provider
                              :id                  id
                              :api                 (:api model)
                              :supports-text       (boolean (:supports-text model))
                              :supports-images     (boolean (:supports-images model))
                              :supports-reasoning  (boolean (:supports-reasoning model))
                              :context-window      (:context-window model)
                              :max-tokens          (:max-tokens model)
                              :locality            (:locality model)}
                  :estimates {:input-cost         (:input-cost model)
                              :output-cost        (:output-cost model)
                              :cache-read-cost    (:cache-read-cost model)
                              :cache-write-cost   (:cache-write-cost model)
                              :latency-tier       (:latency-tier model)
                              :cost-tier          (:cost-tier model)}
                  :reference {:configured? (boolean (or (nil? auth)
                                                        (:api-key auth)
                                                        (seq (:headers auth))
                                                        (false? (:auth-header? auth))))}})))
        (sort-by (juxt :provider :id))
        vec)})

(defn find-candidate
  "Find a resolver-facing candidate by provider/id."
  [catalog provider id]
  (some (fn [candidate]
          (when (and (= provider (:provider candidate))
                     (= id (:id candidate)))
            candidate))
        (:candidates catalog)))

(defn criterion-identity
  "Return the stable identity key for a criterion.

   v1 identity is the compared attribute/criterion itself. This supports:
   - replacement by higher-precedence policy layers
   - deterministic deduplication within a preference tier

   Future richer criterion shapes may extend this to include comparator/target
   dimensions when one attribute supports multiple distinct identities."
  [criterion]
  (or (:identity criterion)
      (:criterion criterion)
      (:attribute criterion)))

(defn normalize-request
  "Normalize a model-selection request.

   Request shape:
   {:mode :explicit | :inherit-session | :resolve
    :role keyword?
    :required [criterion*]
    :strong-preferences [criterion*]
    :weak-preferences [criterion*]
    :context map?
    :model {:provider kw|string :id string}?}

   v1 responsibilities:
   - provide default mode/role
   - normalize missing vectors/maps
   - normalize explicit model provider to keyword
   - merge role defaults underneath caller-supplied fields

   This does not yet implement multi-layer policy composition."
  [request]
  (let [mode          (or (:mode request) :resolve)
        role          (or (:role request) default-role)
        role-default* (role-defaults role)
        model         (when-let [m (:model request)]
                        (cond-> {:provider (:provider m)
                                 :id       (:id m)}
                          (string? (:provider m))
                          (update :provider keyword)))
        normalized    (cond-> {}
                        (contains? request :required)
                        (assoc :required (vec (or (:required request) [])))

                        (contains? request :strong-preferences)
                        (assoc :strong-preferences
                               (vec (or (:strong-preferences request) [])))

                        (contains? request :weak-preferences)
                        (assoc :weak-preferences
                               (vec (or (:weak-preferences request) [])))

                        (contains? request :context)
                        (assoc :context (or (:context request) {}))

                        (contains? request :model)
                        (assoc :model model))]
    (merge {:mode                mode
            :role                role
            :required            []
            :strong-preferences  []
            :weak-preferences    []
            :context             {}
            :model               nil}
           role-default*
           normalized)))

(defn- normalize-policy-layer
  [layer]
  (let [normalized (normalize-request layer)]
    (select-keys normalized [:required :strong-preferences :weak-preferences :context :model])))

(defn- merge-required-constraints
  [layers]
  (reduce
   (fn [acc layer]
     (reduce
      (fn [req-acc constraint]
        (let [id (criterion-identity constraint)]
          (if (some #(= id (criterion-identity %)) req-acc)
            req-acc
            (conj req-acc constraint))))
      acc
      (:required layer)))
   []
   layers))

(defn- merge-preference-tier
  [layers tier]
  (reduce
   (fn [acc layer]
     (reduce
      (fn [tier-acc criterion]
        (let [id (criterion-identity criterion)]
          (if-let [idx (first (keep-indexed (fn [i existing]
                                              (when (= id (criterion-identity existing))
                                                i))
                                            tier-acc))]
            (assoc tier-acc idx criterion)
            (conj tier-acc criterion))))
      acc
      (get layer tier [])))
   []
   layers))

(defn compose-effective-request
  "Compose role defaults, policy layers, and caller request into one effective request.

   Input shape:
   {:system  request-like-map?
    :user    request-like-map?
    :project request-like-map?
    :request request-like-map?}

   Precedence is fixed by `policy-scope-order`:
   role-defaults < system < user < project < request

   Merge rules in v1:
   - required constraints union in precedence order
   - strong/weak preferences dedupe by `criterion-identity`
   - higher-precedence duplicates replace lower-precedence instances in place
   - new criteria append after inherited criteria within a tier
   - context maps merge shallowly, later scopes win per key
   - explicit model on the highest-precedence supplying scope wins

   Returns a normalized effective request."
  [{:keys [system user project request]}]
  (let [request*       (normalize-request (or request {}))
        role           (:role request*)
        layers-by-scope {:role-defaults (normalize-policy-layer (role-defaults role))
                         :system        (normalize-policy-layer (or system {}))
                         :user          (normalize-policy-layer (or user {}))
                         :project       (normalize-policy-layer (or project {}))
                         :request       (normalize-policy-layer request*)}
        ordered-layers (mapv layers-by-scope policy-scope-order)]
    {:mode                (:mode request*)
     :role                role
     :required            (merge-required-constraints ordered-layers)
     :strong-preferences  (merge-preference-tier ordered-layers :strong-preferences)
     :weak-preferences    (merge-preference-tier ordered-layers :weak-preferences)
     :context             (apply merge {} (map :context ordered-layers))
     :model               (some :model (reverse ordered-layers))}))

(defn- candidate-attribute
  [candidate criterion]
  (let [k (or (:criterion criterion) (:attribute criterion))]
    (or (get-in candidate [:facts k])
        (get-in candidate [:estimates k])
        (get-in candidate [:reference k])
        (get candidate k))))

(defn- required-constraint-satisfied?
  [candidate constraint]
  (let [value (candidate-attribute candidate constraint)]
    (cond
      (contains? constraint :match)
      (= value (case (:match constraint)
                 :true true
                 :false false
                 (:match constraint)))

      (contains? constraint :at-least)
      (and (number? value)
           (>= value (:at-least constraint)))

      (contains? constraint :at-most)
      (and (number? value)
           (<= value (:at-most constraint)))

      (contains? constraint :equals)
      (= value (:equals constraint))

      (contains? constraint :one-of)
      (contains? (set (:one-of constraint)) value)

      :else
      (boolean value))))

(defn- explicit-request-candidates
  [catalog request]
  (if-let [{:keys [provider id]} (:model request)]
    (if-let [candidate (find-candidate catalog provider id)]
      [candidate]
      [])
    []))

(defn- inherit-session-candidates
  [catalog request]
  (let [provider (or (get-in request [:context :session-model :provider])
                     (get-in request [:context :session-provider]))
        id       (or (get-in request [:context :session-model :id])
                     (get-in request [:context :session-model-id]))]
    (if (and provider id)
      (if-let [candidate (find-candidate catalog provider id)]
        [candidate]
        [])
      [])))

(defn candidate-pool
  "Return the candidate pool implied by request mode before required filtering."
  [catalog request]
  (case (:mode request)
    :explicit        (explicit-request-candidates catalog request)
    :inherit-session (inherit-session-candidates catalog request)
    :resolve         (:candidates catalog)
    (:candidates catalog)))

(defn filter-candidates
  "Apply stage-1 resolver filtering.

   Returns:
   {:pool [...]
    :survivors [...]
    :eliminated [{:candidate c :reasons [constraint*]} ...]}

   v1 filtering includes:
   - mode-specific candidate pool restriction
   - required constraint checks over that pool

   This does not yet rank survivors or produce the final resolver result."
  [catalog request]
  (let [pool      (vec (candidate-pool catalog request))
        required  (:required request)
        results   (mapv (fn [candidate]
                          (let [failed (->> required
                                            (remove #(required-constraint-satisfied? candidate %))
                                            vec)]
                            {:candidate candidate
                             :failed    failed}))
                        pool)]
    {:pool       pool
     :survivors  (->> results
                      (filter #(empty? (:failed %)))
                      (mapv :candidate))
     :eliminated (->> results
                      (remove #(empty? (:failed %)))
                      (mapv (fn [{:keys [candidate failed]}]
                              {:candidate candidate
                               :reasons   failed})))}))

(def ^:private latency-rank
  {:low 0 :medium 1 :high 2})

(def ^:private cost-rank
  {:zero 0 :low 1 :medium 2 :high 3})

(defn- comparable-value
  [criterion value]
  (case (or (:criterion criterion) (:attribute criterion))
    :latency-tier (get latency-rank value value)
    :cost-tier    (get cost-rank value value)
    value))

(defn- compare-values
  [criterion left right prefer]
  (let [left*  (comparable-value criterion left)
        right* (comparable-value criterion right)]
    (cond
      (and (nil? left*) (nil? right*))
      0

      (nil? left*)
      0

      (nil? right*)
      0

      (= left* right*)
      0

      (= prefer :lower)
      (compare left* right*)

      (= prefer :higher)
      (compare right* left*)

      :else
      0)))

(defn- context-value
  [request criterion]
  (let [context   (:context request)
        provider  (or (get-in context [:session-model :provider])
                      (:session-provider context))
        model-id  (or (get-in context [:session-model :id])
                      (:session-model-id context))]
    (case (:criterion criterion)
      :same-provider-as-session provider
      :same-model-as-session    {:provider provider :id model-id}
      nil)))

(defn- compare-by-criterion
  [request left right criterion]
  (let [prefer (:prefer criterion)]
    (case prefer
      :lower
      (compare-values criterion
                      (candidate-attribute left criterion)
                      (candidate-attribute right criterion)
                      :lower)

      :higher
      (compare-values criterion
                      (candidate-attribute left criterion)
                      (candidate-attribute right criterion)
                      :higher)

      :equals
      (let [target       (:equals criterion)
            left-match?  (= (candidate-attribute left criterion) target)
            right-match? (= (candidate-attribute right criterion) target)]
        (cond
          (= left-match? right-match?) 0
          left-match? -1
          right-match? 1
          :else 0))

      :context-match
      (let [target (context-value request criterion)
            left*  (case (:criterion criterion)
                     :same-provider-as-session (:provider left)
                     :same-model-as-session    {:provider (:provider left)
                                                :id       (:id left)}
                     (candidate-attribute left criterion))
            right* (case (:criterion criterion)
                     :same-provider-as-session (:provider right)
                     :same-model-as-session    {:provider (:provider right)
                                                :id       (:id right)}
                     (candidate-attribute right criterion))
            left-match? (= left* target)
            right-match? (= right* target)]
        (cond
          (= left-match? right-match?) 0
          left-match? -1
          right-match? 1
          :else 0))

      0)))

(defn- tier-comparison
  [request left right criteria]
  (loop [[criterion & more] criteria]
    (if-not criterion
      0
      (let [result (compare-by-criterion request left right criterion)]
        (if (zero? result)
          (recur more)
          result)))))

(defn- candidate-sort-key
  [candidate]
  [(some-> (:provider candidate) name)
   (:id candidate)])

(defn rank-candidates
  "Apply stage-2 resolver ranking to survivor candidates.

   Returns:
   {:ranked [...]
    :ambiguous? boolean}

   Ranking is lexicographic:
   1. strong preferences in declared order
   2. weak preferences in declared order
   3. canonical provider/id tie-break

   Ambiguity is true when the top two ranked candidates tie on all effective
   preferences and are separated only by the canonical tie-break."
  [request survivors]
  (let [strong (:strong-preferences request)
        weak   (:weak-preferences request)
        cmp    (fn [left right]
                 (let [strong* (tier-comparison request left right strong)]
                   (if (zero? strong*)
                     (let [weak* (tier-comparison request left right weak)]
                       (if (zero? weak*)
                         (compare (candidate-sort-key left)
                                  (candidate-sort-key right))
                         weak*))
                     strong*)))
        ranked (vec (sort cmp survivors))
        ambiguous? (when (<= 2 (count ranked))
                     (and (zero? (tier-comparison request (first ranked) (second ranked) strong))
                          (zero? (tier-comparison request (first ranked) (second ranked) weak))))]
    {:ranked     ranked
     :ambiguous? (boolean ambiguous?)}))

(defn short-trace
  "Project a terse explanation from a full resolver result."
  [result]
  (case (:outcome result)
    :ok
    {:outcome     :ok
     :selected    (select-keys (:candidate result) [:provider :id :name])
     :ambiguous?  (:ambiguous? result)
     :pool-count  (count (get-in result [:filtering :pool]))
     :survivors   (count (get-in result [:filtering :survivors]))}

    :no-winner
    {:outcome     :no-winner
     :reason      (:reason result)
     :pool-count  (count (get-in result [:filtering :pool]))
     :survivors   (count (get-in result [:filtering :survivors]))}))

(defn full-trace
  "Project the full explainability payload from a resolver result."
  [result]
  {:effective-request (:effective-request result)
   :filtering         (:filtering result)
   :ranking           (:ranking result)
   :outcome           (:outcome result)
   :reason            (:reason result)
   :candidate         (:candidate result)
   :ambiguous?        (:ambiguous? result)})

(defn resolve-selection
  "Resolve model selection through composition, filtering, and ranking.

   Input shape matches `compose-effective-request`.

   Returns one of three outcomes:
   {:outcome :ok
    :candidate candidate
    :ambiguous? boolean
    :effective-request request
    :filtering filtering-result
    :ranking ranking-result
    :trace {:short ... :full ...}}

   {:outcome :no-winner
    :reason keyword
    :effective-request request
    :filtering filtering-result
    :ranking {:ranked [] :ambiguous? false}
    :trace {:short ... :full ...}}

   v1 no-winner reasons:
   - `:reference-not-found` for explicit/inherit requests with an empty pool
   - `:required-constraints-unsatisfied` when filtering leaves zero survivors"
  [{:keys [catalog system user project request]
    :or   {catalog (catalog-view)}}]
  (let [effective-request (compose-effective-request {:system system
                                                      :user user
                                                      :project project
                                                      :request request})
        filtering         (filter-candidates catalog effective-request)
        ranking           (rank-candidates effective-request (:survivors filtering))
        mode              (:mode effective-request)
        result            (cond
                            (and (empty? (:pool filtering))
                                 (contains? #{:explicit :inherit-session} mode))
                            {:outcome           :no-winner
                             :reason            :reference-not-found
                             :effective-request effective-request
                             :filtering         filtering
                             :ranking           ranking}

                            (empty? (:survivors filtering))
                            {:outcome           :no-winner
                             :reason            :required-constraints-unsatisfied
                             :effective-request effective-request
                             :filtering         filtering
                             :ranking           ranking}

                            :else
                            {:outcome           :ok
                             :candidate         (first (:ranked ranking))
                             :ambiguous?        (:ambiguous? ranking)
                             :effective-request effective-request
                             :filtering         filtering
                             :ranking           ranking})]
    (assoc result
           :trace {:short (short-trace result)
                   :full  (full-trace result)})))
