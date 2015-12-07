package co.paralleluniverse.fibers;

import co.paralleluniverse.common.util.ExtendedStackTrace;
import co.paralleluniverse.common.util.SystemProperties;
import co.paralleluniverse.concurrent.forkjoin.ParkableForkJoinTask;
import co.paralleluniverse.fibers.instrument.*;
import co.paralleluniverse.strands.SuspendableUtils;
import com.google.common.io.ByteStreams;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.stream.Collector;
import java.util.stream.Collectors;

final class LiveInstrumentation {
    static synchronized boolean fixup(Fiber f) {
        boolean checkInstrumentation = true;
        if (autoInstr && f != null) {
            final Stack fs = f.getStack();
            if (esw != null) {
                if (!agree(esw, fs)) {
                    // Slow path, we'll take our time to fix up things
                    checkCaps();
                    // TODO: improve perf
                    final java.util.Stack<FiberFramePush> fiberStackRebuildToDoList = new java.util.Stack<>();
                    // TODO: improve perf
                    final List<StackWalker.StackFrame> frames = esw.walk(s -> s.collect(Collectors.toList()));

                    boolean callingFiberRuntime = false;
                    boolean ok, last = false;
                    StackWalker.StackFrame upper = null;
                    FiberFramePush prevFFP = null;
                    for(final StackWalker.StackFrame sf : frames) {
                        if (isFiberRuntimeMethod(sf.getClassName(), sf.getMethodName())) {
                            // Skip marking/transforming yield frames
                            callingFiberRuntime = true;
                        } else {
                            LOG("Live lazy auto-instrumentation: " + sf.getClassName() + "#" + sf.getMethodName());
                            final Class<?> cCaller = sf.getDeclaringClass();
                            final String mnCaller = sf.getMethodName();

                            if (prevFFP != null)
                                prevFFP.setLower(sf);

                            final MethodType mtCaller;
                            final int b;
                            try {
                                mtCaller = (MethodType) getMethodType.invoke(memberName.get(sf));

                                final Executable m = lookupMethod(cCaller, mnCaller, mtCaller);
                                b = (int) bci.get(sf);
                                final CheckCallSiteFrameInstrumentationReport  report =
                                    checkCallSiteFrameInstrumentation(cCaller, m, b, upper);
                                ok = report.isOK();
                                last = report.last;

                                fixSuspendableSupers(cCaller, mnCaller, mtCaller);
                                if (!ok) {
                                    if (!report.classInstrumented || !report.methodInstrumented)
                                        suspendable(cCaller, mnCaller, mtCaller, MethodDatabase.SuspendableType.SUSPENDABLE);
                                    final String n = cCaller.getName();
                                    final InputStream is = cCaller.getResourceAsStream("/" + n.replace(".", "/") + ".class");
                                    final byte[] diskData = ByteStreams.toByteArray(is);
                                    Retransform.redefine(new ClassDefinition(cCaller, diskData)); // Instrum. will occur
                                }

                                // Get re-instrumented version before checking if method has been optimized
                                final Class<?> c1 = Class.forName(cCaller.getName());
                                final Executable m1 = lookupMethod(c1, mnCaller, mtCaller);
                                final Instrumented i = SuspendableHelper.getAnnotation(m1, Instrumented.class);
                                if (i != null && !i.methodOptimized())
                                    prevFFP = pushRebuildToDo(sf, fiberStackRebuildToDoList, callingFiberRuntime);

                                callingFiberRuntime = false;
                            } catch (final ClassNotFoundException | InvocationTargetException | IllegalAccessException | IOException e) {
                                throw new RuntimeException(e);
                            }
                        }

                        upper = sf;

                        if (last)
                            break;
                    }

                    // Rebuild stack
                    apply(fiberStackRebuildToDoList, fs);

                    // Now it should be ok
                    // assert agree(esw, fs);

                    // We're done, let's skip checks
                    checkInstrumentation = false;
                }
            }
        }

        return true /* TODO after debugging pass the following instead */ /* checkInstrumentation */;
    }

    private static boolean isFiberRuntimeMethod(String className, String methodName) {
        return
            Fiber.class.getName().equals(className) ||
            LiveInstrumentation.class.getName().equals(className) ||
            ForkJoinWorkerThread.class.getName().equals(className) ||
            ForkJoinTask.class.getName().equals(className) ||
            ParkableForkJoinTask.class.getName().equals(className) ||
            className.startsWith(SuspendableUtils.VoidSuspendableCallable.class.getName()) ||
            className.startsWith(ForkJoinPool.class.getName()) ||
            className.startsWith(FiberForkJoinScheduler.class.getName());
    }

