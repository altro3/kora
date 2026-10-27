package io.koraframework.json.annotation.processor.codec;

import com.palantir.javapoet.*;
import io.koraframework.annotation.processor.common.*;
import io.koraframework.json.annotation.processor.*;
import io.koraframework.json.annotation.processor.reader.*;
import io.koraframework.json.annotation.processor.reader.ReaderFieldType.*;
import io.koraframework.json.annotation.processor.writer.*;
import org.jspecify.annotations.*;

import javax.annotation.processing.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.*;
import java.util.*;

public class JsonCodecGenerator {
    private final Types types;

    public JsonCodecGenerator(ProcessingEnvironment processingEnvironment) {
        this.types = processingEnvironment.getTypeUtils();
    }

    @Nullable
    public TypeSpec generate(JsonClassReaderMeta readerMeta, JsonClassWriterMeta writerMeta) {
        var typeElement = readerMeta.typeElement();
        var targetType = TypeName.get(typeElement.asType());
        String codecClassName = JsonUtils.jsonReaderName(typeElement).replace("JsonReader", "JsonCodec");

        var typeBuilder = TypeSpec.classBuilder(codecClassName)
            .addAnnotation(AnnotationUtils.generated(JsonCodecGenerator.class))
            .addSuperinterface(ParameterizedTypeName.get(JsonTypes.jsonReader, targetType))
            .addSuperinterface(ParameterizedTypeName.get(JsonTypes.jsonWriter, targetType))
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addOriginatingElement(typeElement);

        for (TypeParameterElement typeParameter : typeElement.getTypeParameters()) {
            typeBuilder.addTypeVariable(TypeVariableName.get(typeParameter));
        }

        for (var field : writerMeta.fields()) {
            typeBuilder.addField(FieldSpec.builder(JsonTypes.serializedString, this.jsonNameStaticName(field.field().getSimpleName().toString()), Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer(CodeBlock.of("new $T($S)", JsonTypes.serializedString, field.jsonName()))
                .build());
        }

        this.addBitSet(typeBuilder, readerMeta);
        this.addCombinedDependencies(typeBuilder, readerMeta, writerMeta);
        this.addReadMethods(typeBuilder, readerMeta);
        this.addErrorMethods(typeBuilder, readerMeta);
        this.generateReadMethod(typeBuilder, readerMeta);
        this.generateWriteMethod(typeBuilder, writerMeta);

        return typeBuilder.build();
    }

    private void addCombinedDependencies(TypeSpec.Builder typeBuilder, JsonClassReaderMeta readerMeta, JsonClassWriterMeta writerMeta) {
        var constructor = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);
        var addedFields = new HashSet<String>();
        boolean hasParameters = false;

        for (var field : writerMeta.fields()) {
            if (field.writer() != null) {
                var fieldName = field.accessor().getSimpleName() + "Writer";
                if (addedFields.add(fieldName)) {
                    final TypeName fieldType;
                    if (field.writer().mapperClass() != null) {
                        fieldType = TypeName.get(field.writer().mapperClass());
                        var writerField = FieldSpec.builder(fieldType, fieldName, Modifier.PRIVATE, Modifier.FINAL);
                        var writerElement = (TypeElement) this.types.asElement(field.writer().mapperClass());
                        if (CommonUtils.hasDefaultConstructorAndFinal(writerElement)) {
                            writerField.addModifiers(Modifier.STATIC).initializer("new $T()", fieldType);
                            typeBuilder.addField(writerField.build());
                            continue;
                        }
                    } else {
                        fieldType = ParameterizedTypeName.get(JsonTypes.jsonReader, TypeName.get(field.writerTypeMeta().typeMirror()));
                    }
                    var writerField = FieldSpec.builder(fieldType, fieldName, Modifier.PRIVATE, Modifier.FINAL);
                    var fieldTag = field.writer().toTagAnnotation();
                    if (fieldTag != null) writerField.addAnnotation(fieldTag);

                    typeBuilder.addField(writerField.build());
                    constructor.addParameter(fieldType, fieldName).addStatement("this.$L = $L", fieldName, fieldName);
                    hasParameters = true;
                }
            } else if (field.writerTypeMeta() instanceof WriterFieldType.UnknownWriterFieldType) {
                var fieldName = field.accessor().getSimpleName() + "Writer";
                if (addedFields.add(fieldName)) {
                    var fieldType = ParameterizedTypeName.get(JsonTypes.jsonWriter, TypeName.get(field.writerTypeMeta().typeMirror()));
                    var writerField = FieldSpec.builder(fieldType, fieldName, Modifier.PRIVATE, Modifier.FINAL);
                    typeBuilder.addField(writerField.build());
                    constructor.addParameter(fieldType, fieldName).addStatement("this.$L = $L", fieldName, fieldName);
                    hasParameters = true;
                }
            }
        }

        for (var field : readerMeta.fields()) {
            if (field.reader() == null && field.typeMeta() instanceof KnownTypeReaderMeta) {
                continue;
            }
            var fieldName = field.parameter().getSimpleName() + "Reader";
            if (addedFields.add(fieldName)) {
                final TypeName fieldType;
                if (field.reader() != null && field.reader().mapperClass() != null) {
                    fieldType = TypeName.get(field.reader().mapperClass());
                    var readerField = FieldSpec.builder(fieldType, fieldName, Modifier.PRIVATE, Modifier.FINAL);
                    var readerElement = (TypeElement) this.types.asElement(field.reader().mapperClass());
                    if (CommonUtils.hasDefaultConstructorAndFinal(readerElement)) {
                        readerField.addModifiers(Modifier.STATIC).initializer("new $T()", fieldType);
                        typeBuilder.addField(readerField.build());
                        continue;
                    }
                } else {
                    fieldType = ParameterizedTypeName.get(JsonTypes.jsonReader, field.typeName());
                }
                var readerField = FieldSpec.builder(fieldType, fieldName, Modifier.PRIVATE, Modifier.FINAL);
                if (field.reader() != null) {
                    var fieldTag = field.reader().toTagAnnotation();
                    if (fieldTag != null) readerField.addAnnotation(fieldTag);
                }

                typeBuilder.addField(readerField.build());
                constructor.addParameter(fieldType, fieldName).addStatement("this.$L = $L", fieldName, fieldName);
                hasParameters = true;
            }
        }

        if (hasParameters) {
            typeBuilder.addMethod(constructor.build());
        }
    }

