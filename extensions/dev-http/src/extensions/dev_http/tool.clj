(ns extensions.dev-http.tool
  "The `dev-present` model-callable tool: registers a dev-http session route from
   declarative content data and returns its URL. Replay-friendly and safe — the
   model drives it directly, targeting the built-in renderer set."
  (:require
   [clojure.string :as str]
   [extensions.dev-http.renderers :as renderers]
   [psi.tool-runtime.call-summary :as call-summary]))

(defn- renderer-names
  []
  (sort (map name renderers/renderer-keys)))

(def ^:private parameters
  {:type       "object"
   :properties {"renderer" {:type        "string"
                            :enum        (vec (renderer-names))
                            :description "Built-in renderer for the content"}
                "data"     {:description (str "Renderer-specific content payload: "
                                              "markdown/mermaid → a string; "
                                              "table → {headers, rows}; "
                                              "vega → a Vega-Lite spec; "
                                              "hiccup → a hiccup tree; "
                                              "file → {path}.")}
                "route-id" {:type        "string"
                            :description "Optional stable route id; re-registering replaces it"}}
   :required   ["renderer" "data"]})

(defn- gen-route-id
  []
  (str "r-" (subs (str (random-uuid)) 0 8)))

(defn dev-present-tool
  "Build the `dev-present` tool map. `register-content!` is a fn
   `(route-id content) → url-or-nil`, where nil means the server is not
   running."
  [register-content!]
  {:name           "dev-present"
   :label          "Dev Present"
   :description     (str "Present rich content to the developer in a browser via "
                         "the dev-http side channel. Registers a session route "
                         "rendering the content and returns its URL.")
   :parameters     parameters
   :format-request (call-summary/text-key-format-request "dev-present" "renderer")
   :execute
   (fn [args _opts]
     (let [renderer (some-> (get args "renderer") str/trim not-empty keyword)
           data     (get args "data")
           route-id (or (not-empty (str/trim (str (get args "route-id"))))
                        (gen-route-id))]
       (cond
         (not (contains? renderers/renderer-keys renderer))
         {:content  (str "Unknown renderer: " (pr-str (get args "renderer"))
                         ". Supported: " (str/join ", " (renderer-names)) ".")
          :is-error true}

         :else
         (if-let [url (register-content! route-id {:renderer renderer :data data})]
           {:content  (str "Registered route '" route-id "'. Open: " url)
            :is-error false}
           {:content  "dev-http server is not running; run /dev-http start first."
            :is-error true}))))})
