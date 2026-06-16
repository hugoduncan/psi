(ns extensions.dev-http
  "dev-http extension entry point.

   A dev-time, localhost-only HTTP side channel between the agent and the
   developer. The running integrant system, route registry, and api map are
   held in this extension's own atom (precedent: work-on, mcp-tasks-run) — not
   in core state and not as a core managed-service type.

   All reads/writes flow through the runtime ExtensionAPI map captured at
   `init`; the extension never reaches into core namespaces."
  (:require
   [clojure.string :as str]
   [extensions.dev-http.choices :as choices]
   [extensions.dev-http.registry :as registry]
   [extensions.dev-http.renderers :as renderers]
   [extensions.dev-http.sse :as sse]
   [extensions.dev-http.system :as system]
   [extensions.dev-http.tool :as tool]
   [extensions.dev-http.util :as util]
   [integrant.core :as ig]))

(defonce ^:private state
  (atom {:api nil
         :system nil}))

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- log!
  [text]
  (if-let [f (:log (:api @state))]
    (f text)
    (binding [*out* *err*]
      (println text))))

(defn- server-component
  []
  (some-> (:system @state) (get :dev-http/server)))

(defn- registry-component
  []
  (some-> (:system @state) (get :dev-http/registry)))

(defn- base-url
  []
  (when-let [server (server-component)]
    (str "http://" (:host server) ":" (:port server))))

(defn route-url
  "Build the full URL (including token) for a session `route-id`."
  [route-id]
  (when-let [server (server-component)]
    (str (base-url) (util/session-route-path route-id (:token server)))))

;; ---------------------------------------------------------------------------
;; lifecycle
;; ---------------------------------------------------------------------------

(defn stop!
  "Halt the running integrant system (if any). Idempotent."
  []
  (when-let [sys (:system @state)]
    (ig/halt! sys)
    (swap! state assoc :system nil))
  nil)

(defn start!
  "Start the dev-http server. Idempotent: halts any prior system first so no
   orphaned server survives a reload/restart. Returns server info."
  []
  (stop!)
  (let [api (:api @state)
        sys (ig/init (system/system-config api {:host "127.0.0.1" :port 0}))]
    (swap! state assoc :system sys)
    (server-component)))

(defn- url-token-lines
  "Single source of the indented url/token presentation shared by the `status`
   and `start` command output."
  [server]
  (str "  url:   " (base-url) "\n"
       "  token: " (:token server)))

(defn status-text
  "Human-readable status: running?/URL/token."
  []
  (if-let [server (server-component)]
    (str "dev-http running\n" (url-token-lines server))
    "dev-http not running"))

;; ---------------------------------------------------------------------------
;; runtime route registration (REPL/dev, fn-based)
;; ---------------------------------------------------------------------------

(defn register-route!
  "Register an arbitrary ring `handler` fn as a throwaway session route under
   `route-id` (last-write-wins on collision). Returns the route URL, or nil when
   the server is not running."
  [route-id handler]
  (when-let [reg (registry-component)]
    (registry/register-entry! reg route-id {:handler handler})
    (route-url route-id)))

(defn- content-handler
  "Build the ring handler for a declarative `content` map. `:choices` is
   interactive (per-request form + single-shot submit); every other renderer is
   a pure content → response render."
  [reg route-id content]
  (if (= :choices (:renderer content))
    (choices/make-handler {:registry   reg
                           :route-id   route-id
                           :session-id (:session-id content)
                           :api        (:api @state)
                           :content    content})
    (fn [_request] (renderers/render content))))

(defn register-sse-route!
  "Register an SSE live feed as a throwaway session route under `route-id`.
   `emit-fn` is invoked `(emit-fn send! close!)` when a client connects (see
   `extensions.dev-http.sse/make-handler`). Returns the route URL, or nil when
   the server is not running."
  [route-id emit-fn]
  (register-route! route-id (sse/make-handler emit-fn)))

(defn register-content-route!
  "Register a declarative `content` map (`{:renderer … :data … :session-id …}`)
   as a session route under `route-id` whose handler renders the content.
   Returns the route URL, or nil when the server is not running (last-write-wins
   on collision)."
  [route-id content]
  (when-let [reg (registry-component)]
    (registry/register-entry! reg route-id
                              {:handler (content-handler reg route-id content)})
    (route-url route-id)))

;; ---------------------------------------------------------------------------
;; command surface
;; ---------------------------------------------------------------------------

(defn- handle-command
  [args]
  (let [sub (-> (or args "") str/trim (str/split #"\s+") first)]
    (case sub
      "start"  (let [server (start!)]
                 (log! (str "dev-http started\n" (url-token-lines server))))
      "status" (log! (status-text))
      "stop"   (do (stop!) (log! "dev-http stopped"))
      (log! "usage: /dev-http start | status | stop"))))

;; ---------------------------------------------------------------------------
;; init
;; ---------------------------------------------------------------------------

(defn init
  "Extension entry point. Captures the runtime ExtensionAPI map and registers the
   `/dev-http` lifecycle command."
  [api]
  (swap! state assoc :api api)
  ((:register-command api)
   "dev-http"
   {:description "Start/stop the dev-time localhost HTTP side-channel server"
    :handler     handle-command})
  ((:register-tool api)
   (tool/dev-present-tool register-content-route!))
  nil)