    private void generateWriteMethod(TypeSpec.Builder typeBuilder, JsonClassWriterMeta meta) {
        var method = MethodSpec.methodBuilder("write")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addParameter(JsonTypes.jsonGenerator, "_gen")
            .addParameter(ParameterSpec.builder(TypeName.get(meta.typeMirror()).annotated(CommonClassNames.nullableAnnotation), "_object").build())
            .addAnnotation(Override.class);

        method.addCode("if (_object == null) {$>\n_gen.writeNull();\nreturn;$<\n}\n");
        method.addStatement("_gen.writeStartObject(_object)");

        var discriminatorField = JsonUtils.discriminatorField(types, meta.typeElement());
        if (discriminatorField != null) {
            if (meta.fields().stream().noneMatch(f -> f.jsonName().equals(discriminatorField))) {
                var discriminatorFieldValues = JsonUtils.discriminatorValue(meta.typeElement());
                method.addCode("_gen.writeName($S);\n", discriminatorField);
                method.addStatement("_gen.writeString($S)", discriminatorFieldValues.getFirst());
            }
        }
        for (var field : meta.fields()) {
            this.addWriteParam(method, field);
        }
        method.addStatement("_gen.writeEndObject()");
        typeBuilder.addMethod(method.build());
    }

    private void generateReadMethod(TypeSpec.Builder typeBuilder, JsonClassReaderMeta meta) {
        var method = MethodSpec.methodBuilder("read")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addParameter(JsonTypes.jsonParser, "__parser")
            .addAnnotation(Override.class)
            .returns(TypeName.get(meta.typeElement().asType()).withoutAnnotations().annotated(CommonClassNames.nullableAnnotation));

        method.addStatement("var __token = __parser.currentToken()");
        method.addCode("if (__token == $T.VALUE_NULL) $>\nreturn null;$<\n", JsonTypes.jsonToken);
        assertTokenType(method, "START_OBJECT", "an object '{...}'");

        if (meta.fields().size() <= 32) {
            method.addStatement("int __receivedFields = NULLABLE_FIELDS_RECEIVED");
        } else {
            method.addStatement("var __receivedFields = ($T) NULLABLE_FIELDS_RECEIVED.clone()", BitSet.class);
        }
        method.addCode("\n");

        this.addFieldVariables(method, meta);
        this.addFastPath(method, meta);

        if (meta.fields().isEmpty()) {
            method.addStatement("__token = __parser.nextToken()");
        } else {
            method.addStatement("__token = __parser.currentToken()");
        }

        method.addCode("while (__token != $T.END_OBJECT) {$>\n", JsonTypes.jsonToken);
        assertTokenType(method, "PROPERTY_NAME", "a field name");
        method.addStatement("var __fieldName = __parser.currentName()");
        method.addCode("switch (__fieldName) {$>\n");
        for (int i = 0, fieldsSize = meta.fields().size(); i < fieldsSize; i++) {
            var field = meta.fields().get(i);
            method.addCode("case $S -> {$>\n", field.jsonName());
            method.addCode("$L = $L(__parser);\n", field.parameter(), this.readerMethodName(field));
            method.addCode(markReceived(meta, i));
            method.addCode("$<\n}\n");
        }
        method.addCode("default -> {$>\n__parser.nextToken();\n__parser.skipChildren();$<\n}");
        method.addCode("$<\n}\n");
        method.addCode("__token = __parser.nextToken();");
        method.addCode("$<\n}\n");

        var errorSwitch = CodeBlock.builder();
        for (int i = 0; i < meta.fields().size(); i++) {
            if (i == 0) {
                errorSwitch.add("(__i == 0) ? $S : ", meta.fields().get(i).jsonName());
            } else {
                errorSwitch.add("(__i == $L) ? $S : ", i, meta.fields().get(i).jsonName());
            }
        }
        errorSwitch.add("$S", "");

        if (meta.fields().size() > 32) {
            method.addCode("""
                if (!__receivedFields.equals(ALL_FIELDS_RECEIVED)) {
                    __receivedFields.flip(0, $L);
                    var __missing = new $T();
                    for (int __i = __receivedFields.nextSetBit(0); __i >= 0; __i = __receivedFields.nextSetBit(__i+1)) {
                        if (__missing.length() > 0) __missing.append(", ");
                        __missing.append($L);
                    }
                    throw __missingRequiredFields(__parser, __missing.toString());
                }
                """, meta.fields().size(), StringBuilder.class, errorSwitch.build());
        } else {
            method.addCode("""
                if (__receivedFields != ALL_FIELDS_RECEIVED) {
                    var _nonReceivedFields = (~__receivedFields) & ALL_FIELDS_RECEIVED;
                    var __missing = new $T();
                    for (int __i = 0; __i < $L; __i++) {
                        if ((_nonReceivedFields & (1 << __i)) != 0) {
                            if (__missing.length() > 0) __missing.append(", ");
                            __missing.append($L);
                        }
                    }
                    throw __missingRequiredFields(__parser, __missing.toString());
                }
                """, StringBuilder.class, meta.fields().size(), errorSwitch.build());
        }

        method.addCode("return new $T(", meta.typeElement());
        for (int i = 0; i < meta.fields().size(); i++) {
            method.addCode("$L", meta.fields().get(i).parameter().getSimpleName());
            if (i < meta.fields().size() - 1) method.addCode(", ");
        }
        method.addCode(");");

        typeBuilder.addMethod(method.build());
    }

