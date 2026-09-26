import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const root=path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const binary=name=>process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',name+(process.platform==='win32'?'.exe':'')):name;
function run(name,args){const r=spawnSync(binary(name),args,{cwd:root,stdio:'inherit',windowsHide:true});if(r.error)throw r.error;if(r.status!==0)throw Error(name+' failed: '+r.status);}
for(const file of ['build/android.jar','vendor/r8.jar'])if(!fs.existsSync(path.join(root,file)))throw Error('Missing '+file+'; run node scripts/download-build-tools.mjs');
for(const name of ['classes','dex']){
 const dir=path.resolve(root,'build',name);
 if(!dir.startsWith(path.resolve(root,'build')+path.sep))throw Error('Invalid build directory');
 fs.rmSync(dir,{recursive:true,force:true});fs.mkdirSync(dir,{recursive:true});
}
const sources=fs.readdirSync(path.join(root,'src')).filter(n=>n.endsWith('.java')).map(n=>'src/'+n);
run('javac',['-encoding','UTF-8','-source','11','-target','11','-cp','build/android.jar','-d','build/classes',...sources]);
run('jar',['cf','build/helper-classes.jar','-C','build/classes','.']);
run('java',['-cp','vendor/r8.jar','com.android.tools.r8.D8','--lib','build/android.jar','--min-api','30','--output','build/dex','build/helper-classes.jar']);
run('jar',['cfM','build/dsh-vdisplay.jar','-C','build/dex','classes.dex']);
console.log('Built build/dsh-vdisplay.jar');
