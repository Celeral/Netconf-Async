package com.tailf.jnc;

/**
 * A NETCONF transport interface. This interface can be used to write custom
 * NETCONF transport mechanisms. The {@link NetconfSession} constructor takes a
 * transport mechanism to be responsible for the actual sending and receiving
 * of NETCONF protocol messages over the wire. {@link SSHSession} implements
 * the Transport interface.
 * 
 * @see SSHSession
 */
public interface Transport extends InTransport, OutTransport {
    /**
     * Closes the Transport session/connection.
     */
    void close();
}