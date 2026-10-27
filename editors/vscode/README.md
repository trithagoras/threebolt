# Threebolt for VS Code

Support for `.3b` files:

- **Syntax highlighting**: a TextMate grammar (`syntaxes/threebolt.tmLanguage.json`) that follows `ThreeboltLexer.g4`, plus comment toggling, bracket matching and auto-closing (`language-configuration.json`).
- **Language server** (`threebolt lsp`, bundled as `server/threebolt.jar`): errors as you type, across every file of the program; outline; hover with types and signatures; go to definition (including into std); find references; highlight uses; rename; completion after `.`, `::` and on names (including `use` paths); signature help in calls.

Not published; install it locally.

## Install

Needs Node and a JDK 25 or newer (the server runs on the JVM).

```sh
./gradlew vscodeServer                 # from the repo root: builds server/threebolt.jar
cd editors/vscode
npm install
npx @vscode/vsce package --allow-missing-repository --skip-license -o threebolt.vsix
code --install-extension threebolt.vsix
```

Reinstall the same way after changing the grammar or the server, then run **Developer: Reload Window**. To try out changes without installing, open this folder in VS Code and press F5 (**Run Extension**), which opens a window with the extension loaded.

Uninstall: `code --uninstall-extension threebolt.threebolt`.

## Settings

- `threebolt.java.home`: the JDK to run the server with. Empty: `JAVA_HOME`, then `java` on the `PATH`.
- `threebolt.server.jar`: a server jar to use instead of the bundled one, e.g. the repo's `build/server/threebolt.jar` after `./gradlew serverJar`, then **Threebolt: Restart Language Server**. Handy while working on the server.
- `threebolt.trace.server`: `messages` or `verbose` logs the protocol in the **Threebolt** output channel.

## How the server finds the program

A file is checked as part of the program rooted at the nearest `main.3b` at or above it (within the workspace folder), so errors and navigation cover every module that program uses. A file no such program uses is checked on its own, without requiring a `main`. Unsaved edits are seen by the whole program.

While a file has syntax errors, hover and navigation answer from its last version that parsed.

Completion checks a copy of the file with a placeholder name at the cursor (and `;` or closing brackets when the statement is unfinished), so it sees narrowed types and only the locals in scope. When even that does not parse, it offers the file's module-level names, the prelude and keywords.

## Notes

- Highlighting is lexical. Capitalised names are coloured as types (types, enum variants, type parameters), `name::` as a module path and `name(` as a call. `<` and `>` are always coloured as operators, including in generics.
- `get` is a keyword only in `get fn`. `t.0.1` is tuple access, not a float.
- Inside f-string `{…}` interpolations the code is highlighted as ordinary code. `{{` and `}}` are escapes, and a lone `}` is flagged.
- To inspect scopes, run **Developer: Inspect Editor Tokens and Scopes**.
- Rename leaves std and built-in names alone, and refuses a name used in a shorthand `{ x }`. Uses through an import alias (`use a::f as g`) keep the alias.
- Not yet: doc comments in hovers and completions, semantic highlighting, formatting.
