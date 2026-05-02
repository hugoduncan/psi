(ns psi.ai.proxy
  (:require [clojure.string :as str])
  (:import [java.net URI URLDecoder]))

(def ^:private logical-env-keys
  [[:http "HTTP_PROXY" "http_proxy"]
   [:https "HTTPS_PROXY" "https_proxy"]
   [:all "ALL_PROXY" "all_proxy"]])

(def ^:private supported-proxy-schemes
  #{"http" "https" "socks" "socks5"})

(defn- blank->nil
  [s]
  (when-not (str/blank? s)
    s))

(defn normalize-proxy-env
  [env]
  (reduce (fn [acc [logical uppercase lowercase]]
            (if-let [value (or (blank->nil (get env uppercase))
                               (blank->nil (get env lowercase)))]
              (assoc acc logical value)
              acc))
          {}
          logical-env-keys))

(defn- parse-uri
  [source-env raw-uri]
  (try
    (URI. raw-uri)
    (catch Exception e
      (throw (ex-info (str "Invalid proxy URI in " (name source-env) ": " raw-uri)
                      {:source-env source-env
                       :raw-uri raw-uri}
                      e)))))

(defn- decode-user-info
  [s]
  (when s
    (URLDecoder/decode s "UTF-8")))

(defn- parse-proxy-uri
  [{:keys [source-env raw-uri]}]
  (let [uri      (parse-uri source-env raw-uri)
        scheme   (some-> (.getScheme uri) str/lower-case)
        host     (.getHost uri)
        port     (.getPort uri)
        userinfo (.getUserInfo uri)
        [username password] (when userinfo
                              (let [[u p] (str/split userinfo #":" 2)]
                                [(decode-user-info u)
                                 (decode-user-info p)]))]
    (when-not (contains? supported-proxy-schemes scheme)
      (throw (ex-info (str "Unsupported proxy scheme in " (name source-env) ": " raw-uri
                           ". Supported schemes: http, https, socks, socks5")
                      {:source-env source-env
                       :raw-uri raw-uri
                       :scheme scheme})))
    (when-not (seq host)
      (throw (ex-info (str "Proxy URI in " (name source-env) " must include a host: " raw-uri)
                      {:source-env source-env
                       :raw-uri raw-uri
                       :scheme scheme})))
    (when-not (pos-int? port)
      (throw (ex-info (str "Proxy URI in " (name source-env) " must include a numeric port: " raw-uri)
                      {:source-env source-env
                       :raw-uri raw-uri
                       :scheme scheme
                       :host host
                       :port port})))
    (cond-> {:source-env source-env
             :raw-uri raw-uri
             :scheme scheme
             :host host
             :port port}
      username (assoc :username username)
      password (assoc :password password))))

(defn effective-proxy-for-url
  [normalized-env request-url]
  (let [scheme (some-> (parse-uri :request-url request-url) .getScheme str/lower-case)
        selected (case scheme
                   "https" (when-let [raw-uri (or (:https normalized-env)
                                                  (:all normalized-env))]
                             {:source-env (if (:https normalized-env) :https :all)
                              :raw-uri raw-uri})
                   "http" (when-let [raw-uri (or (:http normalized-env)
                                                 (:all normalized-env))]
                            {:source-env (if (:http normalized-env) :http :all)
                             :raw-uri raw-uri})
                   nil)]
    (when selected
      (parse-proxy-uri selected))))

(defn proxy-request-options
  [{:keys [scheme host port username password]}]
  (cond-> {:proxy-host host
           :proxy-port port
           :proxy-scheme (keyword scheme)}
    username (assoc :proxy-user username)
    password (assoc :proxy-pass password)))

(defn request-proxy-options
  ([request-url]
   (request-proxy-options (System/getenv) request-url))
  ([env request-url]
   (some-> env
           normalize-proxy-env
           (effective-proxy-for-url request-url)
           proxy-request-options)))
