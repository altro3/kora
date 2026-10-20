package io.koraframework.kora.app.annotation.processor;

import com.palantir.javapoet.*;
import io.koraframework.annotation.processor.common.*;
import io.koraframework.kora.app.annotation.processor.component.*;
import io.koraframework.kora.app.annotation.processor.declaration.*;
import io.koraframework.kora.app.annotation.processor.interceptor.*;

import javax.lang.model.element.*;
import javax.lang.model.type.*;
import java.util.*;
import java.util.function.*;

public class GraphFileGenerator {
    private final ProcessingContext ctx;
    private final Element classElement;
    private final List<TypeElement> allModules;
    private final ComponentInterceptors interceptors;
    private final List<ResolvedComponent> components;
    private final Map<ClassName, ResolvedComponent> conditions;

    private static final int COMPONENTS_PER_PARTITION = 500;

    public GraphFileGenerator(ProcessingContext ctx, Element classElement, List<TypeElement> allModules, ComponentInterceptors interceptors, List<ResolvedComponent> components, Map<ClassName, ResolvedComponent> conditions) {
        this.ctx = ctx;
        this.classElement = classElement;
        this.allModules = allModules;
        this.interceptors = interceptors;
        this.components = components;
        this.conditions = conditions;
    }

    private static final class CacheEntry<T> {
        final T value;
        int count = 0;
        Integer assignedIndex = null;

        CacheEntry(T value) {this.value = value;}
    }

    private static final class HolderCache {
        final Map<String, CacheEntry<CodeBlock>> types = new LinkedHashMap<>();
        final Map<String, CacheEntry<String>> tags = new LinkedHashMap<>();
    }

    private HolderCache buildPartitionCache(ClassName typeRefClassName, int startIdx, int endIdx) {
        var cache = new HolderCache();
        for (int k = startIdx; k < endIdx; k++) {
            var comp = components.get(k);
            TypeName compTypeName = TypeName.get(comp.type()).box();
            CodeBlock typeCode = buildTypeInitializer(typeRefClassName, compTypeName);
            String typeKey = typeCode.toString();

            cache.types.computeIfAbsent(typeKey, _ -> new CacheEntry<>(typeCode)).count++;
            if (comp.tag() != null) {
                cache.tags.computeIfAbsent(comp.tag(), _ -> new CacheEntry<>(comp.tag())).count++;
            }
        }
        int typeIdx = 0;
        for (var entry : cache.types.values()) {
            if (entry.count > 1) entry.assignedIndex = typeIdx++;
        }
        int tagIdx = 0;
        for (var entry : cache.tags.values()) {
            if (entry.count > 1) entry.assignedIndex = tagIdx++;
        }
        return cache;
    }

    private CodeBlock buildTypeInitializer(ClassName typeRefClassName, TypeName typeName) {
        if (typeName instanceof ParameterizedTypeName ptn) {
            var builder = CodeBlock.builder();
            builder.add("$T.of($T.class", typeRefClassName, ptn.rawType());
            for (var typeArg : ptn.typeArguments()) {
                builder.add(", $L", buildTypeInitializer(typeRefClassName, typeArg));
            }
            builder.add(")");
            return builder.build();
        } else if (typeName instanceof WildcardTypeName wtn) {
            var koraWildcardClassName = ClassName.get("io.koraframework.application.graph", "KoraWildcardType");
            var reflectTypeClass = ClassName.get("java.lang.reflect", "Type");

            if (!wtn.lowerBounds().isEmpty()) {
                return CodeBlock.of("new $T(null, new $T[]{$L})",
                    koraWildcardClassName, reflectTypeClass, buildTypeInitializer(typeRefClassName, wtn.lowerBounds().getFirst()));
            } else if (!wtn.upperBounds().isEmpty() && !wtn.upperBounds().getFirst().equals(ClassName.get(Object.class))) {
                return CodeBlock.of("new $T(new $T[]{$L}, null)",
                    koraWildcardClassName, reflectTypeClass, buildTypeInitializer(typeRefClassName, wtn.upperBounds().getFirst()));
            } else {
                return CodeBlock.of("new $T(null, null)", koraWildcardClassName);
            }
        } else if (typeName instanceof ArrayTypeName atn) {
            return CodeBlock.of("java.lang.reflect.Array.newInstance($L, 0).getClass()", buildTypeInitializer(typeRefClassName, atn.componentType()));
        } else {
            return CodeBlock.of("$T.class", typeName);
        }
    }

