(ns psi.workflow-runtime.ir-error-formatting
  "Human-readable formatting of workflow IR compilation errors.

   Renders the three compilation error sources into a single actionable string:
   - compile errors      — {:message string :data map}
   - structural errors   — Malli explain-data
   - semantic errors     — seq of semantic validation error maps

   Extracted from `psi.workflow-runtime.ir` to keep that namespace focused on
   schema and semantic validation; formatting is pure presentation."
  (:require
   [clojure.string :as str]))

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

    :prompt-ref-non-session-step
    (str "Step '" step "': :prompt ref targets step '" (:step ref)
         "' which is not a :session step")

    :prompt-ref-single-prompt-step
    (str "Step '" step "': :prompt ref targets single-prompt step '" (:step ref)
         "' which has no named prompt groups")

    :prompt-ref-unknown-group
    (str "Step '" step "': :prompt ref names group " (pr-str (:prompt ref))
         " which is not a declared group of step '" (:step ref)
         "' (available: " (pr-str (:available-groups err)) ")")

    :prompt-ref-non-text-surface
    (str "Step '" step "': :prompt ref output key " (:output ref)
         " is not a per-prompt text surface"
         " (available: " (pr-str (:available-surfaces err)) ")")

    :prompt-ref-same-step
    (str "Step '" step "': :prompt ref targets the same step being assembled"
         " (sibling-group refs are only permitted in the step's own post-drain judge)")

    :session-contributions-and-prompts
    (str "Step '" step "': session step declares both :contributions and :prompts"
         "; a session step carries exactly one prompt source"
         " (use :contributions for a single prompt or :prompts for a named queue, never both)")

    :session-without-prompt-source
    (str "Step '" step "': session step declares neither :contributions nor :prompts"
         "; a session step requires exactly one prompt source"
         " (:contributions for a single prompt or :prompts for a named queue)")

    :unnamed-prompt-group
    (str "Step '" step "': a :prompts group is missing its :name"
         "; every prompt group in :prompts must be named")

    :duplicate-prompt-group-name
    (str "Step '" step "': :prompts declares duplicate prompt-group name(s) "
         (pr-str (:duplicate-names err))
         "; prompt-group names must be unique within a step")

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
