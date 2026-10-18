package io.koraframework.kora.app.annotation.processor;

import com.palantir.javapoet.*;
import io.koraframework.annotation.processor.common.*;
import io.koraframework.kora.app.annotation.processor.component.*;
import io.koraframework.kora.app.annotation.processor.declaration.*;
import io.koraframework.kora.app.annotation.processor.interceptor.*;

import javax.lang.model.element.*;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

public class GraphFileGenerator {
    private final ProcessingContext ctx;
    private final Element classElement;
    private final List<TypeElement> allModules;
    private final ComponentInterceptors interceptors;
    private final List<ResolvedComponent> components;
    private final Map<ClassName, ResolvedComponent> conditions;

    public GraphFileGenerator(ProcessingContext ctx, Element classElement, List<TypeElement> allModules, ComponentInterceptors interceptors, List<ResolvedComponent> components, Map<ClassName, ResolvedComponent> conditions) {
        this.ctx = ctx;
        this.classElement = classElement;
        this.allModules = allModules;
        this.interceptors = interceptors;
        this.components = components;
        this.conditions = conditions;
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
            .addField(CommonClassNames.applicationGraphDraw, "graphDraw", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .addMethod(MethodSpec
                .methodBuilder("get")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(CommonClassNames.applicationGraphDraw)
                .addStatement("return graphDraw")
                .build());
        var currentClass = (TypeSpec.Builder) null;
        var currentConstructor = (MethodSpec.Builder) null;
        int holders = 0;

        var typeRefClassName = ClassName.get("io.koraframework.application.graph", "TypeRef");
        HolderCache holderCache = null;

        for (int i = 0; i < components.size(); i++) {
            var componentNumber = i % KoraAppProcessor.COMPONENTS_PER_HOLDER_CLASS;
            if (componentNumber == 0) {
                if (currentClass != null) {
                    currentClass.addMethod(currentConstructor.build());
                    classBuilder.addType(currentClass.build());
                    var prevNumber = ((i / KoraAppProcessor.COMPONENTS_PER_HOLDER_CLASS) - 1);
                    classBuilder.addField(graphTypeName.nestedClass("ComponentHolder" + prevNumber), "holder" + prevNumber, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
                }
                holders++;
                var className = graphTypeName.nestedClass("ComponentHolder" + i / KoraAppProcessor.COMPONENTS_PER_HOLDER_CLASS);
                currentClass = TypeSpec.classBuilder(className)
                    .addAnnotation(AnnotationUtils.generated(KoraAppProcessor.class))
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
                currentConstructor = MethodSpec.constructorBuilder()
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(CommonClassNames.applicationGraphDraw, "graphDraw")
                    .addParameter(implClass, "impl");
                for (int j = 0; j < i / KoraAppProcessor.COMPONENTS_PER_HOLDER_CLASS; j++) {
                    currentConstructor.addParameter(graphTypeName.nestedClass("ComponentHolder" + j), "ComponentHolder" + j);
                }
                holderCache = this.buildHolderCache(typeRefClassName, components, i);
                for (var entry : holderCache.types.values()) {
                    if (entry.assignedIndex != null) {
                        currentConstructor.addStatement("var type_$L = $L", entry.assignedIndex, entry.value);
                    }
                }

                for (var entry : holderCache.tags.values()) {
                    if (entry.assignedIndex != null) {
                        String tagStr = entry.value;
                        String[] parts = tagStr.split("\\.");
                        int firstClassIdx = -1;
                        for (int p = 0; p < parts.length; p++) {
                            if (!parts[p].isEmpty() && Character.isUpperCase(parts[p].charAt(0))) {
                                firstClassIdx = p;
                                break;
                            }
                        }
                        ClassName tagClassName;
                        if (firstClassIdx != -1 && firstClassIdx > 0) {
                            String packageName = String.join(".", Arrays.copyOfRange(parts, 0, firstClassIdx));
                            String outerClassName = parts[firstClassIdx];
                            String[] nestedClasses = Arrays.copyOfRange(parts, firstClassIdx + 1, parts.length);
                            tagClassName = ClassName.get(packageName, outerClassName, nestedClasses);
                        } else {
                            tagClassName = ClassName.bestGuess(tagStr);
                        }
                        currentConstructor.addStatement("var tag_$L = $T.class", entry.assignedIndex, tagClassName);
                    }
                }
            }

            var component = components.get(i);
            TypeName componentTypeName = TypeName.get(component.type()).box();
            var typeMirrorElement = ctx.types.asElement(component.type());
            if (typeMirrorElement instanceof TypeElement te) {
                var annotation = AnnotationUtils.findAnnotation(typeMirrorElement, CommonClassNames.aopProxy);
                if (annotation != null) {
                    var superElement = ctx.types.asElement(te.getSuperclass());
                    var aopProxyName = NameUtils.generatedType(superElement, "_AopProxy");
                    if (typeMirrorElement.getSimpleName().contentEquals(aopProxyName)) {
                        componentTypeName = TypeName.get(te.getSuperclass()).box();
                    }
                }
            }

            currentClass.addField(FieldSpec.builder(ParameterizedTypeName.get(CommonClassNames.node, componentTypeName), component.fieldName(), Modifier.PRIVATE, Modifier.FINAL).build());
            CodeBlock currentComponentTypeCode = buildTypeInitializer(typeRefClassName, componentTypeName);
            var typeEntry = holderCache.types.get(currentComponentTypeCode.toString());

            String typeExpression;
            if (typeEntry.assignedIndex != null) {
                typeExpression = "type_" + typeEntry.assignedIndex;
            } else {
                typeExpression = currentComponentTypeCode.toString();
            }

            var statement = generateComponentStatement(graphTypeName, component, holderCache, typeExpression);
            currentConstructor.addStatement(statement);
        }
        if (!components.isEmpty()) {
            var lastComponentNumber = components.size() / KoraAppProcessor.COMPONENTS_PER_HOLDER_CLASS;
            if (components.size() % KoraAppProcessor.COMPONENTS_PER_HOLDER_CLASS == 0) {
                lastComponentNumber--;
            }
            currentClass.addMethod(currentConstructor.build());
            classBuilder.addType(currentClass.build());
            classBuilder.addField(graphTypeName.nestedClass("ComponentHolder" + lastComponentNumber), "holder" + lastComponentNumber, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
        }

        var staticBlock = CodeBlock.builder();
        staticBlock
            .addStatement("var impl = new $T()", implClass)
            .addStatement("graphDraw = new $T($T.class)", CommonClassNames.applicationGraphDraw, classElement);
        for (int i = 0; i < holders; i++) {
            var args = new ArrayList<>();
            var format = new StringBuilder("$N = new $T(graphDraw, impl");
            args.add("holder" + i);
            args.add(graphTypeName.nestedClass("ComponentHolder" + i));

            for (int j = 0; j < i; j++) {
                format.append(", $N");
                args.add("holder" + j);
            }
            format.append(")");
            staticBlock.addStatement(format.toString(), args.toArray());
        }

        var supplierMethodBuilder = MethodSpec.methodBuilder("graph")
            .returns(CommonClassNames.applicationGraphDraw)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addStatement("return graphDraw");

        return JavaFile.builder(packageElement.getQualifiedName().toString(), classBuilder
                .addMethod(supplierMethodBuilder.build())
                .addStaticBlock(staticBlock.build())
                .build())
            .build();
    }
    private static final class CacheEntry<T> {
        final T value;
        int count = 1;
        Integer assignedIndex = null;

        CacheEntry(T value) { this.value = value; }
    }

    private static final class HolderCache {
        final Map<String, CacheEntry<CodeBlock>> types = new LinkedHashMap<>();
        final Map<String, CacheEntry<String>> tags = new LinkedHashMap<>();
    }

    private HolderCache buildHolderCache(ClassName typeRefClassName, List<ResolvedComponent> components, int startIndex) {
        var cache = new HolderCache();
        int limit = Math.min(components.size(), startIndex + KoraAppProcessor.COMPONENTS_PER_HOLDER_CLASS);

        for (int k = startIndex; k < limit; k++) {
            var comp = components.get(k);
            TypeName compTypeName = TypeName.get(comp.type()).box();

            var mirrorElement = ctx.types.asElement(comp.type());
            if (mirrorElement instanceof TypeElement te) {
                var annotation = AnnotationUtils.findAnnotation(mirrorElement, CommonClassNames.aopProxy);
                if (annotation != null) {
                    var superElement = ctx.types.asElement(te.getSuperclass());
                    var aopProxyName = NameUtils.generatedType(superElement, "_AopProxy");
                    if (mirrorElement.getSimpleName().contentEquals(aopProxyName)) {
                        compTypeName = TypeName.get(te.getSuperclass()).box();
                    }
                }
            }

            CodeBlock typeCode = buildTypeInitializer(typeRefClassName, compTypeName);
            String typeKey = typeCode.toString();
            var typeEntry = cache.types.get(typeKey);
            if (typeEntry == null) {
                cache.types.put(typeKey, new CacheEntry<>(typeCode));
            } else {
                typeEntry.count++;
            }

            if (comp.tag() != null) {
                String tagKey = comp.tag();
                var tagEntry = cache.tags.get(tagKey);
                if (tagEntry == null) {
                    cache.tags.put(tagKey, new CacheEntry<>(tagKey));
                } else {
                    tagEntry.count++;
                }
            }
        }

        int typeIdx = 0;
        for (var entry : cache.types.values()) {
            if (entry.count > 1) {
                entry.assignedIndex = typeIdx++;
            }
        }

        int tagIdx = 0;
        for (var entry : cache.tags.values()) {
            if (entry.count > 1) {
                entry.assignedIndex = tagIdx++;
            }
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

    private CodeBlock parentCondition(ResolvedComponent component) {
        if (component.parentConditions().size() == 1) {
            var conditionComponent = Objects.requireNonNull(conditions.get(component.parentConditions().iterator().next()));
            return CodeBlock.of("g.condition($L)", conditionComponent.nodeRef("_"));
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
                b.add("g.condition($L)", conditionComponent.nodeRef("_"));
            }
            b.add(")");
            return b.build();
        }
    }

    private CodeBlock generateComponentStatement(ClassName graphTypeName, ResolvedComponent component, HolderCache holderCache, String typeExpression) {
        var statement = CodeBlock.builder();
        var declaration = component.declaration();
        var componentHolder = component.holderName();
        var componentField = component.fieldName();
        statement.add("$L = graphDraw.addNode($L, ", componentField, typeExpression);
        if (component.tag() == null) {
            statement.add("null, \n");
        } else {
            var tagEntry = holderCache.tags.get(component.tag());
            if (tagEntry.assignedIndex != null) {
                statement.add("tag_$L, \n", tagEntry.assignedIndex);
            } else {
                statement.add("$L.class, \n", component.tag());
            }
        }
        if (component.parentConditions().isEmpty() && component.declaration().condition() == null) {
            statement.add("null,\n");
        } else if (component.parentConditions().isEmpty()) {
            var conditionComponent = Objects.requireNonNull(conditions.get(component.declaration().condition()));
            statement.add("g -> g.condition($L).eval(),\n", conditionComponent.nodeRef("_"));
        } else if (component.declaration().condition() == null) {
            statement.add("g -> $L.eval(),\n", parentCondition(component));
        } else {
            var conditionComponent = Objects.requireNonNull(conditions.get(component.declaration().condition()));
            statement.add("g -> $T.and($L, g.condition($L)).eval(),\n", CommonClassNames.graphCondition, parentCondition(component), conditionComponent.nodeRef("_"));
        }

        var createDependencies = this.getCreateDependencies(componentHolder, component);
        statement.add("$L,\n", createDependencies);

        var refreshDependencies = this.getRefreshDependencies(componentHolder, component);
        statement.add("$L,\n", refreshDependencies);

        var interceptorsFor = interceptors.interceptorsFor(component);
        statement.add("$T.of(", List.class);
        for (int i = 0; i < interceptorsFor.size(); i++) {
            if (i > 0) {
                statement.add(", ");
            }
            var interceptor = interceptorsFor.get(i);
            statement.add("$L", interceptor.component().nodeRef(componentHolder));
        }
        statement.add("),\n");

        statement.add("g -> ");
        var dependenciesCode = this.generateDependenciesCode(ctx, component, graphTypeName, 0);

        switch (declaration) {
            case ComponentDeclaration.AnnotatedComponent annotatedComponent -> {
                statement.add("new $T", ClassName.get(annotatedComponent.typeElement()));
                if (!annotatedComponent.typeVariables().isEmpty()) {
                    statement.add("<");
                    for (int i = 0; i < annotatedComponent.typeVariables().size(); i++) {
                        if (i > 0) statement.add(", ");
                        statement.add("$T", annotatedComponent.typeVariables().get(i));
                    }
                    statement.add(">");
                }
                statement.add("($L)", dependenciesCode);
            }
            case ComponentDeclaration.FromModuleComponent moduleComponent -> {
                if (moduleComponent.module() instanceof ModuleDeclaration.ClassModule || moduleComponent.module() instanceof ModuleDeclaration.FactoryModule) {
                    dependenciesCode = this.generateDependenciesCode(ctx, component, graphTypeName, 1);
                    var moduleInstDep = component.dependencies().getFirst();
                    statement.add("$L.", moduleInstDep.write(ctx, graphTypeName));
                } else {
                    if (moduleComponent.module() instanceof ModuleDeclaration.AnnotatedModule(var element)) {
                        statement.add("impl.module$L.", allModules.indexOf(element));
                    } else {
                        statement.add("impl.");
                    }
                }
                if (!moduleComponent.typeVariables().isEmpty()) {
                    statement.add("<");
                    for (int i = 0; i < moduleComponent.typeVariables().size(); i++) {
                        if (i > 0) statement.add(", ");
                        statement.add("$T", moduleComponent.typeVariables().get(i));
                    }
                    statement.add(">");
                }
                statement.add("$L($L)", moduleComponent.method().getSimpleName(), dependenciesCode);
            }
            case ComponentDeclaration.FromExtensionComponent extension -> statement.add(extension.generator().apply(dependenciesCode));
            case ComponentDeclaration.PromisedProxyComponent promisedProxyComponent -> {
                if (promisedProxyComponent.typeElement().getTypeParameters().isEmpty()) {
                    statement.add("new $T($L)", promisedProxyComponent.className(), dependenciesCode);
                } else {
                    statement.add("new $T<>($L)", promisedProxyComponent.className(), dependenciesCode);
                }
            }
            case ComponentDeclaration.OptionalComponent optional -> {
                var optionalOf = ((DeclaredType) optional.type()).getTypeArguments().getFirst();
                statement.add("$T.<$T>ofNullable($L)", Optional.class, optionalOf, dependenciesCode);
            }
            case null, default -> throw new IllegalStateException("Kora internal error: graph generator got unsupported component declaration: " + declaration);
        }
        statement.add(")");
        return statement.build();

    }

    private CodeBlock generateDependenciesCode(ProcessingContext ctx, ResolvedComponent component, ClassName graphTypeName, int startFrom) {
        var resolvedDependencies = component.dependencies();
        if (resolvedDependencies.isEmpty()) {
            return CodeBlock.of("");
        }
        var b = CodeBlock.builder();
        b.indent();
        b.add("\n");

        var localGraphTypeName = ClassName.get("", graphTypeName.simpleName());

        for (int i = startFrom, dependenciesSize = resolvedDependencies.size(); i < dependenciesSize; i++) {
            if (i > startFrom) b.add(",\n");
            var resolvedDependency = resolvedDependencies.get(i);

            var depCode = resolvedDependency.write(ctx, localGraphTypeName);
            var depStr = depCode.toString();

            var prefixToRemove = graphTypeName.simpleName() + ".";
            if (depStr.contains(prefixToRemove)) {
                depStr = depStr.replace(prefixToRemove, "");
            }
            if (depStr.startsWith(".")) {
                depStr = depStr.substring(1);
            }

            b.add("$L", depStr);
        }
        b.unindent();
        b.add("\n");
        return b.build();
    }

    private CodeBlock getCreateDependencies(String componentHolder, ResolvedComponent component) {
        var b = CodeBlock.builder();
        b.add("$T.of(", List.class);
        boolean first = true;

        if (component.declaration().condition() != null) {
            var condition = Objects.requireNonNull(this.conditions.get(component.declaration().condition()));
            b.add("$L", condition.nodeRef(componentHolder));
            first = false;
        }
        for (var parentConditionTag : component.parentConditions()) {
            if (!first) b.add(", ");
            var condition = Objects.requireNonNull(this.conditions.get(parentConditionTag));
            b.add("$L", condition.nodeRef(componentHolder));
            first = false;
        }
        for (var dependency : component.dependencies()) {
            switch (dependency) {
                case ComponentDependency.NullDependency _, ComponentDependency.PromisedProxyParameterDependency _ -> {}
                case ComponentDependency.SingleDependency singleDependency -> {
                    switch (singleDependency) {
                        case ComponentDependency.PromiseOfDependency _ -> {}
                        case ComponentDependency.TargetDependency targetDependency when targetDependency.claim().claimType() != DependencyClaim.DependencyClaimType.NODE_OF -> {
                            if (!first) b.add(", ");
                            b.add("$L", targetDependency.component().nodeRef(componentHolder));
                            first = false;
                        }
                        case ComponentDependency.TargetDependency _ -> {}
                        case ComponentDependency.ValueOfDependency valueOfDependency -> {
                            if (!first) b.add(", ");
                            b.add("$L", valueOfDependency.component().nodeRef(componentHolder));
                            first = false;
                        }
                        case ComponentDependency.WrappedTargetDependency wrappedTargetDependency -> {
                            if (!first) b.add(", ");
                            b.add("$L", wrappedTargetDependency.component().nodeRef(componentHolder));
                            first = false;
                        }
                    }
                }
                case ComponentDependency.AllOfDependency allOfDependency -> {
                    if (allOfDependency.claim().claimType() != DependencyClaim.DependencyClaimType.ALL_OF_PROMISE) {
                        for (var d : allOfDependency.getResolvedDependencies()) {
                            if (!first) b.add(", ");
                            b.add("$L", d.component().nodeRef(componentHolder));
                            first = false;
                        }
                    }
                }
                case ComponentDependency.OneOfDependency oneOfDependency -> {
                    for (var singleDependency : oneOfDependency.dependencies()) {
                        switch (singleDependency) {
                            case ComponentDependency.PromiseOfDependency _ -> {}
                            case ComponentDependency.TargetDependency targetDependency -> {
                                if (!first) b.add(", ");
                                b.add("$L", targetDependency.component().nodeRef(componentHolder));
                                first = false;
                            }
                            case ComponentDependency.ValueOfDependency valueOfDependency -> {
                                if (!first) b.add(", ");
                                b.add("$L", valueOfDependency.component().nodeRef(componentHolder));
                                first = false;
                            }
                            case ComponentDependency.WrappedTargetDependency wrappedTargetDependency -> {
                                if (!first) b.add(", ");
                                b.add("$L", wrappedTargetDependency.component().nodeRef(componentHolder));
                                first = false;
                            }
                        }
                    }
                }
                case ComponentDependency.GraphDependency _, ComponentDependency.TypeOfDependency _ -> {}
            }
        }
        b.add(")");
        return b.build();
    }

    private CodeBlock getRefreshDependencies(String componentHolder, ResolvedComponent component) {
        var b = CodeBlock.builder();
        b.add("$T.of(", List.class);
        boolean first = true;

        if (component.declaration().condition() != null) {
            var condition = Objects.requireNonNull(this.conditions.get(component.declaration().condition()));
            b.add("$L", condition.nodeRef(componentHolder));
            first = false;
        }
        for (var parentConditionTag : component.parentConditions()) {
            if (!first) b.add(", ");
            var condition = Objects.requireNonNull(this.conditions.get(parentConditionTag));
            b.add("$L", condition.nodeRef(componentHolder));
            first = false;
        }
        for (var dependency : component.dependencies()) {
            switch (dependency) {
                case ComponentDependency.NullDependency _, ComponentDependency.PromisedProxyParameterDependency _ -> {}
                case ComponentDependency.SingleDependency singleDependency -> {
                    switch (singleDependency) {
                        case ComponentDependency.PromiseOfDependency _, ComponentDependency.ValueOfDependency _ -> {}
                        case ComponentDependency.TargetDependency targetDependency when targetDependency.claim().claimType() != DependencyClaim.DependencyClaimType.NODE_OF -> {
                            if (!first) b.add(", ");
                            b.add("$L", targetDependency.component().nodeRef(componentHolder));
                            first = false;
                        }
                        case ComponentDependency.TargetDependency _ -> {}
                        case ComponentDependency.WrappedTargetDependency wrappedTargetDependency -> {
                            if (!first) b.add(", ");
                            b.add("$L", wrappedTargetDependency.component().nodeRef(componentHolder));
                            first = false;
                        }
                    }
                }
                case ComponentDependency.AllOfDependency allOfDependency -> {
                    if (allOfDependency.claim().claimType() == DependencyClaim.DependencyClaimType.ALL_OF_ONE) {
                        for (var resolved : allOfDependency.getResolvedDependencies()) {
                            if (!first) b.add(", ");
                            b.add("$L", Objects.requireNonNull(resolved.component()).nodeRef(componentHolder));
                            first = false;
                        }
                    }
                }
                case ComponentDependency.OneOfDependency oneOfDependency -> {
                    for (var singleDependency : oneOfDependency.dependencies()) {
                        switch (singleDependency) {
                            case ComponentDependency.PromiseOfDependency _ -> {}
                            case ComponentDependency.TargetDependency targetDependency -> {
                                if (!first) b.add(", ");
                                b.add("$L", targetDependency.component().nodeRef(componentHolder));
                                first = false;
                            }
                            case ComponentDependency.ValueOfDependency valueOfDependency -> {
                                if (!first) b.add(", ");
                                b.add("$L", valueOfDependency.component().nodeRef(componentHolder));
                                first = false;
                            }
                            case ComponentDependency.WrappedTargetDependency wrappedTargetDependency -> {
                                if (!first) b.add(", ");
                                b.add("$L", wrappedTargetDependency.component().nodeRef(componentHolder));
                                first = false;
                            }
                        }
                    }
                }
                case ComponentDependency.GraphDependency _, ComponentDependency.TypeOfDependency _ -> {}
            }
        }
        b.add(")");
        return b.build();
    }
}
