import java.net.URI;
import java.util.Objects;
import java.util.List;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.nio.charset.StandardCharsets;

/** Validated opt-in settings, shared by the CLI, daemon and host tests. */
final class WifiConnectionConfig {
    static final String DEFAULT_URL="http://connect.rom.miui.com/generate_204";
    final boolean avoidEnabled, probeEnabled, screenOffEnabled;
    final int avoidSeconds, probeInterval, recoveryDb;
    final int weakPauseMinutes;
    final long weakPauseUntil;
    final String probeUrl;
    final List<String> cooldownExemptSsids;
    WifiConnectionConfig(boolean avoid,int seconds,boolean probe,int interval,String url,boolean screenOff,int recoveryDb) {
        this(avoid,seconds,probe,interval,url,screenOff,recoveryDb,30,0);
    }
    WifiConnectionConfig(boolean avoid,int seconds,boolean probe,int interval,String url,boolean screenOff,
            int recoveryDb,int weakPauseMinutes,long weakPauseUntil) {
        this(avoid,seconds,probe,interval,url,screenOff,recoveryDb,weakPauseMinutes,weakPauseUntil,Collections.emptyList());
    }
    WifiConnectionConfig(boolean avoid,int seconds,boolean probe,int interval,String url,boolean screenOff,
            int recoveryDb,int weakPauseMinutes,long weakPauseUntil,List<String> exemptSsids) {
        if(seconds<10||seconds>86400)throw new IllegalArgumentException("SSID 暂避时间必须是 10–86400 秒。");
        if(interval<1||interval>3600)throw new IllegalArgumentException("探测间隔必须是 1–3600 秒。");
        if (recoveryDb < 1 || recoveryDb > WifiDisconnectPolicy.MAX_RECOVERY_DB) {
            throw new IllegalArgumentException("回升余量须为 1–10 dB。");
        }
        if (weakPauseMinutes < 1 || weakPauseMinutes > 1440 || weakPauseUntil < 0) {
            throw new IllegalArgumentException("暂停时长须为 1–1440 分钟。");
        }
        try {
            URI u=new URI(url);
            if(url.length()>2048 || (!"http".equals(u.getScheme())&&!"https".equals(u.getScheme()))
                    ||u.getHost()==null||u.getUserInfo()!=null||u.getFragment()!=null)
                throw new IllegalArgumentException();
        } catch(Exception e){throw new IllegalArgumentException("探测地址必须是有效的 HTTP/HTTPS 地址，不能包含账号密码或片段。");}
        avoidEnabled=avoid;avoidSeconds=seconds;probeEnabled=probe;probeInterval=interval;probeUrl=url;
        screenOffEnabled=screenOff;this.recoveryDb=recoveryDb;
        this.weakPauseMinutes=weakPauseMinutes;this.weakPauseUntil=weakPauseUntil;
        if(exemptSsids==null||exemptSsids.size()>32)throw new IllegalArgumentException("冷却例外最多填写 32 个 Wi-Fi 名称。");
        LinkedHashSet<String> names=new LinkedHashSet<>();
        for(String name:exemptSsids) {
            if(name==null||name.isEmpty()||name.getBytes(StandardCharsets.UTF_8).length>32
                ||name.indexOf('\n')>=0||name.indexOf('\r')>=0||name.indexOf('\0')>=0)
                throw new IllegalArgumentException("每个 Wi-Fi 名称须为 1–32 字节，不能包含换行。");
            names.add(name);
        }
        cooldownExemptSsids=Collections.unmodifiableList(new ArrayList<>(names));
    }
    static WifiConnectionConfig defaults(){
        return new WifiConnectionConfig(false,300,false,30,DEFAULT_URL,false,WifiDisconnectPolicy.DEFAULT_RECOVERY_DB);
    }
    boolean sameProbe(WifiConnectionConfig c){return probeEnabled==c.probeEnabled&&probeInterval==c.probeInterval&&Objects.equals(probeUrl,c.probeUrl);}
    boolean cooldownExempt(String androidSsid) {
        return cooldownExemptSsids.contains(ssidName(androidSsid));
    }
    static String ssidName(String androidSsid) {
        if(androidSsid==null||"<unknown ssid>".equals(androidSsid))return null;
        return androidSsid.length()>=2&&androidSsid.startsWith("\"")&&androidSsid.endsWith("\"")
            ? androidSsid.substring(1,androidSsid.length()-1):androidSsid;
    }
    @Override public boolean equals(Object value){
        if(!(value instanceof WifiConnectionConfig))return false;
        WifiConnectionConfig c=(WifiConnectionConfig)value;
        return avoidEnabled==c.avoidEnabled&&avoidSeconds==c.avoidSeconds&&probeEnabled==c.probeEnabled
            &&probeInterval==c.probeInterval&&Objects.equals(probeUrl,c.probeUrl)
            &&screenOffEnabled==c.screenOffEnabled&&recoveryDb==c.recoveryDb
            &&weakPauseMinutes==c.weakPauseMinutes&&weakPauseUntil==c.weakPauseUntil
            &&cooldownExemptSsids.equals(c.cooldownExemptSsids);
    }
    @Override public int hashCode(){return Objects.hash(avoidEnabled,avoidSeconds,probeEnabled,probeInterval,probeUrl,
        screenOffEnabled,recoveryDb,weakPauseMinutes,weakPauseUntil,cooldownExemptSsids);}
}
