(ns extensions.dev-http-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [extensions.dev-http :as sut]
   [extensions.dev-http.middleware :as mw]
   [extensions.dev-http.registry :as registry]
   [extensions.dev-http.router :as router]
   [extensions.dev-http.routes :as routes]
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
