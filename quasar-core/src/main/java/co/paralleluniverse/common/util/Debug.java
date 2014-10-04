/*
 * Copyright (c) 2013-2014, Parallel Universe Software Co. All rights reserved.
 * 
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *  
 *   or (per the licensee's choosing)
 *  
 * under the terms of the GNU Lesser General Public License version 3.0
 * as published by the Free Software Foundation.
 */
package co.paralleluniverse.common.util;

import co.paralleluniverse.common.monitoring.FlightRecorder;
import co.paralleluniverse.strands.Strand;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 *
 * @author pron
 */
public class Debug {
    private static final boolean debugMode = SystemProperties.isEmptyOrTrue("co.paralleluniverse.debugMode");
    private static final String FLIGHT_RECORDER_DUMP_FILE = System.getProperty("co.paralleluniverse.flightRecorderDumpFile");
    private static final FlightRecorder flightRecorder = (debugMode && SystemProperties.isEmptyOrTrue("co.paralleluniverse.globalFlightRecorder") ? new FlightRecorder("PUNIVERSE-FLIGHT-RECORDER") : null);
    private static boolean recordStackTraces = false;
    private static final boolean assertionsEnabled;
    private static final boolean unitTest;
    private static final boolean ci;
    private static final boolean debugger;
    private static final AtomicBoolean requestShutdown = new AtomicBoolean(false);
    private static final AtomicBoolean fileDumped = new AtomicBoolean(false);

    static {
        boolean ea = false;
        assert (ea = true);
        assertionsEnabled = ea;

        boolean isUnitTest = false;
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement ste : stack) {
            if (ste.getClassName().startsWith("org.junit")
                    || ste.getClassName().startsWith("junit.framework")
                    || ste.getClassName().contains("JUnitTestClassExecuter")) {
                isUnitTest = true;
                break;
            }
        }
        unitTest = isUnitTest;

