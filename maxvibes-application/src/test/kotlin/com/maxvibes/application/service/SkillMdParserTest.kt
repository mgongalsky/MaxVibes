package com.maxvibes.application.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SkillMdParserTest {

    @Test
    fun `no frontmatter - whole text is body, no spec`() {
        val parsed = SkillMdParser.parse("Just a body\nSecond line")
        assertNull(parsed.name)
        assertNull(parsed.editorSpec)
        assertEquals("Just a body\nSecond line", parsed.body)
    }

    @Test
    fun `single-line keys parsed as before`() {
        val text = "---\nname: my-skill\ndescription: \"Does things\"\n---\nBody here"
        val parsed = SkillMdParser.parse(text)
        assertEquals("my-skill", parsed.name)
        assertEquals("Does things", parsed.description)
        assertNull(parsed.editorSpec)
        assertEquals("Body here", parsed.body)
    }

    @Test
    fun `applies-to produces editor spec, normalized to lowercase`() {
        val text = "---\nname: s\napplies-to: function, Class\n---\nB"
        val spec = SkillMdParser.parse(text).editorSpec
        assertNotNull(spec)
        assertEquals(setOf("function", "class"), spec!!.appliesTo)
        assertFalse(spec.attachElement)
        assertNull(spec.template)
    }

    @Test
    fun `block scalar template survives with placeholders and inner blank line`() {
        val text =
            "---\napplies-to: function\neditor-template: |\n  Line one {{elementPath}}\n\n  Line two {{elementName}}\n---\nB"
        val spec = SkillMdParser.parse(text).editorSpec
        assertNotNull(spec)
        assertEquals("Line one {{elementPath}}\n\nLine two {{elementName}}", spec!!.template)
    }

    @Test
    fun `attach-element parses true and rejects garbage`() {
        val t1 = "---\napplies-to: any\nattach-element: true\n---\nB"
        assertTrue(SkillMdParser.parse(t1).editorSpec!!.attachElement)
        val t2 = "---\napplies-to: any\nattach-element: yes-ish\n---\nB"
        assertFalse(SkillMdParser.parse(t2).editorSpec!!.attachElement)
    }

    @Test
    fun `unknown keys ignored, unclosed frontmatter falls back to whole text`() {
        val known = SkillMdParser.parse("---\nname: n\nfancy-key: x\n---\nB")
        assertEquals("n", known.name)
        assertEquals("B", known.body)
        val unclosed = SkillMdParser.parse("---\nname: n\nno closing")
        assertNull(unclosed.name)
        assertTrue(unclosed.body.startsWith("---"))
    }

    @Test
    fun `template without applies-to gives no spec`() {
        val text = "---\neditor-template: |\n  Hello\n---\nB"
        assertNull(SkillMdParser.parse(text).editorSpec)
    }
}
