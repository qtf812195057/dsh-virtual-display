'use strict';
class LivePreview {
 constructor(canvas,onFrame,onError){this.canvas=canvas;this.context=canvas.getContext('2d',{alpha:false,desynchronized:true});this.onFrame=onFrame;this.onError=onError;this.active=false;this.frames=0;this.fps=0;this.generation=0;}
 stop(){this.active=false;this.generation++;this.controller?.abort();if(this.decoder?.state!=='closed')this.decoder?.close();this.decoder=null;}
 start(token){if(this.active)return;this.stop();const generation=this.generation;this.active=true;this.frames=0;this.fps=0;this.started=performance.now();this.windowStart=this.started;this.windowFrames=0;this.controller=new AbortController();this.run(token,generation).catch(e=>{if(generation!==this.generation)return;this.stop();this.onError(e.message||'视频连接中断');});}
 async run(token,generation){
  if(!('VideoDecoder' in window))throw Error('当前浏览器不支持流畅预览，已切换为截图。');
  const response=await fetch('/ui/video',{headers:{Authorization:'Bearer '+token},signal:this.controller.signal,cache:'no-store'});
  if(!response.ok)throw Error((await response.json().catch(()=>({}))).error||'视频服务暂不可用');
  const reader=response.body.getReader();let pending=new Uint8Array(0),offset=0,configured=false;
  const read=async n=>{while(pending.length-offset<n){const {value,done}=await reader.read();if(done)throw Error('视频连接结束');const rest=pending.subarray(offset),merged=new Uint8Array(rest.length+value.length);merged.set(rest);merged.set(value,rest.length);pending=merged;offset=0;}const out=pending.slice(offset,offset+n);offset+=n;return out;};
  try{while(generation===this.generation){
   const header=await read(13),view=new DataView(header.buffer),length=view.getUint32(0),kind=view.getUint8(4),timestamp=Number(view.getBigUint64(5));
   if(length>2000000||!Number.isSafeInteger(timestamp))throw Error('无效的视频数据');
   const data=await read(length);if(generation!==this.generation)return;
   if(kind===2){
    const format=JSON.parse(new TextDecoder().decode(data));if(!Number.isInteger(format.width)||!Number.isInteger(format.height)||format.width<1||format.height<1||format.width>1920||format.height>1920||!/^avc1\.[\da-f]{6}$/i.test(format.codec))throw Error('视频格式不兼容');
    const config={codec:format.codec,codedWidth:format.width,codedHeight:format.height,optimizeForLatency:true,hardwareAcceleration:'prefer-hardware'};
    if(!(await VideoDecoder.isConfigSupported(config)).supported)throw Error('当前浏览器不支持此视频格式，已切换为截图。');
    if(generation!==this.generation)return;if(this.decoder?.state!=='closed')this.decoder?.close();
    this.decoder=new VideoDecoder({output:frame=>{try{if(generation!==this.generation)return;if(this.canvas.width!==format.width||this.canvas.height!==format.height){this.canvas.width=format.width;this.canvas.height=format.height;}this.context.drawImage(frame,0,0,format.width,format.height);this.frames++;this.windowFrames++;const now=performance.now();if(now-this.windowStart>=1000){this.fps=this.windowFrames*1000/(now-this.windowStart);this.windowFrames=0;this.windowStart=now;}this.onFrame({width:format.width,height:format.height,revision:format.revision,timestamp:frame.timestamp,fps:this.fps,frames:this.frames});}finally{frame.close();}},error:e=>{if(generation===this.generation){this.stop();this.onError('视频解码失败：'+e.message);}}});
    this.decoder.configure(config);configured=true;
   }else{if(!configured)continue;if(this.decoder.decodeQueueSize>5)throw Error('解码跟不上画面，已切换为截图，可重新开启流畅预览。');this.decoder.decode(new EncodedVideoChunk({type:kind===1?'key':'delta',timestamp,data}));}
  }}finally{await reader.cancel().catch(()=>{});reader.releaseLock();}
 }
}
