package com.liskovsoft.googlecommon.common.converters.jsonpath.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.ParseContext;
import com.jayway.jsonpath.spi.json.GsonJsonProvider;
import com.jayway.jsonpath.spi.mapper.GsonMappingProvider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Differential test of the json engines against the behavior of the old mapper.<br/>
 * <br/>
 * Three things are evaluated side by side for every (document, context, path):
 * <ol>
 * <li><b>old</b>: real Jayway JsonPath + Gson, where every value that is used as a context is first
 * serialized and parsed again. That's literally what the old JsonPathTypeAdapter did.</li>
 * <li><b>java</b>: the production {@link JaywayNode} (same libraries, no re-parsing)</li>
 * <li><b>native</b>: the production {@link NativeNode} (Rust)</li>
 * </ol>
 * They must agree on everything the mapper can observe: "path not found" vs value, structure,
 * key order, strings and the exact number representation.<br/>
 * <br/>
 * Documents: real recorded responses + generated ones (nulls, odd numbers, escapes...).<br/>
 * Paths: the app's expressions + paths generated from each document (with misses and type errors).<br/>
 * Contexts: the root and, recursively, values returned by previous evaluations and their list items.<br/>
 * <br/>
 * Usage: PathDiff --paths file --docs dir [--random N] [--synthetic N] [--contexts N] [--threads N] [--seed N]
 */
public final class PathDiff {
    private static final ParseContext PARSER = JsonPath.using(Configuration.builder()
            .mappingProvider(new GsonMappingProvider())
            .jsonProvider(new GsonJsonProvider())
            .build());
    private static final int MAX_DEPTH = 3;
    private static final int MAX_REPORTED = 25;

    private static final AtomicLong sDocs = new AtomicLong();
    private static final AtomicLong sDocsDelegated = new AtomicLong();
    private static final AtomicLong sBytes = new AtomicLong();
    private static final AtomicLong sEvals = new AtomicLong();
    private static final AtomicLong sValues = new AtomicLong();
    private static final AtomicLong sNotFound = new AtomicLong();
    private static final AtomicLong sContexts = new AtomicLong();
    private static final AtomicLong sCopyContexts = new AtomicLong();
    private static final AtomicLong sMismatches = new AtomicLong();
    private static final Map<String, Long> sMismatchKinds = new ConcurrentHashMap<>();
    private static final Map<String, Long> sDelegated = new ConcurrentHashMap<>();
    private static final Set<String> sUnsupportedSamples = ConcurrentHashMap.newKeySet();

    private static int sRandomPaths = 300;
    private static int sContextsPerLevel = 40;
    private static long sSeed = 1;

