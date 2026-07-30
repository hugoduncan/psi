# Error Handling

The grammar includes an explicit yielded error form.

An error has:

- `:type :error`
- `:reason`
- `:message`
- optional `:details`

The purpose of `:reason` is to provide a stable keyword-classified cause.

The purpose of `:message` is to provide human-readable diagnostic text.

The purpose of `:details` is to carry structured diagnostic data.
