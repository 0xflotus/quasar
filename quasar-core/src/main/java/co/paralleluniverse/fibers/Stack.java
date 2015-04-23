package co.paralleluniverse.fibers;

import java.io.Serializable;
import java.util.Arrays;

/**
 * Internal Class - DO NOT USE! (Public so that instrumented code can access it)
 *
 * ANY CHANGE IN THIS CLASS NEEDS TO BE SYNCHRONIZED WITH {@link co.paralleluniverse.fibers.instrument.InstrumentMethod}
 *
 * @author Matthias Mann
 * @author Ron Pressler
 */
public final class Stack implements Serializable {
    /*
     * curMethodSP points to the first slot to contain data.
     * The _previous_ FRAME_RECORD_SIZE slots contain the frame record.
     * The frame record currently occupies a single long:
     *   - entry (PC)         : 20 bits
     *   - num slots          : 20 bits
     *   - prev method slots  : 20 bits
     */
    public static final int MAX_ENTRY = (1 << 14) - 1;
    public static final int MAX_SLOTS = (1 << 16) - 1;
    private static final int INITIAL_METHOD_STACK_DEPTH = 16;
    private static final int FRAME_RECORD_SIZE = 1;
    private static final long serialVersionUID = 12786283751253L;
    private int sp;
    private transient boolean shouldVerifyInstrumentation;
    private transient boolean pushed;
    private long[] dataLong;        // holds primitives on stack as well as each method's entry point as well as stack pointer
    private Object[] dataObject;    // holds refs on stack
    private final Fiber fiber;

    Stack(Fiber fiber, int stackSize) {
        if (stackSize <= 0)
            throw new IllegalArgumentException("stackSize");

        this.fiber = fiber;
        this.dataLong = new long[stackSize + (FRAME_RECORD_SIZE * INITIAL_METHOD_STACK_DEPTH)];
        this.dataObject = new Object[stackSize + (FRAME_RECORD_SIZE * INITIAL_METHOD_STACK_DEPTH)];

        resumeStack();
    }

    public static Stack getStack() {
        final Fiber currentFiber = Fiber.currentFiber();
        return currentFiber != null ? currentFiber.stack : null;
    }

    Fiber getFiber() {
        return fiber;
    }

    /**
     * called when resuming a stack
     */
    final void resumeStack() {
        sp = 0;
    }

    // for testing/benchmarking only
    void resetStack() {
        resumeStack();
    }

    /**
     * called at the beginning of a method
     *
     * @return the entry point of this method
     */
    public final int nextMethodEntry() {
        shouldVerifyInstrumentation = true;

        int idx = 0;
        int slots = 0;
        if (sp > 0) {
            slots = getNumSlots(dataLong[sp - FRAME_RECORD_SIZE]);
            idx = sp + slots;
        }
        sp = idx + FRAME_RECORD_SIZE;
        long record = dataLong[idx];
        int entry = getEntry(record);
        dataLong[idx] = setPrevNumSlots(record, slots);

        if (fiber.isRecordingLevel(2))
            fiber.record(2, "Stack", "nextMethodEntry", "%s %s %s", Thread.currentThread().getStackTrace()[2], entry, sp /*Arrays.toString(fiber.getStackTrace())*/);

        return entry;
    }

    /**
     * called when nextMethodEntry returns 0
     */
    public final boolean isFirstInStackOrPushed() {
        boolean p = pushed;
        pushed = false;

        if (sp == FRAME_RECORD_SIZE | p)
            return true;

        // not first, but nextMethodEntry returned 0: revert changes
        sp -= FRAME_RECORD_SIZE + getPrevNumSlots(dataLong[sp - FRAME_RECORD_SIZE]);

        return false;
    }

    /**
     * Called before a method is called.
     *
     * @param entry    the entry point in the method for resume
     * @param numSlots the number of required stack slots for storing the state
     */
    public final void pushMethod(int entry, int numSlots) {
        shouldVerifyInstrumentation = false;
        pushed = true;
        int idx = sp - FRAME_RECORD_SIZE;
        long record = dataLong[idx];
        record = setEntry(record, entry);
        record = setNumSlots(record, numSlots);
        dataLong[idx] = record;

        int nextMethodSP = sp + numSlots + FRAME_RECORD_SIZE;
        if (nextMethodSP > dataObject.length)
            growStack(nextMethodSP);

        if (fiber.isRecordingLevel(2))
            fiber.record(2, "Stack", "pushMethod     ", "%s %s %s", Thread.currentThread().getStackTrace()[2], entry, sp /*Arrays.toString(fiber.getStackTrace())*/);
    }

    /**
     * Called at the end of a method.
     * Undoes the effects of nextMethodEntry() and clears the dataObject[] array
     * to allow the values to be GCed.
     */
    public final void popMethod() {
        if (shouldVerifyInstrumentation) {
            Fiber.verifySuspend(fiber);
            shouldVerifyInstrumentation = false;
        }
        pushed = false;

        final int idx = sp - FRAME_RECORD_SIZE;

        final int oldSP = sp;
        final int newSP = oldSP - getPrevNumSlots(dataLong[idx]) - FRAME_RECORD_SIZE;

        for (int i = 0; i < FRAME_RECORD_SIZE; i++)
            dataLong[idx + i] = 0L;
        for (int i = newSP; i < oldSP; i++)
            dataObject[i] = null;

        sp = newSP;

        if (fiber.isRecordingLevel(2))
            fiber.record(2, "Stack", "popMethod      ", "%s %s", Thread.currentThread().getStackTrace()[2], sp /*Arrays.toString(fiber.getStackTrace())*/);
    }

    public final void postRestore() throws SuspendExecution, InterruptedException {
        fiber.onResume();
    }

