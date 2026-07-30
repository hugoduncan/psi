(ns psi.ai.textual-tool-calls
  "Model-capability-gated parser for local-runner textual tool-call markup."
  (:require
   [psi.ai.textual-tool-calls.capabilities :as capabilities]
   [psi.ai.textual-tool-calls.normalizer :as normalizer]
   [psi.ai.textual-tool-calls.parser :as parser]))

(def supported-formats
  "Textual tool-call formats understood by Psi."
  capabilities/supported-formats)

(defn supports-format?
  "Return true when resolved model explicitly opts into textual tool calls for format."
  [model format]
  (capabilities/supports-format? model format))

(def ^:dynamic *parse-work-counter*
  "Optional instrumentation hook for parser tests. When bound to an atom,
  parser scan work increments the atom without affecting parse semantics."
  nil)

(defn parse-xml-tool-calls
  "Parse well-formed XML-like textual tool calls from text.

   Returns successful calls in response order. Malformed blocks are omitted from
   the result so callers can leave their source text unchanged. Later valid
   blocks are still recoverable when they are outside earlier candidate spans."
  [text]
  (binding [parser/*parse-work-counter* *parse-work-counter*]
    (parser/parse-xml-tool-calls text)))

(defn normalize-assistant-message
  "Recover textual tool calls in an assistant message when the resolved model opts in.

   The normalizer is intentionally pure and narrow. It only transforms text blocks
   when `model` declares `{:capabilities {:textual-tool-calls #{:xml}}}`; all
   other models return the assistant message unchanged. Well-formed textual
   calls are converted to canonical tool-call blocks and their exact source spans
   are removed from assistant text. Malformed markup remains ordinary text."
  [turn-id model assistant-message]
  (normalizer/normalize-assistant-message turn-id model assistant-message))
