(ns extensions.dev-http.renderers
  "Built-in declarative renderers for dev-http session content.

   A renderer turns a content map `{:renderer <kw> :data <value>}` into a ring
   response. Renderers are pure functions of their content map: no I/O beyond
   reading a file artifact for `:file`. Vega-Lite and Mermaid pages embed client
   JS served locally from the extension's vendored assets (no CDN/network).

   `data` may arrive with keyword keys (REPL/`register-route!`) or string keys
   (JSON from the `dev-present` tool); accessors below read both."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [extensions.dev-http.util :as util :refer [kget]]
   [hiccup2.core :as h])
  (:import
   (org.commonmark.parser Parser)
   (org.commonmark.renderer.html HtmlRenderer)))

(def asset-prefix
  "URL prefix under which vendored client JS is served (ungated static assets)."
  "/assets")

(def vendor-resource-root
  "Classpath resource root holding the vendored client JS."
  "dev_http/vendor")

(defn page
  "Wrap `body` hiccup forms in a minimal HTML document string with `title`."
  [title & body]
  (str (h/html
        [:html
         [:head
          [:meta {:charset "utf-8"}]
          [:title title]]
         (into [:body] body)])))

;;; ---------------------------------------------------------------------------
;;; input validation — fail loud, never silently degrade
;;;
;;; Each renderer has an expected `data` shape. Passing the wrong shape (e.g. a
;;; Clojure-syntax string where a hiccup tree is meant) must produce a loud,
;;; type-naming 400 rather than empty/garbage/literal output that hides the
;;; mistake.
;;; ---------------------------------------------------------------------------

(defn- value-type-name
  [x]
  (cond
    (nil? x)     "nil"
    (string? x)  "string"
    (keyword? x) "keyword"
    (number? x)  "number"
    (boolean? x) "boolean"
    (vector? x)  "vector"
    (map? x)     "map"
    :else        (.getName (class x))))

(defn- value-preview
  [x]
  (let [s (pr-str x)]
    (if (> (count s) 200) (str (subs s 0 200) "…") s)))

(defn- type-error-response
  [renderer-kw expected data]
  (util/text-response
   400
   (str "400 :" (name renderer-kw) " data must be " expected ", got "
        (value-type-name data) ": " (value-preview data))))

;;; ---------------------------------------------------------------------------
;;; markdown
;;; ---------------------------------------------------------------------------

(defn markdown->html
  "Render commonmark `markdown` text to an HTML fragment string."
  [markdown]
  (let [parser   (.build (Parser/builder))
        renderer (.build (HtmlRenderer/builder))]
    (.render renderer (.parse parser (str markdown)))))

(defn- render-markdown
  [{:keys [data]}]
  (if (string? data)
    (util/html-response (page "markdown" (h/raw (markdown->html data))))
    (type-error-response :markdown "a string" data)))

;;; ---------------------------------------------------------------------------
;;; table
;;; ---------------------------------------------------------------------------

(defn- render-table
  [{:keys [data]}]
  (if-not (map? data)
    (type-error-response :table "a map of {:headers [...] :rows [[...]]}" data)
    (let [headers (kget data :headers "headers")
          rows    (kget data :rows "rows")]
      (util/html-response
       (page "table"
             [:table {:border "1" :cellpadding "4" :cellspacing "0"}
              (when (seq headers)
                [:thead [:tr (for [h headers] [:th (str h)])]])
              [:tbody
               (for [row rows]
                 [:tr (for [cell row] [:td (str cell)])])]])))))

;;; ---------------------------------------------------------------------------
;;; hiccup escape hatch
;;; ---------------------------------------------------------------------------

(defn- coerce-hiccup
  "Coerce a JSON-decoded hiccup tree (string tags) into keyword tags so hiccup
   renders it as elements. Passes idiomatic hiccup through unchanged."
  [form]
  (cond
    (and (vector? form) (string? (first form)))
    (into [(keyword (first form))] (map coerce-hiccup (rest form)))

    (vector? form)
    (mapv coerce-hiccup form)

    :else form))