    public static void main(String[] args) throws Exception {
        String pathsFile = null;
        List<String> docDirs = new ArrayList<>();
        int synthetic = 300;
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        boolean minifyOption = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--paths": pathsFile = args[++i]; break;
                case "--docs": docDirs.add(args[++i]); break;
                case "--random": sRandomPaths = Integer.parseInt(args[++i]); break;
                case "--synthetic": synthetic = Integer.parseInt(args[++i]); break;
                case "--contexts": sContextsPerLevel = Integer.parseInt(args[++i]); break;
                case "--threads": threads = Integer.parseInt(args[++i]); break;
                case "--seed": sSeed = Long.parseLong(args[++i]); break;
                case "--minify": minifyOption = true; break;
                default: throw new IllegalArgumentException(args[i]);
            }
        }

        if (!NativeJson.isAvailable()) {
            System.out.println("FAIL: native library isn't available (java.library.path=" + System.getProperty("java.library.path") + ")");
            System.exit(2);
        }

        // ---- the app's own expressions
        List<JsonPathExpr> appPaths = new ArrayList<>();
        int unsupported = 0;
        if (pathsFile != null) {
            for (String line : Files.readAllLines(new File(pathsFile).toPath(), StandardCharsets.UTF_8)) {
                if (line.isEmpty()) {
                    continue;
                }
                JsonPathExpr expr = new JsonPathExpr(line);
                if (expr.nativeHandle() <= 0) {
                    unsupported++;
                    System.out.println("not supported natively (falls back to Jayway): " + line);
                }
                appPaths.add(expr);
            }
        }
        System.out.println("app expressions: " + appPaths.size() + ", not supported natively: " + unsupported);
        final JsonPathExpr[] paths = appPaths.toArray(new JsonPathExpr[0]);

        // ---- documents
        List<File> files = new ArrayList<>();
        for (String dir : docDirs) {
            collect(new File(dir), files);
        }
        Collections.sort(files);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> tasks = new ArrayList<>();
        long start = System.currentTimeMillis();

        final boolean minify = minifyOption;

        // The reference side needs a lot of memory for big documents (that's the whole point). One by one.
        final Semaphore bigDocs = new Semaphore(2);

        for (final File file : files) {
            tasks.add(pool.submit(() -> {
                boolean big = file.length() > 512 * 1024;
                try {
                    if (big) {
                        bigDocs.acquire();
                    }
                    checkDoc(file.getName(), Fixtures.load(file, minify), paths, new Random(sSeed ^ file.getName().hashCode()));
                } catch (Throwable e) {
                    mismatch("harness error", file.getName(), String.valueOf(e), null, null);
                    e.printStackTrace(System.out);
                } finally {
                    if (big) {
                        bigDocs.release();
                    }
                }
            }));
        }

        for (int i = 0; i < synthetic; i++) {
            final int n = i;
            tasks.add(pool.submit(() -> {
                Random rnd = new Random(sSeed * 7919 + n);
                String json = Generator.document(rnd);
                try {
                    checkDoc("synthetic#" + n, json.getBytes(StandardCharsets.UTF_8), paths, rnd);
                } catch (Throwable e) {
                    mismatch("harness error", "synthetic#" + n, e + " json=" + clip(json), null, null);
                    e.printStackTrace(System.out);
                }
            }));
        }

        for (Future<?> task : tasks) {
            task.get();
        }
        pool.shutdown();

        targeted();

        System.out.println();
        System.out.println("documents            : " + sDocs.get() + " (" + (sBytes.get() / 1024 / 1024) + " MB), of them left to Jayway: " + sDocsDelegated.get());
        System.out.println("contexts             : " + sContexts.get() + " (returned by a previous evaluation: " + sCopyContexts.get() + ")");
        System.out.println("evaluations compared : " + sEvals.get() + " (old vs java vs native)");
        System.out.println("  all returned value : " + sValues.get());
        System.out.println("  all 'not found'    : " + sNotFound.get());
        System.out.println("left to Jayway       : " + new TreeMap<>(sDelegated));
        System.out.println("  e.g.               : " + sUnsupportedSamples);
        System.out.println("MISMATCHES           : " + sMismatches.get() + " " + new TreeMap<>(sMismatchKinds));
        System.out.println("time                 : " + (System.currentTimeMillis() - start) / 1000 + " s");
        System.out.println(sMismatches.get() == 0 ? "RESULT: PASS" : "RESULT: FAIL");
        System.exit(sMismatches.get() == 0 ? 0 : 1);
    }

    private static void collect(File file, List<File> out) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    collect(child, out);
                }
            }
        } else if (file.getName().endsWith(".json")) {
            out.add(file);
        }
    }

    // ------------------------------------------------------------------ one document

    private static final class Ctx {
        /** What the old mapper would read from */
        final JsonElement oldElement;
        final JaywayNode old;
        final JaywayNode java;
        final NativeNode nativeNode;
        final String desc;
        final int depth;

        Ctx(JsonElement oldElement, JaywayNode java, NativeNode nativeNode, String desc, int depth) {
            this.oldElement = oldElement;
            this.old = new JaywayNode(PARSER, oldElement);
            this.java = java;
            this.nativeNode = nativeNode;
            this.desc = desc;
            this.depth = depth;
        }
    }

    /**
     * Random sample of the values that could become contexts of the next level.<br/>
     * Can't keep all of them: every context of the old mapper is a separate Gson tree (megabytes).
     */
    private static final class Reservoir {
        final List<Ctx> items = new ArrayList<>();
        private final int mCapacity;
        private final Random mRandom;
        private int mSeen;
        private int mSlot;

        Reservoir(int capacity, Random random) {
            mCapacity = capacity;
            mRandom = random;
        }

        /**
         * @return true if the next candidate is going to be kept (call {@link #put} then)
         */
        boolean offer() {
            mSeen++;

            if (items.size() < mCapacity) {
                mSlot = items.size();
                return true;
            }

            mSlot = mRandom.nextInt(mSeen);

            return mSlot < mCapacity;
        }

        void put(Ctx ctx) {
            if (mSlot == items.size()) {
                items.add(ctx);
            } else {
                items.set(mSlot, ctx);
            }
        }
    }

    /**
     * The old mapper: <code>readType(type, jsonVal.toString())</code> and then <code>mParser.parse(jsonContent)</code>
     */
    private static JsonElement oldContext(JsonElement value) {
        return PARSER.parse(value.toString()).json();
    }

    private static void checkDoc(String name, byte[] bytes, JsonPathExpr[] appPaths, Random rnd) throws Exception {
        sDocs.incrementAndGet();
        sBytes.addAndGet(bytes.length);

        // Reference: exactly what the mapper did before (decode as UTF-8, parse with Gson through Jayway)
        JsonElement root = null;
        try {
            root = PARSER.parse(new String(bytes, StandardCharsets.UTF_8)).json();
        } catch (RuntimeException e) {
            // not a json
        }

        NativeDocument doc = NativeDocument.load(new ByteArrayInputStream(bytes), false);
        try {
            if (!doc.isReady()) {
                // Fine: such content is replayed on the Java engine. But the replay has to be exact.
                sDocsDelegated.incrementAndGet();
                if (!Arrays.equals(readAll(doc.replay()), bytes)) {
                    mismatch("replay differs from the original content", name, "", null, null);
                }
                return;
            }

            if (root == null) {
                mismatch("native accepted a document that Gson rejects", name, "", null, null);
                return;
            }

            NativeNode nativeRoot = (NativeNode) doc.root();

            // 1. The whole document must look the same
            if (!same(name, "<whole document>", root, null, nativeRoot)) {
                return;
            }

            // 2. Paths. Start from the root, continue from returned values.
            List<Ctx> level = new ArrayList<>();
            level.add(new Ctx(root, new JaywayNode(PARSER, root), nativeRoot, "$", 0));

            for (int depth = 0; depth <= MAX_DEPTH && !level.isEmpty(); depth++) {
                Reservoir candidates = new Reservoir(sContextsPerLevel, rnd);

                for (Ctx ctx : level) {
                    sContexts.incrementAndGet();
                    if (depth > 0) {
                        sCopyContexts.incrementAndGet();
                    }

                    for (JsonPathExpr path : appPaths) {
                        checkPath(name, ctx, path, candidates, rnd);
                    }

                    for (int i = 0; i < sRandomPaths; i++) {
                        checkPath(name, ctx, JsonPathExpr.temporary(Generator.path(ctx.oldElement, rnd)), candidates, rnd);
                    }
                }

                level = candidates.items;
            }
        } finally {
            doc.close();
        }
    }

    private static void checkPath(String name, Ctx ctx, JsonPathExpr path, Reservoir candidates, Random rnd) {
        // ---- native
        NativeNode nativeResult;
        try {
            nativeResult = (NativeNode) ctx.nativeNode.read(path);
        } catch (NativeUnsupportedException e) {
            // Always acceptable: the mapper replays the whole request on Jayway
            if (e.getMessage().startsWith("Unsupported path")) {
                sDelegated.merge("unsupported expression", 1L, Long::sum);
                if (sUnsupportedSamples.size() < 12) {
                    sUnsupportedSamples.add(path.toString());
                }
            } else {
                sDelegated.merge("unsupported evaluation", 1L, Long::sum);
            }
            return;
        }

        // ---- old mapper
        JaywayNode oldResult;
        try {
            oldResult = (JaywayNode) ctx.old.read(path);
        } catch (RuntimeException e) {
            // Not PathNotFoundException. The old mapper aborts the object here. Native must not answer such requests.
            mismatch("jayway throws but native answers", name, e.getClass().getSimpleName(), ctx, path);
            return;
        }

        // ---- new java engine
        JaywayNode javaResult;
        try {
            javaResult = (JaywayNode) ctx.java.read(path);
        } catch (RuntimeException e) {
            mismatch("java engine throws", name, e.toString(), ctx, path);
            return;
        }

        sEvals.incrementAndGet();

        if ((oldResult != null) != (nativeResult != null)) {
            mismatch("found/not found: native", name, "old " + (oldResult != null ? "returns " + clip(String.valueOf(element(oldResult))) : "throws PathNotFound")
                    + " but native " + (nativeResult != null ? "returns " + nativeResult : "says not found"), ctx, path);
            return;
        }

        if ((oldResult != null) != (javaResult != null)) {
            mismatch("found/not found: java engine", name, "old " + (oldResult != null ? "returns" : "throws") + ", java engine " + (javaResult != null ? "returns" : "throws"), ctx, path);
            return;
        }

        if (oldResult == null) {
            sNotFound.incrementAndGet();
            return;
        }

        sValues.incrementAndGet();
        JsonElement oldValue = element(oldResult);
        String desc = ctx.desc + " -> " + path;

        if (!same(name, desc, oldValue, element(javaResult), nativeResult)) {
            return;
        }

        if (ctx.depth < MAX_DEPTH) {
            // The value becomes a context (nested object of the model)
            if (candidates.offer()) {
                candidates.put(new Ctx(oldContext(oldValue), javaResult, nativeResult, desc, ctx.depth + 1));
            }

            if (oldValue instanceof JsonArray && ((JsonArray) oldValue).size() > 0) {
                // List items become contexts too
                JsonArray array = (JsonArray) oldValue;
                for (int k = 0; k < 2; k++) {
                    if (candidates.offer()) {
                        int idx = rnd.nextInt(array.size());
                        candidates.put(new Ctx(oldContext(array.get(idx)), (JaywayNode) javaResult.elements().get(idx),
                                (NativeNode) nativeResult.elements().get(idx), desc + " #" + idx, ctx.depth + 1));
                    }
                }
            }
        }
    }

    private static JsonElement element(JaywayNode node) {
        return node.rawElement();
    }

    /**
     * @param java may be null (nothing to compare)
     */
    private static boolean same(String name, String what, JsonElement old, JsonElement java, NativeNode nativeNode) {
        long expected = Canon.of(old);

        if (java != null && Canon.of(java) != expected) {
            mismatch("content differs: java engine", name, what + "\n      old : " + clip(Canon.text(old)) + "\n      java: " + clip(Canon.text(java)), null, null);
            return false;
        }

        long actual;

        try {
            actual = Canon.of(nativeNode);
        } catch (NativeUnsupportedException e) {
            // E.g. lone surrogate inside. The mapper replays such documents on Jayway.
            sDelegated.merge("unrepresentable string", 1L, Long::sum);
            return false;
        }

        if (expected != actual) {
            mismatch("content differs: native", name, what + "\n      old   : " + clip(Canon.text(old)) + "\n      native: " + clip(Canon.text(nativeNode)), null, null);
            return false;
        }

        return true;
    }

    private static void mismatch(String kind, String name, String message, Ctx ctx, JsonPathExpr path) {
        sMismatchKinds.merge(kind, 1L, Long::sum);
        if (sMismatches.incrementAndGet() <= MAX_REPORTED) {
            System.out.println("MISMATCH (" + kind + ") [" + name + "] " + (path != null ? "path=" + path + " ctx=" + (ctx != null ? ctx.desc : "") + " : " : "") + message
                    + (ctx != null ? "\n      context: " + clip(String.valueOf(ctx.oldElement)) : ""));
        }
    }

    private static String clip(String s) {
        return s.length() > 400 ? s.substring(0, 400) + "...(" + s.length() + ")" : s;
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ canonical form

    /**
     * Everything observable about a value, as text or as a 64 bit hash of that text.
     */
    private static final class Canon {
        private long mHash = 0xcbf29ce484222325L;
        private final StringBuilder mText;

        private Canon(boolean text) {
            mText = text ? new StringBuilder() : null;
        }

        static long of(JsonElement element) {
            Canon c = new Canon(false);
            c.add(element);
            return c.mHash;
        }

        static long of(NativeNode node) {
            Canon c = new Canon(false);
            c.add(node);
            return c.mHash;
        }

        static String text(JsonElement element) {
            Canon c = new Canon(true);
            c.add(element);
            return c.mText.toString();
        }

        static String text(NativeNode node) {
            Canon c = new Canon(true);
            try {
                c.add(node);
            } catch (RuntimeException e) {
                c.mText.append("<").append(e).append(">");
            }
            return c.mText.toString();
        }

        private void put(String s) {
            for (int i = 0; i < s.length(); i++) {
                mHash = (mHash ^ s.charAt(i)) * 0x100000001b3L;
            }
            mHash = (mHash ^ 0xFFFF) * 0x100000001b3L;
            if (mText != null && mText.length() < 4000) {
                mText.append(s);
            }
        }

        private void add(JsonElement e) {
            if (e == null || e.isJsonNull()) {
                put("null");
            } else if (e.isJsonPrimitive()) {
                put(primitive(e.getAsJsonPrimitive()));
            } else if (e.isJsonArray()) {
                put("[");
                for (JsonElement item : e.getAsJsonArray()) {
                    add(item);
                    put(",");
                }
                put("]");
            } else {
                put("{");
                for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) {
                    put(entry.getKey());
                    put(":");
                    add(entry.getValue());
                    put(",");
                }
                put("}");
            }
        }

        private void add(NativeNode n) {
            if (n.isPrimitive()) {
                put(primitive(n.asPrimitive()));
            } else if (n.isArray()) {
                put("[");
                for (JsonNode item : n.elements()) {
                    add((NativeNode) item);
                    put(",");
                }
                put("]");
            } else if (n.isObject()) {
                put("{");
                // Gson: one entry per key. First position, last value.
                Map<String, NativeNode> unique = new LinkedHashMap<>();
                List<NativeNode> members = n.members();
                for (int i = 0; i < members.size(); i += 2) {
                    unique.put(members.get(i).asPrimitive().getAsString(), members.get(i + 1));
                }
                for (Map.Entry<String, NativeNode> entry : unique.entrySet()) {
                    put(entry.getKey());
                    put(":");
                    add(entry.getValue());
                    put(",");
                }
                put("}");
            } else {
                put("null");
            }
        }

        /**
         * What the mapper's parsePrimitive() produces + the exact representation behind it
         */
        private static String primitive(JsonPrimitive p) {
            StringBuilder sb = new StringBuilder();
            if (p.isNumber()) {
                sb.append("N(").append(p.getAsNumber().getClass().getSimpleName()).append(' ').append(p).append(")=");
                try {
                    if (p.toString().contains(".")) {
                        sb.append("Float:").append(Float.floatToRawIntBits(p.getAsFloat()));
                    } else {
                        sb.append("Integer:").append(p.getAsInt());
                    }
                } catch (RuntimeException e) {
                    sb.append(e.getClass().getSimpleName());
                }
            } else if (p.isBoolean()) {
                sb.append("B:").append(p.getAsBoolean());
            } else {
                sb.append("S:").append(p.getAsString());
            }
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------ generators

    private static final class Generator {
        private static final String[] KEYS = {"a", "b", "c", "d", "items", "x-y", "k_1", "title", "runs", "text"};
        private static final String[] NUMBERS = {
                "0", "-0", "7", "-13", "2147483647", "2147483648", "-2147483648", "-2147483649", "9223372036854775807",
                "9223372036854775808", "123456789012345678901234567890", "1.0", "1.5", "-0.0", "0.1", "12.50", "100.0",
                "3.141592653589793238", "1e5", "1E5", "1.5e3", "1e-3", "2.5E+2", "0.000001", "1e400", "-1e-400", "0e0",
                "1.7777778", "0.30000000000000004", "16777217", "16777217.0", "4.9E-324", "1.7976931348623157E308"
        };
        private static final String[] STRINGS = {
                "\"\"", "\"abc\"", "\"with space\"", "\"quote\\\"inside\"", "\"back\\\\slash\"", "\"sl\\/ash\"",
                "\"line\\nbreak\\ttab\\r\\b\\f\"", "\"\\u0041\\u00e9\\u4e2d\"", "\"\\ud83d\\ude00\"", "\"é ✓ 中文\"", "\"😀 raw\"",
                "\"\\u0000nul\"", "\"1\"", "\"1.5\"", "\"true\"", "\"null\"", "\"$.a[0]\""
        };

        static String document(Random r) {
            StringBuilder sb = new StringBuilder();
            // Sometimes the root is an array (lounge commands etc)
            value(sb, r, 0, r.nextInt(6) == 0 ? 2 : 1);
            return sb.toString();
        }

        /** @param force 0 any, 1 object, 2 array */
        private static void value(StringBuilder sb, Random r, int depth, int force) {
            int kind = force != 0 ? force - 1 : r.nextInt(depth > 5 ? 3 : 10);
            ws(sb, r);
            if (force == 0 && kind >= 2 || depth > 6) {
                switch (r.nextInt(6)) {
                    case 0: sb.append("null"); break;
                    case 1: sb.append(r.nextBoolean()); break;
                    case 2: case 3: sb.append(STRINGS[r.nextInt(STRINGS.length)]); break;
                    default: sb.append(NUMBERS[r.nextInt(NUMBERS.length)]); break;
                }
            } else if (kind == 0) {
                sb.append('{');
                List<String> keys = new ArrayList<>(Arrays.asList(KEYS));
                Collections.shuffle(keys, r);
                int n = r.nextInt(6);
                for (int i = 0; i < n; i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    ws(sb, r);
                    // sometimes the key is written with an escape
                    String key = keys.get(i);
                    sb.append('"').append(r.nextInt(12) == 0 ? "\\u00" + Integer.toHexString(key.charAt(0)) + key.substring(1) : key).append('"');
                    ws(sb, r);
                    sb.append(':');
                    value(sb, r, depth + 1, 0);
                }
                ws(sb, r);
                sb.append('}');
            } else {
                sb.append('[');
                int n = r.nextInt(5);
                for (int i = 0; i < n; i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    value(sb, r, depth + 1, 0);
                }
                ws(sb, r);
                sb.append(']');
            }
            ws(sb, r);
        }

        private static void ws(StringBuilder sb, Random r) {
            if (r.nextInt(10) == 0) {
                sb.append(" \n\t\r".charAt(r.nextInt(4)));
            }
        }

        /**
         * Walks the value and writes down the way. Sometimes takes a wrong turn on purpose.
         */
        static String path(JsonElement model, Random r) {
            StringBuilder sb = new StringBuilder("$");
            JsonElement current = model;
            int steps = 1 + r.nextInt(7);

            for (int i = 0; i < steps; i++) {
                int dice = r.nextInt(100);

                if (current != null && current.isJsonObject() && dice < 80) {
                    JsonObject object = current.getAsJsonObject();
                    List<String> keys = new ArrayList<>();
                    for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                        if (isPlainName(entry.getKey())) {
                            keys.add(entry.getKey());
                        }
                    }
                    if (keys.isEmpty() || dice < 6) {
                        sb.append(".missing");
                        current = null;
                    } else if (dice < 14) {
                        sb.append(r.nextBoolean() ? "[*]" : ".*");
                        current = object.get(keys.get(r.nextInt(keys.size())));
                    } else {
                        String key = keys.get(r.nextInt(keys.size()));
                        sb.append(dice < 20 ? "['" + key + "']" : "." + key);
                        current = object.get(key);
                    }
                } else if (current != null && current.isJsonArray() && dice < 85) {
                    JsonArray array = current.getAsJsonArray();
                    int size = array.size();
                    JsonElement next = size > 0 ? array.get(r.nextInt(size)) : null;
                    if (dice < 30) {
                        sb.append("[*]");
                    } else if (dice < 40) {
                        // slices of all kinds, including out of range ones
                        int a = r.nextInt(size + 3) - 1 - (r.nextInt(4) == 0 ? size : 0);
                        int b = r.nextInt(size + 3) - 1 - (r.nextInt(4) == 0 ? size : 0);
                        switch (r.nextInt(3)) {
                            case 0: sb.append('[').append(a).append(":]"); break;
                            case 1: sb.append("[:").append(b).append(']'); break;
                            default: sb.append('[').append(a).append(':').append(b).append(']'); break;
                        }
                    } else if (dice < 50) {
                        // negative and out of range
                        sb.append('[').append(r.nextInt(2 * size + 4) - size - 2).append(']');
                    } else if (size > 0) {
                        int idx = r.nextInt(size);
                        sb.append('[').append(idx).append(']');
                        next = array.get(idx);
                    } else {
                        sb.append("[0]");
                    }
                    current = next;
                } else {
                    // Wrong turn: property of an array, index of an object, something on a primitive...
                    switch (r.nextInt(5)) {
                        case 0: sb.append(".a"); break;
                        case 1: sb.append("[0]"); break;
                        case 2: sb.append("[*]"); break;
                        case 3: sb.append("[1:]"); break;
                        default: sb.append(".title"); break;
                    }
                    current = null;
                }
            }

            return sb.toString();
        }

        private static boolean isPlainName(String key) {
            if (key.isEmpty()) {
                return false;
            }
            for (int i = 0; i < key.length(); i++) {
                char c = key.charAt(i);
                if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '-')) {
                    return false;
                }
            }
            return true;
        }
    }

    // ------------------------------------------------------------------ targeted cases

    /**
     * Things that random documents rarely hit.
     */
    private static void targeted() {
        // Duplicate keys: last one wins for lookups. Iteration is left to Jayway.
        expect("{\"o\":{\"a\":1,\"a\":2,\"b\":3}}", "$.o.a", false);
        expect("{\"o\":{\"a\":1,\"a\":2,\"b\":3}}", "$.o.b", false);
        expect("{\"o\":{\"a\":1,\"a\":2,\"b\":3}}", "$.o[*]", true);
        expect("{\"o\":{\"a\":{\"k\":1},\"a\":null}}", "$.o.a.k", false);
        // Unpaired surrogates can't cross the border
        expect("{\"s\":\"\\ud800\"}", "$.s", true);
        expect("{\"s\":\"\\ud800\",\"t\":1}", "$.t", false);
        // Deep nesting
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            deep.append("{\"a\":[");
        }
        deep.append("1");
        for (int i = 0; i < 200; i++) {
            deep.append("]}");
        }
        expect(deep.toString(), "$.a[0].a[0].a[*]", false);
    }

    /**
     * @param delegated the native engine is expected to refuse (the mapper falls back to Jayway)
     */
    private static void expect(String json, String pathSource, boolean delegated) {
        JsonPathExpr path = JsonPathExpr.temporary(pathSource);
        JsonElement root = PARSER.parse(json).json();
        NativeDocument doc = NativeDocument.load(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), false);
        String name = "targeted " + clip(json) + " " + pathSource;
        try {
            if (!doc.isReady()) {
                mismatch("targeted", name, "native rejects the document", null, null);
                return;
            }
            boolean refused = false;
            String nativeText = null;
            try {
                NativeNode result = (NativeNode) doc.root().read(path);
                nativeText = result != null ? Canon.text(result) : "<not found>";
                if (result != null) {
                    Canon.of(result); // throws if the value can't be represented
                }
            } catch (NativeUnsupportedException e) {
                refused = true;
            }
            if (refused != delegated) {
                mismatch("targeted", name, "expected native to " + (delegated ? "refuse" : "answer") + " but it " + (refused ? "refused" : "answered " + nativeText), null, null);
                return;
            }
            if (!refused) {
                JaywayNode expected = (JaywayNode) new JaywayNode(PARSER, root).read(path);
                String jaywayText = expected != null ? Canon.text(element(expected)) : "<not found>";
                sEvals.incrementAndGet();
                if (!jaywayText.equals(nativeText)) {
                    mismatch("targeted", name, "jayway: " + jaywayText + " native: " + nativeText, null, null);
                }
            }
        } finally {
            doc.close();
        }
    }
}
