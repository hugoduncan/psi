(ns psi.session-profile.names
  "Shared selectable session-profile name grammar.

   This namespace is intentionally small and low-level so config, command,
   workflow-loader, and workflow-runtime boundaries can enforce one profile-name
   grammar without coupling runtime validation to config IO."
  (:require
   [clojure.string :as str]))

(def profile-name-token-pattern
  "The selectable `/session-profile <name>` token grammar, excluding any leading `:`."
  #"[A-Za-z0-9][A-Za-z0-9._-]*")

(defn valid-profile-name-token?
  "Return true when `token` can be typed as a profile name in `/session-profile`."
  [token]
  (boolean (and (string? token)
                (re-matches profile-name-token-pattern token))))

(defn valid-profile-name?
  "Return true when `profile-name` is a keyword addressable by `/session-profile`."
  [profile-name]
  (and (keyword? profile-name)
       (nil? (namespace profile-name))
       (valid-profile-name-token? (name profile-name))))

(defn normalize-profile-name-token
  "Normalize a bare or EDN-style keyword token string into a keyword.

   This mirrors `/session-profile` token spelling only; callers still decide
   whether a particular token is an action such as `clear` before normalization."
  [token]
  (when (string? token)
    (let [trimmed (str/trim token)]
      (when-not (str/blank? trimmed)
        (keyword (if (str/starts-with? trimmed ":")
                   (subs trimmed 1)
                   trimmed))))))
