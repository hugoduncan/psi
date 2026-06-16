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
   [extensions.dev-http.renderers :as renderers]
   [extensions.dev-http.util :as util :refer [kget]]))

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
  (mw/urlencoded-param (body-string (:body request)) "choice"))

(defn- render-form
  [route-id token content]
  (let [data    (:data content)
        prompt  (kget data :prompt "prompt")
        options (map normalize-option (or (kget data :options "options") []))
        action  (util/session-route-path route-id token)]
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

(defn- claim-answer!
  "Atomically mark `route-id` answered with `answer` if not already answered.
   Returns true iff this call won the claim. Single-developer/localhost — the
   accepted lightweight guard (design R6). Uses `swap-vals!` so the update fn is
   pure and the win is derived from the returned prior state."
  [registry-atom route-id answer]
  (let [[old _] (swap-vals! registry-atom
                            (fn [m]
                              (let [entry (get m route-id)]
                                (if (:answered? entry)
                                  m
                                  (assoc m route-id
                                         (assoc entry :answered? true :answer answer))))))]
    (not (:answered? (get old route-id)))))

(defn- release-claim!
  "Revert a won-but-uninjected claim on `route-id` so the choice can be retried.
   Called only when the synthetic-prompt injection failed, so the single-shot is
   not consumed by a submission that injected zero user messages (AC-6/AC-7)."
  [registry-atom route-id]
  (swap! registry-atom
         (fn [m]
           (if-let [entry (get m route-id)]
             (assoc m route-id (dissoc entry :answered? :answer))
             m))))

(defn- inject-choice!
  "Inject the won choice into the origin session via the
   `psi.extension/submit-synthetic-prompt` mutation. Returns true iff the
   mutation reports the prompt was submitted; a throw or falsey
   `:psi.extension/prompt-submitted?` ⇒ false so the caller can release the
   single-shot claim and surface a failure page instead of a false success."
  [api session-id answer]
  (try
    (boolean
     (:psi.extension/prompt-submitted?
      ((:mutate-session api) session-id
                             'psi.extension/submit-synthetic-prompt
                             {:user-msg answer})))
    (catch Throwable _ false)))

(defn- failed-page
  []
  (renderers/page "choices"
                  [:p "Could not record your choice — please try again."]))

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
          (util/text-response 400 "400 missing choice")

          (claim-answer! registry route-id answer)
          (if (inject-choice! api session-id answer)
            (util/html-response (thanks-page answer))
            (do
              ;; Injection failed → the route injected zero user messages, so
              ;; do not consume the single-shot: release the claim so the choice
              ;; can be retried, and report failure rather than a false success.
              (release-claim! registry route-id)
              (util/html-response (failed-page))))

          :else
          (util/html-response (answered-page answer))))
      (util/html-response (render-form route-id (mw/request-token request) content)))))