    private void addFastPath(MethodSpec.Builder method, JsonClassReaderMeta meta) {
        for (int i = 0; i < meta.fields().size(); i++) {
            var field = meta.fields().get(i);
            method.addCode("if (__parser.nextName($L)) {$>\n", jsonNameStaticName(field.parameter().getSimpleName().toString()));
            method.addCode("$L = $L(__parser);\n", field.parameter(), readerMethodName(field));
            method.addCode(markReceived(meta, i));
            if (i == meta.fields().size() - 1) {
                method.addCode("""
                    __token = __parser.nextToken();
                    while (__token != JsonToken.END_OBJECT) {
                        __parser.nextToken();
                        __parser.skipChildren();
                        __token = __parser.nextToken();
                    }
                    """);
                method.addCode("return new $T(", meta.typeMirror());
                for (int j = 0; j < meta.fields().size(); j++) {
                    method.addCode("$L", meta.fields().get(j).parameter());
                    if (j < meta.fields().size() - 1) method.addCode(", ");
                }
                method.addCode(");$<\n");
            }
        }
        for (int i = 0; i < meta.fields().size(); i++) {
            method.addCode("}");
            if (i < meta.fields().size() - 1) method.addCode("$<");
            method.addCode("\n");
        }
    }

    private void addFieldVariables(MethodSpec.Builder method, JsonClassReaderMeta meta) {
        for (int i = 0; i < meta.fields().size(); i++) {
            var field = meta.fields().get(i);
            method.addCode("$T $L", field.parameter(), field.parameter().getSimpleName());
            var parameterType = field.parameter().asType();
            if (parameterType instanceof PrimitiveType) {
                if (parameterType.toString().equals("boolean")) {
                    method.addCode(" = false;\n");
                } else {
                    method.addCode(" = 0;\n");
                }
            } else if (field.typeMeta() != null && field.typeMeta().isJsonNullable()) {
                method.addCode(" = $T.undefined();\n", JsonTypes.jsonNullable);
            } else {
                method.addCode(" = null;\n");
            }
        }
    }

