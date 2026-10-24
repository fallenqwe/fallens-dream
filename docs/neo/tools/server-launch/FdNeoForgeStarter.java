/* SPDX-License-Identifier: MIT
 * Fallen's Dream NeoForge starter for hosts whose startup command is fixed to
 *   java <host flags> -jar <SERVER_JARFILE> nogui
 * (Pterodactyl client API can change only SERVER_JARFILE and the Docker image).
 *
 * It does the same as NeoForge's run.sh, in a child JVM:
 *   java <host flags> @user_jvm_args.txt @libraries/net/neoforged/neoforge/<ver>/unix_args.txt <program args>
 * stdin/stdout/stderr are inherited, so the panel console and the "stop" command reach the server directly.
 * The exit code of the server is returned. On SIGTERM/SIGINT the child is asked to stop and awaited.
 *
 * NeoForge's own --server-jar installer option downloads an unpinned "latest" serverstarterjar from GitHub,
 * which this project does not fetch; this small replacement is built and tested locally instead.
 * When .fallens/server-channel.url exists, the pinned server updater must succeed before the server starts.
 * The updater handles only the approved mods/configs and exact fd-server-fixes datapack scope.
 */
import java.lang.management.ManagementFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class FdNeoForgeStarter {
    static final String NEOFORGE = "21.1.264";
    static final int MIN_JAVA = 21;
    static final long STOP_WAIT_SECONDS = 170;
    static final String UPDATER_SHA256 = "bae23e590077504ddea778bad147ab49970ff89c959b5fc1e6e00b00f692e465";

    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        int feature = Runtime.version().feature();
        if (feature < MIN_JAVA) fail("Java " + MIN_JAVA + "+ is required for NeoForge " + NEOFORGE + ", found Java " + feature + ".");
        String argsName = "libraries/net/neoforged/neoforge/" + NEOFORGE + "/" + (windows ? "win_args.txt" : "unix_args.txt");
        if (!Files.isRegularFile(root.resolve(argsName))) fail("Missing " + argsName + " in " + root + ". Install NeoForge " + NEOFORGE + " here first.");
        if (!Files.isRegularFile(root.resolve("libraries/net/neoforged/neoforge/" + NEOFORGE + "/neoforge-" + NEOFORGE + "-server.jar"))) {
            fail("Missing the patched NeoForge server JAR; the installation in " + root + " is incomplete.");
        }

        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString());
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            // Debug/agent options of the launcher JVM must not be duplicated (port clash); host memory/-D flags are kept.
            if (argument.startsWith("-agentlib:") || argument.startsWith("-javaagent:") || argument.startsWith("-Xrunjdwp")) continue;
            command.add(argument);
        }
        // Later JVM options win, so user_jvm_args.txt can override host -Xmx exactly like run.sh.
        if (Files.isRegularFile(root.resolve("user_jvm_args.txt"))) command.add("@user_jvm_args.txt");
        command.add("@" + argsName);
        command.addAll(List.of(args));

        System.out.println("[fd-starter] NeoForge " + NEOFORGE + " on Java " + Runtime.version() + "; JVM flags: "
            + String.join(" ", command.subList(1, command.size() - args.length)));
        System.out.flush();
        if (Boolean.getBoolean("fd.starter.dryRun")) return;

        Path channel = root.resolve(".fallens/server-channel.url");
        if (Files.exists(channel, LinkOption.NOFOLLOW_LINKS)) {
            try {
                rejectLinks(root.resolve(".fallens"));
                rejectLinks(channel);
                if (!Files.isRegularFile(channel, LinkOption.NOFOLLOW_LINKS) || Files.size(channel) > 4096) throw new IOException("Invalid server channel file.");
                String url = Files.readString(channel, StandardCharsets.UTF_8).strip();
                if (url.isEmpty() || url.chars().anyMatch(Character::isISOControl)) throw new IOException("Empty or invalid server channel URL.");
                Path updater = root.resolve(".fallens/server-updater.jar");
                rejectLinks(updater);
                if (!Files.isRegularFile(updater, LinkOption.NOFOLLOW_LINKS) || Files.size(updater) > 4 * 1024 * 1024) throw new IOException("Missing pinned server updater.");
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(updater)));
                if (!hash.equals(UPDATER_SHA256)) throw new IOException("Server updater checksum mismatch.");
                int updateCode = runChild(root, List.of(command.get(0), "-Djava.awt.headless=true", "-jar", updater.toString(), root.toString(), url));
                if (updateCode != 0) System.exit(updateCode);
            } catch (Exception failure) {
                fail("Pre-start update failed: " + failure.getMessage() + "; Minecraft was not started.");
            }
        }
        System.exit(runChild(root, command));
    }

    static void rejectLinks(Path path) throws IOException {
        for (Path p = path; p != null; p = p.getParent()) {
            if (Files.isSymbolicLink(p)) throw new IOException("Symbolic links are not allowed for updater paths.");
        }
    }

    static int runChild(Path root, List<String> command) throws Exception {
        Process child = new ProcessBuilder(command).directory(root.toFile()).inheritIO().start();
        Thread stopper = new Thread(() -> {
            if (!child.isAlive()) return;
            child.destroy(); // SIGTERM: the server's own shutdown hook saves the world.
            try {
                if (!child.waitFor(STOP_WAIT_SECONDS, TimeUnit.SECONDS)) child.destroyForcibly();
            } catch (InterruptedException e) {
                child.destroyForcibly();
            }
        }, "fd-starter-stop");
        Runtime.getRuntime().addShutdownHook(stopper);
        int code = child.waitFor();
        try { Runtime.getRuntime().removeShutdownHook(stopper); } catch (IllegalStateException alreadyStopping) { }
        return code;
    }

    static void fail(String message) {
        System.err.println("[fd-starter] " + message);
        System.exit(1);
    }
}
