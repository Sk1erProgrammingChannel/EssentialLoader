package gg.essential.loader;

import gg.essential.loader.components.CircleButton;
import gg.essential.loader.components.EssentialProgressBarUI;
import gg.essential.loader.components.MotionPanel;
import net.minecraft.launchwrapper.Launch;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.nimbus.NimbusLookAndFeel;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.io.*;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Objects;

public final class EssentialLoader {
    private static final String VERSION_URL = "https://downloads.essential.gg/v1/mods/essential/updates/latest/%s";
    private static final String CLASS_NAME = "gg.essential.api.tweaker.EssentialTweaker";
    private static final String FILE_NAME = "Essential (%s).jar";
    private static final int FRAME_WIDTH = 470;
    private static final int FRAME_HEIGHT = 240;
    private static final boolean UPDATE = "true".equals(System.getProperty("essential.autoUpdate", "true"));
    private static final char[] hexCodes;

    static {
        hexCodes = "0123456789ABCDEF".toCharArray();
    }

    private final Color COLOR_BACKGROUND = new Color(33, 34, 38);
    private final Color COLOR_FOREGROUND = new Color(141, 141, 143);
    private final Color COLOR_TITLE_BACKGROUND = new Color(27, 28, 31);
    private final Color COLOR_PROGRESS_FILL = new Color(1, 165, 82);
    private final Color COLOR_EXIT = new Color(248, 203, 25);

    private final File gameDir;
    private final String gameVersion;

    private JFrame frame;
    private JProgressBar progressBar;

    public EssentialLoader(final File gameDir, final String gameVersion) {
        this.gameDir = gameDir;
        this.gameVersion = gameVersion;
    }

