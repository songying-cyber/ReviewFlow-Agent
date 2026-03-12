package com.paicli.rag;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 代码分析器：基于 JavaParser AST 构建代码关系图谱
 * <p>
 * 提取的关系类型：
 * - extends：类继承
 * - implements：接口实现
 * - imports：导入依赖
 * - calls：方法调用（简化版，只记录同项目内的调用）
 * - contains：类包含方法
 */
public class CodeAnalyzer {
    private final JavaParser parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    /**
     * 分析单个 Java 文件，提取所有代码关系
     */
    public List<CodeRelation> analyzeFile(Path filePath) throws IOException {
        String content = Files.readString(filePath);
        String relativePath = filePath.toString();
        List<CodeRelation> relations = new ArrayList<>();

        ParseResult<CompilationUnit> result = parser.parse(content);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            return relations;
        }

        CompilationUnit cu = result.getResult().get();

        // 提取导入关系
        extractImports(relativePath, cu, relations);

        // 提取类级别关系（extends, implements, contains）
        extractClassRelations(relativePath, cu, relations);

        return relations;
    }

    private void extractImports(String filePath, CompilationUnit cu, List<CodeRelation> relations) {
        for (ImportDeclaration imp : cu.getImports()) {
            String importName = imp.getNameAsString();
            String simpleName = importName.substring(importName.lastIndexOf('.') + 1);
            // 只记录非 JDK 导入（作为项目内依赖的近似判断）
            if (!importName.startsWith("java.") && !importName.startsWith("javax.")) {
                relations.add(new CodeRelation(
                        filePath, "file", null, simpleName, "imports"));
            }
        }
    }

    private void extractClassRelations(String filePath, CompilationUnit cu, List<CodeRelation> relations) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            String className = clazz.getNameAsString();

            // extends 关系
            clazz.getExtendedTypes().forEach(ext -> {
                relations.add(new CodeRelation(
                        filePath, className, null, ext.getNameAsString(), "extends"));
            });

            // implements 关系
            clazz.getImplementedTypes().forEach(impl -> {
                relations.add(new CodeRelation(
                        filePath, className, null, impl.getNameAsString(), "implements"));
            });

            // contains 关系：类包含方法
            clazz.getMethods().forEach(method -> {
                String methodName = method.getNameAsString();
                relations.add(new CodeRelation(
                        filePath, className, filePath, className + "." + methodName, "contains"));
            });

            // calls 关系：方法调用（简化处理，只记录方法名）
            clazz.findAll(MethodCallExpr.class).forEach(call -> {
                String callee = call.getNameAsString();
                // 尝试获取调用者方法
                Optional<MethodDeclaration> parentMethod = findParentMethod(call);
                if (parentMethod.isPresent()) {
                    String caller = className + "." + parentMethod.get().getNameAsString();
                    relations.add(new CodeRelation(
                            filePath, caller, null, callee, "calls"));
                }
            });
        });
    }

    private Optional<MethodDeclaration> findParentMethod(Node node) {
        Node current = node;
        while (current != null) {
            if (current instanceof MethodDeclaration method) {
                return Optional.of(method);
            }
            current = current.getParentNode().orElse(null);
        }
        return Optional.empty();
    }
}
