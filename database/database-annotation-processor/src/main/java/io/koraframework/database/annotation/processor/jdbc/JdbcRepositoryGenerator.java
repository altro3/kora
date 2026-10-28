package io.koraframework.database.annotation.processor.jdbc;

import com.palantir.javapoet.*;
import io.koraframework.annotation.processor.common.*;
import io.koraframework.database.annotation.processor.*;
import io.koraframework.database.annotation.processor.DbUtils.*;
import io.koraframework.database.annotation.processor.model.*;
import org.jspecify.annotations.*;

import javax.annotation.processing.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.*;
import javax.lang.model.util.Types;
import java.sql.*;
import java.util.*;
import java.util.stream.*;

public final class JdbcRepositoryGenerator implements RepositoryGenerator {
    private final Types types;
    private final Elements elements;
    private final Filer filer;

    public JdbcRepositoryGenerator(ProcessingEnvironment processingEnv) {
        this.types = processingEnv.getTypeUtils();
        this.elements = processingEnv.getElementUtils();
        this.filer = processingEnv.getFiler();
    }

    @Override
    public TypeSpec generate(TypeElement repositoryElement, TypeSpec.Builder type, MethodSpec.Builder constructor) {
        var repositoryType = (DeclaredType) repositoryElement.asType();
        var queryMethods = DbUtils.findQueryMethods(this.types, this.elements, repositoryElement);
        this.enrichWithExecutor(repositoryElement, type, constructor);
        var resultMappers = new FieldFactory(this.types, elements, type, constructor, "_result_mapper_");
        var parameterMappers = new FieldFactory(this.types, elements, type, constructor, "_parameter_mapper_");

        int methodCounter = 1;
        for (var method : queryMethods) {
            var methodType = (ExecutableType) this.types.asMemberOf(repositoryType, method);
            var parameters = QueryParameterParser.parse(this.types, JdbcTypes.CONNECTION, JdbcTypes.PARAMETER_COLUMN_MAPPER, method, methodType);
            var queryAnnotation = AnnotationUtils.findAnnotation(method, DbUtils.QUERY_ANNOTATION);
            var queryString = AnnotationUtils.<String>parseAnnotationValueWithoutDefault(queryAnnotation, "value");
            var query = QueryWithParameters.parse(filer, types, queryString, parameters, repositoryType, method);
            var resultMapper = this.parseResultMapper(method, methodType, parameters)
                .map(rm -> DbUtils.addMapper(resultMappers, rm))
                .orElse(null);

            DbUtils.addMappers(parameterMappers, DbUtils.parseParameterMappers(
                parameters,
                query,
                tn -> JdbcNativeTypes.findNativeType(tn) != null,
                JdbcTypes.PARAMETER_COLUMN_MAPPER
            ));
            var methodSpec = this.generate(repositoryElement, type, methodCounter, method, methodType, query, parameters, resultMapper, parameterMappers);
            type.addMethod(methodSpec);
            methodCounter++;
        }
        return type.addMethod(constructor.build()).build();
    }

    private Optional<Mapper> parseResultMapper(ExecutableElement method, ExecutableType methodType, List<QueryParameter> parameters) {
        var returnType = methodType.getReturnType();
        if (CommonUtils.isVoid(returnType)) {
            return Optional.empty();
        }
        if (returnType.toString().equals(DbUtils.UPDATE_COUNT.canonicalName())) {
            return Optional.empty();
        }

        var batchParam = parameters.stream().filter(QueryParameter.BatchParameter.class::isInstance).findFirst().orElse(null);
        var generatedKeys = AnnotationUtils.isAnnotationPresent(method, DbUtils.ID_ANNOTATION);
        if (batchParam != null && !generatedKeys) {
            // either void or update count, no way to parse results from db with jdbc api
            if (ArrayTypeName.of(int.class).equals(TypeName.get(returnType))) {
                return Optional.empty();
            } else if (ArrayTypeName.of(long.class).equals(TypeName.get(returnType))) {
                return Optional.empty();
            } else {
                throw new ProcessingErrorException(batchReturnTypeError(method, returnType), method);
            }
        }
        var mappings = CommonUtils.parseMapping(method);
        var mapperType = ParameterizedTypeName.get(JdbcTypes.RESULT_SET_MAPPER, TypeName.get(returnType).box());
        var resultSetMapper = mappings.getMapping(JdbcTypes.RESULT_SET_MAPPER);
        if (resultSetMapper != null) {
            return Optional.of(new Mapper(resultSetMapper.mapperClass(), mapperType, mappings.tag()));
        }

        var rowMapper = mappings.getMapping(JdbcTypes.ROW_MAPPER);
        if (rowMapper != null) {
            if (CommonUtils.isList(returnType)) {
                return Optional.of(new Mapper(rowMapper.mapperClass(), mapperType, mappings.tag(), c -> CodeBlock.of("$T.listResultSetMapper($L)", JdbcTypes.RESULT_SET_MAPPER, c)));
            } else if (CommonUtils.isOptional(returnType)) {
                return Optional.of(new Mapper(rowMapper.mapperClass(), mapperType, mappings.tag(), c -> CodeBlock.of("$T.optionalResultSetMapper($L)", JdbcTypes.RESULT_SET_MAPPER, c)));
            } else {
                return Optional.of(new Mapper(rowMapper.mapperClass(), mapperType, mappings.tag(), c -> CodeBlock.of("$T.singleResultSetMapper($L)", JdbcTypes.RESULT_SET_MAPPER, c)));
            }
        }
        return Optional.of(new Mapper(mapperType, mappings.tag()));
    }

