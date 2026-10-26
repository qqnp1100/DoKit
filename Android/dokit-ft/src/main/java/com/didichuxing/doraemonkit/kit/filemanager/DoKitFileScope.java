package com.didichuxing.doraemonkit.kit.filemanager;

import java.io.File;
import java.io.IOException;

/** Check the final target, including existing symlinks, inside the originally selected root. */
public final class DoKitFileScope {
    private DoKitFileScope() {}
    public static String resolve(String path, String[] roots) {
        if (path == null || path.indexOf(0) >= 0) throw new IllegalArgumentException("Invalid path");
        for (String component : path.split("/")) if (component.equals("..")) throw new IllegalArgumentException("Parent traversal is not allowed");
        try {
            String absolute = new File(path).getAbsolutePath();
            for (String root : roots) {
                String lexicalRoot = new File(root).getAbsolutePath();
                String realRoot = new File(root).getCanonicalPath();
                // Android may alias /data/user/0 to /data/data. Accept our own
                // canonical output on subsequent checks without widening the root.
                if (absolute.equals(lexicalRoot) || absolute.startsWith(lexicalRoot + File.separator) ||
                        absolute.equals(realRoot) || absolute.startsWith(realRoot + File.separator)) {
                    String target = new File(path).getCanonicalPath();
                    if (target.equals(realRoot) || target.startsWith(realRoot + File.separator)) return target;
                    throw new IllegalArgumentException("Path escapes selected root");
                }
            }
        } catch (IOException failure) { throw new IllegalArgumentException("Cannot resolve path", failure); }
        throw new IllegalArgumentException("Path is outside DoKit roots");
    }
}
