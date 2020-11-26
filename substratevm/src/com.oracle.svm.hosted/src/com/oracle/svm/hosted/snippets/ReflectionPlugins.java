/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.hosted.snippets;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.graalvm.compiler.api.replacements.SnippetReflectionProvider;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import org.graalvm.compiler.nodes.graphbuilderconf.InvocationPlugin;
import org.graalvm.compiler.nodes.graphbuilderconf.InvocationPlugin.Receiver;
import org.graalvm.compiler.nodes.graphbuilderconf.InvocationPlugins;
import org.graalvm.compiler.nodes.graphbuilderconf.InvocationPlugins.Registration;
import org.graalvm.compiler.options.Option;
import org.graalvm.nativeimage.ImageSingletons;

import com.oracle.graal.pointsto.infrastructure.OriginalClassProvider;
import com.oracle.svm.core.annotate.Delete;
import com.oracle.svm.core.option.HostedOptionKey;
import com.oracle.svm.hosted.ExceptionSynthesizer;
import com.oracle.svm.hosted.ImageClassLoader;
import com.oracle.svm.hosted.SVMHost;
import com.oracle.svm.hosted.c.GraalAccess;
import com.oracle.svm.hosted.phases.SubstrateClassInitializationPlugin;
import com.oracle.svm.hosted.substitute.AnnotationSubstitutionProcessor;
import com.oracle.svm.hosted.substitute.DeletedElementException;

import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

public class ReflectionPlugins {

    public static class ReflectionPluginRegistry extends IntrinsificationPluginRegistry {
        public static void setRegistryDisabledForCurrentThread(boolean registryDisabled) {
            ImageSingletons.lookup(ReflectionPluginRegistry.class).registryDisabled.set(registryDisabled);
        }

        public static boolean registryDisabledForCurrentThread() {
            return ImageSingletons.lookup(ReflectionPluginRegistry.class).registryDisabled.get();
        }
    }

    static class Options {
        @Option(help = "Enable trace logging for reflection plugins.")//
        static final HostedOptionKey<Boolean> ReflectionPluginTracing = new HostedOptionKey<>(false);
    }

    public static void registerInvocationPlugins(ImageClassLoader imageClassLoader, SnippetReflectionProvider snippetReflection, AnnotationSubstitutionProcessor annotationSubstitutions,
                    InvocationPlugins plugins, SVMHost hostVM, boolean analysis, boolean hosted) {
        /*
         * Initialize the registry if we are during analysis. If hosted is false, i.e., we are
         * analyzing the static initializers, then we always intrinsify, so don't need a registry.
         */
        if (hosted && analysis) {
            if (!ImageSingletons.contains(ReflectionPluginRegistry.class)) {
                ImageSingletons.add(ReflectionPluginRegistry.class, new ReflectionPluginRegistry());
            }
        }

        registerClassPlugins(imageClassLoader, snippetReflection, annotationSubstitutions, plugins, hostVM, analysis, hosted);
    }

