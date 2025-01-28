package com.janadri.processors.nats;

import io.nats.client.*;
import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.impl.Headers;
import io.nats.client.impl.NatsMessage;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.OutputStreamCallback;
import org.apache.nifi.processor.util.StandardValidators;
import io.nats.client.Dispatcher;

@SupportsBatching
@Tags({"NATS", "Messaging", "Get", "Ingest", "Ingress", "Topic", "PubSub", "Receive"})
@CapabilityDescription("Fetches messages from a NATS Messaging Topic. Supports protobuf-encoded messages and NATS headers.")
@WritesAttributes({
        @WritesAttribute(attribute = "nats.topic", description = "The NATS topic from which the message was received"),
        @WritesAttribute(attribute = "nats.header.*", description = "Any headers present in the NATS message will be added with this prefix")
})
public class GetNatsWithHeaders extends AbstractNatsProcessor {

    public static final String HEADER_ATTRIBUTE = "nats.header";


    public static final PropertyDescriptor TOPIC = new PropertyDescriptor.Builder()
            .name("Topic Name")
            .description("The NATS Topic to subscribe to")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor BATCH_SIZE = new PropertyDescriptor.Builder()
            .name("Batch Size")
            .description("Maximum number of messages to receive in a single batch")
            .required(true)
            .defaultValue("1")
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .build();

    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("FlowFiles containing received NATS messages are routed here")
            .build();

    private Connection natsConnection;
    private Dispatcher dispatcher;  // Remove the Subscription field since we're using Dispatcher
    private final BlockingQueue<Message> messageQueue;
    private volatile boolean isShutdown = false;

    public GetNatsWithHeaders() {
        messageQueue = new LinkedBlockingQueue<>();
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        final List<PropertyDescriptor> properties = new ArrayList<>();
        properties.add(SEED_BROKERS);
        properties.add(TOPIC);
        properties.add(BATCH_SIZE);
        return properties;
    }

    @Override
    public Set<Relationship> getRelationships() {
        final Set<Relationship> relationships = new HashSet<>();
        relationships.add(REL_SUCCESS);
        return relationships;
    }

    @OnScheduled
    public void onScheduled(final ProcessContext context) throws ProcessException {
        try {
            Options.Builder optionsBuilder = new Options.Builder()
                    .server(context.getProperty(SEED_BROKERS).getValue());

            optionsBuilder.connectionListener(new ConnectionListener() {
                @Override
                public void connectionEvent(Connection conn, Events type) {
                    getLogger().info("NATS connection status changed: {}", type);
                }
            });

            natsConnection = Nats.connect(optionsBuilder.build());

            MessageHandler handler = new MessageHandler() {
                @Override
                public void onMessage(Message msg) {
                    if (!isShutdown) {
                        try {
                            boolean offered = messageQueue.offer(msg, 1, TimeUnit.SECONDS);
                            if (!offered) {
                                getLogger().warn("Failed to add message to queue - queue might be full");
                            }
                        } catch (Exception e) {
                            getLogger().error("Error processing NATS message", e);
                        }
                    }
                }
            };

            String topic = context.getProperty(TOPIC).getValue();
            String queueGroup = "nifi-" + getIdentifier();
            this.dispatcher = natsConnection.createDispatcher(handler);
            this.dispatcher.subscribe(topic, queueGroup);

            getLogger().info("Successfully subscribed to NATS topic: {} with queue group: {}",
                    topic, queueGroup);

        } catch (Exception e) {
            getLogger().error("Failed to initialize NATS connection", e);
            throw new ProcessException("Failed to connect to NATS", e);
        }
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session)
            throws ProcessException {

        final int batchSize = context.getProperty(BATCH_SIZE).asInteger();
        List<Message> messages = new ArrayList<>();

        // Collect messages up to batch size
        for (int i = 0; i < batchSize && !isShutdown; i++) {
            Message msg = messageQueue.poll();
            if (msg != null) {
                messages.add(msg);
            } else {
                break;
            }
        }

        if (messages.isEmpty()) {
            return;
        }

        for (final Message message : messages) {
            FlowFile flowFile = session.create();
            try {
                // Write the message data
                flowFile = session.write(flowFile, new OutputStreamCallback() {
                    @Override
                    public void process(OutputStream out) throws IOException {
                        byte[] data = message.getData();
                        out.write(data);
                    }
                });

                // Add attributes
                Map<String, String> attributes = new HashMap<>();
                attributes.put("nats.topic", message.getSubject());

                // Process headers if present
                if (message.hasHeaders()) {
                    Headers headers = message.getHeaders();
                    attributes.put(HEADER_ATTRIBUTE, headers.toString());
                }

                flowFile = session.putAllAttributes(flowFile, attributes);

                session.transfer(flowFile, REL_SUCCESS);
                session.getProvenanceReporter().receive(flowFile,
                        "nats://" + message.getSubject(),
                        "Received message from NATS");

            } catch (Exception e) {
                session.remove(flowFile);
                getLogger().error("Failed to process NATS message", e);
            }
        }
    }

    @OnStopped
    public void shutdown() {
        isShutdown = true;
        try {
            if (dispatcher != null) {
                dispatcher.drain(Duration.ofSeconds(5));  // Drain any pending messages
            }
            if (natsConnection != null) {
                natsConnection.flush(Duration.ofSeconds(5));
                natsConnection.close();
            }
        } catch (Exception e) {
            getLogger().error("Error closing NATS connection", e);
        } finally {
            messageQueue.clear();
            dispatcher = null;
            natsConnection = null;
            isShutdown = false;
        }
    }
}
