import android.content.Context;
import android.content.ContextWrapper;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.os.FileObserver;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.util.Log;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.channels.FileLock;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

public final class ColorOsWifiSense {
    private static final String TAG = "ColorOsWifiSense", ROOT_PACKAGE = "root";
    private final File moduleDir, stateDir, thresholdFile;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<Network> connections = new HashSet<>();
    private final AtomicLong epoch = new AtomicLong();
    private final Context context;
    private final PowerManager power;
    private final ConnectivityManager connectivity;
    private final Object wifi;
    private final Method connectionInfo, disconnect;
    private final WifiMonitor monitor;
    private volatile boolean screenOff, stopRequested, configDirty = true;
    private boolean closed, fluidEnabled;
    private boolean force5gEnabled;
    private volatile Thread bandWorker;
    private final Runnable bandTask = this::reconcileBand;
    private long wifiBinderReads, fluidStarts, fluidErrors, powerChecks;
    private final AtomicLong ignoredCapabilityEvents = new AtomicLong();
    private WifiSystemEvents events;
    private String fluidToken;
    private BufferedWriter fluidInput;
    private java.lang.Process fluidChild;
    private boolean fluidResult, demo, demoActionSeen;
    private Warning pendingWarning;
    private static final class StopLoop extends RuntimeException {
        StopLoop() { super(null, null, false, false); }
    }

    private static final class Warning {
        final int rssi, threshold; final long deadline, duration;
        Warning(int rssi, int threshold, long deadline, long duration) { this.rssi=rssi; this.threshold=threshold; this.deadline=deadline; this.duration=duration; }
    }

    private ColorOsWifiSense(File moduleDir, File stateDir) throws ReflectiveOperationException {
        this.moduleDir = moduleDir; this.stateDir = stateDir;
        thresholdFile = new File(stateDir, "threshold");
        context = rootContext();
        power = context.getSystemService(PowerManager.class);
        Class<?> connectivityInterface = Class.forName("android.net.IConnectivityManager");
        connectivity = ConnectivityManager.class.getConstructor(Context.class, connectivityInterface)
            .newInstance(context, systemInterface("connectivity", connectivityInterface));
        Class<?> wifiInterface = Class.forName("android.net.wifi.IWifiManager");
        wifi = systemInterface("wifi", wifiInterface);
        connectionInfo = findConnectionInfo(wifiInterface);
        disconnect = wifiInterface.getMethod("disconnect", String.class);
        monitor = new WifiMonitor(new AndroidPort());
        fluidEnabled = readFluidEnabled(moduleDir);
    }

    @SuppressWarnings("deprecation")
    public static void main(String[] args) {
        try {
            if (Process.myUid() != 0 || args.length != 2) throw new IllegalArgumentException("Root and module paths required");
            Looper.prepareMainLooper();
            if ("--demo".equals(args[0])) {
                new ColorOsWifiSense(new File(args[1]), new File(args[1])).runDemo(); return;
            }
            File moduleDir = new File(args[0]), stateDir = new File(args[1]);
            if (!stateDir.isDirectory()) throw new IOException("Module state directory is missing");
            try (RandomAccessFile file = new RandomAccessFile(new File(stateDir, "watcher.lock"), "rw");
                 FileLock lock = file.getChannel().tryLock()) {
                if (lock == null) return;
                new ColorOsWifiSense(moduleDir, stateDir).listen();
            }
            System.exit(0);
        } catch (Exception e) {
            Log.e(TAG, "Wi-Fi watcher stopped", unwrap(e)); System.exit(1);
        }
    }

    private boolean interactive() {
        if (stopRequested || screenOff) return false;
        powerChecks++;
        return power.isInteractive();
    }

