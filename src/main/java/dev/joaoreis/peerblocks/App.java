package dev.joaoreis.peerblocks;

import dev.joaoreis.peerblocks.core.Config;
import dev.joaoreis.peerblocks.gui.GUI;
import java.io.File;
import java.io.IOException;
import java.util.Scanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    private static final String NODE_FILES_BASE = Config.WORK_FOLDER;
    private static int id = -1;

    public static void main(String[] args) {

        Scanner scanner = new Scanner(System.in);

        // Read the node IDs from user input
        System.out.println("Enter the node id");
        System.out.print("ID: ");
        Boolean validInput = false;
        GUI gui = null;

        while (!validInput) {
            // Read the node ID from user input
            String line = scanner.nextLine().trim();

            // Split the line by spaces and add the IDs to the list
            String[] ids = line.split("\\s+");

            if (ids.length == 0) {
                System.err.println("Invalid ID. Please provide a valid ID.");
            } else if (ids.length > 1) {
                System.err.println("Invalid ID. Please provide only one ID.");
            } else {
                try {
                    id = Integer.parseInt(ids[0]);

                    gui = new GUI(id, true);
                    validInput = true;
                } catch (Exception e) {
                    System.err.println("Invalid ID. Please provide a valid ID.");
                    System.out.print("ID: ");
                    validInput = false;
                }
            }
        }

        resetWorkingFolder(id);

        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread() {
                            @Override
                            public void run() {
                                // This block will run before the JVM shuts down

                                String dirToRemove = NODE_FILES_BASE + id;
                                File dirToRemoveFile = new File(dirToRemove);

                                try {
                                    deleteDirectory(dirToRemoveFile);
                                } catch (IOException ex) {
                                    log.error("Failed to remove the directory: " + dirToRemoveFile);
                                }
                            }
                        });

        if (gui == null) {
            log.error("Failed to create node.");
            System.exit(1);
        } else {
            log.info("Node created: " + gui.getNode().getAddressAndPortFormated());
            gui.open();
        }

        // Close the scanner
        scanner.close();
    }

    // Method to delete a directory recursively
    private static void deleteDirectory(File directory) throws IOException {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        if (!file.delete()) {
                            throw new IOException(
                                    "Failed to delete file: " + file.getAbsolutePath());
                        }
                    }
                }
            }
            if (!directory.delete()) {
                throw new IOException("Failed to delete directory: " + directory.getAbsolutePath());
            }
        }
    }

    // Starts the node with an empty working folder
    private static void resetWorkingFolder(int arg) {
        File workingFolder = new File(NODE_FILES_BASE + arg);
        try {
            deleteDirectory(workingFolder);
        } catch (IOException e) {
            log.error("Failed to remove the directory: " + workingFolder);
            System.exit(1);
        }
        if (!workingFolder.mkdirs()) {
            log.error("Failed to create the directory: " + workingFolder.getAbsolutePath());
            System.exit(1);
        }
    }
}
