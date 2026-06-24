(ns psi.provider-auth.oauth.providers-test
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [clj-http.client :as http]
   [psi.provider-auth.oauth.callback-server :as cb]
   [psi.provider-auth.oauth.providers :as providers]))

(deftest default-registry-includes-openai-test
  (testing "register-default-providers! registers both anthropic and openai"
    (providers/register-default-providers!)
    (is (some? (providers/get-provider :anthropic)))
    (is (some? (providers/get-provider :openai)))
    (is (true? (:uses-callback-server (providers/get-provider :openai))))))

(deftest anthropic-begin-login-default-config-test
  (testing "anthropic begin-login uses current defaults"
    (with-redefs [providers/get-env (constantly nil)
                  providers/open-browser! (fn [_] nil)]
      (let [{:keys [url login-state]}
            (#'psi.provider-auth.oauth.providers/anthropic-begin-login)]
        (is (str/includes? url "claude.ai/oauth/authorize"))
        (is (str/includes? url "client_id=9d1c250a-e61b-44d9-88ed-5944d1962f5e"))
        (is (str/includes? url "redirect_uri=https%3A%2F%2Fconsole.anthropic.com%2Foauth%2Fcode%2Fcallback"))
        (is (str/includes? url "scope=org%3Acreate_api_key+user%3Aprofile+user%3Ainference"))
        (is (string? (:verifier login-state)))
        (is (= "9d1c250a-e61b-44d9-88ed-5944d1962f5e" (:client-id login-state)))
        (is (= "https://console.anthropic.com/v1/oauth/token" (:token-url login-state)))
        (is (= "https://console.anthropic.com/oauth/code/callback" (:redirect-uri login-state)))))))

(deftest anthropic-begin-login-env-override-test
  (testing "anthropic begin-login supports env overrides"
    (let [env {"PSI_ANTHROPIC_OAUTH_CLIENT_ID" "client-xyz"
               "PSI_ANTHROPIC_OAUTH_AUTHORIZE_URL" "https://auth.example.test/oauth/authorize"
               "PSI_ANTHROPIC_OAUTH_TOKEN_URL" "https://auth.example.test/oauth/token"
               "PSI_ANTHROPIC_OAUTH_REDIRECT_URI" "https://console.example.test/oauth/cb"
               "PSI_ANTHROPIC_OAUTH_SCOPES" "scope:a scope:b"}]
      (with-redefs [providers/get-env (fn [k] (get env k))
                    providers/open-browser! (fn [_] nil)]
        (let [{:keys [url login-state]}
              (#'psi.provider-auth.oauth.providers/anthropic-begin-login)]
          (is (str/includes? url "auth.example.test/oauth/authorize"))
          (is (str/includes? url "client_id=client-xyz"))
          (is (str/includes? url "redirect_uri=https%3A%2F%2Fconsole.example.test%2Foauth%2Fcb"))
          (is (str/includes? url "scope=scope%3Aa+scope%3Ab"))
          (is (= "client-xyz" (:client-id login-state)))
          (is (= "https://auth.example.test/oauth/token" (:token-url login-state)))
          (is (= "https://console.example.test/oauth/cb" (:redirect-uri login-state)))
          (is (= "scope:a scope:b" (:scopes login-state))))))))

(deftest anthropic-complete-login-parses-code-state-fragment-test
  (testing "anthropic complete-login accepts code=...#state=... input"
    (let [captured (atom nil)]
      (with-redefs [providers/get-env (constantly nil)
                    http/post (fn [url opts]
                                (reset! captured {:url url :opts opts})
                                {:body {:access_token "ant-access"
                                        :refresh_token "ant-refresh"
                                        :expires_in 3600}})]
        (let [cred (#'psi.provider-auth.oauth.providers/anthropic-complete-login
                    "code=test-code#state=test-state"
                    {:verifier "test-state"
                     :client-id "client-override"
                     :token-url "https://auth.example.test/oauth/token"
                     :redirect-uri "https://console.example.test/oauth/cb"
                     :scopes "scope:a scope:b"})
              body (json/parse-string (get-in @captured [:opts :body]) false)]
          (is (= :oauth (:type cred)))
          (is (= "ant-access" (:access cred)))
          (is (= "ant-refresh" (:refresh cred)))
          (is (= "https://auth.example.test/oauth/token" (:url @captured)))
          (is (= "test-code" (get body "code")))
          (is (= "test-state" (get body "state")))
          (is (= "client-override" (get body "client_id")))
          (is (= "https://console.example.test/oauth/cb" (get body "redirect_uri")))
          (is (= {:client-id "client-override"
                  :token-url "https://auth.example.test/oauth/token"
                  :redirect-uri "https://console.example.test/oauth/cb"
                  :scopes "scope:a scope:b"}
                 (:metadata cred))))))))

(deftest openai-complete-login-parses-full-url-test
  (testing "openai complete-login accepts full redirect URL input"
    (let [captured (atom nil)]
      (with-redefs [http/post (fn [url opts]
                                (reset! captured {:url url :opts opts})
                                {:body {:access_token "oa-access"
                                        :refresh_token "oa-refresh"
                                        :expires_in 3600}})]
        (let [cred (#'psi.provider-auth.oauth.providers/openai-complete-login
                    "http://localhost:1455/auth/callback?code=test-code&state=test-state"
                    {:verifier "test-verifier"
                     :state    "test-state"
                     :redirect-uri "http://localhost:1455/auth/callback"})]
          (is (= :oauth (:type cred)))
          (is (= "oa-access" (:access cred)))
          (is (= "oa-refresh" (:refresh cred)))
          (is (number? (:expires cred)))
          (is (= "test-code"
                 (get-in @captured [:opts :form-params "code"]))))))))

(deftest openai-complete-login-uses-callback-server-test
  (testing "openai complete-login waits for callback when input is blank"
    (let [wait-called (atom nil)
          closed?     (atom false)
          captured    (atom nil)
          callback    {:wait-for-code (fn [timeout-ms]
                                        (reset! wait-called timeout-ms)
                                        {:code "cb-code" :state "cb-state"})
                       :close (fn [] (reset! closed? true))}]
      (with-redefs [http/post (fn [_url opts]
                                (reset! captured opts)
                                {:body {:access_token "oa-access"
                                        :refresh_token "oa-refresh"
                                        :expires_in 3600}})]
        (let [cred (#'psi.provider-auth.oauth.providers/openai-complete-login
                    nil
                    {:verifier "v"
                     :state "cb-state"
                     :callback-server callback
                     :redirect-uri "http://localhost:1455/auth/callback"})]
          (is (= :oauth (:type cred)))
          (is (= 180000 @wait-called))
          (is @closed?)
          (is (= "cb-code" (get-in @captured [:form-params "code"]))))))))

(deftest openai-complete-login-state-mismatch-test
  (testing "state mismatch throws"
    (with-redefs [http/post (fn [_url _opts]
                              {:body {:access_token "x" :refresh_token "y" :expires_in 3600}})]
      (is (thrown-with-msg? Exception #"State mismatch"
                            (#'psi.provider-auth.oauth.providers/openai-complete-login
                             "code#wrong-state"
                             {:verifier "v" :state "expected"}))))))

(deftest openai-begin-login-starts-callback-server-test
  (testing "openai begin-login includes callback server in login-state"
    (with-redefs [cb/start-server
                  (fn [_opts]
                    {:port 1455
                     :wait-for-code (fn [_] nil)
                     :close (fn [])})
                  providers/open-browser!
                  (fn [_url] nil)]
      (let [{:keys [url login-state]}
            (#'psi.provider-auth.oauth.providers/openai-begin-login)]
        (is (str/includes? url "auth.openai.com/oauth/authorize"))
        (is (str/includes? url "redirect_uri=http%3A%2F%2Flocalhost%3A1455%2Fauth%2Fcallback"))
        (is (string? (:verifier login-state)))
        (is (string? (:state login-state)))
        (is (= "http://localhost:1455/auth/callback" (:redirect-uri login-state)))
        (is (map? (:callback-server login-state)))))))