    private final class AndroidPort implements WifiMonitor.Port {
        public long now() { return SystemClock.elapsedRealtime(); }
        public long epoch() { return epoch.get(); }
        public boolean interactive() { return ColorOsWifiSense.this.interactive(); }
        public boolean singleWifi() { return connections.size() == 1; }
        public void schedule(Runnable task, long delay) { handler.postDelayed(task, delay); }
        public void cancel(Runnable task) { handler.removeCallbacks(task); }
        public WifiMonitor.Snapshot readWifi() throws Exception {
            if (stopRequested || screenOff || connections.size() != 1) return null;
            Network network = connections.iterator().next();
            WifiInfo info = readConnectionInfo();
            if (info.getSupplicantState() != SupplicantState.COMPLETED) return null;
            return new WifiMonitor.Snapshot(network, info.getBSSID(), info.getRssi());
        }
        public WifiMonitor.Attempt disconnect(WifiMonitor.Snapshot expected, long generation) {
            if (!interactive()) return WifiMonitor.Attempt.ASLEEP;
            // Rare final preflight only: close the FileObserver delivery window
            // without re-reading configuration on every periodic sample.
            if (disabled()) { stopRequested=true; handler.post(() -> closeLoop()); return WifiMonitor.Attempt.STALE; }
            try {
                if (readThresholdValue() != monitor.threshold() || readCountdownValue() * 1_000L != monitor.countdownMs()) {
                    configDirty=true; epoch.incrementAndGet(); handler.post(() -> reloadConfig());
                    return WifiMonitor.Attempt.STALE;
                }
            } catch (IOException e) { monitor.invalidConfiguration(); return WifiMonitor.Attempt.STALE; }
            if (closed || stopRequested || epoch.get() != generation || connections.size() != 1
                    || !connections.contains(expected.network)) return WifiMonitor.Attempt.STALE;
            NetworkCapabilities caps = connectivity.getNetworkCapabilities((Network) expected.network);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return WifiMonitor.Attempt.STALE;
            // Last checks immediately before the disconnect Binder call.
            if (!interactive()) return WifiMonitor.Attempt.ASLEEP;
            if (epoch.get() != generation || stopRequested) return WifiMonitor.Attempt.STALE;
            try {
                boolean accepted = Boolean.TRUE.equals(disconnect.invoke(wifi, ROOT_PACKAGE));
                if (accepted) {
                    fluidResult = fluidInput != null; sendFluid("working");
                    Log.i(TAG, "AP disconnect requested; screen and AP preflight passed");
                }
                return accepted ? WifiMonitor.Attempt.ACCEPTED : WifiMonitor.Attempt.REJECTED;
            } catch (Exception e) {
                Log.w(TAG, "Android rejected AP disconnect", unwrap(e)); return WifiMonitor.Attempt.REJECTED;
            }
        }
        public void warn(WifiMonitor.Snapshot info, int threshold, long deadline) { startFluid(info.rssi, threshold, deadline); }
        public void cancelWarning() { if (!fluidResult) stopFluid(); }
        public void clearFeedback() { stopFluid(); }
        public void result(String reason) { showFluidResult(reason); }
    }

