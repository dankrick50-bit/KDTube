package com.liskovsoft.googlecommon.common.converters.jsonpath.engine;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.ParseContext;
import com.jayway.jsonpath.spi.json.GsonJsonProvider;
import com.jayway.jsonpath.spi.mapper.GsonMappingProvider;
import com.liskovsoft.googlecommon.common.converters.jsonpath.typeadapter.JsonPathTypeAdapter;
import com.liskovsoft.googlecommon.common.converters.jsonpath.typeadapter.LegacyJsonPathTypeAdapter;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Cost of mapping one response: old mapper vs new mapper (Java engine) vs new mapper (native engine).<br/>
 * <br/>
 * <pre>
 * MemBench scan (--classes dir... | --model-list file) --docs dir     which (document, class) pairs are the heavy ones
 * MemBench run  --doc file --class name --mode old|java|native|gson [--runs N] [--pretty]
 * </pre>
 * 'run' reports the time and the number of bytes allocated on the Java heap per response.
 * On Android it also reports what the garbage collector had to do.
 * To find the heap that a mapper needs, run it with decreasing -Xmx until it fails.<br/>
 * <br/>
 * NOTE: numbers of a desktop JVM aren't the numbers of a device. Look at the ratios there.
 */
public final class MemBench {
    private static final ParseContext PARSER = JsonPath.using(Configuration.builder()
            .mappingProvider(new GsonMappingProvider())
            .jsonProvider(new GsonJsonProvider())
            .build());

    private static final Gson GSON = new Gson();

