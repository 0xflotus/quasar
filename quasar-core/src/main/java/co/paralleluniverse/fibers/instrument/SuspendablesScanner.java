/*
 * Quasar: lightweight threads and actors for the JVM.
 * Copyright (C) 2013, Parallel Universe Software Co. All rights reserved.
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

import co.paralleluniverse.fibers.Suspendable;
import static co.paralleluniverse.fibers.instrument.ASMUtil.*;
import static co.paralleluniverse.fibers.instrument.SimpleSuspendableClassifier.PREFIX;
import static co.paralleluniverse.fibers.instrument.SimpleSuspendableClassifier.SUSPENDABLE_SUPERS_FILE;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 *
 * @author pron
 */
public class SuspendablesScanner {
    private static final boolean USE_REFLECTION = false;
    private static final String BUILD_DIR = "build/";
    private static final String RESOURCES_DIR = "resources/main/";
    private static final String CLASSES_DIR = "/classes/main/";
    private static final String CLASSFILE_SUFFIX = ".class";

    public static void main(String args[]) throws Exception {
        String[] classPrefixes = new String[]{"co.paralleluniverse.fibers", "co.paralleluniverse.strands"};//args;
        String outputFile = BUILD_DIR + RESOURCES_DIR + PREFIX + SUSPENDABLE_SUPERS_FILE;

        run(classPrefixes, outputFile);
    }

    public static void run(String[] prefixes, String outputFile) throws Exception {
        Set<String> results = new HashSet<String>();
        for (String prefix : prefixes)
            collect(prefix, results);
        outputResults(results, outputFile);
    }

    private static Set<String> collect(String prefix, Set<String> results) throws Exception {
        prefix = prefix.trim();
        prefix = prefix.replace('.', '/');
        for (Enumeration<URL> urls = ClassLoader.getSystemResources(prefix); urls.hasMoreElements();) {
            URL url = urls.nextElement();
            File file = new File(url.getFile());
            if (file.isDirectory())
                scanClasses(file, results, ClassLoader.getSystemClassLoader());
        }
        return results;
    }

    private static void outputResults(Set<String> results, String outputFile) throws Exception {
        try (PrintStream out = getOutputStream(outputFile)) {
            List<String> sorted = new ArrayList<String>(results);
            Collections.sort(sorted);
            for (String s : sorted) {
                //            if(out != System.out)
                //                System.out.println(s);
                out.println(s);
            }
        }
    }

    private static PrintStream getOutputStream(String outputFile) throws Exception {
        System.out.println("OUTPUT: " + outputFile);
        if (outputFile != null) {
            outputFile = outputFile.trim();
            if (outputFile.isEmpty())
                outputFile = null;
        }
        if (outputFile != null) {
            File file = new File(outputFile);
            if (file.getParent() != null && !file.getParentFile().exists())
                file.getParentFile().mkdirs();
            return new PrintStream(file);
        } else
            return System.out;
    }

    private static void scanClasses(File file, Set<String> results, ClassLoader cl) throws Exception {
        if (file.isDirectory()) {
            System.out.println("Scanning dir: " + file.getPath());
            for (File f : file.listFiles())
                scanClasses(f, results, cl);
        } else {
            String className = extractClassName(file);
            if (className != null) {
                if (USE_REFLECTION)
                    scanClass(Class.forName(className), results);
                else
                    scanClass(getClassNode(className, true, cl), results, cl);
            }
        }
    }

    private static String extractClassName(File file) {
        String fileName = file.getPath();
        if (fileName.endsWith(CLASSFILE_SUFFIX) && fileName.indexOf(CLASSES_DIR) >= 0) {
            String className = fileName.substring(fileName.indexOf(CLASSES_DIR) + CLASSES_DIR.length(),
                    fileName.length() - CLASSFILE_SUFFIX.length()).replace('/', '.');
            return className;
        } else
            return null;
    }

    private static void scanClass(ClassNode cls, Set<String> results, ClassLoader cl) throws Exception {
        List<MethodNode> methods = cls.methods;
        for (MethodNode m : methods) {
            if (hasAnnotation(Suspendable.class, m))
                findSuperDeclarations(cls, cls, m, results, cl);
        }
    }

    private static void findSuperDeclarations(ClassNode cls, ClassNode declaringClass, MethodNode method, Set<String> results, ClassLoader cl) throws IOException {
        if (cls == null)
            return;

        if (!ASMUtil.equals(cls, declaringClass) && hasMethod(method, cls))
            results.add(cls.name.replace('/', '.') + '.' + method.name);

        // recursively look in superclass and interfaces
        findSuperDeclarations(getClassNode(cls.superName, true, cl), declaringClass, method, results, cl);
        for (String iface : (List<String>) cls.interfaces)
            findSuperDeclarations(getClassNode(iface, true, cl), declaringClass, method, results, cl);
    }

    private static void scanClass(Class cls, Set<String> results) throws Exception {
        Method[] methods = cls.getDeclaredMethods();
        for (Method m : methods) {
            if (m.isAnnotationPresent(Suspendable.class))
                findSuperDeclarations(cls, m, results);
        }
    }

    private static void findSuperDeclarations(Class cls, Method method, Set<String> results) {
        if (cls == null)
            return;

        if (!cls.equals(method.getDeclaringClass())) {
            try {
                cls.getDeclaredMethod(method.getName(), method.getParameterTypes());
                results.add(cls.getName() + '.' + method.getName());
            } catch (NoSuchMethodException e) {
            }
        }

        // recursively look in superclass and interfaces
        findSuperDeclarations(cls.getSuperclass(), method, results);
        for (Class iface : cls.getInterfaces())
            findSuperDeclarations(iface, method, results);
    }
}
