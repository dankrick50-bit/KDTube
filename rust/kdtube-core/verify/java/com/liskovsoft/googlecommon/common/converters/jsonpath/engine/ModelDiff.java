package com.liskovsoft.googlecommon.common.converters.jsonpath.engine;

import com.google.gson.JsonElement;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.ParseContext;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.spi.json.GsonJsonProvider;
import com.jayway.jsonpath.spi.mapper.GsonMappingProvider;
import com.liskovsoft.googlecommon.common.converters.jsonpath.typeadapter.JsonPathSkipTypeAdapter;
import com.liskovsoft.googlecommon.common.converters.jsonpath.typeadapter.JsonPathTypeAdapter;
import com.liskovsoft.googlecommon.common.converters.jsonpath.typeadapter.LegacyJsonPathSkipTypeAdapter;
import com.liskovsoft.googlecommon.common.converters.jsonpath.typeadapter.LegacyJsonPathTypeAdapter;
import com.liskovsoft.googlecommon.common.helpers.ReflectionHelper;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Differential test of the whole mapper: the original JsonPathTypeAdapter (source taken from the
 * upstream commit, see run script) against the new one, on both engines.<br/>
 * <br/>
 * Every model class of the app (anything with a @JsonPath field) is applied to every recorded
 * response. The resulting object graphs must be identical: same classes, same fields, same values
 * (Integer vs Float matters), same list order, same nulls.<br/>
 * <br/>
 * Usage: ModelDiff (--classes dir... | --model-list file) --docs dir [--minify] [--threads N] [--sample N]<br/>
 *        ModelDiff --classes dir... --print-models             (writes the list for --model-list)
 */
public final class ModelDiff {
    private static final ParseContext PARSER = JsonPath.using(Configuration.builder()
            .mappingProvider(new GsonMappingProvider())
            .jsonProvider(new GsonJsonProvider())
            .build());
    private static final int MAX_REPORTED = 20;
    private static final byte[] PREFIX = ")]}'\n".getBytes(StandardCharsets.UTF_8);

    private static final AtomicLong sPairs = new AtomicLong();
    private static final AtomicLong sPairsWithContent = new AtomicLong();
    private static final AtomicLong sNonNull = new AtomicLong();
    private static final AtomicLong sObjects = new AtomicLong();
    private static final AtomicLong sValues = new AtomicLong();
    private static final AtomicLong sSkipRuns = new AtomicLong();
    private static final AtomicLong sFailureRuns = new AtomicLong();
    private static final AtomicLong sMismatches = new AtomicLong();
    private static final Map<String, Long> sMismatchKinds = new ConcurrentHashMap<>();
    private static final Map<String, Long> sPopulated = new ConcurrentHashMap<>();

    /** What the old mapper produced. Key: doc + class + variant */
    private static final Map<String, Long> sExpected = new ConcurrentHashMap<>();
    private static boolean sMinify;

