/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package co.paralleluniverse.lwthreads.instrument;

/**
 *
 * @author pron
 */
public interface SuspendableClassifier {
    boolean isSuspendable(boolean retransform, String className, String superClassName, String[] interfaces, String methodName, String methodDesc, String methodSignature, String[] methodExceptions);
}
