package local.dsh.vdisplay;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.*;
import org.json.JSONObject;

/** Local Android-shell health check. Never prints tokens or kills a process. */
public final class HelperControl {
 public static void main(String[] args){
  try{
   if(args.length!=2||!args[1].equals("status"))throw new IllegalArgumentException("Expected runtime directory and status");
   String token=new String(Files.readAllBytes(Paths.get(args[0],"token")),StandardCharsets.UTF_8).trim();
   if(!token.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException("Invalid token file");
   HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+PhoneDisplay.PORT+"/status").openConnection();
   connection.setConnectTimeout(1000);connection.setReadTimeout(3000);connection.setInstanceFollowRedirects(false);
   connection.setRequestProperty("Authorization","Bearer "+token);
   try{
    if(connection.getResponseCode()!=200)throw new IOException("Local status rejected");
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];
    try(InputStream in=connection.getInputStream()){int count;while((count=in.read(buffer))!=-1){bytes.write(buffer,0,count);if(bytes.size()>65536)throw new IOException("Status too large");}}
    JSONObject status=new JSONObject(bytes.toString("UTF-8"));
    if(!status.optBoolean("ok")||!status.optBoolean("phoneLocal"))throw new IOException("Unexpected local service");
    System.out.println(status.toString());
   }finally{connection.disconnect();}
   // app_process may retain framework/network threads after main returns.
   System.exit(0);
  }catch(ConnectException e){System.err.println("Phone-local Helper is not running.");System.exit(2);}
  catch(Exception e){System.err.println("Local status check failed: "+e.getClass().getSimpleName()+". Check configuration/service locally.");System.exit(1);}
 }
}
