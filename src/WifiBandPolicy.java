import java.io.IOException;

/** Transaction rules, independent of Android and the vendor transport. */
final class WifiBandPolicy {
    static final class Backup {
        final String boot, iface;
        final int mask;
        Backup(String boot, String iface, int mask) { this.boot=boot; this.iface=iface; this.mask=mask; }
    }
    interface Port {
        String boot() throws IOException;
        String iface() throws IOException;
        boolean interactive();
        boolean enabled() throws IOException;
        void enabled(boolean value) throws IOException;
        Backup backup() throws IOException;
        void backup(Backup value) throws IOException;
        int get(String iface) throws IOException;
        void set(String iface, int mask) throws IOException;
    }
    private final Port port;
    WifiBandPolicy(Port port) { this.port=port; }

    private Backup currentBackup() throws IOException {
        Backup b=port.backup();
        if (b!=null && !b.boot.equals(port.boot())) { port.backup(null); return null; }
        return b;
    }
    void enable() throws IOException {
        if (!port.interactive()) throw new IOException("请亮屏后切换频段。");
        Backup old=currentBackup();
        String iface=old==null?port.iface():old.iface;
        int before=port.get(iface);
        Backup saved=old;
        // Rebase after a driver reset/external change, preserving the new baseline.
        if (saved==null || before!=1) {
            saved=new Backup(port.boot(),iface,before); port.backup(saved);
        }
        boolean touched=false;
        try {
            if (!port.interactive()) throw new IOException("屏幕已关闭，未切换频段。");
            if (before!=1) { touched=true; port.set(iface,1); }
            if (port.get(iface)!=1) throw new IOException("驱动未确认仅 5GHz，设置未生效。");
            port.enabled(true);
        } catch (IOException error) {
            // Restoration is cleanup, even if the screen went off mid-command.
            try {
                if (touched) { port.set(iface,before); if(port.get(iface)!=before)throw new IOException("还原校验失败"); }
                port.backup(old);
            } catch (IOException restore) { error.addSuppressed(restore); }
            throw error;
        }
    }
    void disable(boolean keepPreference) throws IOException {
        Backup b=currentBackup();
        if (b!=null) {
            int current;
            try { current=port.get(b.iface); }
            catch(IOException error) {
                if(!keepPreference) {
                    port.enabled(false);
                    throw new IOException("已关闭仅 5GHz 请求，驱动暂不可读；Wi-Fi 恢复后继续还原。",error);
                }
                throw error;
            }
            // Do not overwrite a restriction changed by another owner.
            if (current==1 && b.mask!=1) {
                port.set(b.iface,b.mask);
                if(port.get(b.iface)!=b.mask)throw new IOException("原频段尚未还原，请重试关闭。");
            }
            if (!keepPreference) port.enabled(false);
            port.backup(null);
        } else if (!keepPreference) port.enabled(false);
    }
    void reconcile() throws IOException {
        if (port.enabled()) enable(); else disable(true);
    }
}
