(ns psi.recursion.resolvers
  "Pathom3 resolvers and mutations exposing recursion controller state
   and operator controls via the EQL surface.

   Domain attributes:

   Recursion controller state
     :psi/recursion-ctx                 — a RecursionContext (required input seed)
     :psi.recursion/state               — full controller state map
     :psi.recursion/status              — controller status keyword
     :psi.recursion/paused?             — boolean, true when controller is paused
     :psi.recursion/current-cycle       — current non-terminal cycle, or nil
     :psi.recursion/current-future-state — current FutureStateSnapshot, or nil
     :psi.recursion/policy              — GuardrailPolicy map
     :psi.recursion/recent-cycles       — last 10 cycles, newest first
     :psi.recursion/last-outcome        — most recent cycle's CycleOutcome, or nil
     :psi.recursion/hooks               — list of ToolingHook maps

   Control mutations
     psi.recursion/trigger!  — fire a manual trigger
     psi.recursion/pause!    — pause the controller
     psi.recursion/resume!   — resume from paused state
     psi.recursion/approve!  — approve a pending proposal
     psi.recursion/reject!   — reject a pending proposal"
  (:require
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.recursion.core :as core]))

;;; --- Resolvers ---

(pco/defresolver recursion-context-state
  "Resolve :psi.recursion/state from :psi/recursion-ctx."
  [input]
  {::pco/input  [:psi/recursion-ctx]
   ::pco/output [:psi.recursion/state]}
  (let [recursion-ctx (:psi/recursion-ctx input)]
    {:psi.recursion/state (core/get-state-in recursion-ctx)}))

