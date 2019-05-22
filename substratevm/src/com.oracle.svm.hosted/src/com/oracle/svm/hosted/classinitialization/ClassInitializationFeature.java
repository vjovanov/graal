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
package com.oracle.svm.hosted.classinitialization;

import static com.oracle.svm.hosted.classinitialization.InitKind.BUILD_TIME;
import static com.oracle.svm.hosted.classinitialization.InitKind.RERUN;
import static com.oracle.svm.hosted.classinitialization.InitKind.RUN_TIME;
import static com.oracle.svm.hosted.classinitialization.InitKind.SEPARATOR;

import java.lang.reflect.Modifier;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.graalvm.collections.Pair;
import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.options.OptionType;
import org.graalvm.nativeimage.hosted.Feature;

import com.oracle.graal.pointsto.constraints.UnsupportedFeatureException;
import com.oracle.graal.pointsto.meta.AnalysisMetaAccess;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.meta.AnalysisUniverse;
import com.oracle.graal.pointsto.reports.ReportUtils;
import com.oracle.graal.pointsto.util.Timer;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.annotate.AutomaticFeature;
import com.oracle.svm.core.hub.ClassInitializationInfo;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.option.APIOption;
import com.oracle.svm.core.option.HostedOptionKey;
import com.oracle.svm.core.option.SubstrateOptionsParser;
import com.oracle.svm.core.util.UserError;
import com.oracle.svm.hosted.FeatureImpl;
import com.oracle.svm.hosted.analysis.Inflation;
import com.oracle.svm.hosted.meta.MethodPointer;
import com.oracle.svm.hosted.phases.SubstrateClassInitializationPlugin;

import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

@AutomaticFeature
public class ClassInitializationFeature implements Feature {

    private ClassInitializationSupport classInitializationSupport;
    private AnalysisMethod ensureInitializedMethod;
    private AnalysisUniverse universe;
    private AnalysisMetaAccess metaAccess;

    public static class Options {

        private static class InitializationValueTransformer implements Function<Object, Object> {
            private final String val;

            InitializationValueTransformer(String val) {
                this.val = val;
            }

            @Override
            public Object apply(Object o) {
                String[] elements = o.toString().split(",");
                if (elements.length == 0) {
                    return SEPARATOR + val;
                }
                String[] results = new String[elements.length];
                for (int i = 0; i < elements.length; i++) {
                    results[i] = elements[i] + SEPARATOR + val;
                }
                return String.join(",", results);
            }
        }

        private static class InitializationValueDelay extends InitializationValueTransformer {
            InitializationValueDelay() {
                super(RUN_TIME.name().toLowerCase());
            }
        }

        private static class InitializationValueRerun extends InitializationValueTransformer {
            InitializationValueRerun() {
                super(RERUN.name().toLowerCase());
            }
        }

        private static class InitializationValueEager extends InitializationValueTransformer {
            InitializationValueEager() {
                super(BUILD_TIME.name().toLowerCase());
            }
        }

        @APIOption(name = "initialize-at-run-time", valueTransformer = InitializationValueDelay.class, defaultValue = "", //
                        customHelp = "A comma-separated list of packages and classes (and implicitly all of their subclasses) that must be initialized at runtime and not during image building. An empty string is currently not supported.")//
        @APIOption(name = "initialize-at-build-time", valueTransformer = InitializationValueEager.class, defaultValue = "", //
                        customHelp = "A comma-separated list of packages and classes (and implicitly all of their superclasses) that are initialized during image generation. An empty string designates all packages.")//
        @APIOption(name = "delay-class-initialization-to-runtime", valueTransformer = InitializationValueDelay.class, deprecated = "Use --initialize-at-run-time.", //
                        defaultValue = "", customHelp = "A comma-separated list of classes (and implicitly all of their subclasses) that are initialized at runtime and not during image building")//
        @APIOption(name = "rerun-class-initialization-at-runtime", valueTransformer = InitializationValueRerun.class, //
                        deprecated = "Currently there is no replacement for this option. Try using --initialize-at-run-time or use the non-API option -H:ClassInitialization directly.", //
                        defaultValue = "", customHelp = "A comma-separated list of classes (and implicitly all of their subclasses) that are initialized both at runtime and during image building") //
        @Option(help = "A comma-separated list of classes appended with their initialization strategy (':build_time', ':rerun', or ':run_time')", type = OptionType.User)//
        public static final HostedOptionKey<String[]> ClassInitialization = new HostedOptionKey<>(new String[0]);

