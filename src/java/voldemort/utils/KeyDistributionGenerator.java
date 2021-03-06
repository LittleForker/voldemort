package voldemort.utils;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Map.Entry;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import voldemort.cluster.Cluster;
import voldemort.cluster.Node;
import voldemort.routing.RoutingStrategy;
import voldemort.routing.RoutingStrategyFactory;
import voldemort.routing.RoutingStrategyType;
import voldemort.store.StoreDefinition;
import voldemort.xml.ClusterMapper;
import voldemort.xml.StoreDefinitionsMapper;

import com.google.common.base.Joiner;
import com.google.common.collect.Maps;

public class KeyDistributionGenerator {

    public static void main(String args[]) throws IOException {
        OptionParser parser = new OptionParser();
        parser.accepts("help", "print help information");
        parser.accepts("cluster-xml", "[REQUIRED] cluster xml file location")
              .withRequiredArg()
              .describedAs("path");
        parser.accepts("stores-xml", "[REQUIRED] stores xml file location")
              .withRequiredArg()
              .describedAs("path");
        parser.accepts("num-keys", "Number of keys to query [Default:100000]")
              .withRequiredArg()
              .describedAs("number")
              .ofType(Integer.class);

        OptionSet options = parser.parse(args);

        if(options.has("help")) {
            parser.printHelpOn(System.out);
            System.exit(0);
        }

        Set<String> missing = CmdUtils.missing(options, "cluster-xml", "stores-xml");
        if(missing.size() > 0) {
            System.err.println("Missing required arguments: " + Joiner.on(", ").join(missing));
            parser.printHelpOn(System.err);
            System.exit(1);
        }

        // compulsory params
        String clusterXml = (String) options.valueOf("cluster-xml");
        String storesXml = (String) options.valueOf("stores-xml");
        Integer numKeys = CmdUtils.valueOf(options, "num-keys", 100000);

        if(numKeys <= 0) {
            System.err.println("Number of keys should be greater than 0");
            System.exit(1);
        }

        Cluster cluster = new ClusterMapper().readCluster(new File(clusterXml));
        List<StoreDefinition> storeDefs = new StoreDefinitionsMapper().readStoreList(new File(storesXml));

        // Print distribution for every store
        for(StoreDefinition def: storeDefs) {
            HashMap<Integer, Double> storeDistribution = generateDistribution(cluster, def, numKeys);
            System.out.println("For Store " + def.getName());
            printDistribution(storeDistribution);
            System.out.println("Std dev - " + getStdDeviation(storeDistribution));
            System.out.println("=========================");
        }
        HashMap<Integer, Double> overallDistribution = generateOverallDistribution(cluster,
                                                                                   storeDefs,
                                                                                   numKeys);
        System.out.println("Overall distribution ");
        printDistribution(overallDistribution);
        System.out.println("Std dev - " + getStdDeviation(overallDistribution));
    }

    public static HashMap<Integer, Double> generateOverallDistribution(Cluster cluster,
                                                                       List<StoreDefinition> storeDefs,
                                                                       int numKeys) {
        return generateOverallDistributionWithUniqueStores(cluster,
                                                           getUniqueStoreDefinitionsWithCounts(storeDefs),
                                                           numKeys);
    }

    public static HashMap<Integer, Double> generateOverallDistributionWithUniqueStores(Cluster cluster,
                                                                                       HashMap<StoreDefinition, Integer> uniqueStoreDefsWithCount,
                                                                                       int numKeys) {
        HashMap<Integer, Double> overallDistributionCount = Maps.newHashMap();
        for(Node node: cluster.getNodes()) {
            overallDistributionCount.put(node.getId(), 0.0);
        }

        int totalStores = 0;
        for(Entry<StoreDefinition, Integer> entry: uniqueStoreDefsWithCount.entrySet()) {
            HashMap<Integer, Double> nodeDistribution = generateDistribution(cluster,
                                                                             entry.getKey(),
                                                                             numKeys);
            for(int nodeId: nodeDistribution.keySet()) {
                overallDistributionCount.put(nodeId,
                                             overallDistributionCount.get(nodeId)
                                                     + (nodeDistribution.get(nodeId) * entry.getValue()));
            }
            totalStores += entry.getValue();
        }

        // Normalize it
        for(int nodeId: overallDistributionCount.keySet()) {
            overallDistributionCount.put(nodeId, overallDistributionCount.get(nodeId)
                                                 / (totalStores * 100.0) * 100.0);
        }
        return overallDistributionCount;
    }

