package gg.essential.loader.stage2;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import gg.essential.loader.stage2.jvm.ForkedJvmLoaderSwingUI;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

import static gg.essential.loader.stage2.Utils.findMostRecentFile;
import static gg.essential.loader.stage2.Utils.findNextMostRecentFile;

public abstract class EssentialLoaderBase {

    private static final Logger LOGGER = LogManager.getLogger(EssentialLoaderBase.class);
    private static final String BASE_URL = System.getProperty(
        "essential.download.url",
        System.getenv().getOrDefault("ESSENTIAL_DOWNLOAD_URL", "https://downloads.essential.gg")
    );
    private static final String DEFAULT_BRANCH = "stable";
    private static final String VERSION_URL = BASE_URL + "/v1/mods/essential/essential/updates/%s/%s/";
    protected static final String CLASS_NAME = "gg.essential.api.tweaker.EssentialTweaker";
    private static final String FILE_BASE_NAME = "Essential (%s)";
    private static final String FILE_EXTENSION = "jar";
    private static final boolean AUTO_UPDATE = "true".equals(System.getProperty("essential.autoUpdate", "true"));

    private final File gameDir;
    private final String gameVersion;
    private final String fileBaseName;
    private final LoaderUI ui;

    public EssentialLoaderBase(final Path gameDir, final String gameVersion, final boolean lwjgl3) {
        this.gameDir = gameDir.toFile();
        this.gameVersion = gameVersion;
        this.fileBaseName = String.format(FILE_BASE_NAME, this.gameVersion);

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (lwjgl3 && (os.contains("mac") || os.contains("darwin"))) {
            this.ui = LoaderUI.all(new LoaderLoggingUI(), new ForkedJvmLoaderSwingUI());
        } else {
            this.ui = LoaderUI.all(new LoaderLoggingUI(), new LoaderSwingUI());
        }
    }

    public void load() throws IOException {
        if (this.isInClassPath() && this.isInitialized()) {
            return;
        }

        final File dataDir = new File(this.gameDir, "essential");
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            throw new IllegalStateException("Unable to create essential directory, no permissions?");
        }

        File essentialFile = findMostRecentFile(dataDir.toPath(), this.fileBaseName, FILE_EXTENSION).getKey().toFile();

        boolean needUpdate = !essentialFile.exists();

        String branch = determineBranch();

        // Fetch latest version metadata (if required)
        FileMeta meta = null;
        if (needUpdate || AUTO_UPDATE) {
            meta = fetchLatestMetadata(branch);
            if (meta == null && needUpdate) {
                return;
            }
        }

        // Check if our local version matches the latest
        if (!needUpdate && meta != null && !meta.checksum.equals(this.getChecksum(essentialFile))) {
            needUpdate = true;
        }


        if (needUpdate) {
            this.ui.start();

            File downloadedFile = File.createTempFile("essential-download-", "");
            if (downloadFile(meta.url, downloadedFile, meta.checksum)) {
                try {
                    Files.deleteIfExists(essentialFile.toPath());
                } catch (IOException e) {
                    LOGGER.warn("Failed to delete old Essential file, will try again later.", e);
                }

                // If we succeeded in deleting that file, we might now be able to write to a lower-numbered one
                // and if not, we need to write to the next higher one.
                essentialFile = findNextMostRecentFile(dataDir.toPath(), this.fileBaseName, FILE_EXTENSION).toFile();

                Files.move(downloadedFile.toPath(), essentialFile.toPath());
            } else {
                LOGGER.warn("Unable to download Essential, please check your internet connection. If the problem persists, please contact Support.");
                Files.deleteIfExists(downloadedFile.toPath());
            }
        }

        // Check if we can continue, otherwise do not even try
        if (!essentialFile.exists()) {
            return;
        }

        this.addToClasspath(essentialFile);

        if (!this.isInClassPath()) {
            throw new IllegalStateException("Could not find Essential in the classpath even though we added it without errors (fileExists=" + essentialFile.exists() + ").");
        }

