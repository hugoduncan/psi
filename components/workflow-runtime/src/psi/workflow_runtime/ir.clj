(ns psi.workflow-runtime.ir
  "Canonical normalized workflow IR schema, semantic validation, and error formatting.

   This namespace defines the runtime-owned validation boundary for compiled
   workflow IR values. It owns:
   - Malli structural schemas for normalized workflow IR
   - minimal IR-intrinsic semantic validation only
   - human-readable formatting of all compilation error types (compile errors,
     structural Malli errors, and semantic validation errors)

   Semantic checks intentionally stop at invariants intrinsic to normalized IR:
   - step refs must target prior steps only
   - `:on` requires `:judge`
   - source refs to `:output` keys must reference declared step-local outputs
   - source refs to `:yield` fields must reference fields declared by the
     referenced step yield form

   Broader execution/compiler semantics remain out of scope for this slice."
  (:require
   [clojure.string :as str]
   [malli.core :as m]
   [psi.workflow-runtime.structured-output :as structured-output]
   [psi.workflow-runtime.structured-output-schemas :as structured-output-schemas]))

;(def ids)
(def workflow-ir-version-schema [:= :workflow-ir/v1])
(def step-name-schema :string)
(def workflow-name-schema :string)
(def operation-id-schema :string)
(def model-id-schema [:or :string :map])
(def tool-id-schema :string)
(def skill-id-schema :string)
(def output-key-schema :keyword)
(def yield-field-schema :keyword)
(def outcome-schema [:or :string :keyword])
(def path-segment-schema [:or :keyword :string :int])
(def path-schema [:vector path-segment-schema])
(def literal-schema
  [:or :string :keyword number? :boolean nil? [:vector :any] [:map-of :any :any]])
(def compat-schema [:map-of :keyword :any])

(def projection-schema
  [:or
   [:enum :none :full]
   [:map
    [:type [:= :tail]]
    [:turns pos-int?]
    [:tool-output {:optional true} [:maybe :boolean]]]])

(def step-output-ref-schema
  [:map
   [:step step-name-schema]
   [:output output-key-schema]])

(def step-yield-ref-schema
  [:map
   [:step step-name-schema]
   [:yield yield-field-schema]])

(def source-ref-schema
  [:or
   [:= :workflow-input]
   [:= :workflow-original]
   step-output-ref-schema
   step-yield-ref-schema])

(def source-spec-schema
  [:or
   [:map
    [:value :any]]
   [:and
    [:map
     [:from source-ref-schema]
     [:path {:optional true} path-schema]
     [:projection {:optional true} projection-schema]]
    [:fn {:error/message "source-spec cannot contain both :path and :projection"}
     (fn [{:keys [path projection]}]
       (not (and path projection)))]]])

(def structured-output-source-schema
  [:enum :session/structured-output :judge/structured-output])

(def text-output-spec-schema
  [:map
   [:source :keyword]
   [:metadata {:optional true} [:maybe :map]]])

(def structured-output-strategy-schema
  [:enum :provider-native :prompted-json :repair-parse :unsupported])

(def structured-output-fallback-schema
  [:enum :prompted-json :none])

(def structured-output-spec-schema
  [:map
   [:source structured-output-source-schema]
   [:mode [:= :structured]]
   [:schema-id :keyword]
   [:schema-version pos-int?]
   [:schema :any]
   [:json-schema {:optional true} :map]
   [:strategy {:optional true} structured-output-strategy-schema]
   [:strategy-preference {:optional true} structured-output-strategy-schema]
   [:fallback {:optional true} structured-output-fallback-schema]
   [:require-provider-native? {:optional true} :boolean]
   [:metadata {:optional true} [:maybe :map]]])

(def output-spec-schema
  [:or structured-output-spec-schema text-output-spec-schema])

(def terminal-contract-schema
  [:map
   [:handoff {:optional true}
    [:map
     [:type [:= :markdown-handoff-data]]]]])

(def outputs-schema
  [:map-of output-key-schema output-spec-schema])

(def data-yield-schema
  [:map
   [:type [:= :data]]
   [:data output-key-schema]])

(def text-yield-schema
  [:map
   [:type [:= :text]]
   [:text output-key-schema]])

