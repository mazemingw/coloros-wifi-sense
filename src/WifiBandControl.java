import android.content.Context;
import android.os.Build;
import android.os.Looper;
import android.os.PowerManager;
import android.system.Os;
import java.io.*;
import java.nio.channels.FileLock;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** One-shot CLI and on-demand daemon adapter. No timer, scan or resident service. */
public final class WifiBandControl {
    private final File module, state, binary;
    private final BooleanSupplier interactive;
    private WifiBandControl(File module,File state,BooleanSupplier interactive) throws IOException {
        this.module=module; this.state=state; this.interactive=interactive;
        File found=null;
        for(String abi:Build.SUPPORTED_ABIS) {
            File f=new File(module,"bin/"+abi+"/wifi-band");if(f.isFile()&&f.canExecute()){found=f;break;}
        }
        if(found==null)throw new IOException("当前架构没有可执行的频段组件，请重新安装模块。");
        binary=found;
    }
    public static void main(String[] args) {
        try {
            if(android.os.Process.myUid()!=0 || args.length!=3)throw new IOException("Root and module/state/action required");
            Looper.prepareMainLooper();
            Object thread=Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
            Context context=(Context)thread.getClass().getMethod("getSystemContext").invoke(thread);
            PowerManager power=context.getSystemService(PowerManager.class);
            System.out.println(execute(new File(args[0]),new File(args[1]),args[2],power::isInteractive));
            System.exit(0);
        } catch(Exception error) { System.err.println(error.getMessage());System.exit(1); }
    }
    static String execute(File module,File state,String action,BooleanSupplier awake) throws Exception {
        WifiBandControl io=new WifiBandControl(module,state,awake);
        if("status".equals(action))return io.status().toString();
        if(!"0".equals(action)&&!"1".equals(action)&&!"reconcile".equals(action)&&!"release".equals(action))
            throw new IOException("Invalid band action");
        try(RandomAccessFile lockFile=new RandomAccessFile(new File(state,"band.lock"),"rw");
            FileLock lock=lockFile.getChannel().tryLock()) {
            if(lock==null)throw new IOException("频段设置正忙，请稍后重试。");
            Os.chmod(new File(state,"band.lock").getPath(),0600);
            WifiBandPolicy policy=new WifiBandPolicy(io.port);
            if("1".equals(action))policy.enable();
            else if("0".equals(action))policy.disable(false);
            else if("release".equals(action))policy.disable(true);
            else policy.reconcile();
        }
        return "0".equals(action)?"已还原开启前的频段设置。":"1".equals(action)?"仅 5GHz 已生效。":"OK";
    }
    private JSONObject status() throws Exception {
        JSONObject j=new JSONObject().put("requested",port.enabled()).put("supported",false)
            .put("applied",false).put("mask",JSONObject.NULL).put("restore_pending",new File(state,"band_original").isFile());
        try {
            int mask=port.get(port.iface());j.put("supported",true).put("mask",mask).put("applied",mask==1);
            StringBuilder bands=new StringBuilder();
            if((mask&2)!=0)bands.append("2.4GHz / ");if((mask&1)!=0)bands.append("5GHz / ");if((mask&4)!=0)bands.append("6GHz / ");
            j.put("message",mask==1?"当前仅允许 5GHz。":mask==0?"当前频段由系统自动选择。":"当前允许 "+bands.substring(0,bands.length()-3)+"。");
        } catch(IOException error) {
            j.put("message","暂不可用：Wi-Fi 未启用，或驱动不支持高通实时频段接口。");
        }
        return j;
    }
    private int nativeCall(String action,String iface,Integer mask) throws IOException {
        if(!"wlan0".equals(iface)&&!"wlan1".equals(iface))throw new IOException("Invalid interface");
        ProcessBuilder builder=mask==null?new ProcessBuilder(binary.getPath(),action,iface)
            :new ProcessBuilder(binary.getPath(),action,iface,String.valueOf(mask));
        builder.redirectErrorStream(true);java.lang.Process p=builder.start();
        try {
            if(!p.waitFor(5,TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("驱动频段请求超时，未确认生效。");}
            StringBuilder out=new StringBuilder();
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(p.getInputStream(),"UTF-8"))) {
                String line;while((line=reader.readLine())!=null && out.length()<2048)out.append(line).append('\n');
            }
            if(p.exitValue()!=0)throw new IOException("驱动频段请求失败："+out.toString().trim());
            int value=Integer.parseInt(out.toString().trim());if(value<0||value>7)throw new NumberFormatException();return value;
        } catch(InterruptedException e){Thread.currentThread().interrupt();p.destroyForcibly();throw new IOException(e);}
        catch(NumberFormatException e){throw new IOException("Invalid driver response",e);}
    }
    private static String line(File file,String fallback) throws IOException {
        if(!file.exists())return fallback;
        if(!file.isFile()||file.length()>1024)throw new IOException("Invalid band state file");
        try(BufferedReader in=new BufferedReader(new FileReader(file))){String v=in.readLine();return v==null?fallback:v;}
    }
    private void write(String name,String value) throws IOException {
        File target=new File(state,name);
        if(value==null){if(target.exists()&&!target.delete())throw new IOException("Unable to clear band backup");return;}
        File temp=File.createTempFile(name+".",".tmp",state);
        try {
            Os.chmod(temp.getPath(),0600);
            try(FileOutputStream out=new FileOutputStream(temp)){out.write((value+"\n").getBytes("UTF-8"));out.getFD().sync();}
            Os.rename(temp.getPath(),target.getPath());
        } catch(Exception e){throw new IOException("无法保存频段设置/还原记录",e);}
        finally {temp.delete();}
    }
    private final WifiBandPolicy.Port port=new WifiBandPolicy.Port() {
        public String boot() throws IOException{return line(new File("/proc/sys/kernel/random/boot_id"),"");}
        public String iface() throws IOException {
            for(String n:new String[]{"wlan0","wlan1"})if(new File("/sys/class/net/"+n).exists())return n;
            throw new IOException("Wi-Fi interface unavailable");
        }
        public boolean interactive(){return interactive.getAsBoolean()&&!new File(module,"disable").exists()&&!new File(module,"remove").exists();}
        public boolean enabled() throws IOException{return "1".equals(line(new File(state,"force5g"),"0"));}
        public void enabled(boolean enabled) throws IOException{write("force5g",enabled?"1":"0");}
        public WifiBandPolicy.Backup backup() throws IOException {
            File f=new File(state,"band_original");if(!f.exists())return null;
            try {
                JSONObject b=new JSONObject(line(f,""));int mask=b.getInt("mask");String iface=b.getString("iface"),boot=b.getString("boot");
                if(mask<0||mask>7||(!"wlan0".equals(iface)&&!"wlan1".equals(iface))||boot.isEmpty())throw new IOException("Invalid backup");
                return new WifiBandPolicy.Backup(boot,iface,mask);
            } catch(Exception e){throw new IOException("频段还原记录异常，请保留记录并检查日志。",e);}
        }
        public void backup(WifiBandPolicy.Backup b) throws IOException {
            try {write("band_original",b==null?null:new JSONObject().put("boot",b.boot).put("iface",b.iface).put("mask",b.mask).toString());}
            catch(Exception e){throw new IOException(e);}
        }
        public int get(String iface) throws IOException{return nativeCall("get",iface,null);}
        public void set(String iface,int mask) throws IOException {
            int actual=nativeCall("set",iface,mask);if(actual!=mask)throw new IOException("驱动频段读回不一致。");
        }
    };
}
