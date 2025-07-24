package com.khg.storm.bolt;

import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class TestBolt2 extends BaseRichBolt {
    private static final Logger LOG = LoggerFactory.getLogger(TestBolt2.class);
    private OutputCollector outputCollector;
    @Override
    public void prepare(Map<String, Object> topoConf, TopologyContext context, OutputCollector collector) {
        String topologyName = (String) topoConf.get("topologyName");
        LOG.info("topologyName - bolt2");
        this.outputCollector = collector;
    }

    @Override
    public void execute(Tuple input) {
        String mesg = input.getStringByField("bolt1");
        mesg = mesg + "-bolt2";
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        LOG.info("emit: {}",mesg);
        outputCollector.emit(new Values(mesg));
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(new Fields("bolt2"));
    }
}
