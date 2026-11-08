import java.util.*;

public final class WifiMonitorTest {
    static int passed;
    static class Fake implements WifiMonitor.Port {
        long time, generation;
        boolean on=true, wifi=true, valid=true, ui=true;
        String ap="ap-a"; Object network="net-a";
        int rssi=-60, attempts, starts, stops, results;
        boolean feedback, resultVisible;
        Runnable duringRead, beforeAttempt;
        WifiMonitor.Attempt reply=WifiMonitor.Attempt.ACCEPTED;
        final Map<Runnable,Long> tasks=new HashMap<>();
        final WifiMonitor monitor=new WifiMonitor(this);
        public long now(){return time;} public long epoch(){return generation;}
        public boolean interactive(){return on;} public boolean singleWifi(){return wifi;}
        public WifiMonitor.Snapshot readWifi(){
            WifiMonitor.Snapshot s=valid&&wifi?new WifiMonitor.Snapshot(network,ap,rssi):null;
            if(duringRead!=null){Runnable r=duringRead;duringRead=null;r.run();}
            return s;
        }
        public WifiMonitor.Attempt disconnect(WifiMonitor.Snapshot s,long e){
            if(beforeAttempt!=null){Runnable r=beforeAttempt;beforeAttempt=null;r.run();}
            if(!on)return WifiMonitor.Attempt.ASLEEP;
            if(e!=generation||!wifi)return WifiMonitor.Attempt.STALE;
            attempts++; resultVisible=false;
            return reply;
        }
        public void schedule(Runnable r,long delay){tasks.put(r,time+delay);}
        public void cancel(Runnable r){tasks.remove(r);}
        public void warn(WifiMonitor.Snapshot s,int t,long deadline){if(ui){starts++;feedback=true;resultVisible=false;}}
        public void cancelWarning(){if(!resultVisible)clearFeedback();}
        public void clearFeedback(){if(feedback)stops++;feedback=false;resultVisible=false;}
        public void result(String reason){if(ui){results++;feedback=true;resultVisible=true;}}
        void start(){monitor.screen(on);advance(0);}
        void advance(long target){
            int limit=100000;
            while(true){
                Runnable next=null;long due=Long.MAX_VALUE;
                for(Map.Entry<Runnable,Long> e:tasks.entrySet())if(e.getValue()<due){next=e.getKey();due=e.getValue();}
                if(next==null||due>target)break;
                if(--limit==0)throw new AssertionError("scheduler spin");
                time=due;tasks.remove(next);next.run();
            }time=target;
        }
        void screen(boolean value){on=value;generation++;monitor.screen(value);}
        void topology(boolean connected){wifi=connected;generation++;monitor.network(connected?null:network,true);}
        void event(){generation++;monitor.rssiEvent();}
    }
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    static void test(String name,Runnable test){test.run();passed++;System.out.println("PASS "+name);}
    public static void main(String[] args){
        test("awake normal, fallback bounded",()->{
            Fake f=new Fake();f.start();f.advance(60000);check(f.monitor.metrics.rssiReads==9,"7s fallback cadence");
            check(f.attempts==0&&f.starts==0&&f.monitor.state()==WifiMonitor.State.IDLE,"normal work");
        });
        test("gradual drop with events",()->{
            Fake f=new Fake();f.start();f.advance(1000);f.rssi=-76;f.event();f.advance(2000);
            f.rssi=-81;f.event();f.advance(3000);check(f.monitor.deadline()>0,"no countdown");
            f.advance(10000);check(f.attempts==1,"not disconnected");
        });
        test("sudden drop without any RSSI broadcast",()->{
            Fake f=new Fake();f.start();f.rssi=-95;f.advance(13999);check(f.attempts==0,"early");
            f.advance(14000);check(f.attempts==1,"fallback missed threshold");
        });
        test("recover during countdown",()->{
            Fake f=new Fake();f.rssi=-90;f.start();f.advance(3000);f.rssi=-60;f.event();f.advance(20000);
            check(f.attempts==0&&f.monitor.metrics.countdownCancels==1&&!f.feedback,"recovery");
        });
        test("screen off cancels countdown, UI and every task",()->{
            Fake f=new Fake();f.rssi=-90;f.start();f.advance(3000);f.screen(false);
            long reads=f.monitor.metrics.rssiReads,runs=f.monitor.metrics.periodicRuns;
            check(f.tasks.isEmpty()&&!f.feedback&&!f.monitor.hasSampleTask(),"off resources");
            f.advance(3600000);check(f.attempts==0&&f.monitor.metrics.rssiReads==reads&&f.monitor.metrics.periodicRuns==runs,"off work");
        });
        test("off RSSI/network events do no business I/O",()->{
            Fake f=new Fake();f.start();f.screen(false);long reads=f.monitor.metrics.rssiReads;
            for(int i=0;i<500;i++){f.event();f.topology((i%2)==0);}
            f.advance(100000);check(f.monitor.metrics.rssiReads==reads&&f.tasks.isEmpty()&&f.attempts==0,"off event work");
        });
        test("wake weak: new full countdown and new AP",()->{
            Fake f=new Fake();f.rssi=-90;f.start();f.advance(6000);f.screen(false);f.advance(30000);
            f.ap="ap-b";f.screen(true);f.advance(30000);check(f.monitor.deadline()==37000,"old countdown resumed");
            f.advance(36999);check(f.attempts==0,"wake early");f.advance(37000);check(f.attempts==1,"wake missed");
        });
        test("BSSID changes during verification",()->{
            Fake f=new Fake();f.rssi=-90;f.start();f.advance(6000);f.ap="ap-b";f.event();f.advance(7000);
            check(f.attempts==0,"stale AP disconnected");f.advance(14000);check(f.attempts==1,"new AP not verified");
        });
        test("disconnect then reconnect, cooldown retained",()->{
            Fake f=new Fake();f.rssi=-90;f.start();f.advance(7000);f.topology(false);
            check(f.results==1&&f.tasks.isEmpty(),"disconnect confirmation");
            f.advance(7500);f.topology(true);f.advance(8000);long reads=f.monitor.metrics.rssiReads;
            f.advance(66000);check(f.attempts==1&&f.monitor.metrics.rssiReads==reads,"cooldown polled");
        });
        test("weak same AP reconnect counted without rapid redisconnect",()->{
            Fake f=new Fake();f.rssi=-90;f.start();f.advance(7000);f.topology(false);f.topology(true);f.advance(8000);
            check(f.monitor.metrics.weakReconnects==1&&f.attempts==1,"weak reconnect");
            for(int i=0;i<100;i++)f.event();f.advance(66000);check(f.attempts==1,"cooldown bypass");
        });
        test("no Wi-Fi has zero recurring work",()->{
            Fake f=new Fake();f.wifi=false;f.start();f.advance(1000000);
            check(f.monitor.metrics.rssiReads==0&&f.tasks.isEmpty(),"no wifi poll");
        });
        test("mobile data only has zero Wi-Fi reads",()->{
            Fake f=new Fake();f.wifi=false;f.start();for(int i=0;i<100;i++)f.monitor.network(null,false);
            f.advance(1000000);check(f.monitor.metrics.rssiReads==0&&f.attempts==0,"mobile work");
        });
        test("threshold change invalidates countdown",()->{
            Fake f=new Fake();f.rssi=-90;f.start();f.advance(6000);f.monitor.threshold(-85);f.advance(7000);
            check(f.attempts==0&&f.monitor.deadline()==13000,"threshold stale");
        });
        test("manual disconnect requires current weak AP",()->{
            Fake f=new Fake();f.rssi=-90;f.start();f.advance(1000);check(f.monitor.immediate(),"manual rejected");
            check(!f.monitor.immediate()&&f.attempts==1,"duplicate manual");
        });
        test("manual rejects recovered RSSI",()->{
            Fake f=new Fake();f.rssi=-90;f.start();f.rssi=-60;check(!f.monitor.immediate()&&f.attempts==0&&!f.feedback,"manual recovered");
        });
        test("screen-off races read",()->{
            Fake f=new Fake();f.rssi=-90;f.start();f.advance(6000);f.duringRead=()->f.on=false;f.advance(7000);
            check(f.attempts==0&&f.tasks.isEmpty()&&!f.feedback,"read race");
        });
        test("screen-off races last Binder gate",()->{
            Fake f=new Fake();f.rssi=-90;f.start();f.beforeAttempt=()->f.on=false;f.advance(7000);
            check(f.attempts==0&&f.monitor.cooldownUntil()==0&&f.tasks.isEmpty(),"last gate race");
        });
        test("new event invalidates in-flight RSSI",()->{
            Fake f=new Fake();f.rssi=-90;f.start();f.advance(6000);f.duringRead=()->f.generation++;f.advance(7000);
            check(f.attempts==0&&f.monitor.metrics.staleSuppressed>0,"generation race");
        });
        test("new event races final gate, no cooldown consumed",()->{
            Fake f=new Fake();f.rssi=-90;f.start();f.beforeAttempt=()->f.generation++;f.advance(7000);
            check(f.attempts==0&&f.monitor.cooldownUntil()==0,"stale gate");
        });
        test("final fresh RSSI/BSSID recheck",()->{
            Fake f=new Fake();f.rssi=-90;f.start();f.advance(6000);
            f.duringRead=()->f.rssi=-60;f.advance(7000);check(f.attempts==0,"fresh read missing");
        });
        test("invalid connection info cannot disconnect",()->{
            Fake f=new Fake();f.rssi=-90;f.start();f.valid=false;f.advance(15000);check(f.attempts==0,"invalid info");
        });
        test("core-only never creates UI",()->{
            Fake f=new Fake();f.ui=false;f.rssi=-90;f.start();f.advance(7000);
            check(f.attempts==1&&f.starts==0&&!f.feedback,"core UI");
        });
        test("repeated RSSI events coalesce, one child per countdown",()->{
            Fake f=new Fake();f.rssi=-90;f.start();
            for(int t=0;t<6000;t+=100){f.advance(t);for(int i=0;i<20;i++)f.event();}
            f.advance(7000);check(f.starts==1&&f.attempts==1&&f.monitor.metrics.rssiReads<=16,"storm churn");
        });
        test("late disconnect confirmation after screen-off creates no UI",()->{
            Fake f=new Fake();f.rssi=-90;f.start();f.advance(7000);f.screen(false);f.topology(false);f.advance(30000);
            check(!f.feedback&&f.results==0&&f.tasks.isEmpty(),"late UI");
        });
        test("wake during cooldown reads once but never disconnects",()->{
            Fake f=new Fake();f.rssi=-90;f.start();f.advance(7000);f.screen(false);f.advance(15000);
            long reads=f.monitor.metrics.rssiReads;f.screen(true);f.advance(15000);
            check(f.monitor.metrics.rssiReads==reads+1&&f.attempts==1,"cooldown wake snapshot");
        });
        test("stop removes all work and notifications",()->{
            Fake f=new Fake();f.rssi=-90;f.start();f.monitor.stop();f.advance(1000000);
            check(f.tasks.isEmpty()&&!f.feedback&&f.attempts==0,"stop resources");
        });
        test("one-hour operation-count comparison",()->{
            Fake f=new Fake();f.start();f.advance(899999);f.screen(false);long reads=f.monitor.metrics.rssiReads;
            f.advance(3599999);long oldReads=1+3599999/7000;
            check(reads==129&&oldReads==515&&f.monitor.metrics.rssiReads==reads,"read model");
            System.out.println("METRIC stable Wi-Fi 1h, interactive 15m: old="+oldReads+", new="+reads+", off=0");
        });
        test("invalid configuration quiesces instead of retrying every second",()->{
            Fake f=new Fake();f.rssi=-90;f.start();f.advance(6000);f.monitor.invalidConfiguration();
            long reads=f.monitor.metrics.rssiReads;f.advance(60000);
            check(f.tasks.isEmpty()&&f.monitor.metrics.rssiReads==reads&&f.attempts==0&&!f.feedback,"invalid config spin");
            f.monitor.threshold(-80);f.advance(60000);check(f.monitor.deadline()==67000,"configuration recovery");
            f.advance(67000);check(f.attempts==1,"repaired config did not resume");
        });
        System.out.println("TOTAL "+passed+" monitor scenarios passed");
    }
}
