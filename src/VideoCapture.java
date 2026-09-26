package local.dsh.vdisplay;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.opengl.*;
import android.os.*;
import android.view.Surface;
import java.io.*;
import java.net.Socket;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** One GL texture feeds hardware AVC and on-demand JPEG, without display switching. */
final class VideoCapture {
 static final int FPS=30;
 final int W,H,rotation;
 final long revision;
 final HandlerThread thread=new HandlerThread("dsh-video-gl");
 final Handler gl;
 final CopyOnWriteArrayList<Subscriber> clients=new CopyOnWriteArrayList<>();
 EGLDisplay egl; EGLContext context; EGLConfig config; EGLSurface pbuffer,encoderWindow;
 SurfaceTexture texture; Surface input,codecSurface;
 int textureId,program; FloatBuffer vertices,coords;
 final float[] transform=new float[16];
 MediaCodec encoder; byte[] csd=new byte[0],formatPacket;
 boolean hasFrame,closed; volatile boolean encoding;
 volatile long sourceFrames,lastSourceMs,encodedFrames,encodedBytes;
 volatile String encoderName="",lastError="";
 long nextFrameMs;
 VideoCapture(int width,int height,int rotation,long revision)throws Exception{
  W=width;H=height;this.rotation=rotation;this.revision=revision;
  thread.start();gl=new Handler(thread.getLooper());
  try{call(()->{init();return null;});}catch(Exception e){close();throw e;}
 }
 <T>T call(Callable<T> task)throws Exception{
  if(Looper.myLooper()==thread.getLooper())return task.call();
  FutureTask<T> future=new FutureTask<>(task);if(!gl.post(future))throw new IOException("Capture thread stopped");
  try{return future.get(8,TimeUnit.SECONDS);}catch(ExecutionException e){throw new IOException("Capture: "+e.getCause().getMessage(),e.getCause());}
 }
 Surface surface(){return input;}
 static FloatBuffer floats(float[] a){FloatBuffer b=ByteBuffer.allocateDirect(a.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();b.put(a).position(0);return b;}
 void init()throws Exception{
  egl=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);int[] vers=new int[2];
  if(!EGL14.eglInitialize(egl,vers,0,vers,1))throw new IOException("EGL initialization failed");
  int[] attrs={EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT|EGL14.EGL_PBUFFER_BIT,0x3142,1,EGL14.EGL_NONE};
  EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];
  if(!EGL14.eglChooseConfig(egl,attrs,0,configs,0,1,count,0)||count[0]==0)throw new IOException("No recordable EGL config");config=configs[0];
  context=EGL14.eglCreateContext(egl,config,EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
  pbuffer=EGL14.eglCreatePbufferSurface(egl,config,new int[]{EGL14.EGL_WIDTH,W,EGL14.EGL_HEIGHT,H,EGL14.EGL_NONE},0);current(pbuffer);
  int[] ids=new int[1];GLES20.glGenTextures(1,ids,0);textureId=ids[0];GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId);
  GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
  GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
  program=GLES20.glCreateProgram();GLES20.glAttachShader(program,shader(GLES20.GL_VERTEX_SHADER,"attribute vec2 p;attribute vec2 t;uniform mat4 m;varying vec2 uv;void main(){gl_Position=vec4(p,0.,1.);uv=(m*vec4(t,0.,1.)).xy;}"));
  GLES20.glAttachShader(program,shader(GLES20.GL_FRAGMENT_SHADER,"#extension GL_OES_EGL_image_external : require\nprecision mediump float;uniform samplerExternalOES s;varying vec2 uv;void main(){gl_FragColor=texture2D(s,uv);}"));GLES20.glLinkProgram(program);
  int[] linked=new int[1];GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,linked,0);if(linked[0]==0)throw new IOException("GL program failed");
  vertices=floats(new float[]{-1,-1,1,-1,-1,1,1,1});coords=floats(new float[]{0,0,1,0,0,1,1,1});
  // Android's virtual-display Surface remains in natural orientation. Rotate
  // texture coordinates into the logical orientation used by input events.
  float[][] rotated={{0,0,1,0,0,1,1,1},{0,1,0,0,1,1,1,0},{1,1,0,1,1,0,0,0},{1,0,1,1,0,0,0,1}};
  coords=floats(rotated[rotation]);
  texture=new SurfaceTexture(textureId);texture.setDefaultBufferSize(rotation%2==0?W:H,rotation%2==0?H:W);
  texture.setOnFrameAvailableListener(st->{if(closed)return;try{current(pbuffer);st.updateTexImage();st.getTransformMatrix(transform);hasFrame=true;sourceFrames++;lastSourceMs=SystemClock.elapsedRealtime();}catch(Exception e){lastError=e.toString();}},gl);
  input=new Surface(texture);
 }
 int shader(int type,String src)throws Exception{int id=GLES20.glCreateShader(type);GLES20.glShaderSource(id,src);GLES20.glCompileShader(id);int[] ok=new int[1];GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,ok,0);if(ok[0]==0)throw new IOException(GLES20.glGetShaderInfoLog(id));return id;}
 void current(EGLSurface surface)throws Exception{if(!EGL14.eglMakeCurrent(egl,surface,surface,context))throw new IOException("EGL make-current failed");}
 void draw(EGLSurface target)throws Exception{
  current(target);GLES20.glViewport(0,0,W,H);GLES20.glUseProgram(program);GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId);
  int p=GLES20.glGetAttribLocation(program,"p"),t=GLES20.glGetAttribLocation(program,"t");
  GLES20.glEnableVertexAttribArray(p);GLES20.glEnableVertexAttribArray(t);vertices.position(0);coords.position(0);GLES20.glVertexAttribPointer(p,2,GLES20.GL_FLOAT,false,0,vertices);GLES20.glVertexAttribPointer(t,2,GLES20.GL_FLOAT,false,0,coords);
  GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"m"),1,false,transform,0);GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"s"),0);GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
 }
 byte[] jpeg()throws Exception{return call(()->{
  if(!hasFrame||closed)throw new IOException("No virtual display frame yet");draw(pbuffer);
  ByteBuffer pixels=ByteBuffer.allocateDirect(W*H*4);GLES20.glReadPixels(0,0,W,H,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels);
  byte[] raw=new byte[W*H*4];pixels.position(0);pixels.get(raw);byte[] line=new byte[W*4];
  for(int y=0;y<H/2;y++){int a=y*W*4,b=(H-1-y)*W*4;System.arraycopy(raw,a,line,0,line.length);System.arraycopy(raw,b,raw,a,line.length);System.arraycopy(line,0,raw,b,line.length);}
  Bitmap bitmap=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888);try{bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(raw));ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,85,out);return out.toByteArray();}finally{bitmap.recycle();}
 });}
 Subscriber subscribe(Socket socket)throws Exception{
  if(clients.size()>=2)throw new IOException("At most two video viewers are supported");
  Subscriber client=new Subscriber(this,socket);clients.add(client);
  try{call(()->{if(closed)throw new IOException("Display closed");if(encoder==null)startEncoder();if(formatPacket!=null)client.offer(formatPacket);Bundle b=new Bundle();b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0);encoder.setParameters(b);return null;});return client;}catch(Exception e){client.close();throw e;}
 }
 void startEncoder()throws Exception{
  String name=null;for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos())if(info.isEncoder()&&info.isHardwareAccelerated())for(String type:info.getSupportedTypes())if(type.equalsIgnoreCase("video/avc")){name=info.getName();break;}
  if(name==null)throw new IOException("Hardware H.264 encoder unavailable");
  try{
   encoder=MediaCodec.createByCodecName(name);encoderName=name;
   MediaFormat f=MediaFormat.createVideoFormat("video/avc",W,H);f.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);f.setInteger(MediaFormat.KEY_BIT_RATE,2500000);f.setInteger(MediaFormat.KEY_FRAME_RATE,FPS);f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);f.setInteger(MediaFormat.KEY_MAX_B_FRAMES,0);f.setInteger(MediaFormat.KEY_PROFILE,MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
   encoder.configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codecSurface=encoder.createInputSurface();encoderWindow=EGL14.eglCreateWindowSurface(egl,config,codecSurface,new int[]{EGL14.EGL_NONE},0);encoder.start();encoding=true;nextFrameMs=SystemClock.uptimeMillis();gl.post(tick);
  }catch(Exception e){stopEncoder();lastError=e.toString();throw e;}
 }
 final Runnable tick=new Runnable(){public void run(){
  if(closed||encoder==null)return;if(clients.isEmpty()){stopEncoder();return;}
  try{drain();if(hasFrame){draw(encoderWindow);EGLExt.eglPresentationTimeANDROID(egl,encoderWindow,System.nanoTime());if(!EGL14.eglSwapBuffers(egl,encoderWindow))throw new IOException("Encoder surface swap failed");}drain();}
  catch(Exception e){lastError=e.toString();for(Subscriber c:clients)c.close();stopEncoder();return;}
  nextFrameMs+=1000/FPS;if(nextFrameMs<SystemClock.uptimeMillis())nextFrameMs=SystemClock.uptimeMillis();gl.postAtTime(this,nextFrameMs);
 }};
 static byte[] bytes(ByteBuffer b){if(b==null)return new byte[0];ByteBuffer copy=b.duplicate();byte[] out=new byte[copy.remaining()];copy.get(out);return out;}
 static byte[] join(byte[] a,byte[] b){byte[] out=Arrays.copyOf(a,a.length+b.length);System.arraycopy(b,0,out,a.length,b.length);return out;}
 static byte[] packet(int type,long pts,byte[] data){ByteBuffer b=ByteBuffer.allocate(13+data.length).order(ByteOrder.BIG_ENDIAN);b.putInt(data.length).put((byte)type).putLong(pts).put(data);return b.array();}
 String codecString(byte[] data){for(int i=0;i+7<data.length;i++){int n=0;if(data[i]==0&&data[i+1]==0&&data[i+2]==1)n=i+3;else if(data[i]==0&&data[i+1]==0&&data[i+2]==0&&data[i+3]==1)n=i+4;if(n>0&&(data[n]&31)==7)return String.format(Locale.ROOT,"avc1.%02X%02X%02X",data[n+1]&255,data[n+2]&255,data[n+3]&255);}return "avc1.42E01F";}
 void drain()throws Exception{
  MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();for(int j=0;j<20;j++){
   int index=encoder.dequeueOutputBuffer(info,0);
   if(index==MediaCodec.INFO_TRY_AGAIN_LATER)return;
   if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat f=encoder.getOutputFormat();csd=join(bytes(f.getByteBuffer("csd-0")),bytes(f.getByteBuffer("csd-1")));formatPacket=packet(2,0,new JSONObject().put("codec",codecString(csd)).put("width",W).put("height",H).put("fps",FPS).put("revision",revision).toString().getBytes("UTF-8"));for(Subscriber c:clients)c.offer(formatPacket);continue;}
   if(index<0)continue;
   try{if(info.size>0&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){ByteBuffer buffer=encoder.getOutputBuffer(index);buffer.position(info.offset);buffer.limit(info.offset+info.size);byte[] data=bytes(buffer);boolean key=(info.flags&MediaCodec.BUFFER_FLAG_KEY_FRAME)!=0;if(key)data=join(csd,data);byte[] packet=packet(key?1:0,info.presentationTimeUs,data);encodedFrames++;encodedBytes+=data.length;for(Subscriber c:clients){if(c.waitKey&&!key)continue;c.waitKey=false;c.offer(packet);}}}finally{encoder.releaseOutputBuffer(index,false);}
  }
 }
 void stopEncoder(){
  gl.removeCallbacks(tick);encoding=false;
  try{if(egl!=null&&pbuffer!=null)current(pbuffer);}catch(Exception ignored){}
  if(encoderWindow!=null){EGL14.eglDestroySurface(egl,encoderWindow);encoderWindow=null;}
  if(encoder!=null){try{encoder.stop();}catch(Exception ignored){}try{encoder.release();}catch(Exception ignored){}encoder=null;}
  if(codecSurface!=null){codecSurface.release();codecSurface=null;}csd=new byte[0];formatPacket=null;
 }
 void close(){for(Subscriber c:clients)c.close();try{call(()->{closed=true;stopEncoder();if(input!=null)input.release();if(texture!=null)texture.release();if(egl!=null){EGL14.eglMakeCurrent(egl,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);if(pbuffer!=null)EGL14.eglDestroySurface(egl,pbuffer);if(context!=null)EGL14.eglDestroyContext(egl,context);EGL14.eglTerminate(egl);}return null;});}catch(Exception ignored){}thread.quitSafely();}
 JSONObject status()throws Exception{return new JSONObject().put("encoding",encoding).put("codec",encoderName).put("targetFps",FPS).put("viewers",clients.size()).put("encodedFrames",encodedFrames).put("encodedBytes",encodedBytes).put("sourceFrames",sourceFrames).put("lastError",lastError);}
 static final class Subscriber implements AutoCloseable{
  final VideoCapture parent; final Socket socket;final ArrayBlockingQueue<byte[]> queue=new ArrayBlockingQueue<>(12);final AtomicBoolean ended=new AtomicBoolean();boolean waitKey=true;
  Subscriber(VideoCapture p,Socket s){parent=p;socket=s;}
  void offer(byte[] p){if(!ended.get()&&!queue.offer(p))close();}
  public void close(){if(!ended.compareAndSet(false,true))return;parent.clients.remove(this);try{socket.close();}catch(Exception ignored){}parent.gl.post(()->{if(parent.clients.isEmpty())parent.stopEncoder();});}
 }
}
