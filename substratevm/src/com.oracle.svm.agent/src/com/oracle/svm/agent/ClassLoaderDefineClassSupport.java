/*
 * Copyright (c) 2019, 2019, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2020 Alibaba Group Holding Limited. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.agent;

import com.oracle.svm.jni.nativeapi.JNIEnvironment;
import static com.oracle.svm.jvmtiagentbase.Support.getIntArgument;
import static com.oracle.svm.jvmtiagentbase.Support.getObjectArgument;
import static com.oracle.svm.jvmtiagentbase.Support.getMethodFullNameAtFrame;

import com.oracle.svm.jni.nativeapi.JNIObjectHandle;

import java.io.IOException;

/**
 * Support dynamic class loading that is implemented by java.lang.ClassLoader.defineClass.
 */
public class ClassLoaderDefineClassSupport extends AbstractDynamicClassGenerationSupport {

    private static final String DEFINE_CLASS_BYTEARRAY_SIG = "java.lang.ClassLoader.defineClass(Ljava/lang/String;[BII)Ljava/lang/Class;";

    protected ClassLoaderDefineClassSupport(JNIEnvironment jni, JNIObjectHandle callerClass, String generatedClassName, TraceWriter traceWriter, NativeImageAgent agent) throws IOException {
        super(jni, callerClass, generatedClassName, traceWriter, agent);
        callerMethod = getMethodFullNameAtFrame(jni, 1);
    }

    @Override
    public boolean checkSupported() {
        return DEFINE_CLASS_BYTEARRAY_SIG.equals(callerMethod);
    }

    @Override
    public void traceReflects(Object result) throws IOException {
        // Add dynamically generated class into config file, so it will be added into the classpath
        // at build time.
        traceWriter.traceCall("reflect", "forName", null, null, null, result, generatedClassName);
        generatedClassHashCode = calculateGeneratedClassHashcode();
        // Add class' hashcode for verification
        traceWriter.traceDynamicClassChecksum(generatedClassName, generatedClassHashCode, result);
    }

    @Override
    public byte[] getClassContents() {
        assert checkSupported();
        if (values == null) {
            values = getClassContentsFromByteArray();
        }
        return values;
    }

    /**
     * Get value of argument "b" from java.lang.ClassLoader.defineClass(String name, byte[] b, int
     * off, int len, ProtectionDomain protectionDomain) "b" is the 3rd argument. because the 1st
     * argument of instance method is always "this"
     */
    @Override
    protected JNIObjectHandle getClassDefinition() {
        assert checkSupported();
        return getObjectArgument(1, 2);
    }

    /**
     * Get value of argument "len" from java.lang.ClassLoader. defineClass(String name, byte[] b,
     * int off, int len, ProtectionDomain protectionDomain) "len" is the 5th argument. because the
     * 1st argument of instance method is always "this"
     */
    @Override
    protected int getClassDefinitionBytesLength() {
        assert checkSupported();
        return getIntArgument(1, 4);
    }
}
