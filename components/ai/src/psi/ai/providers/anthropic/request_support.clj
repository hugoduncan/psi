(ns psi.ai.providers.anthropic.request-support
  (:require [clojure.string :as str]
            [cheshire.core :as json]))

(defn parse-json-body-safe
  [body]
  (try
    (json/parse-string (str (or body "")) true)
    (catch Exception _
      (str (or body "")))))

(defn split-beta-values
  [beta-header]
  (if (string? beta-header)
    (->> (str/split beta-header #",")
         (map str/trim)
         (remove str/blank?)
         vec)
    []))

(defn set-beta-values
  [headers betas]
  (cond-> (or headers {})
    (seq betas) (assoc "anthropic-beta" (str/join "," betas))
    (empty? betas) (dissoc "anthropic-beta")))

(defn remove-beta-values
  [headers remove-set]
  (let [betas* (->> (split-beta-values (get headers "anthropic-beta"))
                    (remove remove-set)
                    vec)]
    (set-beta-values headers betas*)))

(defn beta-present?
  [headers beta]
  (some #(= % beta)
        (split-beta-values (get headers "anthropic-beta"))))

(defn remove-prompt-caching-betas
  [headers prompt-caching-beta]
  (remove-beta-values headers #{prompt-caching-beta}))

(defn update-request-headers
  [request f]
  (update request :headers #(f (or % {}))))

(defn clear-beta-header
  [headers]
  (dissoc headers "anthropic-beta"))

(defn strip-cache-control-fields
  [x]
  (cond
    (map? x)
    (->> x
         (remove (fn [[k _]] (or (= k :cache_control)
                                 (= k "cache_control"))))
         (map (fn [[k v]] [k (strip-cache-control-fields v)]))
         (into (empty x)))

    (vector? x)
    (mapv strip-cache-control-fields x)

    (set? x)
    (set (map strip-cache-control-fields x))

    (sequential? x)
    (mapv strip-cache-control-fields x)

    :else
    x))

(defn request-body-map
  [request]
  (let [body* (parse-json-body-safe (:body request))]
    (when (map? body*)
      body*)))

(defn request-with-body-map
  [request body-map]
  (assoc request :body (json/generate-string body-map)))

(defn text-system-blocks?
  [blocks]
  (and (sequential? blocks)
       (every? (fn [block]
                 (and (map? block)
                      (string? (:text block))))
               blocks)))

(defn system-blocks->text
  [blocks]
  (apply str (map #(or (:text %) "") blocks)))

(defn collapse-system-blocks-if-plain-text
  [body]
  (if (text-system-blocks? (:system body))
    (assoc body :system (system-blocks->text (:system body)))
    body))

(defn update-request-body
  [request f]
  (if-let [body* (request-body-map request)]
    (request-with-body-map request (f body*))
    request))

(defn request-transform
  [step {:keys [prompt-caching-beta interleaved-thinking-beta]}]
  (case step
    :without-prompt-caching
    (fn [request]
      (-> request
          (update-request-headers #(remove-prompt-caching-betas % prompt-caching-beta))
          (update-request-body #(-> %
                                    strip-cache-control-fields
                                    collapse-system-blocks-if-plain-text))))

    :without-thinking
    (fn [request]
      (-> request
          (update-request-headers #(remove-beta-values %
                                                       #{interleaved-thinking-beta}))
          ;; Strip both extended-thinking and adaptive-thinking params
          (update-request-body #(dissoc % :thinking :output_config))))

    :without-all-betas
    #(update-request-headers % clear-beta-header)

    identity))

(defn apply-request-transforms
  [request steps beta-config]
  (reduce (fn [req step]
            ((request-transform step beta-config) req))
          request
          steps))

(defn prompt-caching-request?
  [request prompt-caching-beta]
  (beta-present? (:headers request) prompt-caching-beta))

(defn thinking-request?
  "True when the request has any form of thinking enabled:
   - extended thinking: interleaved-thinking beta header or :thinking body key
   - adaptive thinking: :thinking body key with type=adaptive (no beta header)"
  [request interleaved-thinking-beta]
  (or (beta-present? (:headers request) interleaved-thinking-beta)
      (contains? (or (request-body-map request) {}) :thinking)))

(defn has-any-beta-header?
  [request]
  (seq (split-beta-values (get-in request [:headers "anthropic-beta"]))))

(defn fallback-request-steps-for-400
  [request {:keys [prompt-caching-beta interleaved-thinking-beta oauth-auth-request?]}]
  (cond-> []
    (prompt-caching-request? request prompt-caching-beta)              (conj :without-prompt-caching)
    (thinking-request? request interleaved-thinking-beta)              (conj :without-thinking)
    (and (has-any-beta-header? request)
         (not (oauth-auth-request? request)))                          (conj :without-all-betas)))

(defn fallback-request-for-400
  [request beta-config]
  (let [steps   (fallback-request-steps-for-400 request beta-config)
        retried (apply-request-transforms request steps beta-config)]
    (when (not= request retried)
      {:request retried
       :steps steps})))
