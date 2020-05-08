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

import com.oracle.svm.core.util.JavaClassUtil;
import static com.oracle.svm.jvmtiagentbase.Support.jniFunctions;
import static com.oracle.svm.jvmtiagentbase.Support.getMethodFullNameAtFrame;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.oracle.svm.jni.nativeapi.JNIMethodId;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.nativeimage.c.type.VoidPointer;
import org.graalvm.word.WordFactory;

import com.oracle.svm.jni.nativeapi.JNIEnvironment;
import com.oracle.svm.jni.nativeapi.JNIObjectHandle;

public abstract class AbstractDynamicClassGenerationSupport {

    protected JNIEnvironment jni;
    protected JNIObjectHandle callerClass;
    protected final String generatedClassName;
    protected TraceWriter traceWriter;
    protected NativeImageAgent agent;
    protected int generatedClassHashCode = 0;
    protected byte[] values = null;

    private static Path dynclassDumpDir = null;
    private static final Path DEFAULT_DUMP = Paths.get("dynClass");
    protected String callerMethod;

    public static void initDynClassDumpDir(String dir) {
        dynclassDumpDir = Paths.get(dir);
    }

    public static AbstractDynamicClassGenerationSupport getClassLoaderDefineClassSupport(JNIEnvironment jni, JNIObjectHandle callerClass,
                    String generatedClassName, TraceWriter traceWriter, NativeImageAgent agent) throws IOException {
        return new ClassLoaderDefineClassSupport(jni, callerClass, generatedClassName, traceWriter, agent);
    }

    protected AbstractDynamicClassGenerationSupport(JNIEnvironment jni, JNIObjectHandle callerClass,
                    String generatedClassName, TraceWriter traceWriter, NativeImageAgent agent) throws IOException {
        if (dynclassDumpDir == null) {
            System.out.println("Warning: dynamic-class-dump-dir= was not set in -agentlib:native-image-agent=. Dynamically generated classes will be dumped to the default location:" +
                            DEFAULT_DUMP.toAbsolutePath().toString());
            dynclassDumpDir = DEFAULT_DUMP;
        }

        if (!Files.exists(dynclassDumpDir)) {
            Files.createDirectories(dynclassDumpDir);
        } else if (!Files.isDirectory(dynclassDumpDir)) {
            throw new IOException("File " + dynclassDumpDir + " already exists! Cannot create the same directory for class file dumping.");
        }

        this.jni = jni;
        this.callerClass = callerClass;
        // Make sure use qualified name for generatedClassName
        this.generatedClassName = generatedClassName.replace('/', '.');
        this.traceWriter = traceWriter;
        this.agent = agent;
    }

    public abstract void traceReflects(Object result) throws IOException;

    /**
     * Get class definition contents from passed in function parameter.
     *
     * @return JObject represents byte array or ByteBuffer
     */
    protected abstract JNIObjectHandle getClassDefinition();

    public abstract boolean checkSupported();

    protected abstract int getClassDefinitionBytesLength();

    public abstract byte[] getClassContents();

    protected byte[] getClassContentsFromByteArray() {
        // bytes parameter of defineClass method
        JNIObjectHandle bytes = getClassDefinition();
        // len parameter of defineClass method
        int length = getClassDefinitionBytesLength();
        // Get generated class' byte array
        CCharPointer byteArray = jniFunctions().getGetByteArrayElements().invoke(jni, bytes, WordFactory.nullPointer());
        byte[] contents = new byte[length];
        try {
            CTypeConversion.asByteBuffer(byteArray, length).get(contents);
        } finally {
            jniFunctions().getReleaseByteArrayElements().invoke(jni, bytes, byteArray, 0);
        }
        return contents;
    }

    protected byte[] getClassContentsFromDirectBuffer() {
        // DirectBuffer parameter of defineClass
        JNIObjectHandle directbuffer = getClassDefinition();

        // Get byte array from DirectBuffer
        VoidPointer baseAddr = jniFunctions().getGetDirectBufferAddress().invoke(jni, directbuffer);
        JNIMethodId limitMId = agent.handles().getMethodId(jni, agent.handles().javaNioByteBuffer, "limit", "()I", false);
        int limit = jniFunctions().getCallIntMethod().invoke(jni, directbuffer, limitMId);
        ByteBuffer classContentsAsByteBuffer = CTypeConversion.asByteBuffer(baseAddr, limit);
        byte[] contents = new byte[classContentsAsByteBuffer.limit()];
        classContentsAsByteBuffer.get(contents);
        classContentsAsByteBuffer.position(0);
        return contents;
    }

    public int calculateGeneratedClassHashcode() throws IOException {
        if (generatedClassHashCode == 0) {
            generatedClassHashCode = JavaClassUtil.getHashCodeWithoutSourceFileInfo(getClassContents());
        }
        return generatedClassHashCode;
    }

    /**
     * Save dynamically defined class to file system.
     *
     */
    public void dumpDefinedClass() throws IOException {
        if (values == null) {
            values = getClassContents();
        }

        // Get name for generated class
        String internalName = generatedClassName.replace('.', File.separatorChar);
        Path dumpFile = dynclassDumpDir.resolve(internalName + ".class");

        // Get directory from package
        Path dumpDirs = dumpFile.getParent();
        if (!Files.exists(dumpDirs)) {
            Files.createDirectories(dumpDirs);
        } else if (!Files.isDirectory(dumpDirs)) {
            throw new IOException("File " + dumpDirs + " already exists! Cannot create the same name directory for dumping class file.");
        }
        try (FileOutputStream stream = new FileOutputStream(dumpFile.toFile());) {
            stream.write(values);
        }

        // Dump stack trace for debug usage
        Path dumpTraceFile = dynclassDumpDir.resolve(internalName + ".txt");
        StringBuilder trace = getStackTrace(jni);
        try (FileOutputStream traceStream = new FileOutputStream(dumpTraceFile.toFile());) {
            traceStream.write(trace.toString().getBytes());
        }
    }

    public static StringBuilder getStackTrace(JNIEnvironment jni) {
        StringBuilder trace = new StringBuilder();
        int i = 0;
        int maxDepth = 20;
        while (i < maxDepth) {
            String methodName = getMethodFullNameAtFrame(jni, i++);
            if (methodName == null) {
                break;
            }
            trace.append("    ").append(methodName).append("\n");
        }
        if (i >= maxDepth) {
            trace.append("    ").append("...").append("\n");
        }
        return trace;
    }

}
