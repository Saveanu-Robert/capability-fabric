package dev.capabilityfabric.sdk;

import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.CapabilityRequest;
import dev.capabilityfabric.protocol.CapabilityResult;
import dev.capabilityfabric.protocol.Grant;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.Security;
import io.nats.client.Message;
import io.nats.client.impl.Headers;
import io.nats.client.impl.NatsMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A capability call as the kernel sends it, through the agent's verification and opening. */
class SealedCallsTest {

    private static final String SUBJECT = "cap.test.echo.v1.i.echo-1";

    private final Security.Identity kernel = Security.generate();
    private final Security.Identity agent = Security.generate();
    private final Security.SealKey seal = Security.SealKey.generate();
    private final AtomicReference<CapabilityRequest> received = new AtomicReference<>();

    private final CapabilityHandler echo = new CapabilityHandler() {
        @Override
        public CapabilityManifest manifest() {
            return CapabilityManifest.builder("test.echo", "1.0.0").description("Echoes its parameters back.").build();
        }

        @Override
        public CapabilityOutcome handle(CapabilityRequest request, InvocationContext context) {
            received.set(request);
            return CapabilityOutcome.output(request.params());
        }
    };

    private AgentRuntime runtime(FabricSecurity security) {
        var properties = new FabricProperties(new FabricProperties.Nats("nats://localhost:4222", null, null, null, null),
                new FabricProperties.Agent("echo", true, Duration.ofSeconds(5), Duration.ofSeconds(15),
                        Duration.ofMillis(100)),
                new FabricProperties.Model("live", "none", 0, 42, 4096, "1m", 0, false),
                new FabricProperties.SecuritySettings(true, null, null));
        FabricClient fabric = new FabricClient(null, Duration.ofSeconds(1), security);
        return new AgentRuntime(null, properties, List.of(echo), List.of(), fabric, null, "echo", security,
                security::refuseUnlessKernel);
    }

    private static byte[] request(String text) {
        return Json.bytes(new CapabilityRequest("r1", "test.echo", null, null, 0, null,
                Json.toTree(Map.of("text", text)), "kernel"));
    }

    /** What the kernel puts on the wire: the stamp covers the bytes sent, sealed or not. */
    private Message message(Security.Identity signer, byte[] wire, boolean sealed) {
        Headers headers = new Headers();
        signer.stamp(SUBJECT, wire).headers().forEach(headers::put);
        if (sealed) {
            headers.put(Security.HEADER_SEALED, Security.SEAL_SCHEME);
        }
        return NatsMessage.builder().subject(SUBJECT).replyTo("_INBOX_kernel.x").headers(headers).data(wire).build();
    }

    private CapabilityResult call(FabricSecurity security, Message message) {
        return runtime(security).answer(echo, echo.manifest(), message);
    }

    @Test
    void theAgentOpensWhatTheKernelSealedForIt() {
        var security = new FabricSecurity(true, agent, seal, kernel.publicKeyText());
        byte[] sealed = Security.seal(seal.publicKeyText(), SUBJECT, request("secret plans"));

        CapabilityResult result = call(security, message(kernel, sealed, true));

        assertThat(result.status()).as(result.error()).isEqualTo(CapabilityResult.Status.OK);
        assertThat(received.get().params().path("text").asString()).isEqualTo("secret plans");
        assertThat(result.output().path("text").asString()).isEqualTo("secret plans");
    }

    @Test
    void plainCallsAreAcceptedWithOrWithoutASealKey() {
        var withoutSeal = new FabricSecurity(true, agent, null, kernel.publicKeyText());
        var withSeal = new FabricSecurity(true, agent, seal, kernel.publicKeyText());

        assertThat(call(withoutSeal, message(kernel, request("plain"), false)).status())
                .isEqualTo(CapabilityResult.Status.OK);
        assertThat(call(withSeal, message(kernel, request("plain"), false)).status())
                .isEqualTo(CapabilityResult.Status.OK);
    }

