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

(def ^:private bare-task-token-pattern
  #"^[0-9]{3,}-[a-z0-9]+(?:-[a-z0-9]+)*$")

(defn normalize-open-task-path
  "Normalize a workflow-input task path/token to a worktree-relative open task
   directory, or nil when it is not parseable (fail-open — DI-4).

   Open-only and anchored (full-string match), reusing the existing open-task
   grammar: a trimmed input that fully matches `munera/open/NNN-slug` is returned
   verbatim; a bare anchored `NNN-slug` token becomes `munera/open/<token>`;
   anything else (free text, a `munera/closed/...` path, a partial/substring
   match) yields nil so the gate reads no content and proceeds."
  [task-path]
  (when (string? task-path)
    (let [trimmed (str/trim task-path)]
      (cond
        (re-matches munera-open-task-path-pattern trimmed) trimmed
        (re-matches bare-task-token-pattern trimmed) (str "munera/open/" trimmed)
        :else nil))))

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

(def ^:private review-feedback-pass-statuses
  ["ACTIONABLE_FEEDBACK" "REVIEW_COMPLETE"])

(defn- parse-review-feedback-reply
  [text]
  (if (string? text)
    (parse-pass-status-routing text review-feedback-pass-statuses)
    {:status :error
     :reason :non-string-pass-feedback
     :message "workflow/pass-feedback-routing reply must be a string"
     :details {:text text}}))

(defn parse-pass-feedback-routing
  "Parse a pass-level set of review replies into a DONE/REPEAT route.

   At least one reply must be supplied. Every supplied reply must contain exactly
   one PASS_STATUS line with one of ACTIONABLE_FEEDBACK or REVIEW_COMPLETE. The
   pass repeats when any reply has actionable feedback and completes only when
   all replies are complete. Invalid replies fail before routing so review
   workflows do not silently treat malformed feedback as complete."
  [args]
  (let [entries (sort-by (comp pr-str key) args)
        parsed (mapv (fn [[feedback-key text]]
                       [feedback-key (parse-review-feedback-reply text)])
                     entries)
        validation-failures (->> parsed
                                 (keep (fn [[feedback-key result]]
                                         (when (= :error (:status result))
                                           [feedback-key result])))
                                 (into {}))]
    (cond
      (empty? entries)
      {:status :error
       :reason :invalid-pass-feedback
       :message "workflow/pass-feedback-routing requires at least one reply"
       :details {:validation-failures
                 {:feedback-inputs
                  {:status :error
                   :reason :empty-pass-feedback
                   :message "workflow/pass-feedback-routing requires at least one reply"
                   :details {:args args}}}}}

      (seq validation-failures)
      {:status :error
       :reason :invalid-pass-feedback
       :message "workflow/pass-feedback-routing replies are invalid"
       :details {:validation-failures validation-failures}}

      :else
      (let [actionable-keys (->> parsed
                                 (keep (fn [[feedback-key result]]
                                         (when (= "REPEAT" (:data result))
                                           feedback-key)))
                                 vec)
            route (if (seq actionable-keys) "REPEAT" "DONE")]
        {:status :ok
         :data route
         :summary route
         :details {:actionable-keys actionable-keys}}))))

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

(def ^:private unchecked-checkbox-prefix "- [ ]")

(defn- open-scope-question-concern
  "Return the trimmed concern substring of an open (unchecked) scope-question
   line, or nil when the line is not an open item for `marker`.

   An open item is a markdown checklist line whose left-trimmed form starts with
   the unchecked checkbox `- [ ]`, then (after optional whitespace) the authored
   `marker`. Checked items (`- [x]`/`- [X]`) do not start with `- [ ]`, so they
   never match. The returned concern is the text after the marker, trimmed."
  [marker line]
  (let [trimmed (str/triml line)]
    (when (str/starts-with? trimmed unchecked-checkbox-prefix)
      (let [after-box (str/triml (subs trimmed (count unchecked-checkbox-prefix)))]
        (when (str/starts-with? after-box marker)
          (str/trim (subs after-box (count marker))))))))

(defn parse-scope-question-gate
  "Deterministically route on unchecked scope-question items in artifact content.

   Scans `content` (a string or nil) for open (unchecked) checklist items whose
   prose begins with `marker` (e.g. \"SCOPE_QUESTION:\"). When one or more open
   items remain, routes to `open-route` and returns the trimmed concern of each
   open item under `:details {:open-questions [...]}`. When `content` is nil,
   empty, or has no open items (including only-checked items), routes to
   `proceed-route`. No IO and independent of any review-convergence signal."
  [content marker proceed-route open-route]
  (let [open-questions (->> (str/split-lines (or content ""))
                            (keep #(open-scope-question-concern marker %))
                            vec)]
    (if (seq open-questions)
      {:status :ok
       :data open-route
       :summary open-route
       :details {:open-questions open-questions}}
      {:status :ok
       :data proceed-route
       :summary proceed-route})))

(defn- complete-authored-blocks
  [content start-delimiter field-prefixes end-delimiter]
  (let [lines (str/split-lines (or content ""))]
    (loop [remaining lines
           blocks []]
      (if-let [line (first remaining)]
        (if (= start-delimiter line)
          (let [field-lines (take (count field-prefixes) (rest remaining))
                end-line (nth remaining (inc (count field-prefixes)) nil)
                values (mapv (fn [prefix field-line]
                               (when (and field-line
                                          (str/starts-with? field-line prefix))
                                 (let [value (subs field-line (count prefix))]
                                   (when (seq (str/trim value)) value))))
                             field-prefixes field-lines)
                complete? (and (= end-delimiter end-line)
                               (= (count field-prefixes) (count values))
                               (every? some? values))]
            (recur (rest remaining)
                   (cond-> blocks
                     complete? (conj (zipmap field-prefixes values)))))
          (recur (rest remaining) blocks))
        blocks))))

(defn parse-final-complete-block
  "Return the last syntactically complete authored block in `content`.

   A complete block has one start delimiter, exactly the supplied field prefixes
   once each in order, and one end delimiter. Malformed and incomplete blocks
   are ignored so a later complete record remains authoritative. Returns nil
   when no complete block exists."
  [content start-delimiter field-prefixes end-delimiter]
  (peek (complete-authored-blocks content start-delimiter field-prefixes end-delimiter)))

(defn final-complete-block-appended?
  "True when `content` preserves `before-content` and appends exactly one complete
   authored block after it. This proves a blocked pass produced one fresh record
   instead of reusing an earlier record or appending multiple records."
  [before-content content start-delimiter field-prefixes end-delimiter]
  (and (string? before-content)
       (string? content)
       (str/starts-with? content before-content)
       (= 1 (count (complete-authored-blocks
                    (subs content (count before-content))
                    start-delimiter field-prefixes end-delimiter)))))

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
    (->> labels-by-route
         (mapcat (fn [[route labels]]
                   (concat
                    (when-not (contains? (set allowed-routes) route)
                      [{:field field
                        :reason :unsupported-field-labels-route
                        :value route}])
                    (when-not (and (vector? labels) (every? route-token? labels))
                      [{:field field
                        :reason :invalid-field-labels
                        :route route
                        :value labels}]))))
         vec)))

(defn- required-route-fields-errors
  [{:keys [allowed-routes required-fields-by-route
           required-field-labels-by-route required-fields-source-text]}]
  (vec
   (concat
    (cond
      (and required-field-labels-by-route
           (not (string? required-fields-source-text)))
      [{:field :required-fields-source-text
        :reason :non-string-required-fields-source-text
        :value required-fields-source-text}]

      (and required-field-labels-by-route
           (not (map? required-field-labels-by-route)))
      [{:field :required-field-labels-by-route
        :reason :non-map-required-field-labels-by-route
        :value required-field-labels-by-route}]

      required-field-labels-by-route
      (mapcat (fn [[route labels]]
                (concat
                 (when-not (contains? (set allowed-routes) route)
                   [{:field :required-field-labels-by-route
                     :reason :unsupported-required-field-labels-route
                     :value route}])
                 (when-not (and (vector? labels) (every? route-token? labels))
                   [{:field :required-field-labels-by-route
                     :reason :invalid-required-field-labels
                     :route route
                     :value labels}])))
              required-field-labels-by-route)

      :else
      [])
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
                 (when-not (contains? (set allowed-routes) route)
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
              required-fields-by-route)))))

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
        prefix (str label ": ")]
    (when (and (= 1 (count candidates))
               (str/starts-with? (first candidates) prefix))
      (subs (first candidates) (count prefix)))))

(defn- source-required-fields
  [text labels]
  (into {} (keep (fn [label]
                   (when-let [value (exact-field-value text label)]
                     [label value])))
        labels))

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
                             (route-field-labels-errors
                              (:allowed-routes args)
                              :forbidden-field-labels-by-route
                              (:forbidden-field-labels-by-route args))))]
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
                    other-route-labels (->> (vals (or required-fields-by-route {}))
                                            (mapcat keys)
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