    private static void registerClassPlugins(ImageClassLoader imageClassLoader, SnippetReflectionProvider snippetReflection, AnnotationSubstitutionProcessor annotationSubstitutions,
                    InvocationPlugins plugins, SVMHost hostVM, boolean analysis, boolean hosted) {
        Registration r = new Registration(plugins, Class.class);

        r.register1("forName", String.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode name) {
                return processForName(b, hostVM, targetMethod, name, ConstantNode.forBoolean(true), imageClassLoader, snippetReflection, analysis, hosted);
            }
        });

        r.register3("forName", String.class, boolean.class, ClassLoader.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode name, ValueNode initialize, ValueNode classLoader) {
                return processForName(b, hostVM, targetMethod, name, initialize, imageClassLoader, snippetReflection, analysis, hosted);
            }
        });

        r.register2("getDeclaredField", Receiver.class, String.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode name) {
                return processGetField(b, targetMethod, receiver, name, snippetReflection, true, analysis, hosted);
            }
        });

        r.register2("getField", Receiver.class, String.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode name) {
                return processGetField(b, targetMethod, receiver, name, snippetReflection, false, analysis, hosted);
            }
        });

        r.register3("getDeclaredMethod", Receiver.class, String.class, Class[].class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode name, ValueNode parameterTypes) {
                return processGetMethod(b, targetMethod, receiver, name, parameterTypes, annotationSubstitutions, snippetReflection, true, analysis, hosted);
            }
        });

        r.register3("getMethod", Receiver.class, String.class, Class[].class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode name, ValueNode parameterTypes) {
                return processGetMethod(b, targetMethod, receiver, name, parameterTypes, annotationSubstitutions, snippetReflection, false, analysis, hosted);
            }
        });

        r.register2("getDeclaredConstructor", Receiver.class, Class[].class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode parameterTypes) {
                return processGetConstructor(b, targetMethod, receiver, parameterTypes, snippetReflection, annotationSubstitutions, true, analysis, hosted);
            }
        });

        r.register2("getConstructor", Receiver.class, Class[].class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode parameterTypes) {
                return processGetConstructor(b, targetMethod, receiver, parameterTypes, snippetReflection, annotationSubstitutions, false, analysis, hosted);
            }
        });
    }

    private static boolean processForName(GraphBuilderContext b, SVMHost host, ResolvedJavaMethod targetMethod, ValueNode name, ValueNode initialize,
                    ImageClassLoader imageClassLoader, SnippetReflectionProvider snippetReflection, boolean analysis, boolean hosted) {
        if (name.isConstant() && initialize.isConstant()) {
            String className = snippetReflection.asObject(String.class, name.asJavaConstant());
            Class<?> clazz = imageClassLoader.findClass(className).get();
            if (clazz == null) {
                return throwException(b, targetMethod, analysis, hosted, className, ClassNotFoundException.class, className);
            } else {
                Class<?> intrinsic = getIntrinsic(analysis, hosted, b, clazz);
                if (intrinsic == null) {
                    return false;
                }
                ResolvedJavaType type = b.getMetaAccess().lookupJavaType(clazz);
                JavaConstant hub = b.getConstantReflection().asJavaClass(type);
                pushConstant(b, targetMethod, hub, className);
                boolean doInitialize = initialize.asJavaConstant().asInt() != 0;
                if (doInitialize && host.getClassInitializationSupport().shouldInitializeAtRuntime(clazz)) {
                    SubstrateClassInitializationPlugin.emitEnsureClassInitialized(b, hub);
                }
                return true;
            }
        }
        return false;
    }

    private static boolean processGetField(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode name,
                    SnippetReflectionProvider snippetReflection, boolean declared, boolean analysis, boolean hosted) {
        if (receiver.isConstant() && name.isConstant()) {
            Class<?> clazz = getReceiverClass(b, receiver);
            String fieldName = snippetReflection.asObject(String.class, name.asJavaConstant());

            String target = clazz.getTypeName() + "." + fieldName;
            try {
                Field field = declared ? clazz.getDeclaredField(fieldName) : clazz.getField(fieldName);
                return pushConstant(b, targetMethod, snippetReflection, analysis, hosted, field, target);
            } catch (NoSuchFieldException | LinkageError e) {
                return throwException(b, targetMethod, analysis, hosted, target, e.getClass(), e.getMessage());
            }
        }
        return false;
    }

    private static boolean processGetMethod(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode name,
                    ValueNode parameterTypes, AnnotationSubstitutionProcessor annotationSubstitutions, SnippetReflectionProvider snippetReflection, boolean declared, boolean analysis,
                    boolean hosted) {
        if (receiver.isConstant() && name.isConstant()) {
            Class<?>[] paramTypes = SubstrateGraphBuilderPlugins.extractClassArray(annotationSubstitutions, snippetReflection, parameterTypes, true);

            if (paramTypes != null) {
                Class<?> clazz = getReceiverClass(b, receiver);
                String methodName = snippetReflection.asObject(String.class, name.asJavaConstant());

                String target = clazz.getTypeName() + "." + methodName + "(" + Stream.of(paramTypes).map(Class::getTypeName).collect(Collectors.joining(", ")) + ")";
                try {
                    Method method = declared ? clazz.getDeclaredMethod(methodName, paramTypes) : clazz.getMethod(methodName, paramTypes);
                    return pushConstant(b, targetMethod, snippetReflection, analysis, hosted, method, target);
                } catch (NoSuchMethodException | LinkageError e) {
                    return throwException(b, targetMethod, analysis, hosted, target, e.getClass(), e.getMessage());
                }
            }
        }
        return false;
    }

    private static boolean processGetConstructor(GraphBuilderContext b, ResolvedJavaMethod targetMethod,
                    Receiver receiver, ValueNode parameterTypes,
                    SnippetReflectionProvider snippetReflection, AnnotationSubstitutionProcessor annotationSubstitutions, boolean declared,
                    boolean analysis, boolean hosted) {
        if (receiver.isConstant()) {
            Class<?>[] paramTypes = SubstrateGraphBuilderPlugins.extractClassArray(annotationSubstitutions, snippetReflection, parameterTypes, true);

            if (paramTypes != null) {
                Class<?> clazz = getReceiverClass(b, receiver);

                String target = clazz.getTypeName() + ".<init>(" + Stream.of(paramTypes).map(Class::getTypeName).collect(Collectors.joining(", ")) + ")";
                try {
                    Constructor<?> constructor = declared ? clazz.getDeclaredConstructor(paramTypes) : clazz.getConstructor(paramTypes);
                    return pushConstant(b, targetMethod, snippetReflection, analysis, hosted, constructor, target);
                } catch (NoSuchMethodException | LinkageError e) {
                    return throwException(b, targetMethod, analysis, hosted, target, e.getClass(), e.getMessage());
                }
            }
        }
        return false;
    }

    /**
     * Get the Class object corresponding to the receiver of the reflective call. If the class is
     * substituted we want the original class, and not the substitution. The reflective call to
     * getMethod()/getConstructor()/getField() will yield the original member, which will be
     * intrinsified, and subsequent phases are responsible for getting the right substitution
     * method/constructor/field.
     */
    private static Class<?> getReceiverClass(GraphBuilderContext b, Receiver receiver) {
        ResolvedJavaType javaType = b.getConstantReflection().asJavaType(receiver.get().asJavaConstant());
        return OriginalClassProvider.getJavaClass(GraalAccess.getOriginalSnippetReflection(), javaType);
    }

    /**
     * This method checks if the element should be intrinsified and returns the cached intrinsic
     * element if found. Caching intrinsic elements during analysis and reusing the same element
     * during compilation is important! For each call to Class.getMethod/Class.getField the JDK
     * returns a copy of the original object. Many of the reflection metadata fields are lazily
     * initialized, therefore the copy is partial. During analysis we use the
     * ReflectionMetadataFeature::replacer to ensure that the reflection metadata is eagerly
     * initialized. Therefore, we want to intrinsify the same, eagerly initialized object during
     * compilation, not a lossy copy of it.
     */
    private static <T> T getIntrinsic(boolean analysis, boolean hosted, GraphBuilderContext context, T element) {
        if (!hosted) {
            /* We are analyzing the static initializers and should always intrinsify. */
            return element;
        }
        if (analysis) {
            if (isDeleted(element, context.getMetaAccess())) {
                /*
                 * Should not intrinsify. Will fail during the reflective lookup at
                 * runtime. @Delete-ed elements are ignored by the reflection plugins regardless of
                 * the value of ReportUnsupportedElementsAtRuntime.
                 */
                return null;
            }

            /* We are during analysis, we should intrinsify and cache the intrinsified object. */
            ImageSingletons.lookup(ReflectionPluginRegistry.class).add(context.getCallingContext(), element);
        }
        /* We are during compilation, we only intrinsify if intrinsified during analysis. */
        return ImageSingletons.lookup(ReflectionPluginRegistry.class).get(context.getCallingContext());
    }

    private static <T> boolean isDeleted(T element, MetaAccessProvider metaAccess) {
        AnnotatedElement annotated = null;
        try {
            if (element instanceof Executable) {
                annotated = metaAccess.lookupJavaMethod((Executable) element);
            } else if (element instanceof Field) {
                annotated = metaAccess.lookupJavaField((Field) element);
            }
        } catch (DeletedElementException ex) {
            /*
             * If ReportUnsupportedElementsAtRuntime is *not* set looking up a @Delete-ed element
             * will result in a DeletedElementException.
             */
            return true;
        }
        /*
         * If ReportUnsupportedElementsAtRuntime is set looking up a @Delete-ed element will return
         * a substitution method that has the @Delete annotation.
         */
        if (annotated != null && annotated.isAnnotationPresent(Delete.class)) {
            return true;
        }
        return false;
    }

    private static <T> boolean pushConstant(GraphBuilderContext b, ResolvedJavaMethod targetMethod, SnippetReflectionProvider snippetReflection,
                    boolean analysis, boolean hosted, T element, String targetElement) {
        T intrinsic = getIntrinsic(analysis, hosted, b, element);
        if (intrinsic == null) {
            return false;
        }
        pushConstant(b, targetMethod, snippetReflection.forObject(intrinsic), targetElement);
        return true;
    }

    private static void pushConstant(GraphBuilderContext b, ResolvedJavaMethod reflectionMethod, JavaConstant constant, String targetElement) {
        b.addPush(JavaKind.Object, ConstantNode.forConstant(constant, b.getMetaAccess(), b.getGraph()));
        traceConstant(b.getMethod(), reflectionMethod, targetElement);
    }

    private static boolean throwException(GraphBuilderContext b, ResolvedJavaMethod reflectionMethod, boolean analysis, boolean hosted, String targetElement,
                    Class<? extends Throwable> exceptionClass, String originalMessage) {
        /* Get the exception throwing method that has a message parameter. */
        Method exceptionMethod = ExceptionSynthesizer.throwExceptionMethod(exceptionClass, String.class);
        Method intrinsic = getIntrinsic(analysis, hosted, b, exceptionMethod);
        if (intrinsic == null) {
            return false;
        }
        throwException(b, reflectionMethod, targetElement, exceptionMethod, originalMessage);
        return true;
    }

    private static void throwException(GraphBuilderContext b, ResolvedJavaMethod reflectionMethod, String targetElement, Method exceptionMethod, String originalMessage) {
        String message = originalMessage + ". This exception was synthesized during native image building from a call to " + reflectionMethod.format("%H.%n(%p)") +
                        " with constant arguments.";
        ExceptionSynthesizer.throwException(b, exceptionMethod, message);
        traceException(b.getMethod(), reflectionMethod, targetElement, exceptionMethod);
    }

    private static void traceConstant(ResolvedJavaMethod contextMethod, ResolvedJavaMethod reflectionMethod, String targetElement) {
        if (Options.ReflectionPluginTracing.getValue()) {
            System.out.println("Call to " + reflectionMethod.format("%H.%n(%p)") + " reached in " + contextMethod.format("%H.%n(%p)") +
                            " for target " + targetElement + " was reduced to a constant.");
        }
    }

    private static void traceException(ResolvedJavaMethod contextMethod, ResolvedJavaMethod reflectionMethod, String targetElement, Method exceptionMethod) {
        if (Options.ReflectionPluginTracing.getValue()) {
            String exception = exceptionMethod.getExceptionTypes()[0].getName();
            System.out.println("Call to " + reflectionMethod.format("%H.%n(%p)") + " reached in " + contextMethod.format("%H.%n(%p)") +
                            " for target " + targetElement + " was reduced to a \"throw new " + exception + "(...)\"");
        }
    }

}
