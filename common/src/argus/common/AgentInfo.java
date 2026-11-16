package argus.common;

import java.io.Serializable;

/** Static description of a VM, sent once when the agent registers. */
public class AgentInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    public String name;        // e.g. "vm1-ubuntu"
    public String address;     // IP the host uses to call back the agent
    public String os;          // e.g. "Linux 6.8 (amd64)"
    public int cores;
    public long totalRamMb;
    public String javaVersion;

    @Override
    public String toString() {
        return name + " @" + address + " [" + os + ", " + cores + " cores, " + totalRamMb + " MB]";
    }
}
