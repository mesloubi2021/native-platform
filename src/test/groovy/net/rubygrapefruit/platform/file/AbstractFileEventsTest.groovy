/*
 * Copyright 2012 Adam Murdoch
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package net.rubygrapefruit.platform.file

import groovy.transform.EqualsAndHashCode
import net.rubygrapefruit.platform.NativeException
import net.rubygrapefruit.platform.internal.Platform
import net.rubygrapefruit.platform.internal.jni.AbstractFileEventFunctions
import net.rubygrapefruit.platform.testfixture.JulLogging
import org.junit.Assume
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestName
import spock.lang.IgnoreIf
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.Timeout
import spock.lang.Unroll
import spock.util.concurrent.AsyncConditions

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.logging.Logger
import java.util.regex.Pattern

import static java.util.concurrent.TimeUnit.SECONDS
import static java.util.logging.Level.FINE
import static net.rubygrapefruit.platform.file.FileWatcherCallback.Type.CREATED
import static net.rubygrapefruit.platform.file.FileWatcherCallback.Type.INVALIDATE
import static net.rubygrapefruit.platform.file.FileWatcherCallback.Type.MODIFIED
import static net.rubygrapefruit.platform.file.FileWatcherCallback.Type.REMOVED

@Timeout(value = 60, unit = SECONDS)
abstract class AbstractFileEventsTest extends Specification {
    static final Logger LOGGER = Logger.getLogger(AbstractFileEventsTest.name)

    @Rule
    TemporaryFolder tmpDir
    @Rule
    TestName testName
    @Rule
    JulLogging logging = new JulLogging(AbstractFileEventFunctions, FINE)

    def callback = new TestCallback()
    File testDir
    File rootDir
    FileWatcher watcher
    List<Throwable> uncaughtFailureOnThread

    def setup() {
        LOGGER.info(">>> Running '${testName.methodName}'")
        testDir = tmpDir.newFolder(testName.methodName).canonicalFile
        rootDir = new File(testDir, "root")
        assert rootDir.mkdirs()
        uncaughtFailureOnThread = []
    }

    def cleanup() {
        stopWatcher()
        uncaughtFailureOnThread.each {
            it.printStackTrace()
        }
        assert uncaughtFailureOnThread.empty
        LOGGER.info("<<< Finished '${testName.methodName}'")
    }

    def "can start and stop watcher without watching any paths"() {
        when:
        startWatcher()

        then:
        noExceptionThrown()
    }

    def "can open and close watcher on a directory without receiving any events"() {
        when:
        startWatcher(rootDir)

        then:
        noExceptionThrown()
    }

    def "can detect file created"() {
        given:
        def createdFile = new File(rootDir, "created.txt")
        startWatcher(rootDir)

        when:
        def expectedChanges = expectEvents event(CREATED, createdFile)
        createNewFile(createdFile)

        then:
        expectedChanges.await()
    }

    def "can detect directory created"() {
        given:
        def createdDir = new File(rootDir, "created")
        startWatcher(rootDir)

        when:
        def expectedChanges = expectEvents event(CREATED, createdDir)
        assert createdDir.mkdirs()

        then:
        expectedChanges.await()
    }

    def "can detect file removed"() {
        given:
        def removedFile = new File(rootDir, "removed.txt")
        createNewFile(removedFile)
        startWatcher(rootDir)

        when:
        // Windows reports the file as modified before removing it
        def expectedChanges = expectEvents Platform.current().windows
            ? [event(MODIFIED, removedFile), event(REMOVED, removedFile)]
            : [event(REMOVED, removedFile)]
        removedFile.delete()

        then:
        expectedChanges.await()
    }

    def "can detect directory removed"() {
        given:
        def removedDir = new File(rootDir, "removed")
        assert removedDir.mkdirs()
        startWatcher(rootDir)

        when:
        def expectedChanges = expectEvents event(REMOVED, removedDir)
        removedDir.deleteDir()

        then:
        expectedChanges.await()
    }

    def "can detect file modified"() {
        given:
        def modifiedFile = new File(rootDir, "modified.txt")
        createNewFile(modifiedFile)
        startWatcher(rootDir)

        when:
        def expectedChanges = expectEvents event(MODIFIED, modifiedFile)
        modifiedFile << "change"

        then:
        expectedChanges.await()
    }

    @Requires({ Platform.current().macOs })
    def "can detect file metadata modified"() {
        given:
        def modifiedFile = new File(rootDir, "modified.txt")
        createNewFile(modifiedFile)
        startWatcher(rootDir)

        when:
        def expectedChanges = expectEvents event(MODIFIED, modifiedFile)
        modifiedFile.setReadable(false)

        then:
        expectedChanges.await()

        when:
        expectedChanges = expectEvents event(MODIFIED, modifiedFile)
        modifiedFile.setReadable(true)

        then:
        expectedChanges.await()
    }

    @Requires({ Platform.current().macOs })
    def "changing metadata doesn't mask content change"() {
        given:
        def modifiedFile = new File(rootDir, "modified.txt")
        modifiedFile.createNewFile()
        startWatcher(rootDir)

        when:
        def expectedChanges = expectEvents event(MODIFIED, modifiedFile)
        modifiedFile.setReadable(false)
        modifiedFile << "change"

        then:
        expectedChanges.await()
    }

    @Requires({ Platform.current().macOs })
    def "changing metadata doesn't mask removal"() {
        given:
        def removedFile = new File(rootDir, "removed.txt")
        createNewFile(removedFile)
        startWatcher(rootDir)

        when:
        def expectedChanges = expectEvents event(REMOVED, removedFile)
        removedFile.setReadable(false)
        assert removedFile.delete()

        then:
        expectedChanges.await()
    }

    def "can detect file renamed"() {
        given:
        def sourceFile = new File(rootDir, "source.txt")
        def targetFile = new File(rootDir, "target.txt")
        createNewFile(sourceFile)
        startWatcher(rootDir)

        when:
        def expectedChanges = expectEvents Platform.current().windows
            ? [event(REMOVED, sourceFile), event(CREATED, targetFile), event(MODIFIED, targetFile, false)]
            : [event(REMOVED, sourceFile), event(CREATED, targetFile)]
        sourceFile.renameTo(targetFile)

        then:
        expectedChanges.await()
    }

    def "can detect file moved out"() {
        given:
        def outsideDir = new File(testDir, "outside")
        assert outsideDir.mkdirs()
        def sourceFileInside = new File(rootDir, "source-inside.txt")
        def targetFileOutside = new File(outsideDir, "target-outside.txt")
        createNewFile(sourceFileInside)
        startWatcher(rootDir)

        when:
        def expectedChanges = expectEvents event(REMOVED, sourceFileInside)
        sourceFileInside.renameTo(targetFileOutside)

        then:
        expectedChanges.await()
    }

    def "can detect file moved in"() {
        given:
        def outsideDir = new File(testDir, "outside")
        assert outsideDir.mkdirs()
        def sourceFileOutside = new File(outsideDir, "source-outside.txt")
        def targetFileInside = new File(rootDir, "target-inside.txt")
        createNewFile(sourceFileOutside)
        startWatcher(rootDir)

        when:
        // On Windows we sometimes get a MODIFIED event after CREATED for some reason
        def expectedChanges = expectEvents Platform.current().windows
            ? [event(CREATED, targetFileInside), event(MODIFIED, targetFileInside, false)]
            : [event(CREATED, targetFileInside)]
        sourceFileOutside.renameTo(targetFileInside)

        then:
        expectedChanges.await()
    }

    @Requires({ Platform.current().linux || Platform.current().macOs })
    def "can detect changes in symlinked watched directory"() {
        given:
        def canonicalFile = new File(rootDir, "modified.txt")
        createNewFile(canonicalFile)
        def watchedLink = new File(testDir, "linked")
        def watchedFile = new File(watchedLink, "modified.txt")
        java.nio.file.Files.createSymbolicLink(watchedLink.toPath(), rootDir.toPath())
        startWatcher(watchedLink)

        when:
        def expectedChanges = expectEvents Platform.current().macOs
            ? event(MODIFIED, canonicalFile)
            : event(MODIFIED, watchedFile)
        watchedFile << "change"

        then:
        expectedChanges.await()
    }

    def "can receive multiple events from the same directory"() {
        given:
        def firstFile = new File(rootDir, "first.txt")
        def secondFile = new File(rootDir, "second.txt")
        startWatcher(rootDir)

        when:
        def expectedChanges = expectEvents event(CREATED, firstFile)
        createNewFile(firstFile)

        then:
        expectedChanges.await()

        when:
        expectedChanges = expectEvents event(CREATED, secondFile)
        waitForChangeEventLatency()
        createNewFile(secondFile)

        then:
        expectedChanges.await()
    }

    def "does not receive events from unwatched directory"() {
        given:
        def watchedFile = new File(rootDir, "watched.txt")
        def unwatchedDir = new File(testDir, "unwatched")
        assert unwatchedDir.mkdirs()
        def unwatchedFile = new File(unwatchedDir, "unwatched.txt")
        startWatcher(rootDir)

        when:
        def expectedChanges = expectEvents event(CREATED, watchedFile)
        createNewFile(unwatchedFile)
        createNewFile(watchedFile)
        // Let's make sure there are no events for the unwatched file,
        // and we don't just miss them because of timing
        waitForChangeEventLatency()

        then:
        expectedChanges.await()
    }

    def "can start and stop watching directory while changes are being made to its contents"() {
        given:
        def numberOfParallelWritersPerWatchedDirectory = 100
        def numberOfWatchedDirectories = 4

        def callback = new FileWatcherCallback() {
            @Override
            void pathChanged(FileWatcherCallback.Type type, String path) {
                assert !path.empty
            }

            @Override
            void reportError(Throwable ex) {
                ex.printStackTrace()
                uncaughtFailureOnThread << ex
            }
        }

        def watchedDirectories = (1..numberOfWatchedDirectories).collect { new File(rootDir, "watchedDir$it") }
        watchedDirectories.each { assert it.mkdirs() }

        expect:
        20.times {
            def executorService = Executors.newFixedThreadPool(numberOfParallelWritersPerWatchedDirectory * numberOfWatchedDirectories)
            def readyLatch = new CountDownLatch(numberOfParallelWritersPerWatchedDirectory * numberOfWatchedDirectories)
            def startModifyingLatch = new CountDownLatch(1)
            def watcher = startNewWatcher(callback)
            watchedDirectories.each { watchedDirectory ->
                numberOfParallelWritersPerWatchedDirectory.times { index ->
                    executorService.submit({ ->
                        def fileToChange = new File(watchedDirectory, "file${index}.txt")
                        readyLatch.countDown()
                        startModifyingLatch.await()
                        fileToChange.createNewFile()
                        500.times { modifyIndex ->
                            fileToChange << "Another change: $modifyIndex\n"
                        }
                    })
                }
            }
            executorService.shutdown()

            watchedDirectories.each {
                watcher.startWatching(it)
            }
            readyLatch.await()
            startModifyingLatch.countDown()
            Thread.sleep(500)
            watcher.close()
            assert uncaughtFailureOnThread.empty
        }
    }

    // Apparently on macOS we can watch non-existent directories
    // TODO Should we fail for this?
    @IgnoreIf({ Platform.current().macOs })
    def "fails when watching non-existent directory"() {
        given:
        def missingDirectory = new File(rootDir, "missing")

        when:
        startWatcher(missingDirectory)

        then:
        def ex = thrown NativeException
        ex.message ==~ /Couldn't add watch.*: ${Pattern.quote(missingDirectory.absolutePath)}/
    }

    // Apparently on macOS we can watch files
    // TODO Should we fail for this?
    @IgnoreIf({ Platform.current().macOs })
    def "fails when watching file"() {
        given:
        def file = new File(rootDir, "file.txt")

        when:
        startWatcher(file)

        then:
        def ex = thrown NativeException
        ex.message ==~ /Couldn't add watch.*: ${Pattern.quote(file.absolutePath)}/
    }

    def "fails when watching directory twice"() {
        given:
        startWatcher(rootDir)

        when:
        watcher.startWatching(rootDir)

        then:
        def ex = thrown NativeException
        ex.message == "Already watching path: ${rootDir.absolutePath}"
    }

    def "can un-watch path that was not watched"() {
        given:
        startWatcher()

        when:
        watcher.stopWatching(rootDir)

        then:
        noExceptionThrown()
    }

    def "can un-watch watched directory twice"() {
        given:
        startWatcher(rootDir)
        watcher.stopWatching(rootDir)

        when:
        watcher.stopWatching(rootDir)

        then:
        noExceptionThrown()
    }

    def "does not receive events after directory is unwatched"() {
        given:
        def file = new File(rootDir, "first.txt")
        def callback = Mock(FileWatcherCallback)
        startWatcher(callback, rootDir)
        watcher.stopWatching(rootDir)

        when:
        createNewFile(file)

        then:
        0 * callback.pathChanged(_ as FileWatcherCallback.Type, _ as String)
        0 * _
    }

    def "can receive multiple events from multiple watched directories"() {
        given:
        def firstWatchedDir = new File(testDir, "first")
        assert firstWatchedDir.mkdirs()
        def firstFileInFirstWatchedDir = new File(firstWatchedDir, "first-watched.txt")
        def secondWatchedDir = new File(testDir, "second")
        assert secondWatchedDir.mkdirs()
        def secondFileInSecondWatchedDir = new File(secondWatchedDir, "sibling-watched.txt")
        startWatcher(firstWatchedDir, secondWatchedDir)

        when:
        def expectedChanges = expectEvents event(CREATED, firstFileInFirstWatchedDir)
        createNewFile(firstFileInFirstWatchedDir)

        then:
        expectedChanges.await()

        when:
        expectedChanges = expectEvents event(CREATED, secondFileInSecondWatchedDir)
        createNewFile(secondFileInSecondWatchedDir)

        then:
        expectedChanges.await()
    }

    @Requires({ !Platform.current().linux })
    def "can receive events from directory with different casing"() {
        given:
        def lowercaseDir = new File(rootDir, "watch-this")
        def uppercaseDir = new File(rootDir, "WATCH-THIS")
        def fileInLowercaseDir = new File(lowercaseDir, "lowercase.txt")
        def fileInUppercaseDir = new File(uppercaseDir, "UPPERCASE.TXT")
        uppercaseDir.mkdirs()
        startWatcher(lowercaseDir)

        when:
        def expectedChanges = expectEvents event(CREATED, fileInLowercaseDir.canonicalFile)
        createNewFile(fileInLowercaseDir)

        then:
        expectedChanges.await()

        when:
        expectedChanges = expectEvents event(CREATED, fileInUppercaseDir.canonicalFile)
        createNewFile(fileInUppercaseDir)

        then:
        expectedChanges.await()
    }

    def "can handle exception in callback"() {
        given:
        def createdFile = new File(rootDir, "created.txt")
        def conditions = new AsyncConditions()
        when:
        startWatcher(new FileWatcherCallback() {
            @Override
            void pathChanged(FileWatcherCallback.Type type, String path) {
                throw new RuntimeException("Error")
            }

            @Override
            void reportError(Throwable ex) {
                conditions.evaluate {
                    assert ex instanceof NativeException
                    assert ex.message == "Caught java.lang.RuntimeException while calling callback: Error"
                }
            }
        }, rootDir)
        createNewFile(createdFile)

        then:
        conditions.await()
    }

    def "fails when stopped multiple times"() {
        given:
        def watcher = startNewWatcher(callback)
        watcher.close()

        when:
        watcher.close()

        then:
        def ex = thrown NativeException
        ex.message == "Closed already"
    }

    def "can be started and stopped multiple times"() {
        when:
        10.times { i ->
            LOGGER.info "> Iteration #${i + 1}"
            startWatcher(rootDir)
            stopWatcher()
        }

        then:
        noExceptionThrown()
    }

    def "can be used multiple times"() {
        given:
        def firstFile = new File(rootDir, "first.txt")
        def secondFile = new File(rootDir, "second.txt")
        startWatcher(rootDir)

        when:
        def expectedChanges = expectEvents event(CREATED, firstFile)
        createNewFile(firstFile)

        then:
        expectedChanges.await()
        stopWatcher()

        when:
        startWatcher(rootDir)
        expectedChanges = expectEvents event(CREATED, secondFile)
        createNewFile(secondFile)

        then:
        expectedChanges.await()
    }

    def "can stop and restart watching directory"() {
        given:
        def createdFile = new File(rootDir, "created.txt")
        startWatcher(rootDir)
        watcher.stopWatching(rootDir)
        watcher.startWatching(rootDir)

        when:
        def expectedChanges = expectEvents event(CREATED, createdFile)
        createNewFile(createdFile)

        then:
        expectedChanges.await()
    }

    def "can start multiple watchers"() {
        given:
        def firstRoot = new File(rootDir, "first")
        firstRoot.mkdirs()
        def secondRoot = new File(rootDir, "second")
        secondRoot.mkdirs()
        def firstFile = new File(firstRoot, "file.txt")
        def secondFile = new File(secondRoot, "file.txt")
        def firstCallback = new TestCallback()
        def secondCallback = new TestCallback()

        LOGGER.info("> Starting first watcher")
        def firstWatcher = startNewWatcher(firstCallback)
        firstWatcher.startWatching(firstRoot)
        LOGGER.info("> Starting second watcher")
        def secondWatcher = startNewWatcher(secondCallback)
        secondWatcher.startWatching(secondRoot)
        LOGGER.info("> Watchers started")

        when:
        def firstChanges = expectEvents firstCallback, event(CREATED, firstFile)
        createNewFile(firstFile)

        then:
        firstChanges.await()

        when:
        def secondChanges = expectEvents secondCallback, event(CREATED, secondFile)
        createNewFile(secondFile)

        then:
        secondChanges.await()

        cleanup:
        firstWatcher.close()
        secondWatcher.close()
    }

    @Requires({ !Platform.current().linux })
    def "can receive event about a non-direct descendant change"() {
        given:
        def subDir = new File(rootDir, "sub-dir")
        subDir.mkdirs()
        def fileInSubDir = new File(subDir, "watched-descendant.txt")
        startWatcher(rootDir)

        when:
        def expectedChanges = expectEvents event(CREATED, fileInSubDir)
        createNewFile(fileInSubDir)

        then:
        expectedChanges.await()
    }

    @Requires({ Platform.current().linux })
    def "does not receive event about a non-direct descendant change"() {
        given:
        def callback = Mock(FileWatcherCallback)
        def subDir = new File(rootDir, "sub-dir")
        subDir.mkdirs()
        def fileInSubDir = new File(subDir, "unwatched-descendant.txt")
        startWatcher(callback, rootDir)

        when:
        createNewFile(fileInSubDir)
        // Let's make sure there are no events occurring,
        // and we don't just miss them because of timing
        waitForChangeEventLatency()

        then:
        0 * callback.pathChanged(_ as FileWatcherCallback.Type, _ as String)
        0 * _
    }

    def "can watch directory with long path"() {
        given:
        def subDir = new File(rootDir, "long-path")
        4.times {
            subDir = new File(subDir, "X" * 200)
        }
        subDir.mkdirs()
        def fileInSubDir = new File(subDir, "watched-descendant.txt")
        startWatcher(subDir)

        when:
        def expectedChanges = expectEvents event(CREATED, fileInSubDir)
        createNewFile(fileInSubDir)

        then:
        expectedChanges.await()
    }

    @Unroll
    def "can watch directory with #type characters"() {
        Assume.assumeTrue(supported as boolean)

        given:
        def subDir = new File(rootDir, path)
        subDir.mkdirs()
        def fileInSubDir = new File(subDir, path)
        startWatcher(subDir)

        when:
        def expectedChanges = expectEvents event(CREATED, fileInSubDir)
        createNewFile(fileInSubDir)

        then:
        expectedChanges.await()

        where:
        type             | path                     | supported
        "ASCII-only"     | "directory"              | true
        "Chinese"        | "输入文件"                   | true
        "four-byte UTF8" | "𠜎𠜱𠝹𠱓"               | true
        "Hungarian"      | "Dezső"                  | true
        "space"          | "test directory"         | true
        "zwnj"           | "test\u200cdirectory"    | true
        "newline"        | "test\ndirectory"        | Platform.current().macOs
        "URL-quoted"     | "test%<directory>#2.txt" | !Platform.current().windows
    }

    @Unroll
    // TODO We currently don't detect if the whole watched directory is removed on Windows
    @IgnoreIf({ Platform.current().windows })
    def "can detect #removedAncestry removed"() {
        given:
        def parentDir = new File(rootDir, "parent")
        def watchedDir = new File(parentDir, "removed")
        watchedDir.mkdirs()
        def removedFile = new File(watchedDir, "file.txt")
        createNewFile(removedFile)
        File removedDir = removedDirectory(watchedDir)
        startWatcher(watchedDir)

        when:
        def expectedChanges = expectEvents Platform.current().macOs
            ? [event(INVALIDATE, watchedDir)]
            : [event(REMOVED, removedFile), event(REMOVED, watchedDir)]
        assert removedDir.deleteDir()

        then:
        expectedChanges.await()

        where:
        removedAncestry                     | removedDirectory
        "watched directory"                 | { it }
        "parent of watched directory"       | { it.parentFile }
        "grand-parent of watched directory" | { it.parentFile.parentFile }
    }

    protected void startWatcher(FileWatcherCallback callback = this.callback, File... roots) {
        watcher = startNewWatcher(callback)
        roots*.absoluteFile.each { root ->
            watcher.startWatching(root)
        }
    }

    protected abstract FileWatcher startNewWatcher(FileWatcherCallback callback)

    protected void stopWatcher() {
        def copyWatcher = watcher
        watcher = null
        copyWatcher?.close()
    }

    protected AsyncConditions expectNoEvents(FileWatcherCallback callback = this.callback) {
        expectEvents(callback, [])
    }

    protected AsyncConditions expectEvents(FileWatcherCallback callback = this.callback, FileEvent... events) {
        expectEvents(callback, events as List)
    }

    protected AsyncConditions expectEvents(FileWatcherCallback callback = this.callback, List<FileEvent> events) {
        return callback.expect(events)
    }

    protected static FileEvent event(FileWatcherCallback.Type type, File file, boolean mandatory = true) {
        return new FileEvent(type, file, mandatory)
    }

    protected static void createNewFile(File file) {
        LOGGER.info("> Creating $file")
        file.createNewFile()
        LOGGER.info("< Created $file")
    }

    private class TestCallback implements FileWatcherCallback {
        private AsyncConditions conditions
        private Collection<FileEvent> expectedEvents = []

        AsyncConditions expect(List<FileEvent> events) {
            events.each { event ->
                LOGGER.info("> Expecting $event")
            }
            this.conditions = new AsyncConditions()
            this.expectedEvents = new ArrayList<>(events)
            return conditions
        }

        @Override
        void pathChanged(Type type, String path) {
            def changed = new File(path)
            if (!changed.absolute) {
                throw new IllegalArgumentException("Received non-absolute changed path: " + path)
            }
            handleEvent(new FileEvent(type, changed, true))
        }

        @Override
        void reportError(Throwable ex) {
            System.err.print("Error reported from native backend:")
            ex.printStackTrace()
            uncaughtFailureOnThread << ex
        }

        private void handleEvent(FileEvent event) {
            LOGGER.info("> Received  $event")
            if (!expectedEvents.remove(event)) {
                conditions.evaluate {
                    throw new RuntimeException("Unexpected event $event")
                }
            }
            if (!expectedEvents.any { it.mandatory }) {
                conditions.evaluate {}
            }
        }
    }

    @EqualsAndHashCode(excludes = ["mandatory"])
    @SuppressWarnings("unused")
    protected static class FileEvent {
        final FileWatcherCallback.Type type
        final File file
        final boolean mandatory

        FileEvent(FileWatcherCallback.Type type, File file, boolean mandatory) {
            this.type = type
            this.file = file
            this.mandatory = mandatory
        }

        @Override
        String toString() {
            return "${mandatory ? "" : "optional "}$type $file"
        }
    }

    protected abstract void waitForChangeEventLatency()
}
