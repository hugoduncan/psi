(ns psi.ai.providers.request-support
  "Shared provider request-support helpers used by all three provider
   transports (:anthropic-messages, :openai-completions,
   :openai-codex-responses) and their capture-redaction paths.

   Provider-scoped API-key resolution, keyless-auth detection, auth-header
   recognition and the capture-redaction primitives were triplicated across
   providers/anthropic.clj, providers/openai/chat_completions.clj,
   providers/openai/codex_responses.clj and providers/openai/transport.clj
   (reviews 3/4/7/10/11/13 introduced and hardened them, and the copies
   repeatedly drifted — reviews 9/10/13 reconciled spec/behavior mismatches
   between them). They live here once, parameterized by the transport's
   built-in provider keyword and env var name, so future fixes land once
   (review 14)."
  (:require [clojure.string :as str]))

(defn getenv
  "Environment lookup indirection, redef-testable (review 3 pattern).
   The three transports' `resolve-api-key` call this instead of
   System/getenv directly so tests can stub the env without forking
   processes."
  [k]
  (System/getenv k))

(defn resolve-key-spec
  "Resolve an api-key spec at REQUEST time:
   - nil / blank → nil
   - \"env:VAR\" → (getenv \"VAR\"), nil if unset
   - anything else → the literal string

   The `env:` prefix is case-sensitive: only the exact lowercase `env:`
   prefix triggers environment lookup — \"ENV:VAR\"/\"Env:VAR\" fall through
   to the literal branch and are sent as the key verbatim (provider-side
   401, never an env lookup). Docs and the missing-key error suggestion use
   lowercase `env:` consistently.

   Custom-provider `env:` keys are stored RAW in the registry (not resolved
   at models.edn parse time, review 26) and re-resolved here per request —
   matching the built-in env fallback's live semantics, so exporting the var
   after psi has loaded models.edn works without a reload. This shared helper
   is the single env-resolution home (review 28: the config-parse layer's
   `user_models/resolve-api-key-spec` delegation wrapper was deleted as
   production-dead)."
  [raw]
  (cond
    (or (nil? raw) (str/blank? raw)) nil
    (str/starts-with? raw "env:")
    (let [var (subs raw 4)]
      ;; A blank variable name after the prefix (e.g. "env:") is an
      ;; unresolvable spec, never an environment lookup of the empty string
      ;; (review 30) — `getenv ""` would silently return nil and the caller
      ;; would report a misleading "environment variable  is unset" naming a
      ;; blank variable. Nil here means "not resolvable", same as an unset var.
      (when-not (str/blank? var)
        (getenv var)))
    :else raw))

(def openai-api-key-config
  "Shared OpenAI api-key resolution config for the :openai-completions and
   :openai-codex-responses transports. Defined once here (previously two
   byte-identical per-transport copies in chat_completions.clj /
   codex_responses.clj, review 16) so the env-var name and built-in
   missing-key message cannot drift between the transports."
  {:builtin-provider    :openai
   :env-var             "OPENAI_API_KEY"
   :builtin-missing-msg "Missing OpenAI API key. Set OPENAI_API_KEY or login via /login openai."})

(defn auth-header?
  "True when a header name is a recognized auth header (case-insensitive)."
  [header]
  (contains? #{"x-api-key" "authorization"}
             (str/lower-case (name header))))

(defn no-auth?
  "True when a request should be built without resolving an API key:
   explicit `:no-auth-header` (e.g. `:auth-header? false` local servers), or
   custom `:headers` carrying a recognized auth header (x-api-key /
   authorization, case-insensitive) with no configured `:api-key`.
   Incidental custom headers (e.g. X-Client) do NOT imply keyless — with a
   blank configured key such a request fast-fails with the clear
   \"Missing API key\" error instead of silently sending a keyless request
   (review 5)."
  [options]
  (or (:no-auth-header options)
      (and (seq (:headers options))
           (str/blank? (:api-key options))
           (some auth-header? (keys (:headers options))))))

(defn builtin?
  "True when a model is a built-in catalog model of the transport: the model
   is not tagged `:custom?` (custom models.edn models are tagged at parse
   time by `expand-model`) and its provider is nil or `builtin-provider-kw`.

   The `:custom?` guard closes the review-14 gap where a custom models.edn
   provider literally named \"anthropic\"/\"openai\" was classified built-in
   by provider name alone, defeating the provider-scoped guarantees: such a
   provider must never fall back to the user's built-in env key or receive
   built-in-only treatment (e.g. Claude Code OAuth headers)."
  [model builtin-provider-kw]
  (and (not (:custom? model))
       (let [provider (:provider model)]
         (or (nil? provider) (= builtin-provider-kw provider)))))

