# Threebolt for VS Code

Syntax highlighting for `.3b` files: a TextMate grammar (`syntaxes/threebolt.tmLanguage.json`) that follows `ThreeboltLexer.g4`, plus comment toggling, bracket matching and auto-closing (`language-configuration.json`). Not published; install it locally.

## Install

Package and install it (needs Node):

```sh
cd editors/vscode
npx @vscode/vsce package --allow-missing-repository --skip-license -o threebolt.vsix
code --install-extension threebolt.vsix
```

Reinstall the same way after changing the grammar, then run **Developer: Reload Window**. To try out changes without installing, open this folder in VS Code and press F5 (**Run Extension**), which opens a window with the extension loaded.

Uninstall: `code --uninstall-extension threebolt.threebolt`.

## Notes

- Highlighting is lexical. Capitalised names are coloured as types (types, enum variants, type parameters), `name::` as a module path and `name(` as a call. `<` and `>` are always coloured as operators, including in generics.
- `get` is a keyword only in `get fn`. `t.0.1` is tuple access, not a float.
- Inside f-string `{…}` interpolations the code is highlighted as ordinary code. `{{` and `}}` are escapes, and a lone `}` is flagged.
- To inspect scopes, run **Developer: Inspect Editor Tokens and Scopes**.