(defn- render-hiccup
  [{:keys [data]}]
  ;; A bare string/scalar is never a hiccup tree — hiccup would silently render
  ;; it as a literal text node. `sequential?` accepts vectors and seqs (a `for`
  ;; result), rejects strings/maps/scalars.
  (if (sequential? data)
    (util/html-response (str (h/html (coerce-hiccup data))))
    (type-error-response :hiccup "a hiccup tree (vector/array or seq of elements)" data)))

;;; ---------------------------------------------------------------------------
;;; file artifact
;;; ---------------------------------------------------------------------------

(def ^:private extension->content-type
  {"html" "text/html; charset=utf-8"
   "htm"  "text/html; charset=utf-8"
   "svg"  "image/svg+xml"
   "png"  "image/png"
   "jpg"  "image/jpeg"
   "jpeg" "image/jpeg"
   "gif"  "image/gif"
   "pdf"  "application/pdf"
   "json" "application/json"
   "css"  "text/css"
   "js"   "application/javascript"
   "txt"  "text/plain; charset=utf-8"})

(defn content-type-for
  "Resolve a content-type from a file `path` extension (octet-stream default)."
  [path]
  (let [ext (-> (str path) (str/split #"\.") last str/lower-case)]
    (get extension->content-type ext "application/octet-stream")))

(defn- render-file
  [{:keys [data]}]
  (if-not (map? data)
    (type-error-response :file "a map of {:path \"/abs/path\"}" data)
    (let [path (kget data :path "path")
          f    (io/file (str path))]
      (if (.isFile f)
        {:status  200
         :headers {"content-type" (content-type-for path)}
         :body    f}
        (util/text-response 404 (str "404 file not found: " path))))))

;;; ---------------------------------------------------------------------------
;;; vega-lite (vendored client JS)
;;; ---------------------------------------------------------------------------

(defn- render-vega
  [{:keys [data]}]
  (if-not (map? data)
    (type-error-response :vega "a Vega-Lite spec map" data)
    (util/html-response
     (page "vega"
           [:div {:id "vega-view"}]
           [:script {:src (str asset-prefix "/vega.min.js")}]
           [:script {:src (str asset-prefix "/vega-lite.min.js")}]
           [:script {:src (str asset-prefix "/vega-embed.min.js")}]
           [:script
            (h/raw
             (str "vegaEmbed('#vega-view', " (json/generate-string data) ");"))]))))

;;; ---------------------------------------------------------------------------
;;; mermaid (vendored client JS)
;;; ---------------------------------------------------------------------------

(defn- render-mermaid
  [{:keys [data]}]
  (if-not (string? data)
    (type-error-response :mermaid "a Mermaid diagram source string" data)
    (util/html-response
     (page "mermaid"
           [:pre {:class "mermaid"} data]
           [:script {:src (str asset-prefix "/mermaid.min.js")}]
           [:script (h/raw "mermaid.initialize({startOnLoad:true});")]))))

;;; ---------------------------------------------------------------------------
;;; dispatch
;;; ---------------------------------------------------------------------------

(def ^:private renderers
  {:markdown render-markdown
   :table    render-table
   :hiccup   render-hiccup
   :file     render-file
   :vega     render-vega
   :mermaid  render-mermaid})

(def renderer-keys
  "Set of supported declarative renderer keys."
  (set (keys renderers)))

(defn render
  "Render a content map `{:renderer <kw> :data <value>}` to a ring response.
   An unknown renderer yields a 400 response."
  [{:keys [renderer] :as content}]
  (if-let [f (get renderers (keyword renderer))]
    (f content)
    (util/text-response 400 (str "400 unknown renderer: " renderer))))

(defn asset-handler
  "Serve a vendored client-JS asset by file name from the classpath resource
   root. Rejects any path containing `..`. Ungated static content."
  [request]
  (let [file-name (get-in request [:path-params :asset])]
    (if (or (str/blank? file-name) (str/includes? file-name ".."))
      (util/text-response 404 "404 asset not found")
      (if-let [resource (io/resource (str vendor-resource-root "/" file-name))]
        {:status  200
         :headers {"content-type" (content-type-for file-name)}
         :body    (io/input-stream resource)}
        (util/text-response 404 (str "404 asset not found: " file-name))))))
