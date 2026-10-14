package com.liskovsoft.googlecommon.common.converters.jsonpath.engine;

import com.liskovsoft.googlecommon.common.converters.jsonpath.JsonPath;
import com.liskovsoft.googlecommon.common.helpers.ReflectionHelper;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Model classes of the app: anything that has a field with @JsonPath.
 */
final class Models {
    private Models() {
    }

    /**
     * @param classDirs directories with compiled classes to look at (desktop)
     * @param listFile  or a file with the class names, one per line (devices: there are no class files there)
     */
    static List<Class<?>> find(List<File> classDirs, String listFile) throws IOException {
        List<Class<?>> result = new ArrayList<>();

        if (listFile != null) {
            for (String line : Files.readAllLines(new File(listFile).toPath(), StandardCharsets.UTF_8)) {
                add(line.trim(), result);
            }
        }

        for (File dir : classDirs) {
            scan(dir, dir, result);
        }

        Collections.sort(result, (a, b) -> a.getName().compareTo(b.getName()));

        return result;
    }

    static List<Field> annotatedFields(Class<?> type) {
        List<Field> result = new ArrayList<>();

        for (Field field : ReflectionHelper.getAllFields(type)) {
            if (field.getAnnotation(JsonPath.class) != null) {
                result.add(field);
            }
        }

        return result;
    }

    private static void scan(File root, File file, List<Class<?>> out) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    scan(root, child, out);
                }
            }
            return;
        }

        String path = file.getPath();
        if (path.endsWith(".class")) {
            add(path.substring(root.getPath().length() + 1, path.length() - ".class".length()).replace(File.separatorChar, '.'), out);
        }
    }

    private static void add(String name, List<Class<?>> out) {
        if (name.isEmpty()) {
            return;
        }

        try {
            // NOTE: no static initialization
            Class<?> type = Class.forName(name, false, Models.class.getClassLoader());
            if (!type.isInterface() && !type.isAnnotation() && !annotatedFields(type).isEmpty()) {
                out.add(type);
            }
        } catch (Throwable e) {
            // Depends on something that isn't on the classpath. Not a model.
        }
    }
}
