'use strict';

const path = require('path');
const os = require('os');

function launcherName(platform = process.platform) {
  return platform === 'win32' ? 'cljd-resolve.cmd' : 'cljd-resolve';
}

// GUI launches can inherit a barer PATH than a terminal. These are the common
// Babashka install locations considered after the user's configured PATH.
function fallbackPaths(platform = process.platform, env = process.env, home = os.homedir()) {
  if (platform === 'win32') {
    const programData = env.ProgramData || env.PROGRAMDATA || 'C:\\ProgramData';
    const localAppData = env.LOCALAPPDATA || path.join(home, 'AppData', 'Local');
    const chocolatey = env.ChocolateyInstall || path.join(programData, 'chocolatey');
    return [
      path.join(home, 'scoop', 'shims'),
      path.join(chocolatey, 'bin'),
      path.join(localAppData, 'Microsoft', 'WinGet', 'Links'),
      path.join(home, '.local', 'bin'),
      path.join(home, 'bin'),
    ];
  }
  return [
    '/opt/homebrew/bin',
    '/usr/local/bin',
    path.join(home, '.local', 'bin'),
    path.join(home, 'bin'),
  ];
}

function needsShell(platform = process.platform, command = '') {
  return platform === 'win32' && /\.(cmd|bat)$/i.test(command);
}

module.exports = { fallbackPaths, launcherName, needsShell };