    private void listen() throws Exception {
        if (disabled()) return;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stopRequested=true;
            stopFluid(); java.lang.Process child = fluidChild; if (child != null) child.destroy();
            releaseBand();
        }, "wifi-sense-cleanup"));
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            private final ConcurrentHashMap<Network,String> signals = new ConcurrentHashMap<>();
            private void enqueue(Network n, int kind) {
                epoch.incrementAndGet();
                handler.post(() -> {
                    if (closed) return;
                    boolean changed = false;
                    if (kind == 1) changed = connections.add(n);
                    if (kind == -1) changed = connections.remove(n);
                    monitor.network(kind == -1 ? n : null, changed);
                    if (kind == 1 && changed) scheduleBand();
                });
            }
            @Override public void onAvailable(Network n) { enqueue(n, 1); }
            @Override public void onLost(Network n) { signals.remove(n); enqueue(n, -1); }
            @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
                Object info=caps.getTransportInfo();
                String bssid=info instanceof WifiInfo?((WifiInfo)info).getBSSID():null;
                String signal=caps.getSignalStrength()+":"+bssid;
                if (signal.equals(signals.put(n,signal))) { ignoredCapabilityEvents.incrementAndGet(); return; }
                enqueue(n, 0);
            }
        };
        FileObserver moduleObserver = new FileObserver(moduleDir, FileObserver.CREATE | FileObserver.CLOSE_WRITE
            | FileObserver.MOVED_TO | FileObserver.DELETE | FileObserver.DELETE_SELF | FileObserver.MOVE_SELF) {
            @Override public void onEvent(int event, String path) {
                if ((event & (FileObserver.DELETE_SELF | FileObserver.MOVE_SELF)) != 0
                        || "disable".equals(path) || "remove".equals(path)) {
                    if (disabled()) { stopRequested = true; epoch.incrementAndGet(); handler.post(() -> closeLoop()); }
                } else if ("fluid_enabled".equals(path)) {
                    configDirty = true; handler.post(() -> { if (interactive()) reloadConfig(); });
                }
            }
        };
        FileObserver stateObserver = new FileObserver(stateDir, FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO | FileObserver.DELETE) {
            @Override public void onEvent(int event, String path) {
                if ("threshold".equals(path) || "countdown".equals(path) || "force5g".equals(path)) {
                    configDirty = true; epoch.incrementAndGet();
                    handler.post(() -> { if (interactive()) reloadConfig(); });
                } else if ("ui_request".equals(path)) {
                    handler.post(() -> {
                        if (closed) return;
                        if (interactive()) { reloadConfig(); monitor.refresh(); }
                        handler.post(() -> writeStatus(readLine(new File(stateDir, "ui_request"), "")));
                    });
                }
            }
        };
        events = new WifiSystemEvents(context, handler, power, new WifiSystemEvents.Listener() {
            public void screen(boolean on) {
                screenOff = !on; epoch.incrementAndGet();
                handler.post(() -> {
                    if (closed) return;
                    if (on && interactive()) { configDirty = true; reloadConfig(); refreshConnections(); scheduleBand(); }
                    else if (!on) handler.removeCallbacks(bandTask);
                    monitor.screen(on);
                });
            }
            public void wifi(String action) {
                epoch.incrementAndGet();
                handler.post(() -> { if (!closed) monitor.rssiEvent(); });
            }
        });
        moduleObserver.startWatching(); stateObserver.startWatching();
        NetworkRequest request = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
        connectivity.registerNetworkCallback(request, callback);
        try {
            events.start();
            writeStatus("");
            Log.i(TAG, "Interactive-only Wi-Fi monitor ready; 7s awake fallback, no wake lock");
            Looper.loop();
        } catch (StopLoop done) {
            // Android's main Looper deliberately rejects quit()/quitSafely().
            // Unwind it through this owner so finally can unregister every listener.
        } finally {
            closed = true; stopRequested=true; handler.removeCallbacks(bandTask); monitor.stop(); events.close();
            moduleObserver.stopWatching(); stateObserver.stopWatching();
            connectivity.unregisterNetworkCallback(callback);
            java.lang.Process child = fluidChild; if (child != null) child.destroy();
            releaseBand();
        }
    }

    private void closeLoop() { if (!closed) { closed=true; monitor.stop(); throw new StopLoop(); } }
    private boolean disabled() {
        return !moduleDir.isDirectory() || new File(moduleDir,"disable").exists() || new File(moduleDir,"remove").exists();
    }
    @SuppressWarnings("deprecation")
    private void refreshConnections() {
        connections.clear();
        for (Network n : connectivity.getAllNetworks()) {
            NetworkCapabilities c = connectivity.getNetworkCapabilities(n);
            if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) connections.add(n);
        }
    }
    private void reloadConfig() {
        if (!configDirty) return;
        configDirty = false;
        try {
            monitor.threshold(readThresholdValue());
            monitor.countdown(readCountdownValue());
        } catch (IOException e) {
            monitor.invalidConfiguration(); Log.w(TAG, "Invalid threshold/countdown; monitoring paused until repaired");
        }
        boolean enabled = readFluidEnabled(moduleDir);
        if (!enabled) stopFluid();
        fluidEnabled = enabled;
        force5gEnabled = "1".equals(readLine(new File(stateDir,"force5g"),"0"));
    }

    private void scheduleBand() {
        if (!interactive() || (!force5gEnabled && !new File(stateDir,"band_original").isFile())) return;
        handler.removeCallbacks(bandTask); handler.postDelayed(bandTask,250);
    }
    private void reconcileBand() {
        if (!interactive() || (bandWorker!=null && bandWorker.isAlive())) return;
        bandWorker=new Thread(() -> {
            try { WifiBandControl.execute(moduleDir,stateDir,"reconcile",this::interactive); }
            catch(Exception e) { Log.w(TAG,"Frequency policy not applied: "+e.getMessage()); }
        },"wifi-band-on-demand");
        bandWorker.setDaemon(true); bandWorker.start();
    }
    private synchronized void releaseBand() {
        Thread worker=bandWorker;
        if(worker!=null && worker!=Thread.currentThread())try { worker.join(15000); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        if(!new File(stateDir,"band_original").isFile())return;
        try { WifiBandControl.execute(moduleDir,stateDir,"release",() -> false); }
        catch(Exception e) { Log.w(TAG,"Frequency restore pending: "+e.getMessage()); }
    }

    private void startFluid(int rssi, int threshold, long deadline) {
        if (!fluidEnabled || !interactive()) return;
        pendingWarning = new Warning(rssi, threshold, deadline, demo ? WifiDisconnectPolicy.COUNTDOWN_MS : monitor.countdownMs());
        if (fluidChild != null) { stopChild(); return; }
        launchPendingWarning();
    }
    private void launchPendingWarning() {
        Warning warning = pendingWarning;
        if (warning == null || fluidChild != null || !fluidEnabled || !interactive()) return;
        pendingWarning = null;
        if (!demo && (monitor.deadline() != warning.deadline || warning.deadline <= SystemClock.elapsedRealtime())) return;
        String token = UUID.randomUUID().toString();
        try {
            ProcessBuilder builder = new ProcessBuilder("/system/bin/app_process", "/system/bin",
                "--nice-name=coloros-wifi-fluid", "WifiFluidSession", demo ? "demo" : "watch", token,
                String.valueOf(warning.rssi), String.valueOf(warning.threshold),
                String.valueOf(warning.duration), String.valueOf(warning.deadline));
            builder.environment().put("CLASSPATH", new File(moduleDir,"wifi-fluid.dex").getAbsolutePath());
            builder.redirectErrorStream(true);
            java.lang.Process child = builder.start(); fluidStarts++;
            fluidChild = child; fluidToken = token; fluidResult = false;
            fluidInput = new BufferedWriter(new OutputStreamWriter(child.getOutputStream(), "UTF-8"));
            Thread reader = new Thread(() -> {
                try (BufferedReader output = new BufferedReader(new InputStreamReader(child.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line=output.readLine()) != null) {
                        String[] parts=line.split(" ");
                        if (parts.length==3 && "WIFI_SENSE".equals(parts[0]) && token.equals(parts[2])) {
                            String event=parts[1]; handler.post(() -> onFluidEvent(token,event));
                        }
                    }
                } catch (IOException ignored) { }
                finally {
                    try {
                        if (!child.waitFor(1000,TimeUnit.MILLISECONDS)) {
                            child.destroy();
                            if (!child.waitFor(500,TimeUnit.MILLISECONDS)) { child.destroyForcibly(); child.waitFor(500,TimeUnit.MILLISECONDS); }
                        }
                    }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); child.destroy(); }
                    handler.post(() -> {
                        if (fluidChild != child) return;
                        if (child.isAlive()) return;
                        if (child.exitValue()!=0) { fluidErrors++; Log.w(TAG,"Fluid session exited: "+child.exitValue()); }
                        fluidChild=null;
                        if (token.equals(fluidToken)) {
                            BufferedWriter oldInput=fluidInput;
                            fluidToken=null; fluidInput=null; fluidResult=false;
                            if(oldInput!=null) try { oldInput.close(); } catch(IOException ignored) { }
                        }
                        launchPendingWarning();
                    });
                }
            }, "wifi-fluid-events");
            reader.setDaemon(true); reader.start();
        } catch (IOException error) { fluidErrors++; Log.w(TAG,"Fluid UI unavailable; core remains active",error); }
    }
    private void onFluidEvent(String token,String event) {
        if (!token.equals(fluidToken) || "ready".equals(event)) return;
        if (!"immediate".equals(event) && !"expired".equals(event)) return;
        if (demo) {
            if (demoActionSeen) return;
            demoActionSeen=true;
            System.out.println("Demo action="+event+"; no Wi-Fi disconnect executed");
            showFluidResult("immediate".equals(event)?"manual":"auto");
            handler.postDelayed(() -> { stopFluid(); System.exit(0); },15500); return;
        }
        if (!interactive()) { monitor.screen(false); return; }
        if ("immediate".equals(event)) monitor.immediate(); else monitor.refresh();
    }
    private void showFluidResult(String reason) {
        if (!fluidEnabled || fluidInput==null || !interactive()) return;
        fluidResult=true; sendFluid("result "+(reason==null?"failed":reason));
    }
    private void sendFluid(String command) {
        BufferedWriter input=fluidInput;
        if (input==null) return;
        try { input.write(command); input.write('\n'); input.flush(); }
        catch(IOException e) { fluidErrors++; }
    }
    private synchronized void stopChild() {
        fluidToken=null; fluidResult=false;
        BufferedWriter input=fluidInput; fluidInput=null;
        if(input!=null) {
            try { input.write("cancel\n"); input.flush(); } catch(IOException ignored) { }
            finally { try { input.close(); } catch(IOException ignored) { } }
        }
        java.lang.Process child=fluidChild;
        if(child!=null) handler.postDelayed(() -> { if(child.isAlive()) child.destroy(); },500);
    }
    private void stopFluid() { pendingWarning=null; stopChild(); }

    private void runDemo() {
        if(!fluidEnabled) { System.out.println("当前为仅核心模式，请重新安装并选择流体云通知。"); System.exit(1); }
        demo=true; System.out.println("演示模式：不会断开WIFI，仅测试流体云和开关回调。");
        startFluid(-84,-80,SystemClock.elapsedRealtime()+7000);
        handler.postDelayed(() -> { if(!demoActionSeen) { stopFluid(); System.exit(0); } },10000);
        Looper.loop();
    }

    private void writeStatus(String request) {
        try {
            WifiMonitor.Metrics m=monitor.metrics; WifiMonitor.Snapshot last=monitor.last();
            JSONObject j=new JSONObject().put("request",request).put("pid",Process.myPid())
                .put("updated_ms",System.currentTimeMillis()).put("state",monitor.state().name())
                .put("interactive",interactive()).put("wifi_connections",connections.size())
                .put("threshold",monitor.threshold()).put("fluid_enabled",fluidEnabled)
                .put("countdown_seconds",monitor.countdownMs()/1000)
                .put("fluid_installed",new File(moduleDir,"wifi-fluid.dex").isFile())
                .put("rssi",last==null?JSONObject.NULL:last.rssi).put("sample_scheduled",monitor.hasSampleTask())
                .put("countdown_ms",Math.max(0,monitor.deadline()-SystemClock.elapsedRealtime()))
                .put("cooldown_ms",Math.max(0,monitor.cooldownUntil()-SystemClock.elapsedRealtime()))
                .put("rssi_events",m.rssiEvents).put("network_events",m.networkEvents).put("screen_events",m.screenEvents)
                .put("rssi_reads",wifiBinderReads).put("read_attempts",m.rssiReads)
                .put("periodic_runs",m.periodicRuns).put("task_runs",m.scheduledRuns)
                .put("countdown_starts",m.countdownStarts).put("countdown_cancels",m.countdownCancels)
                .put("disconnect_attempts",m.disconnectAttempts).put("disconnect_accepted",m.disconnectAccepted)
                .put("weak_reconnects",m.weakReconnects).put("stale_suppressed",m.staleSuppressed)
                .put("late_tasks_suppressed",m.lateTasksSuppressed).put("read_errors",m.readErrors)
                .put("last_cancel",m.lastCancel).put("last_disconnect",m.lastDisconnect)
                .put("fluid_starts",fluidStarts).put("fluid_errors",fluidErrors).put("power_checks",powerChecks)
                .put("ignored_capability_events",ignoredCapabilityEvents.get())
                .put("screen_channel",events!=null&&events.oplusRegistered?"ColorOS + display":"display")
                .put("broadcast_deliveries",events==null?0:events.broadcastDeliveries)
                .put("cpu_ticks",cpuTicks());
            File temp=new File(stateDir,"status.tmp"), target=new File(stateDir,"status.json");
            try(FileOutputStream out=new FileOutputStream(temp)) { out.write(j.toString().getBytes("UTF-8")); }
            Os.chmod(temp.getAbsolutePath(),0600); Os.rename(temp.getAbsolutePath(),target.getAbsolutePath());
        } catch(Exception e) { Log.w(TAG,"Unable to write requested diagnostics",e); }
    }
    private static long cpuTicks() {
        try {
            String stat=readLine(new File("/proc/self/stat"),"");
            String[] f=stat.substring(stat.lastIndexOf(')')+2).split(" ");
            return Long.parseLong(f[11])+Long.parseLong(f[12]);
        } catch(Exception e) { return -1; }
    }
    private static boolean readFluidEnabled(File dir) {
        return new File(dir,"wifi-fluid.dex").isFile() && "1".equals(readLine(new File(dir,"fluid_enabled"),"0"));
    }
    private static String readLine(File file,String fallback) {
        try(BufferedReader r=new BufferedReader(new FileReader(file))) { String value=r.readLine(); return value==null?fallback:value; }
        catch(IOException e) { return fallback; }
    }
    private int readThresholdValue() throws IOException {
        if (!thresholdFile.exists()) return -80;
        if (!thresholdFile.isFile() || thresholdFile.length()>32) throw new IOException("Invalid threshold file");
        try(BufferedReader input=new BufferedReader(new FileReader(thresholdFile))) {
            String text=input.readLine();
            int value=Integer.parseInt(text==null?"":text.trim());
            if(value < -100 || value > -20) throw new IOException("Invalid threshold range");
            return value;
        } catch(NumberFormatException e) { throw new IOException("Invalid threshold number",e); }
    }
    private int readCountdownValue() throws IOException {
        File file=new File(stateDir,"countdown");
        if(!file.exists())return 7;
        if(!file.isFile() || file.length()>32)throw new IOException("Invalid countdown file");
        try(BufferedReader in=new BufferedReader(new FileReader(file))) {
            String text=in.readLine();int n=Integer.parseInt(text==null?"":text.trim());
            if(n<1||n>300)throw new IOException("Invalid countdown range");return n;
        } catch(NumberFormatException e) { throw new IOException("Invalid countdown number",e); }
    }
    private WifiInfo readConnectionInfo() throws ReflectiveOperationException {
        wifiBinderReads++;
        return (WifiInfo)(connectionInfo.getParameterCount()==1?connectionInfo.invoke(wifi,ROOT_PACKAGE)
            :connectionInfo.invoke(wifi,ROOT_PACKAGE,null));
    }
    private static Method findConnectionInfo(Class<?> type) throws NoSuchMethodException {
        try { return type.getMethod("getConnectionInfo",String.class,String.class); }
        catch(NoSuchMethodException e) { return type.getMethod("getConnectionInfo",String.class); }
    }
    private static Context rootContext() throws ReflectiveOperationException {
        Class<?> at=Class.forName("android.app.ActivityThread"); Object thread=at.getMethod("systemMain").invoke(null);
        Context system=(Context)at.getMethod("getSystemContext").invoke(thread);
        return new ContextWrapper(system) {
            @Override public String getPackageName() { return ROOT_PACKAGE; }
            @Override public String getOpPackageName() { return ROOT_PACKAGE; }
        };
    }
    private static Object systemInterface(String name,Class<?> iface) throws ReflectiveOperationException {
        IBinder b=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,name);
        if(b==null) throw new IllegalStateException("Android service unavailable: "+name);
        return Class.forName(iface.getName()+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,b);
    }
    private static Throwable unwrap(Exception error) {
        return error instanceof InvocationTargetException && error.getCause()!=null?error.getCause():error;
    }
}
