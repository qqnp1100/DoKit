package com.didichuxing.doraemonkit.kit.filemanager;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class DoKitFileScopeTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void canonicalAndroidRootAliasCanBeResolvedRepeatedly() throws Exception {
        Path realRoot = temp.newFolder("data-data-app").toPath();
        Path alias = temp.getRoot().toPath().resolve("data-user-0-app");
        Files.createSymbolicLink(alias, realRoot);
        Path file = Files.write(realRoot.resolve("probe.txt"), new byte[] {1});
        String[] roots = {alias.toString()};
        String resolved = DoKitFileScope.resolve(alias.resolve("probe.txt").toString(), roots);
        assertEquals(file.toFile().getCanonicalPath(), resolved);
        assertEquals(resolved, DoKitFileScope.resolve(resolved, roots));
    }

    @Test public void canonicalAliasStillRejectsTraversalAndEscapingSymlinks() throws Exception {
        Path realRoot = temp.newFolder("allowed").toPath();
        Path outside = temp.newFolder("outside").toPath();
        Path alias = temp.getRoot().toPath().resolve("alias");
        Files.createSymbolicLink(alias, realRoot);
        Files.createSymbolicLink(realRoot.resolve("escape"), outside);
        String[] roots = {alias.toString(), outside.toString()};
        for (Path invalid : new Path[] {alias.resolve("../outside/file"),
                alias.resolve("escape/file"), realRoot.resolve("escape/file")}) {
            try {
                DoKitFileScope.resolve(invalid.toString(), roots);
                fail("Escaping the originally selected root must be rejected");
            } catch (IllegalArgumentException expected) { }
        }
    }
}
