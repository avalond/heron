package com.twitter.heron.metricsmgr.sink.tmaster;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.twitter.heron.common.basics.TypeUtils;
import com.twitter.heron.common.core.base.Communicator;
import com.twitter.heron.common.core.base.Constants;
import com.twitter.heron.common.core.base.NIOLooper;
import com.twitter.heron.common.core.base.SingletonRegistry;
import com.twitter.heron.common.core.network.HeronSocketOptions;
import com.twitter.heron.spi.metricsmgr.metrics.ExceptionInfo;
import com.twitter.heron.spi.metricsmgr.metrics.MetricsFilter;
import com.twitter.heron.spi.metricsmgr.metrics.MetricsInfo;
import com.twitter.heron.spi.metricsmgr.metrics.MetricsRecord;
import com.twitter.heron.spi.metricsmgr.sink.IMetricsSink;
import com.twitter.heron.spi.metricsmgr.sink.SinkContext;
import com.twitter.heron.proto.tmaster.TopologyMaster;

/**
 * An IMetricsSink sends Metrics to TMaster.
 * 1. It used StateManager to get the TMasterLocation
 * <p/>
 * 2. Then it would construct a long-live Service running TMasterClient, which could automatically
 * recover from uncaught exceptions, i.e. close the old one and start a new one.
 * Also, it provides api to update the TMasterLocation that TMasterClient need to connect and
 * restart the TMasterClient.
 * There are two scenarios we need to restart a TMasterClient in our case:
 * <p/>
 * -- Uncaught exceptions happen within TMasterClient; then we would restart TMasterClient inside
 * the same ExecutorService inside the UncaughtExceptionHandlers.
 * Notice that, in java, exceptions occur inside UncaughtExceptionHandlers would not invoke
 * UncaughtExceptionHandlers; instead, it would kill the thread with that exception.
 * So if exceptions thrown during restart a new TMasterClient, this TMasterSink would die, and
 * external logic would take care of it.
 * <p/>
 * -- TMasterLocation changes (though in fact, TMasterClient might also throw exceptions in this case),
 * in this case, we would invoke TMasterService to start from tMasterLocationStarter's thread.
 * But the TMasterService and TMasterClient still start wihtin the thread they run.
 * <p/>
 * 3. When a new MetricsRecord comes by invoking processRecord, it would push the MetricsRecord
 * to the Communicator Queue to TMasterClient
 * <p/>
 * Notice that we would not send all metrics to TMaster; we would use MetricsFilter to figure out
 * needed metrics.
 */

public class TMasterSink implements IMetricsSink {
  private static final Logger LOG = Logger.getLogger(TMasterSink.class.getName());

  // These configs would be read from metrics-sink-configs.yaml
  private static final String KEY_TMASTER_LOCATION_CHECK_INTERVAL_SEC =
      "tmaster-location-check-interval-sec";
  private static final String KEY_TMASTER = "tmaster-client";
  private static final String KEY_TMASTER_RECONNECT_INTERVAL_SEC = "reconnect-interval-second";
  private static final String KEY_NETWORK_WRITE_BATCH_SIZE_BYTES = "network-write-batch-size-bytes";
  private static final String KEY_NETWORK_WRITE_BATCH_TIME_MS = "network-write-batch-time-ms";
  private static final String KEY_NETWORK_READ_BATCH_SIZE_BYTES = "network-read-batch-size-bytes";
  private static final String KEY_NETWORK_READ_BATCH_TIME_MS = "network-read-batch-time-ms";
  private static final String KEY_SOCKET_SEND_BUFFER_BYTES = "socket-send-buffer-size-bytes";
  private static final String KEY_SOCKET_RECEIVED_BUFFER_BYTES = "socket-received-buffer-size-bytes";
  private static final String KEY_TMASTER_METRICS_TYPE = "tmaster-metrics-type";

  // Bean name to fetch the TMasterLocation object from SingletonRegistry
  private static final String TMASTER_LOCATION_BEAN_NAME =
      TopologyMaster.TMasterLocation.newBuilder().getDescriptorForType().getFullName();

  private final Communicator<TopologyMaster.PublishMetrics> metricsCommunicator =
      new Communicator<TopologyMaster.PublishMetrics>();
  private final MetricsFilter tMasterMetricsFilter = new MetricsFilter();

