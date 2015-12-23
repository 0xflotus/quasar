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
package co.paralleluniverse.fibers;

import co.paralleluniverse.common.util.SystemProperties;
import co.paralleluniverse.fibers.instrument.*;
import com.google.common.io.ByteStreams;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/**
 * @author circlespainter
 */
public final class LiveInstrumentation {
    private static final MethodDatabase db;

    // TODO: remove `synchronized` or change it to a lock
    static synchronized boolean fixup(Fiber fiber) {
        boolean checkInstrumentation = true;
        if (ACTIVE && fiber != null) {
            final Stack fiberStack = fiber.getStack();
            if (esw != null) {
                final long diff = agree(esw, fiberStack);
                if (diff != 0) {
                    DEBUG("\nFound mismatch between stack depth and fiber stack (" + diff + ")! Activating live lazy auto-instrumentation");
                    // Slow path, we'll take our time to fix up things
                    checkCaps();
                    // TODO: improve perf
                    final java.util.Stack<FiberFramePush> fiberStackRebuildToDoList = new java.util.Stack<>();
                    // TODO: improve perf
                    final List<StackWalker.StackFrame> fsL = esw.walk(s -> s.collect(Collectors.toList()));
                    final StackWalker.StackFrame[] fs = new StackWalker.StackFrame[fsL.size()];
                    fsL.toArray(fs);

                    boolean callingFiberRuntime = false;
                    boolean ok, last = false;
                    StackWalker.StackFrame upper = null;
                    FiberFramePush prevFFP = null;
                    for (int i = 0 ; i < fs.length ; i++) {
                        final StackWalker.StackFrame f = fs[i];
                        if (SuspendableHelper9.isFiberRuntimeStackMethod(f.getClassName())) {
                            // Skip marking/transforming yield frames
                            callingFiberRuntime = true;
                        } else {
                            final Class<?> cCaller = f.getDeclaringClass();
                            final String mnCaller = f.getMethodName();

                            if (prevFFP != null)
                                prevFFP.setLower(f);

                            final MethodType mtCaller;
                            try {
                                mtCaller = (MethodType) getMethodType.invoke(memberName.get(f));

                                DEBUG("\nLive lazy auto-instrumentation for call frame: " + f.getClassName() + "#" + f.getMethodName() + mtCaller.toMethodDescriptorString());

                                final int b = (Integer) bci.get(f);
                                final Verify.CheckFrameInstrumentationReport report =
                                    Verify.checkFrameInstrumentation(fs, i, upper);
                                ok = report.isOK();
                                last = report.last;

                                if (report.ann != null) {
                                    DEBUG("\t\tOptimized method: " + report.ann.methodOptimized());
                                    DEBUG("\t\tMethod start source line: " + report.ann.methodStartSourceLine());
                                    DEBUG("\t\tMethod end source line: " + report.ann.methodEndSourceLine());
                                    DEBUG("\t\tSuspendable call source lines: " + Arrays.toString(report.ann.methodSuspendableCallSourceLines()));
                                    DEBUG("\t\tSuspendable call signatures: " + Arrays.toString(report.ann.methodSuspendableCallSignatures()));
                                    final int[] offsets = report.ann.methodSuspendableCallOffsets();
                                    DEBUG("\t\tSuspendable call offsets (after instrumentation): " + Arrays.toString(offsets));
                                }


                                assert upper != null;
                                DEBUG("Frame instrumentation analysis report:\n" +
                                    "\tclass is " +
                                    (report.classInstrumented ? "instrumented" : "NOT instrumented") + ", \n" +
                                    "\tmethod is " +
                                    (report.methodInstrumented ? "instrumented" : "NOT instrumented") + ", \n" +
                                    "\tcall site in " +
                                    f.getFileName().orElse("<UNKNOWN SOURCE FILE>") +
                                    " at line " + f.getLineNumber() + " and bci " + b +
                                    " to " + upper.getClassName() + "#" + upper.getMethodName() +
                                    ((MethodType) getMethodType.invoke(memberName.get(f))).toMethodDescriptorString() +
                                    " is " + (report.callSiteInstrumented ? "instrumented" : "NOT instrumented"));

                                final String n = cCaller.getName();
                                if (!ok) {
                                    DEBUG("Frame instrumentation analysis found problems");
                                    DEBUG("-> Ensuring suspendable supers are correct");
                                    ensureCorrectSuspendableSupers(cCaller, mnCaller, mtCaller);
                                    if (!report.classInstrumented || !report.methodInstrumented) {
                                        DEBUG("-> Class or method not instrumented at all, marking method suspendable");
                                        suspendable(cCaller, mnCaller, mtCaller, MethodDatabase.SuspendableType.SUSPENDABLE);
                                    }
                                }
                                FrameTypesKB.askRecording(n);
                                DEBUG("Reloading class from original classloader");
                                final InputStream is = cCaller.getResourceAsStream("/" + n.replace(".", "/") + ".class");
                                DEBUG("Redefining class, Quasar instrumentation with fixed suspendable info and frame type info will occur");
                                final byte[] diskData = ByteStreams.toByteArray(is);
                                Retransform.redefine(new ClassDefinition(cCaller, diskData));

                                // The annotation will be correct now
                                final Instrumented annFixed = SuspendableHelper.getAnnotation(SuspendableHelper9.lookupMethod(cCaller, mnCaller, mtCaller), Instrumented.class);
                                if (annFixed != null && !annFixed.methodOptimized()) {
                                    DEBUG("Method is not optimized, creating a fiber stack rebuild record");
                                    prevFFP = pushRebuildToDoFull(f, upper, fiberStackRebuildToDoList, callingFiberRuntime, report.methodInstrumented);
                                } else {
                                    DEBUG("Method is optimized, creating an optimized fiber stack rebuild record");
                                    fiberStackRebuildToDoList.push(FiberFramePushOptimized.INSTANCE);
                                }

                                callingFiberRuntime = false;
                            } catch (final InvocationTargetException | IllegalAccessException | IOException e) {
                                throw new RuntimeException(e);
                            }
                        }

                        upper = f;

                        if (last)
                            break;
                    }

                    DEBUG("\nRebuilding fiber stack");
                    DEBUG("\t** Fiber stack dump before rebuild:"); // TODO: remove
                    fiberStack.dump(); // TODO: remove
                    apply(fiberStackRebuildToDoList, fiberStack);
                    DEBUG("\t** Fiber stack dump after rebuild:"); // TODO: remove
                    fiberStack.dump(); // TODO: remove

                    // We're done, let's skip checks
                    checkInstrumentation = false;
                } else {
                    DEBUG("\nInstrumentation seems OK!\n");
                    DEBUG("\t** Fiber stack dump:"); // TODO: remove
                    fiberStack.dump(); // TODO: remove

                    // We're done, let's skip checks
                    checkInstrumentation = false;
                }
            }
        }

        return checkInstrumentation;
    }

