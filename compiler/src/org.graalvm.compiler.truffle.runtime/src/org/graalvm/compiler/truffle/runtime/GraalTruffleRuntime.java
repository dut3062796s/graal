/*
 * Copyright (c) 2013, 2018, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.truffle.runtime;

import static java.util.Collections.singletonList;
import static org.graalvm.compiler.debug.DebugContext.DEFAULT_LOG_STREAM;
import static org.graalvm.compiler.debug.DebugContext.NO_GLOBAL_METRIC_VALUES;
import static org.graalvm.compiler.serviceprovider.GraalServices.Java8OrEarlier;
import static org.graalvm.compiler.truffle.common.TruffleCompilerOptions.TruffleCompilation;
import static org.graalvm.compiler.truffle.common.TruffleCompilerOptions.TruffleCompilationExceptionsAreThrown;
import static org.graalvm.compiler.truffle.common.TruffleCompilerOptions.TruffleCompileOnly;
import static org.graalvm.compiler.truffle.common.TruffleCompilerOptions.TruffleProfilingEnabled;
import static org.graalvm.compiler.truffle.common.TruffleCompilerOptions.TruffleUseFrameWithoutBoxing;
import static org.graalvm.compiler.truffle.common.TruffleCompilerOptions.getValue;
import static org.graalvm.compiler.truffle.runtime.GraalTruffleRuntime.LazyFrameBoxingQuery.FrameBoxingClass;
import static org.graalvm.compiler.truffle.runtime.GraalTruffleRuntime.LazyFrameBoxingQuery.FrameBoxingClassName;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.graalvm.collections.EconomicMap;
import org.graalvm.compiler.api.replacements.SnippetReflectionProvider;
import org.graalvm.compiler.api.runtime.GraalRuntime;
import org.graalvm.compiler.core.common.CompilationIdentifier;
import org.graalvm.compiler.core.common.PermanentBailoutException;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.DebugContext.Description;
import org.graalvm.compiler.debug.DebugContext.Scope;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.debug.TTY;
import org.graalvm.compiler.nodes.graphbuilderconf.InlineInvokePlugin.InlineInfo;
import org.graalvm.compiler.nodes.graphbuilderconf.LoopExplosionPlugin.LoopExplosionKind;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.serviceprovider.GraalServices;
import org.graalvm.compiler.truffle.common.CompilableTruffleAST;
import org.graalvm.compiler.truffle.common.OptimizedAssumptionDependency;
import org.graalvm.compiler.truffle.common.TruffleCompilationTask;
import org.graalvm.compiler.truffle.common.TruffleCompiler;
import org.graalvm.compiler.truffle.common.TruffleCompilerOptions;
import org.graalvm.compiler.truffle.common.TruffleCompilerRuntime;
import org.graalvm.compiler.truffle.common.TruffleDebugJavaMethod;
import org.graalvm.compiler.truffle.common.TruffleInliningPlan;
import org.graalvm.compiler.truffle.runtime.debug.StatisticsListener;
import org.graalvm.compiler.truffle.runtime.debug.TraceASTCompilationListener;
import org.graalvm.compiler.truffle.runtime.debug.TraceCallTreeListener;
import org.graalvm.compiler.truffle.runtime.debug.TraceCompilationFailureListener;
import org.graalvm.compiler.truffle.runtime.debug.TraceCompilationListener;
import org.graalvm.compiler.truffle.runtime.debug.TraceCompilationPolymorphismListener;
import org.graalvm.compiler.truffle.runtime.debug.TraceInliningListener;
import org.graalvm.compiler.truffle.runtime.debug.TraceSplittingListener;
import org.graalvm.graphio.GraphOutput;
import org.graalvm.util.CollectionsUtil;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.CompilerOptions;
import com.oracle.truffle.api.ExactMath;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleOptions;
import com.oracle.truffle.api.TruffleRuntime;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.FrameInstanceVisitor;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.impl.AbstractAssumption;
import com.oracle.truffle.api.impl.TVMCI;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.Node.Child;
import com.oracle.truffle.api.nodes.Node.Children;
import com.oracle.truffle.api.nodes.RepeatingNode;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.nodes.SlowPathException;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.object.LayoutFactory;
import java.io.Closeable;

import jdk.vm.ci.code.BailoutException;
import jdk.vm.ci.code.stack.InspectedFrame;
import jdk.vm.ci.code.stack.InspectedFrameVisitor;
import jdk.vm.ci.code.stack.StackIntrospection;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.SpeculationLog;

/**
 * Implementation of the Truffle runtime when running on top of Graal.
 */
public abstract class GraalTruffleRuntime implements TruffleRuntime, TruffleCompilerRuntime {

    /**
     * Used only to reset state for native image compilation.
     */
    protected void clearState() {
        assert TruffleOptions.AOT : "Must be called only in AOT mode.";
        callMethods = null;
        truffleCompiler = null;
    }

