package local.dsh.vdisplay;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Phone-local, token-authenticated, virtual-display-only controller. No shell endpoint. */
public final class PhoneDisplay {
 static final int WIDTH=720,HEIGHT=1280,DPI=240,PORT=3096;
 static final Object FRAME_LOCK=new Object();
 static final Object STATE_LOCK=new Object();
 static VideoCapture video;
 static int width=WIDTH,height=HEIGHT,rotation=0;
 static long revision=0;
 static String orientation="auto",displayError="";
 static VirtualDisplay display;
 static ImageReader reader;
 static Handler handler;
 static byte[] frame;
 static long frames,frameTime;
 static volatile long lastAction=SystemClock.elapsedRealtime();
 static byte[] auth;
 static String dataDir;
 static boolean manualPaused;
 static String manualOwner="";
 static final Map<String,Long> tickets=new ConcurrentHashMap<>(), viewers=new ConcurrentHashMap<>();
 static final java.security.SecureRandom random=new java.security.SecureRandom();
 static String app="";
 static final String WRAPPERS="com.genymobile.scrcpy.wrappers.";

 public static void main(String[] args) throws Exception {
  if(args.length!=1)throw new IllegalArgumentException("Expected private token file path");
  String token=new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8).trim();
  if(!token.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException("Invalid token");
  auth=("Bearer "+token).getBytes(StandardCharsets.UTF_8);
  dataDir=Paths.get(args[0]).getParent().toString();
  manualPaused=Files.exists(Paths.get(dataDir,"manual-paused"));
  Looper.prepareMainLooper();
  Class.forName("com.genymobile.scrcpy.Workarounds").getMethod("apply").invoke(null);
  HandlerThread capture=new HandlerThread("dsh-vdisplay-capture");capture.start();handler=new Handler(capture.getLooper());
  Runtime.getRuntime().addShutdownHook(new Thread(()->{try{stop();}catch(Exception ignored){}}));
  Thread server=new Thread(()->{try{serve();}catch(Exception e){System.err.println("Virtual display server stopped: "+e.getClass().getSimpleName());System.exit(1);}},"dsh-vdisplay-http");
  handler.postDelayed(new Runnable(){public void run(){synchronized(STATE_LOCK){try{syncDisplay();}catch(Exception e){displayError=e.getClass().getSimpleName()+": "+e.getMessage();}}handler.postDelayed(this,250);}},250);
  server.start();System.out.println("DSH_VDISPLAY_READY 127.0.0.1:"+PORT);Looper.loop();
 }
 static Object service(String name)throws Exception{return Class.forName(WRAPPERS+"ServiceManager").getMethod("get"+name).invoke(null);}
 static JSONObject status()throws Exception{syncDisplay();return new JSONObject().put("ok",true).put("version","0.5.0").put("manualPaused",manualPaused).put("active",display!=null).put("displayId",display==null?-1:display.getDisplay().getDisplayId()).put("width",width).put("height",height).put("rotation",rotation).put("revision",revision).put("orientation",orientation).put("displayError",displayError).put("app",app).put("frameCount",video==null?0:video.sourceFrames).put("frameAgeMs",video==null?-1:SystemClock.elapsedRealtime()-video.lastSourceMs).put("phoneLocal",true).put("idleTimeoutSeconds",900).put("serverTimeUs",System.nanoTime()/1000).put("video",video==null?JSONObject.NULL:video.status());}
 static String secret(){byte[] bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
 static void setManual(boolean paused,String owner)throws Exception{
  if(paused)Files.write(Paths.get(dataDir,"manual-paused"),new byte[]{1});else Files.deleteIfExists(Paths.get(dataDir,"manual-paused"));
  manualPaused=paused;manualOwner=paused?owner:"";
 }
 static JSONObject viewerStatus(String viewer)throws Exception{return status().put("youControl",manualPaused&&manualOwner.equals(viewer));}
 static void openOnMain(Intent intent)throws Exception{
  intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
  Object am=service("ActivityManager");
  int result=(Integer)am.getClass().getMethod("startActivity",Intent.class,Bundle.class).invoke(am,intent,ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());
  if(result<0)throw new IllegalStateException("Cannot open preview/DSHA: "+result);
 }
 static void returnToDsha()throws Exception{
  Context ctx=(Context)Class.forName("com.genymobile.scrcpy.FakeContext").getMethod("get").invoke(null);
  Intent intent=ctx.getPackageManager().getLaunchIntentForPackage("com.dsh.client");
  if(intent==null)throw new IllegalStateException("DSHA is not installed");openOnMain(intent);
 }
 static JSONObject preview(boolean open)throws Exception{
  long now=SystemClock.elapsedRealtime();tickets.entrySet().removeIf(e->e.getValue()<now);
  if(tickets.size()>=16)tickets.clear();String ticket=secret();tickets.put(ticket,now+300000);
  String url="http://127.0.0.1:"+PORT+"/viewer#"+ticket;
  JSONObject result=new JSONObject().put("ok",true).put("url",url).put("expiresSeconds",300).put("opened",false);
  if(open)try{openOnMain(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));result.put("opened",true);}catch(Exception e){result.put("note","Could not open browser automatically; open the link on this phone.");}
  return result;
 }
 static void check()throws Exception{if(display==null||display.getDisplay().getDisplayId()<=0||!display.getDisplay().isValid())throw new IllegalStateException("Virtual display is not active; main screen will not be used");lastAction=SystemClock.elapsedRealtime();}
 static void start()throws Exception{
  if(display!=null){check();return;}
  width=WIDTH;height=HEIGHT;rotation=0;orientation="auto";displayError="";revision++;
  video=new VideoCapture(width,height,rotation,revision);
  // Compatible independent-display flags (0x1cb). Fallback gracefully from Android 14+ trusted flags on older Android (e.g. Android 12).
  int flags=1|2|8|(1<<6)|(1<<7)|(1<<8);
  Object dm=service("DisplayManager");
  java.lang.reflect.Method createMethod=dm.getClass().getMethod("createNewVirtualDisplay",String.class,int.class,int.class,int.class,Surface.class,int.class);
  try{
   if(android.os.Build.VERSION.SDK_INT>=34){
    try{display=(VirtualDisplay)createMethod.invoke(dm,"DSHA-Phone-Virtual",WIDTH,HEIGHT,DPI,video.surface(),flags|(1<<10)|(1<<11)|(1<<12)|(1<<13)|(1<<14)|(1<<15)|(1<<16));}catch(Throwable ignored){}
   }
   if(display==null)display=(VirtualDisplay)createMethod.invoke(dm,"DSHA-Phone-Virtual",WIDTH,HEIGHT,DPI,video.surface(),flags);
   check();Object wm=service("WindowManager");wm.getClass().getMethod("setDisplayImePolicy",int.class,int.class).invoke(wm,display.getDisplay().getDisplayId(),0);
  }catch(Exception e){stop();throw e;}
 }
 static void syncDisplay()throws Exception{
  if(display==null)return;
  Object dm=service("DisplayManager");
  Object info=dm.getClass().getMethod("getDisplayInfo",int.class).invoke(dm,display.getDisplay().getDisplayId());
  if(info==null)throw new IOException("Virtual display disappeared");
  Object size=info.getClass().getMethod("getSize").invoke(info);
  int w=(Integer)size.getClass().getMethod("getWidth").invoke(size),h=(Integer)size.getClass().getMethod("getHeight").invoke(size);
  int r=(Integer)info.getClass().getMethod("getRotation").invoke(info);
  if(w==width&&h==height&&r==rotation)return;
  if(w<1||h<1||w>1920||h>1920||r<0||r>3)throw new IOException("Unsupported virtual display dimensions");
  VideoCapture next=new VideoCapture(w,h,r,revision+1),old=video;
  try{display.setSurface(next.surface());}catch(Exception e){next.close();throw e;}
  video=next;width=w;height=h;rotation=r;revision++;displayError="";
  if(old!=null)old.close();
 }
 static void orient(JSONObject p)throws Exception{
  check();String mode=p.getString("mode");
  if(!Arrays.asList("auto","portrait","landscape").contains(mode))throw new IllegalArgumentException("Invalid orientation mode");
  Object wm=service("WindowManager");int id=display.getDisplay().getDisplayId();
  if(mode.equals("auto"))autoOrient(app);
  else wm.getClass().getMethod("freezeRotation",int.class,int.class).invoke(wm,id,mode.equals("landscape")?1:0);
  orientation=mode;SystemClock.sleep(300);syncDisplay();
 }
 static void autoOrient(String pkg)throws Exception{
  // Some OEMs letterbox a fixed-landscape app on an independent display instead
  // of honoring its request. Seed this display from the launch activity only.
  // All rotation changes remain scoped to our positive virtual-display ID.
  check();Object wm=service("WindowManager");int id=display.getDisplay().getDisplayId();
  Context ctx=(Context)Class.forName("com.genymobile.scrcpy.FakeContext").getMethod("get").invoke(null);
  Intent intent=pkg.isEmpty()?null:ctx.getPackageManager().getLaunchIntentForPackage(pkg);
  int requested=-1;
  if(intent!=null&&intent.getComponent()!=null)requested=ctx.getPackageManager().getActivityInfo(intent.getComponent(),0).screenOrientation;
  int target=0;
  if(requested==0||requested==6||requested==11)target=1;
  else if(requested==8)target=3;
  else if(requested==9)target=2;
  wm.getClass().getMethod("freezeRotation",int.class,int.class).invoke(wm,id,target);
  // Release the lock so applications which do honor orientation can still change it.
  wm.getClass().getMethod("thawRotation",int.class).invoke(wm,id);
 }
 static void stop(){
  if(display!=null){display.release();display=null;}
  if(video!=null){video.close();video=null;}
  if(reader!=null){reader.setOnImageAvailableListener(null,null);reader.close();reader=null;}
  synchronized(FRAME_LOCK){frame=null;frameTime=0;frames=0;}app="";
 }
 static void launch(String pkg)throws Exception{
  if(!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")||pkg.length()>180)throw new IllegalArgumentException("Invalid package name");
  if(pkg.equals("com.dsh.client"))throw new IllegalArgumentException("Keep DSHA on the physical screen");
  Context ctx=(Context)Class.forName("com.genymobile.scrcpy.FakeContext").getMethod("get").invoke(null);
  Intent intent=ctx.getPackageManager().getLaunchIntentForPackage(pkg);
  if(intent==null)throw new IllegalArgumentException("No launchable activity for package");
  start();if(orientation.equals("auto"))autoOrient(pkg);intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
  ActivityOptions options=ActivityOptions.makeBasic().setLaunchDisplayId(display.getDisplay().getDisplayId());
  Object am=service("ActivityManager");int result=(Integer)am.getClass().getMethod("startActivity",Intent.class,Bundle.class).invoke(am,intent,options.toBundle());
  if(result<0)throw new IllegalStateException("Application launch rejected: "+result);
  app=pkg;SystemClock.sleep(700);
 }
 static void inject(InputEvent event)throws Exception{
  check();int id=display.getDisplay().getDisplayId();
  Class<?> cls=Class.forName(WRAPPERS+"InputManager");
  if(!(Boolean)cls.getMethod("setDisplayId",InputEvent.class,int.class).invoke(null,event,id))throw new IllegalStateException("Cannot target virtual display");
  Object im=service("InputManager");if(!(Boolean)cls.getMethod("injectInputEvent",InputEvent.class,int.class).invoke(im,event,2))throw new IllegalStateException("Virtual display input rejected");
 }
 static void key(int code,int meta)throws Exception{
  if(!Arrays.asList(4,66,67,61,29,50,21,22,19,20,111).contains(code))throw new IllegalArgumentException("Key not allowed");
  long now=SystemClock.uptimeMillis();
  inject(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,code,0,meta,KeyCharacterDevice.VIRTUAL,0,0,InputDevice.SOURCE_KEYBOARD));
  inject(new KeyEvent(now,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,code,0,meta,KeyCharacterDevice.VIRTUAL,0,0,InputDevice.SOURCE_KEYBOARD));
 }
 static final class KeyCharacterDevice{static final int VIRTUAL=-1;}
 static int coordinate(JSONObject p,String key,int max)throws Exception{int v=p.getInt(key);if(v<0||v>=max)throw new IllegalArgumentException("Coordinate outside virtual screen");return v;}
 static void touch(JSONObject p,boolean swipe)throws Exception{
  check();syncDisplay();
  if(p.has("revision")&&p.getLong("revision")!=revision)throw new IllegalStateException("DISPLAY_CHANGED: refresh screenshot before touching");
  int x=coordinate(p,"x",width),y=coordinate(p,"y",height),x2=swipe?coordinate(p,"x2",width):x,y2=swipe?coordinate(p,"y2",height):y;
  int duration=p.optInt("duration_ms",swipe?350:60);if(duration<40||duration>2000)throw new IllegalArgumentException("Invalid duration");
  long down=SystemClock.uptimeMillis();motion(down,0,x,y);
  try{int steps=swipe?Math.max(2,duration/25):1;for(int i=1;i<=steps;i++){SystemClock.sleep(duration/steps);if(swipe)motion(down,2,x+(x2-x)*i/steps,y+(y2-y)*i/steps);}}
  finally{motion(down,1,x2,y2);}SystemClock.sleep(180);
 }
 static void motion(long down,int action,int x,int y)throws Exception{MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);e.setSource(InputDevice.SOURCE_TOUCHSCREEN);try{inject(e);}finally{e.recycle();}}
 static void type(JSONObject p)throws Exception{
  check();String text=p.getString("text");if(text.length()>2000)throw new IllegalArgumentException("Text too long");
  if(p.optBoolean("clear",false))key(KeyEvent.KEYCODE_A,KeyEvent.META_CTRL_ON);
  Object clipboard=service("ClipboardManager");if(clipboard==null)throw new IllegalStateException("Clipboard unavailable");
  clipboard.getClass().getMethod("setText",CharSequence.class).invoke(clipboard,text);
  key(KeyEvent.KEYCODE_V,KeyEvent.META_CTRL_ON);SystemClock.sleep(250);
 }
 static byte[] screenshot()throws Exception{
  check();syncDisplay();return video.jpeg();
 }
 static void serve()throws Exception{
  ServerSocket server=new ServerSocket();server.bind(new InetSocketAddress("127.0.0.1",PORT),8);server.setSoTimeout(1000);
  ExecutorService requests=new ThreadPoolExecutor(0,8,30,TimeUnit.SECONDS,new SynchronousQueue<Runnable>());
  while(true){
   try{Socket socket=server.accept();socket.setSoTimeout(5000);socket.setTcpNoDelay(true);socket.setSendBufferSize(65536);
    try{requests.execute(()->{try(Socket s=socket){handle(s);}catch(Exception e){System.err.println("Request failed: "+e.getClass().getSimpleName());}});}catch(RejectedExecutionException e){socket.close();}
   }catch(SocketTimeoutException ignored){}catch(Exception e){System.err.println("Request failed: "+e.getClass().getSimpleName());}
   synchronized(STATE_LOCK){if(display!=null&&SystemClock.elapsedRealtime()-lastAction>900000)stop();}
  }
 }
 static String line(InputStream in)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();for(int c;(c=in.read())!=-1;){if(c==10)break;if(c!=13)b.write(c);if(b.size()>4096)throw new IOException("Header too long");}return b.toString("UTF-8");}
 static void handle(Socket socket)throws Exception{
  InputStream in=socket.getInputStream();String[] first=line(in).split(" ");if(first.length<2)return;
  String authorization="",origin="",host="";int length=0,headers=0;
  for(String l;!(l=line(in)).isEmpty();){if(++headers>40)throw new IOException("Too many headers");int colon=l.indexOf(':');if(colon<0)continue;String k=l.substring(0,colon).trim().toLowerCase(Locale.ROOT),v=l.substring(colon+1).trim();if(k.equals("authorization"))authorization=v;if(k.equals("content-length"))length=Integer.parseInt(v);if(k.equals("origin"))origin=v;if(k.equals("host"))host=v;}
  if(!host.matches("127\\.0\\.0\\.1:[0-9]{1,5}")||(!origin.isEmpty()&&!origin.equals("http://"+host))||length<0||length>16000){sendJson(socket,400,new JSONObject().put("error","invalid_request"));return;}
  String route=first[1];boolean get=first[0].equals("GET"),post=first[0].equals("POST");
  if(get&&(route.equals("/viewer")||route.equals("/viewer.js")||route.equals("/video.js")||route.equals("/viewer.css"))){String file=route.equals("/viewer")?"viewer.html":route.substring(1);String type=route.endsWith(".js")?"text/javascript; charset=utf-8":route.endsWith(".css")?"text/css; charset=utf-8":"text/html; charset=utf-8";send(socket,200,type,Files.readAllBytes(Paths.get(dataDir,file)));return;}
  boolean robot=MessageDigest.isEqual(auth,authorization.getBytes(StandardCharsets.UTF_8));
  String viewer=authorization.startsWith("Bearer ")?authorization.substring(7):"";
  long now=SystemClock.elapsedRealtime();viewers.entrySet().removeIf(e->e.getValue()<now);
  boolean human=viewers.containsKey(viewer);
  if(!(post&&route.equals("/ui/session"))&&!robot&&!human){sendJson(socket,401,new JSONObject().put("error","unauthorized"));return;}
  byte[] body=new byte[length];int pos=0;while(pos<length){int n=in.read(body,pos,length-pos);if(n<0)throw new EOFException();pos+=n;}
  if(get&&route.equals("/ui/video")){
   if(!human){sendJson(socket,403,new JSONObject().put("error","Viewer session required"));return;}
   VideoCapture.Subscriber subscription;
   try{synchronized(STATE_LOCK){check();syncDisplay();subscription=video.subscribe(socket);}}catch(Exception e){sendJson(socket,409,new JSONObject().put("error",e.getMessage()));return;}
   try(VideoCapture.Subscriber sub=subscription){OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.flush();
    while(!sub.ended.get()&&viewers.getOrDefault(viewer,0L)>SystemClock.elapsedRealtime()){
     byte[] packet=sub.queue.poll(3,TimeUnit.SECONDS);if(packet==null)break;out.write(packet);out.flush();lastAction=SystemClock.elapsedRealtime();
    }
   }return;
  }
  synchronized(STATE_LOCK){
  try{
   JSONObject p=length==0?new JSONObject():new JSONObject(new String(body,StandardCharsets.UTF_8));
   if(post&&route.equals("/ui/session")){
    Long expires=tickets.remove(p.optString("ticket"));if(expires==null||expires<now){sendJson(socket,401,new JSONObject().put("error","链接已使用或过期，请让 DSHA 重新打开预览。"));return;}
    if(viewers.size()>=16){sendJson(socket,409,new JSONObject().put("error","预览会话过多，请稍后重试。"));return;}
    String credential=secret();viewers.put(credential,now+12*3600000L);sendJson(socket,200,new JSONObject().put("token",credential));return;
   }
   if(route.startsWith("/ui/")){
    if(!human){sendJson(socket,403,new JSONObject().put("error","Viewer session required"));return;}
    viewers.put(viewer,now+12*3600000L);
    if(get&&route.equals("/ui/status")){sendJson(socket,200,viewerStatus(viewer));return;}
    if(get&&route.equals("/ui/frame")){send(socket,200,"image/jpeg",screenshot());return;}
    if(!post)throw new IllegalArgumentException("Unknown preview endpoint");
    if(route.equals("/ui/take")){setManual(true,viewer);sendJson(socket,200,viewerStatus(viewer));return;}
    if(route.equals("/ui/return")){returnToDsha();sendJson(socket,200,viewerStatus(viewer));return;}
    if(!manualPaused||!manualOwner.equals(viewer)){sendJson(socket,423,new JSONObject().put("error","请先点「手动接管」；其他预览页可能已接管。"));return;}
    switch(route){case "/ui/orientation":orient(p);break;case "/ui/release":setManual(false,"");break;case "/ui/tap":touch(p,false);break;case "/ui/swipe":touch(p,true);break;case "/ui/type":type(p);break;case "/ui/key":key(p.getInt("code"),0);break;case "/ui/stop":stop();break;default:throw new IllegalArgumentException("Unknown preview endpoint");}
    sendJson(socket,200,viewerStatus(viewer));return;
   }
   if(!robot){sendJson(socket,403,new JSONObject().put("error","Robot credential required"));return;}
   if(post&&route.equals("/preview")){sendJson(socket,200,preview(p.optBoolean("open",true)));return;}
   if(manualPaused&&!route.equals("/status")){sendJson(socket,423,new JSONObject().put("error","MANUAL_CONTROL: 用户正在手动接管副屏。停止副屏操作，不要重试或改用主屏。等待用户在预览页交还控制并要求继续。"));return;}
   if(first[0].equals("GET")&&route.equals("/screenshot")){send(socket,200,"image/jpeg",screenshot());return;}
   if(first[0].equals("GET")&&route.equals("/status")){sendJson(socket,200,status());return;}
   if(!first[0].equals("POST"))throw new IllegalArgumentException("Unknown endpoint");
   switch(route){case "/orientation":orient(p);break;case "/start":launch(p.getString("package"));break;case "/stop":stop();break;case "/tap":touch(p,false);break;case "/swipe":touch(p,true);break;case "/type":type(p);break;case "/key":key(p.getInt("code"),0);break;default:throw new IllegalArgumentException("Unknown endpoint");}
   sendJson(socket,200,status());
  }catch(Exception e){Throwable cause=e;while(cause.getCause()!=null)cause=cause.getCause();sendJson(socket,409,new JSONObject().put("error",cause.getClass().getSimpleName()+": "+String.valueOf(cause.getMessage())));}
  }
 }
 static void sendJson(Socket s,int code,JSONObject o)throws Exception{send(s,code,"application/json; charset=utf-8",o.toString().getBytes(StandardCharsets.UTF_8));}
 static void send(Socket s,int code,String type,byte[] bytes)throws Exception{OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 "+code+" Result\r\nContent-Type: "+type+"\r\nContent-Length: "+bytes.length+"\r\nX-Display-Width: "+width+"\r\nX-Display-Height: "+height+"\r\nX-Display-Revision: "+revision+"\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nX-Frame-Options: DENY\r\nContent-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(bytes);out.flush();}
}