    @FunctionalInterface
    private interface FiberFramePush {
        void apply(Stack s);
        default void setLower(StackWalker.StackFrame lower) {}
    }

    private static class FiberFramePushOptimized implements FiberFramePush {
        public static final FiberFramePush INSTANCE = new FiberFramePushOptimized();

        @Override
        public void apply(Stack s) {
            s.incOptimizedCount();
        }

        private FiberFramePushOptimized() {}
    }

    private static class FiberFramePushFull implements FiberFramePush {
        private final StackWalker.StackFrame sf;
        private final MethodType mt;
        private final Executable m;
        private final Object[] locals;
        private final Object[] operands;

        private final StackWalker.StackFrame upper;
        private final Executable upperM;
        private final Object[] upperLocals;
        // private final Object[] upperOperands;

        private final boolean callingYield;
        private final boolean alreadyInstrumented;

        private int numSlots = -1;
        private int entry = 1;

        private int[] suspendableCallOffsets;

        private FiberFramePushFull(StackWalker.StackFrame sf, StackWalker.StackFrame upper, boolean callingYield, boolean alreadyInstrumented) throws InvocationTargetException, IllegalAccessException {
            this.sf = sf;
            this.mt = (MethodType) getMethodType.invoke(memberName.get(sf));
            this.m = SuspendableHelper9.lookupMethod(sf); // Caching it as it's used multiple times
            this.locals = removeNulls((Object[]) getLocals.invoke(sf));
            this.operands = removeNulls((Object[]) getOperands.invoke(sf));

            this.upper = upper;
            this.upperM = SuspendableHelper9.lookupMethod(upper);
            this.upperLocals = (Object[]) getLocals.invoke(upper);
            // this.upperOperands = (Object[]) getOperands.invoke(upper);

            this.callingYield = callingYield;
            this.alreadyInstrumented = alreadyInstrumented;
        }

