package dev.joaoreis.peerblocks.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;

public class GUIDownloadProgress {

    private final GUI gui;
    private final String fileName;
    private final int totalBlocks;
    private final JFrame frame;
    private final JLabel titleLabel;
    private final JLabel detailsLabel;
    private final JLabel statsLabel;
    private final JProgressBar progressBar;

    public GUIDownloadProgress(GUI gui, String fileName, int totalBlocks) {
        this.gui = gui;
        this.fileName = fileName;
        this.totalBlocks = Math.max(1, totalBlocks);

        this.frame = new JFrame("Download Progress");
        this.frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        this.frame.setSize(new Dimension(500, 240));
        this.frame.setLayout(new BorderLayout(8, 8));

        this.titleLabel = new JLabel("Downloading: " + fileName);
        this.titleLabel.setFont(new Font("Arial", Font.BOLD, 14));

        this.detailsLabel = new JLabel("0% (0/" + this.totalBlocks + " blocks)");
        this.detailsLabel.setHorizontalAlignment(SwingConstants.CENTER);
        this.detailsLabel.setFont(new Font("Arial", Font.PLAIN, 13));

        this.statsLabel = new JLabel(" ");
        this.statsLabel.setVerticalAlignment(SwingConstants.TOP);
        this.statsLabel.setFont(new Font("Arial", Font.PLAIN, 13));

        this.progressBar = new JProgressBar(0, 100);
        this.progressBar.setValue(0);
        this.progressBar.setStringPainted(true);

        JPanel centerPanel = new JPanel(new BorderLayout(0, 6));
        centerPanel.add(progressBar, BorderLayout.NORTH);
        centerPanel.add(statsLabel, BorderLayout.CENTER);

        this.frame.add(titleLabel, BorderLayout.NORTH);
        this.frame.add(centerPanel, BorderLayout.CENTER);
        this.frame.add(detailsLabel, BorderLayout.SOUTH);
    }

    public void open() {
        SwingUtilities.invokeLater(
                () -> {
                    frame.setVisible(gui.getSHOW());
                });
    }

    public void updateProgress(int completedBlocks) {
        int safeCompleted = Math.max(0, Math.min(completedBlocks, totalBlocks));
        int percent = (int) ((safeCompleted * 100L) / totalBlocks);

        SwingUtilities.invokeLater(
                () -> {
                    progressBar.setValue(percent);
                    detailsLabel.setText(
                            percent + "% (" + safeCompleted + "/" + totalBlocks + " blocks)");
                });
    }

    public void finish(long durationInMiliseconds, Map<String, Integer> nodesNBlocks) {
        String readableTime = formatTime(durationInMiliseconds);
        String summaryHtml = buildStatsHtml(nodesNBlocks, readableTime);

        SwingUtilities.invokeLater(
                () -> {
                    titleLabel.setText("Download finished: " + fileName);
                    progressBar.setValue(100);
                    detailsLabel.setText("100% (" + totalBlocks + "/" + totalBlocks + " blocks)");
                    statsLabel.setText(summaryHtml);

                    Timer closeTimer = new Timer(3500, event -> frame.dispose());
                    closeTimer.setRepeats(false);
                    closeTimer.start();
                });
    }

    public void close() {
        SwingUtilities.invokeLater(frame::dispose);
    }

    private String formatTime(long totalMillis) {
        if (totalMillis < 1000) {
            return String.format("%d ms", totalMillis);
        } else if (totalMillis < 60000) {
            long seconds = totalMillis / 1000;
            long millis = totalMillis % 1000;
            return String.format("%d.%03d seconds", seconds, millis);
        } else {
            long minutes = TimeUnit.MILLISECONDS.toMinutes(totalMillis);
            long seconds = (totalMillis / 1000) % 60;
            long millis = totalMillis % 1000;
            return String.format("%d:%02d.%03d", minutes, seconds, millis);
        }
    }

    private String buildStatsHtml(Map<String, Integer> nodesNBlocks, String readableTime) {
        StringBuilder sb = new StringBuilder("<html>");
        sb.append("<b>Download finished</b><br/>");
        sb.append("Time to Download: ").append(readableTime).append("<br/>");

        if (nodesNBlocks == null || nodesNBlocks.isEmpty()) {
            sb.append("No peer block stats available");
        } else {
            for (Map.Entry<String, Integer> entry : new TreeMap<>(nodesNBlocks).entrySet()) {
                sb.append("NodeAddress [address=")
                        .append(entry.getKey())
                        .append("]: ")
                        .append(entry.getValue())
                        .append(" downloads")
                        .append("<br/>");
            }
        }

        sb.append("</html>");
        return sb.toString();
    }
}
