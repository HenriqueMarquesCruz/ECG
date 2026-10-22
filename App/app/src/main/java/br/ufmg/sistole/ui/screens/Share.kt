package br.ufmg.sistole.ui.screens

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Abre a folha de compartilhamento do Android para um arquivo em cache/exports. */
fun shareFile(ctx: Context, file: File, mime: String, subject: String) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, subject)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Compartilhar").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
