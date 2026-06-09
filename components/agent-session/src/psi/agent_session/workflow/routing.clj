(ns psi.agent-session.workflow.routing
  "Deterministic workflow routing parsers for authored workflow markers."
  (:require
   [clojure.string :as str]))

(def ^:private pass-status-prefix "PASS_STATUS:")

(def ^:private route-token-pattern #"^[A-Z_]+$")

(def ^:private known-pass-status->route
  {"REVIEW_COMPLETE" "DONE"
   "ACTIONABLE_FEEDBACK" "REPEAT"
   "IMPLEMENTATION_COMPLETE" "DONE"
   "MORE_WORK_REMAINS" "REPEAT"})

(def ^:private munera-open-task-path-pattern
  #"^munera/open/[0-9]{3,}-[a-z0-9]+(?:-[a-z0-9]+)*$")

(defn- pass-status-line-value
  [line]
  (when (str/starts-with? line pass-status-prefix)
    (subs line (count pass-status-prefix))))

(defn parse-pass-status-routing
  "Parse a review/implementation final reply into a DONE/REPEAT route."
  [text allowed-statuses]
  (let [lines (str/split-lines (or text ""))
        allowed-statuses-set (when (seq allowed-statuses)
                               (set allowed-statuses))
        status-lines (keep (fn [line]
                             (when-let [raw-value (pass-status-line-value line)]
                               {:line line
                                :raw-value raw-value
                                :trimmed-value (str/trim raw-value)}))
                           lines)]
    (cond
      (empty? status-lines)
      {:status :error
       :reason :missing-pass-status
       :message "PASS_STATUS missing"
       :details {:text text}}

      (> (count status-lines) 1)
      {:status :error
       :reason :ambiguous-pass-status
       :message "Multiple PASS_STATUS lines found"
       :details {:text text
                 :pass-status-lines (mapv :line status-lines)}}

      :else
      (let [{:keys [line raw-value trimmed-value]} (first status-lines)
            route (get known-pass-status->route trimmed-value)
            exact-known? (= raw-value (str " " trimmed-value))
            allowed? (or (nil? allowed-statuses-set)
                         (contains? allowed-statuses-set trimmed-value))]
        (cond
          (and route exact-known? allowed?)
          {:status :ok
           :data route
           :summary route}

          (and route exact-known? (not allowed?))
          {:status :error
           :reason :invalid-pass-status
           :message "PASS_STATUS token is not valid for this workflow step"
           :details {:text text
                     :line line
                     :value trimmed-value
                     :allowed-statuses (vec allowed-statuses)}}

          :else
          {:status :error
           :reason :malformed-pass-status
           :message "PASS_STATUS line must contain exactly one known token"
           :details {:text text
                     :line line
                     :value trimmed-value}})))))

(defn parse-munera-open-task-path-routing
  "Parse an extracted Munera task path into a DONE/REPEAT route."
  [text]
  (let [trimmed (when (string? text) (str/trim text))]
    (if (and trimmed
             (re-matches munera-open-task-path-pattern trimmed))
      {:status :ok
       :data "DONE"
       :summary "DONE"}
      {:status :ok
       :data "REPEAT"
       :summary "REPEAT"
       :details {:reason :invalid-munera-open-task-path
                 :text text}})))

(defn- route-token? [value]
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

(defn parse-exact-marker-routing
  "Parse one exact workflow-owned route marker from text.

   Args must contain string :text, all-caps/underscore :marker-label, and a
   non-empty vector of distinct all-caps/underscore :allowed-routes. Invalid
   args return :invalid-route-marker-args before marker parsing."
  [args]
  (let [errors (exact-marker-routing-arg-errors args)]
    (if (seq errors)
      (invalid-exact-marker-routing-args-result errors)
      (let [{:keys [text marker-label allowed-routes]} args
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
              {:status :ok
               :data route
               :summary route}

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
