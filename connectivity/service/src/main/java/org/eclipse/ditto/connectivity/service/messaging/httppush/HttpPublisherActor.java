/*
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.connectivity.service.messaging.httppush;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.eclipse.ditto.base.model.acks.DittoAcknowledgementLabel;
import org.eclipse.ditto.base.model.common.HttpStatus;
import org.eclipse.ditto.base.model.common.HttpStatusCodeOutOfRangeException;
import org.eclipse.ditto.base.model.entity.id.EntityId;
import org.eclipse.ditto.base.model.entity.id.WithEntityId;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.base.model.headers.DittoHeadersBuilder;
import org.eclipse.ditto.base.model.signals.Signal;
import org.eclipse.ditto.base.model.signals.acks.Acknowledgement;
import org.eclipse.ditto.base.model.signals.commands.CommandResponse;
import org.eclipse.ditto.connectivity.api.ExternalMessage;
import org.eclipse.ditto.connectivity.model.Connection;
import org.eclipse.ditto.connectivity.model.GenericTarget;
import org.eclipse.ditto.connectivity.model.MessageSendingFailedException;
import org.eclipse.ditto.connectivity.model.Target;
import org.eclipse.ditto.connectivity.service.config.HttpPushConfig;
import org.eclipse.ditto.connectivity.service.messaging.BasePublisherActor;
import org.eclipse.ditto.connectivity.service.messaging.ConnectivityStatusResolver;
import org.eclipse.ditto.connectivity.service.messaging.SendResult;
import org.eclipse.ditto.connectivity.service.messaging.internal.ConnectionFailure;
import org.eclipse.ditto.connectivity.service.messaging.signing.NoOpSigning;
import org.eclipse.ditto.internal.utils.metrics.DittoMetrics;
import org.eclipse.ditto.internal.utils.metrics.instruments.timer.PreparedTimer;
import org.eclipse.ditto.json.JsonFactory;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.json.JsonValue;
import org.eclipse.ditto.messages.model.Message;
import org.eclipse.ditto.messages.model.MessageHeadersBuilder;
import org.eclipse.ditto.messages.model.signals.commands.MessageCommand;
import org.eclipse.ditto.messages.model.signals.commands.MessageCommandResponse;
import org.eclipse.ditto.messages.model.signals.commands.SendClaimMessage;
import org.eclipse.ditto.messages.model.signals.commands.SendClaimMessageResponse;
import org.eclipse.ditto.messages.model.signals.commands.SendFeatureMessage;
import org.eclipse.ditto.messages.model.signals.commands.SendFeatureMessageResponse;
import org.eclipse.ditto.messages.model.signals.commands.SendThingMessage;
import org.eclipse.ditto.messages.model.signals.commands.SendThingMessageResponse;
import org.eclipse.ditto.protocol.JsonifiableAdaptable;
import org.eclipse.ditto.protocol.ProtocolFactory;
import org.eclipse.ditto.protocol.adapter.DittoProtocolAdapter;
import org.eclipse.ditto.things.model.signals.commands.ThingCommand;
import org.eclipse.ditto.things.model.signals.commands.ThingCommandResponse;

import akka.Done;
import akka.NotUsed;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.http.javadsl.model.HttpCharset;
import akka.http.javadsl.model.HttpEntities;
import akka.http.javadsl.model.HttpHeader;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.Uri;
import akka.http.javadsl.model.headers.ContentType;
import akka.japi.Pair;
import akka.japi.pf.ReceiveBuilder;
import akka.stream.KillSwitch;
import akka.stream.KillSwitches;
import akka.stream.Materializer;
import akka.stream.OverflowStrategy;
import akka.stream.QueueOfferResult;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.javadsl.SourceQueue;
import scala.util.Try;

/**
 * Actor responsible for publishing messages to an HTTP endpoint.
 */
final class HttpPublisherActor extends BasePublisherActor<HttpPublishTarget> {

    /**
     * The name of this Actor in the ActorSystem.
     */
    static final String ACTOR_NAME = "httpPublisherActor";

    private static final long READ_BODY_TIMEOUT_MS = 10000L;

    private static final DittoProtocolAdapter DITTO_PROTOCOL_ADAPTER = DittoProtocolAdapter.newInstance();

    private static final String TOO_MANY_IN_FLIGHT_MESSAGE_DESCRIPTION = "This can have the following reasons:\n" +
            "a) The HTTP endpoint does not consume the messages fast enough.\n" +
            "b) The client count and/or the parallelism of this connection is not configured high enough.";

