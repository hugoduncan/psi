(ns psi.system-bootstrap.core
  "Composition-root query registration.

   Owns whole-application and assembled-isolated registration above the domain
   components.

   Dependency flow:
   - system-bootstrap → all resolver domains (ai, history, memory, recursion,
     agent-session, introspection)
   - higher-level composition/startup code → system-bootstrap
   - domain components expose only their local registration surfaces

   Result: the composition root owns \"register everything\" behavior and
   domain components do not depend back on application assembly."
  (:require
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.query.core :as query]
   [psi.query.registry :as registry]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Registration Protocol
;; ─────────────────────────────────────────────────────────────────────────────

(def ^:private global-domains
  [{:ns 'psi.ai.core
    :resolvers 'all-resolvers}
   {:ns 'psi.history.resolvers
    :resolvers 'all-resolvers
    :mutations 'all-mutations}
   {:ns 'psi.introspection.resolvers
    :resolvers 'all-resolvers}
   {:ns 'psi.memory.resolvers
    :resolvers 'all-resolvers}
   {:ns 'psi.recursion.resolvers
    :resolvers 'all-resolvers
    :mutations 'all-mutations}
   {:ns 'psi.agent-session.resolvers
    :resolvers 'all-resolvers}
   {:ns 'psi.agent-session.mutations
    :mutations 'all-mutations}])

(def ^:private isolated-domains
  {:base [{:ns 'psi.ai.core
           :resolvers 'all-resolvers}
          {:ns 'psi.introspection.resolvers
           :resolvers 'all-resolvers}
          {:ns 'psi.history.resolvers
           :resolvers 'all-resolvers
           :mutations 'all-mutations}
          {:ns 'psi.memory.resolvers
           :resolvers 'all-resolvers}
          {:ns 'psi.recursion.resolvers
           :resolvers 'all-resolvers
           :mutations 'all-mutations}]
   :session [{:ns 'psi.agent-session.resolvers
              :resolvers 'all-resolvers}
             {:ns 'psi.agent-session.mutations
              :mutations 'all-mutations}]})

(defn- register-resolver-if-missing!
  "Register a resolver only if it's not already registered."
  [resolver]
  (let [sym (-> resolver pco/operation-config ::pco/op-name)
        existing-resolvers (registry/registered-resolver-syms)]
    (when-not (contains? existing-resolvers sym)
      (query/register-resolver! resolver))))

(defn- register-mutation-if-missing!
  "Register a mutation only if it's not already registered."
  [mutation]
  (let [sym (-> mutation pco/operation-config ::pco/op-name)
        existing-mutations (registry/registered-mutation-syms)]
    (when-not (contains? existing-mutations sym)
      (query/register-mutation! mutation))))

(defn- resolve-domain-ops
  [{:keys [ns resolvers mutations]}]
  (require ns)
  {:resolvers (when resolvers
                (some-> (resolve (symbol (str ns "/" resolvers))) deref))
   :mutations (when mutations
                (some-> (resolve (symbol (str ns "/" mutations))) deref))})

(defn- register-domain-with!
  [register-resolver! register-mutation! {:keys [ns] :as domain} warning-prefix]
  (try
    (let [{:keys [resolvers mutations]} (resolve-domain-ops domain)]
      (doseq [resolver resolvers]
        (register-resolver! resolver))
      (doseq [mutation mutations]
        (register-mutation! mutation)))
    (catch Exception e
      (println warning-prefix ns ":" (.getMessage e)))))

(defn register-all-domains!
  "Register resolvers and mutations from all system domains into the global registry.

   This is the authoritative composition-root entrypoint for whole-application
   query registration.

   Domains:
   - AI resolvers
   - History resolvers + mutations
   - Introspection resolvers
   - Memory resolvers
   - Recursion resolvers + mutations
   - Agent-session resolvers + mutations

   Idempotent: skips operations already present in the global registry."
  []
  ;; Load domains dynamically to avoid eager loading at require-time
  (doseq [domain global-domains]
    (register-domain-with! register-resolver-if-missing!
                           register-mutation-if-missing!
                           domain
                           "Warning: Could not load resolvers from"))

  ;; Single env rebuild after all operations are registered
  (query/rebuild-env!))

(defn register-domains-in!
  "Register resolvers and mutations into an isolated query context.

   This is the composition-root entrypoint for assembled isolated query
   contexts used by tests and higher-level orchestration.

   Args:
     qctx - isolated query context from query/create-query-context
     session-ctx - optional agent-session context for session-specific resolvers"
  ([qctx]
   (register-domains-in! qctx nil))
  ([qctx session-ctx]
   (let [existing-resolvers (atom (set (map #(-> % pco/operation-config ::pco/op-name)
                                            (registry/all-resolvers-in (:reg qctx)))))
         existing-mutations (atom (set (map #(-> % pco/operation-config ::pco/op-name)
                                            (registry/all-mutations-in (:reg qctx)))))
         register-resolver-if-missing!
         (fn [resolver]
           (let [sym (-> resolver pco/operation-config ::pco/op-name)]
             (when-not (contains? @existing-resolvers sym)
               (query/register-resolver-in! qctx resolver)
               (swap! existing-resolvers conj sym))))
         register-mutation-if-missing!
         (fn [mutation]
           (let [sym (-> mutation pco/operation-config ::pco/op-name)]
             (when-not (contains? @existing-mutations sym)
               (query/register-mutation-in! qctx mutation)
               (swap! existing-mutations conj sym))))
         domains (cond-> (:base isolated-domains)
                   session-ctx (into (:session isolated-domains)))]
     (doseq [domain domains]
       (register-domain-with! register-resolver-if-missing!
                              register-mutation-if-missing!
                              domain
                              "Warning: Could not load resolvers from in isolated context:"))

    ;; Single env rebuild after all operations are registered
     (query/rebuild-env-in! qctx))))
