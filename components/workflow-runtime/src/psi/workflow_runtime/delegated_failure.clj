(ns psi.workflow-runtime.delegated-failure
  "Canonical, bounded diagnostics for failed delegated workflow runs."
  (:require
   [clojure.string :as str]
   [psi.workflow-runtime.statechart :as workflow-statechart]))

(def fallback-message "Delegated workflow failed")

(def ^:private placeholders
  ["[STACKTRACE_REDACTED]"
   "[PATH_REDACTED]"
   "[REDACTED]"
   "[REDACTED_TOKEN]"])

(def ^:private nested-sources
  #{:execution-error :terminal-outcome :fallback})

(def ^:private safe-reason-pattern
  #"[A-Za-z0-9][A-Za-z0-9._-]*(/[A-Za-z0-9][A-Za-z0-9._-]*)?")

(defn nonblank-string?
  [value]
  (and (string? value) (not (str/blank? value))))

(defn code-point-count
  [text]
  (when (string? text)
    (.codePointCount ^String text 0 (.length ^String text))))

(defn- code-point-substring
  [text end]
  (let [end-index (.offsetByCodePoints ^String text 0 end)]
    (subs text 0 end-index)))

(defn safe-reason?
  "True when reason is a bounded keyword safe to render publicly."
  [reason]
  (when (keyword? reason)
    (let [body (if-let [namespace (namespace reason)]
                 (str namespace "/" (name reason))
                 (name reason))]
      (and (<= (code-point-count body) 64)
           (boolean (re-matches safe-reason-pattern body))))))

(defn- whitespace-code-point?
  [code-point]
  (or (Character/isWhitespace code-point)
      (Character/isSpaceChar code-point)))

(defn- remove-controls
  [text]
  (let [builder (StringBuilder.)]
    (loop [index 0]
      (if (>= index (.length ^String text))
        (str builder)
        (let [code-point (.codePointAt ^String text index)]
          (when (or (not (Character/isISOControl code-point))
                    (whitespace-code-point? code-point))
            (.appendCodePoint builder code-point))
          (recur (+ index (Character/charCount code-point))))))))

(defn- left-boundary?
  [text index predicate]
  (or (zero? index)
      (not (predicate (.codePointBefore ^String text index)))))

(defn- unicode-word-or-underscore?
  [code-point]
  (or (= code-point (int \_))
      (Character/isLetterOrDigit code-point)))

(defn- ascii-key-delimiter?
  [code-point]
  (or (<= (int \A) code-point (int \Z))
      (<= (int \a) code-point (int \z))
      (<= (int \0) code-point (int \9))
      (= code-point (int \_))
      (= code-point (int \-))))

(defn- token-character?
  [code-point]
  (or (<= (int \A) code-point (int \Z))
      (<= (int \a) code-point (int \z))
      (<= (int \0) code-point (int \9))
      (contains? #{(int \.) (int \_) (int \~) (int \+) (int \/) (int \-)}
                 code-point)))

(defn- path-left-delimiter?
  [text index]
  (or (zero? index)
      (let [code-point (.codePointBefore ^String text index)]
        (or (whitespace-code-point? code-point)
            (contains? #{(int \() (int \[) (int \{) (int \=) (int \:)
                         (int \,) (int \;)}
                       code-point)))))

(defn- span-match
  [pattern text index]
  (let [match (re-find pattern (subs text index))]
    (if (vector? match) (first match) match)))

(def ^:private stack-frame-pattern
  #"^at[ \t]+[\p{L}\p{N}._$/-]+\([^\s()]*:[0-9]+\)")

(def ^:private credential-pattern
  #"(?i)^[A-Za-z0-9_.-]*(?:token|secret|password|credential|api-key|api_key)[A-Za-z0-9_.-]*[ \t]*(?:=>|=|:)[ \t]*(?:\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'|[^\s,;\)\]\}'\"]+)")

(def ^:private bearer-pattern
  #"(?i)^Bearer[ \t]+[A-Za-z0-9._~+/-]{8,}={0,2}")

(def ^:private prefixed-token-pattern
  #"(?i)^(?:sk-|pk-)[A-Za-z0-9._~+/-]{8,}")

(defn- trim-token-periods
  [token]
  (str/replace token #"\.+$" ""))

(defn- path-end-index
  [text start]
  (loop [index start]
    (if (>= index (.length ^String text))
      index
      (let [code-point (.codePointAt ^String text index)]
        (if (contains? #{(int \space) (int \tab) (int \newline) (int \return)
                         (int \,) (int \;) (int \)) (int \]) (int \})
                         (int \') (int \")}
                       code-point)
          index
          (recur (+ index (Character/charCount code-point))))))))

(defn- trim-path-punctuation
  [path]
  (str/replace path #"[\.:!?]+$" ""))

(defn- absolute-path?
  [path]
  (or (str/starts-with? path "/")
      (str/starts-with? path "~/")
      (str/starts-with? path "./")
      (str/starts-with? path "../")
      (boolean (re-find #"^[A-Za-z]:[\\/]" path))
      (str/starts-with? path "\\\\")))

(defn- secret-bearing-relative-path?
  [path]
  (and (or (str/includes? path "/")
           (str/includes? path "\\"))
       (some (fn [segment]
               (let [segment (str/lower-case segment)]
                 (or (str/includes? segment "secret")
                     (str/includes? segment "token")
                     (str/includes? segment "password")
                     (str/includes? segment "credential")
                     (= segment ".ssh")
                     (= segment "id_rsa"))))
             (str/split path #"[\\/]"))))

(defn- path-span
  [text index]
  (when (path-left-delimiter? text index)
    (let [end (path-end-index text index)
          path (trim-path-punctuation (subs text index end))]
      (when (and (seq path)
                 (or (absolute-path? path)
                     (secret-bearing-relative-path? path)))
        path))))

(defn- redact-spans
  [text]
  (let [builder (StringBuilder.)]
    (loop [index 0]
      (if (>= index (.length ^String text))
        (str builder)
        (let [stack-frame (when (and (left-boundary? text index unicode-word-or-underscore?)
                                     (str/starts-with? (subs text index) "at"))
                            (span-match stack-frame-pattern text index))
              credential (when (left-boundary? text index ascii-key-delimiter?)
                           (span-match credential-pattern text index))
              bearer (when (left-boundary? text index ascii-key-delimiter?)
                       (some-> (span-match bearer-pattern text index)
                               trim-token-periods))
              prefixed-token (when (left-boundary? text index token-character?)
                               (some-> (span-match prefixed-token-pattern text index)
                                       trim-token-periods))
              path (path-span text index)
              [span replacement] (cond
                                   stack-frame [stack-frame "[STACKTRACE_REDACTED]"]
                                   credential [credential "[REDACTED]"]
                                   bearer [bearer "[REDACTED_TOKEN]"]
                                   (seq prefixed-token) [prefixed-token "[REDACTED_TOKEN]"]
                                   path [path "[PATH_REDACTED]"])]
          (if span
            (do
              (.append builder replacement)
              (recur (+ index (.length ^String span))))
            (let [code-point (.codePointAt ^String text index)]
              (.appendCodePoint builder code-point)
              (recur (+ index (Character/charCount code-point))))))))))

(defn- normalize-whitespace
  [text]
  (let [builder (StringBuilder.)]
    (loop [index 0 whitespace? false]
      (if (>= index (.length ^String text))
        (str/trim (str builder))
        (let [code-point (.codePointAt ^String text index)]
          (if (whitespace-code-point? code-point)
            (do
              (when-not whitespace?
                (.append builder " "))
              (recur (+ index (Character/charCount code-point)) true))
            (do
              (.appendCodePoint builder code-point)
              (recur (+ index (Character/charCount code-point)) false))))))))

(defn sanitize-component
  "Remove controls, redact sensitive spans, and normalize whitespace in text."
  [text]
  (when (string? text)
    (-> text remove-controls redact-spans normalize-whitespace)))

(defn actionable?
  [text]
  (and (string? text)
       (let [remaining (reduce #(str/replace %1 %2 "") text placeholders)]
         (loop [index 0]
           (when (< index (.length ^String remaining))
             (let [code-point (.codePointAt ^String remaining index)]
               (or (Character/isLetterOrDigit code-point)
                   (recur (+ index (Character/charCount code-point))))))))))

(defn- valid-step-id?
  [value]
  (nonblank-string? value))

(defn terminal-step-attempt
  "Select a run's deterministic terminal step and attempt without map iteration."
  [workflow-run]
  (let [step-runs (:step-runs workflow-run)
        terminal-step-id (get-in workflow-run [:terminal-outcome :step-id])
        current-step-id (:current-step-id workflow-run)
        fallback-step-id (some (fn [step-id]
                                 (when (= :execution-failed
                                          (:status (last (get-in step-runs [step-id :attempts]))))
                                   step-id))
                               (reverse (workflow-statechart/effective-step-order
                                         (:effective-definition workflow-run))))
        step-id (cond
                  (contains? step-runs terminal-step-id) terminal-step-id
                  (contains? step-runs current-step-id) current-step-id
                  :else fallback-step-id)
        attempts (get-in step-runs [step-id :attempts])
        terminal-attempt-id (get-in workflow-run [:terminal-outcome :attempt-id])
        attempt (or (some #(when (= terminal-attempt-id (:attempt-id %)) %) attempts)
                    (last attempts))]
    {:step-id step-id
     :attempt attempt}))

(defn- selected-location
  [{:keys [step-id attempt]}]
  (cond-> {}
    (valid-step-id? step-id) (assoc :step-id step-id)
    (valid-step-id? (:attempt-id attempt)) (assoc :attempt-id (:attempt-id attempt))))

(defn- valid-terminal-count?
  [value]
  (and (integer? value) (<= 0 value Long/MAX_VALUE)))

(defn- terminal-cause
  [terminal-outcome]
  (when (safe-reason? (:reason terminal-outcome))
    (str "terminal outcome " (:reason terminal-outcome)
         (when (and (= :iteration-limit-reached (:reason terminal-outcome))
                    (valid-terminal-count? (:iteration-count terminal-outcome))
                    (valid-terminal-count? (:max-iterations terminal-outcome)))
           (str " (iteration " (:iteration-count terminal-outcome)
                " of " (:max-iterations terminal-outcome) ")")))))

(defn- recognized-nested-envelope?
  [error]
  (let [failure (:delegate-failure error)]
    (and (map? error)
         (= :delegated-workflow-failed (:reason error))
         (nonblank-string? (:message error))
         (<= (code-point-count (:message error)) 512)
         (map? failure)
         (contains? nested-sources (:source failure))
         (nonblank-string? (:run-id failure))
         (nonblank-string? (:target failure)))))

(defn- nested-cause
  [error]
  (when (recognized-nested-envelope? error)
    (let [failure (:delegate-failure error)]
      (cond-> (select-keys failure [:run-id :target])
        (safe-reason? (:reason failure)) (assoc :reason (:reason failure))
        (valid-step-id? (:step-id failure)) (assoc :step-id (:step-id failure))
        (valid-step-id? (:attempt-id failure)) (assoc :attempt-id (:attempt-id failure))))))

(defn- escape-component
  [component]
  (-> component
      (str/replace "\\" "\\\\")
      (str/replace "'" "\\'")))

(defn- bounded-message
  [message]
  (if (> (code-point-count message) 512)
    (str (code-point-substring message 496) " ... [truncated]")
    message))

(defn- public-message
  [target step cause]
  (bounded-message
   (str "Delegated workflow '" (escape-component target) "' failed"
        (when (actionable? step)
          (str " at step '" (escape-component step) "'"))
        ": " cause)))

(defn delegated-failure
  "Construct the canonical parent-attempt failure envelope for a failed child."
  [delegate-run delegate-run-id target]
  (let [selection (terminal-step-attempt delegate-run)
        error (get-in selection [:attempt :execution-error])
        sanitized-error (when (nonblank-string? (:message error))
                          (sanitize-component (:message error)))
        execution-cause (when (actionable? sanitized-error) sanitized-error)
        terminal-outcome (:terminal-outcome delegate-run)
        terminal-cause-text (terminal-cause terminal-outcome)
        target-text (sanitize-component target)
        step-text (sanitize-component (:step-id selection))
        [source cause reason nested] (cond
                                       execution-cause
                                       [:execution-error execution-cause
                                        (when (safe-reason? (:reason error)) (:reason error))
                                        (nested-cause error)]

                                       terminal-cause-text
                                       [:terminal-outcome terminal-cause-text
                                        (:reason terminal-outcome) nil]

                                       :else
                                       [:fallback nil nil nil])
        actionable-target? (actionable? target-text)
        [source cause reason nested] (if actionable-target?
                                       [source cause reason nested]
                                       [:fallback nil nil nil])
        failure (cond-> (merge {:source source
                                :run-id delegate-run-id
                                :target target}
                               (selected-location selection))
                  reason (assoc :reason reason)
                  nested (assoc :nested-cause nested))]
    {:reason :delegated-workflow-failed
     :message (if cause
                (public-message target-text step-text cause)
                fallback-message)
     :delegate-failure failure}))
