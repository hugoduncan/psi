(ns psi.agent-session.workflow.exact-marker-routing
  "Deterministic parser for authored workflow route markers and route fields."
  (:require
   [clojure.string :as str]))

(def ^:private route-token-pattern #"^[A-Z_]+$")

(defn- route-token?
  [value]
  (and (string? value)
       (boolean (re-matches route-token-pattern value))))

(defn- marker-label-errors
  [args]
  (cond
    (not (contains? args :marker-label))
    [{:field :marker-label :reason :missing-marker-label}]

    (not (string? (:marker-label args)))
    [{:field :marker-label
      :reason :non-string-marker-label
      :value (:marker-label args)}]

    (not (route-token? (:marker-label args)))
    [{:field :marker-label
      :reason :invalid-marker-label
      :value (:marker-label args)}]

    :else []))

(defn- text-errors
  [args]
  (cond
    (not (contains? args :text))
    [{:field :text :reason :missing-text}]

    (not (string? (:text args)))
    [{:field :text
      :reason :non-string-text
      :value (:text args)}]

    :else []))

(defn- invalid-allowed-route-entry-errors
  [allowed-routes]
  (->> allowed-routes
       (map-indexed (fn [idx route]
                      (when-not (route-token? route)
                        {:field :allowed-routes
                         :reason :invalid-allowed-route
                         :index idx
                         :value route})))
       (remove nil?)
       vec))

(defn- duplicate-route-errors
  [allowed-routes]
  (->> allowed-routes
       (map-indexed vector)
       (group-by second)
       (keep (fn [[route indexed-routes]]
               (let [indices (mapv first indexed-routes)]
                 (when (and (route-token? route)
                            (> (count indices) 1))
                   {:field :allowed-routes
                    :reason :duplicate-allowed-route
                    :value route
                    :indices indices}))))
       vec))

(defn- allowed-routes-errors
  [args]
  (cond
    (not (contains? args :allowed-routes))
    [{:field :allowed-routes :reason :missing-allowed-routes}]

    (not (vector? (:allowed-routes args)))
    [{:field :allowed-routes
      :reason :non-vector-allowed-routes
      :value (:allowed-routes args)}]

    (empty? (:allowed-routes args))
    [{:field :allowed-routes :reason :empty-allowed-routes}]

    :else
    (into (invalid-allowed-route-entry-errors (:allowed-routes args))
          (duplicate-route-errors (:allowed-routes args)))))

(defn- exact-marker-routing-arg-errors
  [args]
  (vec (concat (text-errors args)
               (marker-label-errors args)
               (allowed-routes-errors args))))

(defn- invalid-exact-marker-routing-args-result
  [errors]
  {:status :error
   :reason :invalid-route-marker-args
   :message "workflow/exact-marker-routing args are invalid"
   :details {:errors errors}})

(defn- marker-line-classification
  [{:keys [marker-label allowed-routes-set]} line]
  (let [trimmed-left (str/triml line)
        marker-prefix (str marker-label ": ")
        starts-with-label? (str/starts-with? trimmed-left marker-label)
        after-marker-label (if starts-with-label?
                             (subs trimmed-left (count marker-label))
                             "")
        whitespace-before-colon? (boolean (re-find #"^\s+:" after-marker-label))
        marker-attempt? (and starts-with-label?
                             (or (str/starts-with? after-marker-label ":")
                                 whitespace-before-colon?))]
    (cond
      (not marker-attempt?)
      {:kind :ordinary
       :line line}

      (not= line trimmed-left)
      {:kind :malformed
       :line line
       :reason :leading-whitespace}

      whitespace-before-colon?
      {:kind :malformed
       :line line
       :reason :whitespace-before-colon}

      (not (str/starts-with? line marker-prefix))
      {:kind :malformed
       :line line
       :reason :missing-space-after-colon}

      :else
      (let [raw-route (subs line (count marker-prefix))]
        (cond
          (not (route-token? raw-route))
          {:kind :malformed
           :line line
           :reason :malformed-route-token
           :value raw-route}

          (not (contains? allowed-routes-set raw-route))
          {:kind :unsupported
           :line line
           :value raw-route}

          :else
          {:kind :exact
           :line line
           :route raw-route})))))

(defn- route-marker-candidates
  [{:keys [text marker-label allowed-routes]}]
  (let [classification-opts {:marker-label marker-label
                             :allowed-routes-set (set allowed-routes)}]
    (->> (str/split-lines text)
         (mapv #(marker-line-classification classification-opts %))
         (remove #(= :ordinary (:kind %)))
         vec)))

(defn- route-field-labels-errors
  [allowed-routes field labels-by-route]
  (cond
    (nil? labels-by-route)
    []

    (not (map? labels-by-route))
    [{:field field
      :reason :non-map-field-labels-by-route
      :value labels-by-route}]

    :else
    (let [allowed-routes-set (when (vector? allowed-routes)
                               (set allowed-routes))]
      (->> labels-by-route
           (mapcat (fn [[route labels]]
                     (concat
                      (when (and allowed-routes-set
                                 (not (contains? allowed-routes-set route)))
                        [{:field field
                          :reason :unsupported-field-labels-route
                          :value route}])
                      (when-not (and (vector? labels) (every? route-token? labels))
                        [{:field field
                          :reason :invalid-field-labels
                          :route route
                          :value labels}])
                      (when (vector? labels)
                        (->> labels
                             (map-indexed vector)
                             (group-by second)
                             (keep (fn [[label indexed-labels]]
                                     (let [indices (mapv first indexed-labels)]
                                       (when (> (count indices) 1)
                                         {:field field
                                          :reason :duplicate-field-label
                                          :route route
                                          :value label
                                          :indices indices})))))))))
           vec))))

(defn- duplicate-required-field-label-errors
  [route labels]
  (when (vector? labels)
    (->> labels
         (map-indexed vector)
         (group-by second)
         (keep (fn [[label indexed-labels]]
                 (let [indices (mapv first indexed-labels)]
                   (when (> (count indices) 1)
                     {:field :required-field-labels-by-route
                      :reason :duplicate-required-field-label
                      :route route
                      :value label
                      :indices indices})))))))

(defn- required-route-fields-errors
  [{:keys [allowed-routes required-fields-by-route
           required-field-labels-by-route required-fields-source-text]}]
  (let [allowed-routes-set (when (vector? allowed-routes)
                             (set allowed-routes))
        source-fields-requested? (and (map? required-field-labels-by-route)
                                      (some seq (vals required-field-labels-by-route)))
        source-errors
        (cond
          (and source-fields-requested?
               (not (string? required-fields-source-text)))
          [{:field :required-fields-source-text
            :reason :non-string-required-fields-source-text
            :value required-fields-source-text}]

          (and (some? required-field-labels-by-route)
               (not (map? required-field-labels-by-route)))
          [{:field :required-field-labels-by-route
            :reason :non-map-required-field-labels-by-route
            :value required-field-labels-by-route}]

          (map? required-field-labels-by-route)
          (mapcat (fn [[route labels]]
                    (concat
                     (when (and allowed-routes-set
                                (not (contains? allowed-routes-set route)))
                       [{:field :required-field-labels-by-route
                         :reason :unsupported-required-field-labels-route
                         :value route}])
                     (when-not (and (vector? labels) (every? route-token? labels))
                       [{:field :required-field-labels-by-route
                         :reason :invalid-required-field-labels
                         :route route
                         :value labels}])
                     (duplicate-required-field-label-errors route labels)))
                  required-field-labels-by-route)

          :else
          [])
        direct-errors
        (cond
          (nil? required-fields-by-route)
          []

          (not (map? required-fields-by-route))
          [{:field :required-fields-by-route
            :reason :non-map-required-fields-by-route
            :value required-fields-by-route}]

          :else
          (mapcat (fn [[route fields]]
                    (concat
                     (when (and allowed-routes-set
                                (not (contains? allowed-routes-set route)))
                       [{:field :required-fields-by-route
                         :reason :unsupported-required-fields-route
                         :value route}])
                     (when-not (map? fields)
                       [{:field :required-fields-by-route
                         :reason :non-map-required-fields
                         :route route
                         :value fields}])
                     (when (map? fields)
                       (mapcat (fn [[label expected-value]]
                                 (cond-> []
                                   (not (route-token? label))
                                   (conj {:field :required-fields-by-route
                                          :reason :invalid-required-field-label
                                          :route route
                                          :value label})

                                   (or (not (string? expected-value))
                                       (str/blank? expected-value))
                                   (conj {:field :required-fields-by-route
                                          :reason :invalid-required-field-value
                                          :route route
                                          :label label
                                          :value expected-value})))
                               fields))))
                  required-fields-by-route))]
    (vec (concat source-errors direct-errors))))

(defn- required-field-candidates
  [text label]
  (let [prefix (str label ":")]
    (->> (str/split-lines text)
         (filter (fn [line]
                   (let [trimmed-left (str/triml line)]
                     (or (str/starts-with? trimmed-left prefix)
                         (boolean (re-find (re-pattern
                                            (str "^" (java.util.regex.Pattern/quote label)
                                                 "\\s+:"))
                                           trimmed-left))))))
         vec)))

(defn- exact-field-value
  [text label]
  (let [candidates (required-field-candidates text label)
        prefix (str label ": ")
        value (when (and (= 1 (count candidates))
                         (str/starts-with? (first candidates) prefix))
                (subs (first candidates) (count prefix)))]
    (when-not (str/blank? value)
      value)))

(defn- source-required-fields
  [text labels]
  (into {} (keep (fn [label]
                   (when-let [value (exact-field-value text label)]
                     [label value])))
        labels))

(defn- conflicting-required-route-fields-errors
  [{:keys [required-fields-by-route required-field-labels-by-route
           required-fields-source-text]}]
  (if (and (map? required-fields-by-route)
           (map? required-field-labels-by-route)
           (string? required-fields-source-text))
    (->> required-field-labels-by-route
         (mapcat (fn [[route source-labels]]
                   (let [direct-fields (get required-fields-by-route route)]
                     (when (and (map? direct-fields) (vector? source-labels))
                       (keep (fn [label]
                               (let [direct-value (get direct-fields label)]
                                 (when (and (route-token? label)
                                            (string? direct-value)
                                            (not (str/blank? direct-value)))
                                   (let [source-value (exact-field-value
                                                       required-fields-source-text
                                                       label)]
                                     (when (and source-value
                                                (not= direct-value source-value))
                                       {:field :required-fields-by-route
                                        :reason :conflicting-required-field-sources
                                        :route route
                                        :label label
                                        :direct-value direct-value
                                        :source-value source-value})))))
                             source-labels)))))
         vec)
    []))

(defn- required-and-forbidden-route-fields-errors
  [{:keys [required-fields-by-route required-field-labels-by-route
           forbidden-field-labels-by-route]}]
  (if (map? forbidden-field-labels-by-route)
    (->> forbidden-field-labels-by-route
         (mapcat (fn [[route forbidden-labels]]
                   (when (vector? forbidden-labels)
                     (let [direct-fields (get required-fields-by-route route)
                           direct-labels (if (map? direct-fields)
                                           (keys direct-fields)
                                           [])
                           source-labels (get required-field-labels-by-route route [])
                           required-labels (concat direct-labels
                                                   (if (vector? source-labels)
                                                     source-labels
                                                     []))]
                       (->> required-labels
                            (filter route-token?)
                            distinct
                            (keep (fn [label]
                                    (when (some #{label} forbidden-labels)
                                      {:field :forbidden-field-labels-by-route
                                       :reason :required-and-forbidden-route-field
                                       :route route
                                       :label label}))))))))
         vec)
    []))

(defn- marker-and-route-fields-errors
  [{:keys [marker-label required-fields-by-route required-field-labels-by-route
           forbidden-field-labels-by-route]}]
  (let [direct-overlaps (when (map? required-fields-by-route)
                          (for [[route fields] required-fields-by-route
                                :when (and (map? fields)
                                           (contains? fields marker-label))]
                            {:field :required-fields-by-route
                             :reason :marker-label-route-field
                             :route route
                             :label marker-label}))
        source-overlaps (when (map? required-field-labels-by-route)
                          (for [[route labels] required-field-labels-by-route
                                :when (and (vector? labels)
                                           (some #{marker-label} labels))]
                            {:field :required-field-labels-by-route
                             :reason :marker-label-route-field
                             :route route
                             :label marker-label}))
        forbidden-overlaps (when (map? forbidden-field-labels-by-route)
                             (for [[route labels] forbidden-field-labels-by-route
                                   :when (and (vector? labels)
                                              (some #{marker-label} labels))]
                               {:field :forbidden-field-labels-by-route
                                :reason :marker-label-route-field
                                :route route
                                :label marker-label}))]
    (vec (concat direct-overlaps source-overlaps forbidden-overlaps))))

(defn- validate-required-route-fields
  [text required-fields forbidden-labels]
  (if-let [unexpected-label (some (fn [label]
                                    (when (seq (required-field-candidates text label))
                                      label))
                                  forbidden-labels)]
    {:status :error
     :reason :unexpected-route-field
     :message (str unexpected-label " field is not valid for this route")
     :details {:text text :field-label unexpected-label}}
    (reduce-kv
     (fn [result label expected-value]
       (if (= :error (:status result))
         (reduced result)
         (let [candidates (required-field-candidates text label)
               expected-line (str label ": " expected-value)]
           (cond
             (empty? candidates)
             (reduced {:status :error
                       :reason :missing-route-field
                       :message (str label " field missing")
                       :details {:text text :field-label label}})

             (> (count candidates) 1)
             (reduced {:status :error
                       :reason :ambiguous-route-field
                       :message (str "Multiple " label " field lines found")
                       :details {:text text
                                 :field-label label
                                 :field-lines candidates}})

             (not= expected-line (first candidates))
             (reduced {:status :error
                       :reason :mismatched-route-field
                       :message (str label " field does not match the required value")
                       :details {:text text
                                 :field-label label
                                 :line (first candidates)
                                 :expected-line expected-line}})

             :else
             (assoc-in result [:details :fields label] expected-value)))))
     {:status :ok}
     required-fields)))

(defn parse-exact-marker-routing
  "Parse one exact workflow-owned route marker from text.

   Args must contain string :text, all-caps/underscore :marker-label, and a
   non-empty vector of distinct all-caps/underscore :allowed-routes. Invalid
   args return :invalid-route-marker-args before marker parsing."
  [args]
  (let [errors (into (exact-marker-routing-arg-errors args)
                     (concat (required-route-fields-errors args)
                             (conflicting-required-route-fields-errors args)
                             (route-field-labels-errors
                              (:allowed-routes args)
                              :forbidden-field-labels-by-route
                              (:forbidden-field-labels-by-route args))
                             (required-and-forbidden-route-fields-errors args)
                             (marker-and-route-fields-errors args)))]
    (if (seq errors)
      (invalid-exact-marker-routing-args-result errors)
      (let [{:keys [text marker-label allowed-routes required-fields-by-route
                    required-field-labels-by-route required-fields-source-text
                    forbidden-field-labels-by-route]} args
            candidates (route-marker-candidates args)]
        (cond
          (empty? candidates)
          {:status :error
           :reason :missing-route-marker
           :message (str marker-label " marker missing")
           :details {:text text
                     :marker-label marker-label}}

          (> (count candidates) 1)
          {:status :error
           :reason :ambiguous-route-marker
           :message (str "Multiple " marker-label " marker lines found")
           :details {:text text
                     :marker-label marker-label
                     :route-marker-lines (mapv :line candidates)
                     :route-marker-candidates candidates}}

          :else
          (let [{:keys [kind line route value reason]} (first candidates)]
            (case kind
              :exact
              (let [source-labels (get required-field-labels-by-route route [])
                    source-fields (source-required-fields required-fields-source-text source-labels)
                    required-fields (merge (get required-fields-by-route route {}) source-fields)
                    required-labels-by-route
                    (merge-with into
                                (update-vals (or required-fields-by-route {})
                                             (comp vec keys))
                                (or required-field-labels-by-route {}))
                    other-route-labels (->> (vals required-labels-by-route)
                                            (mapcat identity)
                                            (remove #(contains? required-fields %))
                                            set)
                    forbidden-labels (into other-route-labels
                                           (get forbidden-field-labels-by-route route []))
                    field-result (if (= (count source-labels) (count source-fields))
                                   (validate-required-route-fields text required-fields
                                                                   forbidden-labels)
                                   {:status :error
                                    :reason :invalid-required-fields-source
                                    :message "Required route fields source is missing exact fields"
                                    :details {:field-labels source-labels}})]
                (if (= :error (:status field-result))
                  field-result
                  (cond-> {:status :ok
                           :data route
                           :summary route}
                    (:details field-result) (assoc :details (:details field-result)))))

              :unsupported
              {:status :error
               :reason :unsupported-route-marker
               :message (str marker-label " route token is not supported")
               :details {:text text
                         :marker-label marker-label
                         :line line
                         :value value
                         :allowed-routes allowed-routes}}

              :malformed
              {:status :error
               :reason :malformed-route-marker
               :message (str marker-label " marker must start at column 0 with exactly one space after colon, one route token, and no trailing text")
               :details (cond-> {:text text
                                 :marker-label marker-label
                                 :line line
                                 :reason reason}
                          value (assoc :value value))})))))))