(def error-yield-schema
  [:map
   [:type [:= :error]]
   [:reason :keyword]
   [:message :string]
   [:details {:optional true} [:maybe :map]]])

(def delegated-yield-schema
  [:map
   [:type [:= :delegated]]])

(def yields-schema
  [:multi {:dispatch :type}
   [:data data-yield-schema]
   [:text text-yield-schema]
   [:error error-yield-schema]
   [:delegated delegated-yield-schema]])

(def source-contribution-schema
  [:map
   [:type [:= :source]]
   [:from source-ref-schema]
   [:path {:optional true} path-schema]
   [:projection {:optional true} projection-schema]])

(def template-contribution-schema
  [:map
   [:type [:= :template]]
   [:text :string]
   [:vars {:optional true} [:map-of :string source-spec-schema]]])

(def contribution-schema
  [:multi {:dispatch :type}
   [:source source-contribution-schema]
   [:template template-contribution-schema]])

(def invoke-spec-schema
  [:map
   [:operation operation-id-schema]
   [:args {:optional true} [:map-of :keyword [:or literal-schema source-spec-schema]]]])

(def response-mode-schema
  [:enum :streaming :non-streaming])

(def profile-name-schema :keyword)

(def session-spec-schema
  [:map {:closed true}
   [:model {:optional true} [:maybe model-id-schema]]
   [:session-profile {:optional true} profile-name-schema]
   [:thinking-level {:optional true} [:enum :off :minimal :low :medium :high :xhigh]]
   [:system-prompt {:optional true} :string]
   [:tools {:optional true} [:vector tool-id-schema]]
   [:skills {:optional true} [:vector skill-id-schema]]
   [:response-mode {:optional true} [:maybe response-mode-schema]]
   [:prompt-component-selection {:optional true} [:maybe :map]]
   [:temperature {:optional true} [:maybe [:double {:min 0.0 :max 2.0}]]]
   [:logprobs {:optional true} :boolean]
   [:top-logprobs {:optional true} [:int {:min 1 :max 20}]]
   [:contributions [:vector contribution-schema]]])

(def map-prompt-string-schema
  [:map
   [:type [:= :map]]
   [:fields [:map-of :keyword source-spec-schema]]])

(def delegate-prompt-string-schema
  [:or :string template-contribution-schema map-prompt-string-schema])

(def delegate-target-schema
  [:or workflow-name-schema source-spec-schema])

(def delegate-session-spec-schema
  [:map {:closed true}
   [:model {:optional true} [:maybe model-id-schema]]
   [:session-profile {:optional true} profile-name-schema]
   [:thinking-level {:optional true} [:enum :off :minimal :low :medium :high :xhigh]]])

(def delegate-spec-schema
  [:map
   [:target delegate-target-schema]
   [:prompt-string delegate-prompt-string-schema]
   [:session {:optional true} delegate-session-spec-schema]
   [:context {:optional true} [:vector source-contribution-schema]]])

(def llm-judge-session-spec-schema
  [:map {:closed true}
   [:model {:optional true} [:maybe model-id-schema]]
   [:thinking-level {:optional true} [:enum :off :minimal :low :medium :high :xhigh]]
   [:system-prompt {:optional true} :string]
   [:tools {:optional true} [:vector tool-id-schema]]
   [:skills {:optional true} [:vector skill-id-schema]]
   [:response-mode {:optional true} [:maybe response-mode-schema]]
   [:prompt-component-selection {:optional true} [:maybe :map]]
   [:temperature {:optional true} [:maybe [:double {:min 0.0 :max 2.0}]]]
   [:logprobs {:optional true} :boolean]
   [:top-logprobs {:optional true} [:int {:min 1 :max 20}]]
   [:contributions [:vector contribution-schema]]])

(def llm-judge-schema
  [:map {:closed true}
   [:type [:= :llm]]
   [:session llm-judge-session-spec-schema]
   [:outputs {:optional true} outputs-schema]
   [:projection {:optional true} [:maybe projection-schema]]])

(def invoke-judge-schema
  [:map {:closed true}
   [:type [:= :invoke]]
   [:invoke invoke-spec-schema]])

(def judge-schema
  [:multi {:dispatch :type}
   [:llm llm-judge-schema]
   [:invoke invoke-judge-schema]])

