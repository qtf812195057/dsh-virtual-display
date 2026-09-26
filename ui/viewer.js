'use strict';
const $=id=>document.getElementById(id);
let credential=sessionStorage.getItem('vd-viewer')||'',state=null,busy=false,connected=false,imageURL='',gesture=null,lastFrame=0;
let chain=Promise.resolve();
let rendered=null,retryVideo=0;
function frameSize(w,h,revision){rendered={width:w,height:h,revision};document.documentElement.style.setProperty("--screen-ratio",String(w/h));$("frame-size").textContent=w+" × "+h;}
let preferLive=true,clockOffset=0,clockRTT=Infinity;
const live=new LivePreview($('video'),info=>{
 if(!state?.active||info.revision!==state.revision)return;retryVideo=0;if($('notice').textContent==='正在重新连接视频…')notice('');frameSize(info.width,info.height,info.revision);$('video').style.display='block';$('frame').style.visibility='hidden';$('empty').hidden=true;lastFrame=Date.now();
 const latency=(performance.now()*1000+clockOffset-info.timestamp)/1000;
 $('fresh').textContent='流畅预览 · '+Math.round(info.fps||30)+' 帧/秒'+(clockRTT<1000&&latency>=0&&latency<5000?' · 约 '+Math.round(latency)+' ms':'');
},message=>{lastFrame=0;gesture=null;if(++retryVideo<=3){notice('正在重新连接视频…');return;}preferLive=false;$('video').style.display='none';$('video-mode').textContent='开启流畅预览';notice(message);});
const serialized=fn=>{const next=chain.then(fn,fn);chain=next.catch(()=>{});return next;};
function notice(text){$('notice').textContent=text;}
async function api(route,body){
 const res=await fetch('/ui/'+route,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(credential?{Authorization:'Bearer '+credential}:{})},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(12000),cache:'no-store'});
 if(!res.ok){let msg=(await res.json().catch(()=>({}))).error||'连接失败';if(res.status===401){credential='';sessionStorage.removeItem('vd-viewer');msg='预览会话已失效，请回到 DSHA 说“打开副屏预览”。';}throw Error(msg);}
 return route==='frame'?{blob:await res.blob(),width:Number(res.headers.get('X-Display-Width')),height:Number(res.headers.get('X-Display-Height')),revision:Number(res.headers.get('X-Display-Revision'))}:res.json();
}
function render(){
 const owned=connected&&state?.youControl;
 document.body.classList.toggle('controlled',!!owned);
 $('mode').textContent=!connected?'未连接':owned?'你正在接管':state.manualPaused?'机器人已暂停':'仅预览 · 机器人可操作';
 $('hint').textContent=owned?'机器人已暂停副屏操作。离开页面不会自动交还。':state?.manualPaused?'另一预览页接管了副屏；点“手动接管”可转到本页。':'查看不会打断机器人。点“手动接管”后再触摸画面。';
 $('take').disabled=!connected||busy||owned;$('release').disabled=!owned||busy;$('refresh').disabled=!connected||busy||!state?.active;
 $('size').textContent=state?state.width+' × '+state.height:'等待连接';$('orientation').value=state?.orientation||'auto';
 for(const id of ['back','enter','stop','paste','text','clear','orientation'])$(id).disabled=!owned||!state?.active||busy;
 $('return').disabled=!connected||busy;
 if(!state?.active){$('frame').style.visibility='hidden';$('empty').hidden=false;$('empty').textContent='副屏尚未打开\n回到 DSHA，让它在副屏打开一个应用';$('fresh').textContent='暂无活动副屏';lastFrame=0;}
}
async function update(){
 const before=performance.now(),previous=state;state=await api('status');if(previous?.revision!==state.revision){live.stop();lastFrame=0;gesture=null;retryVideo=0;$('video').style.display='none';$('frame').style.visibility='hidden';}const after=performance.now();if(after-before<clockRTT){clockRTT=after-before;clockOffset=state.serverTimeUs-(before+after)*500;}connected=true;render();
 if(!state.active||document.hidden){live.stop();$('video').style.display='none';return;}
 if(preferLive){if(!live.active)live.start(credential);return;}
 if(state.active){const shot=await api('frame'),url=URL.createObjectURL(shot.blob);try{await new Promise((resolve,reject)=>{const img=new Image(),timer=setTimeout(()=>reject(Error('画面加载超时，请刷新')),5000);img.onload=()=>{clearTimeout(timer);resolve();};img.onerror=()=>{clearTimeout(timer);reject(Error('画面加载失败'));};img.src=url;});}catch(e){URL.revokeObjectURL(url);throw e;}frameSize(shot.width,shot.height,shot.revision);const old=imageURL;imageURL=url;$('frame').src=url;$('frame').style.visibility='visible';$('empty').hidden=true;lastFrame=Date.now();$('fresh').textContent='截图 · '+new Date().toLocaleTimeString();if(old)setTimeout(()=>URL.revokeObjectURL(old),1500);}
}
async function action(route,body={}){
 if(busy)return;busy=true;render();
 try{await serialized(async()=>{state=await api(route,body);connected=true;render();if(route!=='return')await update();});notice(route==='release'?'已交还控制。回到 DSHA 说“继续”，机器人再接着做。':'');}
 catch(e){notice(e.message);try{state=await serialized(()=>api('status'));connected=true;}catch{connected=false;}}
 finally{busy=false;render();}
}
$('orientation').onchange=()=>{live.stop();lastFrame=0;gesture=null;retryVideo=0;action('orientation',{mode:$('orientation').value});};
$('take').onclick=()=>action('take');$('release').onclick=()=>action('release');$('return').onclick=()=>action('return');
$('back').onclick=()=>action('key',{code:4});$('enter').onclick=()=>action('key',{code:66});
$('stop').onclick=()=>{if(confirm('关闭副屏会结束其中的页面，未保存内容可能丢失。确定关闭？'))action('stop');};
$('paste').onclick=()=>{if($('text').value)action('type',{text:$('text').value,clear:$('clear').checked});};
$('refresh').onclick=()=>{live.stop();serialized(update).catch(e=>notice(e.message));};
$('zoom').onclick=()=>{const large=$('screen-wrap').classList.toggle('large');$('zoom').textContent=large?'适应屏幕':'放大画面';};
$('video-mode').onclick=()=>{preferLive=!preferLive;retryVideo=0;live.stop();$('video').style.display='none';$('video-mode').textContent=preferLive?'切换为截图':'开启流畅预览';notice('');serialized(update).catch(e=>notice(e.message));};
document.addEventListener('visibilitychange',()=>{gesture=null;if(document.hidden)live.stop();else serialized(update).catch(e=>notice(e.message));});
window.addEventListener('pagehide',()=>live.stop());
function point(e){const r=$('screen').getBoundingClientRect();return {x:Math.max(0,Math.min(rendered.width-1,Math.floor((e.clientX-r.left)*rendered.width/r.width))),y:Math.max(0,Math.min(rendered.height-1,Math.floor((e.clientY-r.top)*rendered.height/r.height)))};}
$('screen').addEventListener('pointerdown',e=>{if(!state?.youControl||!rendered||rendered.revision!==state.revision||busy||Date.now()-lastFrame>5000)return;if(e.button!==0)return;e.preventDefault();gesture={id:e.pointerId,start:{...point(e),revision:rendered.revision},time:performance.now()};$('screen').setPointerCapture(e.pointerId);});
$('screen').addEventListener('pointerup',e=>{if(!gesture||gesture.id!==e.pointerId)return;const g=gesture;gesture=null;if(!state?.youControl||busy||g.start.revision!==state.revision||g.start.revision!==rendered?.revision)return;const end=point(e),distance=Math.hypot(end.x-g.start.x,end.y-g.start.y);if(distance<15)action('tap',g.start);else action('swipe',{...g.start,x2:end.x,y2:end.y,duration_ms:Math.max(100,Math.min(1500,Math.round(performance.now()-g.time)))});});
$('screen').addEventListener('pointercancel',()=>{gesture=null;});
$('screen').addEventListener('contextmenu',e=>e.preventDefault());
async function poll(){if(!document.hidden&&!busy&&credential){try{await serialized(update);}catch(e){connected=false;render();notice(e.message);$('fresh').textContent='画面已停止更新';}}setTimeout(poll,900);}
(async()=>{try{const ticket=location.hash.slice(1);if(ticket){history.replaceState(null,'','/viewer');const data=await api('session',{ticket});credential=data.token;sessionStorage.setItem('vd-viewer',credential);}if(!credential)throw Error('请回到 DSHA 说“打开副屏预览”，使用它提供的入口。');await serialized(update);}catch(e){notice(e.message);render();}poll();})();