    private final HttpPushFactory factory;

    private final Materializer materializer;
    private final SourceQueue<Pair<HttpRequest, HttpPushContext>> sourceQueue;
    private final KillSwitch killSwitch;
    private final HttpRequestSigning httpRequestSigning;
    private final CommandAndCommandResponseMatchingValidator commandAndCommandResponseMatchingValidator;

    @SuppressWarnings("unused")
    private HttpPublisherActor(final Connection connection,
            final HttpPushFactory factory,
            final String clientId,
            final ConnectivityStatusResolver connectivityStatusResolver) {

        super(connection, clientId, connectivityStatusResolver);
        this.factory = factory;
        materializer = Materializer.createMaterializer(this::getContext);
        final var config = connectionConfig.getHttpPushConfig();
        final var materialized =
                Source.<Pair<HttpRequest, HttpPushContext>>queue(config.getMaxQueueSize(), OverflowStrategy.dropNew())
                        .viaMat(buildHttpRequestFlow(config), Keep.left())
                        .viaMat(KillSwitches.single(), Keep.both())
                        .toMat(Sink.foreach(HttpPublisherActor::processResponse), Keep.both())
                        .run(materializer);
        sourceQueue = materialized.first().first();
        killSwitch = materialized.first().second();

        // Inform self of stream termination.
        // If self is alive, the error should be escalated.
        materialized.second()
                .whenComplete((done, error) -> getSelf().tell(toConnectionFailure(done, error), ActorRef.noSender()));

        httpRequestSigning = connection.getCredentials()
                .map(credentials -> credentials.accept(HttpRequestSigningExtension.get(getContext().getSystem())))
                .orElse(NoOpSigning.INSTANCE);
        commandAndCommandResponseMatchingValidator =
                CommandAndCommandResponseMatchingValidator.newInstance(connectionLogger);
    }

    /**
     * Creates Akka configuration object for this actor.
     *
     * @param connection the connection.
     * @param factory the http push factory to use.
     * @param clientId the client ID.
     * @param connectivityStatusResolver connectivity status resolver to resolve occurred exceptions to a connectivity
     * status.
     * @return the Akka configuration Props object.
     */
    static Props props(final Connection connection, final HttpPushFactory factory, final String clientId,
            final ConnectivityStatusResolver connectivityStatusResolver) {
        return Props.create(HttpPublisherActor.class, connection, factory, clientId, connectivityStatusResolver);
    }

    private static Uri setPathAndQuery(final Uri uri, @Nullable final String path, @Nullable final String query) {
        var newUri = uri;
        if (path != null) {
            final var slash = "/";
            newUri = path.startsWith(slash) ? newUri.path(path) : newUri.path(slash + path);
        }
        if (query != null) {
            newUri = newUri.rawQueryString(query);
        }
        return newUri;
    }

    private static Pair<Iterable<HttpHeader>, ContentType> getHttpHeadersPair(final Map<String, String> messageHeaders) {
        final Collection<HttpHeader> headers = new ArrayList<>(messageHeaders.size());
        ContentType contentType = null;
        for (final var entry : messageHeaders.entrySet()) {
            if (!ReservedHeaders.contains(entry.getKey())) {
                final var httpHeader = HttpHeader.parse(entry.getKey(), entry.getValue());
                if (httpHeader instanceof ContentType) {
                    contentType = (ContentType) httpHeader;
                } else {
                    headers.add(httpHeader);
                }
            }
        }
        return Pair.create(headers, contentType);
    }

    // Async callback. Must be thread-safe.
    private static void processResponse(final Pair<Try<HttpResponse>, HttpPushContext> responseWithContext) {
        responseWithContext.second().onResponse(responseWithContext.first());
    }

    private static DittoHeaders mergeWithResponseHeaders(final DittoHeaders dittoHeaders, final HttpResponse response) {
        // Special handling of content-type because it needs to be extracted from the entity instead of the headers.
        final DittoHeadersBuilder<?, ?> dittoHeadersBuilder =
                dittoHeaders.toBuilder().contentType(response.entity().getContentType().toString());

        response.getHeaders().forEach(header -> dittoHeadersBuilder.putHeader(header.name(), header.value()));

        return dittoHeadersBuilder.build();
    }

