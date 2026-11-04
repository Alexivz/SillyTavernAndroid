package com.sillytavern.app;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs extras/cl-helper from the official Character Library ZIP. */
public final class ClHelperInstaller {
    private static final long MAX_TOTAL_BYTES = 40L * 1024L * 1024L;
    private ClHelperInstaller() {}

    public static String install(Context context, Uri zipUri) throws Exception {
        if (!InstanceManager.payloadDir(context).isDirectory()) {
            throw new Exception("SillyTavern is not installed yet");
        }
        for (InstanceManager.Instance inst : InstanceManager.list(context)) {
            if (ServerService.isRunning(inst.id)) {
                throw new Exception("Stop every running instance before installing cl-helper");
            }
        }
        File pluginRoot = new File(InstanceManager.payloadDir(context), "plugins");
        File target = new File(pluginRoot, "cl-helper");
        File temporary = new File(pluginRoot, "cl-helper-installing");
        if (!pluginRoot.isDirectory() && !pluginRoot.mkdirs()) {
            throw new Exception("Cannot create plugins directory");
        }
        if (temporary.exists()) {
            InstanceManager.deleteRecursively(temporary);
        }
        if (!temporary.mkdirs()) {
            throw new Exception("Cannot create temporary plugin directory");
        }
        boolean foundIndex = false;
        boolean foundPackage = false;
        long total = 0;
        try {
            try (InputStream input = context.getContentResolver().openInputStream(zipUri)) {
                if (input == null) throw new Exception("Cannot open selected ZIP");
                try (ZipInputStream zip = new ZipInputStream(input)) {
                    ZipEntry entry;
                    byte[] buffer = new byte[65536];
                    while ((entry = zip.getNextEntry()) != null) {
                        String name = entry.getName().replace('\\', '/');
                        int p = name.indexOf("extras/cl-helper/");
                        if (p < 0) continue;
                        String relative = name.substring(p + "extras/cl-helper/".length());
                        if (relative.isEmpty()) continue;
                        if (relative.startsWith("/") || relative.equals("..") || relative.startsWith("../") || relative.contains("/../") || relative.contains("\0")) {
                            throw new Exception("Unsafe ZIP path");
                        }
                        File out = new File(temporary, relative);
                        if (!out.getCanonicalPath().startsWith(temporary.getCanonicalPath() + File.separator)) {
                            throw new Exception("Unsafe ZIP entry");
                        }
                        if (entry.isDirectory()) {
                            if (!out.isDirectory() && !out.mkdirs()) throw new Exception("Cannot create folder");
                        } else {
                            File parent = out.getParentFile();
                            if (!parent.isDirectory() && !parent.mkdirs()) throw new Exception("Cannot create folder");
                            try (FileOutputStream fos = new FileOutputStream(out)) {
                                int n;
                                while ((n = zip.read(buffer)) != -1) {
                                    total += n;
                                    if (total > MAX_TOTAL_BYTES) throw new Exception("Plugin ZIP too large");
                                    fos.write(buffer, 0, n);
                                }
                            }
                            if (relative.equals("index.js")) foundIndex = true;
                            if (relative.equals("package.json")) foundPackage = true;
                        }
                        zip.closeEntry();
                    }
                }
            }
            if (!foundIndex || !foundPackage) {
                throw new Exception("Selected ZIP has no extras/cl-helper/index.js and package.json");
            }
            if (target.exists()) {
                throw new Exception("cl-helper already exists; update is not supported by this installer");
            }
            if (!temporary.renameTo(target)) throw new Exception("Cannot finalize plugin installation");
            // The payload is shared, but config.yaml belongs to each instance.
            List<InstanceManager.Instance> instances = InstanceManager.list(context);
            for (InstanceManager.Instance inst : instances) {
                InstanceManager.ensureInstanceDirs(context, inst);
                File config = InstanceManager.configFile(context, inst.id);
                String yaml = new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8);
                if (java.util.regex.Pattern.compile("(?m)^enableServerPlugins\\s*:").matcher(yaml).find()) {
                    yaml = yaml.replaceFirst("(?m)^enableServerPlugins\\s*:[^\\r\\n]*", "enableServerPlugins: true");
                } else {
                    if (!yaml.endsWith("\n")) yaml += "\n";
                    yaml += "enableServerPlugins: true\n";
                }
                Files.write(config.toPath(), yaml.getBytes(StandardCharsets.UTF_8));
            }
            return "cl-helper installed. Start SillyTavern and check Character Library.";
        } finally {
            if (temporary.exists()) InstanceManager.deleteRecursively(temporary);
        }
    }
}
