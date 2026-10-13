package dev.wholivedwhen.release

import com.github.victools.jsonschema.generator.Option
import com.github.victools.jsonschema.generator.OptionPreset
import com.github.victools.jsonschema.generator.SchemaGenerator
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder
import com.github.victools.jsonschema.generator.SchemaVersion
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.reflect.full.primaryConstructor

/**
 * The JSON Schema of the release format, generated from its records so the two cannot drift apart.
 * `./gradlew :core:releaseSchema` writes it to sample/release.schema.json; ReleaseSchemaTests checks it is current.
 */
object ReleaseSchema {

    fun generate(): String {
        val config = SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
            .with(Option.DEFINITIONS_FOR_ALL_OBJECTS, Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT)
        // Kotlin says what is required: a constructor parameter without a default value.
        config.forFields().withRequiredCheck { field ->
            field.declaringType.erasedType.kotlin.primaryConstructor?.parameters
                ?.firstOrNull { it.name == field.declaredName }?.isOptional == false
        }
        // dataset/ holds the workbench's export, in a format of its own (its manifest says which).
        config.forFields().withIgnoreCheck { it.declaringType.erasedType == Release::class.java && it.declaredName == "dataset" }
        val schema = SchemaGenerator(config.build()).generateSchema(Release::class.java)
        schema.put("title", "Who Lived When release")
        schema.put(
            "description",
            "A release as a whole. On disk, each array is a JSON Lines file of the same name and each moment a file " +
                "in moments/: validate a line or a file against its record in \$defs. The fetched dataset in dataset/ is " +
                "not described here. See sample/README.md.",
        )
        return schema.toPrettyString() + "\n"
    }
}

fun main(args: Array<String>) {
    Path.of(args.single()).writeText(ReleaseSchema.generate())
}