(defn- active-cycle
  "Return the most recent non-terminal cycle, or nil."
  [cycles]
  (let [terminal? #{:completed :failed :aborted :blocked}]
    (first (filter #(not (terminal? (:status %))) (reverse cycles)))))

(pco/defresolver recursion-state
  "Resolve all required :psi.recursion/* attrs from :psi.recursion/state."
  [input]
  {::pco/input  [:psi.recursion/state]
   ::pco/output [:psi.recursion/status
                 :psi.recursion/paused?
                 :psi.recursion/current-cycle
                 :psi.recursion/current-future-state
                 :psi.recursion/policy
                 :psi.recursion/recent-cycles
                 :psi.recursion/last-outcome
                 :psi.recursion/hooks]}
  (let [state (:psi.recursion/state input)
        cycles (:cycles state)
        recent (vec (take 10 (reverse cycles)))
        last-cycle (first (reverse cycles))
        last-outcome (when last-cycle (:outcome last-cycle))]
    {:psi.recursion/status              (:status state)
     :psi.recursion/paused?             (= :paused (:status state))
     :psi.recursion/current-cycle       (active-cycle cycles)
     :psi.recursion/current-future-state (:current-future-state state)
     :psi.recursion/policy              (:policy state)
     :psi.recursion/recent-cycles       recent
     :psi.recursion/last-outcome        last-outcome
     :psi.recursion/hooks               (:hooks state)}))

;;; --- Mutations ---

(pco/defmutation trigger!
  "Fire a manual trigger on the recursion controller via the production
   orchestration entrypoint.

   Params:
     :psi/recursion-ctx  — recursion context
     :reason             — trigger reason string (default: \"manual-trigger\")
     :system-state       — readiness map with :query-ready, :graph-ready,
                           :introspection-ready, :memory-ready keys
     :graph-state        — optional graph snapshot map for observe phase
     :memory-state       — optional memory snapshot map for observe phase
     :psi/memory-ctx     — optional memory context for learn phase
     :approval-decision  — nil | :approve | :reject (or string equivalent)
     :approver           — approver/reviewer identity
     :approval-notes     — approval/rejection notes"
  [_ {:keys [psi/recursion-ctx reason system-state graph-state memory-state
             psi/memory-ctx approval-decision approver approval-notes]}]
  {::pco/op-name 'psi.recursion/trigger!
   ::pco/params  [:psi/recursion-ctx :reason :system-state :graph-state :memory-state
                  :psi/memory-ctx :approval-decision :approver :approval-notes]
   ::pco/output  [:psi.recursion/trigger-result
                  :psi.recursion/orchestration-result]}
  (let [trigger-signal (core/manual-trigger-signal reason {:source :eql-mutation})
        orchestration (core/orchestrate-manual-trigger-in!
                       recursion-ctx
                       trigger-signal
                       {:system-state (or system-state
                                          {:query-ready true
                                           :graph-ready true
                                           :introspection-ready true
                                           :memory-ready true})
                        :graph-state graph-state
                        :memory-state memory-state
                        :memory-ctx memory-ctx
                        :approval-decision approval-decision
                        :approver approver
                        :approval-notes approval-notes})]
    {:psi.recursion/trigger-result (:trigger-result orchestration)
     :psi.recursion/orchestration-result orchestration}))

(pco/defmutation pause!
  "Pause the recursion controller with a reason.

   Params:
     :psi/recursion-ctx — recursion context
     :reason            — pause reason string"
  [_ {:keys [psi/recursion-ctx reason]}]
  {::pco/op-name 'psi.recursion/pause!
   ::pco/params  [:psi/recursion-ctx :reason]
   ::pco/output  [:psi.recursion/paused?]}
  (let [state (core/get-state-in recursion-ctx)]
    (if (= :paused (:status state))
      {:psi.recursion/paused? true}
      (do
        (core/swap-state-in! recursion-ctx
                             (fn [s]
                               (-> s
                                   (assoc :status :paused)
                                   (assoc :paused-reason (or reason "operator-pause"))
                                   (assoc :paused-checkpoint {:status (:status s)
                                                              :at (java.time.Instant/now)}))))
        {:psi.recursion/paused? true}))))

(pco/defmutation resume!
  "Resume the recursion controller from paused state.

   Params:
     :psi/recursion-ctx — recursion context"
  [_ {:keys [psi/recursion-ctx]}]
  {::pco/op-name 'psi.recursion/resume!
   ::pco/params  [:psi/recursion-ctx]
   ::pco/output  [:psi.recursion/resumed?]}
  (let [state (core/get-state-in recursion-ctx)]
    (if (= :paused (:status state))
      (do
        (core/swap-state-in! recursion-ctx
                             (fn [s]
                               (let [resume-status (or (get-in s [:paused-checkpoint :status]) :idle)]
                                 (-> s
                                     (assoc :status resume-status)
                                     (assoc :paused-reason nil)
                                     (assoc :paused-checkpoint nil)))))
        {:psi.recursion/resumed? true})
      {:psi.recursion/resumed? false})))

(pco/defmutation approve!
  "Approve a pending proposal.

   Params:
     :psi/recursion-ctx — recursion context
     :cycle-id          — cycle ID to approve
     :approver          — approver identity string
     :notes             — approval notes string"
  [_ {:keys [psi/recursion-ctx cycle-id approver notes]}]
  {::pco/op-name 'psi.recursion/approve!
   ::pco/params  [:psi/recursion-ctx :cycle-id :approver :notes]
   ::pco/output  [:psi.recursion/approval-result]}
  (let [result (core/approve-proposal-in! recursion-ctx cycle-id
                                          (or approver "operator")
                                          (or notes ""))]
    {:psi.recursion/approval-result result}))

(pco/defmutation reject!
  "Reject a pending proposal.

   Params:
     :psi/recursion-ctx — recursion context
     :cycle-id          — cycle ID to reject
     :approver          — rejector identity string
     :notes             — rejection notes string"
  [_ {:keys [psi/recursion-ctx cycle-id approver notes]}]
  {::pco/op-name 'psi.recursion/reject!
   ::pco/params  [:psi/recursion-ctx :cycle-id :approver :notes]
   ::pco/output  [:psi.recursion/rejection-result]}
  (let [result (core/reject-proposal-in! recursion-ctx cycle-id
                                         (or approver "operator")
                                         (or notes ""))]
    {:psi.recursion/rejection-result result}))

;;; --- Registration helpers ---

(def all-resolvers
  [recursion-context-state
   recursion-state])

(def all-mutations
  [trigger!
   pause!
   resume!
   approve!
   reject!])
