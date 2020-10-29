/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.hosted.phases;

import java.util.concurrent.ConcurrentHashMap;

import com.oracle.svm.core.util.VMError;

public class NativeImageInlineDuringParsingSupport {
    private boolean nativeImageInlineDuringParsingDisabled;

    /**
     * The map that contains all inlining decisions. During analysis we store information about
     * inlining decision so we can reuse it during compilation.
     */
    final ConcurrentHashMap<NativeImageInlineDuringParsingPlugin.CallSite, NativeImageInlineDuringParsingPlugin.InvocationResult> inlineData = new ConcurrentHashMap<>();

    public void disableNativeImageInlineDuringParsing() {
        this.nativeImageInlineDuringParsingDisabled = true;
    }

    public boolean isNativeImageInlineDuringParsingDisabled() {
        return nativeImageInlineDuringParsingDisabled;
    }

    void add(NativeImageInlineDuringParsingPlugin.CallSite callSite, NativeImageInlineDuringParsingPlugin.InvocationResult value) {
        NativeImageInlineDuringParsingPlugin.InvocationResult existingResult = inlineData.putIfAbsent(callSite, value);
        if (existingResult != null) {
            if (value instanceof NativeImageInlineDuringParsingPlugin.InvocationResultInline && existingResult instanceof NativeImageInlineDuringParsingPlugin.InvocationResultInline) {
                NativeImageInlineDuringParsingPlugin.InvocationResultInline invocationResultInline1 = (NativeImageInlineDuringParsingPlugin.InvocationResultInline) value;
                NativeImageInlineDuringParsingPlugin.InvocationResultInline invocationResultInline2 = (NativeImageInlineDuringParsingPlugin.InvocationResultInline) existingResult;
                if (!(invocationResultInline1.equals(invocationResultInline2))) {
                    throw VMError.shouldNotReachHere("New result (" + invocationResultInline1 + ") different than the previous (" +
                                    invocationResultInline2 + ")");
                }
            } else if (value instanceof NativeImageInlineDuringParsingPlugin.InvocationResultInline || existingResult instanceof NativeImageInlineDuringParsingPlugin.InvocationResultInline) {
                throw VMError.shouldNotReachHere("New result (" + value + ") different than the previous (" +
                                existingResult + ")");
            }
        }
    }
}