    private Object cachedIncludesExcludes;
    private ArrayList<String> includes;
    private ArrayList<String> excludes;

    private final GraalTruffleRuntimeListenerDispatcher listeners = new GraalTruffleRuntimeListenerDispatcher();

    protected volatile TruffleCompiler truffleCompiler;
    protected LoopNodeFactory loopNodeFactory;
    protected CallMethods callMethods;

    private final Supplier<GraalRuntime> graalRuntimeSupplier;
    private final GraalTVMCI tvmci = new GraalTVMCI();

    private volatile GraalTestTVMCI testTvmci;

    /**
     * Utility method that casts the singleton {@link TruffleRuntime}.
     */
    public static GraalTruffleRuntime getRuntime() {
        return (GraalTruffleRuntime) Truffle.getRuntime();
    }

    private final EconomicMap<String, Class<?>> lookupTypes;

    public GraalTruffleRuntime(Supplier<GraalRuntime> graalRuntimeSupplier, Iterable<Class<?>> extraLookupTypes) {
        this.graalRuntimeSupplier = graalRuntimeSupplier;
        this.lookupTypes = initLookupTypes(extraLookupTypes);
    }

    @Override
    public String getName() {
        String compilerConfigurationName = getCompilerConfigurationName();
        assert compilerConfigurationName != null;
        String suffix;
        if (compilerConfigurationName == null) {
            suffix = "Unknown";
        } else if (compilerConfigurationName.equals("community")) {
            suffix = "CE";
        } else if (compilerConfigurationName.equals("enterprise")) {
            suffix = "EE";
        } else {
            assert false : "unexpected compiler configuration name: " + compilerConfigurationName;
            suffix = compilerConfigurationName;
        }
        return "GraalVM " + suffix;
    }

    /**
     * This method allows retrieval of the compiler configuration without requiring to initialize
     * the {@link TruffleCompiler} with {@link #getTruffleCompiler()}. The result of this method
     * should always match {@link TruffleCompiler#getCompilerConfigurationName()}.
     */
    protected abstract String getCompilerConfigurationName();

    protected GraalTVMCI getTvmci() {
        return tvmci;
    }

    protected TVMCI.Test<?, ?> getTestTvmci() {
        if (testTvmci == null) {
            synchronized (this) {
                if (testTvmci == null) {
                    testTvmci = new GraalTestTVMCI(this);
                }
            }
        }
        return testTvmci;
    }

    @Override
    public final GraalRuntime getGraalRuntime() {
        return graalRuntimeSupplier.get();
    }

    @Override
    public TruffleInliningPlan createInliningPlan(CompilableTruffleAST compilable) {
        return new TruffleInlining((OptimizedCallTarget) compilable, TruffleInliningPolicy.getInliningPolicy(getInitialOptions()));
    }

    @Override
    public CompilableTruffleAST asCompilableTruffleAST(JavaConstant constant) {
        SnippetReflectionProvider snippetReflection = getGraalRuntime().getRequiredCapability(SnippetReflectionProvider.class);
        return snippetReflection.asObject(OptimizedCallTarget.class, constant);
    }

    @Override
    public JavaConstant getCallTargetForCallNode(JavaConstant callNodeConstant) {
        SnippetReflectionProvider snippetReflection = getGraalRuntime().getRequiredCapability(SnippetReflectionProvider.class);
        OptimizedDirectCallNode callNode = snippetReflection.asObject(OptimizedDirectCallNode.class, callNodeConstant);
        return snippetReflection.forObject(callNode.getCallTarget());
    }

    @Override
    public Consumer<OptimizedAssumptionDependency> registerOptimizedAssumptionDependency(JavaConstant optimizedAssumptionConstant) {
        SnippetReflectionProvider snippetReflection = getGraalRuntime().getRequiredCapability(SnippetReflectionProvider.class);
        OptimizedAssumption optimizedAssumption = snippetReflection.asObject(OptimizedAssumption.class, optimizedAssumptionConstant);
        return optimizedAssumption.registerDependency();
    }

    private static <T extends PrioritizedServiceProvider> T loadPrioritizedServiceProvider(Class<T> clazz) {
        Iterable<T> providers = GraalServices.load(clazz);
        T bestFactory = null;
        for (T factory : providers) {
            if (bestFactory == null) {
                bestFactory = factory;
            } else if (factory.getPriority() > bestFactory.getPriority()) {
                bestFactory = factory;
            }
        }
        if (bestFactory == null) {
            throw new IllegalStateException("Unable to load a factory for " + clazz.getName());
        }
        return bestFactory;
    }

    @Override
    public void log(String message) {
        TTY.out().println(message);
    }