    public static String toHex(final byte[] bytes) {
        final StringBuilder r = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) {
            r.append(hexCodes[b >> 4 & 0xF]);
            r.append(hexCodes[b & 0xF]);
        }
        return r.toString();
    }

    public static String checksum(final File input) {
        try (final InputStream in = new FileInputStream(input)) {
            return DigestUtils.md5Hex(in);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public void load() {
        if (isInClassPath() && isInitialized()) {
            return;
        }

        final File dataDir = new File(gameDir, "essential");
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            throw new IllegalStateException("Unable to create necessary files");
        }

        final JsonHolder completeDocument = HttpUtils.fetchJson(String.format(VERSION_URL, gameVersion.replace(".", "-")));
        final boolean failed = completeDocument.optBoolean("failed");
        if (completeDocument.optString("url").isEmpty() && !failed) {
            System.out.println("Unsupported game version: " + gameVersion);
            return;
        }
        final String expectedHash = completeDocument.optString("checksum");
        File essentialFile = new File(dataDir, String.format(FILE_NAME, gameVersion));

        if (!essentialFile.exists() && !failed || (UPDATE && essentialFile.exists() && !expectedHash.equalsIgnoreCase(checksum(essentialFile)))) {
            initFrame();
            if (UPDATE && essentialFile.exists())
                essentialFile.delete();
            if ((UPDATE)) {
                downloadFile(completeDocument.optString("url"), essentialFile, expectedHash);
            }
        }
        addToClasspath(essentialFile);

        if (!isInClassPath()) {
            throw new IllegalStateException("Something went wrong; Essential is not found in the classpath. Exists? " + essentialFile.exists());
        }

    }

    private void addToClasspath(final File file) {
        try {
            final URL url = file.toURI().toURL();
            Launch.classLoader.addURL(url);

            final ClassLoader classLoader = EssentialLoader.class.getClassLoader();
            final Method method = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            method.setAccessible(true);
            method.invoke(classLoader, url);
        } catch (Exception e) {
            throw new RuntimeException("Unexpected error", e);
        }
    }

    private boolean isInitialized() {
        try {
            return gg.essential.api.tweaker.EssentialTweaker.initialized;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean isInClassPath() {
        try {
            LinkedHashSet<String> objects = new LinkedHashSet<>();
            objects.add(CLASS_NAME);
            Launch.classLoader.clearNegativeEntries(objects);
            Class.forName(CLASS_NAME);
            return true;
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        return false;
    }

    public void initializeEssential() {
        try {
            gg.essential.api.tweaker.EssentialTweaker.initialize(gameDir);
        } catch (Throwable e) {
            throw new RuntimeException("Unexpected error", e);
        }
    }

    private boolean downloadFile(final String url, final File target, String expectedHash) {
        int attempts = 0;
        while (attempts < 5) {
            if (attemptDownload(url, target)) {
                String anotherString = checksum(target);
                if (expectedHash.equalsIgnoreCase(anotherString)) {
                    return true;
                } else {
                    System.out.println("Essential hash does not match expected " + expectedHash + " " + anotherString);
                    if (target.exists()) {
                        FileUtils.deleteQuietly(target);
                    }
                }
            }
            attempts++;
        }
        JOptionPane.showConfirmDialog(null, "Unable to download Essential. If issues persist please contact support");
        return false;
    }

    private boolean attemptDownload(final String url, final File target) {
        try {
            final HttpURLConnection connection = HttpUtils.prepareConnection(url);
            final int contentLength = connection.getContentLength();

            try (final InputStream is = connection.getInputStream()) {
                try (FileOutputStream outputStream = new FileOutputStream(target)) {
                    final byte[] buffer = new byte[1024];
                    int read;

                    progressBar.setMaximum(contentLength);

                    while ((read = is.read(buffer)) > 0) {
                        outputStream.write(buffer, 0, read);

                        final int progress = progressBar.getValue() + 1024;
                        progressBar.setValue(progress);
                    }
                    return true;
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            frame.dispose();
        }
    }

    private void initFrame() {
        try {
            UIManager.setLookAndFeel(NimbusLookAndFeel.class.getName());
        } catch (Exception ignored) {
        }

        // Initialize the frame
        final JFrame frame = new JFrame();
        frame.setUndecorated(true);
        frame.setSize(FRAME_WIDTH, FRAME_HEIGHT);
        frame.setResizable(false);

        frame.setShape(new RoundRectangle2D.Double(0, 0, FRAME_WIDTH, FRAME_HEIGHT, 16, 16));
        frame.setTitle("Updating Essential...");

        // Setting the background and the layout
        final Container container = frame.getContentPane();
        container.setBackground(COLOR_BACKGROUND);
        container.setLayout(new BoxLayout(container, BoxLayout.PAGE_AXIS));

        // Title bar
        final MotionPanel titleBar = new MotionPanel(frame);
        titleBar.setLayout(null);
        titleBar.setBackground(COLOR_TITLE_BACKGROUND);
        titleBar.setBounds(0, 0, FRAME_WIDTH, 30);
        container.add(titleBar);

        final JLabel title = new JLabel("Updating Essential...");
        title.setBounds(16, 16, 150, 16);
        title.setForeground(COLOR_FOREGROUND);
        titleBar.add(title, BorderLayout.LINE_START);

        final CircleButton exit = new CircleButton();
        exit.setBackground(COLOR_EXIT);
        exit.setForeground(COLOR_EXIT);
        exit.setBounds(FRAME_WIDTH - 32, 16, 16, 16);
        exit.setFocusPainted(false);
        titleBar.add(exit, BorderLayout.LINE_END);

        exit.addActionListener(e -> frame.dispose());

        // Logo
        try {
            final Image icon = ImageIO.read(Objects.requireNonNull(getClass().getResource("/essential.png")));
            final JLabel label = new JLabel(new ImageIcon(icon));
            label.setBorder(new EmptyBorder(35, 0, 0, 0));
            label.setAlignmentX(Container.CENTER_ALIGNMENT);
            container.add(label);
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Progress
        final JProgressBar progressBar = new JProgressBar();
        progressBar.setForeground(COLOR_PROGRESS_FILL);
        progressBar.setBackground(COLOR_BACKGROUND);
        progressBar.setUI(new EssentialProgressBarUI());
        progressBar.setBorderPainted(false);

        final JPanel panel = new JPanel();
        panel.setBackground(COLOR_BACKGROUND);
        panel.setBorder(new EmptyBorder(25, 0, 0, 0));
        panel.add(progressBar);

        container.add(panel);

        // Show the frame
        final Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();

        frame.setLocation((int) (screenSize.getWidth() - FRAME_WIDTH) / 2, (int) (screenSize.getHeight() - FRAME_HEIGHT) / 2);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setVisible(true);

        this.frame = frame;
        this.progressBar = progressBar;
    }
}