    private static byte[] getPayloadAsBytes(final ExternalMessage message) {
        return message.isTextMessage()
                ? getTextPayload(message).getBytes()
                : getBytePayload(message);
    }

    private static String getTextPayload(final ExternalMessage message) {
        return message.getTextPayload().orElse("");
    }

    private static byte[] getBytePayload(final ExternalMessage message) {
        return message.getBytePayload().map(ByteBuffer::array).orElse(new byte[0]);
    }

    private static CompletionStage<JsonValue> getResponseBody(final HttpResponse response, final int maxBytes,
            final Materializer materializer) {

        return response.entity()
                .withSizeLimit(maxBytes)
                .toStrict(READ_BODY_TIMEOUT_MS, materializer)
                .thenApply(strictEntity -> {
                    final akka.http.javadsl.model.ContentType contentType = strictEntity.getContentType();
                    final Charset charset = contentType.getCharsetOption()
                            .map(HttpCharset::nioCharset)
                            .orElse(StandardCharsets.UTF_8);
                    final byte[] bytes = strictEntity.getData().toArray();
                    final org.eclipse.ditto.base.model.headers.contenttype.ContentType dittoContentType =
                            org.eclipse.ditto.base.model.headers.contenttype.ContentType.of(contentType.toString());
                    if (dittoContentType.isJson()) {
                        final String bodyString = new String(bytes, charset);
                        try {
                            return JsonFactory.readFrom(bodyString);
                        } catch (final Exception e) {
                            return JsonValue.of(bodyString);
                        }
                    } else if (dittoContentType.isBinary()) {
                        final String base64bytes = Base64.getEncoder().encodeToString(bytes);
                        return JsonFactory.newValue(base64bytes);
                    } else {
                        // add text payload as JSON string
                        return JsonFactory.newValue(new String(bytes, charset));
                    }
                });
    }

    private static Uri stripUserInfo(final Uri requestUri) {
        return requestUri.userInfo("");
    }

    private Flow<Pair<HttpRequest, HttpPushContext>, Pair<Try<HttpResponse>, HttpPushContext>, ?>
    buildHttpRequestFlow(final HttpPushConfig config) {

        final Duration requestTimeout = config.getRequestTimeout();

        final PreparedTimer timer = DittoMetrics.timer("http_publish_request_time")
                // Set maximum duration higher than request timeout to avoid race conditions
                .maximumDuration(requestTimeout.plus(Duration.ofSeconds(5)))
                .tag("id", connection.getId().toString());

        final Consumer<Duration> logRequestTimes =
                duration -> connectionLogger.success("HTTP request took <{0}> ms.", duration.toMillis());

        final Flow<Pair<HttpRequest, HttpPushContext>, Pair<HttpRequest, HttpPushContext>, NotUsed> requestSigningFlow =
                Flow.<Pair<HttpRequest, HttpPushContext>>create()
                        .flatMapConcat(pair -> httpRequestSigning.sign(pair.first())
                                .map(signedRequest -> {
                                    logger.debug("SignedRequest <{}>", signedRequest);
                                    return Pair.create(signedRequest, pair.second());
                                }));

        final var httpPushFlow =
                factory.<HttpPushContext>createFlow(getContext().getSystem(), logger, requestTimeout, timer,
                        logRequestTimes);

        return requestSigningFlow.via(httpPushFlow);
    }

    @Override
    public void postStop() throws Exception {
        killSwitch.shutdown();
        super.postStop();
    }

    @Override
    protected void preEnhancement(final ReceiveBuilder receiveBuilder) {
        // noop
    }

    @Override
    protected void postEnhancement(final ReceiveBuilder receiveBuilder) {
        receiveBuilder.match(ConnectionFailure.class, failure -> getContext().getParent().tell(failure, getSelf()));
    }

    @Override
    protected HttpPublishTarget toPublishTarget(final GenericTarget target) {
        return HttpPublishTarget.of(target.getAddress());
    }

    @Override
    protected CompletionStage<SendResult> publishMessage(final Signal<?> signal,
            @Nullable final Target autoAckTarget,
            final HttpPublishTarget publishTarget,
            final ExternalMessage message,
            final int maxTotalMessageSize,
            final int ackSizeQuota) {

        final var resultFuture = new CompletableFuture<SendResult>();
        final var request = createRequest(publishTarget, message);
        final var context =
                newContext(signal, autoAckTarget, request, message, maxTotalMessageSize, ackSizeQuota, resultFuture);
        sourceQueue.offer(Pair.create(request, context))
                .handle(handleQueueOfferResult(message, resultFuture));
        return resultFuture;
    }

