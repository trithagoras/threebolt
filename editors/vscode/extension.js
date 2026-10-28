// @ts-check
// Starts the Threebolt language server (`java -jar threebolt.jar lsp`) for .3b files.
const fs = require('fs');
const path = require('path');
const vscode = require('vscode');
const { LanguageClient } = require('vscode-languageclient/node');

/** @type {LanguageClient | undefined} */
let client;

/** @param {vscode.ExtensionContext} context */
async function activate(context) {
  context.subscriptions.push(vscode.commands.registerCommand('threebolt.restartServer', async () => {
    await stop();
    await start(context);
  }));
  await start(context);
}

/** @param {vscode.ExtensionContext} context */
async function start(context) {
  const config = vscode.workspace.getConfiguration('threebolt');
  const jar = config.get('server.jar') || path.join(context.extensionPath, 'server', 'threebolt.jar');
  if (!fs.existsSync(jar)) {
    vscode.window.showErrorMessage(`Threebolt: language server not found at ${jar}. Run \`./gradlew vscodeServer\` and repackage the extension, or set threebolt.server.jar.`);
    return;
  }
  const javaHome = config.get('java.home') || process.env.JAVA_HOME;
  const java = javaHome ? path.join(javaHome, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
  const run = { command: java, args: ['--enable-native-access=ALL-UNNAMED', '-jar', jar, 'lsp'] };
  client = new LanguageClient('threebolt', 'Threebolt', { run, debug: run }, {
    documentSelector: [{ scheme: 'file', language: 'threebolt' }],
    // Packages (spec §2.5) change when their manifests do; the server re-checks open files.
    synchronize: { fileEvents: vscode.workspace.createFileSystemWatcher('**/{package,workspace}.toml') },
  });
  try {
    await client.start();
  } catch (e) {
    vscode.window.showErrorMessage(`Threebolt: could not start the language server with ${java} (it needs JDK 25 or newer; see threebolt.java.home): ${e}`);
    client = undefined;
  }
}

async function stop() {
  if (client) await client.stop();
  client = undefined;
}

module.exports = { activate, deactivate: stop };
