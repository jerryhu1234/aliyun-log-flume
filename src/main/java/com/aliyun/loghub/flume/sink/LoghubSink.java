package com.aliyun.loghub.flume.sink;

import com.aliyun.loghub.flume.source.DelimitedTextEventDeserializer;
import com.aliyun.openservices.aliyun.log.producer.LogProducer;
import com.aliyun.openservices.aliyun.log.producer.Producer;
import com.aliyun.openservices.aliyun.log.producer.ProducerConfig;
import com.aliyun.openservices.aliyun.log.producer.ProjectConfig;
import com.aliyun.openservices.aliyun.log.producer.ProjectConfigs;
import com.aliyun.openservices.aliyun.log.producer.Result;
import com.aliyun.openservices.log.Client;
import com.aliyun.openservices.log.common.LogItem;
import com.aliyun.openservices.log.exception.LogException;
import com.aliyun.openservices.log.util.NetworkUtils;
import com.google.common.base.Preconditions;
import org.apache.flume.Channel;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.Transaction;
import org.apache.flume.conf.Configurable;
import org.apache.flume.instrumentation.SinkCounter;
import org.apache.flume.sink.AbstractSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.aliyun.loghub.flume.Constants.ACCESS_KEY_ID_KEY;
import static com.aliyun.loghub.flume.Constants.ACCESS_KEY_SECRET_KEY;
import static com.aliyun.loghub.flume.Constants.BATCH_SIZE;
import static com.aliyun.loghub.flume.Constants.DEFAULT_BATCH_SIZE;
import static com.aliyun.loghub.flume.Constants.DEFAULT_MAX_RETRY;
import static com.aliyun.loghub.flume.Constants.ENDPOINT_KEY;
import static com.aliyun.loghub.flume.Constants.LOGSTORE_KEY;
import static com.aliyun.loghub.flume.Constants.MAX_BUFFER_SIZE;
import static com.aliyun.loghub.flume.Constants.MAX_RETRY;
import static com.aliyun.loghub.flume.Constants.PROJECT_KEY;
import static com.aliyun.loghub.flume.Constants.SERIALIZER;

public class LoghubSink extends AbstractSink implements Configurable {
    private static final Logger LOG = LoggerFactory.getLogger(LoghubSink.class);

    private SinkCounter counter;
    private int batchSize;
    private int bufferSize;
    private String project;
    private String logstore;
    private EventSerializer serializer;
    private List<Future<Boolean>> producerFutures = new ArrayList<>();
    private ThreadPoolExecutor executor;
    private Client client;
    private long maxBufferTime;
    private int maxRetry;
    private int concurrency;
    private String source;

    @Override
    public synchronized void start() {
        executor = new ThreadPoolExecutor(0, concurrency,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(100),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.CallerRunsPolicy());
        executor.allowCoreThreadTimeOut(true);
        counter.start();
        super.start();
        source = NetworkUtils.getLocalMachineIP();
        LOG.info("Loghub Sink {} started.", getName());
    }

    @Override
    public Status process() throws EventDeliveryException {
        Channel channel = getChannel();
        Transaction transaction = null;
        List<LogItem> buffer = new ArrayList<>(bufferSize);
        long earliestEventTime = -1;
        Status result = Status.READY;
        try {
            long processedEvents = 0;
            transaction = channel.getTransaction();
            transaction.begin();

            producerFutures.clear();

            for (; processedEvents < batchSize; processedEvents += 1) {
                Event event = channel.take();
                if (event == null) {
                    // no events available in channel
                    if (processedEvents == 0) {
                        result = Status.BACKOFF;
                        counter.incrementBatchEmptyCount();
                    } else {
                        counter.incrementBatchUnderflowCount();
                    }
                    break;
                }
                counter.incrementEventDrainAttemptCount();
                buffer.add(serializer.serialize(event));
                if (earliestEventTime < 0) {
                    earliestEventTime = System.currentTimeMillis();
                }
                if (shouldFlush(buffer.size(), earliestEventTime)) {
                    LOG.debug("Flushing events to Log service, event count {}", buffer.size());
                    List<LogItem> events = buffer;
                    producerFutures.add(sendEvents(events));
                    buffer = new ArrayList<>(bufferSize);
                }
            }
            if (!buffer.isEmpty()) {
                producerFutures.add(sendEvents(buffer));
            }
            if (processedEvents > 0) {
                for (Future<Boolean> future : producerFutures) {
                    future.get();
                }
                counter.addToEventDrainSuccessCount(processedEvents);
            }
            transaction.commit();
        } catch (Exception ex) {
            LOG.error("Failed to publish events", ex);
            if (transaction != null) {
                try {
                    transaction.rollback();
                } catch (Exception e) {
                    LOG.error("Transaction rollback failed", e);
                }
            }
            producerFutures.clear();
            throw new EventDeliveryException("Failed to publish events", ex);
        } finally {
            if (transaction != null) {
                transaction.close();
            }
        }
        return result;
    }

