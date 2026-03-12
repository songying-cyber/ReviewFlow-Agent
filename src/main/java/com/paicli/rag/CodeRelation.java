package com.paicli.rag;

/**
 * 代码关系数据模型（用于构建代码关系图谱）
 *
 * @param fromFile     源文件路径
 * @param fromName     源名称（类名或方法名）
 * @param toFile       目标文件路径
 * @param toName       目标名称
 * @param relationType 关系类型：extends / implements / imports / calls / contains
 */
public record CodeRelation(String fromFile, String fromName,
                           String toFile, String toName, String relationType) {
}
