/*
 * Copyright (c) 2020. AxonIQ
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.axoniq.axonserver.connector.query.impl;

import io.axoniq.axonserver.connector.AxonServerException;
import io.axoniq.axonserver.connector.ErrorCategory;
import io.axoniq.axonserver.connector.InstructionHandler;
import io.axoniq.axonserver.connector.Registration;
import io.axoniq.axonserver.connector.ReplyChannel;
import io.axoniq.axonserver.connector.ResultStream;
import io.axoniq.axonserver.connector.impl.AbstractAxonServerChannel;
import io.axoniq.axonserver.connector.impl.AbstractBufferedStream;
import io.axoniq.axonserver.connector.impl.AbstractIncomingInstructionStream;
import io.axoniq.axonserver.connector.impl.AsyncRegistration;
import io.axoniq.axonserver.connector.impl.AxonServerManagedChannel;
import io.axoniq.axonserver.connector.impl.ObjectUtils;
import io.axoniq.axonserver.connector.query.QueryChannel;
import io.axoniq.axonserver.connector.query.QueryDefinition;
import io.axoniq.axonserver.connector.query.QueryHandler;
import io.axoniq.axonserver.connector.query.SubscriptionQueryResult;
import io.axoniq.axonserver.grpc.ErrorMessage;
import io.axoniq.axonserver.grpc.FlowControl;
import io.axoniq.axonserver.grpc.InstructionAck;
import io.axoniq.axonserver.grpc.SerializedObject;
import io.axoniq.axonserver.grpc.control.ClientIdentification;
import io.axoniq.axonserver.grpc.query.QueryComplete;
import io.axoniq.axonserver.grpc.query.QueryProviderInbound;
import io.axoniq.axonserver.grpc.query.QueryProviderOutbound;
import io.axoniq.axonserver.grpc.query.QueryRequest;
import io.axoniq.axonserver.grpc.query.QueryResponse;
import io.axoniq.axonserver.grpc.query.QueryServiceGrpc;
import io.axoniq.axonserver.grpc.query.QuerySubscription;
import io.axoniq.axonserver.grpc.query.QueryUpdate;
import io.axoniq.axonserver.grpc.query.QueryUpdateComplete;
import io.axoniq.axonserver.grpc.query.SubscriptionQuery;
import io.axoniq.axonserver.grpc.query.SubscriptionQueryRequest;
import io.axoniq.axonserver.grpc.query.SubscriptionQueryResponse;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static io.axoniq.axonserver.connector.impl.ObjectUtils.doIfNotNull;
import static io.axoniq.axonserver.connector.impl.ObjectUtils.hasLength;

/**
 * {@link QueryChannel} implementation, serving as the query connection between AxonServer and a client application.
 */
public class QueryChannelImpl extends AbstractAxonServerChannel implements QueryChannel {

    private static final Logger logger = LoggerFactory.getLogger(QueryChannelImpl.class);

    private static final QueryResponse TERMINAL = QueryResponse.newBuilder().setErrorCode("__TERMINAL__").build();

    private final AtomicReference<StreamObserver<QueryProviderOutbound>> outboundQueryStream = new AtomicReference<>();
    private final Set<QueryDefinition> supportedQueries = new CopyOnWriteArraySet<>();
    private final ConcurrentMap<String, Set<QueryHandler>> queryHandlers = new ConcurrentHashMap<>();
    private final ConcurrentMap<Enum<?>, InstructionHandler<QueryProviderInbound, QueryProviderOutbound>> instructionHandlers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<Void>> instructions = new ConcurrentHashMap<>();

    private final ClientIdentification clientIdentification;
    private final int permits;
    private final int permitsBatch;

    // monitor for modification access to queryHandlers collection
    private final Object queryHandlerMonitor = new Object();
    private final Map<String, Set<Registration>> subscriptionQueries = new ConcurrentHashMap<>();
    private final QueryServiceGrpc.QueryServiceStub queryServiceStub;

