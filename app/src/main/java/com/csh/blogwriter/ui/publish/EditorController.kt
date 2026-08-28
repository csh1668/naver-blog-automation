package com.csh.blogwriter.ui.publish

import com.csh.blogwriter.domain.model.PreparedImage

/** ViewModel 이 WebView 에 내리는 명령. 실제 구현은 NaverEditorWebView, 테스트는 가짜. */
interface EditorController {
    fun loadEditor(blogId: String)
    fun setLocalImages(images: List<PreparedImage>)
    fun installBridgeScript()
    fun checkReady()
    fun dismissPopups()
    fun uploadImages(refs: List<String>)
    fun setDocument(documentJson: String)
}

/** ImagePreparer 의 테스트용 추상화. Uri 는 문자열로 받는다. */
interface ImagePreparing {
    suspend fun prepare(jobId: String, uris: List<String>, onProgress: (Int) -> Unit): List<PreparedImage>
    fun load(jobId: String, paths: List<String>): List<PreparedImage>?
    fun clear(jobId: String)
}