  private final Map<String, Object> sinkConfig = new HashMap<String, Object>();

  private TMasterClientService tMasterClientService;

  // A scheduled executor service to check whether the TMasterLocation has changed
  // If so, restart the TMasterClientService with the new TMasterLocation
  // Start of TMasterClientService will also be in this thread
  private final ScheduledExecutorService tMasterLocationStarter =
      Executors.newSingleThreadScheduledExecutor();

  // We need to cache it locally to check whether the TMasterLocation is changed
  // This field is changed only in ScheduledExecutorService's thread,
  // so no need to make it volatile
  private TopologyMaster.TMasterLocation currentTMasterLocation = null;

  // Metrics Counter Name
  private static final String METRICS_COUNT = "metrics-count";
  private static final String EXCEPTIONS_COUNT = "exceptions-count";
  private static final String RECORD_PROCESS_COUNT = "record-process-count";
  private static final String TMASTER_RESTART_COUNT = "tmaster-restart-count";
  private static final String TMASTER_LOCATION_UPDATE_COUNT = "tmaster-location-update-count";

  private SinkContext sinkContext;

  /**
   * A long-live Service running TMasterClient
   * It would automatically restart the TMasterClient connecting and communicating to the latest
   * TMasterLocation if any uncaught exceptions throw.
   * <p/>
   * It provides startNewMasterClient(TopologyMaster.TMasterLocation location), which would also
   * update the currentTMasterLocation to the lastest location.
   * <p/>
   * So a new TMasterClient would start in two cases:
   * 1. The old one threw exceptions and died.
   * 2. startNewMasterClient() is invoked externally with TMasterLocation.
   */
  private static class TMasterClientService {
    private final AtomicInteger startedAttempts = new AtomicInteger(0);

    // An UncaughtExceptionHandler, which would restart TMasterLocation with current TMasterLocation.
    private class TMasterClientThreadFactory implements ThreadFactory {
      private class TMasterClientExceptionHandler implements Thread.UncaughtExceptionHandler {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          LOG.log(Level.SEVERE, "TMasterClient dies in thread: " + t, e);

          long reconnectInterval =
              TypeUtils.getLong(tmasterClientConfig.get(KEY_TMASTER_RECONNECT_INTERVAL_SEC));
          Utils.sleep(reconnectInterval * Constants.SECONDS_TO_MILLISECONDS);
          LOG.info("Restarting TMasterClient");

          // We would use the TMasterLocation in cache, since
          // the new TMasterClient is started due to exception thrown,
          // rather than TMasterLocation changes
          startNewMasterClient();
        }
      }

      @Override
      public Thread newThread(Runnable r) {
        final Thread thread = new Thread(r);
        thread.setUncaughtExceptionHandler(new TMasterClientExceptionHandler());
        return thread;
      }
    }

    private volatile TMasterClient tMasterClient;

    // We need to cache TMasterLocation for failover case
    // This value is set in ScheduledExecutorService' thread while
    // it is used in TMasterClientService thread,
    // so we need to make it volatile to guarantee the visiability.
    private volatile TopologyMaster.TMasterLocation currentTMasterLocation;

    private final Map<String, Object> tmasterClientConfig;

    private final Communicator<TopologyMaster.PublishMetrics> metricsCommunicator;

    private final ExecutorService tmasterClientExecutor =
        Executors.newSingleThreadExecutor(new TMasterClientThreadFactory());

    private TMasterClientService(Map<String, Object> tmasterClientConfig,
                                 Communicator<TopologyMaster.PublishMetrics> metricsCommunicator) {
      this.tmasterClientConfig = tmasterClientConfig;
      this.metricsCommunicator = metricsCommunicator;
    }

    // Update the TMasterLocation to connect within the TMasterClient
    // This method is thread-safe, since
    // currentTMasterLocation is volatile and we just replace it.
    // In our scenario, it is only invoked when TMasterLocation is changed,
    // i.e. this method is only invoked in scheduled executor thread.
    public void updateTMasterLocation(TopologyMaster.TMasterLocation location) {
      currentTMasterLocation = location;
    }

