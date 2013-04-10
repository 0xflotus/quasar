/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package co.paralleluniverse.lwthreads.instrument;

import static co.paralleluniverse.lwthreads.instrument.Classes.EXCEPTION_NAME;
import co.paralleluniverse.lwthreads.instrument.MethodDatabase.ClassEntry;
import java.util.ServiceLoader;

/**
 *
 * @author pron
 */
class SuspendableClassifierService {
    private static ServiceLoader<SuspendableClassifier> loader = ServiceLoader.load(SuspendableClassifier.class);

    public static boolean isSuspendable(boolean retransform, String className, ClassEntry classEntry, String methodName, String methodDesc, String methodSignature, String[] methodExceptions) {
        for (SuspendableClassifier sc : loader) {
            if (sc.isSuspendable(retransform, className, classEntry.superName, classEntry.interfaces, methodName, methodDesc, methodSignature, methodExceptions))
                return true;
        }
        if (checkExceptions(methodExceptions))
            return true;
        return false;
    }

    private static boolean checkExceptions(String[] exceptions) {
        if (exceptions != null) {
            for (String ex : exceptions) {
                if (ex.equals(EXCEPTION_NAME))
                    return true;
            }
        }
        return false;
    }
}
