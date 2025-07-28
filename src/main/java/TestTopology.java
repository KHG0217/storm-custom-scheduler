import com.khg.storm.bolt.TestBolt1;
import com.khg.storm.bolt.TestBolt2;
import com.khg.storm.spout.TestSpout;
import org.apache.storm.Config;
import org.apache.storm.StormSubmitter;
import org.apache.storm.topology.TopologyBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

public class TestTopology {
    private static final Logger LOG = LoggerFactory.getLogger(TestTopology.class);
    private final String SPOUT_ID = "spout";
    private final String BOLT1 = "bolt1";
    private final String BOLT2 = "bolt2";
    private String topologyName = "";
    private int numWorker = 1;
    private int numSpout = 1;
    private int numBolt1 = 1;
    private int numBolt2 = 1;

    private void init(List<String> args) {
        initArgs(args);
    }

    private void initArgs(List<String> args) {
        if(args.size() < 5) {
            LOG.error("Usage: <string topologyName> <number of worker>" +
                    "<number of spout> <number of bolt1> <number of bolt2> ");
            LOG.error("System exit.");
            System.exit(-1);
        }
        this.topologyName = args.get(0);
        this.numWorker = Integer.parseInt(args.get(1));
        this.numSpout = Integer.parseInt(args.get(2));
        this.numBolt1 = Integer.parseInt(args.get(3));
        this.numBolt2 = Integer.parseInt(args.get(4));
    }

    private void start() {
        TopologyBuilder builder = new TopologyBuilder();
        TestSpout spout = new TestSpout();
        TestBolt1 bolt1 = new TestBolt1();
        TestBolt2 bolt2 = new TestBolt2();

        builder.setSpout(SPOUT_ID, spout, numSpout * numWorker);
        builder.setBolt(BOLT1, bolt1, numBolt1 * numWorker).shuffleGrouping(SPOUT_ID);
        builder.setBolt(BOLT2, bolt2, numBolt2 * numWorker).shuffleGrouping(BOLT1);

        Config config = new Config();
        config.setNumWorkers(numWorker);
        config.put("topologyName",topologyName);

        try {
            StormSubmitter.submitTopology(topologyName, config, builder.createTopology());
        } catch (Exception e) {
            LOG.error(e.getMessage());
        }
    }

    public static void main(String[] args) {
        TestTopology topology = new TestTopology();
        List<String> argList = Arrays.asList(args);
        topology.init(argList);
        topology.start();
    }
}