    private void addReadMethods(TypeSpec.Builder typeBuilder, JsonClassReaderMeta meta) {
        var fields = meta.fields();
        for (int i = 0; i < fields.size(); i++) {
            typeBuilder.addMethod(this.readParamMethod(i, fields.size(), fields.get(i)));
        }
    }

    private MethodSpec readParamMethod(int index, int size, JsonClassReaderMeta.FieldMeta field) {
        var method = MethodSpec.methodBuilder(this.readerMethodName(field))
            .addModifiers(Modifier.PRIVATE)
            .addParameter(JsonTypes.jsonParser, "__parser")
            .returns(field.typeName());
        if (field.reader() != null) {
            method.addCode("var __token = __parser.nextToken();\n");
            if (!isNullable(field)) {
                method.addCode("if (__token == $T.VALUE_NULL) throw __requiredFieldNull(__parser, $S);\n", JsonTypes.jsonToken, "." + field.jsonName());
            }
            method.addCode("return $L.read(__parser);\n", field.parameter().getSimpleName() + "Reader");
            return method.build();
        }
        method.addStatement("var __token = __parser.nextToken()");
        if (field.typeMeta() instanceof KnownTypeReaderMeta metaData) {
            method.addModifiers(Modifier.STATIC);
            var block = CodeBlock.builder();
            CodeBlock prefix = field.typeMeta().isJsonNullable() ? CodeBlock.of("return $T.ofNullable(", JsonTypes.jsonNullable) : CodeBlock.of("return ");
            CodeBlock suffix = field.typeMeta().isJsonNullable() ? CodeBlock.of(")") : CodeBlock.of("");
            block.add(readKnownType(field.jsonName(), prefix, suffix, metaData.knownType(), isNullable(field), field.typeMeta().isJsonNullable(), metaData.typeMirror()));
            method.addCode(block.build());
            return method.build();
        }

        var readerName = field.parameter().getSimpleName() + "Reader";
        if (field.typeMeta() != null && field.typeMeta().isJsonNullable()) {
            method.addCode("if (__token == $T.VALUE_NULL) { return $T.nullValue(); }\n", JsonTypes.jsonToken, JsonTypes.jsonNullable);
            method.addStatement("return $T.ofNullable($L.read(__parser))", JsonTypes.jsonNullable, readerName);
        } else if (isNullable(field)) {
            method.addCode("if (__token == $T.VALUE_NULL) { return null; }\n", JsonTypes.jsonToken);
            method.addStatement("return $L.read(__parser)", readerName);
        } else {
            method.addCode("if (__token == $T.VALUE_NULL) throw __requiredFieldNull(__parser, $S);\n", JsonTypes.jsonToken, "." + field.jsonName());
            method.addStatement("return $L.read(__parser)", readerName);
        }
        return method.build();
    }

