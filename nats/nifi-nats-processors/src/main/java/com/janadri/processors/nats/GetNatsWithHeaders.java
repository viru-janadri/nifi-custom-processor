package com.janadri.processors.nats;

import io.nats.client.*;
import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.api.ConsumerInfo;
import io.nats.client.impl.Headers;


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
import io.nats.client.JetStream;
import io.nats.client.JetStreamSubscription;
import io.nats.client.PushSubscribeOptions;

@SupportsBatching
@Tags({"NATS", "Messaging", "Get", "Ingest", "Ingress", "Topic", "PubSub", "Receive"})
@CapabilityDescription("Fetches messages from a NATS Messaging Topic. Supports protobuf-encoded messages and NATS headers.")
@WritesAttributes({
        @WritesAttribute(attribute = "nats.topic", description = "The NATS topic from which the message was received"),
        @WritesAttribute(attribute = "nats.header.*", description = "Any headers present in the NATS message will be added with this prefix")
})
public class GetNatsWithHeaders extends AbstractNatsProcessor {

    public static final String HEADER_ATTRIBUTE = "nats.header.";


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

    public static final PropertyDescriptor NATS_USERNAME = new PropertyDescriptor.Builder()
            .name("NATS Username")
            .description("Username for NATS authentication")
            .required(false)
            .sensitive(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor NATS_PASSWORD = new PropertyDescriptor.Builder()
            .name("NATS Password")
            .description("Password for NATS authentication")
            .required(false)
            .sensitive(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("FlowFiles containing received NATS messages are routed here")
            .build();

    private Connection natsConnection;
    private Dispatcher dispatcher;
    private final BlockingQueue<Message> messageQueue;
    private volatile boolean isShutdown = false;
    private JetStream jetStream;
    private JetStreamSubscription subscription;

    public GetNatsWithHeaders() {
        messageQueue = new LinkedBlockingQueue<>();
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        final List<PropertyDescriptor> properties = new ArrayList<>();
        properties.add(SEED_BROKERS);
        properties.add(NATS_USERNAME);
        properties.add(NATS_PASSWORD);
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
            // Step 1: Set Up Connection Options
            Options.Builder optionsBuilder = Options.builder()
                    .server(context.getProperty(SEED_BROKERS).getValue())
                    .connectionTimeout(Duration.ofSeconds(5));

            String username = context.getProperty(NATS_USERNAME).getValue();
            String password = context.getProperty(NATS_PASSWORD).getValue();

            if (username != null && password != null) {
                optionsBuilder.userInfo(username.toCharArray(), password.toCharArray());
            }

            natsConnection = Nats.connect(optionsBuilder.build());
            jetStream = natsConnection.jetStream();
            JetStreamManagement jsm = natsConnection.jetStreamManagement();

            // Step 2: Extract Topic & Define Consumer Name
            String topic = context.getProperty(TOPIC).getValue();
            String streamName = topic.split("\\.")[0];
            String durableName = "nifi_" + getIdentifier() + "_" + streamName;

            try {
                // Step 3: Retrieve Existing Consumer Info
                ConsumerContext consumerContext = jetStream.getConsumerContext(streamName, durableName);
                ConsumerInfo consumerInfo = consumerContext.getCachedConsumerInfo();

                if (consumerInfo != null) {
                    String existingTopic = consumerInfo.getConsumerConfiguration().getFilterSubject();

                    if (!existingTopic.equals(topic)) {
                        // Consumer topic has changed, so delete and recreate
                        getLogger().debug("Topic changed from '{}' to '{}'. Deleting old consumer: {}", existingTopic, topic, durableName);
                        jsm.deleteConsumer(streamName, durableName);
                    } else {
                        // Reuse the existing consumer if topic remains the same
                        getLogger().debug("Reusing existing consumer for topic: {}", topic);
                    }
                }
            } catch (JetStreamApiException | IOException e) {
                // Step 4: Consumer does not exist, proceed with a new subscription
                getLogger().debug("Consumer does not exist or cannot be retrieved. Proceeding with a new subscription.");
            }

            // Step 5: Set Up Dispatcher and Message Handler
            this.dispatcher = natsConnection.createDispatcher();

            MessageHandler handler = msg -> {
                if (!isShutdown) {
                    try {
                        if (msg.hasHeaders()) {
                            getLogger().debug("Received message with headers: {}", msg.getHeaders());
                        }
                        messageQueue.offer(msg);
                    } catch (Exception e) {
                        getLogger().error("Error handling message", e);
                    }
                }
            };

            // Step 6: Subscribe to the Topic with Durable Consumer
            PushSubscribeOptions pushOpts = PushSubscribeOptions.builder()
                    .durable(durableName)
                    .build();

            subscription = jetStream.subscribe(topic, dispatcher, handler, true, pushOpts);

            getLogger().debug("Successfully subscribed to JetStream topic: {}", topic);

        } catch (Exception e) {
            getLogger().error("Failed to initialize NATS JetStream connection", e);
            throw new ProcessException(e);
        }
    }



    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session)
            throws ProcessException {

        final int batchSize = context.getProperty(BATCH_SIZE).asInteger();

        if (batchSize == 1) {
            // Single message processing
            final Message message = messageQueue.poll();
            if (message == null) {
                return;
            }

            processMessage(message, session);
        } else {
            // Batch processing path
            List<Message> messages = new ArrayList<>(batchSize);
            for (int i = 0; i < batchSize; i++) {
                Message msg = messageQueue.poll();
                if (msg == null) break;
                messages.add(msg);
            }

            if (messages.isEmpty()) {
                return;
            }

            for (final Message message : messages) {
                processMessage(message, session);
            }
        }
    }

    private void processMessage(final Message message, final ProcessSession session) {
        FlowFile flowFile = session.create();
        try {
            flowFile = session.write(flowFile, new OutputStreamCallback() {
                @Override
                public void process(OutputStream out) throws IOException {
                    byte[] data = message.getData();
                    if (data != null) {
                        out.write(data);
                    }
                }
            });

            Map<String, String> attributes = new HashMap<>();
            attributes.put("nats.topic", message.getSubject());

            // Enhanced header handling
            if (message.hasHeaders()) {
                Headers headers = message.getHeaders();

                for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                    attributes.put(HEADER_ATTRIBUTE + entry.getKey(),
                            String.join(",", entry.getValue()));
                }

            }

            flowFile = session.putAllAttributes(flowFile, attributes);
            session.transfer(flowFile, REL_SUCCESS);

            // Ensure proper acknowledgment
            message.ack();

        } catch (Exception e) {
            session.remove(flowFile);
            getLogger().error("Failed to process message: {}", e.getMessage(), e);
        }
    }

    @OnStopped
    public void shutdown() {
        isShutdown = true;
        try {
            if (dispatcher != null) {
                dispatcher.drain(Duration.ofSeconds(5));
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
            subscription = null;
            natsConnection = null;
            isShutdown = false;
        }
    }
}