    public static void main(String[] args) throws Exception {
        List<File> classDirs = new ArrayList<>();
        List<String> docDirs = new ArrayList<>();
        String modelList = null;
        boolean printModels = false;
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        int sample = 3;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--classes": classDirs.add(new File(args[++i])); break;
                case "--model-list": modelList = args[++i]; break;
                case "--print-models": printModels = true; break;
                case "--docs": docDirs.add(args[++i]); break;
                case "--threads": threads = Integer.parseInt(args[++i]); break;
                case "--sample": sample = Integer.parseInt(args[++i]); break;
                case "--minify": sMinify = true; break;
                default: throw new IllegalArgumentException(args[i]);
            }
        }

        // ---- model classes: anything with an annotated field
        final List<Class<?>> models = Models.find(classDirs, modelList);

        if (printModels) {
            // For the devices: there are no class files to look at
            for (Class<?> model : models) {
                System.out.println(model.getName());
            }
            return;
        }

        if (!NativeJson.isAvailable()) {
            System.out.println("FAIL: native library isn't available");
            System.exit(2);
        }

        // The mappers print a stack trace for every object they can't create. Expected and very noisy.
        PrintStream realErr = System.err;
        System.setErr(new PrintStream(new java.io.OutputStream() {
            @Override
            public void write(int b) {
            }
        }));

        int paths = 0;
        for (Class<?> model : models) {
            paths += annotatedFields(model).size();
        }
        System.out.println("model classes: " + models.size() + " (" + paths + " annotated fields)");

        List<File> files = new ArrayList<>();
        for (String dir : docDirs) {
            Fixtures.collect(new File(dir), files);
        }
        Collections.sort(files);
        System.out.println("documents: " + files.size());

        long start = System.currentTimeMillis();
        final int sampleSize = sample;

        // ---- phase 1: old mapper vs new mapper on the Java engine
        NativeJson.setEnabled(false);
        runAll(files, threads, (name, bytes) -> phaseJava(name, bytes, models, sampleSize));
        long javaDone = System.currentTimeMillis();

        // ---- phase 2: new mapper on the native engine vs what the old mapper produced
        NativeJson.setEnabled(true);
        long loadedBefore = NativeDocument.getLoadedCount();
        long replayedBefore = NativeDocument.getReplayedCount();
        runAll(files, threads, (name, bytes) -> phaseNative(name, bytes, models));
        long loaded = NativeDocument.getLoadedCount() - loadedBefore;
        long replayed = NativeDocument.getReplayedCount() - replayedBefore;

        System.setErr(realErr);

        System.out.println();
        System.out.println("(document, class) pairs      : " + sPairs.get());
        System.out.println("  with at least one path hit : " + sPairsWithContent.get() + " (compared against the old mapper, all three variants)");
        System.out.println("  old mapper returned object : " + sNonNull.get());
        System.out.println("  objects / values compared  : " + sObjects.get() + " / " + sValues.get());
        System.out.println("prefixed (skip adapter) runs : " + sSkipRuns.get());
        System.out.println("failing stream runs          : " + sFailureRuns.get());
        System.out.println("native engine                : " + loaded + " documents, " + replayed + " replayed on the Java engine");
        System.out.println("most populated classes       : " + top(sPopulated, 12));
        System.out.println("MISMATCHES                   : " + sMismatches.get() + " " + new TreeMap<>(sMismatchKinds));
        System.out.println("time                         : java phase " + (javaDone - start) / 1000 + " s, native phase " + (System.currentTimeMillis() - javaDone) / 1000 + " s");
        System.out.println(sMismatches.get() == 0 ? "RESULT: PASS" : "RESULT: FAIL");
        System.exit(sMismatches.get() == 0 ? 0 : 1);
    }

    private interface DocTask {
        void run(String name, byte[] bytes) throws Exception;
    }

    private static void runAll(List<File> files, int threads, DocTask task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (final File file : files) {
            futures.add(pool.submit(() -> {
                try {
                    task.run(file.getName(), Fixtures.load(file, sMinify));
                } catch (Throwable e) {
                    mismatch("harness error", file.getName(), null, String.valueOf(e));
                    e.printStackTrace(System.out);
                }
            }));
        }
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();
    }

    // ------------------------------------------------------------------ phases

    private static void phaseJava(String name, byte[] bytes, List<Class<?>> models, int sample) {
        // Which classes can produce anything from this document? Ask the reference engine:
        // if every path of every field throws, the old mapper returns null by construction.
        DocumentContext context = null;
        try {
            context = PARSER.parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            // not a json: every class is interesting
        }

        java.util.Random rnd = new java.util.Random(name.hashCode());

        for (Class<?> model : models) {
            sPairs.incrementAndGet();
            boolean hit = context == null || hasHit(context, model);

            if (hit) {
                sPairsWithContent.incrementAndGet();
            } else if (rnd.nextInt(100) >= sample) {
                // The native phase still checks that the new mapper returns null here
                sExpected.put(key(name, model, ""), NULL_HASH);
                continue;
            }

            // -- plain adapter
            Dump old = run(() -> new LegacyJsonPathTypeAdapter<>(PARSER, model).read(new ByteArrayInputStream(bytes)));
            Dump java = run(() -> new JsonPathTypeAdapter<>(PARSER, model).read(new ByteArrayInputStream(bytes)));
            sExpected.put(key(name, model, ""), old.hash);
            compare("java engine", name, model, old, java);

            if (old.nonNull) {
                sNonNull.incrementAndGet();
                sObjects.addAndGet(old.objects);
                sValues.addAndGet(old.values);
                sPopulated.merge(model.getSimpleName(), (long) old.values, Long::sum);
            }

            if (!hit) {
                continue;
            }

            // -- skip adapter: content behind a prefix line
            final byte[] prefixed = concat(PREFIX, bytes);
            Dump oldSkip = run(() -> new LegacyJsonPathSkipTypeAdapter<>(PARSER, model).read(new ByteArrayInputStream(prefixed)));
            Dump javaSkip = run(() -> new JsonPathSkipTypeAdapter<>(PARSER, model).read(new ByteArrayInputStream(prefixed)));
            sExpected.put(key(name, model, "skip"), oldSkip.hash);
            compare("java engine, skip adapter", name, model, oldSkip, javaSkip);
            sSkipRuns.incrementAndGet();

            // -- skip adapter without the prefix: the first line of the content itself is lost
            Dump oldEaten = run(() -> new LegacyJsonPathSkipTypeAdapter<>(PARSER, model).read(new ByteArrayInputStream(bytes)));
            Dump javaEaten = run(() -> new JsonPathSkipTypeAdapter<>(PARSER, model).read(new ByteArrayInputStream(bytes)));
            sExpected.put(key(name, model, "eaten"), oldEaten.hash);
            compare("java engine, skip adapter, no prefix", name, model, oldEaten, javaEaten);

            // -- connection drops in the middle
            final int cut = bytes.length / 2;
            Dump oldFail = run(() -> new LegacyJsonPathTypeAdapter<>(PARSER, model).read(new FailingStream(bytes, cut)));
            Dump javaFail = run(() -> new JsonPathTypeAdapter<>(PARSER, model).read(new FailingStream(bytes, cut)));
            sExpected.put(key(name, model, "fail"), oldFail.hash);
            compare("java engine, failing stream", name, model, oldFail, javaFail);
            sFailureRuns.incrementAndGet();
        }
    }

    private static void phaseNative(String name, byte[] bytes, List<Class<?>> models) {
        final byte[] prefixed = concat(PREFIX, bytes);
        final int cut = bytes.length / 2;

        for (Class<?> model : models) {
            Long expected = sExpected.get(key(name, model, ""));
            if (expected != null) {
                Dump actual = run(() -> new JsonPathTypeAdapter<>(PARSER, model).read(new ByteArrayInputStream(bytes)));
                compare("native engine", name, model, expected, actual);
            }

            expected = sExpected.get(key(name, model, "skip"));
            if (expected != null) {
                Dump actual = run(() -> new JsonPathSkipTypeAdapter<>(PARSER, model).read(new ByteArrayInputStream(prefixed)));
                compare("native engine, skip adapter", name, model, expected, actual);
            }

            expected = sExpected.get(key(name, model, "eaten"));
            if (expected != null) {
                Dump actual = run(() -> new JsonPathSkipTypeAdapter<>(PARSER, model).read(new ByteArrayInputStream(bytes)));
                compare("native engine, skip adapter, no prefix", name, model, expected, actual);
            }

            expected = sExpected.get(key(name, model, "fail"));
            if (expected != null) {
                Dump actual = run(() -> new JsonPathTypeAdapter<>(PARSER, model).read(new FailingStream(bytes, cut)));
                compare("native engine, failing stream", name, model, expected, actual);
            }
        }
    }

    private static boolean hasHit(DocumentContext context, Class<?> model) {
        for (Field field : annotatedFields(model)) {
            for (String path : field.getAnnotation(com.liskovsoft.googlecommon.common.converters.jsonpath.JsonPath.class).value()) {
                try {
                    context.read(path);
                    return true;
                } catch (PathNotFoundException e) {
                    // next one
                } catch (RuntimeException e) {
                    return true; // unusual: let the mappers deal with it
                }
            }
        }
        return false;
    }

    private static List<Field> annotatedFields(Class<?> type) {
        return Models.annotatedFields(type);
    }

    private static String key(String doc, Class<?> model, String variant) {
        return doc + "|" + model.getName() + "|" + variant;
    }

    private interface Mapper {
        Object map() throws Exception;
    }

    private static Dump run(Mapper mapper) {
        Dump dump = new Dump();
        try {
            dump.add(mapper.map(), 0);
        } catch (Throwable e) {
            // The exception is a part of the behavior too
            dump.put("THROWS " + e.getClass().getName());
        }
        return dump;
    }

    private static void compare(String kind, String doc, Class<?> model, Dump expected, Dump actual) {
        if (expected.hash != actual.hash) {
            mismatch(kind, doc, model, "\n      old: " + clip(expected.text.toString()) + "\n      new: " + clip(actual.text.toString()));
        }
    }

    private static void compare(String kind, String doc, Class<?> model, long expected, Dump actual) {
        if (expected != actual.hash) {
            mismatch(kind, doc, model, "\n      new: " + clip(actual.text.toString()) + (expected == NULL_HASH ? "\n      old: null" : ""));
        }
    }

    private static void mismatch(String kind, String doc, Class<?> model, String message) {
        sMismatchKinds.merge(kind, 1L, Long::sum);
        if (sMismatches.incrementAndGet() <= MAX_REPORTED) {
            System.out.println("MISMATCH (" + kind + ") [" + doc + "] " + (model != null ? model.getName() : "") + " " + message);
        }
    }

    private static String clip(String s) {
        return s.length() > 600 ? s.substring(0, 600) + "...(" + s.length() + ")" : s;
    }

    private static String top(Map<String, Long> map, int n) {
        List<Map.Entry<String, Long>> entries = new ArrayList<>(map.entrySet());
        Collections.sort(entries, (a, b) -> Long.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, entries.size()); i++) {
            sb.append(i > 0 ? ", " : "").append(entries.get(i).getKey()).append('=').append(entries.get(i).getValue());
        }
        return sb.toString();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    // ------------------------------------------------------------------ object graph dump

    static final long NULL_HASH = new Dump().addAndGet(null);

    /**
     * Everything observable about an object graph: classes, field names, values with their types.
     */
    static final class Dump {
        long hash = 0xcbf29ce484222325L;
        final StringBuilder text = new StringBuilder();
        boolean nonNull;
        int objects;
        int values;
        private final IdentityHashMap<Object, Boolean> mSeen = new IdentityHashMap<>();

        long addAndGet(Object o) {
            add(o, 0);
            return hash;
        }

        void put(String s) {
            for (int i = 0; i < s.length(); i++) {
                hash = (hash ^ s.charAt(i)) * 0x100000001b3L;
            }
            hash = (hash ^ 0xFFFF) * 0x100000001b3L;
            if (text.length() < 3000) {
                text.append(s);
            }
        }

        void add(Object o, int depth) {
            if (depth == 0) {
                nonNull = o != null;
            }

            if (o == null) {
                put("null");
                return;
            }

            Class<?> type = o.getClass();

            if (o instanceof String) {
                values++;
                put("\"" + o + "\"");
            } else if (o instanceof Float) {
                values++;
                put("Float:" + Float.floatToRawIntBits((Float) o));
            } else if (o instanceof Double) {
                values++;
                put("Double:" + Double.doubleToRawLongBits((Double) o));
            } else if (o instanceof Number || o instanceof Boolean || o instanceof Character || o instanceof Enum) {
                values++;
                put(type.getSimpleName() + ":" + o);
            } else if (o instanceof Collection) {
                put(type.getSimpleName() + "[");
                for (Object item : (Collection<?>) o) {
                    add(item, depth + 1);
                    put(",");
                }
                put("]");
            } else if (o instanceof Map) {
                put(type.getSimpleName() + "{");
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) o).entrySet()) {
                    add(entry.getKey(), depth + 1);
                    put("=");
                    add(entry.getValue(), depth + 1);
                    put(",");
                }
                put("}");
            } else if (type.isArray()) {
                put("array[");
                for (int i = 0; i < Array.getLength(o); i++) {
                    add(Array.get(o, i), depth + 1);
                    put(",");
                }
                put("]");
            } else if (type.getName().startsWith("com.liskovsoft.")) {
                if (mSeen.put(o, Boolean.TRUE) != null) {
                    put("<seen " + type.getSimpleName() + ">");
                    return;
                }
                objects++;
                put(type.getName() + "{");
                for (Field field : ReflectionHelper.getAllFields(type)) {
                    if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                        continue;
                    }
                    if (!field.getDeclaringClass().getName().startsWith("com.liskovsoft.")) {
                        // Android: java.lang.Object has fields of its own (class pointer, lock word)
                        continue;
                    }
                    put(field.getName() + "=");
                    try {
                        field.setAccessible(true);
                        add(field.get(o), depth + 1);
                    } catch (Exception e) {
                        put("<" + e.getClass().getSimpleName() + ">");
                    }
                    put(";");
                }
                put("}");
            } else {
                // Something foreign. Shouldn't be created by the mapper.
                values++;
                put(type.getName() + ":" + o);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Stream that breaks after a number of bytes (connection problems)
     */
    private static final class FailingStream extends InputStream {
        private final byte[] mData;
        private final int mLimit;
        private int mPos;

        FailingStream(byte[] data, int limit) {
            mData = data;
            mLimit = limit;
        }

        @Override
        public int read() throws IOException {
            if (mPos >= mLimit) {
                throw new IOException("unexpected end of stream");
            }
            return mData[mPos++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (mPos >= mLimit) {
                throw new IOException("unexpected end of stream");
            }
            int n = Math.min(len, mLimit - mPos);
            System.arraycopy(mData, mPos, b, off, n);
            mPos += n;
            return n;
        }
    }
}
