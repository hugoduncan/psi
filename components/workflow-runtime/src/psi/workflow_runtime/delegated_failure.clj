(ns psi.workflow-runtime.delegated-failure
  "Canonical, bounded diagnostics for failed delegated workflow runs."
  (:require
   [clojure.string :as str]
   [psi.workflow-runtime.statechart :as workflow-statechart]))

(def fallback-message "Delegated workflow failed")

(def ^:private max-component-code-points 512)

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
  (boolean
   (when (keyword? reason)
     (let [body (if-let [namespace (namespace reason)]
                  (str namespace "/" (name reason))
                  (name reason))]
       (and (<= (code-point-count body) 64)
            (re-matches safe-reason-pattern body))))))

(defn- whitespace-code-point?
  [code-point]
  (or (= code-point 0x0085)
      (Character/isWhitespace code-point)
      (Character/isSpaceChar code-point)))

(defn- removable-control?
  [code-point]
  (and (Character/isISOControl code-point)
       (not (whitespace-code-point? code-point))))

(defn- skip-removable-controls
  [text start]
  (loop [index start]
    (if (< index (.length ^String text))
      (let [code-point (.codePointAt ^String text index)]
        (if (removable-control? code-point)
          (recur (+ index (Character/charCount code-point)))
          index))
      index)))

(defn- previous-visible-code-point
  [text start]
  (loop [index start]
    (when (pos? index)
      (let [code-point (.codePointBefore ^String text index)
            previous-index (- index (Character/charCount code-point))]
        (if (removable-control? code-point)
          (recur previous-index)
          code-point)))))

(defn- next-visible-index
  [text index]
  (let [code-point (.codePointAt ^String text index)]
    (skip-removable-controls
     text (+ index (Character/charCount code-point)))))

(defn- left-boundary?
  [text index predicate]
  (if-let [code-point (previous-visible-code-point text index)]
    (not (predicate code-point))
    true))

(defn- visible-region-matches-ignore-case?
  [text start literal]
  (loop [index start
         literal-index 0]
    (if (= literal-index (count literal))
      index
      (let [index (skip-removable-controls text index)]
        (when (and (< index (.length ^String text))
                   (= (Character/toLowerCase (int (.charAt ^String text index)))
                      (Character/toLowerCase (int (.charAt ^String literal literal-index)))))
          (recur (inc index) (inc literal-index)))))))

(defn- visible-starts-with-end
  [text start literal]
  (loop [index start
         literal-index 0]
    (if (= literal-index (count literal))
      index
      (let [index (skip-removable-controls text index)]
        (when (and (< index (.length ^String text))
                   (= (.charAt ^String text index)
                      (.charAt ^String literal literal-index)))
          (recur (inc index) (inc literal-index)))))))

(defn- visible-character-count
  [text start end]
  (loop [index (skip-removable-controls text start)
         count 0]
    (if (>= index end)
      count
      (recur (next-visible-index text index) (inc count)))))

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
  (if-let [code-point (previous-visible-code-point text index)]
    (or (whitespace-code-point? code-point)
        (contains? #{(int \() (int \[) (int \{) (int \=) (int \:)
                     (int \,) (int \;)}
                   code-point))
    true))

(defn- stack-symbol-code-point?
  [code-point]
  (or (Character/isLetterOrDigit code-point)
      (contains? #{(int \.) (int \_) (int \$) (int \/) (int \-)}
                 code-point)))

(defn- scan-visible-while
  [text start predicate]
  (loop [index (skip-removable-controls text start)]
    (if (and (< index (.length ^String text))
             (predicate (.codePointAt ^String text index)))
      (recur (next-visible-index text index))
      index)))

