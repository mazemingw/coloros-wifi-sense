import java.io.IOException;
import java.util.*;

/** Temporary per-SSID autojoin transaction; stores only original-true entries. */
final class WifiSsidGuard {
    static final class Config {
        final int id;final String ssid,key;final boolean autojoin;
        Config(int id,String ssid,String key,boolean autojoin){this.id=id;this.ssid=ssid;this.key=key;this.autojoin=autojoin;}
    }
    static final class Record {
        final Config original;final long until;
        Record(Config original,long until){this.original=original;this.until=until;}
    }
    interface Port {
        long now();
        List<Config> configs() throws Exception;
        void autojoin(int id,boolean value) throws Exception;
        List<Record> load() throws Exception;
        void save(List<Record> records) throws Exception;
    }
    static final class Lease {
        final List<Record> previous;final List<Config> changed;
        Lease(List<Record> previous,List<Config> changed){this.previous=previous;this.changed=changed;}
    }
    private final Port port;
    WifiSsidGuard(Port port){this.port=port;}
    private static boolean same(Config a,Config b){return a.id==b.id&&Objects.equals(a.ssid,b.ssid)&&Objects.equals(a.key,b.key);}
    private static Config find(List<Config> configs,Config target){for(Config c:configs)if(same(c,target))return c;return null;}
    private static Config recover(List<Config> configs,Config target)throws IOException {
        Config exact=find(configs,target);if(exact!=null)return exact;
        // Android can reassign network IDs on reboot; only a unique saved profile may be restored.
        Config match=null;
        for(Config c:configs)if(Objects.equals(c.ssid,target.ssid)&&Objects.equals(c.key,target.key)) {
            if(match!=null)throw new IOException("同名网络恢复身份不明确，已保留还原记录。");
            match=c;
        }
        return match;
    }

    Lease block(String ssid,int seconds) throws Exception {
        if(ssid==null||ssid.isEmpty()||"<unknown ssid>".equals(ssid))throw new IOException("SSID 未识别，不能设置暂避。");
        if(seconds<10||seconds>86400)throw new IOException("Invalid hold duration");
        List<Record> before=port.load(),next=new ArrayList<>(before);List<Config> changed=new ArrayList<>();
        List<Config> configs=port.configs();long until=port.now()+seconds*1000L;
        for(Config c:configs)if(ssid.equals(c.ssid)&&c.autojoin) {
            changed.add(c);next.removeIf(r->same(r.original,c));next.add(new Record(c,until));
        }
        if(changed.isEmpty())throw new IOException("没有可暂避的已保存网络，或该网络本来就未开启自动连接。");
        port.save(next); // A durable undo record is mandatory before any Binder change.
        Lease lease=new Lease(before,changed);
        try {
            for(Config c:changed)port.autojoin(c.id,false);
            List<Config> actual=port.configs();
            for(Config c:changed){Config a=find(actual,c);if(a==null||a.autojoin)throw new IOException("系统未确认 SSID 自动连接暂避。");}
            return lease;
        } catch(Exception error){try{rollback(lease);}catch(Exception restore){error.addSuppressed(restore);}throw error;}
    }
    void rollback(Lease lease) throws Exception {
        List<Config> actual=port.configs();
        for(Config c:lease.changed){Config a=find(actual,c);if(a!=null&&!a.autojoin)port.autojoin(c.id,true);}
        actual=port.configs();
        for(Config c:lease.changed){Config a=find(actual,c);if(a!=null&&!a.autojoin)throw new IOException("自动连接还原未确认，恢复记录已保留。");}
        port.save(lease.previous);
    }
    void restore(boolean all) throws Exception {
        List<Record> records=port.load();if(records.isEmpty())return;
        List<Config> configs=port.configs();List<Record> keep=new ArrayList<>();Exception failure=null;
        for(Record r:records) {
            if(!all&&r.until>port.now()){keep.add(r);continue;}
            try {
                Config c=recover(configs,r.original);
                if(c==null||c.autojoin)continue; // forgotten/replaced/manual override; never enable an unrelated ID
                port.autojoin(c.id,true);Config readback=find(port.configs(),c);
                if(readback!=null&&!readback.autojoin)throw new IOException("系统未确认恢复自动连接。");
            } catch(Exception e){keep.add(r);failure=e;}
        }
        port.save(keep);if(failure!=null)throw failure;
    }
    long nextExpiry() throws Exception {
        long next=Long.MAX_VALUE;for(Record r:port.load())next=Math.min(next,r.until);return next;
    }
    int size() throws Exception{return port.load().size();}
}