    private static
    CheckCallSiteFrameInstrumentationReport
    checkCallSiteFrameInstrumentation(Class declaringClass, Executable m, int bci, StackWalker.StackFrame upperStackFrame) {
        // TODO: factor with corresponding (Java9-ported) Fiber::checkInstrumentation
        final String className = declaringClass.getName();
        final String methodName = m.getName();
        final CheckCallSiteFrameInstrumentationReport res = new CheckCallSiteFrameInstrumentationReport();

        if (Thread.class.getName().equals(className) && "getStackTrace".equals(methodName) ||
            ExtendedStackTrace.class.getName().equals(className) ||
            className.contains("$$Lambda$"))
            return CheckCallSiteFrameInstrumentationReport.OK_NOT_FINISHED; // Skip

        if (!className.equals(Fiber.class.getName()) && !className.startsWith(Fiber.class.getName() + '$')
            && !className.equals(Stack.class.getName()) && !SuspendableHelper.isWaiver(className, methodName)) {
            res.classInstrumented = SuspendableHelper.isInstrumented(declaringClass);
            res.methodInstrumented = SuspendableHelper.isInstrumented(m);
            // TODO: this needs to check against bcis after instrumentation, not before
            res.callSiteInstrumented = isCallSiteInstrumented(m, bci, upperStackFrame);
        } else if (Fiber.class.getName().equals(className) && "run1".equals(methodName)) {
            res.last = true;
        }
        return res;
    }

    private static boolean isCallSiteInstrumented(Executable m, int bci, StackWalker.StackFrame upperStackFrame) {
        // TODO: factor with corresponding (Java9-ported) SuspendableHelper::isCallSiteInstrumented
        if (m == null)
            return false;

        if (SuspendableHelper.isSyntheticAndNotLambda(m))
            return true;

        if (upperStackFrame != null
            // `verifySuspend` and `popMethod` calls are not suspendable call sites, not verifying them.
            && ((Fiber.class.getName().equals(upperStackFrame.getClassName())
            && "verifySuspend".equals(upperStackFrame.getMethodName())) ||
            (Stack.class.getName().equals(upperStackFrame.getClassName())
                && "popMethod".equals(upperStackFrame.getMethodName())))) {
            return true;
        } else {
            final Instrumented i = SuspendableHelper.getAnnotation(m, Instrumented.class);
            if (i != null) {
                for (int j : i.suspendableCallSitesBCI()) { // TODO: fill BCIs in instrumentor
                    if (j == bci)
                        return true;
                }
            }
        }

        return false;
    }

    private static class CheckCallSiteFrameInstrumentationReport {
        private static final CheckCallSiteFrameInstrumentationReport OK_NOT_FINISHED;

        static {
            OK_NOT_FINISHED = new CheckCallSiteFrameInstrumentationReport();
            OK_NOT_FINISHED.classInstrumented = true;
            OK_NOT_FINISHED.classInstrumented = true;
            OK_NOT_FINISHED.callSiteInstrumented = true;
        }

        private boolean classInstrumented = false;
        private boolean methodInstrumented = false;
        private boolean callSiteInstrumented = false;
        private boolean last = false;

        private boolean isOK() {
            return classInstrumented && methodInstrumented && callSiteInstrumented;
        }
    }

    private static class FiberFramePush {
        private final StackWalker.StackFrame sf;
        private final Object[] locals;
        private final Object[] operands;
        private final boolean callingYield;
        private final Executable m;

        private int numSlots = -1;
        private int entry = -1;

        private int[] suspendableCallSiteLineNumbers;

        private FiberFramePush(StackWalker.StackFrame sf, Object[] locals, Object[] operands, boolean callingYield) {
            this.sf = sf;
            this.locals = locals;
            this.operands = operands;
            this.callingYield = callingYield;
            this.m = lookupMethod(sf); // Caching it as it's used multiple times
        }

