(ns psi.agent-session.workflow-file-authoring-preload
  "Compile workflow-file `:session :preload` authoring into canonical
   step-local preload entries.

   Owns:
   - task 063 constrained preload source/projection compilation
   - explicit preload role-synthesis contract for synthetic value preload
     messages (`user` for workflow input/original, `assistant` for accepted
     prior-step result text)"
  (:require
   [psi.agent-session.workflow-file-authoring-errors :as authoring-errors]
   [psi.agent-session.workflow-file-authoring-session :as session]))

(def ^:private projection-entry-keys
  #{:from :projection})

(def ^:private transcript-projection-entry-keys
  #{:type :turns :tool-output})

(def ^:private value-preload-projections
  #{:text})

(def ^:private source-map-keys
  #{:step :kind})

(defn- compile-transcript-projection
  [projection]
  (cond
    (or (nil? projection)
        (= projection :full)
        (= projection :none))
    {:ok projection}

    (and (map? projection) (= :tail (:type projection)))
    (or (authoring-errors/unexpected-keys-error ":session preload projection"
                                                transcript-projection-entry-keys
                                                projection)
        (let [{:keys [turns tool-output]} projection]
          (cond
            (not (pos-int? turns))
            (authoring-errors/invalid-in ":session preload projection"
                                         "expected positive `:turns`")

            (and (contains? projection :tool-output)
                 (not (or (true? tool-output) (false? tool-output) (nil? tool-output))))
            (authoring-errors/invalid-in ":session preload projection"
                                         "expected boolean `:tool-output`")

            :else
            {:ok projection})))

    :else
    (authoring-errors/invalid-in ":session preload projection"
                                 (str "unsupported transcript/message projection "
                                      (pr-str projection)))))

(defn- preload-entry-error
  [message]
  (authoring-errors/invalid-in ":session preload" message))

(defn- compile-value-preload-binding
  [entry step-name->step-ref current-step-idx]
  (let [projection (get entry :projection :text)]
    (if (contains? value-preload-projections projection)
      (session/source+projection->binding :preload
                                          (:from entry)
                                          projection
                                          step-name->step-ref
                                          current-step-idx)
      (authoring-errors/invalid-in ":session preload"
                                   (str "value preload supports only `:projection :text`; got "
                                        (pr-str projection))))))

(defn- preload-source-entry
  [source step-name->step-ref current-step-idx]
  (cond
    (= source :workflow-input)
    {:ok {:kind :value
          :role "user"}}

    (= source :workflow-original)
    {:ok {:kind :value
          :role "user"}}

    (map? source)
    (let [{:keys [step kind] :as source-map} source]
      (or (authoring-errors/unexpected-keys-error ":session preload source"
                                                  source-map-keys
                                                  source-map)
          (cond
            (not (string? step))
            (authoring-errors/invalid-in ":session preload source"
                                         "expected `{:step \"...\" :kind ...}`")

            (not (contains? #{:accepted-result :session-transcript} kind))
            (authoring-errors/invalid-in ":session preload source"
                                         (str "unsupported step source kind `"
                                              kind "`"))

            :else
            (if-let [{:keys [step-id idx]} (get step-name->step-ref step)]
              (if (< idx current-step-idx)
                {:ok (if (= kind :session-transcript)
                       {:kind :session-transcript
                        :step-id step-id}
                       {:kind :value
                        :role "assistant"})}
                (authoring-errors/invalid (str "Forward step reference: `"
                                               step
                                               "` must refer to an earlier step")))
              (authoring-errors/invalid (str "Unknown step name: `" step "`"))))))

    :else
    (authoring-errors/invalid-in ":session preload source"
                                 (str "unsupported `:from` value "
                                      (pr-str source)))))

(defn- compile-preload-entry
  [entry step-name->step-ref current-step-idx]
  (cond
    (not (map? entry))
    (preload-entry-error "expected map entries")

    (empty? entry)
    (preload-entry-error "expected non-empty map with `:from`")

    (contains? entry :project)
    (preload-entry-error "use `:projection`, not `:project`")

    (not (contains? entry :from))
    (preload-entry-error "expected non-empty map with `:from`")

    :else
    (or (authoring-errors/unexpected-keys-error ":session preload"
                                                projection-entry-keys
                                                entry)
        (let [{source-entry :ok source-error :error}
              (preload-source-entry (:from entry) step-name->step-ref current-step-idx)]
          (if source-error
            (preload-entry-error source-error)
            (case (:kind source-entry)
              :session-transcript
              (let [{projection :ok projection-error :error}
                    (compile-transcript-projection (get entry :projection :full))]
                (if projection-error
                  (preload-entry-error projection-error)
                  {:ok {:kind :session-transcript
                        :step-id (:step-id source-entry)
                        :projection projection}}))

              :value
              (let [{binding :ok binding-error :error}
                    (compile-value-preload-binding entry step-name->step-ref current-step-idx)]
                (if binding-error
                  (preload-entry-error binding-error)
                  {:ok {:kind :value
                        :role (:role source-entry)
                        :binding binding}}))))))))

(defn compile-step-session-preload
  [step step-name->step-ref current-step-idx]
  (let [session (:session step)
        preload (:preload session ::missing)]
    (cond
      (nil? session)
      {:ok nil}

      (not (map? session))
      (authoring-errors/invalid-in ":session" "expected map")

      (= preload ::missing)
      {:ok nil}

      (nil? preload)
      {:ok nil}

      (not (vector? preload))
      (authoring-errors/invalid-in ":session preload" "expected vector")

      :else
      (loop [entries preload
             compiled []]
        (if (empty? entries)
          {:ok (not-empty compiled)}
          (let [{entry-ok :ok entry-error :error}
                (compile-preload-entry (first entries) step-name->step-ref current-step-idx)]
            (if entry-error
              {:error entry-error}
              (recur (rest entries) (conj compiled entry-ok)))))))))