        loadPlatform();
    }

    private String determineBranch() {
        final String DEFAULT_SOURCE = "default";
        List<Pair<String, String>> configs = Arrays.asList(
            Pair.of("property", System.getProperty("essential.branch")),
            Pair.of("environment", System.getenv().get("ESSENTIAL_BRANCH")),
            Pair.of("file", determineBranchFromFile()),
            Pair.of(DEFAULT_SOURCE, DEFAULT_BRANCH)
        );

        String resultBranch = null;
        String resultSource = null;
        for (Pair<String, String> config : configs) {
            String source = config.getKey();
            String branch = config.getValue();

            if (branch == null) {
                LOGGER.trace("Checked {} for Essential branch, was not supplied.", source);
                continue;
            }

            if (resultBranch != null) {
                if (!source.equals(DEFAULT_SOURCE)) {
                    LOGGER.warn(
                        "Essential branch supplied via {} as \"{}\" but ignored because {} is more important.",
                        source, branch, resultSource
                    );
                }
                continue;
            }

            Level level = source.equals(DEFAULT_SOURCE) ? Level.DEBUG : Level.INFO;
            LOGGER.log(level, "Essential branch set to \"{}\" via {}.", branch, source);

            resultBranch = branch;
            resultSource = source;
        }
        return resultBranch;
    }

    private String determineBranchFromFile() {
        final String BRANCH_FILE_NAME = "essential_branch.txt";
        try {
            Enumeration<URL> resources = getClass().getClassLoader().getResources(BRANCH_FILE_NAME);
            if (!resources.hasMoreElements()) {
                return null;
            }

            URL url = resources.nextElement();
            String branch = IOUtils.toString(url, StandardCharsets.UTF_8).trim();
            LOGGER.info("Found {} for branch \"{}\".", url, branch);

            while (resources.hasMoreElements()) {
                LOGGER.warn("Found extra branch file, ignoring: {}", resources.nextElement());
            }

            return branch;
        } catch (Exception e) {
            LOGGER.warn("Failed to check for " + BRANCH_FILE_NAME + " file on classpath:", e);
            return null;
        }
    }

    private FileMeta fetchLatestMetadata(String branch) {
        JsonObject responseObject;
        try {
            final URLConnection connection = this.prepareConnection(
                String.format(VERSION_URL, branch, this.gameVersion.replace(".", "-"))
            );

            String response;
            try (final InputStream inputStream = connection.getInputStream()) {
                response = IOUtils.toString(inputStream, Charset.defaultCharset());
            }

            JsonElement jsonElement = new JsonParser().parse(response);
            responseObject = jsonElement.isJsonObject() ? jsonElement.getAsJsonObject() : null;
        } catch (final IOException | JsonParseException e) {
            LOGGER.error("Error occurred checking for updates for game version {}.", this.gameVersion, e);
            return null;
        }

        if (responseObject == null) {
            LOGGER.warn("Essential does not support the following game version: {}", this.gameVersion);
            return null;
        }

        final JsonElement
            jsonUrl = responseObject.get("url"),
            jsonChecksum = responseObject.get("checksum");
        final String
            url = jsonUrl != null && jsonUrl.isJsonPrimitive() ? jsonUrl.getAsString() : null,
            checksum = jsonChecksum != null && jsonChecksum.isJsonPrimitive() ? responseObject.get("checksum").getAsString() : null;

        if (StringUtils.isEmpty(url) || StringUtils.isEmpty(checksum)) {
            LOGGER.warn("Unexpected response object data (url={}, checksum={})", jsonUrl, jsonChecksum);
            return null;
        }

        return new FileMeta(url, checksum);
    }

    private String getChecksum(final File input) {
        try (final InputStream inputStream = new FileInputStream(input)) {
            return DigestUtils.md5Hex(inputStream);
        } catch (final Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    private URLConnection prepareConnection(final String url) throws IOException {
        final URLConnection urlConnection = new URL(url).openConnection();

        if (urlConnection instanceof HttpURLConnection) {
            final HttpURLConnection httpURLConnection = (HttpURLConnection) urlConnection;

            httpURLConnection.setRequestMethod("GET");
            httpURLConnection.setUseCaches(true);
            httpURLConnection.setReadTimeout(3000);
            httpURLConnection.setReadTimeout(3000);
            httpURLConnection.setDoOutput(true);

            httpURLConnection.addRequestProperty("User-Agent", "Mozilla/5.0 (Essential Initializer)");
        }

        return urlConnection;
    }

    protected abstract void loadPlatform();

    protected abstract void addToClasspath(final File file);

    private boolean isInitialized() {
        try {
            return Class.forName(CLASS_NAME)
                .getField("initialized")
                .getBoolean(null);
        } catch (Throwable ignored) {
        }
        return false;
    }

    protected abstract boolean isInClassPath();

    public final void initialize() {
        if (!isInClassPath()) {
            return;
        }
        doInitialize();
    }

    protected void doInitialize() {
        try {
            Class.forName(CLASS_NAME)
                .getDeclaredMethod("initialize", File.class)
                .invoke(null, gameDir);
        } catch (Throwable e) {
            throw new RuntimeException("Unexpected error", e);
        }
    }

    protected static URI asJar(URI uri) throws URISyntaxException {
        return new URI("jar:" + uri.getScheme(), uri.getHost(), uri.getPath(), uri.getFragment());
    }

    private boolean downloadFile(final String url, final File target, String expectedHash) {
        if (!this.attemptDownload(url, target)) {
            LOGGER.warn("Unable to download Essential, please check your internet connection. If the problem persists, please contact Support.");
            return false;
        }

        final String downloadedChecksum = this.getChecksum(target);

        if (downloadedChecksum.equals(expectedHash)) {
            return true;
        }

        LOGGER.warn(
            "Downloaded Essential file checksum did not match what we expected (downloaded={}, expected={}",
            downloadedChecksum, expectedHash
        );

        // Do not keep the file they downloaded if validation failed.
        if (target.exists()) {
            target.delete();
        }

        return false;
    }

    private boolean attemptDownload(final String url, final File target) {
        try {
            final URLConnection connection = this.prepareConnection(url);
            final int contentLength = connection.getContentLength();
            this.ui.setDownloadSize(contentLength);

            int totalRead = 0;
            try (
                final InputStream inputStream = connection.getInputStream();
                final FileOutputStream fileOutputStream = new FileOutputStream(target, true)
            ) {
                final byte[] buffer = new byte[1024];

                int read;
                while ((read = inputStream.read(buffer)) > 0) {
                    fileOutputStream.write(buffer, 0, read);
                    totalRead += read;
                    this.ui.setDownloaded(totalRead);
                }

                return true;
            }
        } catch (final IOException e) {
            LOGGER.error("Error occurred when downloading file '{}'.", url, e);
            return false;
        } finally {
            this.ui.complete();
        }
    }

    private static class FileMeta {
        String url;
        String checksum;

        public FileMeta(String url, String checksum) {
            this.url = url;
            this.checksum = checksum;
        }
    }
}