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
package com.oracle.svm.core.util;

import com.sun.org.apache.bcel.internal.classfile.Attribute;
import com.sun.org.apache.bcel.internal.classfile.ClassParser;
import com.sun.org.apache.bcel.internal.classfile.ConstantUtf8;
import com.sun.org.apache.bcel.internal.classfile.JavaClass;
import com.sun.org.apache.bcel.internal.classfile.SourceFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class JavaClassUtil {

    private static final String CONSTANT_PLACE_HOLDER = "CONSTANT_PLACE_HOLDER";

    /**
     * Get hashcode of the class for verification usage. But the name of SourceFile attribute is set
     * to empty, because it might get changed from run to run and it's not really used in the class.
     *
     * @return byte array hashcode without SourceFile name
     * @throws IOException
     */
    public static int getHashCodeWithoutSourceFileInfo(byte[] classDefinition) throws IOException {
        ClassParser cp = new ClassParser(new ByteArrayInputStream(classDefinition), "");
        JavaClass jc = cp.parse();
        // Set SourceFile name's indexed utf8 value to empty
        List<Attribute> sourceFileAttr = Arrays.stream(jc.getAttributes()).filter(attr -> (attr instanceof SourceFile)).collect(Collectors.toList());
        // There should be exactly one SourceFile attribute
        if (sourceFileAttr.size() == 1) {
            SourceFile sourFile = (SourceFile) sourceFileAttr.get(0);
            int nameIndex = sourFile.getSourceFileIndex();
            String originalSourceFile = ((ConstantUtf8) (jc.getConstantPool().getConstant(nameIndex))).getBytes();
            if (originalSourceFile != null && originalSourceFile.length() > 0) {
                jc.getConstantPool().setConstant(nameIndex, new ConstantUtf8(CONSTANT_PLACE_HOLDER));
                return Arrays.hashCode(jc.getBytes());
            }
        }
        return Arrays.hashCode(classDefinition);
    }

    public static String getClassName(byte[] classDefinition) throws IOException {
        ClassParser cp = new ClassParser(new ByteArrayInputStream(classDefinition), "");
        JavaClass jc = cp.parse();
        return jc.getClassName();
    }

}
