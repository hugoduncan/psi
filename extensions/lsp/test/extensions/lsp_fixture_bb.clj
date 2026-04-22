(ns extensions.lsp-fixture-bb
  (:require [cheshire.core :as json]
            [clojure.string :as str])
  (:import
   (java.io BufferedInputStream ByteArrayOutputStream)
   (java.nio.charset StandardCharsets)))

(defn read-line-crlf [^BufferedInputStream in]
  (let [buf (ByteArrayOutputStream.)]
    (loop [prev nil]
      (let [b (.read in)]
        (cond
          (= -1 b)
          (when (pos? (.size buf))
            (.toString buf "UTF-8"))

          (and (= prev 13) (= b 10))
          (.toString buf "UTF-8")

          :else
          (do
            (when-not (= b 13)
              (.write buf b))
            (recur b)))))))

(defn read-headers [^BufferedInputStream in]
  (loop [headers {}]
    (let [line (read-line-crlf in)]
      (cond
        (nil? line) (when (seq headers) headers)
        (empty? line) headers
        :else (let [[k v] (str/split line #":\s*" 2)]
                (recur (assoc headers (str/lower-case k) v)))))))

(defn read-exact [^BufferedInputStream in n]
  (let [buf (byte-array n)]
    (loop [off 0]
      (if (= off n)
        buf
        (let [m (.read in buf off (- n off))]
          (when (= -1 m)
            (System/exit 0))
          (recur (+ off m)))))))

(defn write-msg [payload]
  (let [body  (json/generate-string payload)
        bytes (.getBytes ^String body StandardCharsets/UTF_8)
        head  (.getBytes (str "Content-Length: " (alength bytes) "\r\n\r\n") StandardCharsets/UTF_8)]
    (.write System/out head)
    (.write System/out bytes)
    (.flush System/out)))

(defn uri->path [uri]
  (when (string? uri)
    (some-> uri
            (str/replace-first #"^file://" ""))))

(defn diagnostic-items [uri]
  (let [path (uri->path uri)
        text (try (slurp path) (catch Throwable _ ""))]
    (if (str/includes? text "warn")
      [{"message" (str "fixture diagnostic for " path)
        "severity" 2}]
      [])))

(let [in (BufferedInputStream. System/in)]
  (loop []
    (when-let [headers (read-headers in)]
      (let [n      (Long/parseLong (get headers "content-length" "0"))
            body   (String. (read-exact in n) StandardCharsets/UTF_8)
            msg    (json/parse-string body)
            id     (get msg "id")
            method (get msg "method")
            params (get msg "params")
            payload (cond
                      (and id (= method "initialize"))
                      {"jsonrpc" "2.0"
                       "id" id
                       "result" {"capabilities" {"diagnosticProvider" true
                                                 "textDocumentSync" {"openClose" true
                                                                     "change" 2}}}}

                      (and id (= method "textDocument/diagnostic"))
                      (let [uri (get-in params ["textDocument" "uri"])]
                        {"jsonrpc" "2.0"
                         "id" id
                         "result" {"kind" "full"
                                   "items" (diagnostic-items uri)}})

                      id
                      {"jsonrpc" "2.0"
                       "id" id
                       "result" {"echo" method
                                 "id" id}}

                      :else
                      {"jsonrpc" "2.0"
                       "method" "observed"
                       "params" {"method" method}})]
        (write-msg payload)
        (recur)))))
