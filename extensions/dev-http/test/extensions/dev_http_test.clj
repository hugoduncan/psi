(ns extensions.dev-http-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [extensions.dev-http :as sut]
   [extensions.dev-http.choices :as choices]
   [extensions.dev-http.middleware :as mw]
   [extensions.dev-http.registry :as registry]
   [extensions.dev-http.renderers :as renderers]
   [extensions.dev-http.router :as router]
   [extensions.dev-http.routes :as routes]
   [extensions.dev-http.sse :as sse]
   [extensions.dev-http.tool :as tool]
   [extensions.dev-http.util :as util]
   [org.httpkit.client :as http-client]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(defn- reset-state-fixture
  [f]
  (sut/stop!)
  (f)
  (sut/stop!))

(use-fixtures :each reset-state-fixture)

(def ^:private nullable-session "nullable-session")

(def ^:private test-ext-path "/test/dev_http.clj")

(defn- nullable-api
  "Create the dev-http nullable ExtensionAPI fixture under the shared test
   ext-path. `opts` adds overrides (e.g. a failing `:mutate-fn` seam)."
  ([] (nullable-api nil))
  ([opts] (nullable/create-nullable-extension-api (merge {:path test-ext-path} opts))))

(deftest init-wires-api-test
  (testing "init wires the runtime ExtensionAPI into the extension"
    (let [{:keys [api state]} (nullable-api)]
      (is (nil? (sut/init api)))
      ;; The observable proof that init wired the captured api is the registered
      ;; /dev-http command (the integration tests prove the stored api drives
      ;; start!/status-text); assert that surface rather than reaching through
      ;; the private internal atom.
      (testing "registers the /dev-http command"
        (is (contains? (:commands @state) "dev-http"))))))

(defn- op-capturing-api
  "Nullable ExtensionAPI augmented with :register-operation capture (the default
   nullable does not provide that slot). Returns {:api :state :ops}."
  []
  (let [{:keys [api state]} (nullable-api)
        ops (atom [])]
    {:api   (assoc api :register-operation (fn [op] (swap! ops conj op) nil))
     :state state
     :ops   ops}))

(deftest registers-psi-tool-operations-test
  (testing "init registers dev-http discovery + lifecycle deterministic operations"
    (let [{:keys [api ops]} (op-capturing-api)]
      (sut/init api)
      (is (= #{"dev-http/status" "dev-http/start" "dev-http/stop"}
             (set (map :id @ops)))
          "status (discovery) + start/stop (lifecycle) are psi-tool-invocable")
      (is (every? (comp fn? :handler) @ops))
      (is (every? (comp string? :description) @ops)))))

(deftest operation-handlers-report-and-control-lifecycle-test
  (testing "the registered operation handlers wrap the shared lifecycle"
    (let [{:keys [api ops]} (op-capturing-api)
          _        (sut/init api)
          by-id    (into {} (map (juxt :id :handler)) @ops)
          status   (get by-id "dev-http/status")
          start    (get by-id "dev-http/start")
          stop     (get by-id "dev-http/stop")]
      (testing "status before start → not running, no side effect"
        (is (= {:running? false} (status {})))
        (is (= {:running? false} (sut/server-info))))
      (testing "start → running with discoverable url/token/route-count"
        (let [r (start {})]
          (is (true? (:running? r)))
          (is (string? (:url r)))
          (is (string? (:token r)))
          (is (int? (:route-count r)))
          (is (= r (status {})) "status mirrors start's structured snapshot")))
      (testing "stop → not running"
        (is (= {:running? false} (stop {})))
        (is (= {:running? false} (status {})))))))

;; ---------------------------------------------------------------------------
;; registry — last-write-wins
;; ---------------------------------------------------------------------------

(deftest registry-last-write-wins-test
  (testing "re-registering a route-id replaces the prior entry"
    (let [reg (registry/create-registry)
          h1  (fn [_] {:status 200 :body "one"})
          h2  (fn [_] {:status 200 :body "two"})]
      (registry/register-entry! reg "r" {:handler h1})
      (is (identical? h1 (:handler (registry/get-entry reg "r"))))
      (registry/register-entry! reg "r" {:handler h2})
      (is (identical? h2 (:handler (registry/get-entry reg "r"))))
      (is (= "r" (:route-id (registry/get-entry reg "r")))))))

;; ---------------------------------------------------------------------------
;; router / middleware — pure ring handler, no socket
;; ---------------------------------------------------------------------------

(defn- build-test-handler
  ([reg token]
   (build-test-handler reg token (routes/load-persisted-routes)))
  ([reg token persisted]
   (router/build-handler {:registry         reg
                          :token            token
                          :persisted-routes persisted})))