    private void addBitSet(TypeSpec.Builder typeBuilder, JsonClassReaderMeta meta) {
        if (meta.fields().size() <= 32) {
            var sb = new StringBuilder();
            for (int i = meta.fields().size() - 1; i >= 0; i--) {
                sb.append(isNullable(meta.fields().get(i)) ? "1" : "0");
            }
            var nullableFieldsReceived = meta.fields().isEmpty() ? "0" : "0b" + sb;
            var allFieldsReceived = meta.fields().isEmpty() ? "0" : "0b" + "1".repeat(meta.fields().size());

            typeBuilder.addField(FieldSpec.builder(TypeName.INT, "ALL_FIELDS_RECEIVED", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL).initializer(CodeBlock.of(allFieldsReceived)).build())
                .addField(FieldSpec.builder(TypeName.INT, "NULLABLE_FIELDS_RECEIVED", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL).initializer(CodeBlock.of(nullableFieldsReceived)).build());
        } else {
            typeBuilder.addField(ClassName.get(BitSet.class), "ALL_FIELDS_RECEIVED", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .addField(ClassName.get(BitSet.class), "NULLABLE_FIELDS_RECEIVED", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
            var fieldReceivedInitBlock = CodeBlock.builder().add("""
                ALL_FIELDS_RECEIVED = new $T($L);
                ALL_FIELDS_RECEIVED.set(0, $L);
                NULLABLE_FIELDS_RECEIVED = new $T($L);
                """, BitSet.class, meta.fields().size(), meta.fields().size(), BitSet.class, meta.fields().size());
            for (int i = 0; i < meta.fields().size(); i++) {
                if (isNullable(meta.fields().get(i))) fieldReceivedInitBlock.add("NULLABLE_FIELDS_RECEIVED.set($L);\n", i);
            }
            typeBuilder.addStaticBlock(fieldReceivedInitBlock.build());
        }
    }

    private void addWriteParam(MethodSpec.Builder method, JsonClassWriterMeta.FieldMeta field) {
        String staticFieldName = this.jsonNameStaticName(field.field().getSimpleName().toString());
        String writerFieldName = field.accessor().getSimpleName() + "Writer";

        if (field.typeMirror().getKind().isPrimitive()) {
            method.addCode("_gen.writeName($L);\n", staticFieldName);
            if (field.writer() == null && field.writerTypeMeta() instanceof WriterFieldType.KnownWriterFieldType typeMeta) {
                method.addCode(this.writeKnownType(typeMeta.knownType(), CodeBlock.of("_object.$L", field.accessor())));
            } else {
                method.addStatement("$L.write(_gen, _object.$L)", writerFieldName, field.accessor());
            }
            return;
        }

        var isEmptyCheck = field.includeType() == JsonClassWriterMeta.IncludeType.NON_EMPTY
            && (CommonUtils.isCollection(field.writerTypeMeta().typeMirror()) || CommonUtils.isMap(field.writerTypeMeta().typeMirror()));

        if (field.writerTypeMeta().isJsonNullable()) {
            method.addCode("if (_object.$L.isDefined()) {$>\n", field.accessor());
            if (isEmptyCheck) method.beginControlFlow("if (_object.$L.value() != null && !_object.$L.value().isEmpty())", field.accessor(), field.accessor());
        } else if (isEmptyCheck) {
            method.addCode("if (_object.$L != null && !_object.$L.isEmpty()) {$>\n", field.accessor(), field.accessor());
        } else if (field.includeType() != JsonClassWriterMeta.IncludeType.ALWAYS) {
            method.addCode("if (_object.$L != null) {$>\n", field.accessor());
        }

        method.addCode("_gen.writeName($L);\n", staticFieldName);
        if (field.writer() == null && field.writerTypeMeta() instanceof WriterFieldType.KnownWriterFieldType typeMeta) {
            if (typeMeta.isJsonNullable()) {
                method.beginControlFlow("if (_object.$L.isNull())").addStatement("_gen.writeNull()").nextControlFlow("else")
                    .addCode(this.writeKnownType(typeMeta.knownType(), CodeBlock.of("_object.$L.value()", field.accessor()))).endControlFlow();
            } else if (field.includeType() == JsonClassWriterMeta.IncludeType.ALWAYS) {
                method.beginControlFlow("if (_object.$L == null)").addStatement("_gen.writeNull()").nextControlFlow("else")
                    .addCode(this.writeKnownType(typeMeta.knownType(), CodeBlock.of("_object.$L", field.accessor()))).endControlFlow();
            } else {
                method.addCode(this.writeKnownType(typeMeta.knownType(), CodeBlock.of("_object.$L", field.accessor())));
            }
        } else {
            if (field.writerTypeMeta().isJsonNullable()) {
                method.beginControlFlow("if (_object.$L.isNull())").addStatement("_gen.writeNull()").nextControlFlow("else")
                    .addStatement("$L.write(_gen, _object.$L.value())", writerFieldName, field.accessor()).endControlFlow();
            } else {
                method.addStatement("$L.write(_gen, _object.$L)", writerFieldName, field.accessor());
            }
        }

        if (field.writerTypeMeta().isJsonNullable()) {
            method.addCode("$<}\n");
            if (isEmptyCheck) method.addCode("$<}\n");
        } else if (field.includeType() != JsonClassWriterMeta.IncludeType.ALWAYS) {
            method.addCode("$<}\n");
        }
    }

    private CodeBlock writeKnownType(KnownType.KnownTypesEnum knownType, CodeBlock value) {
        return switch (knownType) {
            case STRING -> CodeBlock.of("_gen.writeString($L);\n", value);
            case BOOLEAN_OBJECT, BOOLEAN_PRIMITIVE -> CodeBlock.of("_gen.writeBoolean($L);\n", value);
            case INTEGER_OBJECT, BIG_INTEGER, DOUBLE_OBJECT, FLOAT_OBJECT, LONG_OBJECT, SHORT_OBJECT,
                 INTEGER_PRIMITIVE, DOUBLE_PRIMITIVE, FLOAT_PRIMITIVE, LONG_PRIMITIVE, SHORT_PRIMITIVE -> CodeBlock.of("_gen.writeNumber($L);\n", value);
            case BINARY -> CodeBlock.of("_gen.writeBinary($L);\n", value);
            case UUID -> CodeBlock.of("_gen.writeString($L.toString());\n", value);
        };
    }

    private CodeBlock readKnownType(String jsonName, CodeBlock prefix, CodeBlock suffix, KnownType.KnownTypesEnum knownType, boolean nullable, boolean jsonNullable, TypeMirror typeMirror) {
        var method = CodeBlock.builder();
        var code = switch (knownType) {
            case STRING -> CodeBlock.of("if (__token == $T.VALUE_STRING) { $L__parser.getString()$L; }", JsonTypes.jsonToken, prefix, suffix);
            case BOOLEAN_OBJECT, BOOLEAN_PRIMITIVE ->
                CodeBlock.of("if (__token == $T.VALUE_TRUE) { $Ltrue$L; } else if (__token == $T.VALUE_FALSE) { $Lfalse$L; }", JsonTypes.jsonToken, prefix, suffix, JsonTypes.jsonToken, prefix, suffix);
            case INTEGER_OBJECT, INTEGER_PRIMITIVE -> CodeBlock.of("if (__token == $T.VALUE_NUMBER_INT) { $L__parser.getIntValue()$L; }", JsonTypes.jsonToken, prefix, suffix);
            case BIG_INTEGER -> CodeBlock.of("if (__token == $T.VALUE_NUMBER_INT) { $L__parser.getBigIntegerValue()$L; }", JsonTypes.jsonToken, prefix, suffix);
            case DOUBLE_OBJECT, DOUBLE_PRIMITIVE ->
                CodeBlock.of("if (__token == $T.VALUE_NUMBER_FLOAT || __token == $T.VALUE_NUMBER_INT) { $L__parser.getDoubleValue()$L; }", JsonTypes.jsonToken, JsonTypes.jsonToken, prefix, suffix);
            case FLOAT_OBJECT, FLOAT_PRIMITIVE ->
                CodeBlock.of("if (__token == $T.VALUE_NUMBER_FLOAT || __token == $T.VALUE_NUMBER_INT) { $L__parser.getFloatValue()$L; }", JsonTypes.jsonToken, JsonTypes.jsonToken, prefix, suffix);
            case LONG_OBJECT, LONG_PRIMITIVE -> CodeBlock.of("if (__token == $T.VALUE_NUMBER_INT) { $L__parser.getLongValue()$L; }", JsonTypes.jsonToken, prefix, suffix);
            case SHORT_OBJECT, SHORT_PRIMITIVE -> CodeBlock.of("if (__token == $T.VALUE_NUMBER_INT) { $L__parser.getShortValue()$L; }", JsonTypes.jsonToken, prefix, suffix);
            case BINARY -> CodeBlock.of("if (__token == $T.VALUE_STRING) { $L__parser.getBinaryValue()$L; }", JsonTypes.jsonToken, prefix, suffix);
            case UUID -> CodeBlock.of("if (__token == $T.VALUE_STRING) { $L$T.fromString(__parser.getString())$L; }", JsonTypes.jsonToken, prefix, UUID.class, suffix);
        };
        method.add(code);
        if (jsonNullable) {
            method.add(" else if (__token == $T.VALUE_NULL) {$>\nreturn $T.nullValue();$<\n}", JsonTypes.jsonToken, JsonTypes.jsonNullable);
        } else if (nullable) {
            method.add(" else if (__token == $T.VALUE_NULL) {$>\nreturn null;$<\n}", JsonTypes.jsonToken);
        } else {
            method.add(" else if (__token == $T.VALUE_NULL) {$>\nthrow __requiredFieldNull(__parser, $S);$<\n}", JsonTypes.jsonToken, "." + jsonName);
        }
        method.add(" else {$>\nthrow __unexpectedToken(__parser, $S, $S);$<\n}", "." + jsonName, expectedPhrase(knownType));
        return method.build();
    }

    private void addErrorMethods(TypeSpec.Builder typeBuilder, JsonClassReaderMeta meta) {
        var typeName = meta.typeElement().getSimpleName().toString();
        typeBuilder.addMethod(MethodSpec.methodBuilder("__jsonPath").addModifiers(Modifier.PRIVATE, Modifier.STATIC).addParameter(JsonTypes.jsonParser, "__parser").returns(String.class)
            .addStatement("var __p = __parser.streamReadContext().pathAsPointer().toString()").addStatement("return __p.isEmpty() ? $S : __p", "<root>").build());

        var actual = MethodSpec.methodBuilder("__actualValue").addModifiers(Modifier.PRIVATE, Modifier.STATIC).addParameter(JsonTypes.jsonParser, "__parser").returns(String.class);
        actual.addStatement("var __t = __parser.currentToken()");
        actual.beginControlFlow("if (__t == null)").addStatement("return $S", "nothing (end of input)").endControlFlow();
        actual.addStatement("var __v = __parser.getValueAsString()");
        actual.beginControlFlow("if (__v != null && __v.length() > 128)").addStatement("__v = __v.substring(0, 128) + $S", "...(truncated)").endControlFlow();

        actual.beginControlFlow("if (__t == $T.VALUE_NULL)", JsonTypes.jsonToken).addStatement("return $S", "null")
            .nextControlFlow("else if (__t == $T.START_OBJECT)", JsonTypes.jsonToken).addStatement("return $S", "an object")
            .nextControlFlow("else if (__t == $T.START_ARRAY)", JsonTypes.jsonToken).addStatement("return $S", "an array")
            .nextControlFlow("else if (__t == $T.VALUE_STRING)", JsonTypes.jsonToken).addStatement("return $S + __v + $S", "a string \"", "\"")
            .nextControlFlow("else if (__t == $T.VALUE_NUMBER_INT)", JsonTypes.jsonToken).addStatement("return $S + __v", "a number ")
            .nextControlFlow("else if (__t == $T.VALUE_NUMBER_FLOAT)", JsonTypes.jsonToken).addStatement("return $S + __v", "a fractional number ")
            .nextControlFlow("else if (__t == $T.VALUE_TRUE || __t == $T.VALUE_FALSE)", JsonTypes.jsonToken, JsonTypes.jsonToken).addStatement("return $S + __v", "a boolean ")
            .nextControlFlow("else").addStatement("return $S + __t", "token ").endControlFlow();

        typeBuilder.addMethod(actual.build());

        typeBuilder.addMethod(MethodSpec.methodBuilder("__unexpectedToken").addModifiers(Modifier.PRIVATE, Modifier.STATIC).addParameter(JsonTypes.jsonParser, "__parser").addParameter(String.class, "__member").addParameter(String.class, "__expected").returns(JsonTypes.jsonParseException)
            .addStatement("return new $T(__parser, $S + __member + $S + __expected + $S + __actualValue(__parser) + $S + __jsonPath(__parser) + $S)", JsonTypes.jsonParseException, "Failed to read json " + typeName, ": expected ", ", but got ", " (at ", ")").build());

        typeBuilder.addMethod(MethodSpec.methodBuilder("__missingRequiredFields").addModifiers(Modifier.PRIVATE, Modifier.STATIC).addParameter(JsonTypes.jsonParser, "__parser").addParameter(String.class, "__fields").returns(JsonTypes.jsonParseException)
            .addStatement("return new $T(__parser, $S + __fields + $S + __jsonPath(__parser) + $S)", JsonTypes.jsonParseException, "Failed to read json " + typeName + ": missing required field(s): ", " (at ", ")").build());

        if (meta.fields().stream().anyMatch(f -> !isNullable(f))) {
            typeBuilder.addMethod(MethodSpec.methodBuilder("__requiredFieldNull").addModifiers(Modifier.PRIVATE, Modifier.STATIC).addParameter(JsonTypes.jsonParser, "__parser").addParameter(String.class, "__member").returns(JsonTypes.jsonParseException)
                .addStatement("return new $T(__parser, $S + __member + $S + __jsonPath(__parser) + $S)", JsonTypes.jsonParseException, "Failed to read json " + typeName, ": required field must not be null (at ", ")").build());
        }
    }

    private boolean isNullable(JsonClassReaderMeta.FieldMeta field) {
        if (field.parameter().asType().getKind().isPrimitive()) return false;
        if (field.typeMeta() != null && field.typeMeta().isJsonNullable()) return true;
        return CommonUtils.isNullable(field.parameter());
    }

    private void assertTokenType(MethodSpec.Builder method, String expectedToken, String expectedPhrase) {
        method.addCode("if (__token != $T.$L) $>\nthrow __unexpectedToken(__parser, $S, $S);$<\n", JsonTypes.jsonToken, expectedToken, "", expectedPhrase);
    }

    private CodeBlock markReceived(JsonClassReaderMeta meta, int index) {
        return meta.fields().size() > 32 ? CodeBlock.of("__receivedFields.set($L);\n", index) : CodeBlock.of("__receivedFields |= (1 << $L);\n", index);
    }

    private String jsonNameStaticName(String name) {
        return "_" + name + "optimized_field_name";
    }

    private String readerMethodName(JsonClassReaderMeta.FieldMeta field) {return "read" + field.parameter().getSimpleName();}

    private String expectedPhrase(KnownType.KnownTypesEnum knownType) {
        return switch (knownType) {
            case STRING -> "a string";
            case BINARY -> "a base64-encoded string";
            case UUID -> "a UUID string";
            case BOOLEAN_OBJECT, BOOLEAN_PRIMITIVE -> "a boolean";
            case SHORT_OBJECT, SHORT_PRIMITIVE, INTEGER_OBJECT, INTEGER_PRIMITIVE, LONG_OBJECT, LONG_PRIMITIVE, BIG_INTEGER -> "an integer number";
            case DOUBLE_OBJECT, DOUBLE_PRIMITIVE, FLOAT_OBJECT, FLOAT_PRIMITIVE -> "a number";
        };
    }
}
