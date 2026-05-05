(ns psi.agent-session.workflow-ir
  "Canonical normalized workflow IR schema and minimal semantic validation.

   This namespace defines the runtime-owned validation boundary for compiled
   workflow IR values. It owns:
   - Malli structural schemas for normalized workflow IR
   - minimal IR-intrinsic semantic validation only

   Semantic checks intentionally stop at invariants intrinsic to normalized IR:
   - step refs must target prior steps only
   - `:on` requires `:judge`
   - source refs to `:output` keys must reference declared step-local outputs
   - source refs to `:yield` fields must reference fields declared by the
     referenced step yield form

   Broader execution/compiler semantics remain out of scope for this slice."
  (:require
   [malli.core :as m]))

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
  [:and
   [:map
    [:from source-ref-schema]
    [:path {:optional true} path-schema]
    [:projection {:optional true} projection-schema]]
   [:fn {:error/message "source-spec cannot contain both :path and :projection"}
    (fn [{:keys [path projection]}]
      (not (and path projection)))]])

(def output-spec-schema
  [:map
   [:source :keyword]
   [:metadata {:optional true} [:maybe :map]]])

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

(def session-spec-schema
  [:map
   [:model {:optional true} [:maybe model-id-schema]]
   [:tools {:optional true} [:vector tool-id-schema]]
   [:skills {:optional true} [:vector skill-id-schema]]
   [:contributions [:vector contribution-schema]]])

(def delegate-prompt-string-schema
  [:or :string template-contribution-schema])

(def delegate-spec-schema
  [:map
   [:target workflow-name-schema]
   [:prompt-string delegate-prompt-string-schema]
   [:context {:optional true} [:vector source-contribution-schema]]])

(def llm-judge-schema
  [:map
   [:type [:= :llm]]
   [:session session-spec-schema]
   [:projection {:optional true} [:maybe projection-schema]]])

(def invoke-judge-schema
  [:map
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
  [:map
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
  [:map
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
  [:map
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

(defn- ref-errors [step-index current-step source-ref]
  (when (map? source-ref)
    (let [{target-step-name :step output-key :output yield-field :yield} source-ref
          current-index (get-in step-index [current-step :index])
          target (get step-index target-step-name)]
      (cond
        (nil? target)
        [{:type :missing-step-ref
          :step current-step
          :ref source-ref}]

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
                (when-let [prompt-string (get-in step [:delegate :prompt-string])]
                  (if (and (map? prompt-string)
                           (= :template (:type prompt-string)))
                    (source-refs-in-template-contribution prompt-string)
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
              ref-errors* (mapcat #(ref-errors step-idx step-name %)
                                  (step-source-refs step))]
          (concat on-without-judge
                  judge-without-routing
                  missing-yields
                  local-yield-errors
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
  [_step accepted-result output-key]
  (let [raw-outputs (:outputs accepted-result)]
    (case output-key
      :result accepted-result
      :final-llm-reply (or (get raw-outputs :final-llm-reply)
                           (get raw-outputs :text))
      (get raw-outputs output-key))))

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