(deftest token-gating-test
  (testing "every dynamic subtree is token-gated"
    (let [reg     (registry/create-registry)
          token   "secret-token"
          handler (build-test-handler reg token)]
      (testing "persisted route without token → 403"
        (is (= 403 (:status (handler {:request-method :get :uri "/demo"})))))
      (testing "persisted route with query token → 200"
        (is (= 200 (:status (handler {:request-method :get
                                      :uri          "/demo"
                                      :query-string (str "token=" token)})))))
      (testing "persisted route with header token → 200"
        (is (= 200 (:status (handler {:request-method :get
                                      :uri     "/demo"
                                      :headers {"x-dev-http-token" token}})))))
      (testing "wrong token → 403"
        (is (= 403 (:status (handler {:request-method :get
                                      :uri          "/demo"
                                      :query-string "token=wrong"}))))))))

(deftest session-route-dispatch-test
  (testing "/s/:route-id dispatches to the registered handler at request time"
    (let [reg     (registry/create-registry)
          token   "tok"
          handler (build-test-handler reg token)]
      (testing "unknown route-id → 404 (token valid)"
        (is (= 404 (:status (handler {:request-method :get
                                      :uri          "/s/nope"
                                      :query-string (str "token=" token)})))))
      (testing "registered route → handler response"
        (registry/register-entry! reg "live" {:handler (fn [_] {:status 200 :body "live!"})})
        (let [resp (handler {:request-method :get
                             :uri          "/s/live"
                             :query-string (str "token=" token)})]
          (is (= 200 (:status resp)))
          (is (= "live!" (:body resp)))))
      (testing "session route still token-gated"
        (is (= 403 (:status (handler {:request-method :get :uri "/s/live"}))))))))

(deftest request-token-test
  (testing "token extracted from query string or header"
    (is (= "abc" (mw/request-token {:query-string "token=abc"})))
    (is (= "abc" (mw/request-token {:query-string "foo=1&token=abc&bar=2"})))
    (is (= "abc" (mw/request-token {:headers {"x-dev-http-token" "abc"}})))
    (is (nil? (mw/request-token {})))))

;; ---------------------------------------------------------------------------
;; persisted-route loader
;; ---------------------------------------------------------------------------

