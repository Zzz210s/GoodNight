package com.goodnight.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/** 备份写入结果(区分"授权失效"与一般写入失败,便于给用户正确提示) */
enum class BackupWriteResult { OK, PERMISSION_DENIED, FAILED }

/**
 * 备份写盘:在用户选定的 SAF 目录(tree Uri)下写入固定文件,已存在则覆盖 ——
 * 避免每次备份产生 (1)(2) 副本(用户要求覆盖,而非叠加版本)。自动/手动备份共用。
 *
 * 用 DocumentFile.listFiles().find{name} 而非 findFile:部分 provider 对 createFile 的
 * displayName/mime 解析不一致,findFile 可能匹配不上同名文件而重复 createFile 生成 (1)(2)。
 *
 * v1.11.2 修复:**必须先删除再新建**。此前直接 openOutputStream 覆盖,在外部存储 provider 上
 * 不会截断(新的 JSON 比旧的短时,旧内容尾部残留)→ 备份文件损坏、无法解析(实测踩到)。
 *
 * v1.13.0:返回 [BackupWriteResult] 而非 Boolean —— SecurityException 明确归类为
 * "授权失效"(对应"重新选择备份目录"),其余归为一般写入失败。此前一律 false,
 * 用户只看到"备份失败",分不清是权限丢了还是目录不可写。
 */
object BackupWriter {
    const val FILE_NAME = "goodnight-backup.json"
    private const val MIME = "application/json"

    fun write(context: Context, treeUri: Uri, json: String): BackupWriteResult = try {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return BackupWriteResult.FAILED
        // 覆盖 = 删除同名文件后重新创建(确保截断,不受 provider 的 append 语义影响)
        tree.listFiles().filter { it.name == FILE_NAME }.forEach { it.delete() }
        val file = tree.createFile(MIME, FILE_NAME) ?: return BackupWriteResult.FAILED
        val written = context.contentResolver.openOutputStream(file.uri)?.use {
            it.write(json.toByteArray(Charsets.UTF_8))
        } != null
        if (written) BackupWriteResult.OK else BackupWriteResult.FAILED
    } catch (e: Throwable) {
        classify(e)
    }

    /** 失败归类(纯函数,便于单测):SecurityException = 授权失效,其余 = 一般写入失败 */
    fun classify(e: Throwable): BackupWriteResult =
        if (e is SecurityException) BackupWriteResult.PERMISSION_DENIED else BackupWriteResult.FAILED
}