    public static void main(String[] args) throws Exception {
        String command = args.length > 0 ? args[0] : "";
        List<File> classDirs = new ArrayList<>();
        String modelList = null;
        String docs = null;
        String doc = null;
        String className = null;
        String mode = "native";
        int runs = 15;
        boolean minify = true;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--classes": classDirs.add(new File(args[++i])); break;
                case "--model-list": modelList = args[++i]; break;
                case "--docs": docs = args[++i]; break;
                case "--doc": doc = args[++i]; break;
                case "--class": className = args[++i]; break;
                case "--mode": mode = args[++i]; break;
                case "--runs": runs = Integer.parseInt(args[++i]); break;
                case "--pretty": minify = false; break;
                default: throw new IllegalArgumentException(args[i]);
            }
        }

        // The mappers print a stack trace for every object they can't create
        PrintStream realErr = System.err;
        System.setErr(new PrintStream(new java.io.OutputStream() {
            @Override
            public void write(int b) {
            }
        }));

        if ("scan".equals(command)) {
            scan(Models.find(classDirs, modelList), docs, minify);
        } else if ("run".equals(command)) {
            run(new File(doc), Class.forName(className), mode, runs, minify);
        } else {
            System.setErr(realErr);
            System.err.println("Usage: MemBench scan|run ...");
            System.exit(2);
        }
    }

    // ------------------------------------------------------------------ scan

    private static void scan(List<Class<?>> models, String docs, boolean minify) throws Exception {
        List<File> files = new ArrayList<>();
        Fixtures.collect(new File(docs), files);
        Collections.sort(files);

        List<String> rows = new ArrayList<>();

        for (File file : files) {
            byte[] bytes = Fixtures.load(file, minify);

            for (Class<?> model : models) {
                ModelDiff.Dump dump = new ModelDiff.Dump();
                try {
                    dump.add(new JsonPathTypeAdapter<>(PARSER, model).read(new ByteArrayInputStream(bytes)), 0);
                } catch (Throwable e) {
                    continue;
                }
                if (dump.values > 200) {
                    rows.add(String.format("%08d values, %6d objects, %7.1f KB  %s  %s", dump.values, dump.objects, bytes.length / 1024.0, model.getName(), file.getPath()));
                }
            }
        }

        Collections.sort(rows, Collections.reverseOrder());
        for (int i = 0; i < Math.min(25, rows.size()); i++) {
            System.out.println(rows.get(i));
        }
    }

    // ------------------------------------------------------------------ run

    private static void run(File file, Class<?> model, String mode, int runs, boolean minify) throws Exception {
        final byte[] bytes = Fixtures.load(file, minify);
        NativeJson.setEnabled("native".equals(mode));

        if ("native".equals(mode) && !NativeJson.isAvailable()) {
            throw new IllegalStateException("native library isn't available");
        }

        long[] nanos = new long[runs];
        long[] allocated = new long[runs];
        int values = 0;
        int objects = 0;
        long gcCountBefore = Stats.art("art.gc.gc-count");
        long gcTimeBefore = Stats.art("art.gc.gc-time");
        long blockingCountBefore = Stats.art("art.gc.blocking-gc-count");
        long blockingTimeBefore = Stats.art("art.gc.blocking-gc-time");

        for (int i = 0; i < runs; i++) {
            long allocBefore = Stats.allocatedBytes();
            long start = System.nanoTime();

            Object result;

            if ("old".equals(mode)) {
                result = new LegacyJsonPathTypeAdapter<>(PARSER, model).read(new ByteArrayInputStream(bytes));
            } else if ("gson".equals(mode)) {
                // The 'v2' api of the app: no json path, Gson streams the response into data classes
                // (same as GsonResponseBodyConverter)
                JsonReader reader = GSON.newJsonReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
                result = GSON.getAdapter(model).read(reader);
            } else {
                result = new JsonPathTypeAdapter<>(PARSER, model).read(new ByteArrayInputStream(bytes));
            }

            nanos[i] = System.nanoTime() - start;
            allocated[i] = Stats.allocatedBytes() - allocBefore;

            if (i == runs - 1) {
                ModelDiff.Dump dump = new ModelDiff.Dump();
                dump.add(result, 0);
                values = dump.values;
                objects = dump.objects;
            }
        }

        // The first runs include class loading and the interpreter. Median of the second half.
        long[] warmNanos = Arrays.copyOfRange(nanos, runs / 2, runs);
        long[] warmAllocated = Arrays.copyOfRange(allocated, runs / 2, runs);
        Arrays.sort(warmNanos);
        Arrays.sort(warmAllocated);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("RESULT mode=%s json_kb=%.1f values=%d objects=%d first_ms=%.1f warm_ms=%.2f java_alloc_mb=%.2f replayed=%d",
                mode, bytes.length / 1024.0, values, objects, nanos[0] / 1e6, warmNanos[warmNanos.length / 2] / 1e6,
                warmAllocated[warmAllocated.length / 2] / 1048576.0, NativeDocument.getReplayedCount()));

        if (gcCountBefore >= 0) {
            // Android: the whole series
            sb.append(String.format(" runs=%d gc_count=%d gc_ms=%d blocking_gc_count=%d blocking_gc_ms=%d",
                    runs, Stats.art("art.gc.gc-count") - gcCountBefore, Stats.art("art.gc.gc-time") - gcTimeBefore,
                    Stats.art("art.gc.blocking-gc-count") - blockingCountBefore, Stats.art("art.gc.blocking-gc-time") - blockingTimeBefore));
        }

        System.out.println(sb);
    }

    /**
     * Allocation counters. Different on a desktop JVM and on Android, so everything is looked up at runtime.
     */
    private static final class Stats {
        private static Method sArtRuntimeStat;
        private static Method sThreadAllocatedBytes;
        private static Object sThreadBean;

        static {
            try {
                sArtRuntimeStat = Class.forName("android.os.Debug").getMethod("getRuntimeStat", String.class);
                // android.jar of the sdk on a desktop: the method is there but it's a stub
                sArtRuntimeStat.invoke(null, "art.gc.gc-count");
            } catch (Throwable e) {
                sArtRuntimeStat = null;
            }

            if (sArtRuntimeStat == null) {
                try {
                    sThreadBean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null);
                    sThreadAllocatedBytes = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", long.class);
                } catch (Throwable e) {
                    sThreadAllocatedBytes = null;
                }
            }
        }

        /**
         * @return -1 if this isn't Android
         */
        static long art(String name) {
            if (sArtRuntimeStat == null) {
                return -1;
            }
            try {
                return Long.parseLong((String) sArtRuntimeStat.invoke(null, name));
            } catch (Throwable e) {
                return -1;
            }
        }

        static long allocatedBytes() {
            if (sArtRuntimeStat != null) {
                return art("art.gc.bytes-allocated");
            }
            if (sThreadAllocatedBytes == null) {
                return 0;
            }
            try {
                return (Long) sThreadAllocatedBytes.invoke(sThreadBean, Thread.currentThread().getId());
            } catch (Throwable e) {
                return 0;
            }
        }
    }
}
