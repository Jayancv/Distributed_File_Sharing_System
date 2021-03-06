package org.uom.cse.distributed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.uom.cse.distributed.peer.Node;
import org.uom.cse.distributed.peer.api.RoutingTable;
import org.uom.cse.distributed.peer.api.RoutingTableEntry;
import org.uom.cse.distributed.peer.rest.RestCommunicationProvider;
import org.uom.cse.distributed.peer.rest.RestNodeServer;
import org.uom.cse.distributed.peer.utils.HashUtils;
import org.uom.cse.distributed.server.BootstrapServer;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.uom.cse.distributed.peer.api.State.CONFIGURED;
import static org.uom.cse.distributed.peer.api.State.IDLE;

public class RESTServerTest {
    private static final Logger logger = LoggerFactory.getLogger(RESTServerTest.class);

    private static final int NODE_COUNT = 2;
    private static BootstrapServer bootstrapServer;

    private AtomicInteger atomicInteger = new AtomicInteger(0);
    private final List<Node> nodes = new ArrayList<>();

    @BeforeMethod
    public void setUp() {
        bootstrapServer = new BootstrapServer(Constants.BOOTSTRAP_PORT);
        bootstrapServer.start();

        int port = 35002;
        for (int i = 0; i < NODE_COUNT; i++) {
            RestNodeServer restNodeServer = new RestNodeServer();
            RestCommunicationProvider communicationProvider = new RestCommunicationProvider();
            nodes.add(new Node(port + i, communicationProvider, restNodeServer));
        }
    }

    @Test
    public void RESTtestNodeInitializationParallel() throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(NODE_COUNT,
                r -> new Thread(r, "node-" + atomicInteger.getAndIncrement()));

        for (Node node : nodes) {
            executorService.submit(() -> {
                node.start();
                logger.info("\n****************** Node {} Done *******************\n", node.getPort());
            });
        }

        Thread.sleep(50000);

        for (Node node : nodes) {
            Assert.assertEquals(node.getState(), CONFIGURED, String.format("%d is not configured", node.getNodeId()));
        }

        checkNodes(nodes);
        executorService.shutdownNow();
        executorService.awaitTermination(5000, TimeUnit.MILLISECONDS);
    }

    private static void checkNodes(List<Node> nodes) {
        nodes.stream()
                .sorted(Comparator.comparingInt(Node::getNodeId))
                .forEach(node -> {
                    logger.info("{} -> {}\t: Characters -> {}", node.getNodeId(), node.getMyChar(),
                            node.getEntryTable().getEntries().keySet());
                });

        RoutingTable routingTable = new RoutingTable();
        nodes.forEach(node -> routingTable.addEntry(new RoutingTableEntry(new InetSocketAddress(node.getIpAddress(),
                node.getPort()), node.getNodeId())));

        for (Node node : nodes) {
            Optional<RoutingTableEntry> myPredecessor = routingTable.findPredecessorOf(node.getNodeId());
            if (node.getRoutingTable().getEntries().size() > 1) {
                Assert.assertTrue(myPredecessor.isPresent());
            } else {
                continue;
            }

            Set<Character> characters = HashUtils.findCharactersOf(node.getNodeId(), myPredecessor.get().getNodeId());
            characters.add(node.getMyChar());
            logger.debug("{}({}) -> {}", node.getNodeId(), node.getMyChar(), characters);
            for (Character character : characters) {
                Assert.assertTrue(node.getEntryTable().getEntries().containsKey(character),
                        String.format("%d -> %s", node.getNodeId(), character));
            }

            Assert.assertEquals(characters.size(), node.getEntryTable().getEntries().size());
        }
    }

    @AfterMethod
    public void tearDown() {
        nodes.forEach(node -> {
            if (!node.getState().equals(IDLE)) {
                node.stop();
            }
        });

        nodes.clear();
        bootstrapServer.stop();
    }
}