    private Future<Boolean> sendEvents(List<LogItem> events) {
        return executor.submit(() -> {
            for (int i = 0; i < maxRetry; i++) {
                if (i > 0) {
                    Thread.sleep(50);
                }
                try {
                    client.PutLogs(project, logstore, "", events, source);
                    return true;
                } catch (LogException ex) {
                    if (ex.GetHttpCode() >= 500 || ex.GetHttpCode() == 403) {
                        LOG.warn("Retry on error: {}", ex.GetErrorMessage());
                    } else {
                        LOG.error("Send events to Log Service failed", ex);
                        throw ex;
                    }
                }
            }
            return false;
        });
    }

    private boolean shouldFlush(int bufferSize, long earliestEventTime) {
        if (bufferSize >= batchSize) {
            return true;
        }
        return System.currentTimeMillis() - earliestEventTime >= maxBufferTime;
    }

    @Override
    public void configure(Context context) {
        String endpoint = context.getString(ENDPOINT_KEY);
        ensureNotEmpty(endpoint, ENDPOINT_KEY);
        project = context.getString(PROJECT_KEY);
        ensureNotEmpty(project, PROJECT_KEY);
        logstore = context.getString(LOGSTORE_KEY);
        ensureNotEmpty(logstore, LOGSTORE_KEY);
        String accessKeyId = context.getString(ACCESS_KEY_ID_KEY);
        ensureNotEmpty(accessKeyId, ACCESS_KEY_ID_KEY);
        String accessKey = context.getString(ACCESS_KEY_SECRET_KEY);
        ensureNotEmpty(accessKey, ACCESS_KEY_SECRET_KEY);
        client = new Client(endpoint, accessKeyId, accessKey);
        logstore = context.getString(LOGSTORE_KEY);
        if (counter == null) {
            counter = new SinkCounter(getName());
        }
        batchSize = context.getInteger(BATCH_SIZE, DEFAULT_BATCH_SIZE);
        bufferSize = context.getInteger(MAX_BUFFER_SIZE, 1000);
        maxBufferTime = context.getInteger("maxBufferTime", 10000);
        maxRetry = context.getInteger(MAX_RETRY, DEFAULT_MAX_RETRY);
        int cores = Runtime.getRuntime().availableProcessors();
        concurrency = context.getInteger("concurrency", cores);
        serializer = createSerializer(context);
    }

    private static void ensureNotEmpty(String value, String name) {
        Preconditions.checkArgument(value != null && !value.isEmpty(), "Missing parameter: " + name);
    }

    private EventSerializer createSerializer(Context context) {
        String serializerName = context.getString(SERIALIZER);
        EventSerializer serializer;
        if (serializerName == null || serializerName.isEmpty()) {
            serializer = new DelimitedTextEventSerializer();
        } else if (serializerName.equals(DelimitedTextEventSerializer.ALIAS)
                || serializerName.equalsIgnoreCase(DelimitedTextEventDeserializer.class.getName())) {
            serializer = new DelimitedTextEventSerializer();
        } else if (serializerName.equals(SimpleEventSerializer.ALIAS)
                || serializerName.equalsIgnoreCase(SimpleEventSerializer.class.getName())) {
            serializer = new SimpleEventSerializer();
        } else {
            try {
                serializer = (EventSerializer) Class.forName(serializerName).newInstance();
            } catch (Exception e) {
                throw new IllegalArgumentException("Unable to instantiate serializer: " + serializerName
                        + " on sink: " + getName(), e);
            }
        }
        serializer.configure(context);
        return serializer;
    }

    @Override
    public synchronized void stop() {
        super.stop();
        LOG.info("Stopping Loghub Sink {}", getName());
        if (executor != null) {
            try {
                executor.shutdown();
                executor.awaitTermination(30, TimeUnit.SECONDS);
            } catch (final Exception ex) {
                LOG.error("Error while closing Loghub sink {}.", getName(), ex);
            }
        }
    }
}