    private HttpRequest createRequest(final HttpPublishTarget publishTarget, final ExternalMessage message) {
        final HttpRequest result;

        final Pair<Iterable<HttpHeader>, ContentType> headersPair = getHttpHeadersPair(message.getHeaders());
        final var requestWithoutEntity = newRequestWithoutEntity(publishTarget, headersPair.first(), message);
        final ContentType contentTypeHeader = headersPair.second();
        if (contentTypeHeader != null) {
            final var httpEntity = HttpEntities.create(contentTypeHeader.contentType(), getPayloadAsBytes(message));
            result = requestWithoutEntity.withEntity(httpEntity);
        } else if (message.isTextMessage()) {
            result = requestWithoutEntity.withEntity(getTextPayload(message));
        } else {
            result = requestWithoutEntity.withEntity(getBytePayload(message));
        }

        return result;
    }

    private HttpRequest newRequestWithoutEntity(final HttpPublishTarget publishTarget,
            final Iterable<HttpHeader> headers,
            final ExternalMessage message) {

        final var request = factory.newRequest(publishTarget).addHeaders(headers);
        final var messageHeaders = message.getHeaders();
        return request.withUri(setPathAndQuery(request.getUri(),
                messageHeaders.get(ReservedHeaders.HTTP_PATH.name),
                messageHeaders.get(ReservedHeaders.HTTP_QUERY.name)));
    }

    // Async callback. Must be thread-safe.
    private BiFunction<QueueOfferResult, Throwable, Void> handleQueueOfferResult(final ExternalMessage message,
            final CompletableFuture<?> resultFuture) {

        return (queueOfferResult, error) -> {
            if (error != null) {
                final String errorDescription = "Source queue failure";
                logger.error(error, errorDescription);
                resultFuture.completeExceptionally(error);
                escalate(error, errorDescription);
            } else if (Objects.equals(queueOfferResult, QueueOfferResult.dropped())) {
                resultFuture.completeExceptionally(MessageSendingFailedException.newBuilder()
                        .message("Outgoing HTTP request aborted: There are too many in-flight requests.")
                        .description(TOO_MANY_IN_FLIGHT_MESSAGE_DESCRIPTION)
                        .dittoHeaders(message.getInternalHeaders())
                        .build());
            }
            return null;
        };
    }

    private HttpPushContext newContext(final Signal<?> signal,
            @Nullable final Target autoAckTarget,
            final HttpRequest request,
            final ExternalMessage message,
            final int maxTotalMessageSize,
            final int ackSizeQuota,
            final CompletableFuture<SendResult> resultFuture) {

        return tryResponse -> {
            final var l = logger.withCorrelationId(message.getInternalHeaders());

            if (tryResponse.isSuccess()) {
                final var response = tryResponse.get();
                l.info("Got response status <{}>", response.status());
                l.debug("Sent message <{}>.", message);
                l.debug("Got response <{} {} {}>", response.status(), response.getHeaders(),
                        response.entity().getContentType());

                toCommandResponseOrAcknowledgement(signal, autoAckTarget, response, maxTotalMessageSize, ackSizeQuota)
                        .thenAccept(resultFuture::complete)
                        .exceptionally(e -> {
                            resultFuture.completeExceptionally(e);
                            return null;
                        });
            } else {
                final var failure = tryResponse.failed();
                final var error = failure.get();
                if (l.isDebugEnabled()) {
                    l.debug("Failed to send message <{}> due to <{}: {}>",
                            message,
                            error.getClass().getSimpleName(),
                            error.getMessage());
                } else {
                    l.info("Failed to send message due to <{}: {}>",
                            error.getClass().getSimpleName(),
                            error.getMessage());
                }
                resultFuture.completeExceptionally(error);
                escalate(error,
                        MessageFormat.format("Failed to send HTTP request to <{0}>.", stripUserInfo(request.getUri())));
            }
        };
    }

