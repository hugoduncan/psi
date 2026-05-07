(ns psi.provider-auth.oauth.pkce-test
  (:require [clojure.test :refer [deftest is testing]]
            [psi.provider-auth.oauth.pkce :as pkce]))

(deftest generate-pkce-test
  ;; PKCE verifier + challenge generation per RFC 7636
  (testing "returns verifier and challenge"
    (let [{:keys [verifier challenge]} (pkce/generate-pkce)]
      (is (string? verifier))
      (is (string? challenge))
      (is (not= verifier challenge))))

  (testing "verifier is base64url encoded (no +, /, =)"
    (let [{:keys [verifier]} (pkce/generate-pkce)]
      (is (not (re-find #"[+/=]" verifier)))))

  (testing "challenge is base64url encoded"
    (let [{:keys [challenge]} (pkce/generate-pkce)]
      (is (not (re-find #"[+/=]" challenge)))))

  (testing "challenge is deterministic for same verifier"
    (let [v "test-verifier-string"]
      (is (= (pkce/compute-challenge v)
             (pkce/compute-challenge v)))))

  (testing "different verifiers produce different challenges"
    (let [{p1 :challenge} (pkce/generate-pkce)
          {p2 :challenge} (pkce/generate-pkce)]
      (is (not= p1 p2)))))
