import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Device integration fixture. Radio-changing calls are always intercepted. Not shipped. */
public final class WifiDeviceHarness {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Handler h=new Handler();
        File dir=new File(args[0]), state=new File(args[1]);
        Constructor<ColorOsWifiSense> ctor=ColorOsWifiSense.class.getDeclaredConstructor(File.class,File.class);
        ctor.setAccessible(true); Object core=ctor.newInstance(dir,state);
        Field wf=ColorOsWifiSense.class.getDeclaredField("wifi");wf.setAccessible(true);Object real=wf.get(core);
        Class<?> iface=Class.forName("android.net.wifi.IWifiManager");
        AtomicInteger rssi=new AtomicInteger(0), blocked=new AtomicInteger();
        Object proxy=Proxy.newProxyInstance(iface.getClassLoader(),new Class<?>[]{iface},(obj,m,a)->{
            if(m.getName().equals("disconnect")){blocked.incrementAndGet();System.out.println("TEST_BLOCKED_RADIO_DISCONNECT");return false;}
            Object result=m.invoke(real,a);
            if(m.getName().equals("getConnectionInfo")&&result!=null&&rssi.get()!=0){
                result.getClass().getMethod("setRssi",int.class).invoke(result,rssi.get());
            }
            return result;
        });
        wf.set(core,proxy); // Must succeed before entering the monitor loop.
        Field mf=ColorOsWifiSense.class.getDeclaredField("monitor");mf.setAccessible(true);
        WifiMonitor monitor=(WifiMonitor)mf.get(core);
        Method close=ColorOsWifiSense.class.getDeclaredMethod("closeLoop");close.setAccessible(true);
        File controlFile=new File(state,"test_control");
        try(FileWriter out=new FileWriter(controlFile)){out.write("native\n");}
        // A second watch on the same directory would reuse inotify's watch ID
        // and replace the production FileObserver dispatcher entry.
        FileObserver control=new FileObserver(controlFile,FileObserver.CLOSE_WRITE){
            @Override public void onEvent(int event,String name){
                h.post(()->{
                    try(BufferedReader in=new BufferedReader(new FileReader(new File(state,"test_control")))){
                        String command=in.readLine();
                        if("stop".equals(command)){stop(core,close);return;}
                        if("immediate".equals(command)){monitor.immediate();return;}
                        if("weak".equals(command))rssi.set(-90);
                        if("strong".equals(command))rssi.set(-60);
                        if("native".equals(command))rssi.set(0);
                        monitor.rssiEvent();
                    }catch(Exception e){
                        if(e instanceof RuntimeException)throw (RuntimeException)e;
                        throw new RuntimeException(e);
                    }
                });
            }
        };
        control.startWatching();
        h.postDelayed(()->stop(core,close),600000);
        Method listen=ColorOsWifiSense.class.getDeclaredMethod("listen");listen.setAccessible(true);
        System.out.println("TEST_READY radio disconnect interception active; uid="+android.os.Process.myUid()+" pid="+android.os.Process.myPid());System.out.flush();
        try{listen.invoke(core);}finally{control.stopWatching();}
        try(FileWriter out=new FileWriter(new File(state,"test-summary.txt"))){
            out.write("blocked_radio_calls="+blocked.get()+"\nrssi_reads="+monitor.metrics.rssiReads+"\n");
        }
        System.out.println("TEST_END blocked_radio_calls="+blocked.get());System.exit(0);
    }
    private static void stop(Object core,Method close){
        try{close.invoke(core);}catch(InvocationTargetException e){throw (RuntimeException)e.getCause();}
        catch(Exception e){throw new RuntimeException(e);}
    }
}