    // This method could be invoked by different threads
    // Make it synchronized to guarantee thread-safe
    public synchronized void startNewMasterClient() {

      // Exit any running tMasterClient if there is any to release the thread in tmasterClientExecutor
      if (tMasterClient != null) {
        tMasterClient.stop();
        tMasterClient.getNIOLooper().exitLoop();
      }

      // Construct the new TMasterClient
      final NIOLooper looper;
      try {
        looper = new NIOLooper();
      } catch (IOException e) {
        throw new RuntimeException("Could not create the NIOLooper", e);
      }

      HeronSocketOptions socketOptions =
          new HeronSocketOptions(
              TypeUtils.getLong(tmasterClientConfig.get(KEY_NETWORK_WRITE_BATCH_SIZE_BYTES)),
              TypeUtils.getLong(tmasterClientConfig.get(KEY_NETWORK_WRITE_BATCH_TIME_MS)),
              TypeUtils.getLong(tmasterClientConfig.get(KEY_NETWORK_READ_BATCH_SIZE_BYTES)),
              TypeUtils.getLong(tmasterClientConfig.get(KEY_NETWORK_READ_BATCH_TIME_MS)),
              TypeUtils.getInt(tmasterClientConfig.get(KEY_SOCKET_SEND_BUFFER_BYTES)),
              TypeUtils.getInt(tmasterClientConfig.get(KEY_SOCKET_RECEIVED_BUFFER_BYTES)));

      // Reset the Consumer
      metricsCommunicator.setConsumer(looper);

      tMasterClient =
          new TMasterClient(looper,
              currentTMasterLocation.getHost(),
              currentTMasterLocation.getMasterPort(),
              socketOptions, metricsCommunicator);
      tMasterClient.
          setReconnectIntervalSec(TypeUtils.getLong(tmasterClientConfig.get(KEY_TMASTER_RECONNECT_INTERVAL_SEC)));

      LOG.severe(String.format("Starting TMasterClient for the %d time.",
          startedAttempts.incrementAndGet()));
      tmasterClientExecutor.execute(tMasterClient);
    }

    // This method could be invoked by different threads
    // Make it synchronized to guarantee thread-safe
    public synchronized void close() {
      tMasterClient.getNIOLooper().exitLoop();
      tmasterClientExecutor.shutdownNow();
    }

    /////////////////////////////////////////////////////////
    // Following protected methods should be used only for unit testing
    /////////////////////////////////////////////////////////
    protected TMasterClient getTMasterClient() {
      return tMasterClient;
    }

    protected int getTMasterStartedAttempts() {
      return startedAttempts.get();
    }

