import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {randomBytes} from 'node:crypto';
import {fileURLToPath} from 'node:url';

// Run inside DSHA. install-phone.sh consumes the temporary handoff.
const home=process.env.DSH_HOME||path.join(os.homedir(),'.dsh');
const output=process.argv[2]||path.join(path.dirname(fileURLToPath(import.meta.url)),'virtual-display.setup.json');
const config=path.join(home,'virtual-display.json');
if(path.resolve(output)===path.resolve(config))throw Error('Handoff path must differ from private configuration.');
fs.mkdirSync(home,{recursive:true,mode:0o700});
let token;
if(fs.existsSync(config)){
 token=JSON.parse(fs.readFileSync(config,'utf8')).token;
 if(typeof token!=='string'||!/^[-\w]{43}$/.test(token))throw Error('Existing configuration is invalid; refusing to replace it.');
}else{
 token=randomBytes(32).toString('base64url');
 fs.writeFileSync(config,JSON.stringify({token})+'\n',{mode:0o600,flag:'wx'});
}
fs.chmodSync(config,0o600);
fs.writeFileSync(output,JSON.stringify({token})+'\n',{mode:0o600,flag:'w'});
console.log('DSHA configuration ready. Temporary handoff: '+output);
console.log('Run install-phone.sh in your authorized Android shell. It deletes the handoff after installation.');
