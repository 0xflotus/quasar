/*
 * Quasar: lightweight threads and actors for the JVM.
 * Copyright (c) 2015, Parallel Universe Software Co. All rights reserved.
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
package co.paralleluniverse.fibers.instrument;

import co.paralleluniverse.common.util.*;
import co.paralleluniverse.fibers.*;
import co.paralleluniverse.strands.SuspendableCallable;
import co.paralleluniverse.strands.SuspendableUtils;

import java.lang.reflect.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author circlespainter
 */
public final class Verify {
    public static final boolean verifyInstrumentation = SystemProperties.isEmptyOrTrue("co.paralleluniverse.fibers.verifyInstrumentation");

    public static <V> void verifyTarget(SuspendableCallable<V> target) {
        if (LiveInstrumentation.ACTIVE)
            return; // Live instrumentation will take care of fixes

        Object t = target;
        if (target instanceof SuspendableUtils.VoidSuspendableCallable)
            t = ((SuspendableUtils.VoidSuspendableCallable) target).getRunnable();

        if (t.getClass().getName().contains("$$Lambda$"))
            return;

        if (verifyInstrumentation && !verifyFiberClass(t.getClass()))
            throw new VerifyInstrumentationException("Target class " + t.getClass() + " has not been instrumented.");
    }

    public static boolean verifyFiberClass(Class clazz) {
        // Live instrumentation will take care of fixes
        boolean res = LiveInstrumentation.ACTIVE || clazz.isAnnotationPresent(Instrumented.class);
        if (!res)
            res = verifyFiberClass0(clazz); // a second chance
        return res;
    }

    private static boolean verifyFiberClass0(Class clazz) {
        // Sometimes, a child class does not implement any suspendable methods AND is loaded before its superclass (that does). Test for that:
        final Class superclazz = clazz.getSuperclass();
        if (superclazz != null) {
            if (superclazz.isAnnotationPresent(Instrumented.class)) {
                // make sure the child class doesn't have any suspendable methods
                final Method[] ms = clazz.getDeclaredMethods();
                for (final Method m : ms) {
                    for (final Class et : m.getExceptionTypes()) {
                        if (et.equals(SuspendExecution.class))
                            return false;
                    }
                    if (m.isAnnotationPresent(Suspendable.class))
                        return false;
                }
                return true;
            } else
                return verifyFiberClass0(superclazz);
        } else
            return false;
    }

    public static class CheckFrameInstrumentationReport {
        private static final CheckFrameInstrumentationReport OK_NOT_FINISHED;

        static {
            OK_NOT_FINISHED = new CheckFrameInstrumentationReport();
        }

        public boolean classInstrumented = true;
        public boolean methodInstrumented = true;
        public boolean callSiteInstrumented = true;
        public boolean last = false;
        public Instrumented ann;

        public boolean isOK() {
            return classInstrumented && methodInstrumented && callSiteInstrumented;
        }

        @Override
        public String toString() {
            return "CheckFrameInstrumentationReport{" +
                "classInstrumented=" + classInstrumented +
                ", methodInstrumented=" + methodInstrumented +
                ", callSiteInstrumented=" + callSiteInstrumented +
                ", last=" + last +
                ", ann=" + ann +
                '}';
        }
    }

    public static boolean checkInstrumentation() {
        final StringBuilder stackTrace = new StringBuilder();
        boolean ok = true;

        final List<StackWalker.StackFrame> fsL = esw.walk(s -> s.collect(Collectors.toList()));
        final List<StackTraceElement> stesL = fsL.stream().map(StackWalker.StackFrame::toStackTraceElement).collect(Collectors.toList());
        final StackWalker.StackFrame[] fs = new StackWalker.StackFrame[fsL.size()];
        fsL.toArray(fs);
        final StackTraceElement[] stes = new StackTraceElement[stesL.size()];
        stesL.toArray(stes);
        for (int i = 0; i < fs.length; i++) {
            final Verify.CheckFrameInstrumentationReport report =
                checkFrameInstrumentation(fs, i, (i == 0 ? null : fs[i - 1]), ok, stes, stackTrace);
            ok = ok && report.isOK();

            if (report.last) {
                if (!ok) {
                    final String str = "Uninstrumented methods (marked '**') or call-sites (marked '!!') detected on the call stack: " + stackTrace;
                    if (Debug.isUnitTest())
                        throw new VerifyInstrumentationException(str);
                    System.err.println("WARNING: " + str);
                }

                return ok;
            }
        }

        throw new IllegalStateException("Not run through Fiber.exec(). (trace: " + Arrays.toString(new RuntimeException().getStackTrace()) + ")");
    }

    public static CheckFrameInstrumentationReport
    checkFrameInstrumentation(StackWalker.StackFrame[] sfs, int idx, StackWalker.StackFrame upperStackFrame) {
        return checkFrameInstrumentation(sfs, idx, upperStackFrame, true, null, null);
    }

