/* SPDX-License-Identifier: MIT
 * Fallen's Dream portable pre-launch updater. Java 21+, no external dependencies.
 * This starts packwiz-installer only; it never starts Minecraft or a graphical UI.
 * 0.2 (2026-10-23): the generated Create JAR and the Catalog JAR are optional. A pack that ships neither
 * .fallens/create/{upstream.jar,overlay.zip} nor .fallens/catalog/{base.jar,recipe.properties} (the NeoForge
 * pack) installs plain packwiz files only; packs that ship them behave exactly as before.
 */
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.zip.*;

public final class FallensUpdater {
    static final String INSTALLER_SHA256 = "c9f646908d340d84773948a9a7d98bc1dae250d35e1016dc6e2b8459760b5598";
    static final String GENERATED = "mods/fallens-create.jar";
    static final List<String> TREES = List.of("mods", "config", "datapacks", "resourcepacks", "shaderpacks", ".fallens/create", ".fallens/catalog");
    static final List<String> FILES = List.of("options.txt", "packwiz.json", ".fallens/state.properties");
    static final long ENTRY_LIMIT = 512L * 1024 * 1024;
    static final long TOTAL_LIMIT = 4L * 1024 * 1024 * 1024;
    static final class Network {
        static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public static void main(String[] args) {
        try {
            if (!((args.length == 2 && !args[1].startsWith("--")) ||
                  (args.length == 3 && args[1].equals("--rollback")))) {
                throw new IOException("Usage: java -jar launcher.jar <minecraft-dir> <channel-url>\n" +
                    "Rollback: java -jar launcher.jar <minecraft-dir> --rollback <backup-dir>");
            }
            Path mc = Path.of(args[0]).toAbsolutePath().normalize();
            noLinks(mc);
            Files.createDirectories(mc.resolve(".fallens"));
            noLinks(mc.resolve(".fallens"));
            Path lockPath = mc.resolve(".fallens/update.lock");
            noLinks(lockPath);
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock lock;
                try { lock = channel.tryLock(); }
                catch (OverlappingFileLockException e) { lock = null; }
                if (lock == null) throw new IOException("Another Fallen's Dream update is already running.");
                try (FileLock held = lock) {
                    if (args.length == 3) {
                        Path backup = validateBackupLocation(mc, Path.of(args[2]));
                        restore(mc, backup);
                        Files.deleteIfExists(mc.resolve(".fallens/pending.properties"));
                        System.out.println("Fallen's Dream: backup restored. The next launch checks the current channel again.");
                    } else {
                        update(mc, args[1]);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Fallen's Dream update stopped: " + e.getMessage());
            System.err.println("Minecraft was not started. Your saves are outside the updater's file scope.");
            System.exit(1);
        }
    }

    static void update(Path mc, String channelUrl) throws Exception {
        Path pending = mc.resolve(".fallens/pending.properties");
        noLinks(pending);
        if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
            Properties interrupted = load(pending);
            Path backup = validateBackupLocation(mc, mc.resolve(required(interrupted, "backup")));
            recordAfter(mc, backup);
            restore(mc, backup);
            Files.delete(pending);
            throw new IOException("An interrupted update was rolled back. Launch again to retry.");
        }
        Properties feed = new Properties();
        feed.load(new StringReader(new String(fetch(uri(channelUrl), 64 * 1024), StandardCharsets.UTF_8)));
        String version = required(feed, "version");
        if (version.length() > 100 || version.chars().anyMatch(Character::isISOControl)) throw new IOException("Invalid channel version.");
        String expectedPack = hashValue(required(feed, "pack.sha256"));
        URI packUrl = uri(required(feed, "pack.url"));
        byte[] verifiedPack = fetch(packUrl, 4 * 1024 * 1024);
        if (!sha(verifiedPack).equals(expectedPack)) throw new IOException("Channel pack.toml checksum mismatch; no files were changed.");

        Path statePath = mc.resolve(".fallens/state.properties");
        noLinks(statePath);
        Properties old = Files.exists(statePath, LinkOption.NOFOLLOW_LINKS) ? load(statePath) : new Properties();
        boolean changed = !expectedPack.equals(old.getProperty("pack.sha256"));
        // The installer is deliberately pinned; it cannot self-update to an unreviewed version.
        Path installer = mc.resolve(".fallens/packwiz-installer.jar");
        noLinks(installer);
        if (!Files.isRegularFile(installer, LinkOption.NOFOLLOW_LINKS) || !sha(installer).equals(INSTALLER_SHA256)) {
            throw new IOException("Pinned packwiz-installer 0.5.14 is missing or its checksum differs.");
        }
        preflight(mc);
        if (!changed) {
            // Stock packwiz detects missing ordinary managed files even when the pack
            // version did not change. A repair/no-op does not create another backup.
            runInstaller(mc, installer, packUrl);
            if (!sha(fetch(packUrl, 4 * 1024 * 1024)).equals(expectedPack)) throw new IOException("pack.toml changed during installation.");
            // Re-check every generated entry on every launch, not just a saved whole-JAR hash.
            if (generatedCurrent(mc)) {
                System.out.println("Fallen's Dream " + version + ": installed files are current.");
                return;
            }
            System.out.println("Fallen's Dream: repairing the generated Create JAR.");
            generateAll(mc);
            saveState(statePath, version, expectedPack, mc);
            return;
        }

        Path backup = snapshot(mc);
        Properties transaction = new Properties();
        transaction.setProperty("backup", mc.relativize(backup).toString().replace('\\', '/'));
        transaction.setProperty("target.pack.sha256", expectedPack);
        save(pending, transaction);
        System.out.println("Fallen's Dream: updating to " + version + ". Backup: " + backup);
        try {
            runInstaller(mc, installer, packUrl);
            // packwiz fetches the original absolute URL to preserve relative index/metafile URLs.
            // Re-fetch to detect a channel content race before accepting the installed result.
            if (!sha(fetch(packUrl, 4 * 1024 * 1024)).equals(expectedPack)) throw new IOException("pack.toml changed during installation.");
            generateAll(mc);
            saveState(statePath, version, expectedPack, mc);
            recordAfter(mc, backup);
            Files.delete(pending);
            System.out.println("Fallen's Dream " + version + ": update complete.");
        } catch (Exception original) {
            try {
                recordAfter(mc, backup);
                restore(mc, backup);
                Files.deleteIfExists(pending);
                System.err.println("The update failed; the previous installation was restored.");
            } catch (Exception rollback) {
                original.addSuppressed(rollback);
                throw new IOException("Update failed (" + original.getMessage() + "); automatic rollback also failed (" +
                    rollback.getMessage() + "). Backup: " + backup + ". Do not launch this instance before restoring it.", original);
            }
            throw original;
        }
    }

    static void runInstaller(Path mc, Path installer, URI packUrl) throws Exception {
        Set<String> previous = manifestLocations(mc);
        verifyCachedImmutable(mc, true);
        String binary = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", binary);
        Process child = new ProcessBuilder(java.toString(), "-Djava.awt.headless=true", "-cp", installer.toString(),
            "link.infra.packwiz.installer.Main", "-g", "--pack-folder", mc.toString(),
            "--multimc-folder", mc.getParent().toString(), packUrl.toString()).directory(mc.toFile()).inheritIO().start();
        try {
            if (!child.waitFor(20, TimeUnit.MINUTES)) {
                child.destroy();
                if (!child.waitFor(5, TimeUnit.SECONDS)) child.destroyForcibly().waitFor();
                throw new IOException("packwiz timed out after 20 minutes.");
            }
            if (child.exitValue() != 0) throw new IOException("packwiz exited with code " + child.exitValue() + ".");
            verifyCachedImmutable(mc, false);
            Set<String> current = manifestLocations(mc);
            for (String old : previous) {
                if (!current.contains(old) && Files.exists(resolve(mc, old), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("packwiz left an obsolete managed file in place: " + old + ".");
                }
            }
        } catch (InterruptedException e) {
            child.destroyForcibly().waitFor();
            Thread.currentThread().interrupt();
            throw new IOException("Update was interrupted.", e);
        }
    }

    static Set<String> manifestLocations(Path mc) throws IOException {
        Path manifest = resolve(mc, "packwiz.json");
        if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) return Set.of();
        byte[] bytes;
        try (InputStream in = Files.newInputStream(manifest)) { bytes = readLimited(in, 16 * 1024 * 1024); }
        Object root = FlatJson.parseAny(bytes);
        if (!(root instanceof Map<?, ?> object) || !(object.get("cachedFiles") instanceof Map<?, ?> files)) throw new IOException("Invalid packwiz local manifest.");
        Set<String> result = new HashSet<>();
        for (Object raw : files.values()) {
            if (!(raw instanceof Map<?, ?> entry)) throw new IOException("Invalid packwiz cached file.");
            Object location = entry.get("cachedLocation");
            if (location == null) continue;
            if (!(location instanceof String name) || !scoped(pathName(name))) throw new IOException("packwiz cached file escaped the managed scope.");
            resolve(mc, name); result.add(name);
        }
        return result;
    }

    // Stock packwiz trusts its local cache when a managed file is still present.
    // Verify immutable assets ourselves; mutable player configuration is deliberately excluded.
    static void verifyCachedImmutable(Path mc, boolean repair) throws IOException {
        Path manifest = resolve(mc, "packwiz.json");
        if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) return;
        byte[] bytes;
        try (InputStream in = Files.newInputStream(manifest)) { bytes = readLimited(in, 16 * 1024 * 1024); }
        Object parsed = FlatJson.parseAny(bytes);
        if (!(parsed instanceof Map<?, ?> object) || !(object.get("cachedFiles") instanceof Map<?, ?> files)) throw new IOException("Invalid packwiz local manifest.");
        long total = 0;
        for (Object raw : files.values()) {
            if (!(raw instanceof Map<?, ?> entry)) throw new IOException("Invalid packwiz cached file.");
            Object location = entry.get("cachedLocation");
            if (location == null) continue;
            if (!(location instanceof String name) || !scoped(pathName(name))) throw new IOException("packwiz cached file escaped the managed scope.");
            Path file = resolve(mc, name);
            if (!immutableCached(name)) continue;
            Object selected = entry.containsKey("linkedFileHash") ? entry.get("linkedFileHash") : entry.get("hash");
            if (!(selected instanceof Map<?, ?> hash) || !(hash.get("type") instanceof String type) || !(hash.get("value") instanceof String expected)) throw new IOException("Missing immutable file checksum: " + name);
            String algorithm;
            int digits;
            if (type.equalsIgnoreCase("sha256")) { algorithm = "SHA-256"; digits = 64; }
            else if (type.equalsIgnoreCase("sha512")) { algorithm = "SHA-512"; digits = 128; }
            else throw new IOException("Unsupported immutable file checksum: " + type);
            if (!expected.matches("[a-fA-F0-9]{" + digits + "}")) throw new IOException("Invalid immutable file checksum: " + name);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                if (repair) continue;
                throw new IOException("Missing immutable managed file after packwiz: " + name);
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Immutable managed path is not a regular file: " + name);
            long size = Files.size(file); total += size;
            if (size > ENTRY_LIMIT || total > TOTAL_LIMIT) throw new IOException("Immutable file verification exceeds its size limit.");
            MessageDigest digest;
            try { digest = MessageDigest.getInstance(algorithm); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[65536]; int count; long read = 0;
                while ((count = in.read(buf)) != -1) { read += count; if (read > ENTRY_LIMIT) throw new IOException("Immutable file grew beyond its size limit."); digest.update(buf, 0, count); }
            }
            if (HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(expected)) continue;
            if (!repair) throw new IOException("Immutable managed file checksum mismatch after packwiz: " + name);
            Path recovery = resolve(mc, ".fallens/recovery/corrupt-" + UUID.randomUUID() + "/" + name);
            Files.createDirectories(recovery.getParent());
            Files.move(file, recovery);
            System.out.println("Fallen's Dream: preserved corrupt managed file for repair: " + name);
        }
    }

