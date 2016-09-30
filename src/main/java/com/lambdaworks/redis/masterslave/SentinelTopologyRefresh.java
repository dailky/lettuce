package com.lambdaworks.redis.masterslave;

import java.io.Closeable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.lambdaworks.redis.RedisClient;
import com.lambdaworks.redis.RedisConnectionException;
import com.lambdaworks.redis.RedisURI;
import com.lambdaworks.redis.codec.StringCodec;
import com.lambdaworks.redis.internal.LettuceLists;
import com.lambdaworks.redis.protocol.LettuceCharsets;
import com.lambdaworks.redis.pubsub.RedisPubSubAdapter;
import com.lambdaworks.redis.pubsub.StatefulRedisPubSubConnection;

import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * Sentinel Pub/Sub listener-enabled topology refresh.
 *
 * @author Mark Paluch
 * @since 4.2
 */
class SentinelTopologyRefresh implements Closeable {

    private static final InternalLogger LOG = InternalLoggerFactory.getInstance(SentinelTopologyRefresh.class);
    private static final StringCodec CODEC = new StringCodec(LettuceCharsets.ASCII);
    private static final Set<String> PROCESSING_CHANNELS = new HashSet<>(
            Arrays.asList("failover-end", "failover-end-for-timeout"));

    private final Map<RedisURI, StatefulRedisPubSubConnection<String, String>> pubSubConnections = new ConcurrentHashMap<>();
    private final RedisClient redisClient;
    private final List<RedisURI> sentinels;
    private final List<Runnable> refreshRunnables = new CopyOnWriteArrayList<>();
    private final RedisPubSubAdapter<String, String> adapter = new RedisPubSubAdapter<String, String>() {

        @Override
        public void message(String pattern, String channel, String message) {
            SentinelTopologyRefresh.this.processMessage(pattern, channel, message);
        }
    };

    private final PubSubMessageActionScheduler topologyRefresh;
    private final PubSubMessageActionScheduler sentinelReconnect;

    private volatile boolean closed = false;

    SentinelTopologyRefresh(RedisClient redisClient, String masterId, List<RedisURI> sentinels) {

        this.redisClient = redisClient;
        this.sentinels = LettuceLists.newList(sentinels);
        this.topologyRefresh = new PubSubMessageActionScheduler(redisClient.getResources().eventExecutorGroup(),
                new TopologyRefreshMessagePredicate(masterId));
        this.sentinelReconnect = new PubSubMessageActionScheduler(redisClient.getResources().eventExecutorGroup(),
                new SentinelReconnectMessagePredicate());

    }

    @Override
    public void close() {

        closed = true;

        HashMap<RedisURI, StatefulRedisPubSubConnection<String, String>> connections = new HashMap<>(pubSubConnections);
        connections.forEach((k, c) -> {
            c.removeListener(adapter);
            c.close();
            pubSubConnections.remove(k);
        });
    }

    void bind(Runnable runnable) {

        refreshRunnables.add(runnable);

        initializeSentinels();
    }

    private void initializeSentinels() {

        if (closed) {
            return;
        }

        AtomicReference<RedisConnectionException> ref = new AtomicReference<>();

        sentinels.forEach(redisURI -> {

            if (closed) {
                return;
            }

            StatefulRedisPubSubConnection<String, String> pubSubConnection = null;
            try {
                if (!pubSubConnections.containsKey(redisURI)) {

                    pubSubConnection = redisClient.connectPubSub(CODEC, redisURI);
                    pubSubConnections.put(redisURI, pubSubConnection);

                    pubSubConnection.addListener(adapter);
                    pubSubConnection.async().psubscribe("*");
                }
            } catch (RedisConnectionException e) {
                if (ref.get() == null) {
                    ref.set(e);
                } else {
                    ref.get().addSuppressed(e);
                }
            }
        });

        if (sentinels.isEmpty() && ref.get() != null) {
            throw ref.get();
        }

        if (closed) {
            close();
        }
    }

    private void processMessage(String pattern, String channel, String message) {

        topologyRefresh.processMessage(channel, message, () -> {
            LOG.debug("Received topology changed signal from Redis Sentinel, scheduling topology update");
            return () -> refreshRunnables.forEach(Runnable::run);
        });

        sentinelReconnect.processMessage(channel, message, () -> {

            LOG.debug("Received sentinel state changed signal from Redis Sentinel, scheduling sentinel reconnect attempts");

            return this::initializeSentinels;
        });
    }

    private static class PubSubMessageActionScheduler {

        private final TimedSemaphore timedSemaphore = new TimedSemaphore();
        private final EventExecutorGroup eventExecutors;
        private final MessagePredicate filter;

        PubSubMessageActionScheduler(EventExecutorGroup eventExecutors, MessagePredicate filter) {
            this.eventExecutors = eventExecutors;
            this.filter = filter;
        }

        void processMessage(String channel, String message, Supplier<Runnable> runnableSupplier) {

            if (!processingAllowed(channel, message)) {
                return;
            }

            timedSemaphore.onEvent(timeout -> {

                Runnable runnable = runnableSupplier.get();

                if (timeout == null) {
                    eventExecutors.submit(runnable);
                } else {
                    eventExecutors.schedule(runnable, timeout.remaining(), TimeUnit.MILLISECONDS);
                }

            });
        }

        private boolean processingAllowed(String channel, String message) {

            if (eventExecutors.isShuttingDown()) {
                return false;
            }

            if (!filter.test(channel, message)) {
                return false;
            }

            return true;
        }
    }

    private static class TimedSemaphore {

        private final AtomicReference<Timeout> timeoutRef = new AtomicReference<>();

        private final int timeout = 5;
        private final TimeUnit timeUnit = TimeUnit.SECONDS;

        private void onEvent(Consumer<Timeout> timeoutConsumer) {

            Timeout existingTimeout = timeoutRef.get();

            if (existingTimeout != null) {
                if (!existingTimeout.isExpired()) {
                    return;
                }
            }

            Timeout timeout = new Timeout(this.timeout, this.timeUnit);
            boolean state = timeoutRef.compareAndSet(existingTimeout, timeout);

            if (state) {
                timeoutConsumer.accept(timeout);
            }
        }
    }

    static interface MessagePredicate extends BiPredicate<String, String> {

        @Override
        boolean test(String message, String channel);
    }

    private static class TopologyRefreshMessagePredicate implements MessagePredicate {

        private final String masterId;

        TopologyRefreshMessagePredicate(String masterId) {
            this.masterId = masterId;
        }

        @Override
        public boolean test(String channel, String message) {

            // trailing spaces after the master name are not bugs
            if (channel.equals("+elected-leader")) {
                if (message.startsWith(String.format("master %s ", masterId))) {
                    return true;
                }
            }

            if (channel.equals("+switch-master")) {
                if (message.startsWith(String.format("%s ", masterId))) {
                    return true;
                }
            }

            if (channel.equals("fix-slave-config")) {
                if (message.contains(String.format("@ %s ", masterId))) {
                    return true;
                }
            }

            if (PROCESSING_CHANNELS.contains(channel)) {
                return true;
            }

            return false;
        }
    }

    private static class SentinelReconnectMessagePredicate implements MessagePredicate {

        @Override
        public boolean test(String message, String channel) {
            if (channel.equals("+sentinel")) {
                return true;
            }

            if (channel.equals("-odown") || channel.equals("-sdown")) {
                if (message.startsWith("sentinel ")) {
                    return true;
                }
            }

            return false;
        }
    }
}