    /**
     * Constructs a {@link QueryChannelImpl}.
     *
     * @param clientIdentification client information identifying whom has connected. This information is used to pass
     *                             on to message
     * @param permits              an {@code int} defining the number of permits this channel has
     * @param permitsBatch         an {@code int} defining the number of permits to be consumed from prior to requesting
     *                             additional permits for this channel
     * @param executor             a {@link ScheduledExecutorService} used to schedule reconnects of this channel
     * @param channel              the {@link AxonServerManagedChannel} used to form the connection with AxonServer
     */
    public QueryChannelImpl(ClientIdentification clientIdentification,
                            int permits,
                            int permitsBatch,
                            ScheduledExecutorService executor,
                            AxonServerManagedChannel channel) {
        super(executor, channel);
        this.clientIdentification = clientIdentification;
        this.permits = permits;
        this.permitsBatch = permitsBatch;
        instructionHandlers.put(QueryProviderInbound.RequestCase.QUERY, this::handleQuery);
        instructionHandlers.put(QueryProviderInbound.RequestCase.ACK, this::handleAck);
        instructionHandlers.put(SubscriptionQueryRequest.RequestCase.GET_INITIAL_RESULT, this::getInitialResult);
        instructionHandlers.put(SubscriptionQueryRequest.RequestCase.SUBSCRIBE, this::subscribeToQueryUpdates);
        instructionHandlers.put(SubscriptionQueryRequest.RequestCase.UNSUBSCRIBE, this::unsubscribeToQueryUpdates);
        queryServiceStub = QueryServiceGrpc.newStub(channel);
    }

    private void handleAck(QueryProviderInbound query, ReplyChannel<QueryProviderOutbound> result) {
        String instructionId = query.getAck().getInstructionId();
        CompletableFuture<Void> future = instructions.get(instructionId);
        if (future != null) {
            if (query.getAck().getSuccess()) {
                future.complete(null);
            } else {
                future.completeExceptionally(new AxonServerException(query.getAck().getError()));
            }
        }
        result.complete();
    }

    private void unsubscribeToQueryUpdates(QueryProviderInbound query, ReplyChannel<QueryProviderOutbound> result) {
        SubscriptionQuery unsubscribe = query.getSubscriptionQueryRequest().getUnsubscribe();
        Set<Registration> registration = subscriptionQueries.remove(unsubscribe.getSubscriptionIdentifier());
        if (registration != null) {
            registration.forEach(Registration::cancel);
        }
    }

    private void subscribeToQueryUpdates(QueryProviderInbound query, ReplyChannel<QueryProviderOutbound> result) {
        final SubscriptionQuery subscribe = query.getSubscriptionQueryRequest().getSubscribe();
        final String subscriptionIdentifier = subscribe.getSubscriptionIdentifier();
        Set<QueryHandler> handlers =
                queryHandlers.getOrDefault(subscribe.getQueryRequest().getQuery(), Collections.emptySet());
        handlers.forEach(e -> {
            Registration registration = e.registerSubscriptionQuery(subscribe, new QueryHandler.UpdateHandler() {
                @Override
                public void sendUpdate(QueryUpdate queryUpdate) {
                    SubscriptionQueryResponse subscriptionQueryUpdate =
                            SubscriptionQueryResponse.newBuilder()
                                                     .setSubscriptionIdentifier(subscriptionIdentifier)
                                                     .setUpdate(queryUpdate)
                                                     .build();
                    result.send(QueryProviderOutbound.newBuilder()
                                                     .setSubscriptionQueryResponse(subscriptionQueryUpdate)
                                                     .build());
                    logger.debug("Subscription Query Update [id: {}] sent to client {}.",
                                 queryUpdate.getMessageIdentifier(),
                                 queryUpdate.getClientId());
                }

                @Override
                public void complete() {
                    QueryUpdateComplete complete =
                            QueryUpdateComplete.newBuilder()
                                               .setClientId(clientIdentification.getClientId())
                                               .setComponentName(clientIdentification.getComponentName())
                                               .build();
                    SubscriptionQueryResponse subscriptionQueryResult =
                            SubscriptionQueryResponse.newBuilder()
                                                     .setSubscriptionIdentifier(subscriptionIdentifier)
                                                     .setComplete(complete)
                                                     .build();
                    result.send(QueryProviderOutbound.newBuilder()
                                                     .setSubscriptionQueryResponse(subscriptionQueryResult)
                                                     .build());
                    logger.debug("Subscription Query Update completion sent to client {}.",
                                 complete.getClientId());
                }
            });
            if (registration != null) {
                subscriptionQueries.compute(
                        subscriptionIdentifier, (k, v) -> v != null ? v : new CopyOnWriteArraySet<>()
                ).add(registration);
            }
        });
    }

