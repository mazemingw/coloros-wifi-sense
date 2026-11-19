import java.util.Arrays;
import java.util.Collections;

public final class WifiNetworkSelectorTest {
    static int passed;
    static final String A="10:20:30:40:50:60", B="10:20:30:40:50:61";
    static WifiNetworkSelector.Candidate<String> c(String n,String b,Boolean p) {
        return new WifiNetworkSelector.Candidate<>(n,b,p);
    }
    @SafeVarargs static String pick(String b,WifiNetworkSelector.Candidate<String>... candidates) {
        return WifiNetworkSelector.select(Arrays.asList(candidates),b);
    }
    static void test(String name,boolean ok) {
        if(!ok)throw new AssertionError(name);
        passed++;System.out.println("PASS "+name);
    }
    public static void main(String[] args) {
        test("no Wi-Fi has no target",WifiNetworkSelector.select(Collections.emptyList(),A)==null);
        test("single legacy network remains supported","main".equals(pick(A,c("main",null,null))));
        test("single matching BSSID remains supported","main".equals(pick(A,c("main",A,null))));
        test("single stale BSSID cannot target root connection",pick(A,c("stale",B,null))==null);
        test("dual WLAN selects primary independently of iteration order","main".equals(pick(A,c("aux",B,false),c("main",A,true)))
            &&"main".equals(pick(A,c("main",A,true),c("aux",B,false))));
        test("redacted dual BSSIDs use primary role","main".equals(pick(A,c("aux","02:00:00:00:00:00",false),c("main","02:00:00:00:00:00",true))));
        test("legacy dual WLAN uses exact BSSID","main".equals(pick(A,c("aux",B,null),c("main",A,null))));
        test("BSSID matching is case insensitive","main".equals(pick("aa:bb:cc:dd:ee:ff",c("main","AA:BB:CC:DD:EE:FF",null),c("aux",B,null))));
        test("ambiguous redacted legacy dual WLAN is rejected",pick(A,c("a",null,null),c("b",null,null))==null);
        test("duplicate primary flags are rejected",pick(A,c("a",null,true),c("b",null,true))==null);
        test("root and explicit primary mismatch is rejected",pick(A,c("old",A,false),c("new",B,true))==null);
        test("known secondary cannot be chosen as primary",pick(A,c("aux",A,false))==null);
        test("duplicate legacy BSSID is rejected",pick(A,c("a",A,null),c("b",A,null))==null);
        test("primary promotion follows the new role","aux".equals(pick(B,c("main",A,false),c("aux",B,true))));
        test("cached role can select a redacted primary without extra Binder reads","main".equals(pick(null,c("aux",null,false),c("main",null,true))));
        System.out.println("TOTAL "+passed+" network selection scenarios passed");
    }
}