    @Override
    public ClassName repositoryInterface() {
        return JdbcTypes.JDBC_REPOSITORY;
    }

    private record QueryReplace(int start, int end, String name) {}

    public MethodSpec generate(TypeElement repositoryElement, TypeSpec.Builder type, int methodNumber, ExecutableElement method, ExecutableType methodType, QueryWithParameters query, List<QueryParameter> parameters, @Nullable String resultMapperName, FieldFactory parameterMappers) {
        var batchParam = parameters.stream().filter(QueryParameter.BatchParameter.class::isInstance).findFirst().orElse(null);
        var sql = query.rawQuery();
        List<QueryReplace> replaceParams = query.parameters().stream()
            .flatMap(p -> p.queryIndexes().stream().map(i -> new QueryReplace(i.start(), i.end(), p.sqlParameterName())))
            .sorted(Comparator.comparingInt(QueryReplace::start))
            .toList();
        int sqlIndexDiff = 0;
        for (var parameter : replaceParams) {
            int queryIndexAdjusted = parameter.start() - sqlIndexDiff;
            sql = sql.substring(0, queryIndexAdjusted) + "?" + sql.substring(queryIndexAdjusted + parameter.name().length() + 1);
            sqlIndexDiff += parameter.name().length();
        }

        var mb = DbUtils.queryMethodBuilder(method, methodType);
        var returnType = methodType.getReturnType();
        var connection = parameters.stream().filter(QueryParameter.ConnectionParameter.class::isInstance).findFirst()
            .map(p -> CodeBlock.of("$L", p.variable()))
            .orElse(CodeBlock.of("this._jdbcExecutor.currentConnection()"));

        var queryContextFieldName = "QUERY_CONTEXT_" + methodNumber;
        type.addField(
            FieldSpec.builder(DbUtils.QUERY_CONTEXT, queryContextFieldName, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("""
                    new $T(
                        $S,
                        $S,
                        $S
                    )""", DbUtils.QUERY_CONTEXT, query.rawQuery(), sql, DbUtils.operationName(method))
                .build());

        mb.addStatement("var _query = $L", queryContextFieldName);
        mb.addStatement("var _observation = this._jdbcExecutor.telemetry().observe(_query)");

        boolean isVoid = methodType.getReturnType().getKind() == TypeKind.VOID;
        String invokerClassName = "_" + method.getSimpleName() + "_Invoker_" + methodNumber;

        String packageName = elements.getPackageOf(repositoryElement).getQualifiedName().toString();
        String simpleName = repositoryElement.getSimpleName().toString();
        ClassName implClassName = ClassName.get(packageName, "$" + simpleName + "_Impl");

        var observationClassName = ClassName.get("io.koraframework.common.telemetry", "Observation");
        var opentelemetryContextClassName = ClassName.get("io.koraframework.common.telemetry", "OpentelemetryContext");
        var contextClassName = ClassName.get("io.opentelemetry.context", "Context");

        var callableOpInterface = ParameterizedTypeName.get(
            ClassName.get("java.lang.ScopedValue", "CallableOp"),
            TypeName.get(returnType).box(),
            ClassName.get(RuntimeException.class)
        );

        var invokerTypeBuilder = TypeSpec.classBuilder(invokerClassName)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .addSuperinterface(isVoid
                ? ClassName.get(Runnable.class)
                : callableOpInterface);

        invokerTypeBuilder.addField(implClassName, "repo", Modifier.PRIVATE, Modifier.FINAL);
        invokerTypeBuilder.addField(DbUtils.QUERY_CONTEXT, "_query", Modifier.PRIVATE, Modifier.FINAL);
        invokerTypeBuilder.addField(observationClassName, "_observation", Modifier.PRIVATE, Modifier.FINAL);

        for (var param : method.getParameters()) {
            invokerTypeBuilder.addField(TypeName.get(param.asType()), param.getSimpleName().toString(), Modifier.PRIVATE, Modifier.FINAL);
        }

        var constructorInvoker = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(implClassName, "repo")
            .addParameter(DbUtils.QUERY_CONTEXT, "_query")
            .addParameter(observationClassName, "_observation");

        constructorInvoker.addStatement("this.repo = repo");
        constructorInvoker.addStatement("this._query = _query");
        constructorInvoker.addStatement("this._observation = _observation");

        for (var param : method.getParameters()) {
            String pName = param.getSimpleName().toString();
            constructorInvoker.addParameter(TypeName.get(param.asType()), pName);
            constructorInvoker.addStatement("this.$L = $L", pName, pName);
        }
        invokerTypeBuilder.addMethod(constructorInvoker.build());

        var execMethodBuilder = MethodSpec.methodBuilder(isVoid ? "run" : "call")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(Override.class)
            .returns(isVoid ? TypeName.VOID : TypeName.get(returnType).box());

        if (!isVoid) {
            execMethodBuilder.addException(RuntimeException.class);
        }

        var eb = execMethodBuilder;

        String repoConnection = connection.toString().contains("this._jdbcExecutor")
            ? "this.repo._jdbcExecutor.currentConnection()"
            : connection.toString();

        eb.addCode("""
            var _conToUse = $L;
            $T _conToClose;
            if (_conToUse == null) {
                _conToUse = this.repo._jdbcExecutor.acquireConnection();
                _conToClose = _conToUse;
            } else {
                _conToClose = null;
            }
            """, repoConnection, JdbcTypes.CONNECTION);

        var generatedKeys = AnnotationUtils.isAnnotationPresent(method, DbUtils.ID_ANNOTATION);
        if (generatedKeys) {
            eb.beginControlFlow("try (_conToClose; var _stmt = _conToUse.prepareStatement(_query.sql(), $T.RETURN_GENERATED_KEYS))", Statement.class);
        } else {
            eb.beginControlFlow("try (_conToClose; var _stmt = _conToUse.prepareStatement(_query.sql()))");
        }

        eb.addCode(StatementSetterGenerator.generate(method, query, parameters, batchParam, parameterMappers));

        String repoMapperName = resultMapperName != null ? "this.repo." + resultMapperName : null;

        if (MethodUtils.isVoid(method)) {
            if (batchParam != null) {
                eb.addStatement("_stmt.executeBatch()");
            } else {
                eb.addStatement("_stmt.execute()");
            }
        } else if (batchParam != null) {
            if (returnType.toString().equals(DbUtils.UPDATE_COUNT.canonicalName())) {
                eb.addStatement("var _batchResult = _stmt.executeLargeBatch()");
                eb.addStatement("return new $T($T.of(_batchResult).sum())", DbUtils.UPDATE_COUNT, LongStream.class);
            } else if (returnType.toString().equals("long[]")) {
                eb.addStatement("var _batchResult = _stmt.executeLargeBatch()");
                eb.addStatement("return _batchResult");
            } else if (returnType.toString().equals("int[]")) {
                eb.addStatement("var _batchResult = _stmt.executeBatch()");
                eb.addStatement("return _batchResult");
            } else if (generatedKeys) {
                var result = CommonUtils.isNullable(method) || method.getReturnType().getKind().isPrimitive()
                    ? CodeBlock.of("_result")
                    : CodeBlock.of("$T.requireNonNull(_result, $S)", Objects.class, "Result mapping is expected non-null, but was null");

                eb.addStatement("var _batchResult = _stmt.executeBatch()");
                eb.beginControlFlow("try (var _rs = _stmt.getGeneratedKeys())")
                    .addStatement("var _result = $L.apply(_rs)", repoMapperName)
                    .addStatement("return $L", result)
                    .endControlFlow();
            } else {
                eb.addStatement("var _batchResult = _stmt.executeBatch()");
            }
        } else if (returnType.toString().equals(DbUtils.UPDATE_COUNT.canonicalName())) {
            eb.addStatement("var _updateCount = _stmt.executeLargeUpdate()")
                .addStatement("return new $T(_updateCount)", DbUtils.UPDATE_COUNT);
        } else if (generatedKeys) {
            var result = CommonUtils.isNullable(method) || method.getReturnType().getKind().isPrimitive()
                ? CodeBlock.of("_result")
                : CodeBlock.of("$T.requireNonNull(_result, $S)", Objects.class, "Result mapping is expected non-null, but was null");

            eb.addStatement("_stmt.execute()");
            eb.beginControlFlow("try (var _rs = _stmt.getGeneratedKeys())")
                .addStatement("var _result = $L.apply(_rs)", repoMapperName)
                .addStatement("return $L", result)
                .endControlFlow();
        } else {
            var result = CommonUtils.isNullable(method) || method.getReturnType().getKind().isPrimitive()
                ? CodeBlock.of("_result")
                : CodeBlock.of("$T.requireNonNull(_result, $S)", Objects.class, "Result mapping is expected non-null, but was null");

            Objects.requireNonNull(repoMapperName, () -> "Illegal State occurred when expected to get result mapper, but got null in " + method.getEnclosingElement().getSimpleName() + "#" + method.getSimpleName());

            eb.beginControlFlow("try (var _rs = _stmt.executeQuery())")
                .addStatement("var _result = $L.apply(_rs)", repoMapperName)
                .addStatement("return $L", result)
                .endControlFlow();
        }

        eb.nextControlFlow("catch (java.sql.SQLException e)")
            .addStatement("_observation.observeError(e)")
            .addStatement("throw new io.koraframework.database.jdbc.exception.UncheckedSqlException(e)");
        eb.nextControlFlow("catch (Exception e)")
            .addStatement("_observation.observeError(e)")
            .addStatement("throw e");
        eb.nextControlFlow("finally")
            .addStatement("_observation.end()")
            .endControlFlow();

        invokerTypeBuilder.addMethod(eb.build());
        type.addType(invokerTypeBuilder.build());

        StringBuilder constructorArgs = new StringBuilder("this, _query, _observation");
        for (var param : method.getParameters()) {
            constructorArgs.append(", ").append(param.getSimpleName());
        }

        if (isVoid) {
            mb.addStatement("""
                    $T.where($T.VALUE, _observation)
                        .where($T.VALUE, $T.current().with(_observation.span()))
                        .run(new $L($L))""",
                ScopedValue.class, observationClassName, opentelemetryContextClassName, contextClassName, invokerClassName, constructorArgs.toString());
        } else {
            mb.addStatement("""
                    return $T.where($T.VALUE, _observation)
                        .where($T.VALUE, $T.current().with(_observation.span()))
                        .call(new $L($L))""",
                ScopedValue.class, observationClassName, opentelemetryContextClassName, contextClassName, invokerClassName, constructorArgs.toString());
        }
        return mb.build();
    }

    public void enrichWithExecutor(TypeElement repositoryElement, TypeSpec.Builder builder, MethodSpec.Builder constructorBuilder) {
        builder.addField(JdbcTypes.CONNECTION_FACTORY, "_jdbcExecutor", Modifier.PRIVATE, Modifier.FINAL);
        builder.addSuperinterface(JdbcTypes.JDBC_REPOSITORY);
        builder.addMethod(MethodSpec.methodBuilder("executor")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addAnnotation(Override.class)
            .returns(JdbcTypes.CONNECTION_FACTORY)
            .addCode("return this._jdbcExecutor;")
            .build());

        var executorTag = DbUtils.getTag(repositoryElement);
        if (executorTag != null) {
            constructorBuilder.addParameter(ParameterSpec.builder(JdbcTypes.CONNECTION_FACTORY, "_jdbcExecutor").addAnnotation(executorTag).build());
        } else {
            constructorBuilder.addParameter(JdbcTypes.CONNECTION_FACTORY, "_jdbcExecutor");
        }
        constructorBuilder.addStatement("this._jdbcExecutor = _jdbcExecutor");
    }

    private static String batchReturnTypeError(ExecutableElement method, TypeMirror returnType) {
        return """
            Invalid JDBC `@Batch` repository method return type: `%s`.
            
            Method `%s#%s` has a batch parameter, but JDBC batch execution cannot map arbitrary result rows.
            Supported return types without generated keys: `void`, `UpdateCount`, `int[]`, or `long[]`.
            Returning generated keys is supported only when the method is annotated with `@Id`.
            
            Fix: change the return type to one of the supported batch result types, or add `@Id` when the query returns database-generated ids.
            """.formatted(returnType, method.getEnclosingElement().getSimpleName(), method.getSimpleName());
    }
}
