(ns psi.agent-session.workflow.routing
  "Deterministic workflow routing parsers for authored workflow markers."
  (:require
   [clojure.string :as str]))

(def ^:private proof-sync-routes
  ["COVERAGE_REVIEW" "VALIDATION_RECAPTURE" "BOOKKEEPING_FIXED_POINT"])

(def ^:private validation-capture-routes
  ["IMPLEMENTATION_REPAIR" "TERMINAL_STOP"])

(defn- parse-exact-marker-routing
  [{:keys [text marker-label allowed-routes]}]
  (let [lines (str/split-lines (or text ""))
        marker-lines (filterv #(str/includes? % marker-label) lines)
        exact-prefix (str marker-label ": ")
        allowed-routes-set (set allowed-routes)]
    (cond
      (empty? marker-lines)
      {:status :error
       :reason :missing-route-marker
       :message (str marker-label " marker missing")
       :details {:text text}}

      (> (count marker-lines) 1)
      {:status :error
       :reason :ambiguous-route-marker
       :message (str "Multiple " marker-label " marker lines found")
       :details {:text text
                 :route-marker-lines marker-lines}}

      :else
      (let [line (first marker-lines)
            raw-route (when (str/starts-with? line exact-prefix)
                        (subs line (count exact-prefix)))]
        (cond
          (nil? raw-route)
          {:status :error
           :reason :malformed-route-marker
           :message (str marker-label " marker must start at column 0 with exactly one space after colon")
           :details {:text text
                     :line line}}

          (not (re-matches #"[A-Z_]+" raw-route))
          {:status :error
           :reason :malformed-route-marker
           :message (str marker-label " marker must contain exactly one route token and no trailing text")
           :details {:text text
                     :line line
                     :value raw-route}}

          (not (contains? allowed-routes-set raw-route))
          {:status :error
           :reason :unsupported-route-marker
           :message (str marker-label " route token is not supported")
           :details {:text text
                     :line line
                     :value raw-route
                     :allowed-routes allowed-routes}}

          :else
          {:status :ok
           :data raw-route
           :summary raw-route})))))

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