(defn- stack-frame-end
  [text start]
  (when-let [after-at (visible-starts-with-end text start "at")]
    (let [space-end (scan-visible-while text after-at #{(int \space) (int \tab)})]
      (when (> space-end (skip-removable-controls text after-at))
        (let [symbol-start space-end
              symbol-end (scan-visible-while text symbol-start stack-symbol-code-point?)]
          (when-let [location-start (and (> symbol-end symbol-start)
                                         (visible-starts-with-end text symbol-end "("))]
            (loop [index (skip-removable-controls text location-start)
                   colon? false
                   digit-after-colon? false]
              (when (< index (.length ^String text))
                (let [code-point (.codePointAt ^String text index)]
                  (cond
                    (= code-point 41)
                    (when (and colon? digit-after-colon?)
                      (next-visible-index text index))

                    (or (whitespace-code-point? code-point)
                        (= code-point 40)) nil

                    (= code-point (int \:))
                    (recur (next-visible-index text index) true false)

                    :else
                    (recur (next-visible-index text index)
                           (and colon? (Character/isDigit code-point))
                           (and colon? (Character/isDigit code-point)))))))))))))

(def ^:private credential-key-fragments
  ["token" "secret" "password" "credential" "api-key" "api_key"])

(defn- credential-key-character?
  [character]
  (let [code-point (int character)]
    (or (<= (int \A) code-point (int \Z))
        (<= (int \a) code-point (int \z))
        (<= (int \0) code-point (int \9))
        (contains? #{(int \_) (int \.) (int \-)} code-point))))

(defn- credential-key-end
  [text start]
  (loop [index start]
    (let [index (skip-removable-controls text index)]
      (if (and (< index (.length ^String text))
               (credential-key-character? (.charAt ^String text index)))
        (recur (inc index))
        index))))

(defn- region-contains-ignore-case?
  [text start end fragment]
  (loop [index (skip-removable-controls text start)]
    (when (< index end)
      (let [match-end (visible-region-matches-ignore-case? text index fragment)]
        (or (when (and match-end (<= match-end end)) match-end)
            (recur (next-visible-index text index)))))))

(defn- credential-key?
  [text start end]
  (some #(region-contains-ignore-case? text start end %)
        credential-key-fragments))

(defn- skip-ascii-space
  [text start]
  (loop [index (skip-removable-controls text start)]
    (if (and (< index (.length ^String text))
             (contains? #{\space \tab} (.charAt ^String text index)))
      (recur (skip-removable-controls text (inc index)))
      index)))

(defn- separator-end
  [text index]
  (or (visible-starts-with-end text index "=>")
      (visible-starts-with-end text index "=")
      (visible-starts-with-end text index ":")))

(defn- quote-end-scanner
  [text quote]
  ;; Credential candidates arrive left-to-right, so a constant-size cursor can
  ;; find each requested closing quote while traversing the input at most once.
  (let [cursor (int-array 1)
        backslashes (int-array 1)]
    (fn [opening-index]
      (loop [index (skip-removable-controls text (aget cursor 0))
             backslash-count (aget backslashes 0)]
        (if (>= index (.length ^String text))
          (do
            (aset-int cursor 0 index)
            (aset-int backslashes 0 backslash-count)
            -1)
          (let [character (.charAt ^String text index)
                unescaped-closing? (and (> index opening-index)
                                        (= quote character)
                                        (even? backslash-count))
                next-backslash-count (if (= \\ character)
                                       (inc backslash-count)
                                       0)
                next-index (skip-removable-controls text (inc index))]
            (if unescaped-closing?
              (do
                (aset-int cursor 0 next-index)
                (aset-int backslashes 0 next-backslash-count)
                index)
              (recur next-index next-backslash-count))))))))

(defn- unquoted-credential-end
  [text start]
  (loop [index (skip-removable-controls text start)]
    (if (>= index (.length ^String text))
      index
      (let [code-point (.codePointAt ^String text index)]
        (if (or (whitespace-code-point? code-point)
                (contains? #{(int \,) (int \;) (int \)) (int \]) (int \})}
                           code-point))
          index
          (recur (next-visible-index text index)))))))

(defn- credential-span
  [text start key-end quote-end-scanners]
  (when (credential-key? text start key-end)
    (let [separator-start (skip-ascii-space text key-end)]
      (when-let [separator-end (separator-end text separator-start)]
        (let [value-start (skip-ascii-space text separator-end)]
          (when (< value-start (.length ^String text))
            (let [quote (.charAt ^String text value-start)]
              (if (contains? #{\' \"} quote)
                (let [quote-end ((get quote-end-scanners quote) value-start)]
                  (when (pos? (visible-character-count text (inc value-start) quote-end))
                    (inc quote-end)))
                (let [value-end (unquoted-credential-end text value-start)]
                  (when (> value-end value-start)
                    value-end))))))))))

(defn- token-run-end
  [text start]
  (loop [index (skip-removable-controls text start)]
    (if (and (< index (.length ^String text))
             (token-character? (int (.charAt ^String text index))))
      (recur (skip-removable-controls text (inc index)))
      index)))

(defn- trim-period-end
  [text start end]
  (loop [end end]
    (let [period-index (loop [index end]
                         (when (pos? index)
                           (let [code-point (.codePointBefore ^String text index)
                                 previous-index (- index (Character/charCount code-point))]
                             (if (removable-control? code-point)
                               (recur previous-index)
                               (when (= code-point (int \.)) previous-index)))))]
      (if (and period-index (>= period-index start))
        (recur period-index)
        end))))

(defn- bearer-token-end
  [text index]
  (when-let [after-bearer (visible-region-matches-ignore-case? text index "Bearer")]
    (let [token-start (skip-ascii-space text after-bearer)]
      (when (> token-start (skip-removable-controls text after-bearer))
        (let [run-end (token-run-end text token-start)
              padding-end (loop [padding-end (skip-removable-controls text run-end)
                                 padding-count 0]
                            (if (and (< padding-end (.length ^String text))
                                     (< padding-count 2)
                                     (= \= (.charAt ^String text padding-end)))
                              (recur (skip-removable-controls text (inc padding-end))
                                     (inc padding-count))
                              padding-end))
              token-end (trim-period-end text token-start run-end)]
          (when (>= (visible-character-count text token-start token-end) 8)
            (if (> padding-end run-end)
              padding-end
              token-end)))))))

(defn- prefixed-token-end
  [text index]
  (when-let [token-start (or (visible-region-matches-ignore-case? text index "sk-")
                             (visible-region-matches-ignore-case? text index "pk-"))]
    (let [token-start token-start
          run-end (token-run-end text token-start)
          token-end (trim-period-end text token-start run-end)]
      (when (>= (visible-character-count text token-start token-end) 8)
        token-end))))

(defn- path-end-index
  [text start]
  (loop [index (skip-removable-controls text start)]
    (if (>= index (.length ^String text))
      index
      (let [code-point (.codePointAt ^String text index)]
        (if (or (whitespace-code-point? code-point)
                (contains? #{(int \,) (int \;) (int \)) (int \]) (int \})
                             (int \') (int \")}
                           code-point))
          index
          (recur (next-visible-index text index)))))))

(defn- trim-path-punctuation-end
  [text start end]
  (loop [end end]
    (let [punctuation-index (loop [index end]
                              (when (> index start)
                                (let [code-point (.codePointBefore ^String text index)
                                      previous-index (- index (Character/charCount code-point))]
                                  (if (removable-control? code-point)
                                    (recur previous-index)
                                    (when (contains? #{(int \.) (int \:) (int \!) (int \?)}
                                                     code-point)
                                      previous-index)))))]
      (if punctuation-index
        (recur punctuation-index)
        end))))

(defn- sensitive-path-segment?
  [text start end]
  (or (region-contains-ignore-case? text start end "secret")
      (region-contains-ignore-case? text start end "token")
      (region-contains-ignore-case? text start end "password")
      (region-contains-ignore-case? text start end "credential")
      (and (= (visible-character-count text start end) 4)
           (= end (visible-region-matches-ignore-case? text start ".ssh")))
      (and (= (visible-character-count text start end) 6)
           (= end (visible-region-matches-ignore-case? text start "id_rsa")))))

(defn- secret-bearing-relative-path?
  [text start end]
  (loop [index (skip-removable-controls text start)
         segment-start start
         has-separator? false
         sensitive? false]
    (if (>= index end)
      (and has-separator?
           (or sensitive? (sensitive-path-segment? text segment-start end)))
      (if (contains? #{\\ \/} (.charAt ^String text index))
        (let [next-index (skip-removable-controls text (inc index))]
          (recur next-index
                 next-index
                 true
                 (or sensitive?
                     (sensitive-path-segment? text segment-start index))))
        (recur (skip-removable-controls text (inc index))
               segment-start has-separator? sensitive?)))))

(defn- ascii-drive-letter?
  [character]
  (let [code-point (int character)]
    (or (<= (int \A) code-point (int \Z))
        (<= (int \a) code-point (int \z)))))

(defn- absolute-path-prefix-at?
  [text index]
  (let [slash (visible-starts-with-end text index "/")
        home (visible-starts-with-end text index "~/")
        dot (visible-starts-with-end text index "./")
        parent (visible-starts-with-end text index "../")
        drive-colon (when (and (< index (.length ^String text))
                               (ascii-drive-letter? (.charAt ^String text index)))
                      (visible-starts-with-end text (inc index) ":"))
        drive (when drive-colon
                (or (visible-starts-with-end text drive-colon "\\")
                    (visible-starts-with-end text drive-colon "/")))
        unc (visible-starts-with-end text index "\\\\")
        third-backslash (when unc (visible-starts-with-end text unc "\\"))]
    (or slash home dot parent drive (and unc (not third-backslash)))))

(defn- exact-sensitive-segment-end
  [text index end]
  (some (fn [segment]
          (when-let [segment-end (visible-region-matches-ignore-case? text index segment)]
            (let [separator-index (skip-removable-controls text segment-end)]
              (when (and (< separator-index end)
                         (contains? #{\\ \/} (.charAt ^String text separator-index)))
                separator-index))))
        [".ssh" "id_rsa"]))

(defn- first-exact-sensitive-suffix-start
  [text start end]
  (loop [index (skip-removable-controls text start)]
    (when (< index end)
      (if (and (path-left-delimiter? text index)
               (exact-sensitive-segment-end text index end))
        index
        (recur (next-visible-index text index))))))

(defn- path-separator-scanner
  [^String text]
  ;; Index both separator families while examining each raw input position once.
  ;; Queries compare cached indexes and therefore examine no additional input.
  (let [[last-slash last-backslash]
        (loop [index 0
               last-slash -1
               last-backslash -1]
          (if (>= index (.length text))
            [last-slash last-backslash]
            (let [character (.charAt text index)]
              (recur (inc index)
                     (if (= character \/) index last-slash)
                     (if (= character \\) index last-backslash)))))]
    (fn [index]
      (or (>= last-slash index)
          (>= last-backslash index)))))

(defn- path-span-scanner
  [text]
  ;; A rejected run can only become sensitive at a later candidate when dropping
  ;; its first-segment prefix exposes an exact .ssh or id_rsa segment. The first
  ;; such candidate consumes the rest of the run, so no later starts are needed.
  ;; Cache a separator-free remainder immediately: no supported absolute or
  ;; relative path can begin there, and repeatedly scanning it adds no evidence.
  (let [cached-run (volatile! nil)
        separator-at-or-after? (path-separator-scanner text)]
    (fn [index]
      (when (path-left-delimiter? text index)
        (let [{:keys [end exact-suffix-start] :as cached} @cached-run
              same-run? (and cached (< index end))
              separator? (or same-run? (separator-at-or-after? index))
              end (cond
                    same-run? end
                    separator? (path-end-index text index)
                    :else (.length ^String text))
              absolute? (and separator? (absolute-path-prefix-at? text index))
              trimmed-end (if separator?
                            (trim-path-punctuation-end text index end)
                            end)
              relative? (and separator?
                             (if same-run?
                               (= exact-suffix-start index)
                               (secret-bearing-relative-path? text index trimmed-end)))
              candidate? (or absolute? relative?)]
          (when-not same-run?
            (vreset! cached-run
                     {:end end
                      :exact-suffix-start (when (and separator? (not candidate?))
                                            (first-exact-sensitive-suffix-start
                                             text index end))}))
          (when (and candidate? (> trimmed-end index))
            trimmed-end))))))

(defn- append-bounded-code-point!
  [builder output-count code-point]
  (when (< (aget output-count 0) max-component-code-points)
    (.appendCodePoint ^StringBuilder builder code-point)
    (aset-int output-count 0 (inc (aget output-count 0)))))

(defn- append-normalized-code-point!
  [builder output-count pending-space code-point]
  (if (whitespace-code-point? code-point)
    (when (pos? (aget output-count 0))
      (aset-boolean pending-space 0 true))
    (do
      (when (and (aget pending-space 0)
                 (< (aget output-count 0) max-component-code-points))
        (append-bounded-code-point! builder output-count (int \space)))
      (aset-boolean pending-space 0 false)
      (append-bounded-code-point! builder output-count code-point))))

(defn- append-normalized-text!
  [builder output-count pending-space text]
  (loop [index 0]
    (when (< index (.length ^String text))
      (let [code-point (.codePointAt ^String text index)]
        (append-normalized-code-point!
         builder output-count pending-space code-point)
        (recur (+ index (Character/charCount code-point)))))))

(defn- first-sanitizer-span
  [text index checked-credential-key-end quote-end-scanners path-span]
  (if-let [stack-frame-end (when (left-boundary? text index unicode-word-or-underscore?)
                             (stack-frame-end text index))]
    [{:end stack-frame-end
      :replacement "[STACKTRACE_REDACTED]"
      :redact? true}
     checked-credential-key-end]
    (let [credential-start? (and (>= index checked-credential-key-end)
                                 (left-boundary? text index ascii-key-delimiter?))
          credential-key-end (if credential-start?
                               (credential-key-end text index)
                               checked-credential-key-end)
          span (or (when-let [end (when credential-start?
                                    (credential-span text index credential-key-end
                                                     quote-end-scanners))]
                     {:end end :replacement "[REDACTED]" :redact? true})
                   (when-let [end (when (left-boundary? text index ascii-key-delimiter?)
                                    (bearer-token-end text index))]
                     {:end end :replacement "[REDACTED_TOKEN]" :redact? true})
                   (when-let [end (when (left-boundary? text index token-character?)
                                    (prefixed-token-end text index))]
                     {:end end :replacement "[REDACTED_TOKEN]" :redact? true})
                   (when-let [end (path-span index)]
                     {:end end :replacement "[PATH_REDACTED]" :redact? true})
                   (some (fn [placeholder]
                           (when-let [end (visible-starts-with-end text index placeholder)]
                             {:end end :replacement placeholder :redact? false}))
                         placeholders))]
      [span credential-key-end])))

(defn- sanitized-component
  [text]
  (let [builder (StringBuilder.)
        output-count (int-array 1)
        pending-space (boolean-array 1)
        actionable (boolean-array 1)
        quote-end-scanners {\' (quote-end-scanner text \')
                            \" (quote-end-scanner text \")}
        path-span (path-span-scanner text)]
    (loop [index 0
           checked-credential-key-end 0]
      (if (>= index (.length ^String text))
        {:text (str builder)
         :actionable? (aget actionable 0)}
        (let [[span credential-key-end]
              (first-sanitizer-span text index checked-credential-key-end
                                    quote-end-scanners path-span)]
          (if span
            (do
              (append-normalized-text! builder output-count pending-space
                                       (:replacement span))
              (recur (:end span)
                     (if (:redact? span)
                       credential-key-end
                       checked-credential-key-end)))
            (let [code-point (.codePointAt ^String text index)]
              (when-not (removable-control? code-point)
                (when (Character/isLetterOrDigit code-point)
                  (aset-boolean actionable 0 true))
                (append-normalized-code-point!
                 builder output-count pending-space code-point))
              (recur (+ index (Character/charCount code-point))
                     credential-key-end))))))))

(defn sanitize-component
  "Remove controls, redact sensitive spans, and normalize bounded public text."
  [text]
  (:text (when (string? text) (sanitized-component text))))

(defn actionable?
  "True when sanitized public text retains a letter or digit outside placeholders."
  [text]
  (boolean
   (when (string? text)
     (:actionable? (sanitized-component text)))))

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
                  (and (valid-step-id? terminal-step-id)
                       (contains? step-runs terminal-step-id)) terminal-step-id
                  (and (valid-step-id? current-step-id)
                       (contains? step-runs current-step-id)) current-step-id
                  :else fallback-step-id)
        attempts (get-in step-runs [step-id :attempts])
        terminal-attempt-id (get-in workflow-run [:terminal-outcome :attempt-id])
        attempt (or (when (valid-step-id? terminal-attempt-id)
                      (some #(when (= terminal-attempt-id (:attempt-id %)) %) attempts))
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

(defn- render-reason
  [reason]
  (let [body (if-let [namespace (namespace reason)]
               (str namespace "/" (name reason))
               (name reason))]
    (str ":" body)))

(defn- terminal-cause
  [terminal-outcome]
  (when (safe-reason? (:reason terminal-outcome))
    (str "terminal outcome " (render-reason (:reason terminal-outcome))
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
  [target step step-actionable? cause]
  (bounded-message
   (str "Delegated workflow '" (escape-component target) "' failed"
        (when step-actionable?
          (str " at step '" (escape-component step) "'"))
        ": " cause)))

(defn delegated-failure
  "Construct the canonical parent-attempt failure envelope for a failed child."
  [delegate-run delegate-run-id target]
  (let [selection (terminal-step-attempt delegate-run)
        error (get-in selection [:attempt :execution-error])
        sanitized-error (when (nonblank-string? (:message error))
                          (sanitized-component (:message error)))
        execution-cause (when (:actionable? sanitized-error) (:text sanitized-error))
        terminal-outcome (:terminal-outcome delegate-run)
        terminal-cause-text (terminal-cause terminal-outcome)
        target-component (when (string? target) (sanitized-component target))
        step-component (when (string? (:step-id selection))
                         (sanitized-component (:step-id selection)))
        target-text (:text target-component)
        step-text (:text step-component)
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
        actionable-target? (:actionable? target-component)
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
                (public-message target-text step-text (:actionable? step-component) cause)
                fallback-message)
     :delegate-failure failure}))
