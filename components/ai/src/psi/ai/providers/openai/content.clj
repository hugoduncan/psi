(ns psi.ai.providers.openai.content
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [psi.ai.content :as ai-content])
  (:import [java.util UUID Base64]))

(defn normalize-part-type
  [part]
  (let [t (:type part)]
    (cond
      (keyword? t) (name t)
      (string? t) t
      :else nil)))

(defn join-parts
  [parts]
  (when (seq parts)
    (str/join "" parts)))

(defn string-fragment
  "Normalize provider text fragments to a plain string."
  [x]
  (letfn [(->text [v]
            (cond
              (nil? v) nil
              (string? v) v
              (number? v) (str v)
              (keyword? v) (name v)
              (sequential? v) (join-parts (keep ->text v))
              (map? v) (or (->text (:text v))
                           (->text (:content v))
                           (->text (:delta v))
                           (->text (:summary v))
                           (->text (:value v)))
              :else nil))]
    (->text x)))

(defn normalize-tool-arguments
  "Normalize streamed function arguments into a string payload."
  [args]
  (cond
    (nil? args) nil
    (string? args) args
    (map? args) (json/generate-string args)
    (sequential? args) (or (join-parts (keep string-fragment args))
                           (str args))
    :else (str args)))

(defn accumulate-tool-arguments
  "Merge incoming tool-argument chunk into current buffer."
  [current incoming]
  (let [cur (or current "")
        inc (or incoming "")]
    (cond
      (not (seq inc))
      {:buffer cur :delta nil}

      (str/starts-with? inc cur)
      (let [delta (subs inc (count cur))]
        {:buffer inc
         :delta  (when (seq delta) delta)})

      (str/starts-with? cur inc)
      {:buffer cur :delta nil}

      :else
      {:buffer (str cur inc)
       :delta  inc})))

(defn content-text
  [content]
  (or (ai-content/content-text content) ""))

(defn structured-blocks
  [msg]
  (ai-content/structured-blocks msg))

(defn text-blocks
  [blocks]
  (ai-content/text-blocks blocks))

(defn tool-call-blocks
  [blocks]
  (ai-content/tool-call-blocks blocks))

(defn join-block-text
  [blocks]
  (ai-content/join-text-blocks blocks))

(defn structured-text
  [msg]
  (ai-content/content-text msg))

(defn assistant-structured-content
  [msg]
  (let [{:keys [text-blocks tool-call-blocks]} (ai-content/assistant-content-parts msg)]
    {:text (ai-content/join-text-blocks text-blocks)
     :tool-calls tool-call-blocks}))

(defn user-message-text
  [msg]
  (let [content (:content msg)]
    (cond
      (string? content) content
      (and (map? content) (= :text (:kind content)))
      (content-text content)
      (and (map? content) (= :structured (:kind content)))
      (structured-text msg)
      (map? content)
      (content-text content)
      :else
      (str content))))

(defn new-msg-id [] (str "msg_" (UUID/randomUUID)))
(defn new-call-id [] (str "call_" (UUID/randomUUID)))

(defn tool-result-text
  [msg]
  (content-text (:content msg)))

(defn chat-tool-call
  [tool-call]
  {:id       (:id tool-call)
   :type     "function"
   :function {:name      (:name tool-call)
              :arguments (json/generate-string (:input tool-call))}})

(defn codex-tool-call-ids
  [tool-call]
  (let [raw-id (or (:id tool-call) (new-call-id))]
    (if (str/includes? raw-id "|")
      (str/split raw-id #"\|" 2)
      [raw-id nil])))

(defn codex-tool-call-item
  [tool-call]
  (let [[call-id item-id] (codex-tool-call-ids tool-call)]
    (cond-> {"type"      "function_call"
             "call_id"   call-id
             "name"      (or (:name tool-call) "tool")
             "arguments" (json/generate-string (or (:input tool-call) {}))}
      (seq item-id) (assoc "id" item-id))))

(defn codex-message-item
  [text]
  {"type"    "message"
   "role"    "assistant"
   "status"  "completed"
   "id"      (new-msg-id)
   "content" [{"type" "output_text"
               "text" text
               "annotations" []}]})

(defn pad-base64url
  "Pad base64url string to a multiple of 4 chars."
  [s]
  (let [m (mod (count s) 4)]
    (case m
      0 s
      2 (str s "==")
      3 (str s "=")
      1 (str s "===")
      s)))

(defn extract-chatgpt-account-id
  "Extract chatgpt_account_id from OAuth JWT access token."
  [token]
  (try
    (let [[_ payload _] (str/split (or token "") #"\." 3)
          decoded       (String. (.decode (Base64/getUrlDecoder)
                                          (pad-base64url payload))
                                 "UTF-8")
          json-map      (json/parse-string decoded false)]
      (get-in json-map ["https://api.openai.com/auth" "chatgpt_account_id"]))
    (catch Exception _
      nil)))
