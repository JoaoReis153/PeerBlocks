package dev.joaoreis.peerblocks.tools;

import dev.joaoreis.peerblocks.core.Config;
import dev.joaoreis.peerblocks.core.Node;
import dev.joaoreis.peerblocks.gui.GUI;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;
import java.util.Set;

public class Scenarios {

    private static final String DEFAULT_BROADCAST_QUERY = "";
    private static final long DEFAULT_DELAY_BEFORE_DOWNLOAD_MS = 1000L;
    // The first node shares sample-file-1 (1 MB), sample-file-2 (2 MB), ...
    private static final int SAMPLE_FILE_COUNT = 3;
    private static final int SAMPLE_FILE_SIZE_BYTES = 1024 * 1024;

    private final List<Integer> inputs;
    private final int mode;
    private final List<GUI> guiList;

    private enum SearchTarget {
        NONE,
        FIRST,
        ALL,
    }

    private enum DownloadTarget {
        NONE,
        LAST,
        ALL,
    }

    private static final class TestScenario {

        private final int mode;
        private final String description;
        private final boolean requiresConnections;
        private final SearchTarget searchTarget;
        private final DownloadTarget downloadTarget;
        private final long preDownloadDelayMs;

        private TestScenario(
                int mode,
                String description,
                boolean requiresConnections,
                SearchTarget searchTarget,
                DownloadTarget downloadTarget,
                long preDownloadDelayMs) {
            this.mode = mode;
            this.description = description;
            this.requiresConnections = requiresConnections;
            this.searchTarget = searchTarget;
            this.downloadTarget = downloadTarget;
            this.preDownloadDelayMs = preDownloadDelayMs;
        }
    }

    private static final Map<Integer, TestScenario> SCENARIOS = buildScenarios();

    private static Map<Integer, TestScenario> buildScenarios() {
        Map<Integer, TestScenario> scenarios = new HashMap<>();

        scenarios.put(
                0,
                new TestScenario(
                        0, "Create nodes only", false, SearchTarget.NONE, DownloadTarget.NONE, 0));

        scenarios.put(
                1,
                new TestScenario(
                        1,
                        "Create a network of nodes and connect them",
                        true,
                        SearchTarget.NONE,
                        DownloadTarget.NONE,
                        0));

        scenarios.put(
                2,
                new TestScenario(
                        2,
                        "Create a network, connect nodes, and search in the first node",
                        true,
                        SearchTarget.FIRST,
                        DownloadTarget.NONE,
                        0));

        scenarios.put(
                3,
                new TestScenario(
                        3,
                        "Create a network, connect nodes, and search in all nodes",
                        true,
                        SearchTarget.ALL,
                        DownloadTarget.NONE,
                        0));

        scenarios.put(
                4,
                new TestScenario(
                        4,
                        "Create a network, connect nodes, and download files in the last node",
                        true,
                        SearchTarget.ALL,
                        DownloadTarget.LAST,
                        0));

        scenarios.put(
                5,
                new TestScenario(
                        5,
                        "Create a network, connect nodes, and download files in every node",
                        true,
                        SearchTarget.ALL,
                        DownloadTarget.ALL,
                        DEFAULT_DELAY_BEFORE_DOWNLOAD_MS));

        return scenarios;
    }

    public Scenarios(Set<String> args, int mode) {
        this.inputs = parseInputs(args);
        this.mode = mode;
        this.guiList = new ArrayList<>();
    }

    public void run() {
        TestScenario scenario = SCENARIOS.get(mode);
        if (scenario == null) {
            System.out.println("Invalid mode.");
            return;
        }

        initializeNodes();

        if (guiList.isEmpty()) {
            System.out.println("No nodes created. Exiting test.");
            return;
        }

        executeScenario(scenario);
    }

    private static List<Integer> parseInputs(Set<String> args) {
        List<Integer> parsed = new ArrayList<>();
        for (String arg : args) {
            try {
                parsed.add(Integer.parseInt(arg));
            } catch (NumberFormatException e) {
                System.err.println("Invalid ID: " + arg + ". Please provide numeric values.");
            }
        }

        Collections.sort(parsed);
        return parsed;
    }

    private void executeScenario(TestScenario scenario) {
        System.out.println("Test " + scenario.mode + ": " + scenario.description);

        if (scenario.requiresConnections) {
            connectNodes();
        }

        runSearchIfNeeded(scenario.searchTarget);
        runDownloadIfNeeded(scenario.downloadTarget, scenario.preDownloadDelayMs);
    }

    private void runSearchIfNeeded(SearchTarget target) {
        switch (target) {
            case NONE -> {
                return;
            }
            case FIRST -> {
                List<GUI> firstNodeOnly = new ArrayList<>();
                firstNodeOnly.add(guiList.get(0));
                broadcastSearchMessage(firstNodeOnly, DEFAULT_BROADCAST_QUERY);
            }
            case ALL -> broadcastSearchMessage(guiList, DEFAULT_BROADCAST_QUERY);
        }
    }

