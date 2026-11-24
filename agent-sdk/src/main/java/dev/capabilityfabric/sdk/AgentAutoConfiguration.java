package dev.capabilityfabric.sdk;

import dev.capabilityfabric.protocol.Security;
import dev.capabilityfabric.sdk.model.ChatBackend;
import dev.capabilityfabric.sdk.model.OllamaChatBackend;
import dev.capabilityfabric.sdk.model.StructuredModel;
import io.nats.client.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.time.Duration;

/** Wires a Spring Boot application into the fabric. */
@AutoConfiguration(afterName = "org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration")
@EnableConfigurationProperties(FabricProperties.class)
public class AgentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentAutoConfiguration.class);

    /** {@code fabric.agent.provider}, else the application name. */
    static String providerName(FabricProperties properties, Environment environment) {
        String configured = properties.agent().provider();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return environment.getProperty("spring.application.name", "capability-fabric-component");
    }

    /**
     * The broker connection ({@link NatsSupport#connect}), with the broker settings of the secrets folder setup
     * created when this agent's settings name none ({@link LocalSecrets}).
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public Connection natsConnection(FabricProperties properties, Environment environment) {
        FabricProperties effective = LocalSecrets.effective(properties, LocalSecrets.folder());
        if (effective != properties) {
            log.info("using the secrets folder setup created: {} with {} and {}", effective.nats().url(),
                    effective.nats().caFile(), effective.nats().seedFile());
        }
        return NatsSupport.connect(effective.nats(), providerName(properties, environment));
    }

    /**
     * This process's keys (signing and sealing), created on first start, and whom it trusts: the kernel key from
     * {@code fabric.security.kernel-key-file} when configured (or from the secrets folder, see {@link LocalSecrets}),
     * otherwise the kernel that admits this process.
     */
    @Bean
    @ConditionalOnMissingBean
    public FabricSecurity fabricSecurity(FabricProperties properties, Environment environment) {
        return fabricSecurity(LocalSecrets.effective(properties, LocalSecrets.folder()).security(),
                providerName(properties, environment));
    }

    static FabricSecurity fabricSecurity(FabricProperties.SecuritySettings settings, String provider) {
        var identity = Security.loadOrCreate(settings.keyPath(provider));
        if (!settings.enabled()) {
            return new FabricSecurity(false, identity);
        }
        var sealKey = Security.SealKey.loadOrCreate(settings.sealPath(provider));
        String kernelKey = settings.pinsKernelKey()
                ? FabricSecurity.readKernelKey(Path.of(settings.kernelKeyFile())) : null;
        return new FabricSecurity(true, identity, sealKey, kernelKey);
    }

    @Bean
    @ConditionalOnMissingBean
    public RequestVerifier requestVerifier(FabricSecurity security) {
        return security::refuseUnlessKernelRequest;
    }

    @Bean
    @ConditionalOnMissingBean
    public FabricClient fabricClient(Connection connection, FabricSecurity security) {
        return new FabricClient(connection, Duration.ofSeconds(10), security);
    }

    /** Ollama through Spring AI when a chat model is configured; otherwise a backend that always fails. */
    @Bean
    @ConditionalOnMissingBean
    public ChatBackend chatBackend(ObjectProvider<ChatModel> chatModel) {
        ChatModel model = chatModel.getIfAvailable();
        if (model == null) {
            return call -> {
                throw new IllegalStateException("no chat model configured");
            };
        }
        return new OllamaChatBackend(model);
    }

    @Bean
    @ConditionalOnMissingBean
    public StructuredModel structuredModel(ChatBackend backend, FabricClient fabric, FabricProperties properties) {
        FabricProperties.Model m = properties.model();
        return new StructuredModel(backend, fabric, m.chat(), m.temperature(), m.seed(), m.numCtx(), m.keepAlive(),
                m.thinking(), m.maxRepairs(), m.replay());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "fabric.agent", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AgentRuntime agentRuntime(Connection connection, FabricProperties properties,
                                     ObjectProvider<CapabilityHandler> handlers,
                                     ObjectProvider<ReactiveHandler> reactiveHandlers, FabricClient fabric,
                                     StructuredModel model, Environment environment, FabricSecurity security,
                                     RequestVerifier verifier) {
        return new AgentRuntime(connection, properties, handlers.orderedStream().toList(),
                reactiveHandlers.orderedStream().toList(), fabric, model,
                providerName(properties, environment), security, verifier);
    }
}
