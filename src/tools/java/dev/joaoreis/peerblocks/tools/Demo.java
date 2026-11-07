package dev.joaoreis.peerblocks.tools;

import dev.joaoreis.peerblocks.core.Config;
import dev.joaoreis.peerblocks.core.Node;
import dev.joaoreis.peerblocks.gui.GUI;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Opens connected nodes. The first one shares sample files. */
public class Demo {

    private static final int[] DEFAULT_IDS = {1, 2, 3};
    private static final int SAMPLE_FILE_COUNT = 3;
    private static final int SAMPLE_FILE_SIZE_BYTES = 1024 * 1024;

    public static void main(String[] args) throws Exception {
        int[] ids = args.length == 0 ? DEFAULT_IDS : parseIds(args);

        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            prepareFolder(ids[i], i == 0);
            GUI gui = new GUI(ids[i], true);
            gui.open();
            nodes.add(gui.getNode());
        }
        Thread.sleep(200); // let the servers start

        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                Node other = nodes.get(j);
                nodes.get(i).connectToNode(other.getAddress().getHostAddress(), other.getPort());
            }
        }
    }

    private static int[] parseIds(String[] args) {
        int[] ids = new int[args.length];
        for (int i = 0; i < args.length; i++) {
            try {
                ids[i] = Integer.parseInt(args[i]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Node ids must be numbers, got: " + args[i]);
            }
        }
        return ids;
    }

    private static void prepareFolder(int id, boolean withSampleFiles) throws IOException {
        File folder = new File(Config.WORK_FOLDER + id);
        deleteRecursively(folder);
        Files.createDirectories(folder.toPath());
        if (!withSampleFiles) return;

        for (int i = 1; i <= SAMPLE_FILE_COUNT; i++) {
            byte[] content = new byte[SAMPLE_FILE_SIZE_BYTES * i];
            new Random(i).nextBytes(content);
            Files.write(new File(folder, "sample-file-" + i + ".bin").toPath(), content);
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }
}