    public JavaFile generate() {
        var packageElement = (PackageElement) classElement.getEnclosingElement();
        var implClass = ClassName.get(packageElement.getQualifiedName().toString(), "$" + classElement.getSimpleName() + "Impl");
        var graphName = classElement.getSimpleName() + "Graph";
        var graphTypeName = ClassName.get(packageElement.getQualifiedName().toString(), graphName);

        var classBuilder = TypeSpec.classBuilder(graphName)
            .addAnnotation(AnnotationUtils.generated(KoraAppProcessor.class))
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(ParameterizedTypeName.get(ClassName.get(Supplier.class), CommonClassNames.applicationGraphDraw))
            .addField(CommonClassNames.applicationGraphDraw, "graphDraw", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addField(implClass, "impl", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);

        int expectedNodes = components.size();
        int expectedCreateEdges = 0;
        int expectedRefreshEdges = 0;
        int expectedInterceptorEdges = 0;

        for (var component : components) {
            expectedCreateEdges += countDependencies(getCreateDependencies(component));
            expectedRefreshEdges += countDependencies(getRefreshDependencies(component));
            expectedInterceptorEdges += interceptors.interceptorsFor(component).size();
        }

        var downstreamMap = invertGraphToDownstream();
        int expectedDownstreamEdges = 0;
        for (var consumers : downstreamMap) {
            expectedDownstreamEdges += consumers.size();
        }

        int totalPartitions = (expectedNodes + COMPONENTS_PER_PARTITION - 1) / COMPONENTS_PER_PARTITION;

        var baseStaticBlock = CodeBlock.builder()
            .addStatement("impl = new $T()", implClass)
            .addStatement("graphDraw = new $T($T.class, (short) $L, (short) $L, (short) $L, (short) $L, (short) $L)",
                CommonClassNames.applicationGraphDraw, classElement,
                expectedNodes, expectedCreateEdges, expectedRefreshEdges, expectedInterceptorEdges, expectedDownstreamEdges);

        for (int p = 0; p < totalPartitions; p++) {
            baseStaticBlock.addStatement("$T.trigger()", ClassName.get(packageElement.getQualifiedName() + "." + graphName, "NodeHolder" + p));
        }

        classBuilder.addStaticBlock(baseStaticBlock.build());

        var typeRefClassName = ClassName.get("io.koraframework.application.graph", "TypeRef");

        for (int p = 0; p < totalPartitions; p++) {
            int startIdx = p * COMPONENTS_PER_PARTITION;
            int endIdx = Math.min(expectedNodes, startIdx + COMPONENTS_PER_PARTITION);

            var holderClassBuilder = TypeSpec.classBuilder("NodeHolder" + p)
                .addAnnotation(AnnotationUtils.generated(KoraAppProcessor.class))
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);

            var holderStaticBlock = CodeBlock.builder();

            var partitionCache = buildPartitionCache(typeRefClassName, startIdx, endIdx);
            for (var entry : partitionCache.types.values()) {
                if (entry.assignedIndex != null) {
                    holderStaticBlock.addStatement("var type_$L = $L", entry.assignedIndex, entry.value);
                }
            }
            for (var entry : partitionCache.tags.values()) {
                if (entry.assignedIndex != null) {
                    holderStaticBlock.addStatement("var tag_$L = $T.class", entry.assignedIndex, ClassName.bestGuess(entry.value));
                }
            }

            for (int i = startIdx; i < endIdx; i++) {
                var component = components.get(i);
                TypeName componentTypeName = TypeName.get(component.type()).box();

                holderClassBuilder.addField(FieldSpec.builder(
                    ParameterizedTypeName.get(CommonClassNames.node, componentTypeName),
                    component.fieldName(), Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL).build());

                CodeBlock typeCode = buildTypeInitializer(typeRefClassName, componentTypeName);
                var typeEntry = partitionCache.types.get(typeCode.toString());
                String typeExpression = (typeEntry != null && typeEntry.assignedIndex != null) ? "type_" + typeEntry.assignedIndex : typeCode.toString();

                CodeBlock tagCode = CodeBlock.of("null");
                if (component.tag() != null) {
                    var tagEntry = partitionCache.tags.get(component.tag());
                    tagCode = (tagEntry != null && tagEntry.assignedIndex != null) ? CodeBlock.of("tag_$L", tagEntry.assignedIndex) : CodeBlock.of("$L.class", component.tag());
                }

                var createDeps = getCreateDependencies(component);
                var refreshDeps = getRefreshDependencies(component);
                var interceptorsFor = interceptors.interceptorsFor(component);

                CodeBlock interceptorBuilder;
                if (interceptorsFor.isEmpty()) {
                    interceptorBuilder = CodeBlock.of("null");
                } else {
                    var ib = CodeBlock.builder().add("new io.koraframework.application.graph.internal.NodeImpl<?>[]{");
                    for (int j = 0; j < interceptorsFor.size(); j++) {
                        if (j > 0) ib.add(", ");
                        ib.add("($T<?>) $L", ClassName.get("io.koraframework.application.graph.internal", "NodeImpl"), resolveNodePath(interceptorsFor.get(j).component()));
                    }
                    ib.add("}");
                    interceptorBuilder = ib.build();
                }

                CodeBlock conditionCode = buildConditionLambda(component);

                String factoryMethodName = "createNode" + i;
                boolean isObject = componentTypeName.equals(ClassName.get(Object.class));
                var bridgeMethodBuilder = MethodSpec.methodBuilder(factoryMethodName)
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .returns(componentTypeName)
                    .addParameter(ClassName.get("io.koraframework.application.graph", "RefreshableGraph"), "g")
                    .addException(ClassName.get(Exception.class));

                if (isObject) {
                    bridgeMethodBuilder.addStatement("return dispatchCreate((short) $L, g)", i);
                } else {
                    bridgeMethodBuilder.addStatement("return ($T) dispatchCreate((short) $L, g)", componentTypeName, i);
                }
                holderClassBuilder.addMethod(bridgeMethodBuilder.build());

                CodeBlock factoryLambdaCode = CodeBlock.of("$T::$L",
                    ClassName.get(packageElement.getQualifiedName() + "." + graphName, "NodeHolder" + p),
                    factoryMethodName);

                var consumers = downstreamMap.get(i);
                CodeBlock downstreamCode;
                if (consumers.isEmpty()) {
                    downstreamCode = CodeBlock.of("null");
                } else {
                    var cb = CodeBlock.builder().add("new short[]{");
                    for (int c = 0; c < consumers.size(); c++) {
                        if (c > 0) cb.add(", ");
                        cb.add("$L", consumers.get(c));
                    }
                    cb.add("}");
                    downstreamCode = cb.build();
                }

                holderStaticBlock.addStatement("$L = graphDraw.addNode($L, $L, $L, $L, $L, $L, $L, $L)",
                    component.fieldName(),
                    typeExpression,
                    tagCode,
                    conditionCode,
                    createDeps,
                    refreshDeps,
                    downstreamCode,
                    interceptorBuilder,
                    factoryLambdaCode
                );
            }

            holderClassBuilder.addStaticBlock(holderStaticBlock.build());

            var triggerMethod = MethodSpec.methodBuilder("trigger")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(void.class)
                .build();
            holderClassBuilder.addMethod(triggerMethod);

            classBuilder.addType(holderClassBuilder.build());
        }

        buildFactoryPartitions(classBuilder, graphTypeName, implClass, COMPONENTS_PER_PARTITION, totalPartitions, expectedNodes);

        var routerMethod = MethodSpec.methodBuilder("dispatchCreate")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(ClassName.get(Object.class))
            .addParameter(short.class, "index")
            .addParameter(ClassName.get("io.koraframework.application.graph", "RefreshableGraph"), "g")
            .addException(ClassName.get(Exception.class));

        routerMethod.addStatement("int partition = index / $L", COMPONENTS_PER_PARTITION);
        routerMethod.beginControlFlow("return switch (partition)");
        for (int p = 0; p < totalPartitions; p++) {
            routerMethod.addStatement("case $L -> FactoryPartition$L.get(index, g, impl)", p, p);
        }
        routerMethod.addStatement("default -> throw new $T()", IllegalArgumentException.class);
        routerMethod.endControlFlow("");
        classBuilder.addMethod(routerMethod.build());

        var supplierMethodBuilder = MethodSpec.methodBuilder("get")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(CommonClassNames.applicationGraphDraw)
            .addStatement("return graphDraw");
        classBuilder.addMethod(supplierMethodBuilder.build());

        var supplierStaticMethodBuilder = MethodSpec.methodBuilder("graph")
            .returns(CommonClassNames.applicationGraphDraw)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addStatement("return graphDraw");
        classBuilder.addMethod(supplierStaticMethodBuilder.build());

        return JavaFile.builder(packageElement.getQualifiedName().toString(), classBuilder.build()).build();
    }