    public final void preemptionPoint(int type) throws SuspendExecution {
        fiber.preemptionPoint(type);
    }

    private void growStack(int required) {
        int newSize = dataObject.length;
        do {
            newSize *= 2;
        } while (newSize < required);

        dataLong = Arrays.copyOf(dataLong, newSize);
        dataObject = Arrays.copyOf(dataObject, newSize);
    }

    void dump() {
        int m = 0;
        int k = 0;
        while (k < sp - 1) {
            final long record = dataLong[k++];
            final int slots = getNumSlots(record);

            System.err.println("\tm=" + (m++) + " entry=" + getEntry(record) + " sp=" + k + " slots=" + slots + " prevSlots=" + getPrevNumSlots(record));
            for (int i = 0; i < slots; i++, k++)
                System.err.println("\t\tsp=" + k + " long=" + dataLong[k] + " obj=" + dataObject[k]);
        }
    }

    public static void push(int value, Stack s, int idx) {
//        if (s.fiber.isRecordingLevel(3))
//            s.fiber.record(3, "Stack", "push", "%d (%d) %s", idx, s.curMethodSP + idx, value);
        s.dataLong[s.sp + idx] = value;
    }

    public static void push(float value, Stack s, int idx) {
//        if (s.fiber.isRecordingLevel(3))
//            s.fiber.record(3, "Stack", "push", "%d (%d) %s", idx, s.curMethodSP + idx, value);
        s.dataLong[s.sp + idx] = Float.floatToRawIntBits(value);
    }

    public static void push(long value, Stack s, int idx) {
//        if (s.fiber.isRecordingLevel(3))
//            s.fiber.record(3, "Stack", "push", "%d (%d) %s", idx, s.curMethodSP + idx, value);
        s.dataLong[s.sp + idx] = value;
    }

    public static void push(double value, Stack s, int idx) {
//        if (s.fiber.isRecordingLevel(3))
//            s.fiber.record(3, "Stack", "push", "%d (%d) %s", idx, s.curMethodSP + idx, value);
        s.dataLong[s.sp + idx] = Double.doubleToRawLongBits(value);
    }

    public static void push(Object value, Stack s, int idx) {
//        if (s.fiber.isRecordingLevel(3))
//            s.fiber.record(3, "Stack", "push", "%d (%d) %s", idx, s.curMethodSP + idx, value);
        s.dataObject[s.sp + idx] = value;
    }

    public final int getInt(int idx) {
        final int value = (int) dataLong[sp + idx];
//        if (fiber.isRecordingLevel(3))
//            fiber.record(3, "Stack", "getInt", "%d (%d) %s", idx, curMethodSP + idx, value);
        return value;
    }

    public final float getFloat(int idx) {
        final float value = Float.intBitsToFloat((int) dataLong[sp + idx]);
//        if (fiber.isRecordingLevel(3))
//            fiber.record(3, "Stack", "getFloat", "%d (%d) %s", idx, curMethodSP + idx, value);
        return value;
    }

    public final long getLong(int idx) {
        final long value = dataLong[sp + idx];
//        if (fiber.isRecordingLevel(3))
//            fiber.record(3, "Stack", "getLong", "%d (%d) %s", idx, curMethodSP + idx, value);
        return value;
    }

    public final double getDouble(int idx) {
        final double value = Double.longBitsToDouble(dataLong[sp + idx]);
//        if (fiber.isRecordingLevel(3))
//            fiber.record(3, "Stack", "getDouble", "%d (%d) %s", idx, curMethodSP + idx, value);
        return value;
    }

    public final Object getObject(int idx) {
        final Object value = dataObject[sp + idx];
//        if (fiber.isRecordingLevel(3))
//            fiber.record(3, "Stack", "getObject", "%d (%d) %s", idx, curMethodSP + idx, value);
        return value;
    }

    ///////////////////////////////////////////////////////////////
    private long setEntry(long record, int entry) {
        return setBits(record, 0, 14, entry);
    }

    private int getEntry(long record) {
        return (int) getSignedBits(record, 0, 14);
    }

    private long setNumSlots(long record, int numSlots) {
        return setBits(record, 14, 16, numSlots);
    }

    private int getNumSlots(long record) {
        return (int) getSignedBits(record, 14, 16);
    }

    private long setPrevNumSlots(long record, int numSlots) {
        return setBits(record, 30, 16, numSlots);
    }

    private int getPrevNumSlots(long record) {
        return (int) getSignedBits(record, 30, 16);
    }
    ///////////////////////////////////////////////////////////////
    private static final long MASK_FULL = 0xffffffffffffffffL;

    private static long getSignedBits(long word, int offset, int length) {
        long mask = (0xffffffffffffffffL >>> (64 - length));
        long xx = (word & (mask << (64 - offset - length))) >>> (64 - offset - length);
        return (xx << (32 - length)) >> (32 - length); // set sign
    }

    private static long getUnsignedBits(long word, int offset, int length) {
        long mask = (MASK_FULL >>> (64 - length));
        return (word & (mask << (64 - offset - length))) >>> (64 - offset - length);
    }

    private static long setBits(long word, int offset, int length, long value) {
        long mask = (MASK_FULL >>> (64 - length)); // create an all 1 mask of size length in least significant bits
        word = word & ~(mask << (64 - offset - length)); // clears bits in our region [offset, offset+length)
        value = value & mask;
        word = word | (value << (64 - offset - length));
        return word;
    }

    private static boolean getBit(long word, int offset) {
        return (getUnsignedBits(word, offset, 1) != 0);
    }

    private static long setBit(long word, int offset, boolean value) {
        return setBits(word, offset, 1, value ? 1 : 0);
    }
}
