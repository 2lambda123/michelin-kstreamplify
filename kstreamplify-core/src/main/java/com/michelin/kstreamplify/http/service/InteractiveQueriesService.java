package com.michelin.kstreamplify.http.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.michelin.kstreamplify.initializer.KafkaStreamsInitializer;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.streams.KeyQueryMetadata;
import org.apache.kafka.streams.StreamsMetadata;
import org.apache.kafka.streams.errors.UnknownStateStoreException;
import org.apache.kafka.streams.query.KeyQuery;
import org.apache.kafka.streams.query.RangeQuery;
import org.apache.kafka.streams.query.StateQueryRequest;
import org.apache.kafka.streams.query.StateQueryResult;
import org.apache.kafka.streams.state.HostInfo;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.ValueAndTimestamp;

/**
 * Interactive queries service.
 */
@Slf4j
@AllArgsConstructor
public class InteractiveQueriesService {
    private static final String UNKNOWN_STATE_STORE = "State store %s not found";
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Getter
    private final KafkaStreamsInitializer kafkaStreamsInitializer;

    /**
     * Get the stores.
     *
     * @return The stores
     */
    public List<String> getStores() {
        final Collection<StreamsMetadata> metadata = kafkaStreamsInitializer
            .getKafkaStreams()
            .metadataForAllStreamsClients();

        if (metadata == null || metadata.isEmpty()) {
            return Collections.emptyList();
        }

        return metadata
            .stream()
            .flatMap(streamsMetadata -> streamsMetadata.stateStoreNames().stream())
            .toList();
    }

    /**
     * Get the hosts of the store.
     *
     * @param store The store
     * @return The hosts
     */
    public Collection<StreamsMetadata> getStreamsMetadata(final String store) {
        return kafkaStreamsInitializer
            .getKafkaStreams()
            .streamsMetadataForStore(store);
    }

    /**
     * Get all values from the store.
     *
     * @param store The store
     * @return The values
     */
    public List<QueryResponse> getAll(String store, boolean includeKey, boolean includeMetadata) {
        final Collection<StreamsMetadata> streamsMetadata = getStreamsMetadata(store);

        if (streamsMetadata == null || streamsMetadata.isEmpty()) {
            log.debug("No host found for the given state store {}", store);
            return Collections.emptyList();
        }

        List<QueryResponse> values = new ArrayList<>();
        streamsMetadata.forEach(metadata -> {
            if (isNotCurrentHost(metadata.hostInfo())) {
                log.debug("Fetching data on other instance ({}:{})", metadata.host(), metadata.port());

                List<QueryResponse> queryResponses = requestAllOtherInstance(metadata.hostInfo(), "/store/" + store);
                values.addAll(queryResponses);
            } else {
                log.debug("Fetching data on this instance ({}:{})", metadata.host(), metadata.port());

                RangeQuery<Object, ValueAndTimestamp<Object>> rangeQuery = RangeQuery.withNoBounds();
                StateQueryResult<KeyValueIterator<Object, ValueAndTimestamp<Object>>> result = kafkaStreamsInitializer
                    .getKafkaStreams()
                    .query(StateQueryRequest
                        .inStore(store)
                        .withQuery(rangeQuery)
                        .withPartitions(metadata.topicPartitions()
                            .stream()
                            .map(TopicPartition::partition)
                            .collect(Collectors.toSet())));

                List<QueryResponse> partitionsResult = new ArrayList<>();
                result.getPartitionResults().forEach((key, queryResult) ->
                    queryResult.getResult().forEachRemaining(kv -> {
                        QueryResponse queryResponse;
                        if (includeMetadata) {
                            Set<String> topics = queryResult.getPosition().getTopics();
                            List<QueryResponse.PositionVector> positions = topics
                                .stream()
                                .flatMap(topic -> queryResult.getPosition().getPartitionPositions(topic)
                                    .entrySet()
                                    .stream()
                                    .map(partitionOffset -> new QueryResponse.PositionVector(topic,
                                        partitionOffset.getKey(), partitionOffset.getValue())))
                                .toList();

                            queryResponse = new QueryResponse(includeKey ? kv.key : null,
                                kv.value.value(),
                                kv.value.timestamp(),
                                new HostInfoResponse(metadata.host(), metadata.port()),
                                positions);
                        } else {
                            queryResponse = new QueryResponse(includeKey ? kv.key : null,
                                kv.value.value());
                        }

                        partitionsResult.add(queryResponse);
                    }));

                values.addAll(partitionsResult);
            }
        });

        return values;
    }

