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
package com.hchen.auto;

import com.google.auto.service.AutoService;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

/**
 * 注解处理
 *
 * @author 焕晨HChen
 */
@AutoService(Processor.class)
@SupportedAnnotationTypes("com.hchen.auto.AutoHook")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class AutoProcessor extends AbstractProcessor {
    private final Map<String, List<HookData>> dataMap = new HashMap<>();

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
        System.out.println("ENV: " + env);
        if (env.processingOver()) return true;

        for (Element element : env.getElementsAnnotatedWith(AutoHook.class)) {
            if (!(element instanceof TypeElement typeElement)) {
                throw new RuntimeException("Element can't cast to TypeElement.");
            }

            AutoHook autoHook = element.getAnnotation(AutoHook.class);
            if (autoHook != null) {
                dataMap
                    .computeIfAbsent(autoHook.targetPackage(), k -> new ArrayList<>())
                    .add(
                        new HookData(
                            typeElement.getQualifiedName().toString(),
                            autoHook.onPackageLoaded(),
                            autoHook.onSystemStarting()
                        )
                    );
            }
        }

        if (!env.getElementsAnnotatedWith(AutoHook.class).isEmpty()) {
            try {
                generateHookData();
            } catch (IOException e) {
                throw new RuntimeException(e);
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
            TypeSpec.classBuilder("HookData")
                .addModifiers(Modifier.PUBLIC)
                .addJavadoc("注解处理器自动生成的 Map 图\n");

        clazz.addField(generateStaticMap(
            "ON_SYSTEM_STARTING",
            dataMap,
            hookData -> hookData.onSystemStarting,
            mapType
        ));

        clazz.addField(generateStaticMap(
            "ON_PACKAGE_LOADED",
            dataMap,
            hookData -> hookData.onPackageLoaded,
            mapType
        ));

        JavaFile.builder("com.hchen.auto", clazz.build())
            .build()
            .writeTo(processingEnv.getFiler());
    }

    /*
     * 生成 static final Map 常量
     */
    private FieldSpec generateStaticMap(
        String fieldName,
        Map<String, List<HookData>> dataMap,
        Predicate<HookData> filter,
        TypeName mapType
    ) {
        CodeBlock.Builder mapInit = CodeBlock.builder();
        mapInit.add("$T.ofEntries(\n", Map.class);

        List<String> entries = new ArrayList<>();
        dataMap.forEach((pkg, data) -> {
            @SuppressWarnings("NewApi")
            List<String> values = data.stream()
                .filter(filter)
                .map(d -> d.classPath)
                .toList();

            if (values.isEmpty()) return;

            CodeBlock.Builder entry = CodeBlock.builder();
            entry.add("$T.entry($S, $T.of(", Map.class, pkg, List.class);

            for (int i = 0; i < values.size(); i++) {
                entry.add("$S", values.get(i));
                if (i != values.size() - 1) {
                    entry.add(", ");
                }
            }

            entry.add("))");

            entries.add(entry.build().toString());
        });

        for (int i = 0; i < entries.size(); i++) {
            mapInit.add(entries.get(i));
            if (i != entries.size() - 1) {
                mapInit.add(",\n");
            }
        }

        mapInit.add("\n)");

        return FieldSpec.builder(mapType, fieldName, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .initializer(mapInit.build())
            .build();
    }

    private record HookData(
        String classPath,
        boolean onPackageLoaded,
        boolean onSystemStarting
    ) {
    }
}