    protected TopologyMaster.TMasterLocation getCurrentTMasterLocation() {
      return currentTMasterLocation;
    }
  }

  @Override
  public void init(Map<String, Object> conf, SinkContext context) {
    sinkConfig.putAll(conf);

    sinkContext = context;

    // Fill the tMasterMetricsFilter according to metrics-sink-configs.yaml
    Map<String, String> tmasterMetricsType = (Map<String, String>) sinkConfig.get(KEY_TMASTER_METRICS_TYPE);
    if (tmasterMetricsType != null) {
      for (Map.Entry<String, String> metricToType : tmasterMetricsType.entrySet()) {
        String value = metricToType.getValue();
        MetricsFilter.MetricAggregationType type;
        if (value.equals("SUM")) {
          type = MetricsFilter.MetricAggregationType.SUM;
        } else if (value.equals("AVG")) {
          type = MetricsFilter.MetricAggregationType.AVG;
        } else if (value.equals("LAST")) {
          type = MetricsFilter.MetricAggregationType.LAST;
        } else {
          type = MetricsFilter.MetricAggregationType.UNKNOWN;
        }
        tMasterMetricsFilter.setPrefixToType(metricToType.getKey(), type);
      }
    }

    // Construct the long-live TMasterClientService
    tMasterClientService =
        new TMasterClientService((Map<String, Object>) sinkConfig.get(KEY_TMASTER), metricsCommunicator);

    // Start the tMasterLocationStarter
    startTMasterChecker();
  }

  // Start the TMasterCheck, which would check whether the TMasterLocation is changed at an interval.
  // If so, restart the TMasterClientService with the new TMasterLocation
  private void startTMasterChecker() {
    final int checkIntervalSec =
        TypeUtils.getInt(sinkConfig.get(KEY_TMASTER_LOCATION_CHECK_INTERVAL_SEC));

    Runnable runnable = new Runnable() {
      @Override
      public void run() {
        TopologyMaster.TMasterLocation location =
            (TopologyMaster.TMasterLocation) SingletonRegistry.INSTANCE.getSingleton(TMASTER_LOCATION_BEAN_NAME);

        if (location != null) {
          if (currentTMasterLocation == null || !location.equals(currentTMasterLocation)) {
            LOG.info("Update current TMasterLocation to: " + location);
            currentTMasterLocation = location;
            tMasterClientService.updateTMasterLocation(currentTMasterLocation);
            tMasterClientService.startNewMasterClient();

            // Update Metrics
            sinkContext.exportCountMetric(TMASTER_LOCATION_UPDATE_COUNT, 1);
          }
        }

        // Schedule itself in future
        tMasterLocationStarter.schedule(this, checkIntervalSec, TimeUnit.SECONDS);
      }
    };

    // First Entry
    tMasterLocationStarter.schedule(runnable, checkIntervalSec, TimeUnit.SECONDS);
  }

  @Override
  public void processRecord(MetricsRecord record) {
    // Format it into TopologyMaster.PublishMetrics

    // The format of source is "host:port/componentName/instanceId"
    // So source.split("/") would be an array with 3 elements: ["host:port", componentName, instanceId]
    String[] sources = record.getSource().split("/");
    String hostPort = sources[0];
    String componentName = sources[1];
    String instanceId = sources[2];

    TopologyMaster.PublishMetrics.Builder publishMetrics = TopologyMaster.PublishMetrics.newBuilder();
    for (MetricsInfo metricsInfo : tMasterMetricsFilter.filter(record.getMetrics())) {
      // We would filter out unneeded metrics
      TopologyMaster.MetricDatum metricDatum = TopologyMaster.MetricDatum.newBuilder().
          setComponentName(componentName).setInstanceId(instanceId).setName(metricsInfo.getName()).
          setValue(metricsInfo.getValue()).setTimestamp(record.getTimestamp()).build();
      publishMetrics.addMetrics(metricDatum);
    }

    for (ExceptionInfo exceptionInfo : record.getExceptions()) {
      TopologyMaster.TmasterExceptionLog exceptionLog = TopologyMaster.TmasterExceptionLog.newBuilder().
          setComponentName(componentName).setHostname(hostPort).setInstanceId(instanceId).
          setStacktrace(exceptionInfo.getStackTrace()).setLasttime(exceptionInfo.getLastTime()).
          setFirsttime(exceptionInfo.getFirstTime()).setCount(exceptionInfo.getCount()).
          setLogging(exceptionInfo.getLogging()).build();
      publishMetrics.addExceptions(exceptionLog);
    }

    metricsCommunicator.offer(publishMetrics.build());

    // Update metrics
    sinkContext.exportCountMetric(RECORD_PROCESS_COUNT, 1);
    sinkContext.exportCountMetric(METRICS_COUNT, publishMetrics.getMetricsCount());
    sinkContext.exportCountMetric(EXCEPTIONS_COUNT, publishMetrics.getExceptionsCount());
  }

  @Override
  public void flush() {
    // We do nothing here but update metrics
    sinkContext.exportCountMetric(TMASTER_RESTART_COUNT,
        tMasterClientService.startedAttempts.longValue());
  }

  @Override
  public void close() {
    tMasterClientService.close();
    metricsCommunicator.clear();
  }

  /////////////////////////////////////////////////////////
  // Following protected methods should be used only for unit testing
  /////////////////////////////////////////////////////////
  protected TMasterClientService getTMasterClientService() {
    return tMasterClientService;
  }

  protected void createSimpleTMasterClientService(Map<String, Object> serviceConfig) {
    tMasterClientService =
        new TMasterClientService(serviceConfig, metricsCommunicator);
  }

  protected TMasterClient getTMasterClient() {
    return tMasterClientService.getTMasterClient();
  }

  protected void startNewTMasterClient(TopologyMaster.TMasterLocation location) {
    tMasterClientService.updateTMasterLocation(location);
    tMasterClientService.startNewMasterClient();
  }

  protected int getTMasterStartedAttempts() {
    return tMasterClientService.startedAttempts.get();
  }

  protected TopologyMaster.TMasterLocation getCurrentTMasterLocation() {
    return currentTMasterLocation;
  }

  protected TopologyMaster.TMasterLocation getCurrentTMasterLocationInService() {
    return tMasterClientService.getCurrentTMasterLocation();
  }
}