        ci = (isEnvTrue("CI") || isEnvTrue("CONTINUOUS_INTEGRATION") || isEnvTrue("TRAVIS"));
        if (debugMode) {
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    if (requestShutdown.get())
                        dumpRecorder();
                }
            });
        }

        debugger = ManagementFactory.getRuntimeMXBean().getInputArguments().toString().contains("-agentlib:jdwp");
    }

    public static boolean isDebug() {
        return debugMode;
    }

    public static boolean isCI() {
        return ci;
    }

    public static boolean isDebugger() {
        return debugger;
    }

    public static boolean isAssertionsEnabled() {
        return assertionsEnabled;
    }

    public static boolean isRecordStackTraces() {
        return recordStackTraces;
    }

    public static boolean isUnitTest() {
        return unitTest;
    }

    public static void setRecordStackTraces(boolean recordStackTraces) {
        Debug.recordStackTraces = recordStackTraces;
    }

    public static String getDumpFile() {
        return FLIGHT_RECORDER_DUMP_FILE;
    }

    public static FlightRecorder getGlobalFlightRecorder() {
        return flightRecorder;
    }

    @SuppressWarnings("CallToThreadDumpStack")
    public static void exit(int code) {
        final Strand currentStrand = Strand.currentStrand();
        if (flightRecorder != null) {
            flightRecorder.record(1, "DEBUG EXIT REQUEST ON STRAND " + currentStrand + ": " + Arrays.toString(currentStrand.getStackTrace()));
            flightRecorder.stop();
        }

        if (requestShutdown.compareAndSet(false, true)) {
            System.err.println("DEBUG EXIT REQUEST ON STRAND " + currentStrand
                    + (currentStrand.isFiber() ? " (THREAD " + Thread.currentThread() + ")" : "")
                    + ": SHUTTING DOWN THE JVM.");
            Thread.dumpStack();
            if (!isUnitTest()) // Calling System.exit() in gradle unit tests breaks gradle
                System.exit(code);
            else
                dumpRecorder();
        }
    }

    public static void record(int level, Object payload) {
        if (!isDebug())
            return;
        if (getGlobalFlightRecorder() == null)
            return;
        getGlobalFlightRecorder().record(level, payload);
    }

    public static void record(int level, Object... payload) {
        if (!isDebug())
            return;
        if (getGlobalFlightRecorder() == null)
            return;
        getGlobalFlightRecorder().record(level, payload);
    }

    public static void dumpRecorder() {
        if (isDebug()) {
            final String fileName = getDumpFile();
            if (fileName != null && !fileName.trim().equals("")) {
                if (fileDumped.compareAndSet(false, true))
                    dumpRecorder(fileName);
            } else
                System.err.println("NO ERROR LOG FILE SPECIFIED.");
        }
    }

    public static void dumpRecorder(String filename) {
        if (flightRecorder != null)
            flightRecorder.dump(filename);
    }

    public static void dumpAfter(final long millis) {
        dumpAfter(millis, FLIGHT_RECORDER_DUMP_FILE);
    }

    public static void dumpAfter(final long millis, final String filename) {
        if (!debugMode)
            return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(millis);
                    dumpRecorder(filename);
                } catch (InterruptedException e) {
                }
            }
        }, "DEBUG").start();
    }

    public interface StackTraceFilter {
        boolean filter(StackTraceElement ste);
    }
    private static final StackTraceFilter UNITTEST_FILTER = new StackTraceFilter() {
        @Override
        public boolean filter(StackTraceElement ste) {
            return !(ste.getClassName().startsWith("org.mockito")
                    || ste.getClassName().startsWith("org.junit")
                    || ste.getClassName().startsWith("org.apache.tools.ant.taskdefs.optional.junit"));
        }
    };

    public static void dumpStack() {
        dumpStack(System.out, new Exception("Stack trace"), UNITTEST_FILTER);
    }

    public static void dumpStack(PrintStream s, Throwable t) {
        dumpStack(s, t, UNITTEST_FILTER);
    }

    public static void dumpStack(PrintStream s, Throwable t, StackTraceFilter filter) {
        synchronized (s) {
            s.println(t);
            StackTraceElement[] trace = t.getStackTrace();
            for (int i = 0; i < trace.length; i++) {
                if (filter.filter(trace[i]))
                    s.println("\tat " + trace[i]);
            }

            Throwable ourCause = t.getCause();
            if (ourCause != null)
                printStackTraceAsCause(s, trace, ourCause, filter);
        }
    }

    /**
     * Print our stack trace as a cause for the specified stack trace.
     */
    private static void printStackTraceAsCause(PrintStream s, StackTraceElement[] causedTrace, Throwable t, StackTraceFilter filter) {
        // assert Thread.holdsLock(s);

        // Compute number of frames in common between this and caused
        StackTraceElement[] trace = t.getStackTrace();
        int m = trace.length - 1, n = causedTrace.length - 1;
        while (m >= 0 && n >= 0 && trace[m].equals(causedTrace[n])) {
            m--;
            n--;
        }
        int framesInCommon = trace.length - 1 - m;

        s.println("Caused by: " + t);
        for (int i = 0; i <= m; i++) {
            if (filter.filter(trace[i]))
                s.println("\tat " + trace[i]);
        }
        if (framesInCommon != 0)
            s.println("\t... " + framesInCommon + " more");

        // Recurse if we have a cause
        Throwable ourCause = t.getCause();
        if (ourCause != null)
            printStackTraceAsCause(s, trace, ourCause, filter);
    }

    private static boolean isEnvTrue(String envVar) {
        final String ev = System.getenv(envVar);
        if (ev == null)
            return false;
        try {
            return Boolean.parseBoolean(ev);
        } catch (Exception e) {
            return false;
        }
    }

    public static String getPackageVersion(String packageName) {
        try {
            Package aPackage = Package.getPackage(packageName);
            if (aPackage != null) {
                return aPackage.getImplementationVersion();
            }
        } catch (Exception e) {
        }
        return null;
    }

    public static Path getJarOfClass(String className) {
        return getJarOfClass(findClass(className));
    }

    public static Path getJarOfClass(Class<?> clazz) {
        try {
            if (clazz != null) {
                final URL resource = clazz.getClassLoader().getResource(clazz.getName().replace('.', '/') + ".class");
                if (resource != null) {
                    String p = resource.toString();
                    int idx = p.lastIndexOf('!');
                    if (idx > 0)
                        return Paths.get(new URI(p.substring(0, idx)));
                }
            }
        } catch (Exception e) {
        }
        return null;
    }

    private static Class findClass(String className) {
        try {
            return Thread.currentThread().getContextClassLoader().loadClass(className);
        } catch (ClassNotFoundException e) {
        }
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
        }
        return null;
    }

    private Debug() {
    }
}
