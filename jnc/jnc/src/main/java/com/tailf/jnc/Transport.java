package com.tailf.jnc;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * A NETCONF transport interface. This interface can be used to write custom
 * NETCONF transport mechanisms. The {@link NetconfSession} constructor takes a
 * transport mechanism to be responsible for the actual sending and receiving
 * of NETCONF protocol messages over the wire. {@link SSHSession} and
 * {@link TCPSession} implements the Transport interface.
 * 
 * @see SSHSession
 * @see TCPSession
 * 
 */
public interface Transport
{
    /**
     * Reads "one" reply from the transport input stream.
     */
    String readOne(long timeout, TimeUnit timeUnit) throws IOException;
}
