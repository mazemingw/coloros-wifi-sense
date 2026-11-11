import java.util.Objects;

/** One finite batch per Wi-Fi connection: success stops; two failures stop and authorize. */
final class WifiProbePolicy {
    private Object network;
    private String ssid;
    private int failures;
    private int attempts;
    private boolean complete;
    int failures(){return failures;}
    boolean complete(){return complete;}
    boolean sameNetwork(Object n){return Objects.equals(network,n);}
    boolean begin(){if(complete||attempts>=2){complete=true;return false;}attempts++;return true;}
    void finish(){complete=true;}
    void suspend(){failures=0;if(attempts>=2)complete=true;}
    void connection(Object n,String name) {
        if(!Objects.equals(network,n)||!Objects.equals(ssid,name)){reset();network=n;ssid=name;}
    }
    boolean result(Object n,String ap,boolean online) {
        connection(n,ap);
        if(complete)return false;
        if(online){failures=0;complete=true;return false;}
        failures++;
        complete=attempts>=2;
        return failures>=2;
    }
    void reset(){network=null;ssid=null;failures=0;attempts=0;complete=false;}
}
