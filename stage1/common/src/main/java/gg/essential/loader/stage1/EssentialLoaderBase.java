package gg.essential.loader.stage1;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

public abstract class EssentialLoaderBase {

    private static final Logger LOGGER = LogManager.getLogger(EssentialLoaderBase.class);
    protected static final String STAGE2_PKG = "gg.essential.loader.stage2.";
    protected static final String STAGE2_CLS = STAGE2_PKG + "EssentialLoader";
    private static final String BASE_URL = System.getProperty(
        "essential.download.url",
        System.getenv().getOrDefault("ESSENTIAL_DOWNLOAD_URL", "https://downloads.essential.gg")
    );
    private static final String VERSION_URL = BASE_URL + "/v1/mods/essential/loader-stage2/updates/%s/%s/";
    private static final String BRANCH_KEY = "branch";
    private static final String AUTO_UPDATE_KEY = "autoUpdate";

    private final String variant;
    private final String gameVersion;
    private Object stage2;
    private boolean loaded;

    EssentialLoaderBase(final String variant, final String gameVersion) {
        this.variant = variant;
        this.gameVersion = gameVersion;
    }

    public void load(final Path gameDir) throws Exception {
        if (this.loaded) {
            return;
        }
        this.loaded = true; // setting this now, no point in retrying when we error

        final Path dataDir = gameDir
            .resolve("essential")
            .resolve("loader")
            .resolve("stage1")
            .resolve(variant);
        final Path stage2File = dataDir.resolve("stage2." + this.gameVersion + ".jar");
        final URL stage2Url = stage2File.toUri().toURL();

        if (!Files.exists(dataDir)) {
            Files.createDirectories(dataDir);
        }

        Properties defaultProps = new Properties();
        copyEnvToProp(defaultProps, "ESSENTIAL_STAGE2_BRANCH", BRANCH_KEY);
        copyPropToProp(defaultProps, "essential.stage2.branch", BRANCH_KEY);
        copyPropToProp(defaultProps, "essential.autoUpdate", AUTO_UPDATE_KEY);

        // Load config
        Properties config = new Properties(defaultProps);
        Path configFile = dataDir.resolve("config.properties");
        if (Files.exists(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                config.load(in);
            } catch (Exception e) {
                LOGGER.error("Failed to read config at " + configFile + ":", e);
            }
        }
        Boolean autoUpdate = booleanOrNull(config.getProperty(AUTO_UPDATE_KEY));
        String branch = config.getProperty(BRANCH_KEY, "stable");

        boolean needUpdate = !Files.exists(stage2File);

        // Fetch latest version metadata (if required)
        FileMeta meta = null;
        URL bundledStage2Url = getClass().getResource("stage2.jar");
        if (bundledStage2Url != null && autoUpdate == null) {
            LOGGER.info("Skipping update check, found pinned stage2 jar: {}", bundledStage2Url);
            meta = new FileMeta(bundledStage2Url, getChecksum(bundledStage2Url));
        } else if (needUpdate || autoUpdate != Boolean.FALSE) {
            meta = fetchLatestMetadata(branch);
            if (meta == null && needUpdate) {
                return;
            }
        }

        // Check if our local version matches the latest
        if (!needUpdate && meta != null && !meta.checksum.equals(this.getChecksum(stage2File))) {
            needUpdate = true;
        }

        // Fetch it
        if (needUpdate) {
            Path downloadedFile = Files.createTempFile("essential-download-", "");
            if (downloadFile(meta, downloadedFile)) {
                Files.deleteIfExists(stage2File);
                Files.move(downloadedFile, stage2File);
            } else {
                LOGGER.warn("Unable to download Essential, please check your internet connection. If the problem persists, please contact Support.");
                Files.deleteIfExists(downloadedFile);
            }
        }

        // Check if we can continue, otherwise do not even try
        if (!Files.exists(stage2File)) {
            return;
        }

        // Add stage2 file to class loader
        ClassLoader classLoader = addToClassLoader(stage2Url);

        // Finally, load stage2
        this.stage2 = Class.forName(STAGE2_CLS, true, classLoader)
            .getConstructor(Path.class, String.class)
            .newInstance(gameDir, this.gameVersion);
        // and continue there
        this.stage2.getClass()
            .getMethod("load")
            .invoke(this.stage2);
    }

