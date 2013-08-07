/*
 * Copyright (c) 2008-2013, Matthias Mann
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 *     * Redistributions of source code must retain the above copyright notice,
 *       this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of Matthias Mann nor the names of its
 *       contributors may be used to endorse or promote products derived from
 *       this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package co.paralleluniverse.fibers.instrument;

import co.paralleluniverse.fibers.SuspendExecution;
import static co.paralleluniverse.fibers.instrument.Classes.ANNOTATION_DESC;
import co.paralleluniverse.fibers.instrument.MethodDatabase.ClassEntry;
import co.paralleluniverse.fibers.instrument.MethodDatabase.SuspendableType;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Check if a class contains suspendable methods.
 * Basicly this class checks if a method is declared to throw {@link SuspendExecution}.
 *
 * @author Matthias Mann
 */
public class CheckInstrumentationVisitor extends ClassVisitor {
    private String className;
    private ClassEntry classEntry;
    private boolean hasSuspendable;
    private boolean alreadyInstrumented;

    public CheckInstrumentationVisitor() {
        super(Opcodes.ASM4);
    }

    public boolean needsInstrumentation() {
        return hasSuspendable;
    }

    ClassEntry getClassEntry() {
        return classEntry;
    }

    public String getName() {
        return className;
    }

    public boolean isAlreadyInstrumented() {
        return alreadyInstrumented;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        this.className = name;
        this.classEntry = new ClassEntry(superName);
        classEntry.setInterfaces(interfaces);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
        if (desc.equals(InstrumentClass.ALREADY_INSTRUMENTED_NAME))
            alreadyInstrumented = true;

        return null;
    }

    @Override
    public MethodVisitor visitMethod(int access, final String name, final String desc, String signature, String[] exceptions) {
        SuspendableType suspendable = classEntry.check(name, desc);
        if (suspendable == null)
            suspendable = SuspendableClassifierService.isSuspendable(className, classEntry, name, desc, signature, exceptions);
        if (suspendable == SuspendableType.SUSPENDABLE) {
            hasSuspendable = true;
            // synchronized methods can't be made suspendable
            if ((access & Opcodes.ACC_SYNCHRONIZED) == Opcodes.ACC_SYNCHRONIZED)
                throw new UnableToInstrumentException("synchronized method", className, name, desc);
        }
        classEntry.set(name, desc, suspendable);

        if (suspendable == null) // look for @Suspendable annotation
            return new MethodVisitor(Opcodes.ASM4) {
                private boolean susp = false;

                @Override
                public AnnotationVisitor visitAnnotation(String adesc, boolean visible) {
                    if (adesc.equals(ANNOTATION_DESC))
                        susp = true;
                    return null;
                }

                @Override
                public void visitEnd() {
                    super.visitEnd();
                    classEntry.set(name, desc, susp ? SuspendableType.SUSPENDABLE : SuspendableType.NON_SUSPENDABLE);
                }
            };
        else
            return null;
    }
}
