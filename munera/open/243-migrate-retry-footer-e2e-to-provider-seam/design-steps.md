# Design follow-ups

- [] Ambiguity (acceptance): "the parallel `with-redefs` test-isolation flakiness attributed to this pattern is re-evaluated" has no verifiable done-condition. Specify the concrete expected outcome/artifact — e.g. remove a specific parallel-test isolation guard/workaround, or confirm-and-record (where?) that the flakiness no longer applies once `with-redefs` is gone — so a plan author can tell when the criterion is met.