        @Option(help = "Prints class initialization info for all classes detected by analysis.", type = OptionType.Debug)//
        public static final HostedOptionKey<Boolean> PrintClassInitialization = new HostedOptionKey<>(false);
    }

    public static void processClassInitializationOptions(ClassInitializationSupport initializationSupport) {
        initializeJDKClasses(initializationSupport);
        initializeSVMClasses(initializationSupport);
        String[] initializationInfo = Options.ClassInitialization.getValue();
        for (String infos : initializationInfo) {
            for (String info : infos.split(",")) {
                boolean noMatches = Arrays.stream(InitKind.values()).noneMatch(v -> info.endsWith(v.suffix()));
                if (noMatches) {
                    throw UserError.abort(
                                    "Element in class initialization configuration must end in " + RUN_TIME.suffix() + ", " + RERUN.suffix() + ", or " + BUILD_TIME.suffix() + ". Found: " + info);
                }

                Pair<String, InitKind> elementType = InitKind.strip(info);
                elementType.getRight().stringConsumer(initializationSupport).accept(elementType.getLeft());
            }
        }
    }

    private static void initializeSVMClasses(ClassInitializationSupport initializationSupport) {
        initializationSupport.initializeAtBuildTime("jdk.vm.ci", "SVM classes are always initialized at build time");
        initializationSupport.initializeAtBuildTime("org.graalvm.collections", "SVM classes are always initialized at build time");
        initializationSupport.initializeAtBuildTime("org.graalvm.compiler", "SVM classes are always initialized at build time");
        initializationSupport.initializeAtBuildTime("org.graalvm.word", "SVM classes are always initialized at build time");
        initializationSupport.initializeAtBuildTime("org.graalvm.nativeimage", "SVM classes are always initialized at build time");
        initializationSupport.initializeAtBuildTime("org.graalvm.util", "SVM classes are always initialized at build time");
        initializationSupport.initializeAtBuildTime("com.oracle.svm", "SVM classes are always initialized at build time");
        initializationSupport.initializeAtBuildTime("com.oracle.graal", "SVM classes are always initialized at build time");
    }

