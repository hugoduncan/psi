(ns psi.ai.providers.environment-boundary
  "Injectable environment boundary for provider credential resolution.

  The default boundary reads the process environment. `nullable` supplies
  deterministic values without process-global redefinition and records reads
  for state-based tests.")

(defprotocol EnvironmentBoundary
  (lookup [boundary variable]
    "Return the configured value for an environment variable, or nil.")
  (reads [boundary]
    "Return environment variable names read through this boundary."))

(defrecord RealEnvironmentBoundary []
  EnvironmentBoundary
  (lookup [_ variable]
    (System/getenv variable))
  (reads [_]
    nil))

(defrecord NullableEnvironmentBoundary [values state]
  EnvironmentBoundary
  (lookup [_ variable]
    (swap! state conj variable)
    (if (fn? values)
      (values variable)
      (clojure.core/get values variable)))
  (reads [_]
    @state))

(def real
  "Production process-environment boundary."
  (->RealEnvironmentBoundary))

(defn nullable
  "Return a zero-process-environment boundary backed by a map or function.

  Variable names read through the boundary are available through `reads`."
  [values]
  (->NullableEnvironmentBoundary values (atom [])))

(defn boundary
  "Return the explicitly configured boundary or the production boundary."
  [options]
  (or (:environment-boundary options) real))
