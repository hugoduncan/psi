(ns psi.github.test-support
  "Shared test stubs for github extension unit tests."
  (:require
   [cheshire.core :as json]))

(defn stub-shell
  "Returns a shell-fn stub that always returns a successful (exit 0) response
   with `items` serialised as JSON."
  [items]
  (fn [& _args]
    {:exit 0
     :out  (json/generate-string items)
     :err  ""}))

(defn error-shell
  "Returns a shell-fn stub that simulates a non-zero exit with `err-msg`."
  [err-msg]
  (fn [& _args]
    {:exit 1
     :out  ""
     :err  err-msg}))

(defn stub-shell-ok
  "Returns a shell-fn stub that always returns a successful (exit 0) empty response."
  []
  (fn [& _args]
    {:exit 0 :out "" :err ""}))

(defn stub-shell-error
  "Returns a shell-fn stub that simulates a non-zero exit with `err-msg`."
  [err-msg]
  (fn [& _args]
    {:exit 1 :out "" :err err-msg}))

(defn capturing-shell
  "Returns [shell-fn calls*] where calls* captures each invocation's arg list.
   Responds with `items` serialised as JSON."
  [items]
  (let [calls* (atom [])]
    [(fn [& args]
       (swap! calls* conj (vec args))
       {:exit 0
        :out  (json/generate-string items)
        :err  ""})
     calls*]))

(defn capturing-shell-ok
  "Returns [shell-fn calls*] where calls* captures each invocation's arg list.
   Always responds with exit 0 and empty output."
  []
  (let [calls* (atom [])]
    [(fn [& args]
       (swap! calls* conj (vec args))
       {:exit 0 :out "" :err ""})
     calls*]))
