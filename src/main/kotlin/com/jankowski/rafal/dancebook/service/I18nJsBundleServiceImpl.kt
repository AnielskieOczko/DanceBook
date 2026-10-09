package com.jankowski.rafal.dancebook.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.MessageSource
import org.springframework.stereotype.Service
import java.util.Locale
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

@Service
class I18nJsBundleServiceImpl(
    private val messageSource: MessageSource,
    private val objectMapper: ObjectMapper = ObjectMapper()
) : I18nJsBundleService {

    private val cache = ConcurrentHashMap<String, String>()
    private val mapCache = ConcurrentHashMap<String, Map<String, String>>()

    private val jsKeys: Set<String> by lazy {
        loadJsKeys()
    }

    private fun loadJsKeys(): Set<String> {
        val keys = mutableSetOf<String>()
        val props = Properties()
        javaClass.classLoader.getResourceAsStream("messages.properties")?.use {
            props.load(it)
        }
        for (name in props.stringPropertyNames()) {
            if (name.startsWith("js.") || name in SHARED_JS_KEYS) {
                keys.add(name)
            }
        }
        keys.addAll(SHARED_JS_KEYS)
        return keys
    }

    override fun getBundleMap(locale: Locale): Map<String, String> {
        val lang = locale.language.lowercase()
        return mapCache.computeIfAbsent(lang) {
            val map = LinkedHashMap<String, String>()
            for (key in jsKeys) {
                val msg = messageSource.getMessage(key, null, key, locale) ?: key
                map[key] = msg
            }
            map
        }
    }

    override fun getBundleJson(locale: Locale): String {
        val lang = locale.language.lowercase()
        return cache.computeIfAbsent(lang) {
            val map = getBundleMap(locale)
            objectMapper.writeValueAsString(map)
        }
    }

    companion object {
        val SHARED_JS_KEYS = setOf(
            "common.close",
            "common.dismiss",
            "common.retry",
            "assistant.dictate",
            "assistant.stop_dictating",
            "notes.form.drive.upload",
            "notes.form.drive.uploading",
            "notes.form.drive.select_file",
            "figures.role.leader",
            "figures.role.follower",
            "figures.form.steps.combination_name",
            "figures.form.steps.combination_name_placeholder",
            "figures.form.steps.mark_default",
            "figures.form.steps.delete_combination",
            "figures.form.steps.steps_list",
            "figures.form.steps.th.timing",
            "figures.form.steps.th.foot",
            "figures.form.steps.th.action",
            "figures.form.steps.th.footwork",
            "figures.form.steps.th.alignment",
            "figures.form.steps.th.turn",
            "figures.form.steps.th.comments",
            "figures.form.steps.add_leader_step",
            "figures.form.steps.add_follower_step",
            "figures.form.steps.remove_step",
            "figures.form.steps.show_technical_details",
            "figures.form.links.title_placeholder",
            "figures.form.links.type.video",
            "figures.form.links.type.syllabus",
            "figures.form.links.type.other",
            "figures.form.links.remove",
            "figures.guided.select_recent_url",
            "figures.form.field.name",
            "figures.form.field.dance_type",
            "figures.view.field.minimum_level",
            "figures.form.field.alternative_timing",
            "figures.form.field.starting_position",
            "figures.form.field.ending_position",
            "figures.form.field.starting_foot_leader",
            "figures.form.field.ending_foot_leader",
            "figures.form.field.starting_foot_follower",
            "figures.form.field.ending_foot_follower",
            "figures.form.field.preceding_figures",
            "figures.form.field.following_figures",
            "figures.guided.field.notes_details",
            "figures.view.class_label",
            "training.form.scope.this_event_hint",
            "training.form.scope.hint"
        )
    }
}
