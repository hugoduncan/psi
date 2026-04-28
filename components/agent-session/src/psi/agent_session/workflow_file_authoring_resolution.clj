(ns psi.agent-session.workflow-file-authoring-resolution
  "Helpers for resolving workflow-file authoring surfaces into canonical
   compiler/runtime data shapes.

   Task 060 introduced explicit source-selection for `:session :input` /
   `:reference` plus named `:goto` routing. Task 061 layers a constrained
   projection vocabulary onto the same source entries:
   - `:projection :text`
   - `:projection :full`
   - `:projection {:path [...]}`
   - routing target resolution compatibility for named `:goto` targets
   - compile-time validation around those surfaces")

(def ^:private supported-session-keys
  #{:input :reference})

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
  [_binding-key source step-name->step-ref current-step-idx]
  (cond
    (= source :workflow-input)
    {:ok {:source :workflow-input
          :path []}}

    (= source :workflow-original)
    {:ok {:source :workflow-input
          :path [:original]}}

    (map? source)
    (let [{:keys [step kind] :as source-map} source
          unknown-keys (seq (remove #{:step :kind} (keys source-map)))]
      (cond
        unknown-keys
        {:error (str "Malformed `:session source` form: unexpected keys "
                     (pr-str (vec unknown-keys)))}

        (not (string? step))
        {:error "Malformed `:session source` form: expected `{:step \"...\" :kind :accepted-result}`"}

        (not= kind :accepted-result)
        {:error (str "Malformed `:session source` form: unsupported step source kind `"
                     kind "`")}

        :else
        (if-let [{:keys [step-id idx]} (get step-name->step-ref step)]
          (if (< idx current-step-idx)
            {:ok {:source :step-output
                  :path [step-id]}}
            {:error (str "Forward step reference: `" step "` must refer to an earlier step")})
          {:error (str "Unknown step name: `" step "`")})))

    :else
    {:error (str "Malformed `:session source` form: unsupported `:from` value "
                 (pr-str source))}))

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
    (let [unknown-keys (seq (remove #{:path} (keys projection)))
          path (:path projection)]
      (cond
        unknown-keys
        {:error (str "Malformed `:projection`: unexpected keys "
                     (pr-str (vec unknown-keys)))}

        (not (contains? projection :path))
        {:error "Malformed `:projection`: expected `:text`, `:full`, or `{:path [...]}`"}

        (not (vector? path))
        {:error "Malformed `:projection {:path ...}`: expected vector path"}

        (not-every? #(or (keyword? %) (string? %) (int? %)) path)
        {:error "Malformed `:projection {:path ...}`: path entries must be keyword, string, or int"}

        :else
        path))

    :else
    {:error (str "Unsupported `:projection` for `:session "
                 (name binding-key)
                 "`: "
                 (pr-str projection))}))

(defn- combine-paths
  [root-path relative-path]
  (into (vec root-path) relative-path))

(defn- source+projection->binding
  [binding-key source projection step-name->step-ref current-step-idx]
  (let [{root :ok source-error :error}
        (source-root binding-key source step-name->step-ref current-step-idx)]
    (if source-error
      {:error (str source-error " in `:session " (name binding-key) "`")}
      (let [relative-path (projection-relative-path binding-key source projection)]
        (if (map? relative-path)
          relative-path
          {:ok {:source (:source root)
                :path (combine-paths (:path root) relative-path)}})))))

(defn- compile-session-binding
  [binding-key previous-step-id session step-name->step-ref current-step-idx]
  (let [entry (get session binding-key ::missing)]
    (cond
      (= entry ::missing)
      {:ok (default-binding binding-key previous-step-id)}

      (not (map? entry))
      {:error (str "Malformed `:session " (name binding-key) "`: expected map")}

      (empty? entry)
      {:error (str "Malformed `:session " (name binding-key)
                   "`: expected non-empty map with `:from`")}

      (contains? entry :project)
      {:error (str "Unsupported `:session " (name binding-key)
                   " :project`; use `:projection`")}

      (not (contains? entry :from))
      {:error (str "Malformed `:session " (name binding-key)
                   "`: expected non-empty map with `:from`")}

      :else
      (let [unknown-keys (seq (remove #{:from :projection} (keys entry)))]
        (if unknown-keys
          {:error (str "Malformed `:session " (name binding-key)
                       "`: unexpected keys "
                       (pr-str (vec unknown-keys)))}
          (source+projection->binding binding-key
                                      (:from entry)
                                      (get entry :projection :text)
                                      step-name->step-ref
                                      current-step-idx))))))

(defn compile-step-input-bindings
  [step previous-step-id step-name->step-ref current-step-idx]
  (let [session (:session step)
        defaults {:input (default-binding :input previous-step-id)
                  :original (default-binding :reference previous-step-id)}]
    (cond
      (nil? session)
      {:ok defaults}

      (not (map? session))
      {:error "Malformed `:session`: expected map"}

      (empty? session)
      {:ok defaults}

      :else
      (let [unsupported-keys (seq (remove supported-session-keys (keys session)))]
        (if unsupported-keys
          {:error (str "Unsupported `:session` keys for task 061: "
                       (pr-str (vec unsupported-keys)))}
          (let [{input-binding :ok input-error :error}
                (compile-session-binding :input previous-step-id session step-name->step-ref current-step-idx)
                {reference-binding :ok reference-error :error}
                (compile-session-binding :reference previous-step-id session step-name->step-ref current-step-idx)]
            (cond
              input-error
              {:error input-error}

              reference-error
              {:error reference-error}

              :else
              {:ok {:input input-binding
                    :original reference-binding}})))))))

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

(defn routing-target->step-id-map
  "Build a map for goto resolution. Explicit `:name` values are authoritative.
   For backward compatibility, unique delegated workflow names are also accepted
   when unambiguous."
  [steps step-order]
  (let [workflow-name-freqs (frequencies (keep :workflow steps))]
    (into {}
          (keep-indexed
           (fn [idx step]
             (cond
               (:name step)
               [(:name step) (nth step-order idx)]

               (and (:workflow step)
                    (= 1 (get workflow-name-freqs (:workflow step))))
               [(:workflow step) (nth step-order idx)]

               :else
               nil))
           steps))))

(defn resolve-routing-table
  "Resolve :goto step names in a routing table to compiled step-ids.
   Keywords (:next, :previous, :done) pass through without resolution."
  [on-table target->step-id]
  (when on-table
    (into {}
          (map (fn [[signal directive]]
                 [signal
                  (if (and (string? (:goto directive))
                           (contains? target->step-id (:goto directive)))
                    (assoc directive :goto (get target->step-id (:goto directive)))
                    directive)]))
          on-table)))
