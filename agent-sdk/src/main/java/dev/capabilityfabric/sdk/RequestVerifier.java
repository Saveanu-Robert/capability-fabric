package dev.capabilityfabric.sdk;

import io.nats.client.Message;

import java.util.Optional;

/**
 * Decides whether this process accepts a capability request or a call for proposals. The default accepts work signed
 * by the kernel only ({@link FabricSecurity#refuseUnlessKernelRequest}).
 */
@FunctionalInterface
public interface RequestVerifier {

    /**
     * Empty to accept; otherwise the reason the message is refused. {@code subject} is the subject the request
     * arrived on; the scope it must be signed for also covers the reply seal key it names, if any
     * ({@link dev.capabilityfabric.protocol.Security#requestScope}).
     */
    Optional<String> refuse(Message message, String subject);
}