        private static Object[] removeNulls(Object[] os) {
            final List<Object> l = new ArrayList<>();
            for (final Object o : os) {
                if (o != null)
                    l.add(o);
            }
            final Object[] ret = new Object[l.size()];
            l.toArray(ret);
            return ret;
        }

        public void setLower(StackWalker.StackFrame lower) {
            // this.lower = lower;
            // this.lowerM = lookupMethod(lower);
            final MethodType mt;
            try {
                mt = (MethodType) getMethodType.invoke(memberName.get(lower));
            } catch (final IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
            final Member lowerM = SuspendableHelper9.lookupMethod(lower.getDeclaringClass(), lower.getMethodName(), mt);
            final Instrumented i = lowerM != null ? SuspendableHelper.getAnnotation(lowerM, Instrumented.class) : null;
            if (i != null) {
                suspendableCallOffsets = i.methodSuspendableCallOffsets();
                if (suspendableCallOffsets != null) {
                    Arrays.sort(suspendableCallOffsets);
                    final int bsRes;
                    final int b;
                    try {
                        b = (int) bci.get(lower); // Actual live offset
                    } catch (final IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                    bsRes = Arrays.binarySearch(suspendableCallOffsets, b);
                    entry = Math.abs(bsRes >= 0 ? bsRes + 1 : bsRes); // entry is 1-based
                }
            }
        }

        /*
        // DON'T SAVE ARGS APPROACH (UNFINISHED) -> ADJUST W.R.T./SHIFT OF ARG OPERANDS TO UPPER LOCALS IN LIVE CALL

        public void apply(Stack s) {
            if (s.nextMethodEntry() == 0)
                s.isFirstInStackOrPushed();

            // New frame
            s.pushMethod(entry, getNumSlots());

            // Operands and locals (in this order) slot indices
            int idxObj = 0, idxPrim = 0;

            final String idx = Integer.toString(entry);
            final String cn = sf.getClassName();
            final String mn = sf.getMethodName();
            final String md = mt.toMethodDescriptorString();

            final org.objectweb.asm.Type[] tsOperands = FrameTypesKB.getOperandStackTypes(cn, mn, md, idx);
            final org.objectweb.asm.Type[] tsLocals = FrameTypesKB.getLocalTypes(cn, mn, md, idx);

            // ************************************************************************************************
            // The in-flight situation of the stack if very different from the static analysis perspective:
            // calls are in progress and in all non-top frames the upper part of operand the stack representing
            // arguments has been eaten already.
            // On the other hand we don't need the values there anymore: the instrumentation saves them only in
            // the case there's a suspension point before the call but this is not the case since we're already
            // in a yield (and a suspension point would be a yield anyway).
            //
            // -> Attempt 1: fake them
            // ************************************************************************************************

            final Class<?>[] upperTS = geClassesOfOperandsPassedToUpperCall(tsOperands);

            for (final Class<?> c : upperTS) {
                if (c.isPrimitive()) {
                    if (Float.class.equals(c))
                        Stack.push(0.0F, s, idxPrim++);
                    if (Double.class.equals(c))
                        Stack.push(0.0D, s, idxPrim++);
                    if (Long.class.equals(c))
                        Stack.push(0L, s, idxPrim++);
                    else
                        Stack.push(0, s, idxPrim++);
                } else {
                    Stack.push(null, s, idxObj++); // Object ref
                }
            }

            // Store stack operands
            int idxLive = 0;
            int idxTypes = upperTS.length; // Skip types of operands already passed in as call arguments
            while (idxLive < operands.length && idxTypes < tsOperands.length) {
                final Object op = operands[idxLive];
                final org.objectweb.asm.Type tOperand = tsOperands[idxTypes];
                int inc = 1;
                if (op != null) {
                    final String tID = type(op);
                    if (!isNullableType(tID)) {
                        if (primitiveValueClass.isInstance(op))
                            inc = storePrim(operands, idxLive, tOperand, s, idxPrim++);
                        else // if (!(op instanceof Stack)) // Skip stack operands
                            Stack.push(op, s, idxObj++);
                    }
                }
                idxLive += inc;
                idxTypes++;
            }

            // Cleanup some tmp mem
            FrameTypesKB.clearOperandStackTypes(cn, mn, md, idx);

            // Store local vars
            idxLive = 0;
            idxTypes = 0;
            while (idxLive < locals.length && idxTypes < tsLocals.length) {
                final Object local = locals[idxLive];
                final org.objectweb.asm.Type tLocal = tsLocals[idxTypes];
                int inc = 1;
                if (local != null) {
                    final String tID = type(local);
                    if (!isNullableType(tID)) {
                        if (primitiveValueClass.isInstance(local))
                            inc = storePrim(locals, idxLive, tLocal, s, idxPrim++);
                        else // if (!(local instanceof Stack)) { // Skip stack locals
                            Stack.push(local, s, idxObj++);
                    }
                }
                idxLive += inc;
                idxTypes++;
            }

            // Since the potential call to a yield method is in progress already (because live instrumentation is
            // called from all yield methods), we don't need to perform any special magic to preserve its args.

            // Cleanup some tmp mem
            FrameTypesKB.clearLocalTypes(cn, mn, md, idx);
        }
         */

        /**
         * Live fiber stack construction
         * <br>
         * !!! Must be kept aligned with `InstrumentMethod.emitStoreState` and `Stack.pusXXX` !!!
         */
        public void apply(Stack s) {
            if (s.nextMethodEntry() == 0)
                s.isFirstInStackOrPushed();

            final String idx = Integer.toString(entry);
            final String cn = sf.getClassName();
            final String mn = sf.getMethodName();
            final String md = mt.toMethodDescriptorString();

            final org.objectweb.asm.Type[] tsOperands = FrameTypesKB.getOperandStackTypes(cn, mn, md, idx);
            final org.objectweb.asm.Type[] tsLocals = FrameTypesKB.getLocalTypes(cn, mn, md, idx);

            // New frame
            final int slots = getNumSlots(tsOperands, tsLocals);
            s.pushMethod(entry, slots);

            // Operands and locals (in this order) slot indices
            int idxObj = 0, idxPrim = 0;

            // ************************************************************************************************
            // The in-flight situation of the stack is skewed compared to the static analysis one:
            // calls are in progress and in all non-top frames the upper part of operand the stack representing
            // arguments has been moved to param locals of the upper frame.
            //
            // On the other hand we don't need the values there anymore: the instrumentation saves them only in
            // the case there's a suspension point before the call but this is not the case since we're already
            // in a yield here (and a suspension point would be a yield anyway).
            //
            // Attempt 2: recover them from the upper frame. Note: their value might have changed but not their
            // number, and the value doesn't matter a lot as per above.
            // ************************************************************************************************

            // TODO: operands corresponding to args of reflective calls don't correspond to real args

            // Recover shifted-up stack operands
            final int shiftedUpOperandsCount = countArgsAsJVMSingleSlots(upperM);
            final List<Object> preCallOperands = new ArrayList<>();
            preCallOperands.addAll(Arrays.asList(upperLocals).subList(0, shiftedUpOperandsCount));
            preCallOperands.addAll(Arrays.asList(operands));

            // Store stack operands
            int idxTypes = 0, idxValues = 0;
            while (idxTypes < tsOperands.length) {
                final Object op = preCallOperands.get(idxValues);
                final org.objectweb.asm.Type tOperand = tsOperands[idxTypes];
                int inc = 1;
                if (op != null) {
                    final String tID = type(op);
                    if (!isNullableType(tID)) {
                        if (primitiveValueClass.isInstance(op))
                            inc = storePrim(preCallOperands, idxValues, tOperand, s, idxPrim++);
                        else // if (!(op instanceof Stack)) // Skip stack operands
                            Stack.push(op, s, idxObj++);
                    }
                }
                idxTypes++;
                idxValues += inc;
            }

            // Cleanup some tmp mem
            FrameTypesKB.clearOperandStackTypes(cn, mn, md, idx);

            // Store local vars, including args, except "this" (present in actual values but not types)
            idxTypes = 0;
            idxValues = (Modifier.isStatic(m.getModifiers()) ? 0 : 1);
            final List<Object> localsL = Arrays.asList(locals);
            while (idxTypes < tsLocals.length) {
                final Object local = locals[idxValues];
                final org.objectweb.asm.Type tLocal = tsLocals[idxTypes];
                int inc = 1;
                if (local != null) {
                    final String tID = type(local);
                    if (!isNullableType(tID)) {
                        if (primitiveValueClass.isInstance(local))
                            inc = storePrim(localsL, idxValues, tLocal, s, idxPrim++);
                        else // if (!(local instanceof Stack)) { // Skip stack locals
                            Stack.push(local, s, idxObj++);
                    }
                }
                idxTypes++;
                idxValues += inc;
            }

            // Since the potential call to a yield method is in progress already (because live instrumentation is
            // called from all yield methods), we don't need to perform any special magic to preserve its args.

            // Cleanup some tmp mem; this assumes that live instrumentation doesn't need to run again for the
            // same methods (as it shouldn't actually need to run again, if it is correct).
            FrameTypesKB.clearLocalTypes(cn, mn, md, idx);
        }

        private static int countArgsAsJVMSingleSlots(Executable m) {
            int count = Modifier.isStatic(m.getModifiers()) ? 0 : 1;
            for (final Class<?> c : m.getParameterTypes()) {
                if (Double.TYPE.equals(c) || Long.TYPE.equals(c))
                    count += 2;
                else
                    count++;
            }
            return count;
        }

        /*
        private Class<?>[] geClassesOfOperandsPassedToUpperCall(Type[] tsOperands) {
            final Class<?>[] reflectClasses = new Class<?>[] { Method.class, Object.class, Object[].class };
            final Class<?>[] invocationHandlerClasses = new Class<?>[] { Object.class, Object.class, Method.class, Object[].class };

            if (isReflectiveCall(tsOperands))
                return reflectClasses;
            if (isInvocationHandlerCall(tsOperands))
                return invocationHandlerClasses;

            final Class<?>[] types =
                new Class[countJVMArgs(upperM)];
            int i = 0;
            if (!Modifier.isStatic(upperM.getModifiers())) {
                types[0] = upperM.getDeclaringClass();
                i = 1;
            }
            for (final Class<?> t : upperM.getParameterTypes()) {
                types[i] = t;
                i++;
            }
            return types;
        }

        private boolean isReflectiveCall(Type[] tsOperands) {
            final Type mt = Type.getObjectType(Type.getInternalName(Method.class));
            final Type oat = Type.getType(Type.getInternalName(Object[].class));
            return tsOperands.length == 3 &&
                mt.equals(tsOperands[0]) &&
                !isPrimitive(tsOperands[1]) &&
                oat.equals(tsOperands[2]);
        }

        private boolean isInvocationHandlerCall(Type[] tsOperands) {
            final Type mt = Type.getObjectType(Type.getInternalName(Method.class));
            final Type oat = Type.getType(Type.getInternalName(Object[].class));
            return
                tsOperands.length == 4 &&
                    !isPrimitive(tsOperands[0]) &&
                    !isPrimitive(tsOperands[1]) &&
                    mt.equals(tsOperands[2]) &&
                    oat.equals(tsOperands[3]);
        }
        */

        private int getNumSlots(Type[] tsOperands, Type[] tsLocals) {
            if (numSlots == -1) {
                int idxPrim = 0, idxObj = 0;

                // ************************************************************************************************
                // The in-flight situation of the stack is skewed compared to the static analysis one:
                // calls are in progress and in all non-top frames the upper part of operand the stack representing
                // arguments has been moved to param locals of the upper frame.
                //
                // On the other hand we don't need the values there anymore: the instrumentation saves them only in
                // the case there's a suspension point before the call but this is not the case since we're already
                // in a yield here (and a suspension point would be a yield anyway).
                //
                // Attempt 2: recover them from the upper frame. Note: their value might have changed but not their
                // number, and the value doesn't matter a lot as per above.
                // ************************************************************************************************

                // TODO: operands that are args of reflective calls don't correspond to real target call args

                // Count stack operands
                for (final Type tOperand : tsOperands) {
                    if (isPrimitive(tOperand))
                        idxPrim++;
                    else
                        idxObj++;
                }

                // Store local vars beyond the args
                for (final Type tLocal : tsLocals) {
                    if (isPrimitive(tLocal))
                        idxPrim++;
                    else
                        idxObj++;
                }

                numSlots = Math.max(idxObj, idxPrim);
            }

            return numSlots;
        }

        private int storePrim(List<Object> objs, int objsIdx, Type t, Stack s, int stackIdx) {
            int inc = 1;
            try {
                // TODO: ask if the present hack will stay (currently all values except double-word are returned as ints)
                if (isSinglePrimitive(t)) {
                    Stack.push((int) intValue.invoke(objs.get(objsIdx)), s, stackIdx);
                } else {
                    inc = 2;
                    final int i1 = (int) intValue.invoke(objs.get(objsIdx)), i2 = (int) intValue.invoke(objs.get(objsIdx + 1));
                    if (Type.LONG_TYPE.equals(t))
                        Stack.push(twoIntsToLong(i1, i2), s, stackIdx);
                    else if (Type.DOUBLE_TYPE.equals(t))
                        Stack.push(twoIntsToDouble(i1, i2), s, stackIdx);
                }
            } catch (final InvocationTargetException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
            return inc;
        }

        private static long twoIntsToLong(int a, int b) {
            // TODO: understand & fix
/*
            System.err.println("** twoIntsToLong **");
            System.err.println("a = " + Integer.toHexString(a) + "_16 = " + Integer.toBinaryString(a) + "_2");
            System.err.println("b = " + Integer.toHexString(b) + "_16 = " + Integer.toBinaryString(b) + "_2");
            System.err.println("4l = " + Long.toHexString(4L) + "_16 = " + Long.toHexString(4L) + "_2");
            final long res = (long)a << 32 | b & 0xFFFFFFFFL;
            System.err.println("(long)a << 32 | b & 0xFFFFFFFFL: " + Long.toHexString(res) + "_16 = " + Long.toHexString(res) + "_2");
            return res;
*/
            return 10;
        }

        private static double twoIntsToDouble(int a, int b) {
            // TODO: understand & fix
/*
            System.err.println("** twoIntsToDouble **");
            double ret = Double.longBitsToDouble(twoIntsToLong(a, b));
            long retBits = Double.doubleToRawLongBits(ret);
            long oneDotFourBits = Double.doubleToRawLongBits(1.4d);
            System.err.println("1.4d = " + Long.toHexString(oneDotFourBits) + "_16 = " + Long.toHexString(oneDotFourBits) + "_2");
            System.err.println("ret = " + Long.toHexString(retBits) + "_16 = " + Long.toHexString(retBits) + "_2");
            return ret;
*/
            return 1.4D;
        }

        @Override
        public String toString() {
            return "FiberFramePush{" +
                "sf=" + sf +
                ", m=" + m +
                ", locals=" + Arrays.toString(locals) +
                ", operands=" + Arrays.toString(operands) +
                ", upper=" + upper +
                ", upperM=" + upperM +
                ", upperLocals=" + Arrays.toString(upperLocals) +
                ", callingYield=" + callingYield +
                ", numSlots=" + numSlots +
                ", entry=" + entry +
                ", suspendableCallOffsets=" + Arrays.toString(suspendableCallOffsets) +
                '}';
        }
    }

    private static boolean isPrimitive(Type t) {
        return isSinglePrimitive(t) || isDoublePrimitive(t);
    }

    private static boolean isDoublePrimitive(Type t) {
        return Type.LONG_TYPE.equals(t) || Type.DOUBLE_TYPE.equals(t);
    }

    private static boolean isSinglePrimitive(Type t) {
        return Type.INT_TYPE.equals(t) || Type.SHORT_TYPE.equals(t) || Type.BOOLEAN_TYPE.equals(t) ||
            Type.CHAR_TYPE.equals(t) || Type.BYTE_TYPE.equals(t) || Type.FLOAT_TYPE.equals(t);
    }

    private static LiveInstrumentation.FiberFramePush pushRebuildToDoFull(StackWalker.StackFrame sf, StackWalker.StackFrame upper, java.util.Stack<FiberFramePush> todo, boolean callingYield, boolean methodInstrumented) {
        try {
            final FiberFramePush ffp =
                new FiberFramePushFull (
                    sf,
                    upper,
                    callingYield,
                    methodInstrumented
                );
            todo.push(ffp);
            return ffp;
        } catch (final InvocationTargetException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    ///////////////////////////// Less interesting

    private static long agree(StackWalker w, Stack fs) {
        // TODO: must be _fast_, JMH it
        final List<StackWalker.StackFrame> l = w.walk (s -> s.collect(Collectors.toList())); // TODO: remove
        final long threadStackDepth = w.walk (s ->
            s.filter (
                sf -> {
                    final boolean ret = !SuspendableHelper9.isFiberRuntimeStackMethod(sf.getClassName());
                    return ret;
                }
            ).collect(COUNTING)
        );
        return threadStackDepth - (fs.getInstrumentedCount() + fs.getOptimizedCount());
        // return 1;
    }

    private static void apply(java.util.Stack<FiberFramePush> todo, Stack fs) {
        fs.clear();
        while (!todo.empty()) {
            final FiberFramePush ffp = todo.pop();
            ffp.apply(fs);
        }
    }

    public static final boolean ACTIVE;

    private static StackWalker esw = null;

    private static Class<?> primitiveValueClass;

    private static Method getLocals, getOperands, getMethodType, primitiveType;
    private static Method intValue;

    private static Field memberName, bci;

    static {
        try {
            // TODO: change to "disableXXX" when stable
            ACTIVE = SystemProperties.isEmptyOrTrue("co.paralleluniverse.fibers.instrument.enableLive");
            db = Retransform.getMethodDB();

            if (ACTIVE) {
                DEBUG("Live lazy auto-instrumentation ENABLED");

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
                Class<?> liveStackFrameClass = Class.forName("java.lang.LiveStackFrame");
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

                intValue = primitiveValueClass.getDeclaredMethod("intValue");
                // intValue.setAccessible(true);

                // getMonitors = liveStackFrameClass.getDeclaredMethod("getMonitors");
                // getMonitors.setAccessible(true);
            }
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    ///////////////////////////// Uninteresting

    private static final Collector<StackWalker.StackFrame, ?, Long> COUNTING = Collectors.counting();

    private static void ensureCorrectSuspendableSupers(Class<?> cCaller, String mnCaller, MethodType mtCaller) {
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
            ensureCorrectSuspendableSupers(c, mn, mt);
        }
    }

    private static void suspendable(Class<?> c, String n, MethodType t, MethodDatabase.SuspendableType st) {
        final Class<?> s = c.getSuperclass();
        final MethodDatabase.ClassEntry ce =
            db.getOrCreateClassEntry (
                c.getName().replace(".", "/"),
                s != null ? s.getName().replace(".", "/") : null
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

    private static void DEBUG(String s) {
        // TODO: plug
        System.err.println(s);
    }

    private LiveInstrumentation() {}
}