    public static HashMap<StoreDefinition, Integer> getUniqueStoreDefinitionsWithCounts(List<StoreDefinition> storeDefs) {

        HashMap<StoreDefinition, Integer> uniqueStoreDefs = Maps.newHashMap();
        for(StoreDefinition storeDef: storeDefs) {
            if(uniqueStoreDefs.isEmpty()) {
                uniqueStoreDefs.put(storeDef, 1);
            } else {
                StoreDefinition sameStore = null;

                // Go over all the other stores to find if this is unique
                for(StoreDefinition uniqueStoreDef: uniqueStoreDefs.keySet()) {
                    if(uniqueStoreDef.getReplicationFactor() == storeDef.getReplicationFactor()
                       && uniqueStoreDef.getRoutingStrategyType()
                                        .compareTo(storeDef.getRoutingStrategyType()) == 0) {

                        // Further check for the zone routing case
                        if(uniqueStoreDef.getRoutingStrategyType()
                                         .compareTo(RoutingStrategyType.ZONE_STRATEGY) == 0) {
                            boolean zonesSame = true;
                            for(int zoneId: uniqueStoreDef.getZoneReplicationFactor().keySet()) {
                                if(storeDef.getZoneReplicationFactor().get(zoneId) == null
                                   || storeDef.getZoneReplicationFactor().get(zoneId) != uniqueStoreDef.getZoneReplicationFactor()
                                                                                                       .get(zoneId)) {
                                    zonesSame = false;
                                    break;
                                }
                            }
                            if(zonesSame) {
                                sameStore = uniqueStoreDef;
                            }
                        } else {
                            sameStore = uniqueStoreDef;
                        }

                        if(sameStore != null) {
                            // Bump up the count
                            int currentCount = uniqueStoreDefs.get(sameStore);
                            uniqueStoreDefs.put(sameStore, currentCount + 1);
                            break;
                        }
                    }
                }

                if(sameStore == null) {
                    // New store
                    uniqueStoreDefs.put(storeDef, 1);
                }
            }
        }

        return uniqueStoreDefs;
    }

    /**
     * Generates distribution for a specific store definition
     * 
     * @param cluster The cluster metadata
     * @param storeDef The store definition metadata
     * @param numKeys Number of keys used to generate distribution
     * @return Map of node id to their corresponding %age distribution
     */
    public static HashMap<Integer, Double> generateDistribution(Cluster cluster,
                                                                StoreDefinition storeDef,
                                                                int numKeys) {
        RoutingStrategyFactory factory = new RoutingStrategyFactory();
        RoutingStrategy strategy = factory.updateRoutingStrategy(storeDef, cluster);

        HashMap<Integer, Long> requestRouting = Maps.newHashMap();
        Long total = new Long(0);
        for(int i = 0; i < numKeys; i++) {
            List<Node> nodes = strategy.routeRequest(("key" + i).getBytes());
            for(Node node: nodes) {
                Long count = requestRouting.get(node.getId());
                if(count == null) {
                    count = new Long(0);
                }
                count++;
                requestRouting.put(node.getId(), count);
                total++;
            }
        }
        HashMap<Integer, Double> finalDistribution = Maps.newHashMap();
        for(int nodeId: requestRouting.keySet()) {
            finalDistribution.put(nodeId, new Double((requestRouting.get(nodeId) * 100.0) / total));
        }
        return finalDistribution;
    }

    public static void printDistribution(HashMap<Integer, Double> distribution) {
        for(int nodeId: distribution.keySet()) {
            System.out.println("Node " + nodeId + " - " + distribution.get(nodeId));
        }
    }

    public static double getStdDeviation(HashMap<Integer, Double> distribution) {
        HashMap<Integer, Double> expectedDistribution = Maps.newHashMap();
        int numberOfNodes = distribution.keySet().size();
        double distributionPerNode = 100.0 / numberOfNodes;

        for(int nodeId = 0; nodeId < numberOfNodes; nodeId++) {
            expectedDistribution.put(nodeId, distributionPerNode);
        }
        return getStdDeviation(distribution, expectedDistribution);
    }

    public static double getStdDeviation(HashMap<Integer, Double> distribution,
                                         HashMap<Integer, Double> expectedDistribution) {
        HashMap<Integer, Double> offBy = Maps.newHashMap();

        for(Integer nodeId: distribution.keySet()) {
            offBy.put(nodeId, new Double(expectedDistribution.get(nodeId)
                                         - distribution.get(nodeId)));
        }
        double sum = 0, squareSum = 0;
        for(double num: offBy.values()) {
            squareSum += num * num;
            sum += num;
        }
        double mean = sum / distribution.size();
        return Math.sqrt(squareSum / distribution.size() - mean * mean);
    }
}
