(ns psi.rpc.runtime
  "Runtime/bootstrap implementation for the public RPC facade."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.state-accessors :as sa]
   [psi.rpc.events :as rpc.events]
   [psi.rpc.session :as rpc.session]
   [psi.rpc.state :as rpc.state]
   [psi.rpc.transport :as rpc.transport]))

(defn- normalize-trace-file
  [rpc-trace-file]
  (when-not (str/blank? rpc-trace-file)
    rpc-trace-file))

(defn- make-trace-fn
  [ctx]
  (let [trace-lock (Object.)]
    (fn [{:keys [dir raw frame parse-error]}]
      (try
        (let [cfg      (or (sa/rpc-trace-state-in ctx) {})
              enabled? (boolean (:enabled? cfg))
              path     (:file cfg)]
          (when (and enabled?
                     (string? path)
                     (not (str/blank? path)))
            (io/make-parents path)
            (let [entry (cond-> {:ts (str (java.time.Instant/now))
                                 :dir dir
                                 :raw raw}
                          (map? frame) (assoc :frame frame)
                          (and (string? parse-error)
                               (not (str/blank? parse-error)))
                          (assoc :parse-error parse-error))]
              (locking trace-lock
                (spit path (str (pr-str entry) "\n") :append true)))))
        (catch Throwable _
          nil)))))

(defn make-rpc-deps
  [{:keys [ctx ai-model ui-type on-new-session! sync-on-git-head-change?]
    :or   {ui-type :emacs
           sync-on-git-head-change? ::default}}]
  {:transport
   {:handshake-server-info-fn
    (fn [state]
      (let [session-id (rpc.state/focus-session-id state)]
        (assoc (rpc.events/session->handshake-server-info ctx session-id)
               :ui-type ui-type)))}
   :session
   {:rpc-ai-model ai-model
    :on-new-session! on-new-session!
    :sync-on-git-head-change? sync-on-git-head-change?}})

(defn- publish-session-state!
  [session-state* {:keys [ctx ai-model oauth-ctx nrepl-runtime]}]
  (reset! session-state* {:ctx ctx
                          :ai-model ai-model
                          :oauth-ctx oauth-ctx
                          :nrepl-runtime-atom nrepl-runtime}))

(defn start-runtime!
  "Run EDN-lines RPC using an already-bootstrapped agent-session runtime context.

   The caller provides:
   - `session-ctx-factory` => (fn [] {:ctx .. :oauth-ctx .. :cwd .. :session-id ..})
   - `bootstrap-fn!`       => (fn [ctx session-id] ...)
   - `on-new-session!`     => (fn [source-session-id] {:session-id .. :agent-messages .. :messages .. :tool-calls .. :tool-order ..})

   In rpc-edn mode, stdout is protocol-only. Direct System/out writes are rebound to stderr."
  [{:keys [model-key
           memory-runtime-opts
           session-config
           rpc-trace-file
           session-state*
           nrepl-runtime
           resolve-model
           session-ctx-factory
           bootstrap-fn!
           on-new-session!
           sync-on-git-head-change?]
    :or {memory-runtime-opts {}
         session-config {}
         rpc-trace-file nil
         sync-on-git-head-change? ::default}}]
  (let [protocol-out       *out*
        original-systemout System/out]
    (try
      (binding [*out* *err*]
        (System/setOut (java.io.PrintStream. System/err true))
        (let [ai-model      (resolve-model model-key)
              {:keys [ctx oauth-ctx session-id]} (session-ctx-factory ai-model session-config)
              _             (bootstrap-fn! ctx session-id ai-model memory-runtime-opts)
              trace-file*   (normalize-trace-file rpc-trace-file)
              _             (dispatch/dispatch! ctx :session/set-rpc-trace {:session-id session-id :enabled? (boolean trace-file*) :file trace-file*} {:origin :core})
              trace-fn      (make-trace-fn ctx)
              state         (rpc.state/make-rpc-state {:session-id session-id
                                                       :err *err*})
              deps          (make-rpc-deps {:ctx ctx
                                            :ai-model ai-model
                                            :ui-type :emacs
                                            :on-new-session! on-new-session!
                                            :sync-on-git-head-change? sync-on-git-head-change?})
              request-handler (rpc.session/make-session-request-handler ctx (:session deps))]
          (publish-session-state! session-state* {:ctx ctx
                                                  :ai-model ai-model
                                                  :oauth-ctx oauth-ctx
                                                  :nrepl-runtime nrepl-runtime})
          (rpc.transport/run-stdio-loop! {:request-handler request-handler
                                          :state state
                                          :out protocol-out
                                          :trace-fn trace-fn
                                          :handshake-server-info-fn (get-in deps [:transport :handshake-server-info-fn])})))
      (finally
        (System/setOut original-systemout)))))