        private void setLower(StackWalker.StackFrame lower) {
            final MethodType mt;
            try {
                mt = (MethodType) getMethodType.invoke(memberName.get(lower));
            } catch (final IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
            final Member lowerM = lookupMethod(lower.getDeclaringClass(), lower.getMethodName(), mt);
            final Instrumented i = lowerM != null ? SuspendableHelper.getAnnotation(lowerM, Instrumented.class) : null;
            if (i != null) {
                suspendableCallSiteLineNumbers = i.suspendableCallSites();
                if (suspendableCallSiteLineNumbers != null) {
                    Arrays.sort(suspendableCallSiteLineNumbers);
                    // TODO: check correctness of binarySearch call
                    final int bsResPlus1 = Arrays.binarySearch(suspendableCallSiteLineNumbers, sf.getLineNumber().orElse(-1)) + 1;
                    entry = Math.abs(bsResPlus1);
                }
            }
        }

        /**
         * Live fiber stack construction
         * <br>
         * !!! Must be kept aligned with `InstrumentMethod.emitStoreState` and `Stack.pusXXX` !!!
         */
        private void apply(Stack s) {
            if (entry < 1)
                throw new RuntimeException("Can't determine call site index for " + this.toString());

            final int numArgsToPreserve = callingYield ? m.getParameterCount() : 0;

            // New frame
            s.pushMethod(entry, getNumSlots());

            // Operands and locals (in this order) slot indices
            int idxObj = 0, idxPrim = 0;
            final Map<Object, Integer> opIdxs = new HashMap<>(); // TODO: improve perf

            // Store stack operands
            for (final Object op : operands) {
                final String type = type(op);
                // TODO: check that "omitted" (new value) can't happen at runtime
                if (!isNullableType(type)) {
                    if (primitiveValueClass.isInstance(op)) {
                        storePrim(op, s, idxPrim);
                        opIdxs.put(op, idxPrim);
                        idxPrim++;
                    } else {
                        Stack.push(op, s, idxObj);
                        opIdxs.put(op, idxObj);
                        idxObj++;
                    }
                }
            }

            // Store local vars
            for (int i = Modifier.isStatic(m.getModifiers()) ? 0 : 1 /* Skip `this` TODO: check */ ; i < locals.length ; i++) {
                final Object local = locals[i];
                final String type = type(local);
                if (!isNullableType(type)) {
                    if (primitiveValueClass.isInstance(local)) {
                        storePrim(local, s, idxPrim);
                        idxPrim++;
                    } else {
                        Stack.push(local, s, idxObj);
                        idxObj++;
                    }
                }
            }

            // Restore last numArgsToPreserve operands
            for (int i = operands.length - numArgsToPreserve ; i < operands.length ; i++) {
                final Object op = operands[i];
                final String type = type(op);
                // TODO: check that "omitted" (new value) can't happen at runtime
                if (!isNullableType(type)) {
                    if (primitiveValueClass.isInstance(op)) {
                        restorePrim(op, s, opIdxs.get(op));
                    } else {
                        s.getObject(opIdxs.get(op));
                    }
                }
            }
        }

        private void storePrim(Object op, Stack s, int idx) {
            try {
                final char t = (char) primitiveType.invoke(op);
                switch (t) {
                    case 'I':
                        Stack.push((int) intValue.invoke(op), s, idx);
                        break;
                    case 'S':
                        Stack.push((int) ((short) shortValue.invoke(op)), s, idx);
                        break;
                    case 'Z':
                        Stack.push((boolean) booleanValue.invoke(op) ? 1 : 0, s, idx);
                        break;
                    case 'C':
                        Stack.push((int) ((char) charValue.invoke(op)), s, idx);
                        break;
                    case 'B':
                        Stack.push((int) ((byte) byteValue.invoke(op)), s, idx);
                        break;
                    case 'J':
                        Stack.push((long) longValue.invoke(op), s, idx);
                        break;
                    case 'F':
                        Stack.push((float) floatValue.invoke(op), s, idx);
                        break;
                    case 'D':
                        Stack.push((double) doubleValue.invoke(op), s, idx);
                        break;
                    default:
                        throw new RuntimeException("Unknown primitive operand type: " + t);
                }
            } catch (final InvocationTargetException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        private void restorePrim(Object op, Stack s, int idx) {
            final char t;
            try {
                t = (char) primitiveType.invoke(op);
            } catch (final InvocationTargetException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }

            switch (t) {
                case 'I':
                case 'S':
                case 'Z':
                case 'C':
                case 'B':
                    s.getInt(idx);
                    break;
                case 'J':
                    s.getLong(idx);
                    break;
                case 'F':
                    s.getFloat(idx);
                    break;
                case 'D':
                    s.getDouble(idx);
                    break;
                default:
                    throw new RuntimeException("Unknown primitive operand type: " + t);
            }
        }

        private int getNumSlots() {
            if (numSlots == -1) {
                int idxPrim = 0, idxObj = 0;
                for (Object operand : operands) {
                    if (primitiveValueClass.isInstance(operand))
                        idxPrim++;
                    else if (!isNullableType(type(operand)))
                        idxObj++;
                }
                numSlots = Math.max(idxObj, idxPrim);
            }
            return numSlots;
        }

        @Override
        public String toString() {
            return "FiberFramePush{" +
                "sf=" + sf +
                ", locals=" + Arrays.toString(locals) +
                ", operands=" + Arrays.toString(operands) +
                ", callingYield=" + callingYield +
                ", m=" + m +
                ", numSlots=" + numSlots +
                ", entry=" + entry +
                ", suspendableCallSiteLineNumbers=" + Arrays.toString(suspendableCallSiteLineNumbers) +
                '}';
        }
    }

    private static FiberFramePush pushRebuildToDo(StackWalker.StackFrame sf, Collection<FiberFramePush> todo, boolean callingYield) {
        try {
            final FiberFramePush ffp =
                new FiberFramePush (
                    sf,
                    (Object[]) getLocals.invoke(sf), (Object[]) getOperands.invoke(sf),
                    callingYield
                );
            todo.add(ffp);
            return ffp;
        } catch (final InvocationTargetException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    ///////////////////////////// Less interesting

    private static boolean agree(StackWalker w, Stack fs) {
        // TODO: must be _fast_, JMH it
        final long threadStackDepth = w.walk(s -> s.filter(sf -> !isFiberRuntimeMethod(sf.getClassName(), sf.getMethodName())).collect(COUNTING));
        return threadStackDepth == fs.getInstrumentedCount();
    }

    private static void apply(Collection<FiberFramePush> todo, Stack fs) {
        fs.clear();
        for (final FiberFramePush ffp : todo)
            ffp.apply(fs);
    }

    private static final boolean autoInstr;

    private static StackWalker esw = null;

    private static Class<?> primitiveValueClass, liveStackFrameClass;

    private static Method getLocals, getOperands, getMethodType, primitiveType;
    private static Method booleanValue, byteValue, charValue, shortValue, intValue, floatValue, longValue, doubleValue;

    private static Field memberName, bci;

    static {
        try {
            // TODO: change to "disableXXX" when stable
            autoInstr = SystemProperties.isEmptyOrTrue("co.paralleluniverse.fibers.instrument.enableLive");

            if (autoInstr) {
                LOG("Live lazy auto-instrumentation ENABLED");

                final Class<?> extendedOptionClass = Class.forName("java.lang.StackWalker$ExtendedOption");
                final Method ewsNI = StackWalker.class.getDeclaredMethod("newInstance", Set.class, extendedOptionClass);
                ewsNI.setAccessible(true);

                final Set<StackWalker.Option> s = new HashSet<>();
                s.add(StackWalker.Option.RETAIN_CLASS_REFERENCE);

                final Field f = extendedOptionClass.getDeclaredField("LOCALS_AND_OPERANDS");
                f.setAccessible(true);
                esw = (StackWalker) ewsNI.invoke(null, s, f.get(null));

                /////////////////////////////////////////////////
                // Get internal LiveStackFrame* class/method refs
                //////////////////////////////////////////////////
                liveStackFrameClass = Class.forName("java.lang.LiveStackFrame");
                primitiveValueClass = Class.forName("java.lang.LiveStackFrame$PrimitiveValue");

                final Class<?> stackFrameInfoClass = Class.forName("java.lang.StackFrameInfo");
                memberName = stackFrameInfoClass.getDeclaredField("memberName");
                memberName.setAccessible(true);
                bci = stackFrameInfoClass.getDeclaredField("bci");
                bci.setAccessible(true);
                getMethodType = Class.forName("java.lang.invoke.MemberName").getDeclaredMethod("getMethodType");
                getMethodType.setAccessible(true);

                getLocals = liveStackFrameClass.getDeclaredMethod("getLocals");
                getLocals.setAccessible(true);

                getOperands = liveStackFrameClass.getDeclaredMethod("getStack");
                getOperands.setAccessible(true);

                primitiveType = primitiveValueClass.getDeclaredMethod("type");
                primitiveType.setAccessible(true);

                booleanValue = primitiveValueClass.getDeclaredMethod("booleanValue");
                // booleanValue.setAccessible(true);

                byteValue = primitiveValueClass.getDeclaredMethod("byteValue");
                // byteValue.setAccessible(true);

                charValue = primitiveValueClass.getDeclaredMethod("charValue");
                // charValue.setAccessible(true);

                shortValue = primitiveValueClass.getDeclaredMethod("shortValue");
                // shortValue.setAccessible(true);

                intValue = primitiveValueClass.getDeclaredMethod("intValue");
                // intValue.setAccessible(true);

                floatValue = primitiveValueClass.getDeclaredMethod("floatValue");
                // floatValue.setAccessible(true);

                longValue = primitiveValueClass.getDeclaredMethod("longValue");
                // longValue.setAccessible(true);

                doubleValue = primitiveValueClass.getDeclaredMethod("doubleValue");
                // doubleValue.setAccessible(true);

                // getMonitors = liveStackFrameClass.getDeclaredMethod("getMonitors");
                // getMonitors.setAccessible(true);
            }
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    ///////////////////////////// Uninteresting

    private static final Collector<StackWalker.StackFrame, ?, Long> COUNTING = Collectors.counting();

    private static void fixSuspendableSupers(Class<?> cCaller, String mnCaller, MethodType mtCaller) {
        final Class<?> sup = cCaller.getSuperclass();
        if (sup != null)
            suspendableSuper(sup, mnCaller, mtCaller);

        for (final Class<?> i : cCaller.getInterfaces())
            suspendableSuper(i, mnCaller, mtCaller);
    }

    private static void suspendableSuper(Class<?> c, String mn, MethodType mt) {
        Method m;
        try {
            m = c.getDeclaredMethod(mn, mt.parameterArray());
        } catch (final NoSuchMethodException _ignored) {
            m = null;
        }
        if (m != null) {
            suspendable(c, mn, mt, MethodDatabase.SuspendableType.SUSPENDABLE_SUPER);
            fixSuspendableSupers(c, mn, mt);
        }
    }

    private static void suspendable(Class<?> c, String n, MethodType t, MethodDatabase.SuspendableType st) {
        final Class<?> s = c.getSuperclass();
        final MethodDatabase.ClassEntry ce = Retransform.getMethodDB().getOrCreateClassEntry (
            c.getName(), s != null ? s.getName() : null
        );
        final String td = t.toMethodDescriptorString();
        final MethodDatabase.SuspendableType stOld = ce.check(n, td);
        ce.set(n, td, max(stOld, st));
    }

    private static MethodDatabase.SuspendableType max(MethodDatabase.SuspendableType a, MethodDatabase.SuspendableType b) {
        if (a == null)
            return b;
        if (b == null)
            return a;
        return b.compareTo(a) > 0 ? b : a;
    }

    private static Executable lookupMethod(StackWalker.StackFrame sf) {
        try {
            return lookupMethod(sf.getDeclaringClass(), sf.getMethodName(), (MethodType) getMethodType.invoke(memberName.get(sf)));
        } catch (final InvocationTargetException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static Executable lookupMethod(Class<?> declaringClass, String methodName, MethodType t) {
        if (declaringClass == null || methodName == null || t == null)
            return null;

        for (final Method m : declaringClass.getDeclaredMethods()) {
            if (m.getName().equals(methodName) && Arrays.equals(m.getParameterTypes(), t.parameterArray())) {
                return m;
            }
        }

        return null;
    }

    private static void checkCaps() {
        if (!JavaAgent.isActive())
            throw new RuntimeException("ERROR: live instrumentation needs the Quasar agent but it is not running!");
        if (!Retransform.supportsRedefinition())
            throw new RuntimeException("ERROR: live instrumentation needs redefinition support but it's missing!");
    }

    private static String type(Object operand) {
        if (primitiveValueClass.isInstance(operand)) {
            final char c;
            try {
                c = (char) primitiveType.invoke(operand);
            } catch (final IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
            return String.valueOf(c);
        } else {
            return operand.getClass().getName();
        }
    }

    private static boolean isNullableType(String type) {
        // TODO: check if this can really happen at runtime too
        return "null".equals(type.toLowerCase());
    }

    private static void LOG(String s) {
        // TODO: plug
        System.err.println(s);
    }

    private LiveInstrumentation() {}
}
