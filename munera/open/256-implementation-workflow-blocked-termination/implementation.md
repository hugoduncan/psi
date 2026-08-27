# Implementation notes

Task created from the request to add an explicit blocked termination path to `implement-task`, analogous in intent to the task-lifecycle unresolved scope-question handback.

No implementation has started. The existing implementation loop currently recognizes only `MORE_WORK_REMAINS` and `IMPLEMENTATION_COMPLETE`; the task must preserve their behavior while adding authored `IMPLEMENTATION_BLOCKED` routing and a lifecycle boundary before implementation review.

- architectural review added 1 new design step
- ambiguity review added 2 new design steps
- inconsistency review added 1 new design step
