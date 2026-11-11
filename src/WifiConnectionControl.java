import android.os.IBinder;
import android.os.Process;
import android.system.Os;
import android.util.Base64;
import java.io.*;
import java.lang.reflect.*;
import java.nio.channels.FileLock;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** On-demand settings bridge and durable, narrowly scoped autojoin undo adapter. */
public final class WifiConnectionControl {
    static WifiConnectionConfig load(File dir)throws Exception {
        File file=new File(dir,"connection.json");
        if(!file.exists())return WifiConnectionConfig.defaults();
        JSONObject j=new JSONObject(read(file,8192));
        return new WifiConnectionConfig(j.getBoolean("avoid_enabled"),j.getInt("avoid_seconds"),
            j.getBoolean("probe_enabled"),j.getInt("probe_interval"),j.getString("probe_url"));
    }
    static JSONObject json(WifiConnectionConfig c)throws Exception {
        return new JSONObject().put("avoid_enabled",c.avoidEnabled).put("avoid_seconds",c.avoidSeconds)
            .put("probe_enabled",c.probeEnabled).put("probe_interval",c.probeInterval).put("probe_url",c.probeUrl);
    }
    private static String read(File file,int max)throws IOException {
        if(!file.isFile()||file.length()>max)throw new IOException("配置/还原文件无效或过大："+file.getName());
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(InputStream in=new FileInputStream(file)){byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1){bytes.write(b,0,n);if(bytes.size()>max)throw new IOException("文件过大");}}
        return bytes.toString("UTF-8");
    }
    private static void atomic(File target,String text)throws Exception {
        File temp=File.createTempFile(target.getName(),".tmp",target.getParentFile());
        try {
            Os.chmod(temp.getAbsolutePath(),0600);
            try(FileOutputStream out=new FileOutputStream(temp)){out.write(text.getBytes("UTF-8"));out.getFD().sync();}
            Os.rename(temp.getAbsolutePath(),target.getAbsolutePath());
        } finally {temp.delete();}
    }
    static final class Autojoin {
        private final File dir;
        private final Object wifi;
        private final Method list,set;
        private final Field allowed;
        Autojoin(File dir)throws Exception {
            this.dir=dir;
            Class<?> type=Class.forName("android.net.wifi.IWifiManager");
            IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"wifi");
            wifi=Class.forName(type.getName()+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
            if(wifi==null)throw new IOException("Wi-Fi 服务不可用。");
            set=type.getMethod("allowAutojoin",int.class,boolean.class);
            Method found=null;
            for(Class<?>[] signature:new Class<?>[][]{{String.class,String.class,boolean.class},{String.class,String.class},{String.class}})
                try{found=type.getMethod("getConfiguredNetworks",signature);break;}catch(NoSuchMethodException ignored){}
            if(found==null)throw new IOException("系统未提供兼容的已保存网络接口。");
            list=found;allowed=Class.forName("android.net.wifi.WifiConfiguration").getField("allowAutojoin");
        }
        interface Work<T>{T run(WifiSsidGuard guard)throws Exception;}
        synchronized <T>T locked(BooleanSupplier mayRestrict,Work<T> work)throws Exception {
            try(RandomAccessFile file=new RandomAccessFile(new File(dir,"autojoin.lock"),"rw");FileLock lock=file.getChannel().lock()) {
                Os.chmod(new File(dir,"autojoin.lock").getAbsolutePath(),0600);
                WifiSsidGuard guard=new WifiSsidGuard(new WifiSsidGuard.Port(){
                    public long now(){return System.currentTimeMillis();}
                    public List<WifiSsidGuard.Config> configs()throws Exception{return configured();}
                    public void autojoin(int id,boolean value)throws Exception {
                        if(!value&&!mayRestrict.getAsBoolean())throw new IOException("状态已变化，已取消暂避。");
                        set.invoke(wifi,id,value);
                    }
                    public List<WifiSsidGuard.Record> load()throws Exception {
                        File journal=new File(dir,"ssid_autojoin_restore.json");List<WifiSsidGuard.Record> records=new ArrayList<>();
                        if(!journal.exists())return records;
                        JSONArray a=new JSONArray(read(journal,262144));
                        for(int i=0;i<a.length();i++){JSONObject j=a.getJSONObject(i);records.add(new WifiSsidGuard.Record(
                            new WifiSsidGuard.Config(j.getInt("id"),j.getString("ssid"),j.getString("key"),true),j.getLong("until")));}
                        return records;
                    }
                    public void save(List<WifiSsidGuard.Record> records)throws Exception {
                        JSONArray a=new JSONArray();for(WifiSsidGuard.Record r:records)a.put(new JSONObject().put("id",r.original.id)
                            .put("ssid",r.original.ssid).put("key",r.original.key).put("until",r.until));
                        atomic(new File(dir,"ssid_autojoin_restore.json"),a.toString());
                    }
                });
                return work.run(guard);
            }
        }
        private List<WifiSsidGuard.Config> configured()throws Exception {
            Object[] args=new Object[list.getParameterCount()];args[0]="root";
            if(args.length>2)args[2]=false;
            Object result=list.invoke(wifi,args);
            if(result==null)throw new IOException("系统未返回已保存网络。");
            Object items=result instanceof List?result:result.getClass().getMethod("getList").invoke(result);
            List<WifiSsidGuard.Config> configs=new ArrayList<>();
            for(Object c:(List<?>)items) {
                Class<?> type=c.getClass();
                if(flag(c,"ephemeral")||flag(c,"fromWifiNetworkSuggestion")||flag(c,"fromWifiNetworkSpecifier"))continue;
                Object fqdn=type.getField("FQDN").get(c);if(fqdn!=null&&!fqdn.toString().isEmpty())continue;
                String ssid=(String)type.getField("SSID").get(c);int id=type.getField("networkId").getInt(c);
                if(id<0||ssid==null)continue;
                // No credentials are read or persisted. Include security type to guard networkId reuse.
                String key=ssid+":"+type.getField("allowedKeyManagement").get(c);
                configs.add(new WifiSsidGuard.Config(id,ssid,key,allowed.getBoolean(c)));
            }
            return configs;
        }
        private static boolean flag(Object c,String field)throws Exception {
            try{return c.getClass().getField(field).getBoolean(c);}catch(NoSuchFieldException e){return false;}
        }
    }
    public static void main(String[] args) {
        try {
            if(Process.myUid()!=0||args.length<2)throw new IllegalArgumentException("Root/state/action required");
            File dir=new File(args[0]);if(!dir.isDirectory())throw new IOException("模块配置目录不存在。");
            String action=args[1];
            if("configure".equals(action)&&args.length==3) {
                if(args[2].length()>12000||!args[2].matches("[A-Za-z0-9+/=]+"))throw new IOException("设置编码无效。");
                JSONObject j=new JSONObject(new String(Base64.decode(args[2],Base64.DEFAULT),"UTF-8"));
                WifiConnectionConfig c=new WifiConnectionConfig(j.getBoolean("avoid_enabled"),j.getInt("avoid_seconds"),
                    j.getBoolean("probe_enabled"),j.getInt("probe_interval"),j.getString("probe_url"));
                if(c.avoidEnabled)new Autojoin(dir); // Capability preflight, no mutation.
                atomic(new File(dir,"connection.json"),json(c).toString());
                if(!c.avoidEnabled)restoreIfNeeded(dir);
                System.out.println("设置已保存。通网检测每次新连接最多两次，成功即停止。");
            } else if("restore".equals(action)&&args.length==2) {
                restoreIfNeeded(dir);System.out.println("模块记录的自动连接设置已还原。");
            } else if("status".equals(action)&&args.length==2) {
                JSONObject j=json(load(dir));
                try{new Autojoin(dir);j.put("avoid_supported",true);}catch(Exception e){j.put("avoid_supported",false).put("error",message(e));}
                System.out.println(j);
            } else throw new IllegalArgumentException("Unknown action");
        } catch(Exception e){System.err.println(message(e));System.exit(1);}
    }
    static void restoreIfNeeded(File dir)throws Exception {
        File journal=new File(dir,"ssid_autojoin_restore.json");
        if(!journal.isFile()||"[]".equals(read(journal,262144).trim()))return;
        new Autojoin(dir).locked(()->false,g->{g.restore(true);return null;});
    }
    static String message(Throwable e){while(e.getCause()!=null)e=e.getCause();return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
}
