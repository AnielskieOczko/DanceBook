package com.jankowski.rafal.dancebook.model

import jakarta.persistence.*
import java.util.UUID

@Entity
@Table(name = "choreography_entry")
class ChoreographyEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "choreography_id", nullable = false)
    var choreography: Choreography? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    var entryType: EntryType = EntryType.FIGURE

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dance_figure_id")
    var danceFigure: DanceFigure? = null

    @Column(name = "section_label")
    var sectionLabel: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "line_indicator")
    var lineIndicator: LineIndicator? = null

    var notes: String? = null

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0
}