    public static CheckFrameInstrumentationReport
    checkFrameInstrumentation(StackWalker.StackFrame[] fs, int idx, StackWalker.StackFrame upperStackFrame, boolean prevOk, StackTraceElement[] optStes, StringBuilder optStackTrace) {
        final StackWalker.StackFrame f = fs[idx];
        final Class<?> declaringClass = f.getDeclaringClass();
        final String className = declaringClass.getName();
        final Method m = SuspendableHelper9.lookupMethod(f);
        final Verify.CheckFrameInstrumentationReport res = new Verify.CheckFrameInstrumentationReport();
        final String methodName = m.getName();
        final int offset;
        try {
            offset = (Integer) bci.get(f);
        } catch (final IllegalAccessException e) {
            throw new RuntimeException(e);
        }

        if (Thread.class.getName().equals(className) && "getStackTrace".equals(methodName) ||
            ExtendedStackTrace.class.getName().equals(className) ||
            className.contains("$$Lambda$") ||
            (SuspendableHelper9.isUpperFiberRuntime(className) && !"run1".equals(methodName)))
            return Verify.CheckFrameInstrumentationReport.OK_NOT_FINISHED; // Skip

        if (optStackTrace != null && !prevOk)
            printTraceLine(optStackTrace, m, f.toStackTraceElement(), offset);

        if (!className.equals(Fiber.class.getName()) && !className.startsWith(Fiber.class.getName() + '$')
            && !className.equals(Stack.class.getName()) && !SuspendableHelper.isWaiver(className, methodName)) {
            res.classInstrumented = SuspendableHelper.isInstrumented(declaringClass);
            res.methodInstrumented = SuspendableHelper.isInstrumented(m);

            res.ann = SuspendableHelper.getAnnotation(m, Instrumented.class);
            res.callSiteInstrumented = res.ann != null && isCallSiteInstrumented(m, res.ann, offset, upperStackFrame);

            if (!res.isOK()) {
                if (prevOk && optStackTrace != null && optStes != null)
                    initTrace(optStackTrace, m, fs, idx);

                if (optStackTrace != null) {
                    if (!res.classInstrumented || !res.methodInstrumented)
                        optStackTrace.append(" **");
                    else if (!res.callSiteInstrumented)
                        optStackTrace
                            .append(" !! (instrumented suspendable calls at: ")
                            .append (
                                res.ann == null ?
                                    "[]" :
                                    "[" +
                                        Arrays.toString(SuspendableHelper.getSourceLines(res.ann)) + "/" +
                                        Arrays.toString(SuspendableHelper.getPostInstrumentationOffsets(res.ann)) +
                                    "]"
                            ).append(")");
                }
            }
        } else if (Fiber.class.getName().equals(className) && "run1".equals(methodName)) {
            res.last = true;
        }

        return res;
    }

    private static boolean isCallSiteInstrumented(Executable m, Instrumented ann, int offset, StackWalker.StackFrame upperStackFrame) {
        if (m == null)
            return false;

        if (SuspendableHelper.isSyntheticAndNotLambda(m))
            return true;

        if (upperStackFrame != null) {
            final String cnCallSite = upperStackFrame.getClassName();
            final String mnCallSite = upperStackFrame.getMethodName();
            if ((Fiber.class.getName().equals(cnCallSite) && "verifySuspend".equals(mnCallSite)) ||
                (Stack.class.getName().equals(upperStackFrame.getClassName()) && "popMethod".equals(mnCallSite))) {
                return true;
            }

            if (ann != null) {
                final int[] offsets = SuspendableHelper.getPostInstrumentationOffsets(ann);
                for (int offset1 : offsets) {
                    if (offset == offset1)
                        return true;
                }
            }

            return false;
        }

        return true; // Checking yield method instrumentation
        // throw new RuntimeException("Checking yield method instrumentation!");
    }

