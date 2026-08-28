package com.csh.blogwriter.domain.model

import java.io.File

data class PreparedImage(val ref: String, val file: File, val width: Int, val height: Int)

data class PublishJob(val id: String, val content: PostContent, val images: List<PreparedImage>)