    private CompletionStage<SendResult> toCommandResponseOrAcknowledgement(final Signal<?> sentSignal,
            @Nullable final Target autoAckTarget,
            final HttpResponse response,
            final int maxTotalMessageSize,
            final int ackSizeQuota) {

        final var autoAckLabel = getAcknowledgementLabel(autoAckTarget);

        final var statusCode = response.status().intValue();
        final HttpStatus httpStatus;
        try {
            httpStatus = HttpStatus.getInstance(statusCode);
        } catch (final HttpStatusCodeOutOfRangeException e) {
            response.discardEntityBytes(materializer);
            final var error = MessageSendingFailedException.newBuilder()
                    .message(String.format("Remote server delivered unknown HTTP status code <%d>!", statusCode))
                    .cause(e)
                    .build();
            return CompletableFuture.failedFuture(error);
        }

        final var isSentSignalLiveCommand = SignalInformationPoint.isLiveCommand(sentSignal);
        final int maxResponseSize = isSentSignalLiveCommand ? maxTotalMessageSize : ackSizeQuota;
        return getResponseBody(response, maxResponseSize, materializer).thenApply(body -> {
            @Nullable final CommandResponse<?> result;
            final var mergedDittoHeaders = mergeWithResponseHeaders(sentSignal.getDittoHeaders(), response);
            final Optional<EntityId> entityIdOptional = WithEntityId.getEntityIdOfType(EntityId.class, sentSignal);
            if (autoAckLabel.isPresent() && entityIdOptional.isPresent()) {
                final EntityId entityId = entityIdOptional.get();

                if (DittoAcknowledgementLabel.LIVE_RESPONSE.equals(autoAckLabel.get())) {
                    // Live-Response is declared as issued ack => parse live response from response
                    if (sentSignal instanceof MessageCommand) {
                        result = toMessageCommandResponse((MessageCommand<?, ?>) sentSignal, mergedDittoHeaders, body,
                                httpStatus);
                    } else if (sentSignal instanceof ThingCommand &&
                            SignalInformationPoint.isChannelLive(sentSignal)) {
                        result = toLiveCommandResponse(sentSignal, mergedDittoHeaders, body, httpStatus);
                    } else {
                        result = null;
                    }
                } else {
                    // There is an issued ack declared but its not live-response => handle response as acknowledgement.
                    result = Acknowledgement.of(autoAckLabel.get(), entityId, httpStatus, mergedDittoHeaders, body);
                }

            } else {
                // No Acks declared as issued acks => Handle response either as live response or as acknowledgement
                // or as fallback build a response for local diagnostics.
                final boolean isDittoProtocolMessage = mergedDittoHeaders.getDittoContentType()
                        .filter(org.eclipse.ditto.base.model.headers.contenttype.ContentType::isDittoProtocol)
                        .isPresent();
                if (isDittoProtocolMessage && body.isObject()) {
                    final CommandResponse<?> parsedResponse = toCommandResponse(body.asObject());
                    if (parsedResponse instanceof Acknowledgement) {
                        result = parsedResponse;
                    } else if (SignalInformationPoint.isLiveCommandResponse(parsedResponse)) {
                        result = parsedResponse;
                    } else {
                        result = null;
                    }
                } else {
                    result = null;
                }
            }

            final var liveCommandWithEntityId = SignalInformationPoint.tryToGetAsLiveCommandWithEntityId(sentSignal);
            if (liveCommandWithEntityId.isPresent()
                    && null != result
                    && SignalInformationPoint.isLiveCommandResponse(result)) {

                // Do only return command response for live commands with a correct response.
                commandAndCommandResponseMatchingValidator.accept(liveCommandWithEntityId.get(), result);
            }
            if (result == null) {
                connectionLogger.success(
                        "No CommandResponse created from HTTP response with status <{0}> and body <{1}>.",
                        response.status(), body);
            } else {
                connectionLogger.success(
                        "CommandResponse <{0}> created from HTTP response with Status <{1}> and body <{2}>.",
                        result, response.status(), body);
            }
            final MessageSendingFailedException sendFailure;
            if (!httpStatus.isSuccess()) {
                final String message =
                        String.format("Got non success status code: <%s> and body: <%s>", httpStatus.getCode(), body);
                sendFailure = MessageSendingFailedException.newBuilder()
                        .message(message)
                        .dittoHeaders(mergedDittoHeaders)
                        .build();
            } else {
                sendFailure = null;
            }
            return new SendResult(result, sendFailure, mergedDittoHeaders);
        });
    }

