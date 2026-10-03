package calebxzhou.rdi.earlydisplay.neoforge;

import calebxzhou.rdi.earlydisplay.RdiDisplayPlatform;
import calebxzhou.rdi.earlydisplay.RdiProgressSource;
import calebxzhou.rdi.earlydisplay.internal.RdiDisplayWindow;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import joptsimple.OptionParser;
import net.neoforged.fml.loading.FMLConfig;
import net.neoforged.fml.loading.ImmediateWindowHandler;
import net.neoforged.fml.loading.progress.StartupNotificationManager;
import net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider;

/** NeoForge 1.21.1 ImmediateWindowProvider adapter. */
public final class NeoForge21 implements ImmediateWindowProvider {
    private final RdiDisplayWindow window = new RdiDisplayWindow(new Platform());

    @Override
    public String name() {
        return "rdiearlywindow";
    }

    @Override
    public Runnable initialize(String[] arguments) {
        return window.initialize(arguments);
    }

    @Override
    public void updateFramebufferSize(IntConsumer width, IntConsumer height) {
        window.updateFramebufferSize(width, height);
    }

    @Override
    public long setupMinecraftWindow(IntSupplier width, IntSupplier height, Supplier<String> title, LongSupplier monitor) {
        // Runs in Minecraft.<init> after FML's preLaunch logging reload and before ClientModLoader.begin.
        RootLogLevelFix.applyFromSystemProperty();
        return window.setupMinecraftWindow(width, height, title, monitor);
    }

    @Override
    public boolean positionWindow(Optional<Object> monitor, IntConsumer widthSetter, IntConsumer heightSetter, IntConsumer xSetter, IntConsumer ySetter) {
        return window.positionWindow(monitor, widthSetter, heightSetter, xSetter, ySetter);
    }

    @Override
    public <T> Supplier<T> loadingOverlay(Supplier<?> mc, Supplier<?> ri, Consumer<Optional<Throwable>> ex, boolean fade) {
        return window.loadingOverlay(mc, ri, ex, fade);
    }

    @Override
    public void updateModuleReads(ModuleLayer layer) {
        window.updateModuleReads(layer);
    }

    @Override
    public void periodicTick() {
        window.periodicTick();
    }

    @Override
    public String getGLVersion() {
        return window.getGLVersion();
    }

    @Override
    public void crash(String message) {
        window.crash(message);
    }

    private static final class Platform implements RdiDisplayPlatform {
        @Override
        public Settings parse(String[] arguments) {
            OptionParser parser = new OptionParser();
            var minecraftVersion = parser.accepts("fml.mcVersion").withRequiredArg().ofType(String.class);
            var neoForgeVersion = parser.accepts("fml.neoForgeVersion").withRequiredArg().ofType(String.class);
            var width = parser.accepts("width").withRequiredArg().ofType(Integer.class)
                    .defaultsTo(FMLConfig.getIntConfigValue(FMLConfig.ConfigValue.EARLY_WINDOW_WIDTH));
            var height = parser.accepts("height").withRequiredArg().ofType(Integer.class)
                    .defaultsTo(FMLConfig.getIntConfigValue(FMLConfig.ConfigValue.EARLY_WINDOW_HEIGHT));
            var maximized = parser.accepts("earlywindow.maximized");
            parser.allowsUnrecognizedOptions();
            var parsed = parser.parse(arguments);
            int windowWidth = parsed.valueOf(width);
            int windowHeight = parsed.valueOf(height);
            FMLConfig.updateConfig(FMLConfig.ConfigValue.EARLY_WINDOW_WIDTH, windowWidth);
            FMLConfig.updateConfig(FMLConfig.ConfigValue.EARLY_WINDOW_HEIGHT, windowHeight);
            return new Settings(
                    parsed.valueOf(minecraftVersion),
                    parsed.valueOf(neoForgeVersion),
                    windowWidth,
                    windowHeight,
                    FMLConfig.getIntConfigValue(FMLConfig.ConfigValue.EARLY_WINDOW_FBSCALE),
                    parsed.has(maximized) || FMLConfig.getBoolConfigValue(FMLConfig.ConfigValue.EARLY_WINDOW_MAXIMIZED),
                    true,
                    FMLConfig.<String>getListConfigValue(FMLConfig.ConfigValue.EARLY_WINDOW_SKIP_GL_VERSIONS));
        }

        @Override
        public RdiProgressSource progressSource() {
            return new RdiProgressSource() {
                @Override
                public List<AgeMessage> messages() {
                    return StartupNotificationManager.getMessages().stream()
                            .map(message -> new AgeMessage(message.age(), message.message().getText()))
                            .toList();
                }

                @Override
                public List<ProgressBar> progressBars() {
                    return StartupNotificationManager.getCurrentProgress().stream()
                            .map(progress -> new ProgressBar(
                                    progress.label().getText(), progress.steps(), progress.progress()))
                            .toList();
                }
            };
        }

        @Override
        public void reportLoaderMessage(String message) {
            StartupNotificationManager.modLoaderConsumer().ifPresent(consumer -> consumer.accept(message));
        }

        @Override
        public String loaderDisplayName() {
            return "NeoForge";
        }

        @Override
        public void updateProgress(String message) {
            ImmediateWindowHandler.updateProgress(message);
        }

        @Override
        public String loaderModuleName() {
            return "neoforge";
        }

        @Override
        public String fallbackLoadingOverlayClassName() {
            return "net.neoforged.neoforge.client.loading.NoVizFallback";
        }
    }
}
