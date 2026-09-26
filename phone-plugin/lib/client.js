import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
const bridgeConfigPath=()=>path.join(process.env.DSH_HOME||path.join(os.homedir(),'.dsh'),'pc-bridge.json');

async function bridgeRequest(method,signal){
 let cfg;
 try{cfg=JSON.parse(fs.readFileSync(bridgeConfigPath(),'utf8'));}
 catch{throw Error('副屏后台服务未运行（127.0.0.1:3096 未响应）。未配置电脑自愈通道；请在手机端通过 Shizuku 启动服务，或连接电脑运行激活脚本。');}
 const base=new URL(cfg.url);
 if(base.protocol!=='https:'||base.username||base.password||base.search||base.hash)throw Error('电脑恢复通道地址无效');
 if(typeof cfg.token!=='string'||cfg.token.length<32)throw Error('电脑恢复通道凭据无效');
 let response;
 try{response=await fetch(new URL('/v1/vdisplay/recover',base.origin),{
  method,redirect:'error',headers:{Authorization:'Bearer '+cfg.token},
  signal:signal?AbortSignal.any([signal,AbortSignal.timeout(12000)]):AbortSignal.timeout(12000)
 });}
 catch{if(signal?.aborted)throw Error('副屏恢复已取消；电脑端操作可能仍在执行');throw Error('无法连接电脑恢复通道');}
 const data=await response.json().catch(()=>({error:'电脑端返回格式错误'}));
 if(!response.ok)throw Error('电脑端恢复失败：'+String(data.error||response.status).slice(0,200));
 return data;
}

async function recoverHelper(signal){
 let state=await bridgeRequest('POST',signal);
 const deadline=Date.now()+65000;
 while(state.status==='running'&&Date.now()<deadline){
  await new Promise((resolve,reject)=>{
   const timer=setTimeout(()=>{signal?.removeEventListener('abort',onAbort);resolve();},1500);
   function onAbort(){clearTimeout(timer);reject(Error('副屏恢复已取消；电脑端操作可能仍在执行'));}
   if(signal?.aborted)onAbort();else signal?.addEventListener('abort',onAbort,{once:true});
  });
  state=await bridgeRequest('GET',signal);
 }
 if(state.status==='complete')return;
 if(state.status==='running')throw Error('电脑端恢复超时；请稍后查询副屏状态');
 throw Error('电脑端恢复失败：'+String(state.error||'未知错误').slice(0,200));
}

export async function request(route,body,signal){
 let token;
 try{token=JSON.parse(fs.readFileSync(path.join(os.homedir(),'.dsh/virtual-display.json'),'utf8')).token;}catch{throw Error('Virtual display configuration missing. Install the phone plugin configuration first.');}
 if(typeof token!=='string'||!/^[-\w]{43}$/.test(token))throw Error('Invalid virtual display configuration');
 const local=()=>fetch('http://127.0.0.1:3096/'+route,{
  method:body===undefined?'GET':'POST',redirect:'error',
  headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},
  ...(body===undefined?{}:{body:JSON.stringify(body)}),
  signal:signal?AbortSignal.any([signal,AbortSignal.timeout(12000)]):AbortSignal.timeout(12000)
 });
 let response;
 try{response=await local();}
 catch(e){
  if(signal?.aborted)throw Error('Virtual display request cancelled; verify state before retrying');
  if(route!=='start'||!(e instanceof TypeError))throw Error('Phone virtual display helper is unavailable or timed out. Never fall back to main-screen actions.');
  await recoverHelper(signal);
  try{response=await local();}
  catch{throw Error('电脑端已尝试恢复，但手机副屏服务仍未响应；请检查无线调试和樱花隧道');}
 }
 if(!response.ok){const data=await response.json().catch(()=>({error:'invalid response'}));throw Error('Virtual display: '+String(data.error||response.status).slice(0,400));}
 return route==='screenshot'?{data:Buffer.from(await response.arrayBuffer()),width:Number(response.headers.get('X-Display-Width')),height:Number(response.headers.get('X-Display-Height')),revision:Number(response.headers.get('X-Display-Revision'))}:response.json();
}
