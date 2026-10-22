package io.koraframework.json.annotation.processor;

import com.palantir.javapoet.JavaFile;
import io.koraframework.annotation.processor.common.CommonUtils;
import io.koraframework.annotation.processor.common.ComparableTypeMirror;
import io.koraframework.json.annotation.processor.codec.JsonCodecGenerator;
import io.koraframework.json.annotation.processor.codec.JsonEnumCodecGenerator;
import io.koraframework.json.annotation.processor.codec.JsonDelegatingCodecGenerator;
import io.koraframework.json.annotation.processor.codec.JsonSealedCodecGenerator;
import io.koraframework.json.annotation.processor.reader.ReaderTypeMetaParser;
import io.koraframework.json.annotation.processor.writer.WriterTypeMetaParser;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

public class JsonProcessor {
    private final ProcessingEnvironment processingEnv;
    private final Elements elements;
    private final Types types;
    private final ReaderTypeMetaParser readerTypeMetaParser;
    private final WriterTypeMetaParser writerTypeMetaParser;
    private final JsonCodecGenerator codecGenerator;
    private final JsonSealedCodecGenerator sealedCodecGenerator;
    private final JsonEnumCodecGenerator enumCodecGenerator;
    private final JsonDelegatingCodecGenerator delegatingCodecGenerator;
    private final Set<String> generatedCodecs = new HashSet<>();

    public JsonProcessor(ProcessingEnvironment processingEnv) {
        this.processingEnv = processingEnv;
        this.elements = processingEnv.getElementUtils();
        this.types = processingEnv.getTypeUtils();
        var knownTypes = new KnownType();
        this.readerTypeMetaParser = new ReaderTypeMetaParser(this.processingEnv, knownTypes);
        this.writerTypeMetaParser = new WriterTypeMetaParser(processingEnv, knownTypes);
        this.codecGenerator = new JsonCodecGenerator(this.processingEnv);
        this.sealedCodecGenerator = new JsonSealedCodecGenerator(this.processingEnv);
        this.enumCodecGenerator = new JsonEnumCodecGenerator();
        this.delegatingCodecGenerator = new JsonDelegatingCodecGenerator();
    }

    public void generateReader(TypeElement jsonElement) {
        var jsonElementType = jsonElement.asType();
        var packageElement = JsonUtils.jsonClassPackage(this.elements, jsonElement);
        var codecClassName = JsonUtils.jsonReaderName(this.types, jsonElementType).replace("JsonReader", "JsonCodec");
        var fullCodecName = packageElement + "." + codecClassName;

        if (!generatedCodecs.add(fullCodecName) || this.elements.getTypeElement(fullCodecName) != null) {
            return;
        }
        if (jsonElement.getKind() == ElementKind.ENUM) {
            this.generateEnumCodec(jsonElement);
            return;
        }
        if (jsonElement.getModifiers().contains(Modifier.SEALED)) {
            this.generateSealedCodec(jsonElement);
            return;
        }
        if (this.delegatingCodecGenerator.detectReaderFactory(jsonElement) != null || this.delegatingCodecGenerator.detectWriterMethod(jsonElement) != null) {
            var codecType = this.delegatingCodecGenerator.generate(jsonElement);
            if (codecType != null) {
                CommonUtils.safeWriteTo(this.processingEnv, JavaFile.builder(packageElement, codecType).build());
            }
            return;
        }
        this.generateDtoCodec(jsonElement, jsonElementType);
    }
    public void generateWriter(TypeElement jsonElement) {
        var wrapper = new ComparableTypeMirror(this.types, jsonElement.asType());
        var packageElement = JsonUtils.jsonClassPackage(this.elements, jsonElement);
        var codecClassName = JsonUtils.jsonWriterName(this.types, wrapper.typeMirror()).replace("JsonWriter", "JsonCodec");
        var fullCodecName = packageElement + "." + codecClassName;

        if (!generatedCodecs.add(fullCodecName) || this.elements.getTypeElement(fullCodecName) != null) {
            return;
        }
        if (jsonElement.getKind() == ElementKind.ENUM) {
            this.generateEnumCodec(jsonElement);
            return;
        }
        if (jsonElement.getModifiers().contains(Modifier.SEALED)) {
            this.generateSealedCodec(jsonElement);
            return;
        }
        if (this.delegatingCodecGenerator.detectReaderFactory(jsonElement) != null || this.delegatingCodecGenerator.detectWriterMethod(jsonElement) != null) {
            var codecType = this.delegatingCodecGenerator.generate(jsonElement);
            if (codecType != null) {
                CommonUtils.safeWriteTo(this.processingEnv, JavaFile.builder(packageElement, codecType).build());
            }
            return;
        }
        this.generateDtoCodec(jsonElement, jsonElement.asType());
    }

    private void generateDtoCodec(TypeElement typeElement, TypeMirror jsonTypeMirror) {
        var packageElement = JsonUtils.jsonClassPackage(this.elements, typeElement);
        var readerMeta = Objects.requireNonNull(this.readerTypeMetaParser.parse(typeElement, jsonTypeMirror));
        var writerMeta = Objects.requireNonNull(this.writerTypeMetaParser.parse(typeElement, jsonTypeMirror));
        var codecType = Objects.requireNonNull(this.codecGenerator.generate(readerMeta, writerMeta));

        var javaFile = JavaFile.builder(packageElement, codecType).build();
        CommonUtils.safeWriteTo(this.processingEnv, javaFile);
    }

    private void generateEnumCodec(TypeElement jsonElement) {
        var packageElement = JsonUtils.jsonClassPackage(this.elements, jsonElement);
        var codecType = this.enumCodecGenerator.generateForEnum(jsonElement);

        var javaFile = JavaFile.builder(packageElement, codecType).build();
        CommonUtils.safeWriteTo(this.processingEnv, javaFile);
    }

    private void generateSealedCodec(TypeElement jsonElement) {
        var packageElement = JsonUtils.jsonClassPackage(this.elements, jsonElement);
        var sealedCodecType = this.sealedCodecGenerator.generateSealedCodec(jsonElement);

        var javaFile = JavaFile.builder(packageElement, sealedCodecType).build();
        CommonUtils.safeWriteTo(this.processingEnv, javaFile);
    }
}
