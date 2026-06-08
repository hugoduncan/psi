(ns psi.agent-session.workflow.routing
  "Deterministic workflow routing parsers for authored workflow markers."
  (:require
   [clojure.string :as str]))

(def ^:private pass-status-prefix "PASS_STATUS:")

(def ^:private known-pass-status->route
  {"REVIEW_COMPLETE" "DONE"
   "ACTIONABLE_FEEDBACK" "REPEAT"
   "IMPLEMENTATION_COMPLETE" "DONE"
   "MORE_WORK_REMAINS" "REPEAT"})

(def ^:private munera-open-task-path-pattern
  #"^munera/open/[0-9]{3,}-[a-z0-9]+(?:-[a-z0-9]+)*$")

(def ^:private proof-sync-routes
  ["COVERAGE_REVIEW" "VALIDATION_RECAPTURE" "BOOKKEEPING_FIXED_POINT"])

(def ^:private validation-capture-routes
  ["IMPLEMENTATION_REPAIR" "TERMINAL_STOP"])

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

(defn- marker-line-classification
  [{:keys [marker-label allowed-routes-set]} line]
  (let [trimmed-left (str/triml line)
        exact-prefix (str marker-label ": ")
        marker-prefix? (str/starts-with? trimmed-left marker-label)
        after-marker-label (if marker-prefix?
                             (subs trimmed-left (count marker-label))
                             "")
        marker-attempt? (or (str/starts-with? after-marker-label ":")
                            (boolean (re-find #"^\s+:" after-marker-label)))]
    (cond
      (not marker-attempt?)
      {:kind :ordinary
       :line line}

      (not= line trimmed-left)
      {:kind :malformed
       :line line
       :reason :leading-whitespace}

      (not (str/starts-with? line exact-prefix))
      {:kind :malformed
       :line line
       :reason :malformed-prefix}

      :else
      (let [raw-route (subs line (count exact-prefix))]
        (cond
          (not (re-matches #"[A-Z_]+" raw-route))
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
    (->> (str/split-lines (or text ""))
         (mapv #(marker-line-classification classification-opts %))
         (remove #(= :ordinary (:kind %)))
         vec)))

(defn- parse-exact-marker-routing
  [{:keys [text marker-label allowed-routes]}]
  (let [candidates (route-marker-candidates {:text text
                                             :marker-label marker-label
                                             :allowed-routes allowed-routes})]
    (cond
      (empty? candidates)
      {:status :error
       :reason :missing-route-marker
       :message (str marker-label " marker missing")
       :details {:text text}}

      (> (count candidates) 1)
      {:status :error
       :reason :ambiguous-route-marker
       :message (str "Multiple " marker-label " marker lines found")
       :details {:text text
                 :route-marker-lines (mapv :line candidates)}}

      :else
      (let [{:keys [kind line route value]} (first candidates)]
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
                     :line line
                     :value value
                     :allowed-routes allowed-routes}}

          :malformed
          {:status :error
           :reason :malformed-route-marker
           :message (str marker-label " marker must start at column 0 with exactly one space after colon, one route token, and no trailing text")
           :details (cond-> {:text text
                             :line line}
                      value (assoc :value value))})))))

(defn parse-proof-sync-disposition-routing
  "Parse a proof-sync final reply into one deterministic route result."
  [text]
  (parse-exact-marker-routing {:text text
                               :marker-label "PROOF_SYNC_ROUTE"
                               :allowed-routes proof-sync-routes}))

(defn parse-validation-capture-disposition-routing
  "Parse a validation-capture final reply into one deterministic route result."
  [text]
  (parse-exact-marker-routing {:text text
                               :marker-label "VALIDATION_CAPTURE_ROUTE"
                               :allowed-routes validation-capture-routes}))
