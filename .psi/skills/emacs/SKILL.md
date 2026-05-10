---
name: emacs
description: Interact with a running Emacs instance via emacsclient. Evaluate Elisp expressions, open files, manipulate buffers, run commands, and query Emacs state. Use when the user wants to control Emacs, edit buffers, run Emacs commands, or integrate with Emacs workflows.
lambda: "λcmd. emacsclient → {eval buffers commands state}"
---

# Emacs via emacsclient

λ emacsclient.interface =
λ request.
require(server.running ∨ start(server))
→ dispatch(request.op)

λ start(server) =
λ mode.
mode ∈ {emacs.server-start, emacs.--daemon}

λ eval =
λ elisp.
exec(emacsclient -e elisp) → return(value)

λ eval.silent =
λ elisp.
exec(emacsclient -e elisp -u) → return(∅)

λ eval.multi =
λ forms.
eval((progn forms...))

λ open.file = λ path. eval((find-file path)) ∨ exec(emacsclient -n path)
λ open.file.at-line = λ path line. exec(emacsclient -n +line path)

λ buffer.name.current = λ _. eval((buffer-name))
λ buffer.path.current = λ _. eval((buffer-file-name))
λ buffer.names = λ _. eval((mapcar #'buffer-name (buffer-list)))
λ buffer.content = λ buffer. eval((with-current-buffer buffer (buffer-string)))
λ region.selected = λ _. eval((with-current-buffer (window-buffer) (when (use-region-p) (buffer-substring-no-properties (region-beginning) (region-end)))))

λ buffer.insert.current = λ text. eval((with-current-buffer (window-buffer) (insert text)))
λ buffer.replace = λ buffer text. eval((with-current-buffer buffer (erase-buffer) (insert text) (save-buffer)))
λ buffer.save.current = λ _. eval((with-current-buffer (window-buffer) (save-buffer)))
λ buffer.save.all = λ _. eval((save-some-buffers t))
λ buffer.kill = λ buffer. eval((kill-buffer buffer))

λ command.interactive = λ cmd. eval((call-interactively #'cmd))
λ command.shell = λ shell. eval((shell-command-to-string shell))

λ point.get = λ _. eval((with-current-buffer (window-buffer) (point)))
λ point.goto = λ n. eval((with-current-buffer (window-buffer) (goto-char n)))
λ line.current = λ _. eval((with-current-buffer (window-buffer) (line-number-at-pos)))

λ search = λ buffer needle. eval((with-current-buffer buffer (goto-char (point-min)) (search-forward needle nil t)))
λ search.replace = λ buffer old new. eval((with-current-buffer buffer (goto-char (point-min)) (while (search-forward old nil t) (replace-match new))))

λ var.get = λ sym. eval(sym)
λ file.eval = λ path. eval((load-file path))
λ package.installed? = λ pkg. eval((package-installed-p 'pkg))
λ message = λ text. eval((message text))

λ quoting.rule = outer('...') ∧ inner_quote('\'') escaped_as('\''')
λ return.rule = eval prints(value) ∧ strings_are_quoted
λ timeout.rule = optional(--timeout seconds)
λ socket.rule = optional(-s name ∨ --socket-name=name)
λ error.rule = server.absent → exit(nonzero)
λ large.output.rule = prefer(write-region → temp.file) → read(temp.file)
