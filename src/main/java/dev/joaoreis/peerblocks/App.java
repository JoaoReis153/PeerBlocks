package dev.joaoreis.peerblocks;

import dev.joaoreis.peerblocks.core.Config;
import dev.joaoreis.peerblocks.gui.GUI;
import java.io.File;
import java.io.IOException;
import java.util.Scanner;

public class App {

    private static final String DEFAULT_FILE_STRUCTURE = "files";
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

        createDefaultFileStructure(id);

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
                                    System.err.println(
                                            "Failed to remove the directory: " + dirToRemoveFile);
                                }
                            }
                        });

        if (gui == null) {
            System.err.println("Failed to create node.");
            System.exit(1);
        } else {
            System.out.println("Node created: " + gui.getNode().getAddressAndPortFormated());
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

    // Method to copy a directory recursively
    private static void copyDirectory(File source, File destination) throws IOException {
        if (source.isDirectory()) {
            if (!destination.exists()) {
                destination.mkdirs();
            }
            String[] files = source.list();
            if (files != null) {
                for (String file : files) {
                    copyDirectory(new File(source, file), new File(destination, file));
                }
            }
        } else {
            java.nio.file.Files.copy(source.toPath(), destination.toPath());
        }
    }

    private static void createDefaultFileStructure(int arg) {
        String dirToRemove = NODE_FILES_BASE + arg;
        File removeDir = new File(dirToRemove);
        if (removeDir.exists() && removeDir.isDirectory()) {
            try {
                deleteDirectory(removeDir);
            } catch (IOException e) {
                System.err.println("Failed to remove the directory: " + dirToRemove);
                System.exit(1);
            }
        }

        String dirToCreate = DEFAULT_FILE_STRUCTURE + "/dl" + arg;
        File createDir = new File(dirToCreate);
        if (createDir.exists() && createDir.isDirectory()) {
            try {
                copyDirectory(createDir, new File(NODE_FILES_BASE + arg));
            } catch (IOException e) {
                System.err.println("Failed to copy files for argument: " + arg);
                System.exit(1);
            }
        } else {
            System.out.println("Argument folder not found in default structure: " + arg);

            // Create the directory in the PROJECT_DIR if it doesn't exist
            File newDir = new File(NODE_FILES_BASE + arg);
            if (!newDir.exists()) {
                boolean created = newDir.mkdirs(); // Create the directory
                if (!created) {
                    System.err.println(
                            "Failed to create the directory: " + newDir.getAbsolutePath());
                    System.exit(1);
                }
            }
        }
    }
}