    @Override
    public ConstantFieldInfo getConstantFieldInfo(ResolvedJavaField field) {
        for (Annotation a : getAnnotations(field)) {
            if (a.annotationType() == Child.class) {
                return TruffleCompilerRuntime.ConstantFieldInfo.CHILD;
            }
            if (a.annotationType() == Children.class) {
                return TruffleCompilerRuntime.ConstantFieldInfo.CHILDREN;
            }
            if (a.annotationType() == CompilationFinal.class) {
                CompilationFinal cf = (CompilationFinal) a;
                int dimensions = actualStableDimensions(field, cf.dimensions());
                return TruffleCompilerRuntime.ConstantFieldInfo.forDimensions(dimensions);
            }

        }
        return null;
    }

    private static int actualStableDimensions(ResolvedJavaField field, int dimensions) {
        if (dimensions == 0) {
            return 0;
        }
        int arrayDim = getArrayDimensions(field.getType());
        if (dimensions < 0) {
            if (dimensions != -1) {
                throw new IllegalArgumentException("Negative @CompilationFinal dimensions");
            }
            return arrayDim;
        }
        if (dimensions > arrayDim) {
            throw new IllegalArgumentException(String.format("@CompilationFinal(dimensions=%d) exceeds declared array dimensions (%d) of field %s", dimensions, arrayDim, field));
        }
        return dimensions;
    }

    private static int getArrayDimensions(JavaType type) {
        int dimensions = 0;
        for (JavaType componentType = type; componentType.isArray(); componentType = componentType.getComponentType()) {
            dimensions++;
        }
        return dimensions;
    }

    @SuppressWarnings("deprecation")
    @Override
    public LoopExplosionKind getLoopExplosionKind(ResolvedJavaMethod method) {
        ExplodeLoop explodeLoop = getAnnotation(ExplodeLoop.class, method);
        if (explodeLoop == null) {
            return LoopExplosionKind.NONE;
        }

        // Support for the deprecated Truffle property until it is removed in a future Truffle
        // release.
        if (explodeLoop.merge()) {
            return LoopExplosionKind.MERGE_EXPLODE;
        }

        switch (explodeLoop.kind()) {
            case FULL_UNROLL:
                return LoopExplosionKind.FULL_UNROLL;
            case FULL_EXPLODE:
                return LoopExplosionKind.FULL_EXPLODE;
            case FULL_EXPLODE_UNTIL_RETURN:
                return LoopExplosionKind.FULL_EXPLODE_UNTIL_RETURN;
            case MERGE_EXPLODE:
                return LoopExplosionKind.MERGE_EXPLODE;
            default:
                throw new GraalError("Unknown Truffle LoopExplosionKind %s", explodeLoop.kind());
        }
    }

    private static EconomicMap<String, Class<?>> initLookupTypes(Iterable<Class<?>> extraTypes) {
        EconomicMap<String, Class<?>> m = EconomicMap.create();
        for (Class<?> c : new Class<?>[]{
                        Node.class,
                        UnexpectedResultException.class,
                        SlowPathException.class,
                        OptimizedCallTarget.class,
                        OptimizedDirectCallNode.class,
                        OptimizedAssumption.class,
                        CompilerDirectives.class,
                        GraalCompilerDirectives.class,
                        CompilerAsserts.class,
                        ExactMath.class,
                        FrameDescriptor.class,
                        FrameSlot.class,
                        FrameSlotKind.class,
                        MethodHandle.class,
                        ArrayList.class,
                        FrameSlotKind.class,
                        AbstractAssumption.class,
                        MaterializedFrame.class
        }) {
            m.put(c.getName(), c);
        }
        for (Class<?> c : extraTypes) {
            m.put(c.getName(), c);
        }
        for (TruffleTypes s : GraalServices.load(TruffleTypes.class)) {
            for (Class<?> c : s.getTypes()) {
                m.put(c.getName(), c);
            }
        }
        return m;
    }

    @Override
    public ResolvedJavaType resolveType(MetaAccessProvider metaAccess, String className, boolean required) {
        Class<?> c = className.equals(FrameBoxingClassName) ? FrameBoxingClass : lookupTypes.get(className);
        if (c == null) {
            if (!required) {
                return null;
            }
            throw new NoClassDefFoundError(className);
        }
        return metaAccess.lookupJavaType(c);
    }

    protected void installDefaultListeners() {
        TraceCompilationFailureListener.install(this);
        TraceCompilationListener.install(this);
        TraceCompilationPolymorphismListener.install(this);
        TraceCallTreeListener.install(this);
        TraceInliningListener.install(this);
        TraceSplittingListener.install(this);
        StatisticsListener.install(this);
        TraceASTCompilationListener.install(this);
        installShutdownHooks();
    }

