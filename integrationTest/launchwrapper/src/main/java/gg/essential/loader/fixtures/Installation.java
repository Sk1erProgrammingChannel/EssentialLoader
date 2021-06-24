package gg.essential.loader.fixtures;

import gg.essential.loader.util.Copy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class Installation {
    private static final Path originalApiDir = Paths.get("build", "downloadsApi");
    private static final Path originalExampleModFile = originalApiDir.resolve("v1/mods/example/mod/updates/stable/forge_1-8-8.jar");

    public final Path gameDir = Files.createTempDirectory("game");
    public final Path modsDir = gameDir.resolve("mods");
    public final Path apiDir = gameDir.resolve("downloadsApi");
    public final Path stage1Dummy = apiDir.resolve("v1/mods/essential/loader-stage1/updates/dummy/forge_1-8-8");
    public final Path stage2Meta = apiDir.resolve("v1/mods/essential/loader-stage2/updates/stable/forge_1-8-8");
    public final Path stage2DummyMeta = apiDir.resolve("v1/mods/essential/loader-stage2/updates/dummy/forge_1-8-8");
    public final Path stage3Meta = apiDir.resolve("v1/mods/essential/essential/updates/stable/forge_1-8-8");
    public final Path stage3DummyMeta = apiDir.resolve("v1/mods/essential/essential/updates/dummy/forge_1-8-8");

    public Installation() throws IOException {
        System.out.println("Installation: " + gameDir);

        Path dummyFile = Files.createTempFile("dummy", "");
        System.setProperty("net.minecraftforge.gradle.GradleStart.srg.srg-mcp", dummyFile.toAbsolutePath().toString());

        Files.createDirectories(modsDir);
    }

    public void setup() throws IOException {
        setupDownloadsApi();
    }

    protected void setupDownloadsApi() throws IOException {
        Copy.recursively(originalApiDir, apiDir);
        System.setProperty("essential.download.url", apiDir.toUri().toString());
    }

    public void addExampleMod() throws IOException {
        Files.copy(originalExampleModFile, modsDir.resolve("examplemod.jar"));
    }

    public IsolatedLaunch launch(String tweaker) throws Exception {
        IsolatedLaunch isolatedLaunch = new IsolatedLaunch();
        isolatedLaunch.launch(gameDir, tweaker);
        return isolatedLaunch;
    }

    public IsolatedLaunch launchFML() throws Exception {
        return launch("net.minecraftforge.fml.common.launcher.FMLTweaker");
    }

    public void assertModLaunched(IsolatedLaunch isolatedLaunch) throws Exception {
        assertTrue(isolatedLaunch.getModLoadState("tweaker"), "Example Tweaker ran");
        assertTrue(isolatedLaunch.getModLoadState("coreMod"), "Example CoreMod ran");
        assertTrue(isolatedLaunch.getModLoadState("mod"), "Example Mod ran");
    }
}