    static boolean immutableCached(String name) {
        return (name.startsWith("mods/") && name.endsWith(".jar"));
    }

    static URI uri(String value) throws IOException {
        try {
            URI u = URI.create(value);
            boolean local = "http".equals(u.getScheme()) && Set.of("127.0.0.1", "localhost", "[::1]", "::1").contains(u.getHost());
            if (!("https".equals(u.getScheme()) || local) || u.getHost() == null || u.getUserInfo() != null || u.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return u;
        } catch (IllegalArgumentException e) { throw new IOException("An update URL must use HTTPS (HTTP is allowed only on loopback for tests)."); }
    }

    static byte[] fetch(URI url, int limit) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(45))
            .header("User-Agent", "FallensDreamUpdater/1").GET().build();
        HttpResponse<InputStream> response = Network.HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            uri(response.uri().toString());
            if (response.statusCode() != 200) throw new IOException("Update server returned HTTP " + response.statusCode() + ".");
            FutureTask<byte[]> read = new FutureTask<>(() -> readLimited(in, limit));
            Thread worker = Thread.ofVirtual().name("fallens-http-body").start(read);
            try { return read.get(45, TimeUnit.SECONDS); }
            catch (TimeoutException e) { read.cancel(true); in.close(); throw new IOException("Update download timed out."); }
            catch (ExecutionException e) { throw new IOException("Update download failed.", e.getCause()); }
            finally { if (worker.isAlive()) worker.interrupt(); }
        }
    }

    static String required(Properties p, String key) throws IOException {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IOException("Missing property: " + key);
        return value.trim();
    }

    static String hashValue(String value) throws IOException {
        if (!value.matches("[a-fA-F0-9]{64}")) throw new IOException("Invalid SHA-256 value.");
        return value.toLowerCase(Locale.ROOT);
    }

    static String sha(byte[] data) { return HexFormat.of().formatHex(digest().digest(data)); }
    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    static String sha(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) { return sha(in); }
    }
    static String sha(InputStream in) throws IOException {
        MessageDigest hash = digest();
        byte[] buf = new byte[64 * 1024];
        int n;
        long size = 0;
        while ((n = in.read(buf)) != -1) {
            size += n;
            if (size > ENTRY_LIMIT) throw new IOException("A file exceeds the updater's 512 MiB per-file limit.");
            hash.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    static String pathName(String name) throws IOException {
        if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":") ||
            name.chars().anyMatch(Character::isISOControl)) throw new IOException("Unsafe archive path: " + name);
        for (String part : name.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")) {
                throw new IOException("Unsafe archive path: " + name);
            }
        }
        return name;
    }

    static Path resolve(Path root, String name) throws IOException {
        Path result = root.resolve(pathName(name)).normalize();
        if (!result.startsWith(root)) throw new IOException("Path escaped its allowed root.");
        noLinks(result);
        return result;
    }

    static void noLinks(Path path) throws IOException {
        Path current = path.toAbsolutePath().getRoot();
        for (Path component : path.toAbsolutePath()) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic links are not allowed in managed paths: " + current);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes a = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!(a.isDirectory() || a.isRegularFile())) throw new IOException("Special files are not allowed in managed paths: " + current);
            }
        }
    }

    static Properties load(Path path) throws IOException {
        noLinks(path);
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { p.load(r); }
        return p;
    }
    static void save(Path path, Properties p) throws IOException {
        noLinks(path);
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), ".fallens-", ".tmp");
        try {
            try (Writer w = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) { p.store(w, "Fallen's Dream updater"); }
            replace(temp, path);
        } finally { Files.deleteIfExists(temp); }
    }
    static void replace(Path source, Path destination) throws IOException {
        noLinks(destination);
        try { Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING); }
    }

    record Plan(String name, String hash, char mode, String source) {}

    static List<Plan> plans(ZipFile overlay) throws IOException {
        ZipEntry entry = overlay.getEntry("plan.tsv");
        if (entry == null) throw new IOException("Create overlay has no plan.tsv.");
        byte[] data;
        try (InputStream in = overlay.getInputStream(entry)) { data = readLimited(in, 16 * 1024 * 1024); }
        List<Plan> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (String line : new String(data, StandardCharsets.UTF_8).split("\\R")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] fields = line.split("\\t", -1);
            if (fields.length != 4 || fields[2].length() != 1 || "CFDN".indexOf(fields[2].charAt(0)) < 0) {
                throw new IOException("Malformed Create overlay plan.");
            }
            String name = pathName(fields[0]);
            if (!names.add(name) || names.size() > 100000) throw new IOException("Duplicate or excessive Create plan entries.");
            result.add(new Plan(name, hashValue(fields[1]), fields[2].charAt(0), pathName(fields[3])));
        }
        if (result.isEmpty()) throw new IOException("Create overlay plan is empty.");
        return result;
    }

    static byte[] entryBytes(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) throw new IOException("Missing Create source entry: " + name);
        try (InputStream in = zip.getInputStream(entry)) { return readLimited(in, ENTRY_LIMIT); }
    }

    static void checkZip(ZipFile zip) throws IOException {
        rejectZipLinks(Path.of(zip.getName()));
        Set<String> names = new HashSet<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            String n = e.getName();
            if (e.isDirectory()) n = n.substring(0, n.length() - 1);
            pathName(n);
            if (!names.add(e.getName())) throw new IOException("Duplicate ZIP entry: " + e.getName());
        }
    }

    static void rejectZipLinks(Path path) throws IOException {
        // java.util.zip does not expose Unix external attributes. Read only the central
        // directory metadata to reject link entries; extraction still uses ZipFile.
        try (RandomAccessFile f = new RandomAccessFile(path.toFile(), "r")) {
            long length = f.length();
            if (length < 22) throw new IOException("Truncated ZIP.");
            long start = Math.max(0, length - 65557);
            byte[] tail = new byte[(int) (length - start)]; f.seek(start); f.readFully(tail);
            ByteBuffer b = ByteBuffer.wrap(tail).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int end = -1;
            for (int p = tail.length - 22; p >= 0; p--) {
                if (b.getInt(p) == 0x06054b50 && p + 22 + Short.toUnsignedInt(b.getShort(p + 20)) == tail.length) { end = p; break; }
            }
            if (end < 0) throw new IOException("Missing ZIP end record.");
            int count = Short.toUnsignedInt(b.getShort(end + 10));
            long offset = Integer.toUnsignedLong(b.getInt(end + 16));
            if (count == 65535 || offset == 0xffffffffL || b.getShort(end + 4) != 0 || b.getShort(end + 6) != 0) {
                throw new IOException("ZIP64 or split archives are not supported for updater inputs.");
            }
            f.seek(offset); byte[] header = new byte[46];
            for (int i = 0; i < count; i++) {
                f.readFully(header); ByteBuffer h = ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                if (h.getInt(0) != 0x02014b50) throw new IOException("Invalid ZIP central directory.");
                int attrs = h.getInt(38), unixType = (attrs >>> 16) & 0170000;
                if (unixType == 0120000 || (attrs & 0x400) != 0) throw new IOException("Archive symbolic links are forbidden.");
                long skip = Short.toUnsignedInt(h.getShort(28)) + Short.toUnsignedInt(h.getShort(30)) + Short.toUnsignedInt(h.getShort(32));
                f.seek(f.getFilePointer() + skip);
            }
        }
    }

    static byte[] readLimited(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        long size = 0;
        while ((n = in.read(buf)) != -1) {
            size += n;
            if (size > limit) throw new IOException("Input exceeds its allowed size.");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    static byte[] applyDelta(byte[] original, byte[] encoded) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (in.readInt() != 0x46444431) throw new IOException("Invalid FDD1 delta header.");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (true) {
                int opcode = in.readUnsignedByte();
                if (opcode == 0) {
                    if (in.read() != -1) throw new IOException("Trailing data in FDD1 delta.");
                    return out.toByteArray();
                }
                if (opcode == 1) {
                    int offset = in.readInt(), length = in.readInt();
                    if (offset < 0 || length < 0 || (long) offset + length > original.length || (long) out.size() + length > ENTRY_LIMIT) {
                        throw new IOException("FDD1 copy is outside its source or output limit.");
                    }
                    out.write(original, offset, length);
                } else if (opcode == 2) {
                    int length = in.readInt();
                    if (length < 0 || (long) out.size() + length > ENTRY_LIMIT) throw new IOException("Invalid FDD1 insert length.");
                    byte[] data = in.readNBytes(length);
                    if (data.length != length) throw new EOFException("Truncated FDD1 insert.");
                    out.write(data);
                } else throw new IOException("Unknown FDD1 opcode: " + opcode);
            }
        }
    }

    static boolean validGenerated(Path mc) throws IOException {
        Path overlayPath = resolve(mc, ".fallens/create/overlay.zip");
        Path generated = resolve(mc, GENERATED);
        if (!Files.isRegularFile(overlayPath, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(generated, LinkOption.NOFOLLOW_LINKS)) return false;
        try (ZipFile overlay = new ZipFile(overlayPath.toFile()); ZipFile target = new ZipFile(generated.toFile())) {
            checkZip(overlay); checkZip(target);
            List<Plan> plan = plans(overlay);
            Enumeration<? extends ZipEntry> entries = target.entries();
            for (Plan p : plan) {
                if (!entries.hasMoreElements()) return false;
                ZipEntry e = entries.nextElement();
                if (!e.getName().equals(p.name()) || e.isDirectory()) return false;
                try (InputStream in = target.getInputStream(e)) { if (!sha(in).equals(p.hash())) return false; }
            }
            return !entries.hasMoreElements();
        } catch (ZipException e) { return false; }
    }

    static void generateCreate(Path mc) throws IOException {
        Path original = resolve(mc, ".fallens/create/upstream.jar");
        Path overlayPath = resolve(mc, ".fallens/create/overlay.zip");
        Path target = resolve(mc, GENERATED);
        if (!Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(overlayPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Create upstream JAR or overlay is missing.");
        }
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".fallens-create-", ".tmp");
        try {
            try (ZipFile source = new ZipFile(original.toFile()); ZipFile overlay = new ZipFile(overlayPath.toFile());
                 ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temp))) {
                checkZip(source); checkZip(overlay);
                long total = 0;
                for (Plan p : plans(overlay)) {
                    byte[] data = switch (p.mode()) {
                        case 'C' -> entryBytes(source, p.source());
                        case 'N' -> normalizeCrlf(entryBytes(source, p.source()));
                        case 'F' -> entryBytes(overlay, p.source());
                        case 'D' -> applyDelta(entryBytes(source, p.name()), entryBytes(overlay, p.source()));
                        default -> throw new IOException("Unknown Create mode.");
                    };
                    total += data.length;
                    if (total > TOTAL_LIMIT || !sha(data).equals(p.hash())) throw new IOException("Create reconstructed entry checksum mismatch: " + p.name());
                    ZipEntry e = new ZipEntry(p.name());
                    e.setTime(315532800000L); // 1980-01-01: reproducible ZIP timestamps.
                    out.putNextEntry(e); out.write(data); out.closeEntry();
                }
            }
            replace(temp, target);
        } finally { Files.deleteIfExists(temp); }
        if (!validGenerated(mc)) throw new IOException("Generated Create JAR failed verification.");
    }

    static byte[] normalizeCrlf(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        for (int i = 0; i < data.length; i++) {
            if (data[i] == '\r' && i + 1 < data.length && data[i + 1] == '\n') continue;
            out.write(data[i]);
        }
        return out.toByteArray();
    }

    static void saveState(Path state, String version, String packHash, Path mc) throws IOException {
        Properties p = new Properties();
        p.setProperty("format", "1"); p.setProperty("version", version); p.setProperty("pack.sha256", packHash);
        if (Files.isRegularFile(resolve(mc, GENERATED), LinkOption.NOFOLLOW_LINKS)) p.setProperty("generatedjar.sha256", sha(mc.resolve(GENERATED)));
        if (Files.isRegularFile(resolve(mc, CATALOG_TARGET), LinkOption.NOFOLLOW_LINKS)) p.setProperty("catalogjar.sha256", sha(mc.resolve(CATALOG_TARGET)));
        save(state, p);
    }

    /** Both Create inputs present: generate. Both absent: this pack has no generated Create JAR. One: broken pack. */
    static boolean hasCreate(Path mc) throws IOException {
        boolean upstream = Files.exists(resolve(mc, ".fallens/create/upstream.jar"), LinkOption.NOFOLLOW_LINKS);
        boolean overlay = Files.exists(resolve(mc, ".fallens/create/overlay.zip"), LinkOption.NOFOLLOW_LINKS);
        if (upstream != overlay) throw new IOException("Create upstream JAR or overlay is missing.");
        return upstream;
    }

    static boolean hasCatalog(Path mc) throws IOException {
        boolean base = Files.exists(resolve(mc, ".fallens/catalog/base.jar"), LinkOption.NOFOLLOW_LINKS);
        boolean recipe = Files.exists(resolve(mc, ".fallens/catalog/recipe.properties"), LinkOption.NOFOLLOW_LINKS);
        if (base != recipe) throw new IOException("Catalog base JAR or recipe is missing.");
        return base;
    }

    /** Generated files are current: valid when their inputs exist, absent when the pack ships no inputs. */
    static boolean generatedCurrent(Path mc) throws IOException {
        boolean create = hasCreate(mc) ? validGenerated(mc) : !Files.exists(resolve(mc, GENERATED), LinkOption.NOFOLLOW_LINKS);
        boolean catalog = hasCatalog(mc) ? validCatalog(mc) : !Files.exists(resolve(mc, CATALOG_TARGET), LinkOption.NOFOLLOW_LINKS);
        return create && catalog;
    }

    static void generateAll(Path mc) throws IOException {
        // A reserved generated name without its inputs is a leftover of another pack; it is removed
        // (on a version change it is still inside the pre-update snapshot).
        if (hasCreate(mc)) generateCreate(mc); else Files.deleteIfExists(resolve(mc, GENERATED));
        if (hasCatalog(mc)) generateCatalog(mc); else Files.deleteIfExists(resolve(mc, CATALOG_TARGET));
    }

    static final String CATALOG_ENTRY = "resourcepacks/readable_russian/assets/mcwwindows/lang/ru_ru.json";
    static final String CATALOG_TARGET = "mods/fallens-catalog.jar";

    static String sha512(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    static String sha512(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            try (InputStream in = Files.newInputStream(path)) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) != -1) digest.update(b, 0, n); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    static Properties catalogRecipe(Path mc) throws IOException {
        Properties p = load(resolve(mc, ".fallens/catalog/recipe.properties"));
        String upstream = pathName(required(p, "upstream.path"));
        if (!upstream.startsWith("mods/") || !CATALOG_ENTRY.equals(required(p, "target.entry"))) throw new IOException("Invalid Catalog recipe paths.");
        for (String key : List.of("upstream.sha512", "source.ru.sha512", "source.en.sha512", "target.sha512")) {
            if (!required(p, key).matches("[a-fA-F0-9]{128}")) throw new IOException("Invalid Catalog SHA-512.");
        }
        hashValue(required(p, "base.sha256"));
        return p;
    }

    static boolean validCatalog(Path mc) throws IOException {
        Path target = resolve(mc, CATALOG_TARGET);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return false;
        Properties recipe = catalogRecipe(mc);
        Path base = resolve(mc, ".fallens/catalog/base.jar");
        if (!sha(base).equalsIgnoreCase(required(recipe, "base.sha256"))) throw new IOException("Catalog base checksum mismatch.");
        try (ZipFile original = new ZipFile(base.toFile()); ZipFile generated = new ZipFile(target.toFile())) {
            checkZip(original); checkZip(generated);
            Set<String> expected = new HashSet<>();
            Enumeration<? extends ZipEntry> entries = original.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory() || e.getName().equals(CATALOG_ENTRY)) continue;
                expected.add(e.getName()); ZipEntry actual = generated.getEntry(e.getName());
                if (actual == null || actual.isDirectory()) return false;
                try (InputStream a = original.getInputStream(e); InputStream b = generated.getInputStream(actual)) {
                    if (!sha(a).equals(sha(b))) return false;
                }
            }
            expected.add(CATALOG_ENTRY);
            Enumeration<? extends ZipEntry> all = generated.entries(); int size = 0;
            while (all.hasMoreElements()) { ZipEntry e = all.nextElement(); if (e.isDirectory() || !expected.contains(e.getName())) return false; size++; }
            return size == expected.size() && sha512(entryBytes(generated, CATALOG_ENTRY)).equalsIgnoreCase(required(recipe, "target.sha512"));
        } catch (ZipException e) { return false; }
    }

    static void generateCatalog(Path mc) throws IOException {
        Properties recipe = catalogRecipe(mc);
        Path base = resolve(mc, ".fallens/catalog/base.jar");
        Path upstream = resolve(mc, required(recipe, "upstream.path"));
        if (!sha(base).equalsIgnoreCase(required(recipe, "base.sha256")) || !sha512(upstream).equalsIgnoreCase(required(recipe, "upstream.sha512"))) {
            throw new IOException("Catalog base or official Macaw Windows checksum mismatch.");
        }
        byte[] locale;
        try (ZipFile source = new ZipFile(upstream.toFile())) {
            checkZip(source);
            byte[] ru = entryBytes(source, "assets/mcwwindows/lang/ru_ru.json"), en = entryBytes(source, "assets/mcwwindows/lang/en_us.json");
            if (!sha512(ru).equalsIgnoreCase(required(recipe, "source.ru.sha512")) || !sha512(en).equalsIgnoreCase(required(recipe, "source.en.sha512"))) {
                throw new IOException("Official Macaw Windows language checksum mismatch.");
            }
            locale = repairWindowsLocale(ru, en);
        }
        if (!sha512(locale).equalsIgnoreCase(required(recipe, "target.sha512"))) throw new IOException("Locally repaired Catalog locale checksum mismatch.");
        Path target = resolve(mc, CATALOG_TARGET); Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".fallens-catalog-", ".tmp");
        try {
            try (ZipFile source = new ZipFile(base.toFile()); ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temp))) {
                checkZip(source); Enumeration<? extends ZipEntry> entries = source.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement(); if (e.isDirectory() || e.getName().equals(CATALOG_ENTRY)) continue;
                    ZipEntry dest = new ZipEntry(e.getName()); dest.setTime(315532800000L); out.putNextEntry(dest);
                    try (InputStream in = source.getInputStream(e)) { in.transferTo(out); } out.closeEntry();
                }
                ZipEntry e = new ZipEntry(CATALOG_ENTRY); e.setTime(315532800000L); out.putNextEntry(e); out.write(locale); out.closeEntry();
            }
            replace(temp, target);
        } finally { Files.deleteIfExists(temp); }
        if (!validCatalog(mc)) throw new IOException("Generated Catalog JAR failed verification.");
    }

    static byte[] repairWindowsLocale(byte[] ruRaw, byte[] enRaw) throws IOException {
        Map<String, String> ru = FlatJson.parse(ruRaw), en = FlatJson.parse(enRaw);
        Map<Character, Integer> reverse = new HashMap<>();
        String special = "\u20ac\u0081\u201a\u0192\u201e\u2026\u2020\u2021\u02c6\u2030\u0160\u2039\u0152\u008d\u017d\u008f\u0090\u2018\u2019\u201c\u201d\u2022\u2013\u2014\u02dc\u2122\u0161\u203a\u0153\u009d\u017e\u0178";
        for (int b = 0; b < 256; b++) reverse.put(b >= 128 && b < 160 ? special.charAt(b - 128) : (char) b, b);
        LinkedHashMap<String, String> result = new LinkedHashMap<>(); int normal = 0;
        Pattern placeholders = Pattern.compile("%(?:\\d+\\$)?[a-zA-Z%]");
        for (Map.Entry<String, String> entry : ru.entrySet()) {
            String value = entry.getValue();
            if (value.isEmpty() || !(value.contains("\u00d0") || value.contains("\u00d1"))) { normal++; continue; }
            byte[] bytes = new byte[value.length()];
            for (int i = 0; i < value.length(); i++) {
                Integer b = reverse.get(value.charAt(i)); if (b == null) throw new IOException("Macaw locale contains an unsupported encoding character."); bytes[i] = (byte) (int) b;
            }
            String fixed = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (fixed.equals(value) || !Pattern.compile("[А-Яа-яЁё]").matcher(fixed).find() || fixed.indexOf('\ufffd') >= 0 ||
                fixed.chars().anyMatch(c -> c >= 128 && c <= 159) ||
                !placeholders.matcher(value).results().map(m -> m.group()).toList().equals(placeholders.matcher(fixed).results().map(m -> m.group()).toList())) {
                throw new IOException("Macaw locale repair did not satisfy its encoding invariants.");
            }
            result.put(entry.getKey(), fixed);
        }
        if (result.size() != 342 || normal != 2) throw new IOException("Unexpected Macaw locale key set.");
        int derived = 0;
        for (Map.Entry<String, String> entry : en.entrySet()) {
            String key = entry.getKey(); if (ru.containsKey(key) || key.equals("itemGroup.mcwwindows")) continue;
            String oak = key.replace("poplar", "oak");
            if (!key.contains("poplar") || !result.containsKey(oak) || !Objects.equals(en.get(oak), entry.getValue().replace("Poplar", "Oak"))) throw new IOException("Unexpected Macaw poplar key.");
            String candidate = result.get(oak);
            for (String[] pair : new String[][]{{"Дубов", "Тополев"}, {"дубов", "тополев"}, {"Дуба", "Тополя"}, {"дуба", "тополя"}, {"Дуб", "Тополь"}, {"дуб", "тополь"}}) candidate = candidate.replace(pair[0], pair[1]);
            if (candidate.equals(result.get(oak))) throw new IOException("Macaw poplar name was not derived.");
            result.put(key, candidate); derived++;
        }
        if (derived != 18) throw new IOException("Unexpected number of Macaw poplar keys.");
        result.put("itemGroup.mcwwindows", "Macaw’s Windows: окна и жалюзи");
        return FlatJson.encode(result);
    }

    /** Strict parser for the language files' flat JSON object of strings. */
    static final class FlatJson {
        final String text; int p;
        FlatJson(String text) { this.text = text; }
        void space() { while (p < text.length() && " \t\r\n".indexOf(text.charAt(p)) >= 0) p++; }
        void token(char c) throws IOException { space(); if (p >= text.length() || text.charAt(p++) != c) throw new IOException("Invalid flat language JSON."); }
        String string() throws IOException {
            token('"'); StringBuilder out = new StringBuilder();
            while (p < text.length()) {
                char c = text.charAt(p++); if (c == '"') return out.toString();
                if (c < 32) throw new IOException("Unescaped JSON control character.");
                if (c != '\\') { out.append(c); continue; }
                if (p >= text.length()) throw new IOException("Truncated JSON escape.");
                char escape = text.charAt(p++);
                switch (escape) {
                    case '"', '\\', '/' -> out.append(escape);
                    case 'b' -> out.append('\b'); case 'f' -> out.append('\f'); case 'n' -> out.append('\n'); case 'r' -> out.append('\r'); case 't' -> out.append('\t');
                    case 'u' -> { if (p + 4 > text.length()) throw new IOException("Truncated Unicode escape."); try { out.append((char) Integer.parseInt(text.substring(p, p + 4), 16)); } catch (NumberFormatException e) { throw new IOException("Invalid Unicode escape."); } p += 4; }
                    default -> throw new IOException("Invalid JSON escape.");
                }
            }
            throw new IOException("Unterminated JSON string.");
        }
        static Map<String, String> parse(byte[] bytes) throws IOException {
            String decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (decoded.startsWith("\ufeff")) decoded = decoded.substring(1);
            FlatJson parser = new FlatJson(decoded);
            LinkedHashMap<String, String> map = new LinkedHashMap<>(); parser.token('{'); parser.space();
            if (parser.p < parser.text.length() && parser.text.charAt(parser.p) == '}') parser.p++;
            else while (true) {
                String key = parser.string(); parser.token(':'); String value = parser.string();
                if (map.put(key, value) != null) throw new IOException("Duplicate language JSON key.");
                parser.space(); if (parser.p >= parser.text.length()) throw new IOException("Unterminated language object.");
                char c = parser.text.charAt(parser.p++); if (c == '}') break; if (c != ',') throw new IOException("Invalid language object separator.");
            }
            parser.space(); if (parser.p != parser.text.length()) throw new IOException("Trailing language JSON data.");
            return map;
        }
        static Object parseAny(byte[] bytes) throws IOException {
            String decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (decoded.startsWith("\ufeff")) decoded = decoded.substring(1);
            FlatJson parser = new FlatJson(decoded); Object result = parser.value(0); parser.space();
            if (parser.p != parser.text.length()) throw new IOException("Trailing JSON data."); return result;
        }
        Object value(int depth) throws IOException {
            if (depth > 40) throw new IOException("Excessively nested JSON.");
            space(); if (p >= text.length()) throw new IOException("Truncated JSON.");
            char c = text.charAt(p);
            if (c == '"') return string();
            if (c == '{') {
                p++; Map<String, Object> object = new LinkedHashMap<>(); space();
                if (p < text.length() && text.charAt(p) == '}') { p++; return object; }
                while (true) {
                    String key = string(); token(':'); Object val = value(depth + 1);
                    if (object.containsKey(key)) throw new IOException("Duplicate JSON key."); object.put(key, val); space();
                    if (p >= text.length()) throw new IOException("Truncated JSON object.");
                    c = text.charAt(p++); if (c == '}') return object; if (c != ',') throw new IOException("Invalid JSON separator.");
                }
            }
            if (c == '[') {
                p++; List<Object> array = new ArrayList<>(); space();
                if (p < text.length() && text.charAt(p) == ']') { p++; return array; }
                while (true) {
                    array.add(value(depth + 1)); space(); if (p >= text.length()) throw new IOException("Truncated JSON array.");
                    c = text.charAt(p++); if (c == ']') return array; if (c != ',') throw new IOException("Invalid JSON separator.");
                }
            }
            for (String literal : List.of("true", "false", "null")) {
                if (text.startsWith(literal, p)) { p += literal.length(); return literal.equals("null") ? null : Boolean.valueOf(literal); }
            }
            int start = p; while (p < text.length() && "-+0123456789.eE".indexOf(text.charAt(p)) >= 0) p++;
            if (start == p) throw new IOException("Invalid JSON value.");
            String number = text.substring(start, p);
            if (!number.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")) throw new IOException("Invalid JSON number.");
            return number;
        }
        static String quote(String value) {
            StringBuilder out = new StringBuilder("\"");
            for (char c : value.toCharArray()) switch (c) {
                case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\"); case '\b' -> out.append("\\b"); case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                default -> { if (c < 32) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
            }
            return out.append('"').toString();
        }
        static byte[] encode(Map<String, String> map) {
            StringJoiner out = new StringJoiner(",\r\n", "{\r\n", "\r\n}\r\n");
            map.forEach((key, value) -> out.add("  " + quote(key) + ": " + quote(value)));
            return out.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    static boolean scoped(String name) {
        return FILES.contains(name) || TREES.stream().anyMatch(t -> name.startsWith(t + "/"));
    }

    static Map<String, Path> inventory(Path mc) throws IOException {
        Map<String, Path> files = new TreeMap<>();
        for (String tree : TREES) {
            Path root = resolve(mc, tree);
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) continue;
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Expected a managed directory: " + tree);
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException { noLinks(dir); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException {
                    noLinks(file);
                    if (!a.isRegularFile()) throw new IOException("Managed paths must contain regular files only.");
                    String name = pathName(mc.relativize(file).toString().replace('\\', '/'));
                    files.put("mc/" + name, file);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        for (String f : FILES) {
            Path file = resolve(mc, f);
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Expected a managed regular file: " + f);
                files.put("mc/" + f, file);
            }
        }
        Path mmc = mc.getParent().resolve("mmc-pack.json"); noLinks(mmc);
        if (Files.exists(mmc, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(mmc, LinkOption.NOFOLLOW_LINKS)) throw new IOException("mmc-pack.json is not a regular file.");
            files.put("instance/mmc-pack.json", mmc);
        }
        return files;
    }

    static void preflight(Path mc) throws IOException { inventory(mc); }

    static Path snapshot(Path mc) throws IOException {
        Map<String, Path> files = inventory(mc);
        Path root = resolve(mc, ".fallens/backups");
        Files.createDirectories(root);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now());
        Path backup = Files.createTempDirectory(root, stamp + "-");
        Properties p = new Properties(); p.setProperty("format", "fallens-backup-1");
        p.setProperty("created.utc", Instant.now().toString());
        p.setProperty("count", Integer.toString(files.size()));
        int i = 0; long total = 0;
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(backup.resolve("snapshot.zip")))) {
            for (Map.Entry<String, Path> entry : files.entrySet()) {
                long size = Files.size(entry.getValue()); total += size;
                if (size > ENTRY_LIMIT || total > TOTAL_LIMIT) throw new IOException("Managed backup exceeds its size limit.");
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                MessageDigest hash = digest();
                try (InputStream in = Files.newInputStream(entry.getValue())) {
                    byte[] buf = new byte[64 * 1024]; int n;
                    while ((n = in.read(buf)) != -1) { hash.update(buf, 0, n); zip.write(buf, 0, n); }
                }
                zip.closeEntry();
                p.setProperty("entry." + i + ".path", entry.getKey());
                p.setProperty("entry." + i + ".sha256", HexFormat.of().formatHex(hash.digest()));
                i++;
            }
        }
        p.setProperty("snapshot.sha256", shaLarge(backup.resolve("snapshot.zip")));
        save(backup.resolve("manifest.properties"), p);
        return backup;
    }

    // The backup ZIP can exceed the per-entry limit while each contained file cannot.
    static String shaLarge(Path path) throws IOException {
        MessageDigest hash = digest();
        try (InputStream in = Files.newInputStream(path)) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) != -1) hash.update(b, 0, n); }
        return HexFormat.of().formatHex(hash.digest());
    }

    static void recordAfter(Path mc, Path backup) throws IOException {
        Properties after = new Properties(); int i = 0;
        for (Map.Entry<String, Path> entry : inventory(mc).entrySet()) {
            after.setProperty("entry." + i + ".path", entry.getKey());
            after.setProperty("entry." + i + ".sha256", sha(entry.getValue())); i++;
        }
        after.setProperty("count", Integer.toString(i));
        save(backup.resolve("after.properties"), after);
    }

    static Path validateBackupLocation(Path mc, Path candidate) throws IOException {
        Path base = resolve(mc, ".fallens/backups");
        Path backup = candidate.toAbsolutePath().normalize();
        noLinks(backup);
        if (!backup.startsWith(base) || backup.equals(base) || !Files.isDirectory(backup, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Backup must be a directory inside this instance's .fallens/backups/.");
        }
        return backup;
    }

    static Path backupTarget(Path mc, String name) throws IOException {
        pathName(name);
        if (name.equals("instance/mmc-pack.json")) {
            Path p = mc.getParent().resolve("mmc-pack.json"); noLinks(p); return p;
        }
        if (!name.startsWith("mc/") || !scoped(name.substring(3))) throw new IOException("Backup entry is outside the managed scope: " + name);
        return resolve(mc, name.substring(3));
    }

    static int count(Properties p) throws IOException {
        try { int value = Integer.parseInt(required(p, "count")); if (value < 0 || value > 100000) throw new NumberFormatException(); return value; }
        catch (NumberFormatException e) { throw new IOException("Invalid backup entry count."); }
    }

    static void restore(Path mc, Path backup) throws IOException {
        backup = validateBackupLocation(mc, backup);
        Properties manifest = load(backup.resolve("manifest.properties"));
        if (!"fallens-backup-1".equals(manifest.getProperty("format"))) throw new IOException("Unsupported backup format.");
        Path snapshot = backup.resolve("snapshot.zip"); noLinks(snapshot);
        if (!shaLarge(snapshot).equals(hashValue(required(manifest, "snapshot.sha256")))) throw new IOException("Backup ZIP checksum mismatch; restoration was not started.");
        Map<String, String> before = new LinkedHashMap<>();
        int count = count(manifest);
        for (int i = 0; i < count; i++) {
            String name = required(manifest, "entry." + i + ".path");
            backupTarget(mc, name);
            if (before.put(name, hashValue(required(manifest, "entry." + i + ".sha256"))) != null) throw new IOException("Duplicate backup entry.");
        }
        try (ZipFile zip = new ZipFile(snapshot.toFile())) {
            checkZip(zip);
            if (zip.size() != before.size()) throw new IOException("Backup inventory does not match its ZIP.");
            // Validate the entire backup BEFORE deleting or overwriting any current file.
            for (Map.Entry<String, String> entry : before.entrySet()) {
                ZipEntry e = zip.getEntry(entry.getKey());
                if (e == null || e.isDirectory()) throw new IOException("Backup entry is missing.");
                try (InputStream in = zip.getInputStream(e)) {
                    if (!sha(in).equals(entry.getValue())) throw new IOException("Backup entry checksum mismatch.");
                }
            }
            Path afterPath = backup.resolve("after.properties");
            Map<Path, String> added = new LinkedHashMap<>();
            if (Files.exists(afterPath, LinkOption.NOFOLLOW_LINKS)) {
                Properties after = load(afterPath);
                for (int i = 0, n = count(after); i < n; i++) {
                    String name = required(after, "entry." + i + ".path");
                    Path target = backupTarget(mc, name);
                    String recorded = hashValue(required(after, "entry." + i + ".sha256"));
                    if (!before.containsKey(name)) added.put(target, recorded);
                }
            }
            for (Map.Entry<String, String> entry : before.entrySet()) {
                Path target = backupTarget(mc, entry.getKey());
                if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    String currentHash = sha(target);
                    if (!currentHash.equals(entry.getValue())) added.put(target, currentHash);
                }
            }
            Path recovery = null;
            for (Map.Entry<Path, String> entry : added.entrySet()) {
                if (Files.isRegularFile(entry.getKey(), LinkOption.NOFOLLOW_LINKS) && sha(entry.getKey()).equals(entry.getValue())) {
                    // A crash cannot reliably distinguish a newly downloaded mod from a
                    // player's file added after the crash. Remove it from the active mod
                    // scope, but retain its bytes in a separate recovery directory.
                    if (recovery == null) {
                        Path base = resolve(mc, ".fallens/recovery-files"); Files.createDirectories(base);
                        recovery = Files.createTempDirectory(base, backup.getFileName() + "-");
                    }
                    String relative = entry.getKey().startsWith(mc) ? "mc/" + mc.relativize(entry.getKey()).toString().replace('\\', '/') : "instance/mmc-pack.json";
                    Path destination = resolve(recovery, relative); Files.createDirectories(destination.getParent());
                    replace(entry.getKey(), destination);
                }
            }
            if (recovery != null) System.out.println("Rollback preserved additional or changed files in: " + recovery);
            for (String name : before.keySet()) {
                Path target = backupTarget(mc, name);
                if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && sha(target).equals(before.get(name))) continue;
                Files.createDirectories(target.getParent());
                Path temp = Files.createTempFile(target.getParent(), ".fallens-restore-", ".tmp");
                try {
                    try (InputStream in = zip.getInputStream(zip.getEntry(name)); OutputStream out = Files.newOutputStream(temp)) { in.transferTo(out); }
                    replace(temp, target);
                } finally { Files.deleteIfExists(temp); }
            }
        }
    }
}