(defn builtin-openai-chat-completions?
  "True when a model is a built-in OpenAI chat-completions model: built-in
   classification via `builtin?` (provider nil or :openai, not tagged
   `:custom?`) AND api :openai-completions.

   This is the shared built-in-classification predicate for agent-session's
   mid-conversation system-message inference (model_capabilities.clj,
   review 26): the origin-tag + provider built-in semantics live here once,
   alongside the provider transports' `builtin?`, instead of an inline copy
   that could drift. The api constraint preserves the inference's
   chat-completions-only intent — codex-routed built-ins (gpt-5.5/gpt-5.6-*
   under OAuth) have api :openai-codex-responses and never match."
  [model]
  (and (builtin? model :openai)
       (= :openai-completions (:api model))))

(defn resolve-api-key
  "Resolve the API key for a request, scoped to the request's provider.

   `config` is a map with:
   - `:builtin-provider` — the keyword identifying built-in models (e.g.
     `:anthropic` or `:openai`);
   - `:env-var` — the env var built-in models fall back to (e.g.
     \"ANTHROPIC_API_KEY\");
   - `:builtin-missing-msg` — the error message for built-in models with no
     configured key and no env var.

   Built-in models (`builtin?`) fall back to the env var. Custom providers
   never fall back to that env var: a nil/blank configured key is an error,
   so a custom provider's request can never silently send the user's
   built-in provider key to a third-party endpoint (the provider-scoped
   resolution introduced in review 3 for :anthropic-messages and extended to
   :openai-completions (review 10) and :openai-codex-responses (review 13)).

   When the options are keyless (`no-auth?` — `:no-auth-header` set, e.g.
   `:auth-header? false` local servers, or a recognized auth header among
   custom `:headers` with no configured `:api-key`), no key is required: the
   caller strips the auth headers anyway, so this returns nil instead of
   failing. The keyless contract lives in one predicate (`no-auth?`, review
   22) so a direct caller cannot drift from what the request builders gate
   on.

   The configured `:api-key` may be an `env:` spec (custom models.edn keys
   are stored RAW in the registry, review 26): it is re-resolved through
   `getenv` per request, matching the built-in env fallback's live
   semantics. A custom provider whose `env:VAR` is unset at request time
   fails fast with an error naming the variable (not a generic \"configure
   :auth in models.edn\" — the config is already there)."
  [model options config]
  (when-not (no-auth? options)
    (let [{:keys [builtin-provider env-var builtin-missing-msg]} config
          provider   (:provider model)
          builtin?   (builtin? model builtin-provider)
          api-key    (resolve-key-spec (:api-key options))
          api-key    (if (and builtin? (str/blank? api-key))
                       (getenv env-var)
                       api-key)]
      (when (str/blank? api-key)
        (if builtin?
          (throw (ex-info builtin-missing-msg
                          {:error-code "auth/missing-api-key"
                           :provider builtin-provider}))
          ;; OAuth /login only exists for built-in providers, so custom
          ;; providers must not hint at it — the remedy is models.edn :auth.
          ;; When the configured spec is an env: string, name the unset var
          ;; instead of pointing back at models.edn (the user already
          ;; configured it there; review 26).
          (let [spec (some-> (:api-key options) str)
                env-var (when (and (string? spec)
                                   (str/starts-with? spec "env:"))
                          (subs spec 4))]
            (cond
              ;; An env: spec with a blank variable name (e.g. "env:") is a
              ;; config error naming the literal spec — never the misleading
              ;; "environment variable  is unset" with a blank name (review 30).
              (and (string? env-var) (str/blank? env-var))
              (throw (ex-info (str "Missing API key for provider " (name provider)
                                   ": api-key spec \"" spec "\" names an empty"
                                   " environment variable (use \"env:VAR_NAME\").")
                              {:error-code "auth/missing-api-key"
                               :provider provider}))

              env-var
              (throw (ex-info (str "Missing API key for provider " (name provider)
                                   ": environment variable " env-var
                                   " is unset (env: keys are re-read per request"
                                   " — export it and retry).")
                              {:error-code "auth/missing-api-key"
                               :provider provider}))

              :else
              (throw (ex-info (str "Missing API key for provider " (name provider)
                                   ". Configure the provider's :auth {:api-key ...} in models.edn"
                                   ;; The suggested env var name normalizes kebab-case
                                   ;; provider keys (- → _): :my-anthropic-proxy must
                                   ;; suggest MY_ANTHROPIC_PROXY_API_KEY (bash
                                   ;; identifiers cannot contain hyphens), not
                                   ;; MY-ANTHROPIC-PROXY_API_KEY (review 12).
                                   " (e.g. \"env:" (-> (name provider)
                                                       (str/replace "-" "_")
                                                       str/upper-case)
                                   "_API_KEY\").")
                              {:error-code "auth/missing-api-key"
                               :provider provider}))))))
      api-key)))

;; ── Stream event helpers ─────────────────────────────────────────────────────

(defn emit-start!
  "Emit :start exactly once for a stream, when the stream never emitted it.

   The compare-and-set on the `started?` atom makes this idempotent across
   every call site, so the transport can call it before any event that might
   be the stream's first (:start must precede the first output/terminal/
   error/content event when the stream never received its opening event —
   message_start / a role-or-content chunk / output_item.added — e.g. a
   malformed/truncated stream, an error-first stream, a content-block-first
   stream, or a stream-read exception before any output).

   Review 54: extracted from the three byte-identical per-transport copies
   (anthropic's `emit-start!` from review 50, chat-completions'
   `emit-stream-start!` from review 53, codex's `emit-codex-start!` from
   review 52) — the review-14 triplication class request_support.clj exists
   to prevent: a future :start-semantics change (e.g. carrying a payload, or
   a different once-guard) must land in one place, not three."
  [consume-fn started?]
  (when (compare-and-set! started? false true)
    (consume-fn {:type :start})))

;; ── Capture redaction ────────────────────────────────────────────────────────

(defn find-headers
  "Find ALL header entries whose names match header-name case-insensitively.
   Returns a seq of [key value] pairs (empty when none match). Auth-header
   recognition is case-insensitive, so every differently-cased duplicate of
   an auth header name on the wire (base \"x-api-key\" + custom \"X-API-Key\",
   or \"Authorization\" + \"authorization\") must be found: redacting only
   the first match would leak the duplicate verbatim into the
   :on-provider-request capture (review 19)."
  [headers header-name]
  (let [target (str/lower-case header-name)]
    (keep (fn [[k v]]
            (when (= target (str/lower-case (name k)))
              [k v]))
          headers)))

(defn find-header
  "Find the FIRST header entry whose name matches header-name
   case-insensitively. Returns a [key value] pair, or nil. Auth-header
   recognition is case-insensitive, so a mixed-case X-API-Key /
   authorization header must be redacted too (review 7)."
  [headers header-name]
  (first (find-headers headers header-name)))

(defn redact-secret
  [value]
  (when (string? value)
    (str "***REDACTED***"
         (when (> (count value) 20)
           (str " (len=" (count value) ")")))))

(defn redact-authorization
  [value]
  (when (string? value)
    (str "Bearer "
         ;; Strip a leading "Bearer " prefix before counting so the (len=N)
         ;; metadata measures the secret itself, not the 7-char prefix
         ;; (review 13 aligned the openai redactor with the anthropic one).
         (redact-secret (str/replace value #"^Bearer\s+" "")))))

(defn mask-chatgpt-account-id
  [value]
  (when (string? value)
    (str (subs value 0 (min 6 (count value))) "...")))

(defn redact-headers
  "Redact auth headers from a request header map. `redactors` is a seq of
   [header-name redactor-fn] pairs; header names are matched
   case-insensitively and EVERY matching header — including differently-cased
   duplicates of the same auth header name on the wire (e.g. base
   \"x-api-key\" + custom \"X-API-Key\", or \"Authorization\" +
   \"authorization\") — is redacted, with the redacted value written back
   under the original key casing. Non-auth headers pass through unchanged."
  [headers redactors]
  (reduce (fn [hdr [name redactor]]
            (reduce (fn [h [k v]]
                      (assoc h k (redactor v)))
                    hdr
                    (find-headers hdr name)))
          headers
          redactors))