(def routing-directive-schema
  [:map
   [:goto [:or [:enum :next :previous :done] step-name-schema]]
   [:max-iterations {:optional true} [:maybe pos-int?]]])

(def routing-table-schema
  [:map-of outcome-schema routing-directive-schema])

(def invoke-step-schema
  [:map {:closed true}
   [:name step-name-schema]
   [:type [:= :invoke]]
   [:invoke invoke-spec-schema]
   [:outputs {:optional true} outputs-schema]
   [:yields {:optional true} yields-schema]
   [:judge {:optional true} [:maybe judge-schema]]
   [:on {:optional true} [:maybe routing-table-schema]]
   [:max-iterations {:optional true} [:maybe pos-int?]]
   [:compat {:optional true} [:maybe compat-schema]]])

(def session-step-schema
  [:map {:closed true}
   [:name step-name-schema]
   [:type [:= :session]]
   [:session session-spec-schema]
   [:outputs {:optional true} outputs-schema]
   [:yields {:optional true} yields-schema]
   [:judge {:optional true} [:maybe judge-schema]]
   [:on {:optional true} [:maybe routing-table-schema]]
   [:max-iterations {:optional true} [:maybe pos-int?]]
   [:compat {:optional true} [:maybe compat-schema]]])

(def delegate-step-schema
  [:map {:closed true}
   [:name step-name-schema]
   [:type [:= :delegate]]
   [:delegate delegate-spec-schema]
   [:outputs {:optional true} outputs-schema]
   [:yields {:optional true} yields-schema]
   [:judge {:optional true} [:maybe judge-schema]]
   [:on {:optional true} [:maybe routing-table-schema]]
   [:max-iterations {:optional true} [:maybe pos-int?]]
   [:compat {:optional true} [:maybe compat-schema]]])

(def ir-step-schema
  [:multi {:dispatch :type}
   [:invoke invoke-step-schema]
   [:session session-step-schema]
   [:delegate delegate-step-schema]])

(def workflow-ir-schema
  [:map
   [:version workflow-ir-version-schema]
   [:terminal-contract {:optional true} [:maybe terminal-contract-schema]]
   [:steps [:vector {:min 1} ir-step-schema]]])

(def valid-workflow-ir? (m/validator workflow-ir-schema))
(def explain-workflow-ir (m/explainer workflow-ir-schema))

(defn- step-index [steps]
  (into {}
        (map-indexed (fn [idx step]
                       [(:name step) {:index idx :step step}]))
        steps))

