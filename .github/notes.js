// "What's new" for an update: every change since the last released update (so a quick follow-up fix
// never hides the real news). Changes starting with "Build fix:" are left out.
const { execSync } = require('child_process');
const run = (c) => execSync(c, { stdio: ['ignore', 'pipe', 'ignore'] }).toString();
let base = '';
try { base = run("git describe --tags --abbrev=0 --match 'build-*' HEAD^").trim(); } catch (e) { /* first release */ }
const range = base ? base + '..HEAD' : '-1';
const msgs = run(`git log ${range} --pretty=%B%x1e`).split('\x1e')
  .map((m) => m.split(/\r?\n/).filter((l) => !/^(Co-Authored-By|Claude-Session):/.test(l)).join('\n').trim())
  .filter((m) => m && !/^Build fix:/i.test(m));
let out = msgs.join('\n\n').trim() || 'Improvements';
if (out.length > 3500) out = out.slice(0, 3500) + '…';
require('fs').writeFileSync(process.argv[2], out + '\n');
console.log(out);