(deftest persisted-routes-loaded-test
  (testing "the persisted demo route is discovered under the dev/ source path"
    (let [loaded (routes/load-persisted-routes)
          paths  (map first loaded)]
      (is (some #{"/demo"} paths)))))

(deftest routes-from-resource-jar-safety-test
  (testing "absent dev source path (nil resource) yields no routes"
    (is (= [] (routes/routes-from-resource nil))))
  (testing "non-file (jar:) URL yields no routes — never scans inside a jar"
    (is (= [] (routes/routes-from-resource
               (java.net.URL. "jar:file:/tmp/app.jar!/extensions/dev_http/dev"))))))

;; ---------------------------------------------------------------------------
;; renderers (Slice 2) — pure content-map → ring response
;; ---------------------------------------------------------------------------

(deftest renderers-test
  (testing "each renderer produces the expected response for representative input"
    (testing ":markdown renders commonmark to HTML"
      (let [resp (renderers/render {:renderer :markdown :data "# Title\n\nhi *there*"})]
        (is (= 200 (:status resp)))
        (is (= "text/html; charset=utf-8" (get-in resp [:headers "content-type"])))
        (is (re-find #"<h1>Title</h1>" (:body resp)))
        (is (re-find #"<em>there</em>" (:body resp)))))
    (testing ":table renders rows (keyword and string data keys)"
      (let [kw       (renderers/render {:renderer :table :data {:headers ["a" "b"] :rows [[1 2]]}})
            str-resp (renderers/render {:renderer :table :data {"headers" ["a"] "rows" [[9]]}})]
        (is (re-find #"<th>a</th><th>b</th>" (:body kw)))
        (is (re-find #"<td>1</td><td>2</td>" (:body kw)))
        (is (re-find #"<th>a</th>" (:body str-resp)))
        (is (re-find #"<td>9</td>" (:body str-resp)))))
    (testing ":table with rows but no headers renders a tbody-only table"
      ;; headers are optional; this is the `(seq headers)` false branch of
      ;; render-table, which omits the <thead> entirely (no header row).
      (let [resp (renderers/render {:renderer :table :data {:rows [[1 2]]}})]
        (is (re-find #"<td>1</td><td>2</td>" (:body resp)))
        (is (not (re-find #"<thead>" (:body resp))))
        (is (not (re-find #"<th>" (:body resp))))))
    (testing ":hiccup renders a JSON-decoded (string-tag) tree as elements"
      (let [resp (renderers/render {:renderer :hiccup :data ["div" {} ["h1" "X"]]})]
        (is (= "<div><h1>X</h1></div>" (:body resp)))))
    (testing ":hiccup passes idiomatic keyword-tag hiccup through unchanged"
      ;; REPL/register-route! supplies keyword tags ([:div … [:h1 …]]); this is
      ;; the `(vector? form)` passthrough branch of coerce-hiccup, distinct from
      ;; the string-tag coercion branch above.
      (let [resp (renderers/render {:renderer :hiccup :data [:div {} [:h1 "X"]]})]
        (is (= "<div><h1>X</h1></div>" (:body resp)))))
    (testing ":hiccup fails loud on a non-tree (e.g. a Clojure-syntax string)"
      ;; A bare string is not a hiccup tree; rendering it silently as a literal
      ;; text node hides the mistake. It must 400 and name the actual type.
      (let [resp (renderers/render {:renderer :hiccup :data "[:div [:h1 \"X\"]]"})]
        (is (= 400 (:status resp)))
        (is (str/includes? (:body resp) "hiccup tree"))
        (is (str/includes? (:body resp) "got string"))
        (is (str/includes? (:body resp) "[:div"))))
    (testing ":hiccup accepts a seq of elements (e.g. a `for` result)"
      (let [resp (renderers/render {:renderer :hiccup
                                    :data (for [x ["a" "b"]] [:li x])})]
        (is (= 200 (:status resp)))
        (is (= "<li>a</li><li>b</li>" (:body resp)))))
    (testing ":vega embeds the vendored client JS and the spec as JSON"
      (let [resp (renderers/render {:renderer :vega :data {"mark" "bar"}})]
        ;; all three scripts: vega.min.js is a required dependency of vega-embed.
        (is (re-find #"/assets/vega\.min\.js" (:body resp)))
        (is (re-find #"/assets/vega-lite\.min\.js" (:body resp)))
        (is (re-find #"/assets/vega-embed\.min\.js" (:body resp)))
        (is (re-find #"\{\"mark\":\"bar\"\}" (:body resp)))
        ;; the embed invocation that mounts the spec into #vega-view.
        (is (re-find #"vegaEmbed\('#vega-view'" (:body resp)))))
    (testing ":mermaid embeds the vendored client JS and the source"
      (let [resp (renderers/render {:renderer :mermaid :data "graph TD; A-->B"})]
        (is (re-find #"/assets/mermaid\.min\.js" (:body resp)))
        (is (re-find #"class=\"mermaid\"" (:body resp)))
        ;; the diagram source itself must be embedded in the <pre>; hiccup
        ;; HTML-escapes `>` to `&gt;`, so the rendered source is `A--&gt;B`.
        (is (re-find #"graph TD; A--&gt;B" (:body resp)))))
    (testing "an unknown renderer yields a 400"
      (is (= 400 (:status (renderers/render {:renderer :nope :data 1})))))))

(deftest file-renderer-test
  (testing ":file serves a disk artifact with a content-type from its extension"
    (let [f (java.io.File/createTempFile "dev-http" ".svg")]
      (try
        (spit f "<svg></svg>")
        (let [resp (renderers/render {:renderer :file :data {:path (.getAbsolutePath f)}})]
          (is (= 200 (:status resp)))
          (is (= "image/svg+xml" (get-in resp [:headers "content-type"])))
          (is (= f (:body resp))))
        (finally (.delete f))))
    (testing ":file reads the string-keyed (JSON-tool) {\"path\" …} variant"
      (let [f (java.io.File/createTempFile "dev-http" ".svg")]
        (try
          (spit f "<svg></svg>")
          (let [resp (renderers/render {:renderer :file :data {"path" (.getAbsolutePath f)}})]
            (is (= 200 (:status resp)))
            (is (= "image/svg+xml" (get-in resp [:headers "content-type"])))
            (is (= f (:body resp))))
          (finally (.delete f)))))
    (testing "a missing file yields a 404"
      (let [resp (renderers/render {:renderer :file :data {:path "/no/such/artifact.png"}})]
        (is (= 404 (:status resp)))))
    (testing "content-type resolves from the file extension (octet-stream default)"
      (doseq [[path expected]
              [["page.html"   "text/html; charset=utf-8"]
               ["img.png"     "image/png"]
               ["photo.jpg"   "image/jpeg"]
               ["doc.pdf"     "application/pdf"]
               ["data.json"   "application/json"]
               ["style.css"   "text/css"]
               ["app.js"      "application/javascript"]
               ["notes.txt"   "text/plain; charset=utf-8"]
               ["mystery.xyz" "application/octet-stream"]]]
        (is (= expected (renderers/content-type-for path))
            (str path " → " expected))))))

(defn- resource-bytes
  [path]
  (with-open [in (io/input-stream (io/resource path))]
    (.readAllBytes in)))

(deftest asset-serving-test
  (testing "vendored client JS is served locally from the classpath (no network)"
    (let [resp (renderers/asset-handler {:path-params {:asset "vega-embed.min.js"}})]
      (is (= 200 (:status resp)))
      (is (= "application/javascript" (get-in resp [:headers "content-type"])))
      (testing "served bytes equal the vendored resource bytes"
        (let [served (with-open [in (:body resp)] (.readAllBytes in))]
          (is (java.util.Arrays/equals served
                                       (resource-bytes "dev_http/vendor/vega-embed.min.js"))))))
    (testing "a path containing .. is rejected"
      (is (= 404 (:status (renderers/asset-handler {:path-params {:asset "../secret"}})))))
    (testing "a missing asset yields a 404"
      (is (= 404 (:status (renderers/asset-handler {:path-params {:asset "nope.js"}})))))))

(deftest asset-route-is-ungated-test
  (testing "the /assets subtree is reachable without a token (public static JS)"
    (let [reg     (registry/create-registry)
          handler (build-test-handler reg "tok" [])
          resp    (handler {:request-method :get
                            :uri            (str renderers/asset-prefix "/vega-embed.min.js")})]
      (is (= 200 (:status resp)))
      (is (= "application/javascript" (get-in resp [:headers "content-type"]))))))

;; ---------------------------------------------------------------------------
;; dev-present tool (Slice 2)
;; ---------------------------------------------------------------------------

(defn- registry-register-fn
  "A real `register-content!` seam for the `dev-present` tool: registers the
   normalized `content` map into `reg` under `route-id` (mirroring the
   production `register-content-route!` seam) and returns a deterministic
   session-route URL string. State-observable — assert on
   `(registry/get-entry reg route-id)` rather than a recorded call."
  [reg]
  (fn [route-id content]
    (registry/register-entry! reg route-id {:content content})
    (str "http://127.0.0.1:9" (util/session-route-path route-id "t"))))

(deftest dev-present-tool-test
  (testing "the tool registers a content route and returns its URL"
    (let [reg     (registry/create-registry)
          tool    (tool/dev-present-tool (registry-register-fn reg))
          execute (:execute tool)]
      (testing "tool metadata"
        (is (= "dev-present" (:name tool)))
        (is (fn? (:format-request tool))))
      (testing "valid renderer → registers content + returns URL"
        (let [result (execute {"renderer" "markdown" "data" "# Hi" "route-id" "md"} {})
              entry  (registry/get-entry reg "md")]
          (is (false? (:is-error result)))
          (is (re-find #"Open: http://127\.0\.0\.1:9/s/md" (:content result)))
          (is (= :markdown (get-in entry [:content :renderer])))
          (is (= "# Hi" (get-in entry [:content :data])))
          (is (= "md" (:route-id entry)))))
      (testing "absent route-id is generated"
        (let [reg2   (registry/create-registry)
              result ((:execute (tool/dev-present-tool (registry-register-fn reg2)))
                      {"renderer" "table" "data" {"rows" []}} {})
              entry  (val (first (registry/entries reg2)))]
          (is (false? (:is-error result)))
          (is (string? (:route-id entry)))
          (is (re-find #"^r-" (:route-id entry)))))
      (testing "opts :session-id threads into the registered content (invoking session only)"
        (let [result (execute {"renderer" "choices"
                               "data"     {"prompt" "Pick" "options" ["a" "b"]}
                               "route-id" "c"}
                              {:session-id "sess-x"})
              entry  (registry/get-entry reg "c")]
          (is (false? (:is-error result)))
          (is (= :choices (get-in entry [:content :renderer])))
          (is (= "sess-x" (get-in entry [:content :session-id])))))
      (testing "absent opts :session-id threads nil (no fabricated session)"
        (let [result (execute {"renderer" "markdown" "data" "# Hi" "route-id" "n"} {})
              entry  (registry/get-entry reg "n")]
          (is (false? (:is-error result)))
          (is (contains? (:content entry) :session-id))
          (is (nil? (get-in entry [:content :session-id])))))
      (testing "unknown renderer → error, no registration"
        (let [reg3   (registry/create-registry)
              result ((:execute (tool/dev-present-tool (registry-register-fn reg3)))
                      {"renderer" "bogus" "data" "x"} {})]
          (is (true? (:is-error result)))
          (is (re-find #"Unknown renderer" (:content result)))
          (is (empty? (registry/entries reg3)))))))
  (testing "server not running (register! returns nil) → error"
    (let [tool    (tool/dev-present-tool (fn [_ _] nil))
          result  ((:execute tool) {"renderer" "markdown" "data" "hi"} {})]
      (is (true? (:is-error result)))
      (is (re-find #"not running" (:content result))))))

;; ---------------------------------------------------------------------------
;; choice interaction loop (Slice 3) — handler in isolation
;; ---------------------------------------------------------------------------

(defn- submit-prompt-mutations
  "The `submit-synthetic-prompt` mutations recorded by the nullable api state."
  [state]
  (filterv #(= 'psi.extension/submit-synthetic-prompt (:op %)) (:mutations @state)))

(defn- make-choices-handler
  "Compress the repeated `:choices` arrange ceremony. Builds a `:choices`
   content map over `options`, makes its handler, registers the route entry,
   and returns `{:handler :state :reg :api}`. Each test states only what
   differs — the `:options` shape (and, for failure-seam tests, a pre-built
   `:api` carrying a custom `:mutate-fn`). The GET/POST assertions stay inline."
  [{:keys [options prompt api reg route-id session-id]
    :or   {prompt     "Pick one"
           route-id   "c1"
           session-id nullable-session}}]
  (let [created (when-not api (nullable-api))
        api     (or api (:api created))
        state   (:state created)
        reg     (or reg (registry/create-registry))
        content {:renderer   :choices
                 :data       {:prompt prompt :options options}
                 :session-id session-id}
        handler (choices/make-handler {:registry   reg
                                       :route-id   route-id
                                       :session-id session-id
                                       :api        api
                                       :content    content})]
    (registry/register-entry! reg route-id {})
    {:handler handler :state state :reg reg :api api}))

(deftest choices-handler-test
  (let [{:keys [handler state]} (make-choices-handler {:options ["A" "B"]})]
    (testing "GET renders a token-gated form posting back to the route"
      (let [resp (handler {:request-method :get :query-string "token=tok"})]
        (is (= 200 (:status resp)))
        (is (re-find #"Pick one" (:body resp)))
        (is (re-find #"action=\"/s/c1\?token=tok\"" (:body resp)))
        (is (re-find #"value=\"A\"" (:body resp)))
        (is (re-find #"value=\"B\"" (:body resp)))))
    (testing "AC-6: a submission injects exactly one user message into the origin session"
      (let [resp (handler {:request-method :post :body "choice=A"})
            muts (submit-prompt-mutations state)]
        (is (= 200 (:status resp)))
        (is (re-find #"Recorded" (:body resp)))
        (is (= 1 (count muts)))
        (is (= nullable-session (:session-id (:params (first muts)))))
        (is (= "A" (:user-msg (:params (first muts)))))))
    (testing "AC-7: single-shot — a second submission is rejected, no second injection"
      (let [resp (handler {:request-method :post :body "choice=B"})]
        (is (= 200 (:status resp)))
        (is (re-find #"Already answered" (:body resp)))
        (is (= 1 (count (submit-prompt-mutations state))))))
    (testing "a blank choice is a 400"
      (let [{handler2 :handler} (make-choices-handler {:options  ["A" "B"]
                                                       :route-id "c2"})]
        (is (= 400 (:status (handler2 {:request-method :post :body ""}))))))))

(deftest choices-failed-injection-releases-claim-test
  ;; AC-6/AC-7 robustness: a failed synthetic-prompt injection must NOT consume
  ;; the single-shot (zero user messages were injected) and must NOT report a
  ;; false success. The claim is released so the choice can be retried. The
  ;; failing/throwing mutate seam is supplied through the sanctioned nullable
  ;; `:mutate-fn` override (not a bespoke spy map); the rendered page and the
  ;; registry claim state are the assertion signals.
  (testing "a falsey prompt-submitted? renders a failure page and releases the claim"
    (let [outcome       (atom {:psi.extension/prompt-submitted? false})
          {:keys [api]} (nullable-api {:mutate-fn (fn [_op _params] @outcome)})
          {:keys [handler reg]} (make-choices-handler {:options ["A" "B"]
                                                       :api     api})
          resp                  (handler {:request-method :post :body "choice=A"})]
      (is (= 200 (:status resp)))
      (is (re-find #"try again" (:body resp)))
      (is (not (re-find #"Recorded" (:body resp))))
      (testing "claim released → not marked answered, retry can succeed"
        (is (not (:answered? (registry/get-entry reg "c1"))))
        (reset! outcome {:psi.extension/prompt-submitted? true})
        (let [resp2 (handler {:request-method :post :body "choice=A"})]
          (is (re-find #"Recorded" (:body resp2)))
          (is (:answered? (registry/get-entry reg "c1")))))))
  (testing "a throwing mutation renders a failure page and releases the claim"
    (let [{:keys [api]} (nullable-api {:mutate-fn (fn [_op _params] (throw (ex-info "boom" {})))})
          {:keys [handler reg]} (make-choices-handler {:options ["A" "B"]
                                                       :api     api})
          resp                  (handler {:request-method :post :body "choice=A"})]
      (is (= 200 (:status resp)))
      (is (re-find #"try again" (:body resp)))
      (is (not (:answered? (registry/get-entry reg "c1")))))))

(deftest choices-map-option-test
  ;; A choice option given as a `{:label … :value …}` map renders the *label*
  ;; as the button text but submits the *value*; the submitted value (not the
  ;; label) is the user message injected into the origin session. Scalar-only
  ;; tests (label==value) leave this label/value distinction unverified, so a
  ;; label/value swap would pass them. Cover both keyword-keyed (REPL) and
  ;; string-keyed (JSON-tool) option maps.
  (doseq [[variant options]
          [["keyword-keyed" [{:label "Yes please" :value "y"}
                             {:label "No" :value "n"}]]
           ["string-keyed" [{"label" "Yes please" "value" "y"}
                            {"label" "No" "value" "n"}]]]]
    (testing variant
      (let [{:keys [handler state]} (make-choices-handler {:options options})]
        (testing "the button displays the label and posts the value"
          (let [resp (handler {:request-method :get :query-string "token=tok"})]
            (is (= 200 (:status resp)))
            (is (re-find #"value=\"y\"" (:body resp)))
            (is (re-find #">Yes please</button>" (:body resp)))
            (is (not (re-find #"value=\"Yes please\"" (:body resp))))))
        (testing "submitting injects the value, not the label, as the user message"
          (let [resp (handler {:request-method :post :body "choice=y"})
                muts (submit-prompt-mutations state)]
            (is (= 200 (:status resp)))
            (is (re-find #"Recorded" (:body resp)))
            (is (= 1 (count muts)))
            (is (= "y" (:user-msg (:params (first muts)))))))))))

(deftest choices-urlencoded-decode-test
  ;; A choice value is URL-encoded by the browser on POST; the handler must
  ;; URL-decode it (mw/urlencoded-param's URLDecoder path, including +→space)
  ;; before injecting it as the user message. The scalar/map tests post
  ;; unencoded values (choice=A/B/y), so the decode step is unverified — a
  ;; regression dropping `decode` (or +→space) would inject the raw encoded
  ;; string yet pass them.
  (doseq [[variant value encoded]
          [["plus-space"    "a b" "choice=a+b"]
           ["percent-slash" "a/b" "choice=a%2Fb"]]]
    (testing variant
      (let [{:keys [handler state]} (make-choices-handler
                                     {:options [{:label "Opt" :value value}]})]
        (testing "the GET form renders the raw (decoded) value attribute"
          (let [resp (handler {:request-method :get :query-string "token=tok"})]
            (is (re-find (re-pattern (str "value=\"" value "\"")) (:body resp)))))
        (testing "a POST of the URL-encoded body injects the decoded value"
          (let [resp (handler {:request-method :post :body encoded})
                muts (submit-prompt-mutations state)]
            (is (= 200 (:status resp)))
            (is (re-find #"Recorded" (:body resp)))
            (is (= 1 (count muts)))
            (is (= value (:user-msg (:params (first muts)))))))))))

;; ---------------------------------------------------------------------------
;; SSE live-updates (Slice 4)
;; ---------------------------------------------------------------------------

(deftest sse-event-format-test
  (testing "an SSE event is a data: block terminated by a blank line"
    (is (= "data: hello\n\n" (sse/event "hello")))))

(deftest sse-route-is-token-gated-test
  (testing "an SSE feed registered as a session route sits inside the token-gated subtree"
    (let [reg     (registry/create-registry)
          handler (build-test-handler reg "tok" [])]
      (registry/register-entry! reg "registry"
                                {:handler (sse/registry-feed-handler reg)})
      (testing "no token → 403 (never reaches the event stream)"
        (is (= 403 (:status (handler {:request-method :get :uri "/s/registry"}))))))))

;; ---------------------------------------------------------------------------
;; lifecycle (integration — real ephemeral-port http-kit server)
;; ---------------------------------------------------------------------------

(defn- server-base
  "The `http://host:port` base URL of a running server map."
  [server]
  (str "http://" (:host server) ":" (:port server)))

(def ^:private http-timeout-ms
  "Bounded client timeout for the integration HTTP helpers so a stalled or
   never-closing handler (e.g. a broken SSE `close!`) fails fast with a
   meaningful error instead of blocking on http-kit's implicit default."
  5000)

(defn- http-get
  "GET `url` over the live server and deref the full response with a bounded
   timeout. The single source for full-response GETs in this suite."
  [url]
  @(http-client/get url {:timeout http-timeout-ms}))

(defn- get-status
  [url]
  (:status (http-get url)))

(defn- body-str
  [resp]
  (let [b (:body resp)]
    (if (instance? java.io.InputStream b) (slurp b) (str b))))

(defn- with-running-server
  "Init + start the dev-http extension against a real ephemeral-port server,
   call `f` with `{:server :state :api}`, and always `stop!`. Not for tests
   that drive the lifecycle surface directly (start/status/stop)."
  [f]
  (let [{:keys [api state]} (nullable-api)
        server              (do (sut/init api) (sut/start!))]
    (try
      (f {:server server :state state :api api})
      (finally (sut/stop!)))))

(deftest ^:integration lifecycle-and-serving-test
  (let [{:keys [api]} (nullable-api)]
    (sut/init api)
    (testing "not running before start"
      (is (= "dev-http not running" (sut/status-text))))
    (let [server (sut/start!)
          base   (server-base server)]
      (try
        (testing "AC-1: binds 127.0.0.1 on an ephemeral port"
          (is (= "127.0.0.1" (:host server)))
          (is (pos? (:port server)))
          (is (string? (:token server))))
        (testing "AC-8: access requires the token"
          (is (= 403 (get-status (str base "/demo"))))
          (is (= 200 (get-status (str base "/demo?token=" (:token server))))))
        (testing "AC-2: persisted demo route is served"
          (let [resp (http-get (str base "/demo?token=" (:token server)))]
            (is (= 200 (:status resp)))
            (is (re-find #"dev-http demo" (body-str resp)))))
        (testing "AC-4 (partial): register-route! reachable, last-write-wins"
          (let [url1 (sut/register-route! "rt" (fn [_] {:status 200 :body "first"}))]
            (is (= 200 (get-status url1)))
            (is (= "first" (body-str (http-get url1))))
            (sut/register-route! "rt" (fn [_] {:status 200 :body "second"}))
            (is (= "second" (body-str (http-get (sut/route-url "rt")))))))
        (testing "AC-3: register-content-route! renders a content route"
          (let [url (sut/register-content-route! "md" {:renderer :markdown
                                                       :data     "# Live"})]
            (is (= 200 (get-status url)))
            (is (re-find #"<h1>Live</h1>" (body-str (http-get url))))))
        (testing "AC-5: vendored asset served locally and ungated over the wire"
          (let [resp (http-get (str base renderers/asset-prefix
                                    "/vega-embed.min.js"))]
            (is (= 200 (:status resp)))
            (is (re-find #"javascript"
                         (str (get-in resp [:headers :content-type]))))))
        (testing "status reports running url + token"
          (is (re-find #"dev-http running" (sut/status-text))))
        (finally
          (sut/stop!))))
    (testing "stop halts the server"
      (is (= "dev-http not running" (sut/status-text))))
    (testing "AC-1: restart leaves no orphaned server"
      (let [s1   (sut/start!)
            url1 (str (server-base s1) "/demo?token=" (:token s1))]
        (try
          (is (= 200 (get-status url1)))
          (let [s2 (sut/start!)]
            (is (pos? (:port s2)))
            (is (= 200 (get-status (str (server-base s2)
                                        "/demo?token=" (:token s2)))))
            ;; The prior server must actually be gone — not merely supplanted.
            ;; Two cases carry the no-orphan evidence:
            ;;  - same ephemeral port re-bound: s2 could not have bound s1's port
            ;;    unless s1 released it; the s2 serve-200 assertion above is the
            ;;    proof, so this case needs no extra check here.
            ;;  - differing port: s1's old URL must no longer serve (its socket
            ;;    was closed by synchronous halt). Without this guard a
            ;;    regression that stopped halting the prior `:system` would leave
            ;;    s1 still listening yet pass the suite.
            (when (not= (:port s1) (:port s2))
              (is (not= 200 (get-status url1))
                  "prior server halted — its old URL no longer serves")))
          (finally
            (sut/stop!)))))))

(deftest ^:integration dev-present-tool-renders-over-server-test
  ;; AC-3: the agent calls the registered `dev-present` tool and receives back a
  ;; URL that renders the content. Drives the *actual* registered tool — resolved
  ;; from the nullable state `:tools` (wired in `init` to
  ;; `register-content-route!`) — through to a URL fetched over the real
  ;; http-kit server. The unit `dev-present-tool-test` stubs the register seam
  ;; and `lifecycle-and-serving-test` calls `register-content-route!` directly,
  ;; so both bypass the tool→register wiring this test exercises end-to-end.
  (with-running-server
    (fn [{:keys [state]}]
      (let [tool (get-in @state [:tools "dev-present"])]
        (is (some? tool) "init registered the dev-present tool")
        (let [result ((:execute tool)
                      {"renderer" "markdown" "data" "# Tool Live" "route-id" "tool-md"}
                      {:session-id nullable-session})
              url    (second (re-find #"Open: (\S+)" (str (:content result))))]
          (is (false? (:is-error result)))
          (is (string? url))
          (let [resp (http-get url)]
            (is (= 200 (:status resp)))
            (is (re-find #"<h1>Tool Live</h1>" (body-str resp)))))))))

(defn- live-token
  "Extract the running server's live token from a session-route URL."
  []
  (second (re-find #"token=(\S+)" (sut/route-url "probe"))))

(deftest ^:integration dev-http-command-handler-test
  ;; AC-1 + design §Lifecycle: the user-facing surface is the
  ;; `/dev-http start | status | stop` command. The other tests drive
  ;; `start!`/`status-text`/`stop!` directly, bypassing `handle-command`'s
  ;; arg-parse + subcommand `case` routing and its unknown-subcommand usage
  ;; fallback. Drive the *registered* command handler — resolved from the
  ;; nullable state `:commands` (wired in `init`) — and assert on the captured
  ;; log lines + the running/stopped server state, including the running-status
  ;; url/token presentation.
  (let [{:keys [api state]} (nullable-api)]
    (sut/init api)
    (let [handler (get-in @state [:commands "dev-http" :handler])]
      (is (fn? handler) "init registered a /dev-http command handler")
      (testing "status before start reports not running"
        (handler "status")
        (is (= "dev-http not running" (nullable/drain-log! state)))
        (is (= "dev-http not running" (sut/status-text))))
      (try
        (testing "start launches the server and logs the running url/token"
          (handler "start")
          (let [started (nullable/drain-log! state)
                token   (live-token)]
            (is (re-find #"dev-http started" started))
            (is (re-find #"http://127\.0\.0\.1:\d+" started)
                "started output carries the base URL")
            (is (str/includes? started token)
                "started output carries the live token")
            (is (some? (sut/route-url "probe")) "server is running after start")))
        (testing "status after start logs the running header + live url/token"
          (handler "status")
          (let [status (nullable/drain-log! state)
                token  (live-token)]
            (is (re-find #"dev-http running" status))
            (is (re-find #"http://127\.0\.0\.1:\d+" status)
                "status logs the base URL")
            (is (str/includes? status token) "status logs the live token")))
        (testing "stop halts the server and logs stopped"
          (handler "stop")
          (is (= "dev-http stopped" (nullable/drain-log! state)))
          (is (= "dev-http not running" (sut/status-text)))
          (is (nil? (sut/route-url "probe")) "server is gone after stop"))
        (testing "an unknown subcommand logs the usage line"
          (handler "wat")
          (is (= "usage: /dev-http start | status | stop"
                 (nullable/drain-log! state))))
        (finally
          (sut/stop!))))))

(defn- post-form
  [url body]
  @(http-client/post url {:headers {"content-type" "application/x-www-form-urlencoded"}
                          :body    body
                          :timeout http-timeout-ms}))

(deftest ^:integration choices-interaction-loop-test
  ;; Drives the choice loop over the real http-kit server: GET form → POST
  ;; choice → mutate-session (captured by the nullable api) → single-shot guard.
  (with-running-server
    (fn [{:keys [state]}]
      (let [url (sut/register-content-route! "ch" {:renderer   :choices
                                                   :data       {:prompt  "Pick one"
                                                                :options ["A" "B"]}
                                                   :session-id nullable-session})]
        (testing "the choices form is served"
          (let [resp (http-get url)]
            (is (= 200 (:status resp)))
            (is (re-find #"Pick one" (body-str resp)))))
        (testing "AC-6: submitting injects one user message into the origin session"
          (let [resp (post-form url "choice=A")
                muts (submit-prompt-mutations state)]
            (is (= 200 (:status resp)))
            (is (re-find #"Recorded" (body-str resp)))
            (is (= 1 (count muts)))
            (is (= "A" (:user-msg (:params (first muts)))))
            (is (= nullable-session (:session-id (:params (first muts)))))))
        (testing "AC-7: a second submission is rejected with no further injection"
          (let [resp (post-form url "choice=B")
                muts (submit-prompt-mutations state)]
            (is (re-find #"Already answered" (body-str resp)))
            (is (= 1 (count muts)))))))))

(deftest ^:integration sse-live-feed-test
  ;; Connects to the real SSE feed over the ephemeral-port server and reads the
  ;; pushed snapshot, which reflects current registry state.
  (with-running-server
    (fn [{:keys [server]}]
      (let [base  (server-base server)
            token (:token server)]
        (testing "AC-8: the SSE feed (a session route registered at start!) requires the token"
          (is (= 403 (get-status (str base "/s/registry")))))
        (testing "a connected client receives a pushed snapshot event"
          ;; start! auto-registered the `registry` feed itself; add one more so
          ;; the snapshot reflects both session routes (the feed counts itself).
          (sut/register-route! "live-1" (fn [_] {:status 200 :body "x"}))
          (let [resp (http-get (str base "/s/registry?token=" token))]
            (is (= 200 (:status resp)))
            (is (re-find #"text/event-stream"
                         (str (get-in resp [:headers :content-type]))))
            (let [body (body-str resp)]
              (is (re-find #"data: open" body))
              (is (re-find #"data: routes 2" body)))))))))

(deftest ^:integration register-sse-route!-test
  ;; Exercises the public Slice 4 `register-sse-route!` REPL/dev surface end to
  ;; end: it must wrap an arbitrary `emit-fn` via `sse/make-handler`, register it
  ;; through `register-route!` as a token-gated session route, and return its
  ;; URL — none of which the `register-route!`-based feed tests cover.
  (testing "returns nil when the server is not running"
    (let [{:keys [api]} (nullable-api)]
      (sut/init api)
      (is (nil? (sut/register-sse-route!
                 "feed" (fn [send! _close!] (send! "tick")))))))
  (with-running-server
    (fn [{:keys [server]}]
      (let [base  (server-base server)
            token (:token server)
            url   (sut/register-sse-route!
                   "feed" (fn [send! close!] (send! "tick") (close!)))]
        (testing "returns the registered route URL"
          (is (some? url)))
        (testing "AC-8: the registered feed is token-gated like any session route"
          (is (= 403 (get-status (str base "/s/feed")))))
        (testing "the wrapped emit-fn streams data: open + data: tick over the server"
          (let [resp (http-get (str base "/s/feed?token=" token))]
            (is (= 200 (:status resp)))
            (is (re-find #"text/event-stream"
                         (str (get-in resp [:headers :content-type]))))
            (let [body (body-str resp)]
              (is (re-find #"data: open" body))
              (is (re-find #"data: tick" body)))))))))
