# Plan

Single change in `psi-projection.el`:

1. In `psi-emacs--upsert-projection-block`, replace the
   `(psi-emacs--ensure-newline-before-projection-append)` call with
   `(goto-char (point-max))` and add a comment explaining that the rendered
   block already starts with `"\n"`.

2. Add regression test `psi-footer-update-does-not-insert-newline-into-draft`
   to `psi-buffer-lifecycle-test.el`.

## Status

Complete — committed 9eac90ca.
