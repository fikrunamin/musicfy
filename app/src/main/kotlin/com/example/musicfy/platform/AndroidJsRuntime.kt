package com.example.musicfy.platform

import android.content.Context
import com.example.musicfy.core.resolver.CipherSession
import com.example.musicfy.core.resolver.JsRuntime
import com.example.musicfy.core.resolver.NSolverSession
import com.example.musicfy.utils.cipher.CipherWebView
import com.example.musicfy.utils.cipher.FunctionNameExtractor
import com.example.musicfy.utils.sabr.EjsSolverWebView

/** The same WebViews (and application context) CipherDeobfuscator / EjsNTransformSolver used before. */
class AndroidJsRuntime(context: Context) : JsRuntime {
    private val appContext = context.applicationContext

    override suspend fun loadCipher(
        playerJs: String,
        sigInfo: FunctionNameExtractor.SigFunctionInfo,
        nFuncInfo: FunctionNameExtractor.NFunctionInfo?,
    ): CipherSession = CipherWebView.create(
        context = appContext,
        playerJs = playerJs,
        sigInfo = sigInfo,
        nFuncInfo = nFuncInfo,
    )

    override suspend fun loadNSolver(playerJs: String): NSolverSession = EjsSolverWebView.create(appContext, playerJs)
}
