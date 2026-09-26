import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
const root=path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const {version}=JSON.parse(fs.readFileSync(path.join(root,'phone-plugin/package.json'),'utf8'));
if(!/^\d+\.\d+\.\d+$/.test(version))throw Error('Invalid release version');
const dist=path.join(root,'dist');fs.mkdirSync(dist,{recursive:true});
const npmOptions={cwd:path.join(root,'phone-plugin'),stdio:'inherit',windowsHide:true};
const packed=process.platform==='win32'
 ?spawnSync('npm.cmd pack --ignore-scripts',{...npmOptions,shell:true})
 :spawnSync('npm',['pack','--ignore-scripts'],npmOptions);
if(packed.error||packed.status!==0)throw Error('npm pack failed');
const pluginName=`dsh-virtual-display-${version}.tgz`;
fs.renameSync(path.join(root,'phone-plugin',`local-dsh-virtual-display-${version}.tgz`),path.join(dist,pluginName));
const staging=fs.mkdtempSync(path.join(root,'build','release-'));
const folder=`dsh-vdisplay-${version}`,bundle=path.join(staging,folder);fs.mkdirSync(bundle);
const files={
 'helper.jar':'build/dsh-vdisplay.jar','scrcpy.jar':'vendor/scrcpy.jar',
 'viewer.html':'ui/viewer.html','viewer.js':'ui/viewer.js','viewer.css':'ui/viewer.css','video.js':'ui/video.js',
 'start.sh':'scripts/start.sh','install-phone.sh':'scripts/install-phone.sh','configure.mjs':'scripts/configure.mjs',
 [pluginName]:'dist/'+pluginName,'README.md':'README.md','VALIDATION.md':'VALIDATION.md','CHANGELOG.md':'CHANGELOG.md','LICENSE':'LICENSE','THIRD-PARTY-LICENSE.txt':'THIRD-PARTY-LICENSE.txt'
};
for(const [name,source] of Object.entries(files))fs.copyFileSync(path.join(root,source),path.join(bundle,name));
const hashes=Object.keys(files).sort().map(name=>createHash('sha256').update(fs.readFileSync(path.join(bundle,name))).digest('hex')+'  '+name);
fs.writeFileSync(path.join(bundle,'SHA256SUMS'),hashes.join('\n')+'\n');
const jar=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'jar.exe':'jar'):'jar';
const archive=`dsh-vdisplay-phone-${version}.zip`;
const zipped=spawnSync(jar,['cfM',path.join(dist,archive),'-C',staging,folder],{stdio:'inherit',windowsHide:true});
if(zipped.error||zipped.status!==0)throw Error('ZIP packaging failed');
fs.writeFileSync(path.join(dist,'SHA256SUMS'),[pluginName,archive].map(name=>createHash('sha256').update(fs.readFileSync(path.join(dist,name))).digest('hex')+'  '+name).join('\n')+'\n');
console.log('Release staged in '+bundle);
console.log('Created '+archive+' and '+pluginName);
