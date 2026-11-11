/*
 * This file is part of SuperLyric.

 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.

 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.processor;

import com.google.auto.service.AutoService;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/**
 * 读取 {@code @HookThis} 并生成 {@code HookMaps}
 *
 * <p>按 {@code targetPackage} 分组，生成 {@code ON_PACKAGE_LOADED} 与
 * {@code ON_SYSTEM_STARTING} 两个常量表。
 *
 */
@AutoService(Processor.class)
@SupportedAnnotationTypes("com.hchen.processor.HookThis")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class HookProcessor extends AbstractProcessor {
    private final Map<String, List<HookData>> maps = new TreeMap<>();
    private final Set<String> seenTypes = new HashSet<>();

    /**
     * 是否已经生成过 {@code HookMaps}
     *
     * <p>注解处理器可能被调用多轮；{@code JavaFile.writeTo(filer)} 对同一个类名写第二次会抛
     * {@code FilerException}，因此只写一次
     */
    private boolean generated;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
        if (env.processingOver()) return true;

        Set<? extends Element> annotated = env.getElementsAnnotatedWith(HookThis.class);
        for (Element element : annotated) {
            TypeElement typeElement = (TypeElement) element;
            String typeName = typeElement.getQualifiedName().toString();
            boolean newType = seenTypes.add(typeName);
            if (generated && newType) {
                // javac 不允许在最后一轮写新的 class 文件，首轮之后出现的 @HookThis
                // 无法再并入已有的 HookMaps；显式报错，不能静默漏掉 Provider
                processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@HookThis element appeared after HookMaps was generated: " + typeName
                );
            }
            HookThis hook = typeElement.getAnnotation(HookThis.class);
            maps
                .computeIfAbsent(hook.targetPackage(), k -> new ArrayList<>())
                .add(new HookData(typeName, hook.onPackageLoaded(), hook.onSystemStarting()));
        }

        if (!generated && !annotated.isEmpty()) {
            try {
                generateHookData();
                generated = true;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        return true;
    }

    private void generateHookData() throws IOException {
        TypeName listOfString = ParameterizedTypeName.get(ClassName.get(List.class), ClassName.get(String.class));
        TypeName mapType =
            ParameterizedTypeName.get(
                ClassName.get(Map.class),
                ClassName.get(String.class),
                listOfString
            );

        TypeSpec.Builder clazz =
            TypeSpec.classBuilder("HookMaps")
                .addModifiers(Modifier.PUBLIC)
                .addJavadoc("注解处理器自动生成的 Map 图\n");

        clazz.addField(generateStaticMap(
            "ON_SYSTEM_STARTING",
            maps,
            hookData -> hookData.onSystemStarting,
            mapType
        ));

        clazz.addField(generateStaticMap(
            "ON_PACKAGE_LOADED",
            maps,
            hookData -> hookData.onPackageLoaded,
            mapType
        ));

        JavaFile.builder("com.hchen.processor", clazz.build())
            .build()
            .writeTo(processingEnv.getFiler());
    }

    /**
     * 生成 static final Map 常量
     *
     * <p>按包名聚合类路径，过滤条件不成立的条目不会出现在结果中。
     */
    private FieldSpec generateStaticMap(
        String fieldName,
        Map<String, List<HookData>> dataMap,
        Predicate<HookData> filter,
        TypeName mapType
    ) {
        List<CodeBlock> entries = new ArrayList<>();
        dataMap.forEach((pkg, data) -> {
            CodeBlock values = CodeBlock.join(
                data.stream()
                    .filter(filter)
                    .map(d -> CodeBlock.of("$S", d.classPath))
                    .toList(),
                ", "
            );
            if (values.isEmpty()) return;

            entries.add(CodeBlock.of("$T.entry($S, $T.of($L))", Map.class, pkg, List.class, values));
        });

        return FieldSpec.builder(mapType, fieldName, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .initializer(CodeBlock.of("$T.ofEntries(\n$L\n)", Map.class, CodeBlock.join(entries, ",\n")))
            .build();
    }

    private record HookData(
        String classPath,
        boolean onPackageLoaded,
        boolean onSystemStarting
    ) {
    }
}
