(ns psi.app-runtime.retry-display
  "Shared retry/rate-limit display formatting for adapter-neutral app-runtime projections."
  (:require
   [clojure.string :as str]))

(defn format-relative-seconds
  [ms]
  (let [seconds (max 0 (long (Math/ceil (/ (double (max 0 ms)) 1000.0))))]
    (str seconds "s")))

(defn- remaining-text
  [rate-limit]
  (when (some? (:remaining rate-limit))
    (str (:remaining rate-limit)
         (when (some? (:limit rate-limit))
           (str "/" (:limit rate-limit))))))

(defn retry-summary-fragment
  [retry now-ms]
  (when (:active? retry)
    (let [rate-limit (:rate-limit retry)
          delay-text (format-relative-seconds (- (or (:resume-at retry) now-ms) now-ms))
          remaining  (remaining-text rate-limit)
          reset-text (when-let [reset-at (:reset-at rate-limit)]
                       (format-relative-seconds (- reset-at now-ms)))]
      (str " retrying-in:" delay-text
           " source:" (name (:delay-source retry))
           (when remaining (str " remaining:" remaining))
           (when reset-text (str " reset-in:" reset-text))))))

(defn retry-status-text
  [retry now-ms]
  (when (:active? retry)
    (let [rate-limit (:rate-limit retry)
          delay-text (format-relative-seconds (- (or (:resume-at retry) now-ms) now-ms))
          remaining  (remaining-text rate-limit)
          reset-text (when-let [reset-at (:reset-at rate-limit)]
                       (format-relative-seconds (- reset-at now-ms)))]
      (str/join " · "
                (remove str/blank?
                        [(str "retry in " delay-text)
                         (when remaining (str "remaining " remaining))
                         (when reset-text (str "reset in " reset-text))])))))
