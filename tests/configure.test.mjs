import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
test('configuration generates a private random token, preserves it and refuses invalid configuration',()=>{
 const temp=fs.mkdtempSync(path.join(os.tmpdir(),'vdisplay-config-test-'));
 try{
  const home=path.join(temp,'home'),handoff=path.join(temp,'handoff.json');
  const run=()=>spawnSync(process.execPath,[fileURLToPath(new URL('../scripts/configure.mjs',import.meta.url)),handoff],{env:{...process.env,DSH_HOME:home},encoding:'utf8',windowsHide:true});
  const first=run();assert.equal(first.status,0,first.stderr);
  const config=path.join(home,'virtual-display.json'),token=JSON.parse(fs.readFileSync(config)).token;
  assert.match(token,/^[\w-]{43}$/);assert.ok(!first.stdout.includes(token));
  assert.equal(JSON.parse(fs.readFileSync(handoff)).token,token);
  assert.equal(run().status,0);assert.equal(JSON.parse(fs.readFileSync(config)).token,token);
  fs.writeFileSync(config,'{"token":"invalid"}');assert.notEqual(run().status,0);
  assert.equal(fs.readFileSync(config,'utf8'),'{"token":"invalid"}');
 }finally{fs.rmSync(temp,{recursive:true,force:true});}
});