    private static void initTrace(StringBuilder stackTrace, Member m, StackWalker.StackFrame[] fs, int i) {
        for (int j = 0; j <= i; j++) {
            final StackWalker.StackFrame f = fs[j];
            if (f.getClassName().equals(Thread.class.getName()) && f.getMethodName().equals("getStackTrace"))
                continue;
            try {
                printTraceLine(stackTrace, m, f.toStackTraceElement(), (Integer) bci.get(f));
            } catch (final IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void printTraceLine(StringBuilder stackTrace, Member m, StackTraceElement ste, int offset) {
        stackTrace.append("\n\tat ").append(ste).append("(bytecode offset: ").append(offset).append(")");
        if (SuspendableHelper.isOptimized(m))
            stackTrace.append(" (optimized)");
    }

    @SuppressWarnings("null")
    public static boolean checkInstrumentation(ExtendedStackTrace st) {
        final boolean[] ok_last = new boolean[] { true, false };
        final StringBuilder stackTrace = new StringBuilder();

        final ExtendedStackTraceElement[] stes = st.get();
        for (int i = 0; i < stes.length; i++) {
            final ExtendedStackTraceElement ste = stes[i];
            checkInstrumentation(ok_last, ste, (i == 0 ? null : stes[i - 1]), stackTrace, stes, i);
            if (ok_last[1]) {
                if (!ok_last[0]) {
                    final String str = "Uninstrumented methods (marked '**') or call-sites (marked '!!') detected on the call stack: " + stackTrace;
                    if (Debug.isUnitTest())
                        throw new VerifyInstrumentationException(str);
                    System.err.println("WARNING: " + str);
                }

                return ok_last[0];
            }
        }
        throw new IllegalStateException("Not run through Fiber.exec(). (trace: " + Arrays.toString(stes) + ")");
    }

    private static void checkInstrumentation(boolean[] ok_last, ExtendedStackTraceElement ste, ExtendedStackTraceElement optUpperSte,
                                             StringBuilder optStackTrace, ExtendedStackTraceElement[] optStes, Integer optCurrStesIdx) {
        if (ste.getClassName().equals(Thread.class.getName()) && ste.getMethodName().equals("getStackTrace"))
            return;
        if (ste.getClassName().equals(ExtendedStackTrace.class.getName()))
            return;
        if (optStackTrace != null && !ok_last[0])
            printTraceLine(optStackTrace, ste);
        if (ste.getClassName().contains("$$Lambda$"))
            return;

        if (!ste.getClassName().equals(Fiber.class.getName()) && !ste.getClassName().startsWith(Fiber.class.getName() + '$')
            && !ste.getClassName().equals(Stack.class.getName()) && !SuspendableHelper.isWaiver(ste.getClassName(), ste.getMethodName())) {
            final Class<?> clazz = ste.getDeclaringClass();
            boolean classInstrumented = SuspendableHelper.isInstrumented(clazz);
            final /*Executable*/ Member m = SuspendableHelper.lookupMethod(ste);
            if (m != null) {
                boolean methodInstrumented = SuspendableHelper.isInstrumented(m);
                Pair<Boolean, int[]> callSiteInstrumented = SuspendableHelper.isCallSiteInstrumented(m, ste.getLineNumber(), optUpperSte);
                if (!classInstrumented || !methodInstrumented || !callSiteInstrumented.getFirst()) {
                    if (ok_last[0] && optStackTrace != null && optStes != null && optCurrStesIdx != null)
                        initTrace(optStackTrace, optStes, optCurrStesIdx);

                    if (optStackTrace != null) {
                        if (!classInstrumented || !methodInstrumented)
                            optStackTrace.append(" **");
                        else if (!callSiteInstrumented.getFirst())
                            optStackTrace.append(" !! (instrumented suspendable calls at: ")
                                .append(callSiteInstrumented.getSecond() == null ? "[]" : Arrays.toString(callSiteInstrumented.getSecond()))
                                .append(")");
                    }
                    ok_last[0] = false;
                }
            } else {
                if (optStackTrace != null && optStes != null && optCurrStesIdx != null) {
                    if (ok_last[0])
                        initTrace(optStackTrace, optStes, optCurrStesIdx);
                    optStackTrace.append(" **"); // Methods can only be found via source lines in @Instrumented annotations
                }

                ok_last[0] = false;
            }
        } else if (ste.getClassName().equals(Fiber.class.getName()) && ste.getMethodName().equals("run1")) {
            ok_last[1] = true;
        }
    }

    private static void initTrace(StringBuilder stackTrace, ExtendedStackTraceElement[] stes, int i) {
        for (int j = 0; j <= i; j++) {
            final ExtendedStackTraceElement ste2 = stes[j];
            if (ste2.getClassName().equals(Thread.class.getName()) && ste2.getMethodName().equals("getStackTrace"))
                continue;
            printTraceLine(stackTrace, ste2);
        }
    }

    private static void printTraceLine(StringBuilder stackTrace, ExtendedStackTraceElement ste) {
        stackTrace.append("\n\tat ").append(ste);
        final Member m = SuspendableHelper.lookupMethod(ste);
        if (SuspendableHelper.isOptimized(m))
            stackTrace.append(" (optimized)");
    }

    private static final StackWalker esw;
    private static final Field bci;

    static {
        try {
            final Class<?> stackFrameInfoClass = Class.forName("java.lang.StackFrameInfo");
            bci = stackFrameInfoClass.getDeclaredField("bci");
            bci.setAccessible(true);

            final Class<?> extendedOptionClass;
            extendedOptionClass = Class.forName("java.lang.StackWalker$ExtendedOption");

            final Method ewsNI = StackWalker.class.getDeclaredMethod("newInstance", Set.class, extendedOptionClass);
            ewsNI.setAccessible(true);

            final Set<StackWalker.Option> s = new HashSet<>();
            s.add(StackWalker.Option.RETAIN_CLASS_REFERENCE);

            final Field f = extendedOptionClass.getDeclaredField("LOCALS_AND_OPERANDS");
            f.setAccessible(true);
            esw = (StackWalker) ewsNI.invoke(null, s, f.get(null));
        } catch (final ClassNotFoundException | InvocationTargetException | IllegalAccessException | NoSuchFieldException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private Verify() {}
}
