package dev.joaoreis.peerblocks.gui;

import dev.joaoreis.peerblocks.core.LogContext;
import dev.joaoreis.peerblocks.core.Node;
import dev.joaoreis.peerblocks.core.NodeListener;
import dev.joaoreis.peerblocks.filesearch.FileSearchResult;
import dev.joaoreis.peerblocks.services.SubNode;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GUI implements NodeListener {

    private static final Logger log = LoggerFactory.getLogger(GUI.class);

    private JFrame frame;
    private JList<FileSearchResult> fileList;
    private DefaultListModel<FileSearchResult> listModel;
    private JList<String> currentFolderFileList;
    private DefaultListModel<String> currentFolderFilesModel;
    private DefaultListModel<String> peersModel;
    private ArrayList<FileSearchResult> localFiles = new ArrayList<>();
    private ArrayList<FileSearchResult> allFiles;
    private Node node;
    private boolean SHOW = true;
    private boolean isOpen = false;
    private String lastSearchKeyword = "";
    private final Map<String, GUIDownloadProgress> activeDownloadProgress;

    public GUI(int nodeId) {
        this.node = new Node(nodeId, this);
        this.activeDownloadProgress = new HashMap<>();

        createGUI();
    }

    public GUI(int nodeId, boolean show) {
        this.node = new Node(nodeId, this);
        this.activeDownloadProgress = new HashMap<>();

        createGUI();
        this.SHOW = show;
    }

    private void createGUI() {
        frame =
                new JFrame(
                        "Port NodeAddress [ address "
                                + node.getAddress().getHostAddress()
                                + ":"
                                + node.getPort()
                                + " ]");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.addWindowListener(
                new java.awt.event.WindowAdapter() {
                    @Override
                    public void windowClosing(java.awt.event.WindowEvent e) {
                        node.close();
                    }
                });
        addFrameContent();
        frame.pack();
    }

    /*
     * Opens the GUI
     * If the GUI is already open, it does nothing
     * Otherwise, it starts the server and opens the GUI
     * The GUI is opened only if the SHOW variable is true
     */
    public void open() {
        if (this.isOpen) return;
        this.isOpen = true;

        new Thread(
                        () -> {
                            try {
                                node.startServing();
                            } catch (Exception e) {
                                log.error("Failed to start server: " + e.getMessage());
                                frame.dispose();
                            }
                        })
                .start();

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        int x = (screenSize.width - frame.getWidth()) / 2;
        int y = (screenSize.height - frame.getHeight()) / 2;
        frame.setLocation(x, y);

        frame.setVisible(SHOW);
        refreshCurrentFolderFilesList();
        peersChanged();
    }

    /*
     * Shows the peers this node is connected to as address:port
     * Peers that haven't finished their handshake yet have no port and are left out
     */
    @Override
    public void peersChanged() {
        List<String> peers = new ArrayList<>();
        for (SubNode peer : node.getPeers()) {
            if (peer.getDestinationPort() > 0) {
                peers.add(peer.getDestinationAddress() + ":" + peer.getDestinationPort());
            }
        }
        Collections.sort(peers);

        SwingUtilities.invokeLater(
                () -> {
                    peersModel.clear();
                    if (peers.isEmpty()) {
                        peersModel.addElement("(no peers connected)");
                        return;
                    }
                    for (String peer : peers) peersModel.addElement(peer);
                });
    }

    private void addFrameContent() {
        frame.setLayout(new BorderLayout());

        JPanel searchPanel = new JPanel();
        searchPanel.setLayout(new FlowLayout(FlowLayout.LEFT));

        JLabel searchLabel = new JLabel("Text to search:");
        JTextField searchTextField = new JTextField(20);
        JButton searchButton = new JButton("Search");

        searchPanel.add(searchLabel);
        searchPanel.add(searchTextField);
        searchPanel.add(searchButton);

        frame.add(searchPanel, BorderLayout.NORTH);

        JPanel bottomPanel = new JPanel();
        bottomPanel.setLayout(new BorderLayout());

        JPanel leftArea = new JPanel();
        leftArea.setPreferredSize(new Dimension(300, 150));
        leftArea.setBorder(
                BorderFactory.createTitledBorder(
                        BorderFactory.createLineBorder(Color.BLACK), "Files in the network:"));

        allFiles = new ArrayList<>();
        listModel = new DefaultListModel<>();
        fileList = new JList<>(listModel);
        fileList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        JScrollPane scrollPane = new JScrollPane(fileList);
        leftArea.setLayout(new BorderLayout());
        leftArea.add(scrollPane, BorderLayout.CENTER);

        JPanel currentFolderPanel = new JPanel(new BorderLayout());
        currentFolderPanel.setPreferredSize(new Dimension(240, 150));
        currentFolderPanel.setBorder(BorderFactory.createTitledBorder("Current Folder Files"));

        currentFolderFilesModel = new DefaultListModel<>();
        currentFolderFileList = new JList<>(currentFolderFilesModel);
        currentFolderFileList.setFocusable(false);
        currentFolderPanel.add(new JScrollPane(currentFolderFileList), BorderLayout.CENTER);

        JPanel peersPanel = new JPanel(new BorderLayout());
        peersPanel.setPreferredSize(new Dimension(240, 120));
        peersPanel.setBorder(BorderFactory.createTitledBorder("Connected Peers"));

        peersModel = new DefaultListModel<>();
        JList<String> peersList = new JList<>(peersModel);
        peersList.setFocusable(false);
        peersPanel.add(new JScrollPane(peersList), BorderLayout.CENTER);

        currentFolderPanel.setPreferredSize(new Dimension(240, 120));
        JPanel westPanel = new JPanel(new GridLayout(2, 1));
        westPanel.add(currentFolderPanel);
        westPanel.add(peersPanel);

        JPanel rightButtonsPanel = new JPanel();
        rightButtonsPanel.setLayout(new GridLayout(2, 1, 10, 10));

        JButton downloadButton = new JButton("Download");
        JButton connectButton = new JButton("Connect to Node");

        downloadButton.setPreferredSize(new Dimension(150, 75));
        connectButton.setPreferredSize(new Dimension(150, 75));

        rightButtonsPanel.add(downloadButton);
        rightButtonsPanel.add(connectButton);

        bottomPanel.add(westPanel, BorderLayout.WEST);
        bottomPanel.add(leftArea, BorderLayout.CENTER);
        bottomPanel.add(rightButtonsPanel, BorderLayout.EAST);

        frame.add(bottomPanel, BorderLayout.CENTER);

        connectButton.addActionListener(
                new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        GUINode newNodeWindow = new GUINode(GUI.this);
                        newNodeWindow.open();
                    }
                });

        downloadButton.addActionListener(
                new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        List<FileSearchResult> selectedOptions = fileList.getSelectedValuesList();
                        List<List<FileSearchResult>> filesToDownload = new ArrayList<>();
                        for (FileSearchResult option : selectedOptions) {
                            List<FileSearchResult> searchResultOfDifferentNodes =
                                    new ArrayList<FileSearchResult>();
                            for (FileSearchResult searchResult : allFiles) {
                                if (option.equals(searchResult)) {
                                    searchResultOfDifferentNodes.add(searchResult);
                                }
                            }
                            filesToDownload.add(searchResultOfDifferentNodes);
                        }

                        node.downloadFiles(filesToDownload);
                    }
                });

        searchButton.addActionListener(
                new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        listModel.clear();
                        allFiles.clear();
                        String searchText = searchTextField.getText().toLowerCase();
                        node.broadcastWordSearchMessageRequest(searchText);
                        try (var logContext = LogContext.open(node.getAddressAndPort())) {
                            log.info("Search request sent for keyword: [" + searchText + "]");
                        }
                        lastSearchKeyword = searchText;
                    }
                });
    }

    /*
     * Simulates a download button click
     * The download button is simulated by sending a download request to all the nodes
     * Its used to test the GUI without actually downloading files
     */
    public void simulateDownloadButton(List<FileSearchResult> options) {
        List<List<FileSearchResult>> filesToDownload = new ArrayList<>();
        for (FileSearchResult option : options) {
            List<FileSearchResult> searchResultOfDifferentNodes = new ArrayList<FileSearchResult>();
            for (FileSearchResult searchResult : allFiles) {
                if (option.equals(searchResult)) {
                    searchResultOfDifferentNodes.add(searchResult);
                }
            }
            filesToDownload.add(searchResultOfDifferentNodes);
        }
        node.downloadFiles(filesToDownload);
    }

    /*
     * Reloads the list model
     * The list model is reloaded by sending a new word search message request
     * with the previous searched word to all the nodes
     */
    @Override
    public synchronized void reloadListModel() {
        listModel.clear();
        allFiles.clear();
        node.broadcastWordSearchMessageRequest(lastSearchKeyword);
    }

    /*
     * Loads the list model with the given list of files
     * The list model is used to display the files in the GUI
     */
    @Override
    public synchronized void loadListModel(FileSearchResult[] list) {
        if (list == null || list.length == 0) return;
        File[] files = node.files().sharedFiles();
        if (files != null) {
            for (File file : files) {
                FileSearchResult fileSearchResult = new FileSearchResult(file, node);
                allFiles.add(fileSearchResult);
                if (!localFiles.contains(fileSearchResult)) {
                    localFiles.add(fileSearchResult);
                }
                ;
            }
        }

        refreshCurrentFolderFilesList();

        ArrayList<FileSearchResult> aux = new ArrayList<>();

        for (FileSearchResult searchResult : list) {
            if (!allFiles.contains(searchResult)) {
                aux.add(searchResult);
            }
            allFiles.add(searchResult);
        }

        SwingUtilities.invokeLater(
                () -> {
                    for (FileSearchResult searchResult : aux) {
                        searchResult.setDisplayNumber(countOccurrencesInAllFiles(searchResult));
                        listModel.addElement(searchResult);
                    }
                });
    }

    public ArrayList<FileSearchResult> getListModel() {
        ArrayList<FileSearchResult> list = new ArrayList<>();
        for (int i = 0; i < listModel.size(); i++) {
            list.add(listModel.getElementAt(i));
        }
        return list;
    }

    @Override
    public synchronized void startDownloadProgress(byte[] hash, String fileName, int totalBlocks) {
        String hashKey = hashKey(hash);
        GUIDownloadProgress progressWindow = new GUIDownloadProgress(this, fileName, totalBlocks);
        activeDownloadProgress.put(hashKey, progressWindow);
        progressWindow.open();
    }

    @Override
    public synchronized void updateDownloadProgress(byte[] hash, int completedBlocks) {
        GUIDownloadProgress progressWindow = activeDownloadProgress.get(hashKey(hash));
        if (progressWindow != null) {
            progressWindow.updateProgress(completedBlocks);
        }
    }

    @Override
    public synchronized void finishDownloadProgress(byte[] hash) {
        GUIDownloadProgress progressWindow = activeDownloadProgress.remove(hashKey(hash));
        if (progressWindow != null) {
            progressWindow.close();
        }
    }

    @Override
    public synchronized void completeDownloadProgress(
            byte[] hash, long durationInMiliseconds, Map<String, Integer> nodesNBlocks) {
        GUIDownloadProgress progressWindow = activeDownloadProgress.remove(hashKey(hash));
        if (progressWindow != null) {
            progressWindow.finish(durationInMiliseconds, nodesNBlocks);
        }
    }

    @Override
    public boolean confirmIncomingConnection(String address, int port) {
        if (!SHOW) return true;

        final int[] userChoice = new int[] {JOptionPane.NO_OPTION};
        Runnable promptTask =
                () -> {
                    userChoice[0] =
                            JOptionPane.showConfirmDialog(
                                    frame,
                                    "Incoming connection from "
                                            + address
                                            + ":"
                                            + port
                                            + "\nAccept connection?",
                                    "Incoming Connection",
                                    JOptionPane.YES_NO_OPTION,
                                    JOptionPane.QUESTION_MESSAGE);
                };

        try {
            if (SwingUtilities.isEventDispatchThread()) {
                promptTask.run();
            } else {
                SwingUtilities.invokeAndWait(promptTask);
            }
        } catch (Exception e) {
            log.error("Failed to show incoming connection dialog: " + e.getMessage());
            return false;
        }

        return userChoice[0] == JOptionPane.YES_OPTION;
    }

    public Node getNode() {
        return node;
    }

    public boolean getSHOW() {
        return SHOW;
    }

    private void refreshCurrentFolderFilesList() {
        File[] files = node.files().sharedFiles();
        ArrayList<String> fileNames = new ArrayList<>();

        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    fileNames.add(file.getName());
                }
            }
        }

        Collections.sort(fileNames, String.CASE_INSENSITIVE_ORDER);

        SwingUtilities.invokeLater(
                () -> {
                    currentFolderFilesModel.clear();
                    if (fileNames.isEmpty()) {
                        currentFolderFilesModel.addElement("(no files in current folder)");
                        return;
                    }

                    for (String fileName : fileNames) {
                        currentFolderFilesModel.addElement(fileName);
                    }
                });
    }

    /*
     * Counts the number of occurrences of a file in the list of all files
     * The count is used to display the number of neighboor nodes that contain the file
     */
    private synchronized int countOccurrencesInAllFiles(FileSearchResult searchResult) {
        int count = 0;
        for (FileSearchResult file : allFiles) {
            if (file.equals(searchResult)) {
                count++;
            }
        }
        return count;
    }

    private String hashKey(byte[] hash) {
        return Arrays.toString(hash);
    }
}
