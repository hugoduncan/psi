(ns extensions.dev-http.choices
  "The `:choices` interaction primitive. A choices route renders a token-gated
   form; submitting it injects the selection as a mid-conversation **user**
   message into the invoking session (via the `psi.extension/submit-synthetic-prompt`
   mutation) and is **single-shot** — the answered flag lives in the
   session-route registry entry, so a second submission is rejected and at most
   one user message is injected per prompt.

   Presentation (the form) is out-of-band; only the submission's mutation enters
   the event log."
  (:require
   [clojure.string :as str]
   [extensions.dev-http.middleware :as mw]
   [extensions.dev-http.renderers :as renderers]))

(defn- kget
  [m & ks]
  (some (fn [k] (when (contains? m k) (get m k))) ks))

(defn- normalize-option
  "Normalize an option to `{:label … :value …}`. A bare scalar becomes both."
  [o]
  (if (map? o)
    (let [value (kget o :value "value" :label "label")
          label (kget o :label "label" :value "value")]
      {:label (str label) :value (str value)})
    {:label (str o) :value (str o)}))

(defn- body-string
  "Read a ring request body to a string. Accepts an InputStream (http-kit) or a
   raw string."
  [body]
  (cond
    (nil? body)        ""
    (string? body)     body
    :else              (slurp body)))

(defn- form-choice
  "Parse the submitted `choice` value from a urlencoded POST body."
  [request]
  (->> (body-string (:body request))
       (re-seq #"([^&=]+)=([^&]*)")
       (some (fn [[_ k v]]
               (when (= k "choice")
                 (java.net.URLDecoder/decode (str v) "UTF-8"))))))

(defn- render-form
  [route-id token content]
  (let [data    (:data content)
        prompt  (kget data :prompt "prompt")
        options (map normalize-option (or (kget data :options "options") []))
        action  (str "/s/" route-id "?token=" token)]
    (renderers/page "choices"
                    [:form {:method "post" :action action}
                     (when prompt [:p prompt])
                     (for [{:keys [label value]} options]
                       [:button {:type "submit" :name "choice" :value value} label])])))

(defn- answered-page
  [answer]
  (renderers/page "choices" [:p (str "Already answered: " answer)]))

(defn- thanks-page
  [answer]
  (renderers/page "choices" [:p (str "Recorded your choice: " answer)]))

(defn- text-response
  [body]
  {:status  200
   :headers {"content-type" "text/html; charset=utf-8"}
   :body    body})

(defn- claim-answer!
  "Atomically mark `route-id` answered with `answer` if not already answered.
   Returns true iff this call won the claim. Single-developer/localhost — the
   accepted lightweight guard (design R6)."
  [registry-atom route-id answer]
  (let [won (atom false)]
    (swap! registry-atom
           (fn [m]
             (let [entry (get m route-id)]
               (if (:answered? entry)
                 (do (reset! won false) m)
                 (do (reset! won true)
                     (assoc m route-id (assoc entry :answered? true :answer answer)))))))
    @won))

(defn make-handler
  "Build the ring handler for a `:choices` route. Closes over the `registry`
   atom, `route-id`, origin `session-id`, the api map (`:mutate-session`), and
   the `content` map (`:data` holds `{:prompt :options}`)."
  [{:keys [registry route-id session-id api content]}]
  (fn [request]
    (if (= :post (:request-method request))
      (let [answer (form-choice request)]
        (cond
          (str/blank? answer)
          {:status 400 :headers {"content-type" "text/plain; charset=utf-8"}
           :body "400 missing choice"}

          (claim-answer! registry route-id answer)
          (do
            ((:mutate-session api) session-id
                                   'psi.extension/submit-synthetic-prompt
                                   {:user-msg answer})
            (text-response (thanks-page answer)))

          :else
          (text-response (answered-page answer))))
      (text-response (render-form route-id (mw/request-token request) content)))))