    private static void initializeJDKClasses(ClassInitializationSupport initializationSupport) {
        initializationSupport.initializeAtBuildTime("apple.security", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("org.jcp.xml", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("jdk.net", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("com.sun.xml", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("com.sun.beans", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("com.sun.crypto", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("com.sun.naming", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("com.sun.management", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("com.sun.proxy", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("com.sun.jarsigner", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("com.sun.java.accessibility", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("com.sun.javadoc", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("com.sun.jdi", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("com.sun.net", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("com.sun.nio", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("com.sun.security", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("com.sun.source", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("com.sun.tools", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("sun.net", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("sun.nio", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("sun.misc", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("sun.util", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("sun.reflect", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("sun.security", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("sun.text", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("java.applet", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("java.awt", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("java.beans", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("java.io", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("java.lang", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("java.math", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("java.net", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("java.nio", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("java.rmi", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("java.security", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("java.sql", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("java.text", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("java.time", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("java.util", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("javax.accessibility", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("javax.activation", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("javax.activity", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("javax.annotation", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("javax.crypto", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("javax.imageio", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("javax.jws", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("javax.lang.model", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("javax.management", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("javax.naming", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("javax.net", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("javax.print", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("javax.rmi", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("javax.script", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("javax.security", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("javax.validation", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("javax.sound.midi", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("javax.sound.sampled", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("javax.sql", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("javax.swing", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("javax.tools", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("javax.transaction", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("javax.xml", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("org.ietf.jgss", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("org.omg", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("org.w3c.dom", "JDK is initialized at build time");
        initializationSupport.initializeAtBuildTime("org.xml.sax", "JDK is initialized at build time");
    }

    @Override
    public void duringSetup(DuringSetupAccess a) {
        FeatureImpl.DuringSetupAccessImpl access = (FeatureImpl.DuringSetupAccessImpl) a;
        classInitializationSupport = access.getHostVM().getClassInitializationSupport();
        classInitializationSupport.setUnsupportedFeatures(access.getBigBang().getUnsupportedFeatures());
        access.registerObjectReplacer(this::checkImageHeapInstance);
        universe = ((FeatureImpl.DuringSetupAccessImpl) a).getBigBang().getUniverse();
        metaAccess = ((FeatureImpl.DuringSetupAccessImpl) a).getBigBang().getMetaAccess();
    }

    private Object checkImageHeapInstance(Object obj) {
        /*
         * Note that computeInitKind also memoizes the class as InitKind.BUILD_TIME, which means
         * that the user cannot later manually register it as RERUN or RUN_TIME.
         */
        if (obj != null && classInitializationSupport.shouldInitializeAtRuntime(obj.getClass())) {
            throw new UnsupportedFeatureException("No instances are allowed in the image heap for a class that is initialized or reinitialized at image runtime: " + obj.getClass().getTypeName() +
                            ". Try marking this class for build-time initialization with " +
                            SubstrateOptionsParser.commandArgument(ClassInitializationFeature.Options.ClassInitialization, obj.getClass().getTypeName(), "initialize-at-build-time"));
        }
        return obj;
    }

    @Override
    public void duringAnalysis(DuringAnalysisAccess a) {
        FeatureImpl.DuringAnalysisAccessImpl access = (FeatureImpl.DuringAnalysisAccessImpl) a;

        /*
         * Check early and often during static analysis if any class that must not have been
         * initialized during image building got initialized. We want to fail as early as possible,
         * even though we cannot pinpoint the exact time and reason why initialization happened.
         */
        classInitializationSupport.checkDelayedInitialization();

        for (AnalysisType type : access.getUniverse().getTypes()) {
            if (type.isInTypeCheck() || type.isInstantiated()) {
                DynamicHub hub = access.getHostVM().dynamicHub(type);
                if (hub.getClassInitializationInfo() == null) {
                    buildClassInitializationInfo(access, type, hub);
                    access.requireAnalysisIteration();
                }
            }
        }
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        ensureInitializedMethod = ((FeatureImpl.BeforeAnalysisAccessImpl) access).getBigBang().getMetaAccess().lookupJavaMethod(SubstrateClassInitializationPlugin.ENSURE_INITIALIZED_METHOD);
    }

    /**
     * Initializes classes that can be proven safe and prints class initialization statistics.
     */
    @Override
    @SuppressWarnings("try")
    public void beforeCompilation(BeforeCompilationAccess access) {
        String imageName = ((FeatureImpl.BeforeCompilationAccessImpl) access).getUniverse().getBigBang().getHostVM().getImageName();
        try (Timer.StopTimer ignored = new Timer(imageName, "(clinit)").start()) {
            classInitializationSupport.setUnsupportedFeatures(null);

            String path = Paths.get(Paths.get(SubstrateOptions.Path.getValue()).toString(), "reports").toAbsolutePath().toString();
            assert ensureInitializedMethod != null;
            assert classInitializationSupport.checkDelayedInitialization();

            TypeInitializerGraph initGraph = new TypeInitializerGraph(universe, ensureInitializedMethod);
            initGraph.computeInitializerSafety();

            Set<AnalysisType> provenSafe = initializeSafeDelayedClasses(initGraph);

            if (Options.PrintClassInitialization.getValue()) {
                List<ClassOrPackageConfig> allConfigs = classInitializationSupport.getClassInitializationConfiguration();
                allConfigs.sort(Comparator.comparing(ClassOrPackageConfig::getName));
                ReportUtils.report("initializer configuration", path, "initializer_configuration", "txt", writer -> {
                    for (ClassOrPackageConfig config : allConfigs) {
                        writer.append(config.getName()).append(" -> ").append(config.getKind().toString()).append(" reasons: ")
                                        .append(String.join(" and ", config.getReasons())).append(System.lineSeparator());
                    }
                });
                reportSafeTypeInitiazliation(universe, initGraph, path, provenSafe);
                reportMethodInitializationInfo(path);
            }
        }

    }

    private static void reportSafeTypeInitiazliation(AnalysisUniverse universe, TypeInitializerGraph initGraph, String path, Set<AnalysisType> provenSafe) {
        ReportUtils.report("initializer dependencies", path, "initializer_dependencies", "dot", writer -> {
            writer.println("digraph initializer_dependencies {");
            universe.getTypes().stream()
                            .filter(ClassInitializationFeature::isRelevantForPrinting)
                            .forEach(t -> writer.println(quote(t.toClassName()) + "[fillcolor=" + (initGraph.isUnsafe(t) ? "red" : "green") + "]"));
            universe.getTypes().stream()
                            .filter(ClassInitializationFeature::isRelevantForPrinting)
                            .forEach(t -> initGraph.getDependencies(t)
                                            .forEach(t1 -> writer.println(quote(t.toClassName()) + " -> " + quote(t1.toClassName()))));
            writer.println("}");
        });

        ReportUtils.report(provenSafe.size() + " classes that are considered as safe for build-time initialization", path, "safe_classes", "txt",
                        printWriter -> provenSafe.forEach(t -> printWriter.println(t.toClassName())));
    }

    /**
     * Prints a file for every type of class initialization. Each file contains a list of classes
     * that belong to it.
     */
    private void reportMethodInitializationInfo(String path) {
        for (InitKind kind : InitKind.values()) {
            Set<Class<?>> classes = classInitializationSupport.classesWithKind(kind);
            ReportUtils.report(classes.size() + " classes of type " + kind, path, kind.toString().toLowerCase() + "_classes", "txt",
                            writer -> classes.stream()
                                            .map(Class::getTypeName)
                                            .sorted()
                                            .forEach(writer::println));
        }
    }

    private static boolean isRelevantForPrinting(AnalysisType type) {
        return !type.isPrimitive() && !type.isArray() && type.isInTypeCheck();
    }

    private static String quote(String className) {
        return "\"" + className + "\"";
    }

    /**
     * Initializes all classes that are considered delayed by the system. Classes specified by the
     * user will not be delayed.
     */
    private Set<AnalysisType> initializeSafeDelayedClasses(TypeInitializerGraph initGraph) {
        Set<AnalysisType> provenSafe = new HashSet<>();
        classInitializationSupport.classesWithKind(RUN_TIME).stream()
                        .filter(t -> metaAccess.optionalLookupJavaType(t).isPresent())
                        .filter(t -> metaAccess.lookupJavaType(t).isInTypeCheck())
                        .filter(t -> classInitializationSupport.specifiedInitKindFor(t) == null)
                        .forEach(c -> {
                            AnalysisType type = metaAccess.lookupJavaType(c);
                            if (!initGraph.isUnsafe(type)) {
                                provenSafe.add(type);
                                classInitializationSupport.forceInitializeHosted(c, "proven safe to initialize");
                            }
                        });
        return provenSafe;
    }

    @Override
    public void afterImageWrite(AfterImageWriteAccess a) {
        /*
         * This is the final time to check if any class that must not have been initialized during
         * image building got initialized.
         */
        classInitializationSupport.checkDelayedInitialization();
    }

    private void buildClassInitializationInfo(FeatureImpl.DuringAnalysisAccessImpl access, AnalysisType type, DynamicHub hub) {
        ClassInitializationInfo info;
        if (classInitializationSupport.shouldInitializeAtRuntime(type)) {
            assert !type.isInitialized();
            AnalysisMethod classInitializer = type.getClassInitializer();
            if (type.isLinked()) {
                if (classInitializer != null) {
                    assert classInitializer.getCode() != null;
                    access.registerAsCompiled(classInitializer);
                }
                info = new ClassInitializationInfo(MethodPointer.factory(classInitializer));
            } else {
                /* The type failed to link due to verification issues triggered by missing types. */
                assert classInitializer == null || classInitializer.getCode() == null;
                info = ClassInitializationInfo.FAILED_INFO_SINGLETON;
            }
        } else {
            assert type.isInitialized();
            info = ClassInitializationInfo.INITIALIZED_INFO_SINGLETON;
        }

        hub.setClassInitializationInfo(info, hasDefaultMethods(type), declaresDefaultMethods(type));
    }

    private static boolean hasDefaultMethods(ResolvedJavaType type) {
        if (!type.isInterface() && type.getSuperclass() != null && hasDefaultMethods(type.getSuperclass())) {
            return true;
        }
        for (ResolvedJavaType iface : type.getInterfaces()) {
            if (hasDefaultMethods(iface)) {
                return true;
            }
        }
        return declaresDefaultMethods(type);
    }

    static boolean declaresDefaultMethods(ResolvedJavaType type) {
        if (!type.isInterface()) {
            /* Only interfaces can declare default methods. */
            return false;
        }
        /*
         * We call getDeclaredMethods() directly on the wrapped type. We avoid calling it on the
         * AnalysisType because it resolves all the methods in the AnalysisUniverse.
         */
        for (ResolvedJavaMethod method : Inflation.toWrappedType(type).getDeclaredMethods()) {
            if (method.isDefault()) {
                assert !Modifier.isStatic(method.getModifiers()) : "Default method that is static?";
                return true;
            }
        }
        return false;
    }
}
