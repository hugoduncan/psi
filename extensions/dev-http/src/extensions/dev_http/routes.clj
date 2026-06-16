(ns extensions.dev-http.routes
  "Persisted-route loader. Scans the extension-local `dev/` source path on the
   classpath for route-defining namespaces and collects their reitit route data.

   A persisted-route namespace lives under `extensions.dev-http.dev.*` and
   defines a `routes` var holding a vector of reitit routes (each `[path data]`).
   Dev-only; this source path is exposed solely via the extension's scoped `:dev`
   extra-path and never ships in a published jar."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def ^:private dev-resource-root "extensions/dev_http/dev")
(def ^:private dev-ns-prefix "extensions.dev-http.dev")

(defn- clj-files
  [root-dir]
  (->> (file-seq root-dir)
       (filter (fn [f] (and (.isFile f) (str/ends-with? (.getName f) ".clj"))))))

(defn- file->ns-sym
  [root-dir file]
  (let [rel  (-> (.toURI root-dir) (.relativize (.toURI file)) .getPath)
        stem (subs rel 0 (- (count rel) (count ".clj")))
        tail (->> (str/split stem #"/")
                  (map (fn [seg] (str/replace seg "_" "-")))
                  (str/join "."))]
    (symbol (str dev-ns-prefix "." tail))))

(defn- ns-routes
  [ns-sym]
  (require ns-sym)
  (when-let [v (ns-resolve ns-sym 'routes)]
    (let [r @v]
      (when (seq r) r))))

(defn routes-from-resource
  "Given a resolved dev-source-root `url` (or `nil`), require each route
   namespace under it and return a flat vector of reitit routes (each
   `[path data]`). Returns an empty vector when `url` is `nil` or does not use
   the `file` protocol (e.g. a `jar:` URL when running from a published jar),
   so route loading never scans or throws inside a jar."
  [url]
  (if (and url (= "file" (.getProtocol ^java.net.URL url)))
    (let [root-dir (io/file (.toURI url))]
      (->> (clj-files root-dir)
           (map (fn [f] (file->ns-sym root-dir f)))
           (mapcat ns-routes)
           vec))
    []))

(defn load-persisted-routes
  "Scan the extension-local `dev/` source path, require each route namespace,
   and return a flat vector of reitit routes (each `[path data]`). Returns an
   empty vector when the dev source path is absent (e.g. running from a jar)."
  []
  (routes-from-resource (io/resource dev-resource-root)))
