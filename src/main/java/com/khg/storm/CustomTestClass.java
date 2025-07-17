package com.khg.storm;

import org.apache.storm.metric.StormMetricsRegistry;
import org.apache.storm.scheduler.Cluster;
import org.apache.storm.scheduler.IScheduler;
import org.apache.storm.scheduler.Topologies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;

public class CustomTestClass implements IScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(CustomTestClass.class);
    @Override
    public void prepare(Map<String, Object> conf, StormMetricsRegistry metricsRegistry) {
        LOG.info("CustomTestClass, prepare call");
    }

    @Override
    public void schedule(Topologies topologies, Cluster cluster) {
        LOG.info("CustomTestClass, schedule call");
    }

    @Override
    public Map config() {
        return Collections.emptyMap();
    }

    @Override
    public void cleanup() {
        LOG.info("CustomTestClass, cleanup call");
    }
}
