package argus.common;

import java.io.Serializable;

/** Result of a remote command executed on a VM. */
public class CommandResult implements Serializable {
    private static final long serialVersionUID = 1L;

    public String vmName;
    public String action;
    public boolean success;
    public String output;
    public long durationMs;

    public static CommandResult of(String vm, String action, boolean ok, String out, long ms) {
        CommandResult r = new CommandResult();
        r.vmName = vm; r.action = action; r.success = ok; r.output = out; r.durationMs = ms;
        return r;
    }
}
