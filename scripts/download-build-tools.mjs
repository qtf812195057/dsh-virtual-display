import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const root=path.dirname(path.dirname(fileURLToPath(import.meta.url)));
fs.mkdirSync(path.join(root,'vendor'),{recursive:true});fs.mkdirSync(path.join(root,'build'),{recursive:true});
const files=[
 ['r8.jar','https://dl.google.com/android/maven2/com/android/tools/r8/8.13.23/r8-8.13.23.jar','e3cdcb003d9beca956209ad6b9e9df31f26b732bfaed9c7c8674e903ca9f3b81'],
 ['platform.zip','https://dl.google.com/android/repository/platform-35_r02.zip','0988cacad01b38a18a47bac14a0695f246bc76c1b06c0eeb8eb0dc825ab0c8e0']
];
for(const [name,url,sha] of files){
 const destination=path.join(root,'vendor',name);
 let bytes=fs.existsSync(destination)?fs.readFileSync(destination):null;
 if(!bytes||createHash('sha256').update(bytes).digest('hex')!==sha){
  const response=await fetch(url,{signal:AbortSignal.timeout(180000)});if(!response.ok)throw Error('Download failed: '+name);
  bytes=Buffer.from(await response.arrayBuffer());
  if(createHash('sha256').update(bytes).digest('hex')!==sha)throw Error('Checksum mismatch: '+name);
  fs.writeFileSync(destination,bytes);
 }
 console.log(name+': SHA256 verified');
}
const jar=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'jar.exe':'jar'):'jar';
const unpack=spawnSync(jar,['xf','platform.zip','android-35/android.jar'],{cwd:path.join(root,'vendor'),stdio:'inherit',windowsHide:true});
if(unpack.error||unpack.status!==0)throw Error('SDK extraction failed; JDK jar must be available');
fs.copyFileSync(path.join(root,'vendor/android-35/android.jar'),path.join(root,'build/android.jar'));
console.log('Build dependencies ready.');
