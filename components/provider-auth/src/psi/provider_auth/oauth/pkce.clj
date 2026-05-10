(ns psi.provider-auth.oauth.pkce
  "PKCE (Proof Key for Code Exchange) for OAuth 2.0.

   Generates code verifier and S256 challenge per RFC 7636."
  (:import [java.security MessageDigest SecureRandom]
           [java.util Base64]))

(defn- base64url-encode
  "Encode bytes as base64url (no padding)."
  ^String [^bytes bs]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs))

(defn generate-verifier
  "Generate a random 32-byte code verifier, base64url-encoded."
  []
  (let [bs (byte-array 32)]
    (.nextBytes (SecureRandom.) bs)
    (base64url-encode bs)))

(defn compute-challenge
  "Compute S256 challenge from a code verifier."
  ^String [^String verifier]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (base64url-encode (.digest digest (.getBytes verifier "UTF-8")))))

(defn generate-pkce
  "Generate a PKCE verifier + challenge pair.
   Returns {:verifier \"...\" :challenge \"...\"}."
  []
  (let [v (generate-verifier)]
    {:verifier  v
     :challenge (compute-challenge v)}))