    public Object getStage2() {
        return stage2;
    }

    protected abstract ClassLoader addToClassLoader(URL stage2Url) throws Exception;

    public void initialize() {
        if (this.stage2 == null) {
            return;
        }
        try {
            this.stage2.getClass()
                .getMethod("initialize")
                .invoke(this.stage2);
        } catch (Throwable e) {
            throw new RuntimeException("Unexpected error", e);
        }
    }

    private String getChecksum(final Path input) {
        try (final InputStream inputStream = Files.newInputStream(input)) {
            return DigestUtils.md5Hex(inputStream);
        } catch (final Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String getChecksum(final URL input) {
        try (final InputStream inputStream = input.openStream()) {
            return DigestUtils.md5Hex(inputStream);
        } catch (final Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private URLConnection prepareConnection(final URL url) throws IOException {
        final URLConnection urlConnection = url.openConnection();

        if (urlConnection instanceof HttpURLConnection) {
            final HttpURLConnection httpURLConnection = (HttpURLConnection) urlConnection;

            httpURLConnection.setRequestMethod("GET");
            httpURLConnection.setUseCaches(true);
            httpURLConnection.setConnectTimeout(30_000);
            httpURLConnection.setReadTimeout(30_000);
            httpURLConnection.setDoOutput(true);

            httpURLConnection.addRequestProperty("User-Agent", "Mozilla/5.0 (Essential Initializer)");
        }

        return urlConnection;
    }

    private FileMeta fetchLatestMetadata(String branch) {
        URLConnection connection = null;
        JsonObject responseObject;
        try {
            String url = String.format(VERSION_URL, branch, this.gameVersion.replace(".", "-"));
            connection = this.prepareConnection(new URL(url));

            String response;
            try (final InputStream inputStream = connection.getInputStream()) {
                response = IOUtils.toString(inputStream, Charset.defaultCharset());
            }

            JsonElement jsonElement = new JsonParser().parse(response);
            responseObject = jsonElement.isJsonObject() ? jsonElement.getAsJsonObject() : null;
        } catch (final IOException | JsonParseException e) {
            LOGGER.error("Error occurred checking for updates for game version {}.", this.gameVersion, e);
            logConnectionInfoOnError(connection);
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

        try {
            return new FileMeta(new URL(url), checksum);
        } catch (MalformedURLException e) {
            LOGGER.error("Received invalid url `" + url + "`:", e);
            return null;
        }
    }

    private boolean downloadFile(FileMeta meta, Path target) {
        URLConnection connection = null;
        try {
            connection = this.prepareConnection(meta.url);
            Files.copy(connection.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            LOGGER.error("Error occurred when downloading file '{}'.", meta.url, e);
            logConnectionInfoOnError(connection);
            return false;
        }

        final String actualHash = this.getChecksum(target);
        if (!meta.checksum.equals(actualHash)) {
            LOGGER.warn(
                "Downloaded Essential file checksum did not match what we expected (actual={}, expected={}",
                actualHash, meta.checksum
            );
            return false;
        }

        return true;
    }

    private void logConnectionInfoOnError(URLConnection connection) {
        if (connection == null) {
            return;
        }
        LOGGER.error("url: {}", connection.getURL());
        LOGGER.error("cf-ray: {}", connection.getHeaderField("cf-ray"));
    }

    private Boolean booleanOrNull(String str) {
        return str == null ? null : Boolean.parseBoolean(str);
    }

    private void copyEnvToProp(Properties properties, String envKey, String dstKey) {
        String value = System.getenv(envKey);
        if (value != null) {
            properties.setProperty(dstKey, value);
        }
    }

    private void copyPropToProp(Properties properties, String srcKey, String dstKey) {
        String value = System.getProperty(srcKey);
        if (value != null) {
            properties.setProperty(dstKey, value);
        }
    }

    private static class FileMeta {
        URL url;
        String checksum;

        public FileMeta(URL url, String checksum) {
            this.url = url;
            this.checksum = checksum;
        }
    }
}