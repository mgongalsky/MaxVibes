package com.maxvibes.application.port.output

import com.maxvibes.domain.model.planning.PlanDiagram

/**
 * Output port: renders a [PlanDiagram] into a renderer-specific textual form.
 *
 * The signature is deliberately UI-agnostic — no Swing/JCEF types: the first
 * adapter emits Mermaid source, a future ELK/Swing adapter may emit SVG or
 * another textual payload; both fit `render(PlanDiagram) -> String`.
 */
interface DiagramRenderer {
    /** Stable renderer id used for selector state and persistence. */
    val id: String

    /** Human-readable name shown in the renderer selector combo box. */
    val displayName: String

    /** Renders the diagram into the adapter's textual output format. */
    fun render(diagram: PlanDiagram): String
}
