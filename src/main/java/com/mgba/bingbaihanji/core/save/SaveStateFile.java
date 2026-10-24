package com.mgba.bingbaihanji.core.save;

/**
 * 解析后的即时存档内容。
 *
 * @param formatVersion 容器格式版本
 * @param timestamp     保存时间（Unix 毫秒）
 * @param coreName      保存时的核心名称
 * @param coreVersion   保存时的核心版本
 * @param romId         ROM 短 ID（SHA-256 前 16 位）
 * @param data          核心序列化数据
 */
public record SaveStateFile(
        int formatVersion,
        long timestamp,
        String coreName,
        String coreVersion,
        String romId,
        byte[] data) {
}
