# Implementation notes

Created to track the custom-provider auth regression where real session-shaped string provider identities appear to lose provider-scoped auth lookup and fall through to built-in Anthropic missing-key behavior.

Expected investigation focus:

- session state stores model providers as strings
- model-registry auth is keyed by provider keyword
- shared provider-auth resolution is the likely normalization boundary

Record findings, implementation decisions, and verification results here during execution.
