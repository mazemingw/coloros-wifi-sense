import java.net.URI;
import java.util.Objects;

/** Validated opt-in settings, shared by the CLI, daemon and host tests. */
final class WifiConnectionConfig {
    static final String DEFAULT_URL="http://connect.rom.miui.com/generate_204";
    final boolean avoidEnabled, probeEnabled;
    final int avoidSeconds, probeInterval;
    final String probeUrl;
    WifiConnectionConfig(boolean avoid,int seconds,boolean probe,int interval,String url) {
        if(seconds<10||seconds>86400)throw new IllegalArgumentException("SSID 暂避时间必须是 10–86400 秒。");
        if(interval<1||interval>3600)throw new IllegalArgumentException("探测间隔必须是 1–3600 秒。");
        try {
            URI u=new URI(url);
            if(url.length()>2048 || (!"http".equals(u.getScheme())&&!"https".equals(u.getScheme()))
                    ||u.getHost()==null||u.getUserInfo()!=null||u.getFragment()!=null)
                throw new IllegalArgumentException();
        } catch(Exception e){throw new IllegalArgumentException("探测地址必须是有效的 HTTP/HTTPS 地址，不能包含账号密码或片段。");}
        avoidEnabled=avoid;avoidSeconds=seconds;probeEnabled=probe;probeInterval=interval;probeUrl=url;
    }
    static WifiConnectionConfig defaults(){return new WifiConnectionConfig(false,300,false,30,DEFAULT_URL);}
    boolean sameProbe(WifiConnectionConfig c){return probeEnabled==c.probeEnabled&&probeInterval==c.probeInterval&&Objects.equals(probeUrl,c.probeUrl);}
    @Override public boolean equals(Object value){
        if(!(value instanceof WifiConnectionConfig))return false;
        WifiConnectionConfig c=(WifiConnectionConfig)value;
        return avoidEnabled==c.avoidEnabled&&avoidSeconds==c.avoidSeconds&&probeEnabled==c.probeEnabled
            &&probeInterval==c.probeInterval&&Objects.equals(probeUrl,c.probeUrl);
    }
    @Override public int hashCode(){return Objects.hash(avoidEnabled,avoidSeconds,probeEnabled,probeInterval,probeUrl);}
}