    protected void installShutdownHooks() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
    }

    protected void lookupCallMethods(MetaAccessProvider metaAccess) {
        callMethods = CallMethods.lookup(metaAccess);
    }

    /** Accessor for non-public state in {@link FrameDescriptor}. */
    public void markFrameMaterializeCalled(FrameDescriptor descriptor) {
        try {
            getTvmci().markFrameMaterializeCalled(descriptor);
        } catch (Throwable ex) {
            /*
             * Backward compatibility: do nothing on old Truffle version where the field in
             * FrameDescriptor does not exist.
             */
        }
    }

    /** Accessor for non-public state in {@link FrameDescriptor}. */
    public boolean getFrameMaterializeCalled(FrameDescriptor descriptor) {
        try {
            return getTvmci().getFrameMaterializeCalled(descriptor);
        } catch (Throwable ex) {
            /*
             * Backward compatibility: be conservative on old Truffle version where the field in
             * FrameDescriptor does not exist.
             */
            return true;
        }
    }

    @Override
    public LoopNode createLoopNode(RepeatingNode repeatingNode) {
        if (!(repeatingNode instanceof Node)) {
            throw new IllegalArgumentException("Repeating node must be of type Node.");
        }
        return getLoopNodeFactory().create(repeatingNode);
    }

    protected LoopNodeFactory getLoopNodeFactory() {
        if (loopNodeFactory == null) {
            loopNodeFactory = loadPrioritizedServiceProvider(LoopNodeFactory.class);
        }
        return loopNodeFactory;
    }

    @Override
    public DirectCallNode createDirectCallNode(CallTarget target) {
        if (target instanceof OptimizedCallTarget) {
            final OptimizedDirectCallNode directCallNode = new OptimizedDirectCallNode((OptimizedCallTarget) target);
            TruffleSplittingStrategy.newDirectCallNodeCreated(directCallNode);
            return directCallNode;
        } else {
            throw new IllegalStateException(String.format("Unexpected call target class %s!", target.getClass()));
        }
    }

    @Override
    public IndirectCallNode createIndirectCallNode() {
        return new OptimizedIndirectCallNode();
    }

    @Override
    public VirtualFrame createVirtualFrame(Object[] arguments, FrameDescriptor frameDescriptor) {
        return OptimizedCallTarget.createFrame(frameDescriptor, arguments);
    }

    @Override
    public MaterializedFrame createMaterializedFrame(Object[] arguments) {
        return createMaterializedFrame(arguments, new FrameDescriptor());
    }

    @Override
    public MaterializedFrame createMaterializedFrame(Object[] arguments, FrameDescriptor frameDescriptor) {
        if (LazyFrameBoxingQuery.useFrameWithoutBoxing) {
            return new FrameWithoutBoxing(frameDescriptor, arguments);
        } else {
            return new FrameWithBoxing(frameDescriptor, arguments);
        }
    }

    @Override
    public CompilerOptions createCompilerOptions() {
        return new GraalCompilerOptions();
    }

    @Override
    public Assumption createAssumption() {
        return createAssumption(null);
    }

    @Override
    public Assumption createAssumption(String name) {
        return new OptimizedAssumption(name);
    }

    public GraalTruffleRuntimeListener getListener() {
        return listeners;
    }

    @TruffleBoundary
    @Override
    public <T> T iterateFrames(final FrameInstanceVisitor<T> visitor) {
        return iterateImpl(visitor, 0);
    }

    private static final class FrameVisitor<T> implements InspectedFrameVisitor<T> {

        private final FrameInstanceVisitor<T> visitor;
        private final CallMethods methods;

        private int skipFrames;

        private InspectedFrame callNodeFrame;

        FrameVisitor(FrameInstanceVisitor<T> visitor, CallMethods methods, int skip) {
            this.visitor = visitor;
            this.methods = methods;
            this.skipFrames = skip;
        }

        @Override
        public T visitFrame(InspectedFrame frame) {
            if (frame.isMethod(methods.callOSRMethod)) {
                // we ignore OSR frames.
                skipFrames++;
                return null;
            } else if (frame.isMethod(methods.callTargetMethod)) {
                if (skipFrames == 0) {
                    try {
                        return visitor.visitFrame(new GraalFrameInstance(frame, callNodeFrame));
                    } finally {
                        callNodeFrame = null;
                    }
                } else {
                    skipFrames--;
                }
            } else if (frame.isMethod(methods.callNodeMethod)) {
                callNodeFrame = frame;
            }
            return null;
        }
    }

    private <T> T iterateImpl(FrameInstanceVisitor<T> visitor, final int skip) {
        CallMethods methods = getCallMethods();
        FrameVisitor<T> jvmciVisitor = new FrameVisitor<>(visitor, methods, skip);
        return getStackIntrospection().iterateFrames(methods.anyFrameMethod, methods.anyFrameMethod, 0, jvmciVisitor);
    }

    protected abstract StackIntrospection getStackIntrospection();

    @Override
    public FrameInstance getCallerFrame() {
        return iterateImpl(frame -> frame, 1);
    }

    @TruffleBoundary
    @Override
    public FrameInstance getCurrentFrame() {
        return iterateImpl(frame -> frame, 0);
    }

    @Override
    public <T> T getCapability(Class<T> capability) {
        if (capability == TVMCI.class) {
            return capability.cast(tvmci);
        } else if (capability == LayoutFactory.class) {
            return capability.cast(loadObjectLayoutFactory());
        } else if (capability == TVMCI.Test.class) {
            return capability.cast(getTestTvmci());
        }
        try {
            Iterator<T> services = GraalServices.load(capability).iterator();
            if (services.hasNext()) {
                return services.next();
            }
            return null;
        } catch (ServiceConfigurationError e) {
            // Happens on JDK9 when a service type has not been exported to Graal
            // or Graal's module descriptor does not declare a use of capability.
            return null;
        }
    }

    @SuppressFBWarnings(value = "", justification = "Cache that does not need to use equals to compare.")
    final boolean acceptForCompilation(RootNode rootNode) {
        if (!getValue(TruffleCompilation)) {
            return false;
        }
        String includesExcludes = getValue(TruffleCompileOnly);
        if (includesExcludes != null) {
            if (cachedIncludesExcludes != includesExcludes) {
                parseCompileOnly();
                this.cachedIncludesExcludes = includesExcludes;
            }

            String name = rootNode.getName();
            boolean included = includes.isEmpty();
            if (name != null) {
                for (int i = 0; !included && i < includes.size(); i++) {
                    if (name.contains(includes.get(i))) {
                        included = true;
                    }
                }
            }
            if (!included) {
                return false;
            }
            if (name != null) {
                for (String exclude : excludes) {
                    if (name.contains(exclude)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    protected void parseCompileOnly() {
        ArrayList<String> includesList = new ArrayList<>();
        ArrayList<String> excludesList = new ArrayList<>();

        String[] items = getValue(TruffleCompileOnly).split(",");
        for (String item : items) {
            if (item.startsWith("~")) {
                excludesList.add(item.substring(1));
            } else {
                includesList.add(item);
            }
        }
        this.includes = includesList;
        this.excludes = excludesList;
    }

    public abstract SpeculationLog createSpeculationLog();

    @Override
    public RootCallTarget createCallTarget(RootNode rootNode) {
        CompilerAsserts.neverPartOfCompilation();
        final RootCallTarget newCallTarget = createClonedCallTarget(null, rootNode);
        TruffleSplittingStrategy.newTargetCreated(tvmci, newCallTarget);
        return newCallTarget;
    }

    @SuppressWarnings("deprecation")
    public RootCallTarget createClonedCallTarget(OptimizedCallTarget source, RootNode rootNode) {
        CompilerAsserts.neverPartOfCompilation();

        OptimizedCallTarget target = createOptimizedCallTarget(source, rootNode);
        rootNode.setCallTarget(target);
        tvmci.onLoad(target.getRootNode());
        return target;
    }

    public abstract OptimizedCallTarget createOptimizedCallTarget(OptimizedCallTarget source, RootNode rootNode);

    public void addListener(GraalTruffleRuntimeListener listener) {
        listeners.add(listener);
    }

    public void removeListener(GraalTruffleRuntimeListener listener) {
        listeners.remove(listener);
    }

    private void shutdown() {
        getListener().onShutdown();
        TruffleCompiler tcp = truffleCompiler;
        if (tcp != null) {
            tcp.shutdown();
        }
    }

    /**
     * Helper class that adds a "Truffle::method_name" group around all graph dumps of a single
     * compilation, and makes sure the group is properly closed at the end of the compilation.
     */
    private static final class OutputGroup implements Closeable {

        private final GraphOutput<Void, ?> output;

        private OutputGroup(DebugContext debug, CallTarget callTarget) {
            if (debug != null && debug.isDumpEnabled(DebugContext.BASIC_LEVEL)) {
                String name = "Truffle::" + callTarget.toString();
                GraphOutput<Void, ?> out = null;
                try {
                    out = debug.buildOutput(GraphOutput.newBuilder(VoidGraphStructure.INSTANCE).protocolVersion(6, 0));
                    out.beginGroup(null, name, name, null, 0, DebugContext.addVersionProperties(null));
                } catch (Throwable e) {
                    if (out != null) {
                        out.close();
                        out = null;
                    }
                }
                this.output = out;
            } else {
                this.output = null;
            }
        }

        @Override
        public void close() throws IOException {
            if (output != null) {
                try {
                    output.endGroup();
                } finally {
                    output.close();
                }
            }
        }
    }

    @SuppressWarnings("try")
    protected void doCompile(DebugContext debug, CompilationIdentifier compilationId, OptionValues options, OptimizedCallTarget callTarget, TruffleCompilationTask task) {
        listeners.onCompilationStarted(callTarget);
        TruffleCompiler compiler = getTruffleCompiler();
        TruffleInlining inlining = new TruffleInlining(callTarget,
                        task != null && task.isLastTier() ? TruffleInliningPolicy.getInliningPolicy(options) : TruffleInliningPolicy.getNoInliningPolicy(options));
        try (Scope s = debug != null ? debug.scope("Truffle", new TruffleDebugJavaMethod(callTarget)) : null) {
            // Open the "Truffle::methodName" dump group if dumping is enabled.
            try (OutputGroup o = new OutputGroup(debug, callTarget)) {
                // Create "AST" and "Call Tree" groups if dumping is enabled.
                maybeDumpTruffleTree(debug, options, callTarget, inlining);
                // Compile the method (puts dumps in "Graal Graphs" group if dumping is enabled).
                compiler.doCompile(debug, compilationId, options, callTarget, inlining, task, listeners.isEmpty() ? null : listeners);
            }
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new InternalError(e);
        } finally {
            if (debug != null) {
                /*
                 * The graph dumping code of Graal might leave inlining dump groups open, in case
                 * there are more graphs coming. Close these groups at the end of the compilation.
                 */
                debug.closeDumpHandlers(false);
            }
        }
        dequeueInlinedCallSites(inlining, callTarget);
    }

    @SuppressWarnings("try")
    protected void doCompile(OptionValues options, OptimizedCallTarget callTarget, TruffleCompilationTask task) {
        TruffleCompiler compiler = getTruffleCompiler();
        CompilationIdentifier compilationId = compiler.getCompilationIdentifier(callTarget);
        try (DebugContext debug = compilationId != null ? compiler.openDebugContext(options, compilationId, callTarget) : null) {
            doCompile(debug, compilationId, options, callTarget, task);
        }
    }

    @SuppressWarnings("try")
    private static void maybeDumpTruffleTree(DebugContext inDebug, OptionValues options, OptimizedCallTarget callTarget, TruffleInlining inlining) {
        DebugContext debug = inDebug;
        if (debug == null) {
            Description description = new Description(callTarget, "TruffleTree:" + callTarget.getName());
            debug = DebugContext.create(options, description, NO_GLOBAL_METRIC_VALUES, DEFAULT_LOG_STREAM, singletonList(new TruffleTreeDebugHandlersFactory()));
        }
        try (Scope c = debug.scope("TruffleTree")) {
            if (debug.isDumpEnabled(DebugContext.BASIC_LEVEL)) {
                debug.dump(DebugContext.BASIC_LEVEL, new TruffleTreeDumpHandler.TruffleTreeDump(callTarget, inlining), "TruffleTree");
            }
        }
    }

    private static void dequeueInlinedCallSites(TruffleInlining inliningDecision, OptimizedCallTarget optimizedCallTarget) {
        if (inliningDecision != null) {
            for (TruffleInliningDecision decision : inliningDecision) {
                if (decision.shouldInline()) {
                    OptimizedCallTarget target = decision.getTarget();
                    if (target != optimizedCallTarget) {
                        target.cancelInstalledTask(decision.getProfile().getCallNode(), "Inlining caller compiled.");
                    }
                    dequeueInlinedCallSites(decision, optimizedCallTarget);
                }
            }
        }
    }

    protected abstract BackgroundCompileQueue getCompileQueue();

    @SuppressWarnings("try")
    public CancellableCompileTask submitForCompilation(OptimizedCallTarget optimizedCallTarget, boolean lastTierCompilation) {
        BackgroundCompileQueue queue = getCompileQueue();
        return queue.submitCompilationRequest(this, optimizedCallTarget, lastTierCompilation);
    }

    public void finishCompilation(OptimizedCallTarget optimizedCallTarget, CancellableCompileTask task, boolean mayBeAsynchronous) {
        getListener().onCompilationQueued(optimizedCallTarget);

        if (!mayBeAsynchronous) {
            try {
                uninterruptibleWaitForCompilation(task);
            } catch (ExecutionException e) {
                if (TruffleCompilerOptions.getValue(TruffleCompilationExceptionsAreThrown) && !(e.getCause() instanceof BailoutException && !((BailoutException) e.getCause()).isPermanent())) {
                    throw new RuntimeException(e.getCause());
                } else {
                    // silently ignored
                }
            }
        }
    }

    private static void uninterruptibleWaitForCompilation(CancellableCompileTask task) throws ExecutionException {
        // We want to keep the interrupt bit if we are interrupted.
        // But we also want to maintain the semantics of foreground compilation:
        // waiting for the compilation to finish, even if it takes long,
        // so that compilation errors or effects are still properly waited for.
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    task.awaitCompletion();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean cancelInstalledTask(OptimizedCallTarget optimizedCallTarget, Object source, CharSequence reason) {
        CancellableCompileTask task = optimizedCallTarget.getCompilationTask();
        if (task != null) {
            if (task.cancel()) {
                getListener().onCompilationDequeued(optimizedCallTarget, source, reason);
                return true;
            }
        }
        return false;
    }

    public void waitForCompilation(OptimizedCallTarget optimizedCallTarget, long timeout) throws ExecutionException, TimeoutException {
        CancellableCompileTask task = optimizedCallTarget.getCompilationTask();
        if (task != null && !task.isCancelled()) {
            try {
                task.awaitCompletion(timeout, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // ignore interrupted
            }
        }

    }

    @Deprecated
    public Collection<OptimizedCallTarget> getQueuedCallTargets() {
        return Collections.emptyList();
    }

    public int getCompilationQueueSize() {
        return getCompileQueue().getQueueSize();
    }

    public boolean isCompiling(OptimizedCallTarget optimizedCallTarget) {
        return optimizedCallTarget.isCompiling();
    }

    /**
     * Notifies this runtime when a Truffle AST is being executed in the Truffle interpreter even
     * though compiled code is available for the AST.
     *
     * If this runtime uses a special entry point to switch to compiled Truffle code, then this
     * means the code with the special entry point was deoptimized or otherwise removed from the
     * code cache and needs to be re-installed.
     */
    public void bypassedInstalledCode() {
    }

    protected CallMethods getCallMethods() {
        return callMethods;
    }

    // cached field access to make it fast in the interpreter
    private Boolean profilingEnabled;

    @Override
    public final boolean isProfilingEnabled() {
        if (profilingEnabled == null) {
            profilingEnabled = TruffleCompilerOptions.getValue(TruffleProfilingEnabled);
        }
        return profilingEnabled;
    }

    private static Object loadObjectLayoutFactory() {
        ServiceLoader<LayoutFactory> graalLoader = ServiceLoader.load(LayoutFactory.class, GraalTruffleRuntime.class.getClassLoader());
        if (Java8OrEarlier) {
            return selectObjectLayoutFactory(graalLoader);
        } else {
            /*
             * The Graal module (i.e., jdk.internal.vm.compiler) is loaded by the platform class
             * loader on JDK 9. Its module dependencies such as Truffle are supplied via
             * --module-path which means they are loaded by the app class loader. As such, we need
             * to search the app class loader path as well.
             */
            ServiceLoader<LayoutFactory> appLoader = ServiceLoader.load(LayoutFactory.class, LayoutFactory.class.getClassLoader());
            return selectObjectLayoutFactory(CollectionsUtil.concat(graalLoader, appLoader));
        }
    }

    protected static LayoutFactory selectObjectLayoutFactory(Iterable<LayoutFactory> availableLayoutFactories) {
        String layoutFactoryImplName = System.getProperty("truffle.object.LayoutFactory");
        LayoutFactory bestLayoutFactory = null;
        for (LayoutFactory currentLayoutFactory : availableLayoutFactories) {
            if (layoutFactoryImplName != null) {
                if (currentLayoutFactory.getClass().getName().equals(layoutFactoryImplName)) {
                    return currentLayoutFactory;
                }
            } else {
                if (bestLayoutFactory == null) {
                    bestLayoutFactory = currentLayoutFactory;
                } else if (currentLayoutFactory.getPriority() >= bestLayoutFactory.getPriority()) {
                    assert currentLayoutFactory.getPriority() != bestLayoutFactory.getPriority();
                    bestLayoutFactory = currentLayoutFactory;
                }
            }
        }
        return bestLayoutFactory;
    }

    protected static final class CallMethods {
        public final ResolvedJavaMethod callNodeMethod;
        public final ResolvedJavaMethod callTargetMethod;
        public final ResolvedJavaMethod callOSRMethod;
        public final ResolvedJavaMethod[] anyFrameMethod;

        private CallMethods(MetaAccessProvider metaAccess) {
            this.callNodeMethod = metaAccess.lookupJavaMethod(GraalFrameInstance.CALL_NODE_METHOD);
            this.callTargetMethod = metaAccess.lookupJavaMethod(GraalFrameInstance.CALL_TARGET_METHOD);
            this.callOSRMethod = metaAccess.lookupJavaMethod(GraalFrameInstance.CALL_OSR_METHOD);
            this.anyFrameMethod = new ResolvedJavaMethod[]{callNodeMethod, callTargetMethod, callOSRMethod};
        }

        public static CallMethods lookup(MetaAccessProvider metaAccess) {
            return new CallMethods(metaAccess);
        }
    }

    @Override
    public boolean isValueType(ResolvedJavaType type) {
        return getAnnotation(CompilerDirectives.ValueType.class, type) != null;
    }

    @Override
    public JavaKind getJavaKindForFrameSlotKind(int frameSlotKindTag) {
        if (frameSlotKindTag == FrameSlotKind.Boolean.tag) {
            return JavaKind.Boolean;
        }
        if (frameSlotKindTag == FrameSlotKind.Byte.tag) {
            return JavaKind.Byte;
        }
        if (frameSlotKindTag == FrameSlotKind.Int.tag) {
            return JavaKind.Int;
        }
        if (frameSlotKindTag == FrameSlotKind.Float.tag) {
            return JavaKind.Float;
        }
        if (frameSlotKindTag == FrameSlotKind.Long.tag) {
            return JavaKind.Long;
        }
        if (frameSlotKindTag == FrameSlotKind.Double.tag) {
            return JavaKind.Double;
        }
        if (frameSlotKindTag == FrameSlotKind.Object.tag) {
            return JavaKind.Object;
        }
        if (frameSlotKindTag == FrameSlotKind.Illegal.tag) {
            return JavaKind.Illegal;
        }
        throw new IllegalArgumentException("Unknown FrameSlotKind tag: " + frameSlotKindTag);
    }

    @Override
    public int getFrameSlotKindTagForJavaKind(JavaKind kind) {
        switch (kind) {
            case Boolean:
                return FrameSlotKind.Boolean.tag;
            case Byte:
                return FrameSlotKind.Byte.tag;
            case Int:
                return FrameSlotKind.Int.tag;
            case Float:
                return FrameSlotKind.Float.tag;
            case Long:
                return FrameSlotKind.Long.tag;
            case Double:
                return FrameSlotKind.Double.tag;
            case Object:
                return FrameSlotKind.Object.tag;
            case Illegal:
                return FrameSlotKind.Illegal.tag;
        }
        throw new IllegalArgumentException("No FrameSlotKind for Java kind " + kind);
    }

    @Override
    public int getFrameSlotKindTagsCount() {
        return FrameSlotKind.values().length;
    }

    @SuppressWarnings("deprecation")
    @Override
    public InlineInfo getInlineInfo(ResolvedJavaMethod original, boolean duringPartialEvaluation) {
        TruffleBoundary truffleBoundary = getAnnotation(TruffleBoundary.class, original);
        if (truffleBoundary != null) {
            if (duringPartialEvaluation || !truffleBoundary.allowInlining()) {
                // Since this method is invoked by the bytecode parser plugins, which can be invoked
                // by the partial evaluator, we want to prevent inlining across the boundary during
                // partial evaluation,
                // even if the TruffleBoundary allows inlining after partial evaluation.
                if (!truffleBoundary.throwsControlFlowException() && truffleBoundary.transferToInterpreterOnException()) {
                    return InlineInfo.DO_NOT_INLINE_DEOPTIMIZE_ON_EXCEPTION;
                } else {
                    return InlineInfo.DO_NOT_INLINE_WITH_EXCEPTION;
                }
            }
        } else if (getAnnotation(TruffleCallBoundary.class, original) != null) {
            return InlineInfo.DO_NOT_INLINE_WITH_EXCEPTION;
        }
        return InlineInfo.createStandardInlineInfo(original);
    }

    @Override
    public boolean isTruffleBoundary(ResolvedJavaMethod method) {
        return getAnnotation(TruffleBoundary.class, method) != null;
    }

    public static class LazyFrameBoxingQuery {
        /**
         * The flag is checked from within a Truffle compilation and we need to constant fold the
         * decision. In addition, we want only one of {@link FrameWithoutBoxing} and
         * {@link FrameWithBoxing} seen as reachable in AOT mode, so we need to be able to constant
         * fold the decision as early as possible.
         */
        public static final boolean useFrameWithoutBoxing = TruffleCompilerOptions.getValue(TruffleUseFrameWithoutBoxing);

        static final Class<?> FrameBoxingClass = useFrameWithoutBoxing ? FrameWithoutBoxing.class : FrameWithBoxing.class;
        static final String FrameBoxingClassName = FrameBoxingClass.getName();
    }

    // https://bugs.openjdk.java.net/browse/JDK-8209535

    private static PermanentBailoutException handleAnnotationFailure(NoClassDefFoundError e, String attemptedAction) {
        throw new PermanentBailoutException(e, "Error while %s. " +
                        "This usually means that the unresolved type is in the signature of some other " +
                        "method or field in the same class. This can be resolved by modifying the relevant class path " +
                        "or module path such that it includes the missing type.",
                        attemptedAction);
    }

    private static Annotation[] getAnnotations(ResolvedJavaField element) {
        try {
            return element.getAnnotations();
        } catch (NoClassDefFoundError e) {
            throw handleAnnotationFailure(e, String.format("querying %s for annotations", element.format("%H.%n:%t")));
        }
    }

    private static <T extends Annotation> T getAnnotation(Class<T> annotationClass, ResolvedJavaMethod method) {
        try {
            return annotationClass.cast(method.getAnnotation(annotationClass));
        } catch (NoClassDefFoundError e) {
            throw handleAnnotationFailure(e, String.format("querying %s for presence of a %s annotation", method.format("%H.%n(%p)"), annotationClass.getName()));
        }
    }

    private static <T extends Annotation> T getAnnotation(Class<T> annotationClass, ResolvedJavaType type) {
        try {
            return annotationClass.cast(type.getAnnotation(annotationClass));
        } catch (NoClassDefFoundError e) {
            throw handleAnnotationFailure(e, String.format("querying %s for presence of a %s annotation", type.toJavaName(), annotationClass.getName()));
        }
    }
}
