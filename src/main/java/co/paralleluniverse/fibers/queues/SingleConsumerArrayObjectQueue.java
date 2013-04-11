/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package co.paralleluniverse.fibers.queues;

/**
 *
 * @author pron
 */
public class SingleConsumerArrayObjectQueue<E> extends SingleConsumerArrayQueue<E> {
    private final Object[] array;

    public SingleConsumerArrayObjectQueue(int size) {
        this.array = new Object[size];
    }

    @Override
    public E value(int index) {
        return (E) array[index];
    }

    @Override
    int arrayLength() {
        return array.length;
    }

    @Override
    public void enq(E item) {
        if (item == null)
            throw new IllegalArgumentException("null values not allowed");
        set(preEnq(), item);
    }

    @SuppressWarnings("empty-statement")
    @Override
    void awaitValue(int i) {
        while (get(i) == null); // volatile read
    }
    
    @Override
    void clearValue(int index) {
        lazySet(index, null);
    }

    @Override
    void copyValue(int to, int from) {
        lazySet(to, array[from]);
    }
    
    private static final int base;
    private static final int shift;

    static {
        try {
            base = unsafe.arrayBaseOffset(Object[].class);
            int scale = unsafe.arrayIndexScale(Object[].class);
            if ((scale & (scale - 1)) != 0)
                throw new Error("data type scale not a power of two");
            shift = 31 - Integer.numberOfLeadingZeros(scale);
        } catch (Exception ex) {
            throw new Error(ex);
        }
    }

    private static long byteOffset(int i) {
        return ((long) i << shift) + base;
    }

    private void set(int i, Object value) {
        unsafe.putObjectVolatile(array, byteOffset(i), value);
    }

    private void lazySet(int i, Object value) {
        unsafe.putOrderedObject(array, byteOffset(i), value);
    }

    private Object get(int i) {
        return unsafe.getObjectVolatile(array, byteOffset(i));
    }
}
