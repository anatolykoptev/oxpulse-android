#!/usr/bin/env bash
# Fails unless the bluetooth-le major, the @capacitor/core major, the
# @capacitor/android major and the OxPulseCap/<N> token in the generated
# capacitor.config.json are all the same number.
#
# The web app turns BLE mesh on only when this N equals the bluetooth-le major
# bundled in its own JS. bluetooth-le 7.0 changed the JS-to-native hex encoding,
# so any other pairing corrupts or rejects BLE writes with no visible error.
# Run after `cap sync`, from the repository root.
set -euo pipefail
node - <<'JS'
const fs = require('node:fs');
const major = (pkg) => {
  const v = JSON.parse(fs.readFileSync(`node_modules/${pkg}/package.json`, 'utf8')).version;
  const m = /^(\d+)\./.exec(v);
  if (!m) throw new Error(`${pkg}: unparseable version "${v}"`);
  return Number(m[1]);
};
const cfgPath = 'android/app/src/main/assets/capacitor.config.json';
const ua = JSON.parse(fs.readFileSync(cfgPath, 'utf8')).android?.appendUserAgent ?? '';
// Same grammar the web parser uses: whitespace-delimited tokens.
const cap = [...ua.matchAll(/(?:^|\s)OxPulseCap\/(\d+)(?=\s|$)/g)];
const shell = [...ua.matchAll(/(?:^|\s)OxPulseShell\/1(?=\s|$)/g)];
const got = {
  'bluetooth-le': major('@capacitor-community/bluetooth-le'),
  'capacitor/core': major('@capacitor/core'),
  'capacitor/android': major('@capacitor/android'),
  'token N': cap.length === 1 ? Number(cap[0][1]) : NaN,
};
console.log(`appendUserAgent: ${JSON.stringify(ua)}`);
for (const [k, v] of Object.entries(got)) console.log(`  ${k.padEnd(18)} ${v}`);
const errs = [];
if (shell.length !== 1) errs.push(`expected exactly one OxPulseShell/1 token, found ${shell.length}`);
if (cap.length !== 1) errs.push(`expected exactly one OxPulseCap/<N> token, found ${cap.length}`);
if (new Set(Object.values(got)).size !== 1) errs.push('majors differ');
if (errs.length) {
  for (const e of errs) console.log(`::error::${e}`);
  process.exit(1);
}
console.log(`  ok      all four majors are ${got['token N']}`);
JS