    @Override
    public synchronized void connect() {
        if (outboundQueryStream.get() != null) {
            // we're already connected on this channel
            return;
        }
        IncomingQueryInstructionStream responseObserver = new IncomingQueryInstructionStream(
                clientIdentification.getClientId(), permits, permitsBatch, e -> scheduleReconnect()
        );

        //noinspection ResultOfMethodCallIgnored
        queryServiceStub.openStream(responseObserver);
        StreamObserver<QueryProviderOutbound> newValue = responseObserver.getInstructionsForPlatform();
        StreamObserver<QueryProviderOutbound> previous = outboundQueryStream.getAndSet(newValue);

        supportedQueries.forEach(k -> newValue.onNext(
                buildSubscribeMessage(k.getQueryName(), k.getResultType(), UUID.randomUUID().toString())
        ));
        responseObserver.enableFlowControl();

        logger.info("QueryChannel connected, {} registrations resubscribed", queryHandlers.size());
        ObjectUtils.silently(previous, StreamObserver::onCompleted);
    }

    private QueryProviderOutbound buildSubscribeMessage(String queryName, String resultName, String instructionId) {
        QuerySubscription.Builder querySubscription =
                QuerySubscription.newBuilder()
                                 .setMessageId(instructionId)
                                 .setQuery(queryName)
                                 .setResultName(resultName)
                                 .setClientId(clientIdentification.getClientId())
                                 .setComponentName(clientIdentification.getComponentName());
        return QueryProviderOutbound.newBuilder()
                                    .setInstructionId(instructionId)
                                    .setSubscribe(querySubscription)
                                    .build();
    }

    @Override
    public Registration registerQueryHandler(QueryHandler handler, QueryDefinition... queryDefinitions) {
        CompletableFuture<Void> subscriptionResult = CompletableFuture.completedFuture(null);
        synchronized (queryHandlerMonitor) {
            for (QueryDefinition queryDefinition : queryDefinitions) {
                this.queryHandlers.computeIfAbsent(queryDefinition.getQueryName(), k -> new CopyOnWriteArraySet<>())
                                  .add(handler);
                boolean firstRegistration = supportedQueries.add(queryDefinition);
                if (firstRegistration) {
                    QueryProviderOutbound subscribeMessage = buildSubscribeMessage(queryDefinition.getQueryName(),
                                                                                   queryDefinition.getResultType(),
                                                                                   UUID.randomUUID().toString());
                    CompletableFuture<Void> instructionResult = sendInstruction(subscribeMessage);
                    subscriptionResult = CompletableFuture.allOf(subscriptionResult, instructionResult);
                }
                logger.debug("Registered handler for query {}", queryDefinition);
            }
        }
        return new AsyncRegistration(subscriptionResult, () -> {
            synchronized (queryHandlerMonitor) {
                CompletableFuture<Void> result = CompletableFuture.completedFuture(null);
                for (QueryDefinition def : queryDefinitions) {
                    Set<QueryHandler> refs = queryHandlers.get(def.getQueryName());
                    if (refs != null && refs.remove(handler) && refs.isEmpty()) {
                        queryHandlers.remove(def.getQueryName());
                        result = CompletableFuture.allOf(result, sendUnsubscribe(def));
                        logger.debug("Unregistered handlers for query {}", def);
                    }
                }
                return result;
            }
        });
    }

