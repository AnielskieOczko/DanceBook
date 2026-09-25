package com.jankowski.rafal.dancebook.model

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "uploaded_file")
class UploadedFile(
    @Id
    @Column(name = "drive_file_id", nullable = false)
    var driveFileId: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploader_id", nullable = false)
    var uploader: AppUser? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
)
