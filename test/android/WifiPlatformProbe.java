import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IInterface;
import android.os.Looper;
import android.os.Parcel;
import android.os.PowerManager;
import java.lang.reflect.*;

/** Read-only platform probe, never packaged into the production DEX. */
public class WifiPlatformProbe {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Handler h = new Handler();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object t = at.getMethod("systemMain").invoke(null);
        Context c = (Context) at.getMethod("getSystemContext").invoke(t);
        PowerManager p = c.getSystemService(PowerManager.class);
        System.out.println("interactive=" + p.isInteractive());
        System.out.println("PROBE_PID=" + android.os.Process.myPid());
        for (String n : new String[]{"android.os.OplusPowerManager", "android.os.IOplusPowerManager"}) {
            try {
                Class<?> type = Class.forName(n);
                for (Method m : type.getMethods()) if (m.getName().toLowerCase().contains("screen") || m.getName().toLowerCase().contains("register")) System.out.println(n + " " + m);
            } catch (Exception e) { System.out.println(n + " " + e); }
        }
        Class<?> receiver = Class.forName("android.content.IIntentReceiver");
        Binder b = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code == 1) {
                    data.enforceInterface("android.content.IIntentReceiver");
                    Intent i = data.readInt() != 0 ? Intent.CREATOR.createFromParcel(data) : null;
                    System.out.println("BROADCAST=" + (i == null ? null : i.getAction()));
                    System.out.flush();
                    return true;
                }
                return false;
            }
        };
        Object proxy = Proxy.newProxyInstance(receiver.getClassLoader(), new Class<?>[]{receiver},
            (obj, method, values) -> method.getName().equals("asBinder") ? b : null);
        b.attachInterface((IInterface) proxy, "android.content.IIntentReceiver");
        Object am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        Class<?> iam = Class.forName("android.app.IActivityManager");
        IntentFilter f = new IntentFilter("com.nvmex.wifisense.PLATFORM_PROBE");
        f.addAction(Intent.ACTION_SCREEN_OFF); f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction("android.net.wifi.RSSI_CHANGED");
        for (Method m : iam.getMethods()) if (m.getName().equals("registerReceiverWithFeature")) {
            Class<?>[] types = m.getParameterTypes(); Object[] a = new Object[types.length]; int ints = 0;
            for (int i=0; i<types.length; i++) {
                if (types[i] == receiver) a[i] = proxy;
                else if (types[i] == IntentFilter.class) a[i] = f;
                else if (types[i] == int.class) a[i] = ints++ == 0 ? 0 : 2;
            }
            try { System.out.println("REGISTER=" + m.invoke(am, a)); }
            catch (Exception e) { System.out.println("REGISTER_FAILED=" + e); }
        }
        DisplayManager dm = c.getSystemService(DisplayManager.class);
        DisplayManager.DisplayListener dl = new DisplayManager.DisplayListener() {
            public void onDisplayAdded(int id) {}
            public void onDisplayRemoved(int id) {}
            public void onDisplayChanged(int id) { System.out.println("DISPLAY="+id+" interactive="+p.isInteractive()); System.out.flush(); }
        };
        dm.registerDisplayListener(dl, h);
        System.out.println("PROBE_READY"); System.out.flush();
        h.postDelayed(() -> { dm.unregisterDisplayListener(dl); System.out.println("PROBE_END"); System.exit(0); }, 60000);
        Looper.loop();
    }
}
