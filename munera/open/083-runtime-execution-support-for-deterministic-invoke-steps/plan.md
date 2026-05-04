Approach:
- build invoke execution as a first-class runtime path on top of the IR execution substrate rather than as a side-channel helper
- keep invoke-step semantics parallel to other step forms where possible: attempts, results, progression, routing, and observability should stay structurally coherent across forms
- use minimal concrete operations or test doubles to prove the execution model before broadening real integrations
- prefer explicit resolution of invoke arg source specs before calling the registry implementation

Likely steps:
1. identify the IR execution seam where step type dispatch should add `:invoke`
2. resolve invoke args from workflow input, workflow original, prior step outputs, and prior step yields
3. call the deterministic operation registry with the resolved arg map
4. normalize/validate the returned result against the canonical invoke result contract
5. record invoke attempt/result data so downstream refs can read `:output` and `:yield` surfaces
6. integrate invoke success/failure into progression, terminal status, and routing
7. prove invoke-step routing behavior with both direct transitions and judge-driven transitions where appropriate
8. add focused regression tests for invoke-only and mixed-form workflows

Proof target:
- a workflow with `:type :invoke` steps executes through the canonical runtime path and its outputs/yields are consumable by downstream workflow steps

Risks:
- current step-attempt/result recording may assume session-oriented execution too deeply
- invoke failure semantics may need careful shaping so they align with existing workflow terminal/error behavior
- mixed-form workflows may reveal gaps in shared output/yield reference resolution