    @Test
    void alteredOrMisaddressedSealedCallsAreRefused() {
        var security = new FabricSecurity(true, agent, seal, kernel.publicKeyText());
        byte[] sealed = Security.seal(seal.publicKeyText(), SUBJECT, request("x"));

        byte[] tampered = sealed.clone();
        tampered[tampered.length - 1] ^= 1;
        // Altered after signing: the stamp no longer matches.
        assertThat(refusal(security, NatsMessage.builder().subject(SUBJECT).headers(message(kernel, sealed, true)
                .getHeaders()).data(tampered).build())).contains("invalid signature");
        // Altered and signed again: the stamp holds, but the seal does not open.
        assertThat(refusal(security, message(kernel, tampered, true))).contains("cannot open the sealed request");
        // Sealed for another subject or another key.
        assertThat(refusal(security, message(kernel, Security.seal(seal.publicKeyText(), "cap.other.v1",
                request("x")), true))).contains("cannot open");
        assertThat(refusal(security, message(kernel, Security.seal(Security.SealKey.generate().publicKeyText(),
                SUBJECT, request("x")), true))).contains("cannot open");
        // Sealed for an agent that keeps no seal key.
        assertThat(refusal(new FabricSecurity(true, agent, null, kernel.publicKeyText()), message(kernel, sealed, true)))
                .contains("does not have");
        // A scheme this agent does not know.
        Headers rot13 = new Headers();
        kernel.stamp(SUBJECT, sealed).headers().forEach(rot13::put);
        rot13.put(Security.HEADER_SEALED, "rot13");
        assertThat(refusal(security, NatsMessage.builder().subject(SUBJECT).headers(rot13).data(sealed).build()))
                .contains("unknown sealing scheme");
        // The stamp must cover the sealed bytes on the wire, not the plaintext.
        Headers overPlaintext = new Headers();
        kernel.stamp(SUBJECT, request("x")).headers().forEach(overPlaintext::put);
        overPlaintext.put(Security.HEADER_SEALED, Security.SEAL_SCHEME);
        assertThat(refusal(security, NatsMessage.builder().subject(SUBJECT).headers(overPlaintext).data(sealed).build()))
                .contains("invalid signature");
        assertThat(received.get()).as("no refused call reaches the handler").isNull();
    }

    @Test
    void aPinnedKernelKeyIsTheOnlyOneTrustedFromTheStart() {
        var stranger = Security.generate();
        var pinned = new FabricSecurity(true, agent, seal, kernel.publicKeyText());

        assertThat(refusal(pinned, message(stranger, request("x"), false))).contains("not signed by the kernel");
        assertThat(call(pinned, message(kernel, request("x"), false)).status()).isEqualTo(CapabilityResult.Status.OK);

        Grant fromStranger = Grant.issue(stranger, agent.publicKeyText(),
                Grant.Rights.provider("echo", List.of("test.echo"), List.of()), null, null);
        assertThat(pinned.admittedWith(fromStranger)).isPresent();
        assertThat(pinned.kernelKey()).contains(kernel.publicKeyText());
        assertThat(pinned.grant()).isEmpty();

        Grant fromKernel = Grant.issue(kernel, agent.publicKeyText(),
                Grant.Rights.provider("echo", List.of("test.echo"), List.of()), null, null);
        assertThat(pinned.admittedWith(fromKernel)).isEmpty();
        assertThat(pinned.grant()).contains(fromKernel);
    }

    @Test
    void withoutAPinTheAdmittingKernelIsTrustedOnlyForAGrantMeantForThisKey() {
        var learning = new FabricSecurity(true, agent, seal, null);
        assertThat(refusal(learning, message(kernel, request("x"), false))).contains("not admitted yet");

        Grant forSomeoneElse = Grant.issue(kernel, Security.generate().publicKeyText(),
                Grant.Rights.provider("echo", List.of("test.echo"), List.of()), null, null);
        assertThat(learning.admittedWith(forSomeoneElse)).contains("the grant is for another key");
        Grant expired = Grant.issue(kernel, agent.publicKeyText(),
                Grant.Rights.provider("echo", List.of("test.echo"), List.of()), Instant.now().minusSeconds(5), null);
        assertThat(learning.admittedWith(expired)).hasValueSatisfying(r -> assertThat(r).contains("ended"));
        assertThat(learning.kernelKey()).isEmpty();

        Grant good = Grant.issue(kernel, agent.publicKeyText(),
                Grant.Rights.provider("echo", List.of("test.echo"), List.of()), null, null);
        assertThat(learning.admittedWith(good)).isEmpty();
        assertThat(learning.kernelKey()).contains(kernel.publicKeyText());
        assertThat(call(learning, message(kernel, request("x"), false)).status()).isEqualTo(CapabilityResult.Status.OK);
    }

    @Test
    void aPinnedKeyMustBeAKey() {
        assertThatThrownBy(() -> new FabricSecurity(true, agent, null, "garbage"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(FabricSecurity.selfTrusted(true, kernel).kernelKeyPinned()).isTrue();
        assertThat(new FabricSecurity(true, agent, seal, null).sealKeyText()).isEqualTo(seal.publicKeyText());
        assertThat(new FabricSecurity(false, agent, seal, null).sealKeyText()).as("security off: nothing advertised")
                .isNull();
    }

    private String refusal(FabricSecurity security, Message message) {
        CapabilityResult result = call(security, message);
        assertThat(result.status()).isEqualTo(CapabilityResult.Status.ERROR);
        return result.error();
    }
}
