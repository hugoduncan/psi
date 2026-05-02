(ns psi.ai.proxy-test
  (:require [clojure.test :refer [deftest is testing]]
            [psi.ai.proxy :as proxy]))

(deftest normalize-proxy-env-test
  (testing "uppercase wins over lowercase and blanks are ignored"
    (is (= {:http "http://upper-http:8080"
            :https "http://upper-https:8443"
            :all "socks5://upper-all:1080"}
           (proxy/normalize-proxy-env {"HTTP_PROXY" "http://upper-http:8080"
                                       "http_proxy" "http://lower-http:8080"
                                       "HTTPS_PROXY" "http://upper-https:8443"
                                       "https_proxy" ""
                                       "ALL_PROXY" "socks5://upper-all:1080"
                                       "all_proxy" "socks5://lower-all:1080"}))))

  (testing "missing and blank values are omitted"
    (is (= {}
           (proxy/normalize-proxy-env {"HTTP_PROXY" ""
                                       "HTTPS_PROXY" "   "})))))

(deftest effective-proxy-for-url-test
  (testing "scheme-specific proxies beat all-proxy"
    (is (= {:source-env :https
            :raw-uri "http://secure-proxy.example:8443"
            :scheme "http"
            :host "secure-proxy.example"
            :port 8443}
           (proxy/effective-proxy-for-url {:https "http://secure-proxy.example:8443"
                                           :all "socks5://fallback.example:1080"}
                                          "https://api.example.com/v1/chat")))
    (is (= {:source-env :http
            :raw-uri "http://plain-proxy.example:8080"
            :scheme "http"
            :host "plain-proxy.example"
            :port 8080}
           (proxy/effective-proxy-for-url {:http "http://plain-proxy.example:8080"
                                           :all "socks5://fallback.example:1080"}
                                          "http://api.example.com/v1/chat"))))

  (testing "all-proxy is the fallback"
    (is (= {:source-env :all
            :raw-uri "socks5://fallback.example:1080"
            :scheme "socks5"
            :host "fallback.example"
            :port 1080}
           (proxy/effective-proxy-for-url {:all "socks5://fallback.example:1080"}
                                          "https://api.example.com/v1/chat"))))

  (testing "non-http schemes are ignored"
    (is (nil? (proxy/effective-proxy-for-url {:all "http://proxy.example:8080"}
                                             "ws://api.example.com/socket")))))

(deftest proxy-request-options-test
  (testing "credentials and scheme are projected into clj-http options"
    (is (= {:proxy-host "proxy.example"
            :proxy-port 8080
            :proxy-scheme :http
            :proxy-user "user"
            :proxy-pass "pass"}
           (-> {:source-env :http
                :raw-uri "http://user:pass@proxy.example:8080"
                :scheme "http"
                :host "proxy.example"
                :port 8080
                :username "user"
                :password "pass"}
               proxy/proxy-request-options)))))

(deftest request-proxy-options-test
  (testing "env-backed helper returns final request options"
    (is (= {:proxy-host "proxy.example"
            :proxy-port 8080
            :proxy-scheme :http}
           (proxy/request-proxy-options {"HTTPS_PROXY" "http://proxy.example:8080"}
                                        "https://api.example.com/v1/messages"))))

  (testing "no matching proxy returns nil"
    (is (nil? (proxy/request-proxy-options {}
                                           "https://api.example.com/v1/messages")))))

(deftest invalid-proxy-uri-test
  (testing "malformed uri names the offending env var"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid proxy URI in https"
         (proxy/effective-proxy-for-url {:https "://bad"}
                                        "https://api.example.com/v1/messages"))))

  (testing "missing port fails explicitly"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"must include a numeric port"
         (proxy/effective-proxy-for-url {:https "http://proxy.example"}
                                        "https://api.example.com/v1/messages"))))

  (testing "unsupported schemes fail explicitly"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Unsupported proxy scheme"
         (proxy/effective-proxy-for-url {:https "ftp://proxy.example:21"}
                                        "https://api.example.com/v1/messages")))))
