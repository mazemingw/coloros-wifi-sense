import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.RemoteException;
import android.view.Display;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Event subscriptions for an app_process without an AMS application record. */
final class WifiSystemEvents implements AutoCloseable {
    interface Listener {
        // May run on a Binder thread: latch/invalidate before queuing handler work.
        void screen(boolean on);
        void wifi(String action);
    }
    private final Context context;
    private final Handler handler;
    private final PowerManager power;
    private final Listener listener;
    private final DisplayManager display;
    private boolean displayRegistered;
    private volatile boolean closed;
    private volatile boolean lastScreen;
    private Object am, receiver, oplus, oplusListener;
    private Class<?> receiverType, oplusListenerType;
    boolean broadcastRegistrationAttempted, oplusRegistered;
    volatile long broadcastDeliveries;
    private final Runnable reconcile = this::reconcileScreen;
    private void reconcileScreen() { if (!closed) notifyScreen(power.isInteractive()); }
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        public void onDisplayAdded(int id) { onDisplayChanged(id); }
        public void onDisplayRemoved(int id) { if (id == Display.DEFAULT_DISPLAY) notifyScreen(false); }
        public void onDisplayChanged(int id) {
            if (closed || id != Display.DEFAULT_DISPLAY) return;
            Display current = display.getDisplay(id);
            if (current == null) return;
            if (current.getState() != Display.STATE_ON) {
                handler.removeCallbacks(reconcile); notifyScreen(false);
            } else {
                notifyScreen(power.isInteractive());
                // A bounded lifecycle reconciliation, never an RSSI poll or wake lock.
                if (!power.isInteractive()) { handler.removeCallbacks(reconcile); handler.postDelayed(reconcile, 100); }
            }
        }
    };

    WifiSystemEvents(Context context, Handler handler, PowerManager power, Listener listener) {
        this.context = context; this.handler = handler; this.power = power; this.listener = listener;
        display = context.getSystemService(DisplayManager.class);
        lastScreen = power.isInteractive();
    }

    void start() throws Exception {
        try { registerBroadcasts(); } catch (Exception ignored) { }
        try { registerOplus(); } catch (Exception ignored) { }
        try {
            display.registerDisplayListener(displayListener, handler);
            displayRegistered = true;
        } catch (Exception error) {
            if (!oplusRegistered) throw new IllegalStateException("No screen lifecycle event channel", error);
        }
        listener.screen(power.isInteractive());
    }

    private void notifyScreen(boolean on) {
        if (closed || lastScreen == on) return;
        lastScreen = on;
        listener.screen(on);
    }

    private void registerOplus() throws Exception {
        Class<?> type = Class.forName("android.os.OplusPowerManager");
        try { oplus = type.getConstructor().newInstance(); }
        catch (NoSuchMethodException e) { oplus = type.getConstructor(Context.class).newInstance(context); }
        oplusListenerType = Class.forName("com.oplus.os.IOplusScreenStatusListener");
        Class<?> stub = Class.forName(oplusListenerType.getName() + "$Stub");
        int on = transaction(stub, "onScreenOn");
        int off = transaction(stub, "onScreenOff");
        oplusListener = new CallbackBinder(oplusListenerType, (code, data) -> {
            if (code == on) notifyScreen(true);
            else if (code == off) notifyScreen(false);
        }).proxy;
        type.getMethod("registerScreenStatusListener", oplusListenerType).invoke(oplus, oplusListener);
        oplusRegistered = true;
    }

    private void registerBroadcasts() throws Exception {
        receiverType = Class.forName("android.content.IIntentReceiver");
        Class<?> type = Class.forName("android.app.IActivityManager");
        am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        int perform = transaction(Class.forName(receiverType.getName() + "$Stub"), "performReceive");
        CallbackBinder callback = new CallbackBinder(receiverType, (code, data) -> {
            if (code != perform || closed) return;
            Intent intent = data.readInt() != 0 ? Intent.CREATOR.createFromParcel(data) : null;
            int resultCode = data.readInt(); String resultData = data.readString();
            Bundle extras = data.readInt() != 0 ? Bundle.CREATOR.createFromParcel(data) : null;
            boolean ordered = data.readInt() != 0;
            if (intent != null) {
                broadcastDeliveries++;
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) notifyScreen(false);
                else if (Intent.ACTION_SCREEN_ON.equals(action)) notifyScreen(true);
                else listener.wifi(action); // Ignore the broadcast's RSSI extra.
            }
            if (ordered) {
                try {
                    type.getMethod("finishReceiver", IBinder.class, int.class, String.class, Bundle.class,
                        boolean.class, int.class).invoke(am, ((IInterface) receiver).asBinder(), resultCode,
                            resultData, extras, false, intent == null ? 0 : intent.getFlags());
                } catch (Exception ignored) { }
            }
        });
        receiver = callback.proxy;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON); filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction("android.net.wifi.RSSI_CHANGED");
        filter.addAction("android.net.wifi.STATE_CHANGE");
        filter.addAction("android.net.wifi.WIFI_STATE_CHANGED");
        Method register = null;
        for (Method m : type.getMethods()) {
            if (m.getName().equals("registerReceiverWithFeature")) { register = m; break; }
            if (m.getName().equals("registerReceiver")) register = m;
        }
        if (register == null) throw new NoSuchMethodException("registerReceiver");
        Object[] args = new Object[register.getParameterCount()]; int ints = 0;
        Class<?>[] types = register.getParameterTypes();
        for (int i=0; i<args.length; i++) {
            if (types[i] == receiverType) args[i] = receiver;
            else if (types[i] == IntentFilter.class) args[i] = filter;
            else if (types[i] == int.class) args[i] = ints++ == 0 ? 0 : 2; // user 0, RECEIVER_EXPORTED
        }
        // New Android may silently reject a null caller without a ProcessRecord.
        // Return value is a sticky Intent, NOT proof of receiver registration.
        register.invoke(am, args);
        broadcastRegistrationAttempted = true;
    }

    private static int transaction(Class<?> stub, String name) throws Exception {
        Field f = stub.getDeclaredField("TRANSACTION_" + name); f.setAccessible(true); return f.getInt(null);
    }

    private interface Transaction { void receive(int code, Parcel data) throws Exception; }
    private static final class CallbackBinder extends Binder {
        final Object proxy;
        final String descriptor;
        final Transaction transaction;
        CallbackBinder(Class<?> type, Transaction transaction) {
            this.transaction = transaction; descriptor = type.getName();
            proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (obj, m, args) -> {
                if (m.getName().equals("asBinder")) return this;
                if (m.getName().equals("hashCode")) return System.identityHashCode(obj);
                if (m.getName().equals("equals")) return obj == args[0];
                if (m.getName().equals("toString")) return descriptor;
                return null;
            });
            attachInterface((IInterface) proxy, descriptor);
        }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(descriptor); return true; }
            if (code >= FIRST_CALL_TRANSACTION && code <= LAST_CALL_TRANSACTION) {
                data.enforceInterface(descriptor);
                try { transaction.receive(code, data); } catch (Exception ignored) { }
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    @Override public void close() {
        closed = true; handler.removeCallbacks(reconcile);
        if (displayRegistered) display.unregisterDisplayListener(displayListener);
        if (oplusRegistered) try {
            oplus.getClass().getMethod("unregisterScreenStatusListener", oplusListenerType).invoke(oplus, oplusListener);
        } catch (Exception ignored) { }
        if (receiver != null) try {
            Class.forName("android.app.IActivityManager").getMethod("unregisterReceiver", receiverType).invoke(am, receiver);
        } catch (Exception ignored) { }
    }
}
