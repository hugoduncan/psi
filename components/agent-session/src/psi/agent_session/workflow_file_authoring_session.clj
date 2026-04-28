(ns psi.agent-session.workflow-file-authoring-session
  "Compile workflow-file `:session` authoring into canonical workflow binding and
   step-local override data.

   Owns:
   - task 060/061 source+projection compilation for `:session :input` and
     `:session :reference`
   - task 062 per-step session-shaping override compilation"
  (:require
   [malli.core :as m]
   [psi.agent-session.session :as session]))

(def ^:private binding-session-keys
  #{:input :reference})

(def ^:private override-session-keys
  #{:system-prompt :tools :skills :model :thinking-level})

(def ^:private supported-session-keys
  (into #{} (concat binding-session-keys override-session-keys)))

(def ^:private canonical-thinking-levels
  #{:off :minimal :low :medium :high :xhigh})

(def ^:private projection-entry-keys
  #{:from :projection})

(def ^:private source-map-keys
  #{:step :kind})

(defn- invalid
  [message]
  {:error message})

(defn- invalid-in
  [scope message]
  (invalid (str message " in `" scope "`")))

(defn- unexpected-keys-error
  [scope allowed-keys actual-map]
  (when-let [unknown-keys (seq (remove allowed-keys (keys actual-map)))]
    (invalid-in scope
                (str "unexpected keys "
                     (pr-str (vec unknown-keys))))))

(defn- default-binding
  [binding-key previous-step-id]
  (case binding-key
    :input
    (if previous-step-id
      {:source :step-output
       :path [previous-step-id :outputs :text]}
      {:source :workflow-input
       :path [:input]})

    :reference
    {:source :workflow-input
     :path [:original]}))

(defn- source-root
  [source step-name->step-ref current-step-idx]
  (cond
    (= source :workflow-input)
    {:ok {:source :workflow-input
          :path []}}

    (= source :workflow-original)
    {:ok {:source :workflow-input
          :path [:original]}}

    (map? source)
    (let [{:keys [step kind] :as source-map} source]
      (or (unexpected-keys-error ":session source"
                                 source-map-keys
                                 source-map)
          (cond
            (not (string? step))
            (invalid "Malformed `:session source` form: expected `{:step \"...\" :kind :accepted-result}`")

            (not= kind :accepted-result)
            (invalid (str "Malformed `:session source` form: unsupported step source kind `"
                          kind "`"))

            :else
            (if-let [{:keys [step-id idx]} (get step-name->step-ref step)]
              (if (< idx current-step-idx)
                {:ok {:source :step-output
                      :path [step-id]}}
                (invalid (str "Forward step reference: `" step "` must refer to an earlier step")))
              (invalid (str "Unknown step name: `" step "`"))))))

    :else
    (invalid (str "Malformed `:session source` form: unsupported `:from` value "
                  (pr-str source)))))

(defn- projection-relative-path
  [binding-key source projection]
  (cond
    (= projection :text)
    (case source
      :workflow-input [:input]
      :workflow-original []
      [:outputs :text])

    (= projection :full)
    []

    (map? projection)
    (let [path (:path projection)]
      (or (unexpected-keys-error ":projection"
                                 #{:path}
                                 projection)
          (cond
            (not (contains? projection :path))
            (invalid "Malformed `:projection`: expected `:text`, `:full`, or `{:path [...]}`")

            (not (vector? path))
            (invalid "Malformed `:projection {:path ...}`: expected vector path")

            (not-every? #(or (keyword? %) (string? %) (int? %)) path)
            (invalid "Malformed `:projection {:path ...}`: path entries must be keyword, string, or int")

            :else
            path)))

    :else
    (invalid (str "Unsupported `:projection` for `:session "
                  (name binding-key)
                  "`: "
                  (pr-str projection)))))

(defn- combine-paths
  [root-path relative-path]
  (into (vec root-path) relative-path))

(defn- binding-error
  [binding-key message]
  (invalid-in (str ":session " (name binding-key)) message))

(defn- source+projection->binding
  [binding-key source projection step-name->step-ref current-step-idx]
  (let [{root :ok source-error :error}
        (source-root source step-name->step-ref current-step-idx)]
    (if source-error
      (binding-error binding-key source-error)
      (let [relative-path (projection-relative-path binding-key source projection)]
        (if (map? relative-path)
          (binding-error binding-key (:error relative-path))
          {:ok {:source (:source root)
                :path (combine-paths (:path root) relative-path)}})))))

(defn- compile-session-binding
  [binding-key previous-step-id session step-name->step-ref current-step-idx]
  (let [entry (get session binding-key ::missing)
        scope (str ":session " (name binding-key))]
    (cond
      (= entry ::missing)
      {:ok (default-binding binding-key previous-step-id)}

      (not (map? entry))
      (invalid-in scope "expected map")

      (empty? entry)
      (invalid-in scope "expected non-empty map with `:from`")

      (contains? entry :project)
      (invalid (str "Unsupported `" scope " :project`; use `:projection`"))

      (not (contains? entry :from))
      (invalid-in scope "expected non-empty map with `:from`")

      :else
      (or (unexpected-keys-error scope projection-entry-keys entry)
          (source+projection->binding binding-key
                                      (:from entry)
                                      (get entry :projection :text)
                                      step-name->step-ref
                                      current-step-idx)))))

(defn- compile-session-bindings
  [step previous-step-id step-name->step-ref current-step-idx]
  (let [session (:session step)
        defaults {:input (default-binding :input previous-step-id)
                  :original (default-binding :reference previous-step-id)}]
    (cond
      (nil? session)
      {:ok defaults}

      (not (map? session))
      (invalid "Malformed `:session`: expected map")

      (empty? session)
      {:ok defaults}

      :else
      (let [{input-binding :ok input-error :error}
            (compile-session-binding :input previous-step-id session step-name->step-ref current-step-idx)
            {reference-binding :ok reference-error :error}
            (compile-session-binding :reference previous-step-id session step-name->step-ref current-step-idx)]
        (cond
          input-error {:error input-error}
          reference-error {:error reference-error}
          :else {:ok {:input input-binding
                      :original reference-binding}})))))

(defn compile-step-input-bindings
  [step previous-step-id step-name->step-ref current-step-idx]
  (let [session (:session step)]
    (cond
      (nil? session)
      (compile-session-bindings step previous-step-id step-name->step-ref current-step-idx)

      (not (map? session))
      (invalid "Malformed `:session`: expected map")

      (empty? session)
      (compile-session-bindings step previous-step-id step-name->step-ref current-step-idx)

      :else
      (if-let [unsupported-keys (seq (remove supported-session-keys (keys session)))]
        (invalid (str "Unsupported `:session` keys for tasks 060-062: "
                      (pr-str (vec unsupported-keys))))
        (compile-session-bindings step previous-step-id step-name->step-ref current-step-idx)))))

(defn- vector-of-strings?
  [xs]
  (and (vector? xs)
       (every? string? xs)))

(defn- compile-tools-override
  [session]
  (if (contains? session :tools)
    (let [tools (:tools session)]
      (if (vector-of-strings? tools)
        {:ok tools}
        (invalid "Malformed `:session tools`: expected vector of strings")))
    {:ok ::absent}))

(defn- compile-skills-override
  [session]
  (if (contains? session :skills)
    (let [skills (:skills session)]
      (if (vector-of-strings? skills)
        {:ok skills}
        (invalid "Malformed `:session skills`: expected vector of strings")))
    {:ok ::absent}))

(defn- compile-system-prompt-override
  [session]
  (if (contains? session :system-prompt)
    (let [system-prompt (:system-prompt session)]
      (if (string? system-prompt)
        {:ok system-prompt}
        (invalid "Malformed `:session system-prompt`: expected string")))
    {:ok ::absent}))

(defn- compile-model-override
  [session]
  (if (contains? session :model)
    (let [model (:model session)]
      (if (or (string? model)
              (m/validate session/model-schema model))
        {:ok model}
        (invalid "Malformed `:session model`: expected model string or canonical model map")))
    {:ok ::absent}))

(defn- compile-thinking-level-override
  [session]
  (if (contains? session :thinking-level)
    (let [level (:thinking-level session)]
      (if (contains? canonical-thinking-levels level)
        {:ok level}
        (invalid "Malformed `:session thinking-level`: expected one of :off, :minimal, :low, :medium, :high, :xhigh")))
    {:ok ::absent}))

(defn compile-step-session-overrides
  [step]
  (let [session (:session step)]
    (cond
      (nil? session)
      {:ok nil}

      (not (map? session))
      (invalid "Malformed `:session`: expected map")

      (empty? session)
      {:ok nil}

      :else
      (if-let [unsupported-keys (seq (remove supported-session-keys (keys session)))]
        (invalid (str "Unsupported `:session` keys for tasks 060-062: "
                      (pr-str (vec unsupported-keys))))
        (let [{system-prompt :ok system-prompt-error :error} (compile-system-prompt-override session)
              {tools :ok tools-error :error} (compile-tools-override session)
              {skills :ok skills-error :error} (compile-skills-override session)
              {model :ok model-error :error} (compile-model-override session)
              {thinking-level :ok thinking-level-error :error} (compile-thinking-level-override session)]
          (cond
            system-prompt-error {:error system-prompt-error}
            tools-error {:error tools-error}
            skills-error {:error skills-error}
            model-error {:error model-error}
            thinking-level-error {:error thinking-level-error}
            :else {:ok (cond-> {}
                         (not= system-prompt ::absent) (assoc :system-prompt system-prompt)
                         (not= tools ::absent) (assoc :tools tools)
                         (not= skills ::absent) (assoc :skills skills)
                         (not= model ::absent) (assoc :model model)
                         (not= thinking-level ::absent) (assoc :thinking-level thinking-level))}))))))

(defn step-source-reference-map
  "Source-selection references are intentionally stricter than routing refs:
   `:session` step sources resolve only explicit author-facing step `:name`
   values. Legacy compatibility fallback to unambiguous delegated `:workflow`
   names is preserved only for `:goto` routing."
  [steps step-order]
  (into {}
        (keep-indexed (fn [idx step]
                        (when-let [step-name (:name step)]
                          [step-name
                           {:step-id (nth step-order idx)
                            :idx idx}])))
        steps))
