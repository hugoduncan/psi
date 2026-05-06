Task created.

Initial framing:
- this task exists because task 095 intentionally used `requiring-resolve` as a temporary cycle-breaking seam when removing the static `agent-session -> system-bootstrap` dependency
- that seam was a good intermediate move, but not the desired final ownership shape
- the deeper issue is that whole-system registration is still partially discoverable from within a domain component rather than being owned explicitly by a higher-level composition root

Working hypothesis:
- each domain should expose only domain-local registration helpers
- one higher-level composition/bootstrap root should own whole-system registration assembly
- local/isolated query-context construction and global/app assembly should become explicit, separate responsibilities