    @Nullable
    private MessageCommandResponse<?, ?> toMessageCommandResponse(final MessageCommand<?, ?> messageCommand,
            final DittoHeaders dittoHeaders,
            final JsonValue jsonValue,
            final HttpStatus status) {

        final boolean isDittoProtocolMessage = dittoHeaders.getDittoContentType()
                .filter(org.eclipse.ditto.base.model.headers.contenttype.ContentType::isDittoProtocol)
                .isPresent();
        if (isDittoProtocolMessage && jsonValue.isObject()) {
            final CommandResponse<?> commandResponse = toCommandResponse(jsonValue.asObject());
            if (commandResponse == null) {
                return null;
            } else if (commandResponse instanceof MessageCommandResponse) {
                return (MessageCommandResponse<?, ?>) commandResponse;
            } else {
                connectionLogger.failure("Expected <{0}> to be of type <{1}> but was of type <{2}>.",
                        commandResponse, MessageCommandResponse.class.getSimpleName(),
                        commandResponse.getClass().getSimpleName());
                return null;
            }
        } else {
            final var commandMessage = messageCommand.getMessage();
            final var messageHeaders = MessageHeadersBuilder.of(commandMessage.getHeaders())
                    .httpStatus(status)
                    .putHeaders(dittoHeaders)
                    .build();
            final var message = Message.newBuilder(messageHeaders)
                    .payload(jsonValue)
                    .build();

            switch (messageCommand.getType()) {
                case SendClaimMessage.TYPE:
                    return SendClaimMessageResponse.of(messageCommand.getEntityId(), message, status,
                            dittoHeaders);
                case SendThingMessage.TYPE:
                    return SendThingMessageResponse.of(messageCommand.getEntityId(), message, status,
                            dittoHeaders);
                case SendFeatureMessage.TYPE:
                    final SendFeatureMessage<?> sendFeatureMessage = (SendFeatureMessage<?>) messageCommand;
                    return SendFeatureMessageResponse.of(messageCommand.getEntityId(),
                            sendFeatureMessage.getFeatureId(), message, status, dittoHeaders);
                default:
                    connectionLogger.failure("Initial message command type <{0}> is unknown.",
                            messageCommand.getType());
                    return null;
            }
        }
    }

    @Nullable
    private CommandResponse<?> toLiveCommandResponse(final Signal<?> sentSignal,
            final DittoHeaders dittoHeaders,
            final JsonValue jsonValue,
            final HttpStatus status) {

        final boolean isDittoProtocolMessage = dittoHeaders.getDittoContentType()
                .filter(org.eclipse.ditto.base.model.headers.contenttype.ContentType::isDittoProtocol)
                .isPresent();
        if (isDittoProtocolMessage && jsonValue.isObject()) {
            final CommandResponse<?> commandResponse = toCommandResponse(jsonValue.asObject());
            if (commandResponse == null) {
                return null;
            } else if (commandResponse instanceof ThingCommandResponse &&
                    SignalInformationPoint.isChannelLive(commandResponse)) {
                return commandResponse;
            } else {
                connectionLogger.failure("Expected <{0}> to be of type <{1}> but was of type <{2}>.",
                        commandResponse, ThingCommandResponse.class.getSimpleName(),
                        commandResponse.getClass().getSimpleName());
                return null;
            }
        } else {
            return null;
        }
    }

    @Nullable
    private CommandResponse<?> toCommandResponse(final JsonObject jsonObject) {
        final JsonifiableAdaptable jsonifiableAdaptable = ProtocolFactory.jsonifiableAdaptableFromJson(jsonObject);
        final Signal<?> signal = DITTO_PROTOCOL_ADAPTER.fromAdaptable(jsonifiableAdaptable);
        if (signal instanceof CommandResponse) {
            return (CommandResponse<?>) signal;
        } else {
            connectionLogger.exception("Expected <{}> to be of type <{}> but was of type <{}>.",
                    jsonObject, CommandResponse.class.getSimpleName(), signal.getClass().getSimpleName());
            return null;
        }

    }

    private ConnectionFailure toConnectionFailure(@Nullable final Done done, @Nullable final Throwable error) {
        return ConnectionFailure.of(getSelf(), error, "HttpPublisherActor stream terminated");
    }

    private enum ReservedHeaders {

        HTTP_QUERY("http.query"),

        HTTP_PATH("http.path");

        private final String name;

        ReservedHeaders(final String name) {
            this.name = name;
        }

        private static boolean contains(final String header) {
            return Arrays.stream(values()).anyMatch(reservedHeader -> reservedHeader.matches(header));
        }

        private boolean matches(final String headerName) {
            return name.equalsIgnoreCase(headerName);
        }
    }
}

