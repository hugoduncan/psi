(ns psi.session-state.display-name
  "Canonical lower-level session display-name shaping for session listings.
   Owns compact explicit-name and last-user-message fallback logic without
   depending on higher-level UI projection namespaces."
  (:require
   [clojure.string :as str]))

(def ^:private default-display-name-max-chars 48)

(defn short-display-text
  [text]
  (let [normalized (some-> text str (str/replace #"\s+" " ") str/trim)]
    (when (seq normalized)
      (if (> (count normalized) default-display-name-max-chars)
        (str (subs normalized 0 (dec default-display-name-max-chars)) "…")
        normalized))))

(defn- slash-command-text?
  [text]
  (let [trimmed (some-> text str/trim)]
    (and (seq trimmed)
         (str/starts-with? trimmed "/"))))

(defn- message-content-text
  [content]
  (cond
    (nil? content) nil
    (string? content) content
    (sequential? content) (some->> content
                                   (keep (fn [block]
                                           (when (map? block)
                                             (or (:text block)
                                                 (:message block)
                                                 (:thinking block)))))
                                   seq
                                   (str/join "\n"))
    :else nil))

(defn user-message-display-text
  [message]
  (when (= "user" (:role message))
    (let [text (short-display-text (message-content-text (:content message)))]
      (when-not (slash-command-text? text)
        text))))

(defn session-display-name
  [session-name messages]
  (or (short-display-text session-name)
      (some->> (reverse (vec (or messages [])))
               (some user-message-display-text))))