    private void buildFactoryPartitions(TypeSpec.Builder classBuilder, ClassName graphTypeName, ClassName implClass, int partitionSize, int totalPartitions, int expectedNodes) {
        for (int p = 0; p < totalPartitions; p++) {
            int startIdx = p * partitionSize;
            int endIdx = Math.min(expectedNodes, startIdx + partitionSize);

            var partitionBuilder = TypeSpec.classBuilder("FactoryPartition" + p)
                .addAnnotation(AnnotationUtils.generated(KoraAppProcessor.class))
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);

            var getMethod = MethodSpec.methodBuilder("get")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(ClassName.get(Object.class))
                .addParameter(short.class, "index")
                .addParameter(ClassName.get("io.koraframework.application.graph", "RefreshableGraph"), "g")
                .addParameter(implClass, "impl")
                .addException(ClassName.get(Exception.class));

            getMethod.beginControlFlow("return switch (index)");

            for (int i = startIdx; i < endIdx; i++) {
                var component = components.get(i);
                var declaration = component.declaration();
                var dependenciesCode = generateDependenciesCode(ctx, component, graphTypeName, 0);

                var code = CodeBlock.builder().add("case $L -> ", i);

                switch (declaration) {
                    case ComponentDeclaration.AnnotatedComponent annotatedComponent -> {
                        code.add("new $T", ClassName.get(annotatedComponent.typeElement()));
                        if (!annotatedComponent.typeVariables().isEmpty()) {
                            code.add("<");
                            for (int j = 0; j < annotatedComponent.typeVariables().size(); j++) {
                                if (j > 0) code.add(", ");
                                code.add("$T", annotatedComponent.typeVariables().get(j));
                            }
                            code.add(">");
                        }
                        code.add("($L)", dependenciesCode);
                    }
                    case ComponentDeclaration.FromModuleComponent moduleComponent -> {
                        if (moduleComponent.module() instanceof ModuleDeclaration.ClassModule || moduleComponent.module() instanceof ModuleDeclaration.FactoryModule) {
                            var customDepsCode = generateDependenciesCode(ctx, component, graphTypeName, 1);
                            var moduleInstDep = component.dependencies().getFirst();

                            CodeBlock moduleInstCode = resolveDependencyPath(ctx, moduleInstDep, graphTypeName);
                            if (moduleInstDep instanceof ComponentDependency.SingleDependency) {
                                code.add("g.get($L).", moduleInstCode);
                            } else {
                                code.add("$L.", moduleInstCode);
                            }

                            if (!moduleComponent.typeVariables().isEmpty()) {
                                code.add("<");
                                for (int j = 0; j < moduleComponent.typeVariables().size(); j++) {
                                    if (j > 0) code.add(", ");
                                    code.add("$T", moduleComponent.typeVariables().get(j));
                                }
                                code.add(">");
                            }
                            code.add("$L($L)", moduleComponent.method().getSimpleName(), customDepsCode);
                        } else {
                            if (moduleComponent.module() instanceof ModuleDeclaration.AnnotatedModule(var element)) {
                                code.add("impl.module$L.", allModules.indexOf(element));
                            } else {
                                code.add("impl.");
                            }
                            if (!moduleComponent.typeVariables().isEmpty()) {
                                code.add("<");
                                for (int j = 0; j < moduleComponent.typeVariables().size(); j++) {
                                    if (j > 0) code.add(", ");
                                    code.add("$T", moduleComponent.typeVariables().get(j));
                                }
                                code.add(">");
                            }
                            code.add("$L($L)", moduleComponent.method().getSimpleName(), dependenciesCode);
                        }
                    }
                    case ComponentDeclaration.FromExtensionComponent extension -> code.add(extension.generator().apply(dependenciesCode));
                    case ComponentDeclaration.PromisedProxyComponent promisedProxyComponent -> {
                        if (promisedProxyComponent.typeElement().getTypeParameters().isEmpty()) {
                            code.add("new $T($L)", promisedProxyComponent.className(), dependenciesCode);
                        } else {
                            code.add("new $T<>($L)", promisedProxyComponent.className(), dependenciesCode);
                        }
                    }
                    case ComponentDeclaration.OptionalComponent optional -> {
                        code.add("$T.ofNullable($L)", Optional.class, dependenciesCode);
                    }
                    default -> throw new IllegalStateException("Unsupported component declaration context");
                }

                getMethod.addStatement(code.build());
            }

            getMethod.addStatement("default -> throw new $T()", IllegalArgumentException.class);
            getMethod.endControlFlow("");

            partitionBuilder.addMethod(getMethod.build());
            classBuilder.addType(partitionBuilder.build());
        }
    }

    private CodeBlock generateDependenciesCode(ProcessingContext ctx, ResolvedComponent component, ClassName graphTypeName, int startFrom) {
        var resolvedDependencies = component.dependencies();
        if (resolvedDependencies.isEmpty()) {
            return CodeBlock.of("");
        }
        var b = CodeBlock.builder();
        var localGraphTypeName = ClassName.get("", graphTypeName.simpleName());

        for (int i = startFrom, dependenciesSize = resolvedDependencies.size(); i < dependenciesSize; i++) {
            if (i > startFrom) b.add(", ");
            var resolvedDependency = resolvedDependencies.get(i);
            CodeBlock depCode = resolvedDependency.write(ctx, localGraphTypeName, components, COMPONENTS_PER_PARTITION);
            b.add("$L", depCode);
        }
        return b.build();
    }

    private CodeBlock resolveDependencyPath(ProcessingContext ctx, ComponentDependency dependency, ClassName localGraphTypeName) {
        ResolvedComponent targetComponent = switch (dependency) {
            case ComponentDependency.SingleDependency single -> switch (single) {
                case ComponentDependency.TargetDependency target -> target.component();
                case ComponentDependency.ValueOfDependency valueOf -> valueOf.component();
                case ComponentDependency.WrappedTargetDependency wrapped -> wrapped.component();
                default -> null;
            };
            default -> null;
        };

        if (targetComponent != null) {
            int globalIndex = components.indexOf(targetComponent);
            int partition = globalIndex / COMPONENTS_PER_PARTITION;
            return CodeBlock.of("NodeHolder$L.$L", partition, targetComponent.fieldName());
        }

        return dependency.write(ctx, localGraphTypeName, components, COMPONENTS_PER_PARTITION);
    }

    private int countDependencies(CodeBlock codeBlock) {
        String str = codeBlock.toString();
        if (str.equals("null")) return 0;
        return str.split(",").length;
    }

    private String resolveNodePath(ResolvedComponent comp) {
        int idx = components.indexOf(comp);
        if (idx == -1) {
            return comp.fieldName();
        }
        int p = idx / COMPONENTS_PER_PARTITION;
        return "NodeHolder" + p + "." + comp.fieldName();
    }

    private CodeBlock buildConditionLambda(ResolvedComponent component) {
        if (component.parentConditions().isEmpty() && component.declaration().condition() == null) {
            return CodeBlock.of("null");
        } else if (component.parentConditions().isEmpty()) {
            var condComp = Objects.requireNonNull(conditions.get(component.declaration().condition()));
            return CodeBlock.of("g -> g.condition($L).eval()", resolveNodePath(condComp));
        } else if (component.declaration().condition() == null) {
            return CodeBlock.of("g -> $L.eval()", parentCondition(component));
        } else {
            var condComp = Objects.requireNonNull(conditions.get(component.declaration().condition()));
            return CodeBlock.of("g -> $T.and($L, g.condition($L)).eval()", CommonClassNames.graphCondition, parentCondition(component), resolveNodePath(condComp));
        }
    }

    private CodeBlock parentCondition(ResolvedComponent component) {
        if (component.parentConditions().size() == 1) {
            var conditionComponent = Objects.requireNonNull(conditions.get(component.parentConditions().iterator().next()));
            return CodeBlock.of("g.condition($L)", resolveNodePath(conditionComponent));
        } else {
            var b = CodeBlock.builder();
            b.add("$T.or(", CommonClassNames.graphCondition);
            var parentConditions = new ArrayList<>(component.parentConditions());
            for (int i = 0; i < parentConditions.size(); i++) {
                if (i > 0) {
                    b.add(", ");
                }
                var condition = parentConditions.get(i);
                var conditionComponent = Objects.requireNonNull(conditions.get(condition));
                b.add("g.condition($L)", resolveNodePath(conditionComponent));
            }
            b.add(")");
            return b.build();
        }
    }

    private List<List<Integer>> invertGraphToDownstream() {
        int n = components.size();
        var downstreamMap = new ArrayList<List<Integer>>(n);
        for (int i = 0; i < n; i++) {
            downstreamMap.add(new ArrayList<>());
        }

        for (int consumerIdx = 0; consumerIdx < n; consumerIdx++) {
            var component = components.get(consumerIdx);
            List<Integer> upstreamIndices = collectUpstreamIndices(component);
            for (int providerIdx : upstreamIndices) {
                downstreamMap.get(providerIdx).add(consumerIdx);
            }
        }
        return downstreamMap;
    }

    private List<Integer> collectUpstreamIndices(ResolvedComponent component) {
        var indices = new ArrayList<Integer>();
        if (component.declaration().condition() != null) {
            var condComponent = conditions.get(component.declaration().condition());
            int idx = components.indexOf(condComponent);
            if (idx != -1) indices.add(idx);
        }
        for (var parentConditionTag : component.parentConditions()) {
            var condComponent = conditions.get(parentConditionTag);
            int idx = components.indexOf(condComponent);
            if (idx != -1) indices.add(idx);
        }

        var interceptorsFor = interceptors.interceptorsFor(component);
        for (var interceptor : interceptorsFor) {
            int idx = components.indexOf(interceptor.component());
            if (idx != -1) {
                indices.add(idx);
            }
        }

        for (var dependency : component.dependencies()) {
            switch (dependency) {
                case ComponentDependency.SingleDependency single -> {
                    var target = switch (single) {
                        case ComponentDependency.TargetDependency t -> t.component();
                        case ComponentDependency.ValueOfDependency v -> v.component();
                        case ComponentDependency.WrappedTargetDependency w -> w.component();
                        default -> null;
                    };
                    if (target != null) {
                        int idx = components.indexOf(target);
                        if (idx != -1) indices.add(idx);
                    }
                }
                case ComponentDependency.AllOfDependency allOf -> {
                    for (var subDep : allOf.getResolvedDependencies()) {
                        int idx = components.indexOf(subDep.component());
                        if (idx != -1) indices.add(idx);
                    }
                }
                case ComponentDependency.OneOfDependency oneOf -> {
                    for (var subDep : oneOf.dependencies()) {
                        int idx = components.indexOf(subDep.component());
                        if (idx != -1) indices.add(idx);
                    }
                }
                case ComponentDependency.PromisedProxyParameterDependency promised -> {
                    if (promised.realDependency != null) {
                        int idx = components.indexOf(promised.realDependency);
                        if (idx != -1) indices.add(idx);
                    }
                }
                default -> {}
            }
        }
        return indices;
    }

    private CodeBlock getCreateDependencies(ResolvedComponent component) {
        var indexList = new ArrayList<String>();

        if (component.declaration().condition() != null) {
            var condition = Objects.requireNonNull(this.conditions.get(component.declaration().condition()));
            int idx = components.indexOf(condition);
            indexList.add(String.valueOf(idx));
        }
        for (var parentConditionTag : component.parentConditions()) {
            var condition = Objects.requireNonNull(this.conditions.get(parentConditionTag));
            int idx = components.indexOf(condition);
            indexList.add(String.valueOf(idx));
        }
        for (var dependency : component.dependencies()) {
            switch (dependency) {
                case ComponentDependency.NullDependency _, ComponentDependency.PromisedProxyParameterDependency _ -> {}
                case ComponentDependency.SingleDependency singleDependency -> {
                    switch (singleDependency) {
                        case ComponentDependency.PromiseOfDependency _ -> {}
                        case ComponentDependency.TargetDependency targetDependency when targetDependency.claim().claimType() != DependencyClaim.DependencyClaimType.NODE_OF -> {
                            int idx = components.indexOf(targetDependency.component());
                            indexList.add(String.valueOf(idx));
                        }
                        case ComponentDependency.TargetDependency _ -> {}
                        case ComponentDependency.ValueOfDependency valueOfDependency -> {
                            int idx = components.indexOf(valueOfDependency.component());
                            indexList.add(String.valueOf(idx));
                        }
                        case ComponentDependency.WrappedTargetDependency wrappedTargetDependency -> {
                            int idx = components.indexOf(wrappedTargetDependency.component());
                            indexList.add(String.valueOf(idx));
                        }
                    }
                }
                case ComponentDependency.AllOfDependency allOfDependency -> {
                    if (allOfDependency.claim().claimType() != DependencyClaim.DependencyClaimType.ALL_OF_PROMISE) {
                        for (var d : allOfDependency.getResolvedDependencies()) {
                            int idx = components.indexOf(d.component());
                            indexList.add(String.valueOf(idx));
                        }
                    }
                }
                case ComponentDependency.OneOfDependency oneOfDependency -> {
                    for (var singleDependency : oneOfDependency.dependencies()) {
                        switch (singleDependency) {
                            case ComponentDependency.PromiseOfDependency _ -> {}
                            case ComponentDependency.TargetDependency targetDependency -> {
                                int idx = components.indexOf(targetDependency.component());
                                indexList.add(String.valueOf(idx));
                            }
                            case ComponentDependency.ValueOfDependency valueOfDependency -> {
                                int idx = components.indexOf(valueOfDependency.component());
                                indexList.add(String.valueOf(idx));
                            }
                            case ComponentDependency.WrappedTargetDependency wrappedTargetDependency -> {
                                int idx = components.indexOf(wrappedTargetDependency.component());
                                indexList.add(String.valueOf(idx));
                            }
                        }
                    }
                }
                default -> {}
            }
        }

        if (indexList.isEmpty()) {
            return CodeBlock.of("null");
        }

        var b = CodeBlock.builder().add("new short[]{");
        for (int i = 0; i < indexList.size(); i++) {
            if (i > 0) b.add(", ");
            b.add("$L", indexList.get(i));
        }
        b.add("}");
        return b.build();
    }

    private CodeBlock getRefreshDependencies(ResolvedComponent component) {
        var indexList = new ArrayList<String>();

        if (component.declaration().condition() != null) {
            var condition = Objects.requireNonNull(this.conditions.get(component.declaration().condition()));
            int idx = components.indexOf(condition);
            indexList.add(String.valueOf(idx));
        }
        for (var parentConditionTag : component.parentConditions()) {
            var condition = Objects.requireNonNull(this.conditions.get(parentConditionTag));
            int idx = components.indexOf(condition);
            indexList.add(String.valueOf(idx));
        }
        for (var dependency : component.dependencies()) {
            switch (dependency) {
                case ComponentDependency.NullDependency _, ComponentDependency.PromisedProxyParameterDependency _ -> {}
                case ComponentDependency.SingleDependency singleDependency -> {
                    switch (singleDependency) {
                        case ComponentDependency.PromiseOfDependency _ -> {}
                        case ComponentDependency.TargetDependency targetDependency when targetDependency.claim().claimType() != DependencyClaim.DependencyClaimType.NODE_OF -> {
                            int idx = components.indexOf(targetDependency.component());
                            indexList.add(String.valueOf(idx));
                        }
                        case ComponentDependency.TargetDependency _ -> {}
                        case ComponentDependency.ValueOfDependency valueOfDependency -> {
                            int idx = components.indexOf(valueOfDependency.component());
                            indexList.add(String.valueOf(idx));
                        }
                        case ComponentDependency.WrappedTargetDependency wrappedTargetDependency -> {
                            int idx = components.indexOf(wrappedTargetDependency.component());
                            indexList.add(String.valueOf(idx));
                        }
                    }
                }
                case ComponentDependency.AllOfDependency allOfDependency -> {
                    var claimType = allOfDependency.claim().claimType();
                    // Исключаем как ALL_OF_PROMISE, так и ALL_OF_VALUE (коллекции ValueOf) из refresh-зависимостей
                    if (claimType != DependencyClaim.DependencyClaimType.ALL_OF_PROMISE && claimType != DependencyClaim.DependencyClaimType.ALL_OF_VALUE) {
                        for (var d : allOfDependency.getResolvedDependencies()) {
                            int idx = components.indexOf(d.component());
                            indexList.add(String.valueOf(idx));
                        }
                    }
                }
                case ComponentDependency.OneOfDependency oneOfDependency -> {
                    for (var singleDependency : oneOfDependency.dependencies()) {
                        switch (singleDependency) {
                            case ComponentDependency.PromiseOfDependency _ -> {}
                            case ComponentDependency.TargetDependency targetDependency -> {
                                int idx = components.indexOf(targetDependency.component());
                                indexList.add(String.valueOf(idx));
                            }
                            case ComponentDependency.ValueOfDependency valueOfDependency -> {
                                int idx = components.indexOf(valueOfDependency.component());
                                indexList.add(String.valueOf(idx));
                            }
                            case ComponentDependency.WrappedTargetDependency wrappedTargetDependency -> {
                                int idx = components.indexOf(wrappedTargetDependency.component());
                                indexList.add(String.valueOf(idx));
                            }
                        }
                    }
                }
                default -> {}
            }
        }
        if (indexList.isEmpty()) {
            return CodeBlock.of("null");
        }
        var b = CodeBlock.builder().add("new short[]{");
        for (int i = 0; i < indexList.size(); i++) {
            if (i > 0) b.add(", ");
            b.add("$L", indexList.get(i));
        }
        b.add("}");
        return b.build();
    }
}