(defn- yield-fields [step]
  (let [yield-spec (:yields step)]
    (case (:type yield-spec)
      :data #{:data}
      :text #{:text}
      :error #{:reason :message :details}
      :delegated #{:text}
      #{})))

(defn- yield-output-key
  [step]
  (let [yield-spec (:yields step)]
    (case (:type yield-spec)
      :data (:data yield-spec)
      :text (:text yield-spec)
      nil)))

(defn- skills-without-read-errors [step]
  (when (= :session (:type step))
    (let [skills (get-in step [:session :skills] [])
          tools (get-in step [:session :tools] [])
          has-read? (some #(= "read" %) tools)]
      (when (and (seq skills) (not has-read?))
        [{:type :skills-without-read-tool
          :step (:name step)
          :skills skills}]))))

(defn- structured-output-cardinality-errors
  [step]
  (let [step-entries (structured-output/structured-output-entries (:outputs step))
        judge-entries (structured-output/structured-output-entries (get-in step [:judge :outputs]))]
    (concat
     (when (> (count step-entries) 1)
       [{:type :multiple-structured-outputs
         :step (:name step)
         :scope :step
         :output-keys (mapv first step-entries)}])
     (when (> (count judge-entries) 1)
       [{:type :multiple-structured-outputs
         :step (:name step)
         :scope :judge
         :output-keys (mapv first judge-entries)}]))))

(defn- reusable-schema-errors
  [step]
  (letfn [(schema-error [scope [output-key output-spec]]
            (when-let [known-schema (structured-output-schemas/schema-for (:schema-id output-spec)
                                                                          (:schema-version output-spec))]
              (when (not= known-schema (:schema output-spec))
                {:type :reusable-structured-output-schema-mismatch
                 :step (:name step)
                 :scope scope
                 :output-key output-key
                 :schema-id (:schema-id output-spec)
                 :schema-version (:schema-version output-spec)})))]
    (keep identity
          (concat
           (map #(schema-error :step %) (structured-output/structured-output-entries (:outputs step)))
           (map #(schema-error :judge %) (structured-output/structured-output-entries (get-in step [:judge :outputs])))))))

(defn- invoke-judge-same-step-output-ref?
  [step current-step source-ref]
  (and (= current-step (:step source-ref))
       (= :invoke (get-in step [:judge :type]))
       (contains? source-ref :output)
       (contains? (set (keys (:outputs step))) (:output source-ref))))

(defn- ref-errors [step-index step source-ref]
  (when (map? source-ref)
    (let [current-step (:name step)
          {target-step-name :step output-key :output yield-field :yield} source-ref
          current-index (get-in step-index [current-step :index])
          target (get step-index target-step-name)]
      (cond
        (nil? target)
        [{:type :missing-step-ref
          :step current-step
          :ref source-ref}]

        (invoke-judge-same-step-output-ref? step current-step source-ref)
        []

        (>= (:index target) current-index)
        [{:type :non-prior-step-ref
          :step current-step
          :ref source-ref}]

        output-key
        (if (contains? (set (keys (get-in target [:step :outputs] {}))) output-key)
          []
          [{:type :missing-output-key
            :step current-step
            :ref source-ref
            :available-outputs (vec (keys (get-in target [:step :outputs] {})))}])

        yield-field
        (if (contains? (yield-fields (:step target)) yield-field)
          []
          [{:type :missing-yield-field
            :step current-step
            :ref source-ref
            :available-yield-fields (vec (yield-fields (:step target)))}])

        :else
        []))))

(defn- source-refs-in-source-spec [source-spec]
  (when (and (map? source-spec)
             (contains? source-spec :from)
             (m/validate source-ref-schema (:from source-spec)))
    [(:from source-spec)]))

(defn- source-refs-in-template-contribution [contribution]
  (mapcat source-refs-in-source-spec
          (vals (:vars contribution))))

(defn- source-refs-in-contribution [contribution]
  (case (:type contribution)
    :source (source-refs-in-source-spec contribution)
    :template (source-refs-in-template-contribution contribution)
    []))

(defn- source-refs-in-arg-value [arg-value]
  (if (and (map? arg-value) (contains? arg-value :from))
    (source-refs-in-source-spec arg-value)
    []))

(defn- source-refs-in-invoke-spec [invoke-spec]
  (mapcat source-refs-in-arg-value
          (vals (:args invoke-spec))))

(defn- source-refs-in-judge [judge]
  (case (:type judge)
    :llm (mapcat source-refs-in-contribution
                 (get-in judge [:session :contributions]))
    :invoke (source-refs-in-invoke-spec (:invoke judge))
    []))

(defn- step-source-refs [step]
  (concat
   (case (:type step)
     :invoke (source-refs-in-invoke-spec (:invoke step))
     :session (mapcat source-refs-in-contribution
                      (get-in step [:session :contributions]))
     :delegate (concat
                (source-refs-in-source-spec (get-in step [:delegate :target]))
                (when-let [prompt-string (get-in step [:delegate :prompt-string])]
                  (case (when (map? prompt-string) (:type prompt-string))
                    :template (source-refs-in-template-contribution prompt-string)
                    :map      (mapcat source-refs-in-source-spec
                                      (vals (:fields prompt-string)))
                    []))
                (mapcat source-refs-in-contribution
                        (get-in step [:delegate :context])))
     [])
   (when-let [judge (:judge step)]
     (source-refs-in-judge judge))))

(defn semantic-errors
  "Return semantic validation errors for a structurally valid workflow IR.

   This owns only IR-intrinsic invariants for this slice:
   - compilers must materialize default `:yields` before this boundary
   - local `:yields` output-key refs must correspond to declared step-local outputs
   - step refs must target prior steps only
   - `:on` requires `:judge`
   - present `:judge` also requires a non-empty `:on` routing table
   - source refs must target declared output keys / yield fields"
  [workflow-ir]
  (let [steps (:steps workflow-ir)
        step-idx (step-index steps)]
    (vec
     (mapcat
      (fn [step]
        (let [step-name (:name step)
              on-without-judge (when (and (:on step) (not (:judge step)))
                                 [{:type :routing-without-judge
                                   :step step-name}])
              judge-without-routing (when (and (:judge step)
                                               (or (nil? (:on step))
                                                   (empty? (:on step))))
                                      [{:type :judge-without-routing
                                        :step step-name}])
              missing-yields (when-not (:yields step)
                               [{:type :missing-yields
                                 :step step-name}])
              local-yield-errors (when-let [output-key (yield-output-key step)]
                                   (when-not (contains? (set (keys (:outputs step))) output-key)
                                     [{:type :missing-local-yield-output-key
                                       :step step-name
                                       :output-key output-key
                                       :available-outputs (vec (keys (:outputs step)))}]))
              skills-read-errors (skills-without-read-errors step)
              structured-cardinality-errors (structured-output-cardinality-errors step)
              reusable-schema-errors* (reusable-schema-errors step)
              ref-errors* (mapcat #(ref-errors step-idx step %)
                                  (step-source-refs step))]
          (concat on-without-judge
                  judge-without-routing
                  missing-yields
                  local-yield-errors
                  skills-read-errors
                  structured-cardinality-errors
                  reusable-schema-errors*
                  ref-errors*)))
      steps))))

(defn validate-workflow-ir
  "Validate workflow IR at the owned boundary for this slice.

   Returns:
   {:valid? boolean
    :structural-errors explain-data?
    :semantic-errors [error*]}

   Structural validation runs first. Semantic validation runs only when the
   IR passes the Malli shape schema."
  [workflow-ir]
  (if-not (valid-workflow-ir? workflow-ir)
    {:valid? false
     :structural-errors (explain-workflow-ir workflow-ir)
     :semantic-errors []}
    (let [errors (semantic-errors workflow-ir)]
      {:valid? (empty? errors)
       :structural-errors nil
       :semantic-errors errors})))

(defn valid-workflow-ir-value?
  [workflow-ir]
  (:valid? (validate-workflow-ir workflow-ir)))

(defn step-output-value
  "Resolve the normalized logical output-surface value for `output-key` from an
   accepted result envelope for the given canonical IR `step`.

   Notes:
   - canonical refs address declared logical output keys, not storage details
   - session steps still tolerate legacy stored `:text` output as a fallback for
     canonical `:final-llm-reply` during compatibility migration
   - `:result` denotes the whole accepted-result envelope when declared locally"
  [step accepted-result output-key]
  (let [raw-outputs (:outputs accepted-result)
        value (case output-key
                :result accepted-result
                :final-llm-reply (or (get raw-outputs :final-llm-reply)
                                     (get raw-outputs :text))
                :handoff (get raw-outputs :handoff)
                (get raw-outputs output-key))
        output-spec (get-in step [:outputs output-key])]
    (if (structured-output/structured-output-spec? output-spec)
      (if (structured-output/valid-output-result? value)
        (get-in value [:structured-output :value])
        (throw (ex-info "Workflow structured output is not valid"
                        {:type :invalid-structured-output
                         :step (:name step)
                         :output output-key
                         :structured-output (:structured-output value)})))
      value)))

(defn step-output-surfaces
  "Return the normalized logical output-surface map for a canonical IR `step`
   and accepted result envelope.

   The returned map is keyed by declared output keys only, with compatibility
   fallback applied at value resolution time. Undeclared storage keys are not
   surfaced here."
  [step accepted-result]
  (into {}
        (map (fn [output-key]
               [output-key (step-output-value step accepted-result output-key)]))
        (keys (:outputs step))))

(defn step-yield-field-value
  "Resolve a yielded-value field from a canonical IR `step` and accepted result
   envelope.

   Yielded-value resolution is distinct from step-local output-surface resolution:
   `:yield` addresses fields of the step's yielded tagged union, not arbitrary
   logical outputs.

   Delegate steps intentionally expose the delegated callee terminal yielded text
   as the minimal canonical downstream-consumable surface for this slice. They do
   not expose arbitrary delegated step-local outputs through `:yield`."
  [step accepted-result yield-field]
  (let [yield-spec (:yields step)]
    (case (:type yield-spec)
      :data (when (= :data yield-field)
              (step-output-value step accepted-result (:data yield-spec)))
      :text (when (= :text yield-field)
              (step-output-value step accepted-result (:text yield-spec)))
      :error (get-in accepted-result [:blocked yield-field])
      :delegated (when (= :text yield-field)
                   (step-output-value step accepted-result :final-llm-reply))
      nil)))

;;;; Compilation error formatting

(defn- format-compile-error
  "Format a compile-error map `{:message string :data map}` into a human-readable line.

   When both `:step-name` and `:step-index` are present in `:data`, prefixes the
   message with step context. Both keys are always co-present when set by
   `compile-step-with-context`; the guard enforces this invariant defensively so
   a partial map cannot produce \"(index nil)\" output."
  [{:keys [message data]}]
  (let [step-name  (:step-name data)
        step-index (:step-index data)]
    (if (and step-name (some? step-index))
      (str "Step '" step-name "' (index " step-index "): " message)
      message)))

(defn- format-structural-error
  "Format a single Malli explain-data error entry into a human-readable line.

   Real Malli explain-data error entries carry :path, :in, :schema, :value and
   sometimes :type (e.g. :malli.core/missing-key) but do NOT carry :message.
   Falls back to (name :type) when present, then to \"invalid value\" so the
   description is never blank."
  [{:keys [path message type]}]
  (let [msg (or message (some-> type name) "invalid value")]
    (if (seq path)
      (str "Structural error at " (pr-str path) ": " msg)
      (str "Structural error: " msg))))

(defn- format-semantic-error
  "Format a single semantic error map into a human-readable line."
  [{:keys [type step ref output-key available-outputs available-yield-fields scope output-keys schema-id schema-version] :as err}]
  (case type
    :routing-without-judge
    (str "Step '" step "': routing table (:on) requires a judge")

    :judge-without-routing
    (str "Step '" step "': judge requires a non-empty routing table (:on)")

    :missing-yields
    (str "Step '" step "': missing :yields")

    :missing-local-yield-output-key
    (str "Step '" step "': yield references output key " output-key
         " which is not declared in :outputs (available: " (pr-str available-outputs) ")")

    :missing-step-ref
    (str "Step '" step "': references unknown step '" (:step ref) "'")

    :non-prior-step-ref
    (str "Step '" step "': references step '" (:step ref)
         "' which is not prior (forward/self references are not allowed)")

    :missing-output-key
    (str "Step '" step "': references output key " (:output ref)
         " of step '" (:step ref) "' but that key is not declared"
         " (available: " (pr-str available-outputs) ")")

    :missing-yield-field
    (str "Step '" step "': references yield field " (:yield ref)
         " of step '" (:step ref) "' but that field is not available"
         " (available: " (pr-str available-yield-fields) ")")

    :skills-without-read-tool
    (str "Step '" step "': skills require the 'read' tool to be present in :tools")

    :multiple-structured-outputs
    (str "Step '" step "': " (name scope) " declares multiple structured outputs "
         (pr-str output-keys) "; declare one structured output and group fields in its schema")

    :reusable-structured-output-schema-mismatch
    (str "Step '" step "': " (name scope) " output " output-key
         " declares schema-id/version " schema-id " v" schema-version
         " but its inline schema does not match the reusable workflow schema")

    ;; fallback for unknown types
    (str "Step '" step "': " type " (raw: " (pr-str err) ")")))

(defn format-compilation-errors
  "Format workflow IR compilation errors into a single actionable human-readable string.

   Accepts:
   - `compile-error`     — {:message string :data map} or nil
   - `structural-errors` — Malli explain-data or nil
   - `semantic-errors`   — seq of semantic error maps (may be empty)

   Returns a multi-line string prefixed with 'Workflow IR compilation failed:'."
  [compile-error structural-errors semantic-errors]
  (let [lines (cond-> []
                (some? compile-error)
                (conj (format-compile-error compile-error))

                (some? structural-errors)
                (into (map format-structural-error (:errors structural-errors)))

                (seq semantic-errors)
                (into (map format-semantic-error semantic-errors)))]
    (str "Workflow IR compilation failed:\n"
         (str/join "\n" lines))))
