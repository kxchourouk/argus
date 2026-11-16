package argus.common;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Remote interface exported by the HOST (central server).
 * Agents running on the VMs look it up in the RMI registry and call it.
 */
public interface MonitorServer extends Remote {

    /** Name under which the server is bound in the RMI registry. */
    String BINDING_NAME = "MonitorServer";

    /**
     * Registers a VM. The agent passes its own remote object (callback) so the
     * host can later call the VM (ping, remote commands).
     * @return true if accepted (valid token, unique name)
     */
    boolean register(AgentInfo info, AgentRemote callback, String token) throws RemoteException;

    /**
     * Pushes one metrics snapshot. Also acts as a heartbeat.
     * @return false if the server does not know this VM (e.g. the server was
     *         restarted): the agent must then register again.
     */
    boolean pushMetrics(Metrics metrics, String token) throws RemoteException;

    /** Clean shutdown of an agent (distinguishes a stop from a crash). */
    void unregister(String vmName, String token) throws RemoteException;
}
