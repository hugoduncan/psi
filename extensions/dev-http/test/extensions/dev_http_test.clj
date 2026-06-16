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
   [org.httpkit.client :as http-client]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(defn- reset-state-fixture
  [f]
  (sut/stop!)
  (f)
  (sut/stop!))

(use-fixtures :each reset-state-fixture)

(deftest init-captures-api-test
  (testing "init captures the runtime ExtensionAPI map into the extension atom"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/dev_http.clj"})]
      (is (nil? (sut/init api)))
      (is (identical? api (:api @@#'sut/state)))
      (testing "registers the /dev-http command"
        (is (contains? (:commands @state) "dev-http"))))))

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
  [reg token]
  (router/build-handler {:registry         reg
                         :token            token
                         :persisted-routes (routes/load-persisted-routes)}))

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
      (let [kw  (renderers/render {:renderer :table :data {:headers ["a" "b"] :rows [[1 2]]}})
            str (renderers/render {:renderer :table :data {"headers" ["a"] "rows" [[9]]}})]
        (is (re-find #"<th>a</th><th>b</th>" (:body kw)))
        (is (re-find #"<td>1</td><td>2</td>" (:body kw)))
        (is (re-find #"<th>a</th>" (:body str)))
        (is (re-find #"<td>9</td>" (:body str)))))
    (testing ":hiccup renders a JSON-decoded (string-tag) tree as elements"
      (let [resp (renderers/render {:renderer :hiccup :data ["div" {} ["h1" "X"]]})]
        (is (= "<div><h1>X</h1></div>" (:body resp)))))
    (testing ":vega embeds the vendored client JS and the spec as JSON"
      (let [resp (renderers/render {:renderer :vega :data {"mark" "bar"}})]
        (is (re-find #"/assets/vega-lite\.min\.js" (:body resp)))
        (is (re-find #"/assets/vega-embed\.min\.js" (:body resp)))
        (is (re-find #"\{\"mark\":\"bar\"\}" (:body resp)))))
    (testing ":mermaid embeds the vendored client JS and the source"
      (let [resp (renderers/render {:renderer :mermaid :data "graph TD; A-->B"})]
        (is (re-find #"/assets/mermaid\.min\.js" (:body resp)))
        (is (re-find #"class=\"mermaid\"" (:body resp)))))
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
          handler (router/build-handler {:registry reg :token "tok" :persisted-routes []})
          resp    (handler {:request-method :get
                            :uri            (str renderers/asset-prefix "/vega-embed.min.js")})]
      (is (= 200 (:status resp)))
      (is (= "application/javascript" (get-in resp [:headers "content-type"]))))))

;; ---------------------------------------------------------------------------
;; dev-present tool (Slice 2)
;; ---------------------------------------------------------------------------

(deftest dev-present-tool-test
  (testing "the tool registers a content route and returns its URL"
    (let [captured (atom nil)
          register! (fn [route-id content]
                      (reset! captured {:route-id route-id :content content})
                      (str "http://127.0.0.1:9/s/" route-id "?token=t"))
          tool      (tool/dev-present-tool register!)
          execute   (:execute tool)]
      (testing "tool metadata"
        (is (= "dev-present" (:name tool)))
        (is (fn? (:format-request tool))))
      (testing "valid renderer → registers content + returns URL"
        (let [result (execute {"renderer" "markdown" "data" "# Hi" "route-id" "md"} {})]
          (is (false? (:is-error result)))
          (is (re-find #"Open: http://127\.0\.0\.1:9/s/md" (:content result)))
          (is (= :markdown (get-in @captured [:content :renderer])))
          (is (= "# Hi" (get-in @captured [:content :data])))
          (is (= "md" (:route-id @captured)))))
      (testing "absent route-id is generated"
        (let [result (execute {"renderer" "table" "data" {"rows" []}} {})]
          (is (false? (:is-error result)))
          (is (string? (:route-id @captured)))
          (is (re-find #"^r-" (:route-id @captured)))))
      (testing "opts :session-id threads into the registered content (invoking session only)"
        (let [result (execute {"renderer" "choices"
                               "data"     {"prompt" "Pick" "options" ["a" "b"]}
                               "route-id" "c"}
                              {:session-id "sess-x"})]
          (is (false? (:is-error result)))
          (is (= :choices (get-in @captured [:content :renderer])))
          (is (= "sess-x" (get-in @captured [:content :session-id])))))
      (testing "absent opts :session-id threads nil (no fabricated session)"
        (let [result (execute {"renderer" "markdown" "data" "# Hi" "route-id" "n"} {})]
          (is (false? (:is-error result)))
          (is (contains? (get-in @captured [:content]) :session-id))
          (is (nil? (get-in @captured [:content :session-id])))))
      (testing "unknown renderer → error, no registration"
        (reset! captured :unchanged)
        (let [result (execute {"renderer" "bogus" "data" "x"} {})]
          (is (true? (:is-error result)))
          (is (re-find #"Unknown renderer" (:content result)))
          (is (= :unchanged @captured))))))
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

(deftest choices-handler-test
  (let [{:keys [api state]} (nullable/create-nullable-extension-api
                             {:path "/test/dev_http.clj"})
        reg       (registry/create-registry)
        content   {:renderer   :choices
                   :data       {:prompt "Pick one" :options ["A" "B"]}
                   :session-id "nullable-session"}
        handler   (choices/make-handler {:registry   reg
                                         :route-id   "c1"
                                         :session-id "nullable-session"
                                         :api        api
                                         :content    content})]
    (registry/register-entry! reg "c1" {})
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
        (is (= "nullable-session" (:session-id (:params (first muts)))))
        (is (= "A" (:user-msg (:params (first muts)))))))
    (testing "AC-7: single-shot — a second submission is rejected, no second injection"
      (let [resp (handler {:request-method :post :body "choice=B"})]
        (is (= 200 (:status resp)))
        (is (re-find #"Already answered" (:body resp)))
        (is (= 1 (count (submit-prompt-mutations state))))))
    (testing "a blank choice is a 400"
      (let [reg2     (registry/create-registry)
            handler2 (choices/make-handler {:registry   reg2
                                            :route-id   "c2"
                                            :session-id "nullable-session"
                                            :api        api
                                            :content    content})]
        (is (= 400 (:status (handler2 {:request-method :post :body ""}))))))))

(deftest choices-failed-injection-releases-claim-test
  ;; AC-6/AC-7 robustness: a failed synthetic-prompt injection must NOT consume
  ;; the single-shot (zero user messages were injected) and must NOT report a
  ;; false success. The claim is released so the choice can be retried. The
  ;; failing/throwing mutate seam is supplied through the sanctioned nullable
  ;; `:mutate-fn` override (not a bespoke spy map); the rendered page and the
  ;; registry claim state are the assertion signals.
  (let [content {:renderer   :choices
                 :data       {:prompt "Pick one" :options ["A" "B"]}
                 :session-id "nullable-session"}]
    (testing "a falsey prompt-submitted? renders a failure page and releases the claim"
      (let [outcome       (atom {:psi.extension/prompt-submitted? false})
            {:keys [api]} (nullable/create-nullable-extension-api
                           {:path      "/test/dev_http.clj"
                            :mutate-fn (fn [_op _params] @outcome)})
            reg           (registry/create-registry)
            handler       (choices/make-handler {:registry   reg
                                                 :route-id   "c1"
                                                 :session-id "nullable-session"
                                                 :api        api
                                                 :content    content})]
        (registry/register-entry! reg "c1" {})
        (let [resp (handler {:request-method :post :body "choice=A"})]
          (is (= 200 (:status resp)))
          (is (re-find #"try again" (:body resp)))
          (is (not (re-find #"Recorded" (:body resp))))
          (testing "claim released → not marked answered, retry can succeed"
            (is (not (:answered? (registry/get-entry reg "c1"))))
            (reset! outcome {:psi.extension/prompt-submitted? true})
            (let [resp2 (handler {:request-method :post :body "choice=A"})]
              (is (re-find #"Recorded" (:body resp2)))
              (is (:answered? (registry/get-entry reg "c1"))))))))
    (testing "a throwing mutation renders a failure page and releases the claim"
      (let [{:keys [api]} (nullable/create-nullable-extension-api
                           {:path      "/test/dev_http.clj"
                            :mutate-fn (fn [_op _params] (throw (ex-info "boom" {})))})
            reg           (registry/create-registry)
            handler       (choices/make-handler {:registry   reg
                                                 :route-id   "c1"
                                                 :session-id "nullable-session"
                                                 :api        api
                                                 :content    content})]
        (registry/register-entry! reg "c1" {})
        (let [resp (handler {:request-method :post :body "choice=A"})]
          (is (= 200 (:status resp)))
          (is (re-find #"try again" (:body resp)))
          (is (not (:answered? (registry/get-entry reg "c1")))))))))

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
      (let [{:keys [api state]} (nullable/create-nullable-extension-api
                                 {:path "/test/dev_http.clj"})
            reg       (registry/create-registry)
            content   {:renderer   :choices
                       :data       {:prompt "Pick one" :options options}
                       :session-id "nullable-session"}
            handler   (choices/make-handler {:registry   reg
                                             :route-id   "c1"
                                             :session-id "nullable-session"
                                             :api        api
                                             :content    content})]
        (registry/register-entry! reg "c1" {})
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

;; ---------------------------------------------------------------------------
;; SSE live-updates (Slice 4)
;; ---------------------------------------------------------------------------

(deftest sse-event-format-test
  (testing "an SSE event is a data: block terminated by a blank line"
    (is (= "data: hello\n\n" (sse/event "hello")))))

(deftest sse-route-is-token-gated-test
  (testing "an SSE feed registered as a session route sits inside the token-gated subtree"
    (let [reg     (registry/create-registry)
          handler (router/build-handler {:registry reg :token "tok" :persisted-routes []})]
      (registry/register-entry! reg "registry"
                                {:handler (sse/registry-feed-handler reg)})
      (testing "no token → 403 (never reaches the event stream)"
        (is (= 403 (:status (handler {:request-method :get :uri "/s/registry"}))))))))

;; ---------------------------------------------------------------------------
;; lifecycle (integration — real ephemeral-port http-kit server)
;; ---------------------------------------------------------------------------

(defn- get-status
  [url]
  (:status @(http-client/get url)))

(defn- body-str
  [resp]
  (let [b (:body resp)]
    (if (instance? java.io.InputStream b) (slurp b) (str b))))

(deftest ^:integration lifecycle-and-serving-test
  (let [{:keys [api]} (nullable/create-nullable-extension-api
                       {:path "/test/dev_http.clj"})]
    (sut/init api)
    (testing "not running before start"
      (is (= "dev-http not running" (sut/status-text))))
    (let [server (sut/start!)
          base   (str "http://" (:host server) ":" (:port server))]
      (try
        (testing "AC-1: binds 127.0.0.1 on an ephemeral port"
          (is (= "127.0.0.1" (:host server)))
          (is (pos? (:port server)))
          (is (string? (:token server))))
        (testing "AC-8: access requires the token"
          (is (= 403 (get-status (str base "/demo"))))
          (is (= 200 (get-status (str base "/demo?token=" (:token server))))))
        (testing "AC-2: persisted demo route is served"
          (let [resp @(http-client/get (str base "/demo?token=" (:token server)))]
            (is (= 200 (:status resp)))
            (is (re-find #"dev-http demo" (body-str resp)))))
        (testing "AC-4 (partial): register-route! reachable, last-write-wins"
          (let [url1 (sut/register-route! "rt" (fn [_] {:status 200 :body "first"}))]
            (is (= 200 (get-status url1)))
            (is (= "first" (body-str @(http-client/get url1))))
            (sut/register-route! "rt" (fn [_] {:status 200 :body "second"}))
            (is (= "second" (body-str @(http-client/get (sut/route-url "rt")))))))
        (testing "AC-3: register-content-route! renders a content route"
          (let [url (sut/register-content-route! "md" {:renderer :markdown
                                                       :data     "# Live"})]
            (is (= 200 (get-status url)))
            (is (re-find #"<h1>Live</h1>" (body-str @(http-client/get url))))))
        (testing "AC-5: vendored asset served locally and ungated over the wire"
          (let [resp @(http-client/get (str base renderers/asset-prefix
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
            url1 (str "http://" (:host s1) ":" (:port s1)
                      "/demo?token=" (:token s1))]
        (try
          (is (= 200 (get-status url1)))
          (let [s2 (sut/start!)]
            (is (pos? (:port s2)))
            (is (= 200 (get-status (str "http://" (:host s2) ":" (:port s2)
                                        "/demo?token=" (:token s2)))))
            ;; The prior server must actually be gone — not merely supplanted.
            ;; If the new launch re-bound the *same* ephemeral port, that itself
            ;; proves the old server released it. Otherwise the old URL must no
            ;; longer serve (its listening socket was closed by synchronous
            ;; halt). Without one of these, a regression that stopped halting the
            ;; prior `:system` would leave s1 still listening yet pass the suite.
            (if (= (:port s1) (:port s2))
              (is (= (:port s1) (:port s2))
                  "old ephemeral port was freed and re-bound — prior server released it")
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
  (let [{:keys [api state]} (nullable/create-nullable-extension-api
                             {:path "/test/dev_http.clj"})]
    (sut/init api)
    (sut/start!)
    (try
      (let [tool (get-in @state [:tools "dev-present"])]
        (is (some? tool) "init registered the dev-present tool")
        (let [result ((:execute tool)
                      {"renderer" "markdown" "data" "# Tool Live" "route-id" "tool-md"}
                      {:session-id "nullable-session"})
              url    (second (re-find #"Open: (\S+)" (str (:content result))))]
          (is (false? (:is-error result)))
          (is (string? url))
          (let [resp @(http-client/get url)]
            (is (= 200 (:status resp)))
            (is (re-find #"<h1>Tool Live</h1>" (body-str resp))))))
      (finally
        (sut/stop!)))))

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
  (let [{:keys [api state]} (nullable/create-nullable-extension-api
                             {:path "/test/dev_http.clj"})]
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
                          :body    body}))

(deftest ^:integration choices-interaction-loop-test
  ;; Drives the choice loop over the real http-kit server: GET form → POST
  ;; choice → mutate-session (captured by the nullable api) → single-shot guard.
  (let [{:keys [api state]} (nullable/create-nullable-extension-api
                             {:path "/test/dev_http.clj"})]
    (sut/init api)
    (sut/start!)
    (try
      (let [url (sut/register-content-route! "ch" {:renderer   :choices
                                                   :data       {:prompt  "Pick one"
                                                                :options ["A" "B"]}
                                                   :session-id "nullable-session"})]
        (testing "the choices form is served"
          (let [resp @(http-client/get url)]
            (is (= 200 (:status resp)))
            (is (re-find #"Pick one" (body-str resp)))))
        (testing "AC-6: submitting injects one user message into the origin session"
          (let [resp (post-form url "choice=A")
                muts (filter #(= 'psi.extension/submit-synthetic-prompt (:op %))
                             (:mutations @state))]
            (is (= 200 (:status resp)))
            (is (re-find #"Recorded" (body-str resp)))
            (is (= 1 (count muts)))
            (is (= "A" (:user-msg (:params (first muts)))))
            (is (= "nullable-session" (:session-id (:params (first muts)))))))
        (testing "AC-7: a second submission is rejected with no further injection"
          (let [resp (post-form url "choice=B")
                muts (filter #(= 'psi.extension/submit-synthetic-prompt (:op %))
                             (:mutations @state))]
            (is (re-find #"Already answered" (body-str resp)))
            (is (= 1 (count muts))))))
      (finally
        (sut/stop!)))))

(deftest ^:integration sse-live-feed-test
  ;; Connects to the real SSE feed over the ephemeral-port server and reads the
  ;; pushed snapshot, which reflects current registry state.
  (let [{:keys [api]} (nullable/create-nullable-extension-api
                       {:path "/test/dev_http.clj"})]
    (sut/init api)
    (let [server (sut/start!)
          base   (str "http://" (:host server) ":" (:port server))
          token  (:token server)]
      (try
        (testing "AC-8: the SSE feed (a session route registered at start!) requires the token"
          (is (= 403 (get-status (str base "/s/registry")))))
        (testing "a connected client receives a pushed snapshot event"
          ;; start! auto-registered the `registry` feed itself; add one more so
          ;; the snapshot reflects both session routes (the feed counts itself).
          (sut/register-route! "live-1" (fn [_] {:status 200 :body "x"}))
          (let [resp @(http-client/get (str base "/s/registry?token=" token))]
            (is (= 200 (:status resp)))
            (is (re-find #"text/event-stream"
                         (str (get-in resp [:headers :content-type]))))
            (let [body (body-str resp)]
              (is (re-find #"data: open" body))
              (is (re-find #"data: routes 2" body)))))
        (finally
          (sut/stop!))))))
