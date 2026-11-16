package argus.common;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * Remote interface exported by each AGENT (on a VM).
 * The host receives its stub at registration time and uses it to call the VM.
 */
public interface AgentRemote extends Remote {

    /** Liveness check. Returns the VM name. */
    String ping() throws RemoteException;

    /** Names of the commands this agent accepts (whitelist). */
    List<String> availableActions() throws RemoteException;

    /**
     * Runs a whitelisted administration command on the VM.
     * @param action one of availableActions()
     * @param arg    optional argument (PID, seconds...), may be empty
     * @param token  shared secret, the command is refused if it is wrong
     */
    CommandResult execute(String action, String arg, String token) throws RemoteException;
}
