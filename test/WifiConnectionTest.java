import java.io.IOException;
import java.util.*;

public final class WifiConnectionTest {
    static int passed;
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    interface Case {void run()throws Exception;}
    static void test(String name,Case c)throws Exception{c.run();passed++;System.out.println("PASS "+name);}
    static class Fake implements WifiSsidGuard.Port {
        long now;int writes,mutations;boolean failSave,failDisable,failRestore,ignore;
        List<WifiSsidGuard.Config> configs=new ArrayList<>();List<WifiSsidGuard.Record> journal=new ArrayList<>();
        final WifiSsidGuard guard=new WifiSsidGuard(this);
        void add(int id,String ssid,boolean on){configs.add(new WifiSsidGuard.Config(id,ssid,ssid+":WPA2",on));}
        public long now(){return now;}
        public List<WifiSsidGuard.Config> configs(){return new ArrayList<>(configs);}
        public List<WifiSsidGuard.Record> load(){return new ArrayList<>(journal);}
        public void save(List<WifiSsidGuard.Record> records)throws Exception{if(failSave)throw new IOException("disk full");writes++;journal=new ArrayList<>(records);}
        public void autojoin(int id,boolean value)throws Exception {
            check(!journal.isEmpty(),"mutation before durable undo");
            if(!value&&failDisable||value&&failRestore)throw new IOException("binder rejected");
            mutations++;if(ignore)return;
            for(int i=0;i<configs.size();i++){WifiSsidGuard.Config c=configs.get(i);if(c.id==id)configs.set(i,new WifiSsidGuard.Config(c.id,c.ssid,c.key,value));}
        }
        boolean on(int id){for(WifiSsidGuard.Config c:configs)if(c.id==id)return c.autojoin;throw new AssertionError();}
    }
    public static void main(String[] args)throws Exception {
        test("defaults opt out of network requests and SSID mutation",()->{WifiConnectionConfig c=WifiConnectionConfig.defaults();check(!c.probeEnabled&&!c.avoidEnabled&&c.avoidSeconds==300,"defaults");});
        test("invalid durations and probe URLs rejected",()->{
            int rejected=0;for(String url:new String[]{"file:///data/a","http://user:pass@example.com/","http://example.com/#x","garbage"})try{new WifiConnectionConfig(true,300,true,30,url);}catch(IllegalArgumentException e){rejected++;}
            for(int seconds:new int[]{0,9,86401})try{new WifiConnectionConfig(true,seconds,false,30,WifiConnectionConfig.DEFAULT_URL);}catch(IllegalArgumentException e){rejected++;}
            check(rejected==7,"validation");
        });
        test("one successful request ends connection batch forever",()->{
            WifiProbePolicy p=new WifiProbePolicy();p.connection("net","ssid");check(p.begin(),"start");check(!p.result("net","ssid",true),"success cut");
            for(int i=0;i<100;i++)check(!p.begin(),"success restarted");
        });
        test("two failures authorize exactly once and never poll again",()->{
            WifiProbePolicy p=new WifiProbePolicy();p.connection("net","ssid");p.begin();check(!p.result("net","ssid",false),"first failure");p.begin();check(p.result("net","ssid",false),"second failure");
            check(p.complete()&&!p.begin()&&!p.result("net","ssid",false),"repeated cut");
        });
        test("success after one failure ends without disconnect",()->{
            WifiProbePolicy p=new WifiProbePolicy();p.connection("n","s");p.begin();p.result("n","s",false);p.begin();check(!p.result("n","s",true)&&!p.begin(),"retry success");
        });
        test("cancelled requests still count toward two-request limit",()->{
            WifiProbePolicy p=new WifiProbePolicy();p.connection("n","s");check(p.begin()&&p.begin()&&!p.begin(),"cancellation unbounded");
        });
        test("screen-off invalidates previous failure without increasing request budget",()->{
            WifiProbePolicy p=new WifiProbePolicy();p.connection("n","s");p.begin();p.result("n","s",false);p.suspend();p.begin();
            check(!p.result("n","s",false)&&p.complete()&&!p.begin(),"old failure reused");
        });
        test("new connection resets completed batch, same connection does not",()->{
            WifiProbePolicy p=new WifiProbePolicy();p.connection("n","s");p.begin();p.result("n","s",true);p.connection("n","s");check(!p.begin(),"same restarted");p.connection("n2","s");check(p.begin(),"new not reset");
        });
        test("unrelated avoid duration change does not restart probe",()->{
            WifiConnectionConfig a=WifiConnectionConfig.defaults(),b=new WifiConnectionConfig(true,600,false,30,a.probeUrl);check(a.sameProbe(b)&&!a.equals(b),"settings scope");
        });
        test("all saved configs for same SSID held, other SSIDs untouched",()->{
            Fake f=new Fake();f.add(1,"campus",true);f.add(2,"campus",true);f.add(3,"home",true);f.guard.block("campus",300);
            check(!f.on(1)&&!f.on(2)&&f.on(3)&&f.journal.size()==2,"SSID scope");
        });
        test("expiry restores precisely modified entries",()->{
            Fake f=new Fake();f.add(1,"s",true);f.add(2,"s",false);f.guard.block("s",10);f.now=9999;f.guard.restore(false);check(!f.on(1),"early restore");f.now=10000;f.guard.restore(false);check(f.on(1)&&!f.on(2)&&f.journal.isEmpty(),"expiry/original false");
        });
        test("off switch restores before expiry",()->{Fake f=new Fake();f.add(1,"s",true);f.guard.block("s",300);f.guard.restore(true);check(f.on(1)&&f.journal.isEmpty(),"off restore");});
        test("disk failure causes no Android mutation",()->{Fake f=new Fake();f.add(1,"s",true);f.failSave=true;try{f.guard.block("s",30);throw new AssertionError();}catch(IOException e){}check(f.mutations==0&&f.on(1),"unrecoverable write");});
        test("rejected restriction rolls back and retains original config",()->{Fake f=new Fake();f.add(1,"s",true);f.failDisable=true;try{f.guard.block("s",30);throw new AssertionError();}catch(IOException e){}check(f.on(1)&&f.journal.isEmpty(),"rollback");});
        test("ignored asynchronous mutation fails readback",()->{Fake f=new Fake();f.add(1,"s",true);f.ignore=true;try{f.guard.block("s",30);throw new AssertionError();}catch(IOException e){}check(f.on(1)&&f.journal.isEmpty(),"readback");});
        test("stale final preflight lease can undo only new restrictions",()->{Fake f=new Fake();f.add(1,"old",true);f.add(2,"new",true);f.guard.block("old",30);WifiSsidGuard.Lease l=f.guard.block("new",30);f.guard.rollback(l);check(!f.on(1)&&f.on(2)&&f.journal.size()==1,"lease rollback");});
        test("network ID reuse never enables unrelated saved network",()->{Fake f=new Fake();f.add(1,"s",true);f.guard.block("s",30);f.configs.clear();f.add(1,"other",false);f.guard.restore(true);check(!f.on(1)&&f.journal.isEmpty(),"ID reuse");});
        test("failed restore preserves journal and can retry",()->{Fake f=new Fake();f.add(1,"s",true);f.guard.block("s",30);f.failRestore=true;try{f.guard.restore(true);throw new AssertionError();}catch(IOException e){}check(f.journal.size()==1&&!f.on(1),"lost recovery");f.failRestore=false;f.guard.restore(true);check(f.on(1)&&f.journal.isEmpty(),"recovery");});
        test("boot recovery handles reassigned network ID",()->{Fake f=new Fake();f.add(1,"s",true);f.guard.block("s",30);f.configs.clear();f.add(7,"s",false);f.guard.restore(true);check(f.on(7)&&f.journal.isEmpty(),"new ID");});
        System.out.println("TOTAL "+passed+" connection scenarios passed");
    }
}
