package com.khg.storm.spout;

import clojure.lang.IFn;
import org.apache.storm.spout.SpoutOutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichSpout;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;

public class TestSpout extends BaseRichSpout {
    private static final Logger LOG = LoggerFactory.getLogger(TestSpout.class);
    private SpoutOutputCollector collector;
    private int spoutNum;
    @Override
    public void open(Map<String, Object> conf, TopologyContext context, SpoutOutputCollector collector) {
        this.collector = collector;
        String topologyName = (String) conf.get("topologyName");
        LOG.info("topologyName - spout");
        this.spoutNum = 1;
    }

    @Override
    public void nextTuple() {
        String mesg = "spout" + spoutNum;
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        LOG.info("emit: {}",mesg);
        spoutNum ++;
        collector.emit(new Values(mesg));
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(new Fields("spout"));
    }
}
