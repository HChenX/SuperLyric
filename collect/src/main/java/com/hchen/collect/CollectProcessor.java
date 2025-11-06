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

 * Copyright (C) 2023-2025 HChenX
 */
package com.hchen.collect;

import com.google.auto.service.AutoService;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

/**
 * 注解处理
 *
 * @author 焕晨HChen
 */
@AutoService(Processor.class)
@SupportedAnnotationTypes("com.hchen.collect.Collect")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class CollectProcessor extends AbstractProcessor {
    boolean isProcessed = false;

    @Override
    public boolean process(Set<? extends TypeElement> set, RoundEnvironment roundEnvironment) {
        if (isProcessed) return true;
        isProcessed = true;

        HashMap<String, List<CollectCache>> collectMap = new HashMap<>();
        roundEnvironment.getElementsAnnotatedWith(Collect.class).forEach(new Consumer<Element>() {
            @Override
            public void accept(Element element) {
                String fullClassName = null;
                if (element instanceof TypeElement typeElement) {
                    fullClassName = typeElement.getQualifiedName().toString();
                    if (fullClassName == null)
                        throw new RuntimeException("E: Full class name is null!!");
                } else
                    throw new RuntimeException("E: element can't cast to TypeElement!!");

                Collect collect = element.getAnnotation(Collect.class);
                String targetPackage = collect.targetPackage();
                boolean onLoadPackage = collect.onLoadPackage();
                boolean onZygote = collect.onZygote();
                boolean onApplication = collect.onApplication();

                if (collectMap.get(targetPackage) == null) {
                    ArrayList<CollectCache> collectCacheList = new ArrayList<>();
                    collectCacheList.add(new CollectCache(fullClassName, onLoadPackage, onZygote, onApplication));
                    collectMap.put(targetPackage, collectCacheList);
                } else {
                    ArrayList<CollectCache> collectCacheList = (ArrayList<CollectCache>) collectMap.get(targetPackage);
                    collectCacheList.add(new CollectCache(fullClassName, onLoadPackage, onZygote, onApplication));
                }
            }
        });

        try (Writer writer = processingEnv.getFiler().createSourceFile("com.hchen.collect.CollectMap").openWriter()) {
            writer.write("""
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
                 *
                 * Copyright (C) 2023-2025 HChenX
                 */
                package com.hchen.collect;
                
                import java.util.ArrayList;
                import java.util.Arrays;
                import java.util.HashMap;
                import java.util.List;
                import java.util.Set;
                import java.util.HashSet;
                
                /**
                 * 注解处理器自动生成的 Map 图
                 *
                 * @author 焕晨HChen
                 */
                public class CollectMap {
                
                    public static HashMap<String, List<String>> getOnLoadPackageMap() {
                        HashMap<String, List<String>> collectOnLoadPackageMap = new HashMap<>();
                """);
            collectMap.forEach(new BiConsumer<String, List<CollectCache>>() {
                @Override
                public void accept(String targetPackage, List<CollectCache> collectCaches) {
                    ArrayList<String> onLoadPackageList = collectCaches.stream()
                        .filter(collectCache -> collectCache.onLoadPackage)
                        .map(collectCache -> collectCache.fullClassName)
                        .collect(Collectors.toCollection(ArrayList::new));
                    try {
                        writer.write("        ");
                        writer.write("collectOnLoadPackageMap.put(\"" + targetPackage + "\", toList(\"" + onLoadPackageList + "\"));\n");
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });
            writer.write("""
                        return collectOnLoadPackageMap;
                    }
                
                    public static HashMap<String, List<String>> getOnZygoteList() {
                        HashMap<String, List<String>> collectOnZygoteMap = new HashMap<>();
                """);
            collectMap.forEach(new BiConsumer<String, List<CollectCache>>() {
                @Override
                public void accept(String targetPackage, List<CollectCache> collectCaches) {
                    ArrayList<String> onZygoteList = collectCaches.stream()
                        .filter(collectCache -> collectCache.onZygote)
                        .map(collectCache -> collectCache.fullClassName)
                        .collect(Collectors.toCollection(ArrayList::new));
                    try {
                        writer.write("        ");
                        writer.write("collectOnZygoteMap.put(\"" + targetPackage + "\", toList(\"" + onZygoteList + "\"));\n");
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });
            writer.write("""
                        return collectOnZygoteMap;
                    }
                
                    public static HashMap<String, List<String>> getOnApplicationMap() {
                        HashMap<String, List<String>> collectOnApplicationMap = new HashMap<>();
                """);
            collectMap.forEach(new BiConsumer<String, List<CollectCache>>() {
                @Override
                public void accept(String targetPackage, List<CollectCache> collectCaches) {
                    ArrayList<String> onApplicationList = collectCaches.stream()
                        .filter(collectCache -> collectCache.onApplication)
                        .map(collectCache -> collectCache.fullClassName)
                        .collect(Collectors.toCollection(ArrayList::new));
                    try {
                        writer.write("        ");
                        writer.write("collectOnApplicationMap.put(\"" + targetPackage + "\", toList(\"" + onApplicationList + "\"));\n");
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });

            writer.write("""
                        return collectOnApplicationMap;
                    }
                
                    public static Set<String> getTargetPackages() {
                        HashSet<String> set = new HashSet();
                """);
            collectMap.keySet().forEach(new Consumer<String>() {
                @Override
                public void accept(String targetPackage) {
                    try {
                        writer.write("        ");
                        writer.write("set.add(\"" + targetPackage + "\");\n");
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });

            writer.write("""
                        return set;
                    }
                
                    private static List<String> toList(String fullClassNames) {
                        String[] fullClassNameArray = fullClassNames.replace("[", "")
                                    .replace("]", "")
                                    .replace(" ", "")
                                    .split(",");
                
                        if (fullClassNameArray.length == 0) return new ArrayList<>();
                        fullClassNameArray =  Arrays.stream(fullClassNameArray).filter(s -> !s.isEmpty()).toArray(String[]::new);
                        return new ArrayList<>(List.of(fullClassNameArray));
                    }
                
                    private static List<String> getMapNotNull(HashMap<String, List<String>> map, String key) {
                        List<String> value = map.get(key);
                        return value == null ? new ArrayList<String>() : value;
                    }
                }
                """);
        } catch (IOException e) {
            e.printStackTrace();
        }

        return true;
    }

    private record CollectCache(String fullClassName, boolean onLoadPackage, boolean onZygote,
                                boolean onApplication) {
    }
}