    /**
     * Get the value by key from the store.
     *
     * @param store The store name
     * @param key   The key
     * @return The value
     */
    public <K> QueryResponse getByKey(String store, K key, Serializer<K> serializer, boolean includeKey,
                                      boolean includeMetadata) {
        KeyQueryMetadata keyQueryMetadata = getKeyQueryMetadata(store, key, serializer);

        if (keyQueryMetadata == null) {
            throw new UnknownStateStoreException(String.format(UNKNOWN_STATE_STORE, store));
        }

        HostInfo host = keyQueryMetadata.activeHost();
        if (isNotCurrentHost(host)) {
            log.debug("The key {} has been located on another instance ({}:{})", key,
                host.host(), host.port());

            return requestOtherInstance(host, "/store/" + store + "/" + key);
        }

        log.debug("The key {} has been located on the current instance ({}:{})", key,
            host.host(), host.port());

        KeyQuery<K, ValueAndTimestamp<Object>> keyQuery = KeyQuery.withKey(key);
        StateQueryResult<ValueAndTimestamp<Object>> result = kafkaStreamsInitializer
            .getKafkaStreams()
            .query(StateQueryRequest
                .inStore(store)
                .withQuery(keyQuery)
                .withPartitions(Collections.singleton(keyQueryMetadata.partition())));

        if (includeMetadata) {
            Set<String> topics = result.getOnlyPartitionResult().getPosition().getTopics();
            List<QueryResponse.PositionVector> positions = topics
                .stream()
                .flatMap(topic -> result.getOnlyPartitionResult().getPosition().getPartitionPositions(topic)
                    .entrySet()
                    .stream()
                    .map(partitionOffset -> new QueryResponse.PositionVector(topic,
                        partitionOffset.getKey(), partitionOffset.getValue())))
                .toList();

            return new QueryResponse(includeKey ? key : null,
                result.getOnlyPartitionResult().getResult().value(),
                result.getOnlyPartitionResult().getResult().timestamp(),
                new HostInfoResponse(host.host(), host.port()),
                positions);
        }

        return new QueryResponse(includeKey ? key : null,
            result.getOnlyPartitionResult().getResult().value());
    }

    /**
     * Get the host by store and key.
     *
     * @param store The store
     * @param key   The key
     * @return The host
     */
    private <K> KeyQueryMetadata getKeyQueryMetadata(String store, K key, Serializer<K> serializer) {
        return kafkaStreamsInitializer
            .getKafkaStreams()
            .queryMetadataForKey(store, key, serializer);
    }

    /**
     * Request other instance.
     *
     * @param host        The host instance
     * @param endpointPath The endpoint path to request
     * @return The response
     */
    private List<QueryResponse> requestAllOtherInstance(HostInfo host, String endpointPath) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .header("Accept", "application/json")
                .uri(new URI(String.format("http://%s:%d/%s", host.host(), host.port(), endpointPath)))
                .GET()
                .build();

            String json = httpClient
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .get();

            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (URISyntaxException | ExecutionException | InterruptedException | JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Request other instance.
     *
     * @param host        The host instance
     * @param endpointPath The endpoint path to request
     * @return The response
     */
    private QueryResponse requestOtherInstance(HostInfo host, String endpointPath) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .header("Accept", "application/json")
                .uri(new URI(String.format("http://%s:%d/%s", host.host(), host.port(), endpointPath)))
                .GET()
                .build();

            String json = httpClient
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .get();

            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.readValue(json, QueryResponse.class);
        } catch (URISyntaxException | ExecutionException | InterruptedException | JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isNotCurrentHost(HostInfo compareHostInfo) {
        return !kafkaStreamsInitializer.getHostInfo().host().equals(compareHostInfo.host())
            || kafkaStreamsInitializer.getHostInfo().port() != compareHostInfo.port();
    }
}
