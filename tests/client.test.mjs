import {test,after} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {randomBytes} from 'node:crypto';
import {request} from '../phone-plugin/lib/client.js';
const originalFetch=globalThis.fetch,originalHome=process.env.DSH_HOME;
const temporary=fs.mkdtempSync(path.join(os.tmpdir(),'vdisplay-client-test-'));
const token=randomBytes(32).toString('base64url');
process.env.DSH_HOME=temporary;
fs.writeFileSync(path.join(temporary,'virtual-display.json'),JSON.stringify({token}));
fs.writeFileSync(path.join(temporary,'pc-bridge.json'),JSON.stringify({url:'https://must-never-be-used.invalid',token}));
after(()=>{globalThis.fetch=originalFetch;if(originalHome===undefined)delete process.env.DSH_HOME;else process.env.DSH_HOME=originalHome;fs.rmSync(temporary,{recursive:true,force:true});});
test('start uses one local request even if an old remote config exists',async()=>{
 const calls=[];globalThis.fetch=async(url,opts)=>{calls.push({url,opts});throw new TypeError('connection refused');};
 await assert.rejects(request('start',{package:'com.example.app'}),/Shizuku/);
 assert.equal(calls.length,1);assert.equal(calls[0].url,'http://127.0.0.1:3096/start');
 assert.equal(calls[0].opts.redirect,'error');assert.equal(calls[0].opts.headers.Authorization,'Bearer '+token);
});
test('manual takeover is returned without retries',async()=>{
 let calls=0;globalThis.fetch=async()=>{calls++;return new Response(JSON.stringify({error:'MANUAL_CONTROL'}),{status:423});};
 await assert.rejects(request('tap',{x:1,y:1}),/MANUAL_CONTROL/);assert.equal(calls,1);
});
test('cancelled actions never start a request',async()=>{
 globalThis.fetch=async()=>{throw Error('must not fetch');};
 await assert.rejects(request('tap',{},AbortSignal.abort()),/取消/);
});
test('screenshots retain dimensions and revision',async()=>{
 globalThis.fetch=async()=>new Response(new Uint8Array([255,216]),{headers:{'X-Display-Width':'1280','X-Display-Height':'720','X-Display-Revision':'3'}});
 const image=await request('screenshot');assert.equal(image.width,1280);assert.equal(image.height,720);assert.equal(image.revision,3);assert.equal(image.data.length,2);
});
test('unknown endpoints and credential errors do not trigger recovery',async()=>{
 await assert.rejects(request('../remote'),/Unknown/);
 let calls=0;globalThis.fetch=async()=>{calls++;return new Response('',{status:401});};
 await assert.rejects(request('start',{}),/凭据不匹配/);assert.equal(calls,1);
});
