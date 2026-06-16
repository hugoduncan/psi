(ns extensions.dev-http-test
  (:require
   [clojure.java.io :as io]
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
        (is (= 404 (:status resp)))))))

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

(defn- capturing-api
  [submitted]
  {:mutate-session (fn [session-id op params]
                     (swap! submitted conj {:session-id session-id :op op :params params})
                     {:psi.extension/prompt-submitted? true})})

(deftest choices-handler-test
  (let [reg       (registry/create-registry)
        submitted (atom [])
        content   {:renderer   :choices
                   :data       {:prompt "Pick one" :options ["A" "B"]}
                   :session-id "sess-1"}
        handler   (choices/make-handler {:registry   reg
                                         :route-id   "c1"
                                         :session-id "sess-1"
                                         :api        (capturing-api submitted)
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
      (let [resp (handler {:request-method :post :body "choice=A"})]
        (is (= 200 (:status resp)))
        (is (re-find #"Recorded" (:body resp)))
        (is (= 1 (count @submitted)))
        (is (= "sess-1" (:session-id (first @submitted))))
        (is (= 'psi.extension/submit-synthetic-prompt (:op (first @submitted))))
        (is (= "A" (:user-msg (:params (first @submitted)))))))
    (testing "AC-7: single-shot — a second submission is rejected, no second injection"
      (let [resp (handler {:request-method :post :body "choice=B"})]
        (is (= 200 (:status resp)))
        (is (re-find #"Already answered" (:body resp)))
        (is (= 1 (count @submitted)))))
    (testing "a blank choice is a 400"
      (let [reg2     (registry/create-registry)
            handler2 (choices/make-handler {:registry   reg2
                                            :route-id   "c2"
                                            :session-id "sess-1"
                                            :api        (capturing-api (atom []))
                                            :content    content})]
        (is (= 400 (:status (handler2 {:request-method :post :body ""}))))))))

;; ---------------------------------------------------------------------------
;; SSE live-updates (Slice 4)
;; ---------------------------------------------------------------------------

(deftest sse-event-format-test
  (testing "an SSE event is a data: block terminated by a blank line"
    (is (= "data: hello\n\n" (sse/event "hello")))))

(deftest sse-route-is-token-gated-test
  (testing "the /sse/registry feed sits inside the token-gated subtree"
    (let [reg     (registry/create-registry)
          handler (router/build-handler {:registry reg :token "tok" :persisted-routes []})]
      (testing "no token → 403 (never reaches the event stream)"
        (is (= 403 (:status (handler {:request-method :get :uri "/sse/registry"}))))))))

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
      (let [s1 (sut/start!)]
        (try
          (is (= 200 (get-status (str "http://" (:host s1) ":" (:port s1)
                                      "/demo?token=" (:token s1)))))
          (let [s2 (sut/start!)]
            (is (pos? (:port s2)))
            (is (= 200 (get-status (str "http://" (:host s2) ":" (:port s2)
                                        "/demo?token=" (:token s2))))))
          (finally
            (sut/stop!)))))))

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
        (testing "AC-8: the SSE feed requires the token"
          (is (= 403 (get-status (str base "/sse/registry")))))
        (testing "a connected client receives a pushed snapshot event"
          (sut/register-route! "live-1" (fn [_] {:status 200 :body "x"}))
          (let [resp @(http-client/get (str base "/sse/registry?token=" token))]
            (is (= 200 (:status resp)))
            (is (re-find #"text/event-stream"
                         (str (get-in resp [:headers :content-type]))))
            (let [body (body-str resp)]
              (is (re-find #"data: open" body))
              (is (re-find #"data: routes 1" body)))))
        (finally
          (sut/stop!))))))
