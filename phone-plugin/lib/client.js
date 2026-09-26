import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

// Runtime transport is fixed to this phone. No remote recovery or automatic retry.
export async function request(route,body,signal){
 if(!['status','start','stop','orientation','screenshot','preview','tap','swipe','type','key'].includes(route))throw Error('Unknown virtual display endpoint');
 let token;
 const home=process.env.DSH_HOME||path.join(os.homedir(),'.dsh');
 try{token=JSON.parse(fs.readFileSync(path.join(home,'virtual-display.json'),'utf8')).token;}
 catch{throw Error('副屏配置不存在或无法读取。请先按 README 在 DSHA 终端运行 configure.mjs。');}
 if(typeof token!=='string'||!/^[-\w]{43}$/.test(token))throw Error('副屏配置无效，请检查 virtual-display.json；不要显示凭据。');
 if(signal?.aborted)throw Error('副屏请求已取消；重新操作前先确认当前状态。');
 let response;
 try{
  response=await fetch('http://127.0.0.1:3096/'+route,{
   method:body===undefined?'GET':'POST',redirect:'error',
   headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},
   ...(body===undefined?{}:{body:JSON.stringify(body)}),
   signal:signal?AbortSignal.any([signal,AbortSignal.timeout(12000)]):AbortSignal.timeout(12000)
  });
 }catch{
  if(signal?.aborted)throw Error('副屏请求已取消；重新操作前先确认当前状态。');
  throw Error('手机本地副屏 Helper 未响应或请求超时。请用户先用 Shizuku 授权终端、手机本地 ADB 或 root shell 按 README 启动 /data/local/tmp/dsh-phone-vdisplay/start.sh，再查询状态。已发出的操作可能已执行，不要自动重试或改用主屏。');
 }
 if(!response.ok){
  if(response.status===401)throw Error('副屏凭据不匹配。请按 README 配对本机配置，不要重启或覆盖已有服务。');
  const data=await response.json().catch(()=>({error:'invalid response'}));
  throw Error('Virtual display: '+String(data.error||response.status).slice(0,400));
 }
 return route==='screenshot'?{data:Buffer.from(await response.arrayBuffer()),width:Number(response.headers.get('X-Display-Width')),height:Number(response.headers.get('X-Display-Height')),revision:Number(response.headers.get('X-Display-Revision'))}:response.json();
}