    private CompletableFuture<Void> sendInstruction(QueryProviderOutbound subscribeMessage) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        doIfNotNull(outboundQueryStream.get(),
                    s -> {
                        if (hasLength(subscribeMessage.getInstructionId())) {
                            instructions.put(subscribeMessage.getInstructionId(), future);
                        } else {
                            future.complete(null);
                        }
                        s.onNext(subscribeMessage);
                    })
                .orElse(() -> future.complete(null));
        return future;
    }

    private CompletableFuture<Void> sendUnsubscribe(QueryDefinition queryDefinition) {
        String instructionId = UUID.randomUUID().toString();
        QuerySubscription unsubscribeMessage =
                QuerySubscription.newBuilder()
                                 .setMessageId(instructionId)
                                 .setQuery(queryDefinition.getQueryName())
                                 .setResultName(queryDefinition.getResultType())
                                 .setClientId(clientIdentification.getClientId())
                                 .setComponentName(clientIdentification.getComponentName())
                                 .build();
        return sendInstruction(QueryProviderOutbound.newBuilder()
                                                    .setInstructionId(instructionId)
                                                    .setUnsubscribe(unsubscribeMessage)
                                                    .build()
        );
    }

    @Override
    public ResultStream<QueryResponse> query(QueryRequest query) {
        AbstractBufferedStream<QueryResponse, QueryRequest> results = new AbstractBufferedStream<QueryResponse, QueryRequest>(
                clientIdentification.getClientId(), Integer.MAX_VALUE, 0
        ) {

            @Override
            protected QueryRequest buildFlowControlMessage(FlowControl flowControl) {
                return null;
            }

            @Override
            protected QueryResponse terminalMessage() {
                return TERMINAL;
            }

            @Override
            public void close() {
                // this is a one-way stream. No need to close it.
            }
        };
        queryServiceStub.query(query, results);
        return results;
    }

    @Override
    public SubscriptionQueryResult subscriptionQuery(QueryRequest query,
                                                     SerializedObject updateResponseType,
                                                     int bufferSize,
                                                     int fetchSize) {
        String subscriptionId = UUID.randomUUID().toString();
        CompletableFuture<QueryResponse> initialResultFuture = new CompletableFuture<>();
        SubscriptionQueryStream subscriptionStream = new SubscriptionQueryStream(
                subscriptionId, initialResultFuture, QueryChannelImpl.this.clientIdentification.getClientId(),
                bufferSize, fetchSize
        );
        StreamObserver<SubscriptionQueryRequest> upstream = queryServiceStub.subscription(subscriptionStream);
        subscriptionStream.enableFlowControl();
        SubscriptionQuery subscriptionQuery = SubscriptionQuery.newBuilder()
                                                               .setQueryRequest(query)
                                                               .setSubscriptionIdentifier(subscriptionId)
                                                               .setUpdateResponseType(updateResponseType)
                                                               .build();
        upstream.onNext(SubscriptionQueryRequest.newBuilder().setSubscribe(subscriptionQuery).build());
        return new SubscriptionQueryResult() {

            private final AtomicBoolean initialResultRequested = new AtomicBoolean();

            @Override
            public CompletableFuture<QueryResponse> initialResult() {
                if (!initialResultFuture.isDone() && !initialResultRequested.getAndSet(true)) {
                    SubscriptionQuery.Builder initialResultRequest =
                            SubscriptionQuery.newBuilder()
                                             .setQueryRequest(query)
                                             .setSubscriptionIdentifier(subscriptionId);
                    upstream.onNext(SubscriptionQueryRequest.newBuilder()
                                                            .setGetInitialResult(initialResultRequest)
                                                            .build());
                }
                return initialResultFuture;
            }

            @Override
            public ResultStream<QueryUpdate> updates() {
                return subscriptionStream.buffer();
            }
        };
    }

    @Override
    public void disconnect() {
        doIfNotNull(outboundQueryStream.getAndSet(null), StreamObserver::onCompleted);
        cancelAllSubscriptionQueries();
    }

    @Override
    public void reconnect() {
        disconnect();
        scheduleImmediateReconnect();
    }

    @Override
    public CompletableFuture<Void> prepareDisconnect() {
        CompletableFuture<Void> future = supportedQueries.stream()
                                                         .map(this::sendUnsubscribe)
                                                         .reduce(CompletableFuture::allOf)
                                                         .orElseGet(() -> CompletableFuture.completedFuture(null));
        cancelAllSubscriptionQueries();
        return future;
    }

    private void cancelAllSubscriptionQueries() {
        subscriptionQueries.forEach((k, v) -> subscriptionQueries.remove(k).forEach(Registration::cancel));
    }

    @Override
    public boolean isConnected() {
        return outboundQueryStream.get() != null;
    }

    private void doHandleQuery(QueryProviderInbound query, ReplyChannel<QueryResponse> responseHandler) {
        doHandleQuery(query.getQuery(), responseHandler);
    }

    private void doHandleQuery(QueryRequest query, ReplyChannel<QueryResponse> responseHandler) {
        Set<QueryHandler> handlers = queryHandlers.getOrDefault(query.getQuery(), Collections.emptySet());
        if (handlers.isEmpty()) {
            responseHandler.sendNack();
            responseHandler.sendLast(QueryResponse.newBuilder()
                                                  .setRequestIdentifier(query.getMessageIdentifier())
                                                  .setErrorCode(ErrorCategory.NO_HANDLER_FOR_QUERY.errorCode())
                                                  .setErrorMessage(ErrorMessage.newBuilder()
                                                                               .setMessage("No handler for query")
                                                                               .build())
                                                  .build());
        }

        responseHandler.sendAck();

        AtomicInteger completeCounter = new AtomicInteger(handlers.size());
        handlers.forEach(queryHandler -> queryHandler.handle(query, new ReplyChannel<QueryResponse>() {
            @Override
            public void send(QueryResponse response) {
                if (!query.getMessageIdentifier().equals(response.getRequestIdentifier())) {
                    logger.debug("RequestIdentifier not properly set, modifying message");
                    QueryResponse newResponse = response.toBuilder()
                                                        .setRequestIdentifier(query.getMessageIdentifier())
                                                        .build();
                    responseHandler.send(newResponse);
                } else {
                    responseHandler.send(response);
                }
            }

            @Override
            public void complete() {
                if (completeCounter.decrementAndGet() == 0) {
                    responseHandler.complete();
                }
            }

            @Override
            public void completeWithError(ErrorMessage errorMessage) {
                responseHandler.completeWithError(errorMessage);
            }

            @Override
            public void completeWithError(ErrorCategory errorCategory, String message) {
                responseHandler.completeWithError(errorCategory, message);
            }

            @Override
            public void sendNack(ErrorMessage errorMessage) {
                responseHandler.sendNack(errorMessage);
            }

            @Override
            public void sendAck() {
                responseHandler.sendAck();
            }
        }));
    }

    private void handleQuery(QueryProviderInbound inbound, ReplyChannel<QueryProviderOutbound> result) {
        doHandleQuery(inbound, new ReplyChannel<QueryResponse>() {
            @Override
            public void send(QueryResponse response) {
                result.send(QueryProviderOutbound.newBuilder().setQueryResponse(response).build());
            }

            @Override
            public void complete() {
                QueryComplete queryComplete = QueryComplete.newBuilder()
                                                           .setRequestId(inbound.getQuery().getMessageIdentifier())
                                                           .setMessageId(UUID.randomUUID().toString())
                                                           .build();
                result.send(QueryProviderOutbound.newBuilder()
                                                 .setQueryComplete(queryComplete)
                                                 .build());
                result.complete();
            }

            @Override
            public void completeWithError(ErrorMessage errorMessage) {
                result.completeWithError(errorMessage);
            }

            @Override
            public void completeWithError(ErrorCategory errorCategory, String message) {
                result.completeWithError(errorCategory, message);
            }

            @Override
            public void sendNack(ErrorMessage errorMessage) {
                result.sendNack(errorMessage);
            }

            @Override
            public void sendAck() {
                result.sendAck();
            }
        });
    }

    private void getInitialResult(QueryProviderInbound query, ReplyChannel<QueryProviderOutbound> result) {
        String subscriptionId = query.getSubscriptionQueryRequest().getGetInitialResult().getSubscriptionIdentifier();
        doHandleQuery(
                query.getSubscriptionQueryRequest().getGetInitialResult().getQueryRequest(),
                new ReplyChannel<QueryResponse>() {

                    @Override
                    public void send(QueryResponse response) {
                        SubscriptionQueryResponse initialResult =
                                SubscriptionQueryResponse.newBuilder()
                                                         .setSubscriptionIdentifier(subscriptionId)
                                                         .setInitialResult(response)
                                                         .setMessageIdentifier(response.getMessageIdentifier())
                                                         .build();
                        result.send(QueryProviderOutbound.newBuilder()
                                                         .setSubscriptionQueryResponse(initialResult)
                                                         .build());
                    }

                    @Override
                    public void complete() {
                        result.sendAck();
                    }

                    @Override
                    public void completeWithError(ErrorMessage errorMessage) {
                        result.completeWithError(errorMessage);
                    }

                    @Override
                    public void completeWithError(ErrorCategory errorCategory, String message) {
                        result.completeWithError(errorCategory, message);
                    }

                    @Override
                    public void sendNack(ErrorMessage errorMessage) {
                        result.sendNack(errorMessage);
                    }

                    @Override
                    public void sendAck() {
                        result.sendAck();
                    }
                }
        );
    }

    private class IncomingQueryInstructionStream
            extends AbstractIncomingInstructionStream<QueryProviderInbound, QueryProviderOutbound> {

        public IncomingQueryInstructionStream(String clientId,
                                              int permits,
                                              int permitsBatch,
                                              Consumer<Throwable> disconnectHandler) {
            super(clientId, permits, permitsBatch, disconnectHandler);
        }

        @Override
        protected QueryProviderOutbound buildFlowControlMessage(FlowControl flowControl) {
            return QueryProviderOutbound.newBuilder().setFlowControl(flowControl).build();
        }

        @Override
        protected QueryProviderOutbound buildAckMessage(InstructionAck ack) {
            return QueryProviderOutbound.newBuilder().setAck(ack).build();
        }

        @Override
        protected String getInstructionId(QueryProviderInbound instruction) {
            return instruction.getInstructionId();
        }

        @Override
        protected InstructionHandler<QueryProviderInbound, QueryProviderOutbound> getHandler(
                QueryProviderInbound request
        ) {
            if (request.getRequestCase() == QueryProviderInbound.RequestCase.SUBSCRIPTION_QUERY_REQUEST) {
                return instructionHandlers.get(request.getSubscriptionQueryRequest().getRequestCase());
            }
            return instructionHandlers.get(request.getRequestCase());
        }

        @Override
        protected boolean unregisterOutboundStream(StreamObserver<QueryProviderOutbound> expected) {
            if (outboundQueryStream.compareAndSet(expected, null)) {
                cancelAllSubscriptionQueries();
                return true;
            }
            return false;
        }
    }
}
