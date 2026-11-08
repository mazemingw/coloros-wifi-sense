import java.io.IOException;

public final class WifiBandPolicyTest {
    static final class Fake implements WifiBandPolicy.Port {
        String boot="boot-a";int mask=7,sets;boolean awake=true,enabled,failBackup,failPref,failSet,failGet,wrongRead;
        WifiBandPolicy.Backup backup;
        public String boot(){return boot;}
        public String iface(){return "wlan0";}
        public boolean interactive(){return awake;}
        public boolean enabled(){return enabled;}
        public void enabled(boolean value)throws IOException{if(failPref)throw new IOException("disk");enabled=value;}
        public WifiBandPolicy.Backup backup(){return backup;}
        public void backup(WifiBandPolicy.Backup b)throws IOException{if(failBackup)throw new IOException("disk");backup=b;}
        public int get(String iface)throws IOException{if(failGet)throw new IOException("unavailable");if(wrongRead&&sets==1){wrongRead=false;return 2;}return mask;}
        public void set(String iface,int value)throws IOException{sets++;if(failSet)throw new IOException("driver");mask=value;}
        WifiBandPolicy policy(){return new WifiBandPolicy(this);}
    }
    static int passed;
    interface Check{void run()throws Exception;}
    static void test(String name,Check c)throws Exception{c.run();passed++;System.out.println("PASS "+name);}
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    static void failure(Check c)throws Exception{try{c.run();throw new AssertionError("expected failure");}catch(IOException expected){}}
    public static void main(String[] args)throws Exception{
        test("band enable saves original before applying",()->{Fake f=new Fake();f.policy().enable();check(f.mask==1&&f.enabled&&f.backup.mask==7);});
        test("band off restores exactly the original mask",()->{Fake f=new Fake();f.mask=3;f.policy().enable();f.policy().disable(false);check(f.mask==3&&!f.enabled&&f.backup==null);});
        test("screen-off cannot enable band restriction",()->{Fake f=new Fake();f.awake=false;failure(()->f.policy().enable());check(f.sets==0&&!f.enabled&&f.backup==null);});
        test("backup write failure performs no driver write",()->{Fake f=new Fake();f.failBackup=true;failure(()->f.policy().enable());check(f.sets==0&&f.mask==7);});
        test("preference write failure rolls driver back",()->{Fake f=new Fake();f.failPref=true;failure(()->f.policy().enable());check(f.mask==7&&f.backup==null&&!f.enabled);});
        test("driver readback mismatch rolls back",()->{Fake f=new Fake();f.wrongRead=true;failure(()->f.policy().enable());check(f.mask==7&&f.backup==null&&!f.enabled);});
        test("restore failure retains recovery record",()->{Fake f=new Fake();f.policy().enable();f.failSet=true;failure(()->f.policy().disable(false));check(f.enabled&&f.backup!=null);});
        test("release restores while keeping requested preference",()->{Fake f=new Fake();f.policy().enable();f.awake=false;f.policy().disable(true);check(f.mask==7&&f.enabled&&f.backup==null);});
        test("new boot never restores a stale mask",()->{Fake f=new Fake();f.policy().enable();f.boot="boot-b";f.mask=3;f.policy().disable(false);check(f.mask==3&&f.sets==1&&!f.enabled);});
        test("external band changes are not overwritten on release",()->{Fake f=new Fake();f.policy().enable();f.mask=2;f.policy().disable(false);check(f.mask==2&&f.sets==1&&!f.enabled);});
        test("reconnect rebases backup after driver reset",()->{Fake f=new Fake();f.policy().enable();f.mask=3;f.policy().reconcile();f.policy().disable(false);check(f.mask==3&&!f.enabled);});
        test("already restricted band preserves previous restriction",()->{Fake f=new Fake();f.mask=1;f.policy().enable();f.policy().disable(false);check(f.mask==1&&f.sets==0);});
        test("default off reconciliation has no driver I/O",()->{Fake f=new Fake();f.policy().reconcile();check(f.sets==0&&f.backup==null);});
        test("off request persists when interface is unavailable, recovery record retained",()->{Fake f=new Fake();f.policy().enable();f.failGet=true;failure(()->f.policy().disable(false));check(!f.enabled&&f.backup!=null);f.failGet=false;f.policy().reconcile();check(f.mask==7&&f.backup==null);});
        System.out.println("TOTAL "+passed+" band transaction scenarios passed");
    }
}