    private void runDownloadIfNeeded(DownloadTarget target, long delayMs) {
        if (target == DownloadTarget.NONE) {
            return;
        }

        sleepIfNeeded(delayMs);

        switch (target) {
            case LAST -> runDownload(guiList.get(guiList.size() - 1));
            case ALL -> {
                for (GUI gui : guiList) {
                    runDownload(gui);
                }
            }
            case NONE -> {
                return;
            }
        }
    }

    private void runDownload(GUI gui) {
        try {
            gui.simulateDownloadButton(gui.getListModel());
        } catch (Exception e) {
            System.err.println("Failed to simulate download.");
            e.printStackTrace();
        }
    }

    private void sleepIfNeeded(long delayMs) {
        if (delayMs <= 0) {
            return;
        }

        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted while waiting before downloads.");
        }
    }

    /**
     * Initializes nodes based on the provided arguments.
     *
     * @param inputs Command-line arguments containing node IDs
     * @return List of GUI objects for the created nodes
     */
    private void initializeNodes() {
        if (inputs.size() == 0) {
            System.out.println("Usage: Please provide at least one ID as arguments.");
            return;
        }

        // Every node starts empty, except the first one, which shares generated sample files
        int seederId = inputs.get(0);
        for (int id : inputs) {
            try {
                prepareWorkingFolder(id, id == seederId);
                GUI gui = new GUI(id, true);
                guiList.add(gui);
                gui.open();
            } catch (NumberFormatException e) {
                System.err.println("Invalid ID: " + id + ". Please provide numeric values.");
            } catch (IllegalArgumentException e) {
                System.err.println("Failed to create node: " + e.getMessage());
            } catch (Exception e) {
                System.err.println("An error occurred while initializing the node with ID: " + id);
                e.printStackTrace();
            }
        }
    }

    /*
     * Gives the node an empty working folder, filled with random sample files when it is the seeder.
     * Nothing is read from the repository, so there are no media files to commit.
     */
    private static void prepareWorkingFolder(int id, boolean withSampleFiles) throws IOException {
        File folder = new File(Config.WORK_FOLDER + id);
        deleteRecursively(folder);
        Files.createDirectories(folder.toPath());
        if (!withSampleFiles) return;

        for (int i = 1; i <= SAMPLE_FILE_COUNT; i++) {
            byte[] content = new byte[SAMPLE_FILE_SIZE_BYTES * i];
            new Random(i).nextBytes(content);
            Files.write(new File(folder, "sample-file-" + i + ".bin").toPath(), content);
        }
        System.out.println(
                "Node " + id + " shares " + SAMPLE_FILE_COUNT + " generated sample files");
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }

    /**
     * Connects all nodes in the provided list to each other.
     *
     * @param guiList List of GUI objects representing the nodes
     */
    private void connectNodes() {
        for (GUI gui : guiList) {
            Node currentNode = gui.getNode();
            for (GUI otherGui : guiList) {
                if (currentNode != otherGui.getNode()) {
                    try {
                        currentNode.connectToNode(
                                otherGui.getNode().getAddress().getHostAddress(),
                                otherGui.getNode().getPort());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    /**
     * Broadcasts a search message from in the nodes provided in the list.
     *
     * @param guiList List of GUI objects representing the nodes
     */
    private static void broadcastSearchMessage(List<GUI> guiList, String broadcastString) {
        for (GUI gui : guiList) {
            gui.getNode().broadcastWordSearchMessageRequest(broadcastString);
        }
    }

    public static void main(String[] args) {

        Scanner scanner = new Scanner(System.in);
        HashSet<String> nodeIds = new HashSet<>();

        // Read the node IDs from user input
        System.out.println("Enter the node IDs (separated by spaces): ");

        String line = scanner.nextLine().trim();

        // Split the line by spaces and add the IDs to the list
        String[] ids = line.split("\\s+");
        for (String id : ids) {
            if (!id.isEmpty()) {
                nodeIds.add(id);
            }
        }

        System.out.println("Node IDs entered: " + nodeIds);

        /*
        Test:
        0 - Create nodes
        1 - Create a network of nodes and connect them
        2 - Create a network of nodes, connect them and search in the first node
        3 - Create a network of nodes, connect them and search in all nodes
        4 - Create a network of nodes, connect them and download all files in the last node.
        5 - Create a network of nodes, connect them and download all files in every node
        */

        System.out.println("Choose the test mode:");
        for (int i = 0; i <= 5; i++) {
            TestScenario scenario = SCENARIOS.get(i);
            if (scenario != null) {
                System.out.println(i + " - " + scenario.description);
            }
        }
        System.out.print("Enter the test number: ");

        // Read the test mode interactively from user input
        int mode = -1; // Default mode
        try {
            mode = Integer.parseInt(scanner.nextLine()); // Read and parse input
        } catch (NumberFormatException e) {
            System.err.println("Invalid input. Please enter a number between 0 and 5.");
            System.exit(1);
        }

        // Validate the chosen mode
        if (mode < 0 || mode > 5) {
            System.err.println("Invalid mode! Please choose a number between 0 and 5.");
            System.exit(1);
        }

        // Create the TestModes object with the chosen mode
        Scenarios scenarios = new Scenarios(nodeIds, mode);

        // Run the selected test
        scenarios.run();

        // Close the scanner
        scanner.close();
    }
}
